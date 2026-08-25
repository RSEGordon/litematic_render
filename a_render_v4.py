#!/usr/bin/env python3
"""Litematic orthographic model renderer (V4).

V4 keeps V3's report layout, but renders the actual rectangles in Minecraft's
block model ``elements``.  Thin and non-cubic blocks therefore retain their
shape in the front and side projections.
"""

import json
import math
import subprocess
import sys
import tempfile
from collections import Counter
from functools import lru_cache
from pathlib import Path

import litemapy
from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageFont

import a_render_v3 as v3

ROOT = Path(__file__).parent
ASSETS = ROOT / "client_assets/assets/minecraft"
MODEL_DIR = ASSETS / "models/block"
BLOCKSTATE_DIR = ASSETS / "blockstates"
TEXTURE_DIR = ASSETS / "textures/block"
ITEM_TEXTURE_DIR = ASSETS / "textures/item"


def _piston_extension_model(platform: str) -> dict:
    """Return Minecraft's dynamic piston arm as an in-code virtual model."""
    return {
        "textures": {"platform": platform, "side": "piston_side"},
        "elements": [
            {
                "from": [7, 0, 0], "to": [9, 16, 2], "shade": False,
                "faces": {
                    "down": {"uv": [7, 0, 9, 2], "texture": "#side"},
                    "up": {"uv": [7, 14, 9, 16], "texture": "#side"},
                    "north": {"uv": [7, 0, 9, 16], "texture": "#side"},
                    "south": {"uv": [7, 0, 9, 16], "texture": "#side"},
                    "west": {"uv": [0, 0, 2, 16], "texture": "#side"},
                    "east": {"uv": [14, 0, 16, 16], "texture": "#side"},
                },
            },
            {
                "from": [6, 0, 2], "to": [10, 4, 4], "shade": False,
                "faces": {
                    "down": {"uv": [6, 12, 10, 14], "texture": "#side"},
                    "up": {"uv": [6, 2, 10, 4], "texture": "#side"},
                    "north": {"uv": [6, 12, 10, 16], "texture": "#platform"},
                    "south": {"uv": [6, 12, 10, 16], "texture": "#side"},
                    "west": {"uv": [12, 12, 14, 16], "texture": "#side"},
                    "east": {"uv": [2, 12, 4, 16], "texture": "#side"},
                },
            },
        ],
    }


_PISTON_EXTENSION_MODEL_STICKY = _piston_extension_model("piston_top_sticky")
_PISTON_EXTENSION_MODEL_NORMAL = _piston_extension_model("piston_top")


def _strip_model(value: str) -> str:
    return value.replace("minecraft:", "").removeprefix("block/")


def _strip_texture(value: str) -> str:
    return value.replace("minecraft:", "").removeprefix("block/")


def _props(block) -> dict:
    raw = block.properties() if hasattr(block, "properties") else {}
    return {str(k): str(v).lower() for k, v in dict(raw or {}).items()}


@lru_cache(maxsize=None)
def load_model(model_id: str) -> dict | None:
    """Resolve parent models, texture references, and inherited elements."""
    path = MODEL_DIR / f"{_strip_model(model_id)}.json"
    if not path.exists():
        return None
    raw = json.loads(path.read_text())
    parent = load_model(raw["parent"]) if raw.get("parent") else None
    textures = dict(parent.get("textures", {})) if parent else {}
    textures.update(raw.get("textures", {}))

    def resolve_texture(value):
        seen = set()
        while isinstance(value, str) and value.startswith("#") and value not in seen:
            seen.add(value)
            value = textures.get(value[1:], value)
        return _strip_texture(value) if isinstance(value, str) else value

    textures = {key: resolve_texture(value) for key, value in textures.items()}
    elements = raw.get("elements")
    if elements is None and parent:
        elements = parent.get("elements", [])
    return {"textures": textures, "elements": elements or []}


def _variant_value(value) -> dict | None:
    if isinstance(value, list):
        value = value[0] if value else None
    return value if isinstance(value, dict) else None


@lru_cache(maxsize=None)
def _blockstate(name: str) -> dict | None:
    path = BLOCKSTATE_DIR / f"{name}.json"
    return json.loads(path.read_text()) if path.exists() else None


def resolve_variant(block) -> dict | None:
    """Return the matching model together with its JSON x/y rotations."""
    name = block.id.replace("minecraft:", "")
    state = _blockstate(name)
    if not state or not state.get("variants"):
        return None
    properties = _props(block)
    for key, value in state["variants"].items():
        wanted = dict(part.split("=", 1) for part in key.split(",") if "=" in part)
        if all(properties.get(k) == v for k, v in wanted.items()):
            return _variant_value(value)
    return _variant_value(next(iter(state["variants"].values())))


def _rotate_point(point, x_rotation=0, y_rotation=0):
    """Rotate a model point around the block centre in MC's 90-degree steps."""
    x, y, z = (coordinate - 8 for coordinate in point)
    for _ in range((x_rotation % 360) // 90):
        y, z = -z, y
    for _ in range((y_rotation % 360) // 90):
        x, z = -z, x
    return x + 8, y + 8, z + 8


def _rotate_normal(face, x_rotation=0, y_rotation=0):
    normals = {
        "west": (-1, 0, 0), "east": (1, 0, 0),
        "down": (0, -1, 0), "up": (0, 1, 0),
        "north": (0, 0, -1), "south": (0, 0, 1),
    }
    x, y, z = normals[face]
    for _ in range((x_rotation % 360) // 90):
        y, z = -z, y
    for _ in range((y_rotation % 360) // 90):
        x, z = -z, x
    return next(name for name, normal in normals.items() if normal == (x, y, z))


def _texture_name(face_def: dict, textures: dict) -> str | None:
    value = face_def.get("texture")
    seen = set()
    while isinstance(value, str) and value.startswith("#") and value not in seen:
        seen.add(value)
        value = textures.get(value[1:])
    return _strip_texture(value) if value else None


def _crop_uv(texture: Image.Image, face_def: dict) -> Image.Image:
    uv = face_def.get("uv")
    if not uv:
        return texture.copy()
    x0, y0, x1, y1 = uv
    flip_x, flip_y = x1 < x0, y1 < y0
    box = tuple(round(v) for v in (min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)))
    image = texture.crop(box)
    if flip_x:
        image = image.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    if flip_y:
        image = image.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    rotations = int(face_def.get("rotation", 0)) // 90
    return image.rotate(-90 * rotations, expand=True) if rotations else image


def _rotate_vector(vector, x_rotation=0, y_rotation=0):
    """Rotate a direction vector with the same transform as model points."""
    x, y, z = vector
    for _ in range((x_rotation % 360) // 90):
        y, z = -z, y
    for _ in range((y_rotation % 360) // 90):
        x, z = -z, x
    return x, y, z


def _rotate_element(value, rotation: dict | None, *, point: bool):
    """Apply an element's own (possibly 22.5/45 degree) model rotation."""
    if not rotation:
        return value
    origin = rotation.get("origin", [8, 8, 8]) if point else (0, 0, 0)
    x, y, z = (coordinate - centre for coordinate, centre in zip(value, origin))
    angle = math.radians(float(rotation.get("angle", 0)))
    cosine, sine = math.cos(angle), math.sin(angle)
    axis = rotation.get("axis")
    if axis == "x":
        y, z = y * cosine - z * sine, y * sine + z * cosine
    elif axis == "y":
        x, z = x * cosine + z * sine, -x * sine + z * cosine
    elif axis == "z":
        x, y = x * cosine - y * sine, x * sine + y * cosine
    return tuple(coordinate + centre for coordinate, centre in zip((x, y, z), origin))


def _orient_variant_uv(image: Image.Image, model_face: str, view_face: str,
                       x_rotation: int, y_rotation: int) -> Image.Image:
    """Rotate/flip face UVs together with the blockstate model transform."""
    # These are the world-space directions represented by image-right and
    # image-down in this renderer's three orthographic projections.
    face_basis = {
        "up": ((1, 0, 0), (0, 0, 1)),
        "down": ((1, 0, 0), (0, 0, 1)),
        "north": ((1, 0, 0), (0, -1, 0)),
        "south": ((1, 0, 0), (0, -1, 0)),
        "west": ((0, 0, 1), (0, -1, 0)),
        "east": ((0, 0, 1), (0, -1, 0)),
    }
    source_right, source_down = face_basis[model_face]
    target_right, target_down = face_basis[view_face]
    source_right = _rotate_vector(source_right, x_rotation, y_rotation)
    source_down = _rotate_vector(source_down, x_rotation, y_rotation)

    def screen_vector(vector):
        return (sum(a * b for a, b in zip(vector, target_right)),
                sum(a * b for a, b in zip(vector, target_down)))

    mapping = screen_vector(source_right), screen_vector(source_down)
    transforms = {
        ((1, 0), (0, 1)): None,
        ((0, 1), (-1, 0)): Image.Transpose.ROTATE_270,
        ((-1, 0), (0, -1)): Image.Transpose.ROTATE_180,
        ((0, -1), (1, 0)): Image.Transpose.ROTATE_90,
        ((-1, 0), (0, 1)): Image.Transpose.FLIP_LEFT_RIGHT,
        ((1, 0), (0, -1)): Image.Transpose.FLIP_TOP_BOTTOM,
        ((0, 1), (1, 0)): Image.Transpose.TRANSPOSE,
        ((0, -1), (-1, 0)): Image.Transpose.TRANSVERSE,
    }
    transform = transforms[mapping]
    return image.transpose(transform) if transform is not None else image


def _project_box(corners, view_face: str):
    xs, ys, zs = zip(*corners)
    if view_face in ("up", "down"):
        return min(xs), min(zs), max(xs), max(zs), max(ys)
    if view_face in ("north", "south"):
        return min(xs), 16 - max(ys), max(xs), 16 - min(ys), max(zs) if view_face == "south" else -min(zs)
    return min(zs), 16 - max(ys), max(zs), 16 - min(ys), max(xs) if view_face == "east" else -min(xs)


def render_model_face(block, view_face: str, px: int) -> Image.Image:
    """Render all visible element faces into one transparent block cell."""
    variant = resolve_variant(block)
    if not variant or not variant.get("model"):
        return _fallback_cell(block, view_face, px)
    model = load_model(variant["model"])
    if not model or not model["elements"]:
        return _fallback_cell(block, view_face, px)
    xr, yr = int(variant.get("x", 0)), int(variant.get("y", 0))
    drawables = []
    view_normal = {
        "west": (-1, 0, 0), "east": (1, 0, 0),
        "down": (0, -1, 0), "up": (0, 1, 0),
        "north": (0, 0, -1), "south": (0, 0, 1),
    }[view_face]
    for element in model["elements"]:
        lo, hi = element.get("from", [0, 0, 0]), element.get("to", [16, 16, 16])
        element_rotation = element.get("rotation")
        corners = [_rotate_point(_rotate_element((x, y, z), element_rotation, point=True), xr, yr)
                   for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
        rect = _project_box(corners, view_face)
        for model_face, face_def in element.get("faces", {}).items():
            normal = {
                "west": (-1, 0, 0), "east": (1, 0, 0),
                "down": (0, -1, 0), "up": (0, 1, 0),
                "north": (0, 0, -1), "south": (0, 0, 1),
            }[model_face]
            normal = _rotate_element(normal, element_rotation, point=False)
            normal = _rotate_vector(normal, xr, yr)
            if sum(a * b for a, b in zip(normal, view_normal)) <= 1e-6:
                continue
            tex_name = _texture_name(face_def, model["textures"])
            if tex_name:
                drawables.append((rect[4], rect[:4], tex_name, model_face,
                                  face_def, bool(element_rotation)))
    if not drawables:
        return _fallback_cell(block, view_face, px)
    cell = Image.new("RGBA", (px, px))
    scale = px / 16
    for _, (x0, y0, x1, y1), tex_name, model_face, face_def, locally_rotated in sorted(drawables):
        left, top = round(x0 * scale), round(y0 * scale)
        right, bottom = round(x1 * scale), round(y1 * scale)
        # Zero-thickness planes still occupy one pixel in an orthographic view.
        right, bottom = max(right, left + 1), max(bottom, top + 1)
        texture = _crop_uv(v3.load_texture_rgba(tex_name), face_def)
        if not locally_rotated:
            texture = _orient_variant_uv(texture, model_face, view_face, xr, yr)
        texture = texture.resize((right - left, bottom - top), Image.Resampling.NEAREST)
        cell.alpha_composite(texture, (left, top))
    return cell


def _render_piston_extension(face: str, px: int, sticky: bool,
                             facing: str) -> Image.Image:
    """Render the virtual extension model in the base block's cell."""
    rotations = {
        "down": (90, 0), "east": (0, 90), "north": (0, 0),
        "south": (0, 180), "up": (270, 0), "west": (0, 270),
    }
    xr, yr = rotations[facing]
    model = (_PISTON_EXTENSION_MODEL_STICKY if sticky
             else _PISTON_EXTENSION_MODEL_NORMAL)
    drawables = []
    for element in model["elements"]:
        lo, hi = element["from"], element["to"]
        corners = [_rotate_point((x, y, z), xr, yr)
                   for x in (lo[0], hi[0]) for y in (lo[1], hi[1])
                   for z in (lo[2], hi[2])]
        rect = _project_box(corners, face)
        for model_face, face_def in element["faces"].items():
            if _rotate_normal(model_face, xr, yr) != face:
                continue
            tex_name = _texture_name(face_def, model["textures"])
            if tex_name:
                drawables.append((rect[4], rect[:4], tex_name,
                                  model_face, face_def))

    cell = Image.new("RGBA", (px, px))
    scale = px / 16
    for _, (x0, y0, x1, y1), tex_name, model_face, face_def in sorted(drawables):
        left, top = round(x0 * scale), round(y0 * scale)
        right, bottom = round(x1 * scale), round(y1 * scale)
        right, bottom = max(right, left + 1), max(bottom, top + 1)
        texture = _crop_uv(v3.load_texture_rgba(tex_name), face_def)
        texture = _orient_variant_uv(texture, model_face, face, xr, yr)
        texture = texture.resize((right - left, bottom - top),
                                 Image.Resampling.NEAREST)
        cell.alpha_composite(texture, (left, top))
    return cell


def _redstone_color(power: int):
    strength = max(0, min(15, power)) / 15
    return (round(255 * (strength * .6 + (.4 if power else .3))),
            round(255 * max(strength * strength * .7 - .5, 0)),
            round(255 * max(strength * strength * .6 - .7, 0)), 255)


def _tinted_texture(name: str, color) -> Image.Image:
    source = v3.load_texture_rgba(name)
    alpha = source.getchannel("A")
    # The dust texture is white/grey; multiply it by MC's power tint.
    solid = Image.new("RGBA", source.size, color)
    brightness = source.convert("L")
    solid = ImageEnhance.Brightness(solid).enhance(0.85)
    solid.putalpha(Image.composite(alpha, Image.new("L", source.size), brightness))
    return solid


def render_redstone(block, view_face: str, px: int) -> Image.Image:
    properties = _props(block)
    if view_face != "up":
        # Dust occupies only the bottom 1/16 of a front/side cell.
        cell = Image.new("RGBA", (px, px))
        color = _redstone_color(int(properties.get("power", 0)))
        thickness = max(1, round(px / 16))
        strip = Image.new("RGBA", (px, thickness), color)
        cell.alpha_composite(strip, (0, px - thickness))
        return cell
    color = _redstone_color(int(properties.get("power", 0)))
    directions = {key: properties.get(key, "none") for key in ("north", "east", "south", "west")}
    connected = [key for key, value in directions.items() if value != "none"]
    canvas = Image.new("RGBA", (16, 16))
    # Multipart rule: dot is present for isolation, corners, tees, and crosses.
    opposite_line = set(connected) in ({"north", "south"}, {"east", "west"})
    if not connected or not opposite_line:
        canvas.alpha_composite(_tinted_texture("redstone_dust_dot", color))
    vertical = _tinted_texture("redstone_dust_line0", color)
    for direction in connected:
        arm = vertical
        if direction == "south":
            arm = arm.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
        elif direction == "east":
            arm = arm.rotate(-90)
        elif direction == "west":
            arm = arm.rotate(90)
        # Keep only the centre-to-edge half of each line model.
        mask = Image.new("L", (16, 16))
        if direction == "north": mask.paste(255, (0, 0, 16, 9))
        if direction == "south": mask.paste(255, (0, 7, 16, 16))
        if direction == "west": mask.paste(255, (0, 0, 9, 16))
        if direction == "east": mask.paste(255, (7, 0, 16, 16))
        arm.putalpha(Image.composite(arm.getchannel("A"), Image.new("L", (16, 16)), mask))
        canvas.alpha_composite(arm)
    return canvas.resize((px, px), Image.Resampling.NEAREST)


FALLBACK_EXACT = {
    "lever": "lever", "tripwire": "tripwire", "scaffolding": "scaffolding_top",
    "target": "target_top", "bell": "bell_body", "lightning_rod": "lightning_rod",
    "cake": "cake_top", "dispenser": "dispenser_front", "dropper": "dropper_front",
    "hopper": "hopper_outside", "chest": "oak_planks", "trapped_chest": "oak_planks",
    "ender_chest": "obsidian", "barrel": "barrel_side", "redstone_lamp": "redstone_lamp",
    "soul_sand": "soul_sand", "observer": "observer_side",
    "composter": "composter_side",
    "bubble_column": "water_still", "chain": "iron_chain",
    "spruce_wall_sign": "spruce_planks", "player_head": "smooth_stone",
    "moving_piston": "piston_side",
}


def _fallback_texture(name: str, face: str) -> str | None:
    """Return the first real block texture alias, never a missing filename."""
    candidates = []
    if name in FALLBACK_EXACT:
        candidates.append(FALLBACK_EXACT[name])
    if "shulker_box" in name:
        candidates.append(name.replace("_shulker_box", "_shulker_box"))
    if name.endswith("_stained_glass_pane"):
        candidates.append(name.removesuffix("_pane"))
    if name == "glass_pane":
        candidates.append("glass")
    if name.endswith("_coral_wall_fan"):
        candidates.append(name.replace("_wall_fan", "_fan"))
    if name.endswith("_wall_sign"):
        candidates.append(f"{name.removesuffix('_wall_sign')}_planks")
    suffixes = (
        "_wall", "_fence", "_stairs", "_door", "_sign", "_trapdoor", "_slab",
        "_pressure_plate", "_button", "_coral", "_leaves", "_log", "_wood",
        "_planks", "_glazed_terracotta",
    )
    for suffix in suffixes:
        if name.endswith(suffix):
            base = name[:-len(suffix)]
            candidates.extend([name, f"{base}_planks", f"{base}_log", base])
    candidates.append(name)
    return next((candidate for candidate in candidates
                 if (TEXTURE_DIR / f"{candidate}.png").exists()), None)


def _resource_texture_paths(name: str):
    return (
        ITEM_TEXTURE_DIR / f"{name}.png",
        ITEM_TEXTURE_DIR / f"{name}_inventory.png",
        TEXTURE_DIR / f"{name}.png",
        TEXTURE_DIR / f"{name}_top.png",
    )


def _purple_checker(size: int) -> Image.Image:
    image = Image.new("RGB", (size, size), (122, 31, 162))
    step = max(1, size // 4)
    draw = ImageDraw.Draw(image)
    for coordinate in range(0, size, step):
        draw.line((coordinate, 0, coordinate, size - 1), fill=(0, 0, 0))
        draw.line((0, coordinate, size - 1, coordinate), fill=(0, 0, 0))
    return image.convert("RGBA")


def _fallback_cell(block, face: str, px: int) -> Image.Image:
    name = block.id.replace("minecraft:", "")
    if name == "water":
        # The extracted client assets only retain water's animation metadata.
        return Image.new("RGBA", (px, px), (35, 105, 230, 255))
    if name.endswith("_door"):
        half = _props(block).get("half", "bottom")
        half = "top" if half == "upper" else "bottom"
        candidate = f"{name}_{half}"
        if (TEXTURE_DIR / f"{candidate}.png").exists():
            return v3.load_texture_rgba(candidate).resize((px, px), Image.Resampling.NEAREST)
    texture = _fallback_texture(name, face)
    if texture:
        return v3.load_texture_rgba(texture).resize((px, px), Image.Resampling.NEAREST)
    for path in _resource_texture_paths(name):
        if path.exists():
            return Image.open(path).convert("RGBA").resize((px, px), Image.Resampling.NEAREST)
    if face != "up":
        top = render_model_face(block, "up", px)
        if top.getbbox():
            return top
    return _purple_checker(px)


def block_cell(block, face: str, px: int) -> Image.Image:
    if block.id == "minecraft:redstone_wire":
        return render_redstone(block, face, px)
    if block.id.replace("minecraft:", "") in {"rail", "powered_rail", "detector_rail", "activator_rail"} and face != "up":
        # Rail models are zero-thickness planes with no horizontal face.  They
        # must be shown edge-on, not replaced by a full-cell fallback texture.
        cell = Image.new("RGBA", (px, px))
        thickness = max(1, round(px / 16))
        properties = _props(block)
        texture_name = block.id.replace("minecraft:", "")
        if properties.get("powered") == "true" and texture_name != "rail":
            texture_name += "_on"
        texture = v3.load_texture_rgba(texture_name).resize((px, thickness), Image.Resampling.NEAREST)
        cell.alpha_composite(texture, (0, px - thickness))
        return cell
    return render_model_face(block, face, px)


def _apply_alpha(image: Image.Image, opacity: float) -> Image.Image:
    image = image.copy()
    image.putalpha(image.getchannel("A").point(lambda value: round(value * opacity)))
    return image


def _composite_layer_shadow(canvas: Image.Image, occupied_cells, px: int) -> None:
    """Add a soft shadow just outside one projected layer's combined contour."""
    mask = Image.new("L", canvas.size, 0)
    draw = ImageDraw.Draw(mask)
    for cx, cy in occupied_cells:
        x0, y0 = cx * px, cy * px
        draw.rectangle((x0, y0, x0 + px - 1, y0 + px - 1), fill=255)

    blurred = mask.filter(ImageFilter.GaussianBlur(radius=2))
    outer_ring = ImageChops.subtract(blurred, mask).point(
        lambda value: round(value * 80 / 255)
    )
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shadow.putalpha(outer_ring)
    canvas.alpha_composite(shadow)


# 背景纹理常量 (8-25 老板硬偏好: 浅灰棋盘格 + 1px 方块阴影边框)
# 老板原话 8-25 补充: "网格就不要渲染在方块上面了, 灰色边框和棋盘格只是放在背景上"
BG_LIGHT = (240, 240, 240)  # 棋盘浅格
BG_DARK = (220, 220, 220)   # 棋盘深格
BLOCK_EDGE = (180, 180, 180)  # 1px 方块边界阴影


def _is_air_cell(ray) -> bool:
    """判断一个 (cx, cy) 位置是否所有层都是空气 — 用来判断是否画背景纹理"""
    return all(block is None or v3.is_air(block.id) for _, block in ray)


def _draw_checker_background(width: int, height: int, cells, px: int) -> Image.Image:
    """16px 浅灰棋盘格背景 — 只画在空气格子上, 不覆盖方块贴图"""
    bg = Image.new("RGB", (width * px, height * px), BG_LIGHT)
    pixels = bg.load()
    for (cx, cy, ray) in cells:
        if not _is_air_cell(ray):
            continue  # 跳过有方块的格子, 保持 BG_LIGHT
        if (cx + cy) % 2 == 1:
            x0, y0 = cx * px, cy * px
            for dx in range(px):
                for dy in range(px):
                    pixels[x0 + dx, y0 + dy] = BG_DARK
    return bg


def _draw_wool_mosaic_background(width_px: int, height_px: int,
                                 tile_px: int = 16) -> Image.Image:
    """Tile light-gray wool without stretching it across the canvas."""
    wool = Image.open(TEXTURE_DIR / "light_gray_wool.png").convert("RGB")
    if wool.size != (tile_px, tile_px):
        wool = wool.resize((tile_px, tile_px), Image.NEAREST)
    cols = (width_px + tile_px - 1) // tile_px
    rows = (height_px + tile_px - 1) // tile_px
    canvas = Image.new("RGB", (cols * tile_px, rows * tile_px))
    for row in range(rows):
        for col in range(cols):
            canvas.paste(wool, (col * tile_px, row * tile_px))
    return canvas.crop((0, 0, width_px, height_px))


def _draw_block_edges(canvas: Image.Image, cells, px: int) -> Image.Image:
    """画方块阴影边框 — 只画在空气与方块的交界处, 不覆盖方块贴图"""
    from PIL import ImageDraw
    canvas = canvas.copy()
    draw = ImageDraw.Draw(canvas)

    # 建 (cx, cy) -> 是否空气 索引
    cell_map = {(cx, cy): _is_air_cell(ray) for (cx, cy, ray) in cells}

    for (cx, cy, ray) in cells:
        x0, y0 = cx * px, cy * px
        # 右边界: 当前格有方块, 右边格是空气 → 画阴影
        if x0 + px < canvas.width and not _is_air_cell(ray):
            if cell_map.get((cx + 1, cy), True):
                draw.line([(x0 + px - 1, y0), (x0 + px - 1, y0 + px - 1)], fill=BLOCK_EDGE)
        # 下边界: 当前格有方块, 下边格是空气 → 画阴影
        if y0 + px < canvas.height and not _is_air_cell(ray):
            if cell_map.get((cx, cy + 1), True):
                draw.line([(x0, y0 + px - 1), (x0 + px - 1, y0 + px - 1)], fill=BLOCK_EDGE)
    return canvas


def _draw_piston_extension(canvas: Image.Image, reg, face: str, px: int) -> None:
    """Draw valid dynamic piston arms before projected block layers."""
    offsets = {
        "down": (0, -1, 0), "up": (0, 1, 0),
        "north": (0, 0, -1), "south": (0, 0, 1),
        "west": (-1, 0, 0), "east": (1, 0, 0),
    }
    base_ids = {"minecraft:piston", "minecraft:sticky_piston"}
    for x in range(reg.minx(), reg.maxx() + 1):
        for y in range(reg.miny(), reg.maxy() + 1):
            for z in range(reg.minz(), reg.maxz() + 1):
                base = reg[x, y, z]
                properties = _props(base)
                facing = properties.get("facing")
                if (base.id not in base_ids or
                        properties.get("extended") != "true" or
                        facing not in offsets):
                    continue
                dx, dy, dz = offsets[facing]
                hx, hy, hz = x + dx, y + dy, z + dz
                if not (reg.minx() <= hx <= reg.maxx() and
                        reg.miny() <= hy <= reg.maxy() and
                        reg.minz() <= hz <= reg.maxz()):
                    continue
                head = reg[hx, hy, hz]
                if (head.id != "minecraft:piston_head" or
                        _props(head).get("facing") != facing):
                    continue
                if face == "up":
                    cx, cy = x - reg.minx(), z - reg.minz()
                elif face in ("north", "south"):
                    cx, cy = x - reg.minx(), reg.maxy() - y
                else:
                    cx, cy = z - reg.minz(), reg.maxy() - y
                cell = _render_piston_extension(
                    face, px, base.id == "minecraft:sticky_piston", facing)
                canvas.alpha_composite(cell, (cx * px, cy * px))


def _xray_layers(ray, max_layers: int):
    """Keep only the nearest water surface so submerged blocks stay visible."""
    layers = []
    water_seen = False
    for depth, block in ray:
        if block is None or v3.is_air(block.id):
            continue
        is_water = block.id in {"minecraft:water", "minecraft:bubble_column"}
        if is_water and water_seen:
            continue
        water_seen |= is_water
        layers.append((depth, block))
        if len(layers) == max_layers:
            break
    return layers


def render_projection(reg, axes: str, px=16, max_layers=5) -> Image.Image:
    """Render a projection back-to-front so translucent deeper layers remain visible."""
    rx = list(range(reg.minx(), reg.maxx() + 1))
    ry = list(range(reg.miny(), reg.maxy() + 1))
    rz = list(range(reg.minz(), reg.maxz() + 1))
    if axes == "xz":
        width, height, face = len(rx), len(rz), "up"
        cells = ((ix, iz, [(y, reg[x, y, z]) for y in reversed(ry)])
                 for ix, x in enumerate(rx) for iz, z in enumerate(rz))
    elif axes == "xy":
        width, height, face = len(rx), len(ry), "north"
        cells = ((ix, len(ry) - 1 - iy, [(z, reg[x, y, z]) for z in reversed(rz)])
                 for ix, x in enumerate(rx) for iy, y in enumerate(ry))
    else:
        width, height, face = len(rz), len(ry), "west"
        cells = ((iz, len(ry) - 1 - iy, [(x, reg[x, y, z]) for x in reversed(rx)])
                 for iz, z in enumerate(rz) for iy, y in enumerate(ry))
    # 把 cells 转成 list — 后面背景和边框函数都要复用
    cell_list = list(cells)
    canvas = _draw_wool_mosaic_background(width * px, height * px).convert("RGBA")
    projected_layers = [
        (cx, cy, _xray_layers(ray, max_layers))
        for cx, cy, ray in cell_list
    ]
    _draw_piston_extension(canvas, reg, face, px)
    # Paste complete depth layers deepest first so each gets one combined outer shadow.
    for index in reversed(range(max_layers)):
        occupied_cells = []
        for cx, cy, layers in projected_layers:
            if index >= len(layers):
                continue
            block = layers[index][1]
            cell = block_cell(block, face, px)
            if block.id in {"minecraft:water", "minecraft:bubble_column"}:
                cell.putalpha(cell.getchannel("A").point(lambda alpha: round(alpha * .30)))
            if index > 0:
                overlay = Image.new("RGBA", cell.size, (0, 0, 0, round(255 * 0.10)))
                for _ in range(index):
                    cell.alpha_composite(overlay)
            canvas.alpha_composite(cell, (cx * px, cy * px))
            occupied_cells.append((cx, cy))
        if max_layers > 1 and occupied_cells:
            _composite_layer_shadow(canvas, occupied_cells, px)
    # 加 1px 方块阴影边框 (老板原话"网格就不要渲染在方块上面了" — 只画在方块与空气交界处)
    canvas = _draw_block_edges(canvas, cell_list, px)
    return canvas.convert("RGB")


def _projection_cells(reg, axes: str):
    """Return the existing V4 cell rays in the same orientation as V14 cameras."""
    rx = list(range(reg.minx(), reg.maxx() + 1))
    ry = list(range(reg.miny(), reg.maxy() + 1))
    rz = list(range(reg.minz(), reg.maxz() + 1))
    if axes == "xz":
        return len(rx), len(rz), [
            (ix, iz, [(y, reg[x, y, z]) for y in reversed(ry)])
            for ix, x in enumerate(rx) for iz, z in enumerate(rz)]
    if axes == "xy":
        return len(rx), len(ry), [
            (ix, len(ry) - 1 - iy, [(z, reg[x, y, z]) for z in reversed(rz)])
            for ix, x in enumerate(rx) for iy, y in enumerate(ry)]
    return len(rz), len(ry), [
        (iz, len(ry) - 1 - iy, [(x, reg[x, y, z]) for x in reversed(rx)])
        for iz, z in enumerate(rz) for iy, y in enumerate(ry)]


def _finish_3d_projection(raw: Image.Image, reg, axes: str, px: int) -> Image.Image:
    """Put a transparent Three.js view onto V4's approved background treatment."""
    width, height, cells = _projection_cells(reg, axes)
    canvas = _draw_wool_mosaic_background(width * px, height * px).convert("RGBA")
    occupied = [(cx, cy) for cx, cy, ray in cells if not _is_air_cell(ray)]
    if occupied:
        _composite_layer_shadow(canvas, occupied, px)
    canvas.alpha_composite(raw.convert("RGBA"))
    return _draw_block_edges(canvas, cells, px).convert("RGB")


def render_three_views_3d(reg, px: int, output_dir=Path("/tmp"), prefix="v14",
                          include_iso: bool = False):
    """Serialize litemapy's block palette and invoke the Three.js renderer."""
    blocks = []
    for x in range(reg.minx(), reg.maxx() + 1):
        for y in range(reg.miny(), reg.maxy() + 1):
            for z in range(reg.minz(), reg.maxz() + 1):
                block = reg[x, y, z]
                if block is None or v3.is_air(block.id):
                    continue
                blocks.append({
                    "id": block.id, "properties": _props(block),
                    "x": x, "y": y, "z": z,
                })
    entities = []
    for ent in reg.entities:
        pos = getattr(ent, "pos", None) or getattr(ent, "position", (0, 0, 0))
        rotation = getattr(ent, "rotation", (0, 0))
        entities.append({
            "id": str(ent.id),
            "x": float(pos[0]), "y": float(pos[1]), "z": float(pos[2]),
            # Minecraft's Rotation list is [yaw, pitch]. Keep both components
            # named by the axis the renderer uses rather than by tuple index.
            "rotation_y": float(rotation[0]) if len(rotation) > 0 else 0,
            "rotation_x": float(rotation[1]) if len(rotation) > 1 else 0,
        })
    manifest = {
        "assets": str(ASSETS),
        "min": [reg.minx(), reg.miny(), reg.minz()],
        "size": {"x": reg.maxx() - reg.minx() + 1,
                 "y": reg.maxy() - reg.miny() + 1,
                 "z": reg.maxz() - reg.minz() + 1},
        "px": px, "blocks": blocks, "entities": entities,
        "outputDir": str(output_dir), "prefix": prefix,
        "views": ["top", "front", "side", "iso"] if include_iso else
                 ["top", "front", "side"],
    }
    with tempfile.NamedTemporaryFile("w", suffix=".json", encoding="utf-8") as handle:
        json.dump(manifest, handle)
        handle.flush()
        completed = subprocess.run(
            ["node", str(ROOT / "render_3d.js"), "--manifest", handle.name],
            cwd=ROOT, text=True, capture_output=True,
        )
        if completed.returncode:
            raise RuntimeError(f"V14 renderer failed: {completed.stderr.strip()}")
    if completed.stdout.strip():
        print(f"V14 renderer: {completed.stdout.strip()}")
    raw = [Image.open(output_dir / f"{prefix}_{view}.png").convert("RGBA")
           for view in ("top", "front", "side")]
    return tuple(_finish_3d_projection(image, reg, axes, px)
                 for image, axes in zip(raw, ("xz", "xy", "yz")))


def _load_item_texture(block_id: str, size: int = 32):
    """材料列表图标：item、block、模型顶视图、紫黑格依次兜底。"""
    target = block_id.split("[")[0].replace("minecraft:", "")
    for path in _resource_texture_paths(target):
        if path.exists():
            img = Image.open(path).convert("RGB")
            if img.size != (size, size):
                img = img.resize((size, size), Image.NEAREST)
            return img
    model_icon = block_cell(litemapy.BlockState(f"minecraft:{target}"), "up", size)
    if model_icon.getbbox():
        background = Image.new("RGBA", model_icon.size, "white")
        background.alpha_composite(model_icon)
        return background.convert("RGB")
    return _purple_checker(size).convert("RGB")


def make_layout_v4(img_xz, img_xy, img_yz, title: str, subtitle: str,
                   stats: list) -> Image.Image:
    """V4 专属排版 (8-25 老板原话 三视图横向 1x3):
    1. 顶部: 大字标题 (litematic 文件名) + 副标题 (尺寸/层模式)
    2. 三视图横向 1x3 并排: 俯视 | 正视 | 侧视
    3. 底部: 材料列表 [贴图] 方块名 数量 (按数量降序)
    """
    pad = 32
    label_h = 28
    title_h = 56
    subtitle_h = 28
    item_h = 40
    item_pad = 8

    # 字体 - 大
    try:
        font_title = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc", 32)
        font_subtitle = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK.ttc", 18)
        font_label = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc", 18)
        font_item = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK.ttc", 16)
        font_section = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc", 20)
    except Exception:
        font_title = font_subtitle = font_label = font_item = font_section = ImageFont.load_default()

    views = [(img_xz, "俯视图 (Top, XZ) — 看向 -y"),
             (img_xy, "正视图 (Front, XY) — 看向 +z"),
             (img_yz, "侧视图 (Side, YZ) — 看向 -x")]
    # 三视图横向并排: 总宽 = 三图宽之和 + 3*pad
    views_h = max(img.height for img, _ in views) + label_h
    views_w = sum(img.width for img, _ in views) + 4 * pad
    list_h = (item_h + item_pad) * len(stats) + label_h + pad
    total_w = views_w
    total_h = title_h + subtitle_h + views_h + list_h + 3 * pad

    bg = Image.new("RGB", (total_w, total_h), (255, 255, 255))
    draw = ImageDraw.Draw(bg)

    # 1. 顶部标题
    draw.text((pad, pad // 2), title, fill=(0, 0, 0), font=font_title)
    draw.text((pad, pad // 2 + title_h - 8), subtitle, fill=(80, 80, 80), font=font_subtitle)

    # 2. 三视图横向 1x3 并排
    y_views = pad + title_h + subtitle_h + pad
    x = pad
    for img, label in views:
        # 标签在图片上方, 左对齐 (老板原话"对齐" = 同一基线)
        draw.text((x, y_views), label, fill=(0, 0, 0), font=font_label)
        # 图片贴在标签下方
        bg.paste(img, (x, y_views + label_h))
        x += img.width + pad

    # 3. 底部材料列表
    y = y_views + label_h + max(img.height for img, _ in views) + pad
    draw.text((pad, y), f"材料清单 ({len(stats)} 种方块)", fill=(0, 0, 0), font=font_section)
    y += label_h + 4

    # 渲染每行 [贴图] 方块名 数量
    for i, (block_id, count) in enumerate(stats):
        # 贴图 32x32 (白底)
        tex = _load_item_texture(block_id, 32)
        if tex is not None:
            # 1px 边框
            tex_rgba = tex.convert("RGBA")
            txd = ImageDraw.Draw(tex_rgba)
            txd.rectangle([(0, 0), (31, 31)], outline=(180, 180, 180), width=1)
            bg.paste(tex_rgba.convert("RGB"), (pad, y))
        # 文本
        name = block_id.replace("minecraft:", "")
        text = f"{name}\t×{count}"
        # tab 对齐: 贴图右 + 16px
        draw.text((pad + 32 + 16, y + 8), text, fill=(0, 0, 0), font=font_item)
        y += item_h + item_pad

    return bg


def main():
    import argparse
    p = argparse.ArgumentParser(description="Litematic orthographic model renderer (V4)")
    p.add_argument("input", help=".litematic file")
    p.add_argument("out", nargs="?", default=None, help="output PNG (default: <input>.v4.png)")
    p.add_argument("--px", type=int, default=32, help="pixels per block (default 32)")
    p.add_argument("--layers", type=int, default=5, help="X-ray layers when mode=xray (default 5)")
    p.add_argument("--mode", choices=["top", "xray"], default="top",
                   help="top: only y=max_y for every view; xray: top N layers with 10%% fade (default top)")
    p.add_argument("--iso", action="store_true",
                   help="also render an isometric orthographic view to /tmp/v16_iso.png")
    args = p.parse_args()

    path = Path(args.input)
    out = Path(args.out) if args.out else path.with_suffix(".v4.png")
    px = args.px
    max_layers = args.layers if args.mode == "xray" else 1
    schematic = litemapy.Schematic.load(str(path))
    reg = next(iter(schematic.regions.values()))
    print(f"=== {path.name}: {schematic.width}x{schematic.height}x{schematic.length} mode={args.mode} layers={max_layers} ===")
    if args.mode == "top":
        xz, xy, yz = render_three_views_3d(
            reg, px, prefix="v16" if args.iso else "v14", include_iso=args.iso)
    else:
        # Preserve V13's black-overlay X-ray semantics; V14 handles true surfaces.
        xz = render_projection(reg, "xz", px, max_layers)
        xy = render_projection(reg, "xy", px, max_layers)
        yz = render_projection(reg, "yz", px, max_layers)
    counts = Counter()
    for x in range(reg.minx(), reg.maxx() + 1):
        for y in range(reg.miny(), reg.maxy() + 1):
            for z in range(reg.minz(), reg.maxz() + 1):
                block = reg[x, y, z]
                if block is not None and not v3.is_air(block.id):
                    counts[block.id] += 1
    mode_label = f"Y={reg.maxy()} 顶层" if args.mode == "top" else f"X 射线 {max_layers} 层 (顶层向内)"
    result = make_layout_v4(
        xz, xy, yz,
        title=path.stem,
        subtitle=f"{schematic.width} × {schematic.height} × {schematic.length}    {mode_label}    V14 Three.js projection",
        stats=counts.most_common(),
    )
    result.save(out)
    print(f"Output: {out} ({result.width}x{result.height})")


if __name__ == "__main__":
    main()
