#!/usr/bin/env python3
"""Litematic orthographic model renderer (V4).

V4 keeps V3's report layout, but renders the actual rectangles in Minecraft's
block model ``elements``.  Thin and non-cubic blocks therefore retain their
shape in the front and side projections.
"""

import json
import sys
from collections import Counter
from functools import lru_cache
from pathlib import Path

import litemapy
from PIL import Image, ImageEnhance

import a_render_v3 as v3

ROOT = Path(__file__).parent
ASSETS = ROOT / "client_assets/assets/minecraft"
MODEL_DIR = ASSETS / "models/block"
BLOCKSTATE_DIR = ASSETS / "blockstates"
TEXTURE_DIR = ASSETS / "textures/block"


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
    for element in model["elements"]:
        lo, hi = element.get("from", [0, 0, 0]), element.get("to", [16, 16, 16])
        corners = [_rotate_point((x, y, z), xr, yr)
                   for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
        rect = _project_box(corners, view_face)
        for model_face, face_def in element.get("faces", {}).items():
            if _rotate_normal(model_face, xr, yr) != view_face:
                continue
            tex_name = _texture_name(face_def, model["textures"])
            if tex_name:
                drawables.append((rect[4], rect[:4], tex_name, face_def))
    if not drawables:
        return _fallback_cell(block, view_face, px)
    cell = Image.new("RGBA", (px, px))
    scale = px / 16
    for _, (x0, y0, x1, y1), tex_name, face_def in sorted(drawables):
        left, top = round(x0 * scale), round(y0 * scale)
        right, bottom = round(x1 * scale), round(y1 * scale)
        # Zero-thickness planes still occupy one pixel in an orthographic view.
        right, bottom = max(right, left + 1), max(bottom, top + 1)
        texture = _crop_uv(v3.load_texture_rgba(tex_name), face_def)
        texture = texture.resize((right - left, bottom - top), Image.Resampling.NEAREST)
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
    "spruce_wall_sign": "spruce_planks", "player_head": "smooth_stone",
    "moving_piston": "piston_side",
}


def _fallback_texture(name: str, face: str) -> str:
    if name in FALLBACK_EXACT:
        return FALLBACK_EXACT[name]
    if "shulker_box" in name:
        return name.replace("_shulker_box", "_shulker_box")
    if name.endswith("_stained_glass_pane"):
        return name.removesuffix("_pane")
    if name == "glass_pane":
        return "glass"
    if name.endswith("_coral_wall_fan"):
        return name.replace("_wall_fan", "_fan")
    if name.endswith("_wall_sign"):
        return f"{name.removesuffix('_wall_sign')}_planks"
    suffixes = (
        "_wall", "_fence", "_stairs", "_door", "_sign", "_trapdoor", "_slab",
        "_pressure_plate", "_button", "_coral", "_leaves", "_log", "_wood",
        "_planks", "_glazed_terracotta",
    )
    for suffix in suffixes:
        if name.endswith(suffix):
            base = name[:-len(suffix)]
            candidates = [name, f"{base}_planks", f"{base}_log", base]
            for candidate in candidates:
                if (TEXTURE_DIR / f"{candidate}.png").exists():
                    return candidate
    return name


def _fallback_cell(block, face: str, px: int) -> Image.Image:
    name = block.id.replace("minecraft:", "")
    if name == "water":
        # The extracted client assets only retain water's animation metadata.
        return Image.new("RGBA", (px, px), (35, 105, 230, 105))
    if name.endswith("_door"):
        half = _props(block).get("half", "bottom")
        half = "top" if half == "upper" else "bottom"
        candidate = f"{name}_{half}"
        if (TEXTURE_DIR / f"{candidate}.png").exists():
            return v3.load_texture_rgba(candidate).resize((px, px), Image.Resampling.NEAREST)
    texture = _fallback_texture(name, face)
    return v3.load_texture_rgba(texture).resize((px, px), Image.Resampling.NEAREST)


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
        width, height, face = len(rx), len(ry), "south"
        cells = ((ix, len(ry) - 1 - iy, [(z, reg[x, y, z]) for z in rz])
                 for ix, x in enumerate(rx) for iy, y in enumerate(ry))
    else:
        width, height, face = len(rz), len(ry), "west"
        cells = ((iz, len(ry) - 1 - iy, [(x, reg[x, y, z]) for x in reversed(rx)])
                 for iz, z in enumerate(rz) for iy, y in enumerate(ry))
    # 把 cells 转成 list — 后面背景和边框函数都要复用
    cell_list = list(cells)
    # 棋盘格背景 (白玻璃立刻能区分) — 只画在空气格子上
    canvas = Image.new("RGBA", (width * px, height * px), BG_LIGHT)
    bg_checker = _draw_checker_background(width, height, cell_list, px).convert("RGBA")
    canvas.alpha_composite(bg_checker)
    for cx, cy, ray in cell_list:
        layers = [(depth, block) for depth, block in ray if block and not v3.is_air(block.id)][:max_layers]
        # Paste deepest first. Front is opaque; each deeper layer fades by 10% (90% opaque).
        for index in reversed(range(len(layers))):
            block = layers[index][1]
            cell = block_cell(block, face, px)
            cell = _apply_alpha(cell, 1.0 if index == 0 else 0.9 ** index)
            canvas.alpha_composite(cell, (cx * px, cy * px))
    # 加 1px 方块阴影边框 (老板原话"网格就不要渲染在方块上面了" — 只画在方块与空气交界处)
    canvas = _draw_block_edges(canvas, cell_list, px)
    return canvas.convert("RGB")


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 a_render_v4.py input.litematic [out.png] [px=16] [layers=5]")
        raise SystemExit(1)
    path = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else path.with_suffix(".v4.png")
    px = int(sys.argv[3]) if len(sys.argv) > 3 else 16
    max_layers = int(sys.argv[4]) if len(sys.argv) > 4 else 5
    schematic = litemapy.Schematic.load(str(path))
    reg = next(iter(schematic.regions.values()))
    print(f"=== {path.name}: {schematic.width}x{schematic.height}x{schematic.length} ===")
    xz = render_projection(reg, "xz", px, max_layers)
    xy = render_projection(reg, "xy", px, max_layers)
    yz = render_projection(reg, "yz", px, max_layers)
    counts = Counter()
    for x in range(reg.minx(), reg.maxx() + 1):
        for y in range(reg.miny(), reg.maxy() + 1):
            for z in range(reg.minz(), reg.maxz() + 1):
                block = reg[x, y, z]
                if block and not v3.is_air(block.id):
                    counts[block.id] += 1
    result = v3.make_combined(
        xz, xy, yz,
        ["Top (XZ): model top faces", "Front (XY): model south faces", "Side (YZ): model west faces"],
        title=f"{path.stem} ({schematic.width}x{schematic.height}x{schematic.length}) V4 model projection | X-ray {max_layers} layers",
        stats=counts.most_common(),
    )
    result.save(out)
    print(f"Output: {out} ({result.width}x{result.height})")


if __name__ == "__main__":
    main()
