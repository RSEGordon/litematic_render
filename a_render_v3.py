#!/usr/bin/env python3
"""
litematic → 三视图带模型渲染（方案 A V3）
按 MC 方块模型 JSON 解析 (y_min, y_max) + 6 面贴图 + X 射线半透明

依赖: pip install litemapy matplotlib Pillow
"""

import sys
import json
from pathlib import Path
from collections import Counter
import litemapy
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).parent
ASSETS = ROOT / "client_assets/assets/minecraft"
TEXTURE_DIR = ASSETS / "textures/block"
MODEL_DIR = ASSETS / "models/block"
BLOCKSTATE_DIR = ASSETS / "blockstates"

_TEXTURE_CACHE: dict = {}
_MODEL_CACHE: dict = {}


# ============================================================
# 1. 模型 JSON 解析（递归 parent + 算 y_min/y_max + 6 面贴图）
# ============================================================
def resolve_model(model_id: str, max_depth: int = 8) -> dict | None:
    """
    解析 model JSON（递归 parent 合并），返回：
    {
        "y_min": 0, "y_max": 16,    # 模型在 y 方向的最低/最高（像素 0-16）
        "faces": {  # 6 个面 + 贴图 key (texture_name 不带 minecraft:block/)
            "up":    "repeater",
            "down":  "smooth_stone",
            "north": "smooth_stone",
            "south": "smooth_stone",
            "east":  "smooth_stone",
            "west":  "smooth_stone",
        },
        "elements": [...]  # 原始 elements 备用
    }
    """
    if model_id in _MODEL_CACHE:
        return _MODEL_CACHE[model_id]
    if max_depth <= 0:
        return None

    path = MODEL_DIR / f"{model_id}.json"
    if not path.exists():
        return None
    raw = json.loads(path.read_text())

    # 1. 递归 parent
    parent = raw.get("parent")
    if parent:
        parent_id = parent.replace("minecraft:", "").replace("block/", "")
        parent_resolved = resolve_model(parent_id, max_depth - 1)
        if parent_resolved:
            merged = dict(parent_resolved)
        else:
            merged = {"y_min": 0, "y_max": 16, "faces": {}}
    else:
        merged = {"y_min": 0, "y_max": 16, "faces": {}}

    # 2. 覆盖 textures
    textures = merged.get("textures", {})
    textures.update(raw.get("textures", {}))
    # 处理 "#xxx" 引用
    def resolve_tex(key):
        if key is None:
            return None
        if key.startswith("#"):
            return textures.get(key[1:], key)
        if key.startswith("minecraft:block/"):
            key = key[len("minecraft:block/"):]
        return key
    for k, v in list(textures.items()):
        nv = resolve_tex(v)
        if nv is not None:
            textures[k] = nv
    merged["textures"] = textures

    # 3. 处理 elements
    elements = raw.get("elements", [])
    if elements:
        # 收集所有 face 贴图
        faces_seen = {}
        y_min = 16
        y_max = 0
        for el in elements:
            el_from = el.get("from", [0, 0, 0])
            el_to = el.get("to", [16, 16, 16])
            y_min = min(y_min, el_from[1])
            y_max = max(y_max, el_to[1])
            el_faces = el.get("faces", {})
            for face_name, face_def in el_faces.items():
                tex = face_def.get("texture", "")
                if tex.startswith("#"):
                    tex = textures.get(tex[1:], tex)
                else:
                    tex = textures.get(tex, tex)
                # 去掉 minecraft:block/ 前缀
                if tex.startswith("minecraft:block/"):
                    tex = tex[len("minecraft:block/"):]
                if face_name not in faces_seen:
                    faces_seen[face_name] = tex
        merged["y_min"] = y_min
        merged["y_max"] = y_max
        merged["faces"] = faces_seen
    elif parent and parent_resolved:
        # 没 elements，继承 parent
        pass
    else:
        # 都没 = 满 1×1×1
        merged["y_min"] = 0
        merged["y_max"] = 16

    _MODEL_CACHE[model_id] = merged
    return merged


# ============================================================
# 2. BlockState → model_id (按 blockstate JSON + 旋转)
# ============================================================
def resolve_model_for_blockstate(block_id: str, properties: dict) -> str | None:
    """
    给 block_id + properties dict，返回 model 相对路径 (e.g. "repeater_1tick")。
    按 blockstate JSON 的 variants 匹配，找不到匹配就用第一项。
    """
    raw = block_id.replace("minecraft:", "").split("[")[0]
    bs_path = BLOCKSTATE_DIR / f"{raw}.json"
    if not bs_path.exists():
        return None
    bs = json.loads(bs_path.read_text())
    variants = bs.get("variants", {})
    if not variants:
        return None

    # 1. 完全匹配
    for key, value in variants.items():
        if _match_variant(key, properties):
            return _extract_model_id(value)

    # 2. 部分匹配 (nbt 简版: 一个个试)
    for key, value in variants.items():
        if _partial_match(key, properties):
            return _extract_model_id(value)

    # 3. 兜底 = 第一个 variant
    first_key = list(variants.keys())[0]
    return _extract_model_id(variants[first_key])


def _extract_model_id(value) -> str | None:
    """从 blockstate variant value 提取 model_id
    value 可能是 dict (单模型) 或 list of dict (multipart)"""
    if isinstance(value, list):
        # multipart: 多个模型叠加，通常取第一个
        if value and isinstance(value[0], dict):
            return value[0].get("model", "").replace("minecraft:block/", "")
        return None
    elif isinstance(value, dict):
        return value.get("model", "").replace("minecraft:block/", "")
    return None


def _match_variant(key: str, properties: dict) -> bool:
    """严格匹配 blockstate variant key: 'delay=1,facing=east,powered=false'"""
    if key == "":  # 空 key = 默认
        return True
    parts = key.split(",")
    for part in parts:
        if "=" not in part:
            return False
        k, v = part.split("=", 1)
        if str(properties.get(k)) != v:
            return False
    return True


def _partial_match(key: str, properties: dict) -> bool:
    """模糊匹配 - 某些 key 里只有部分 properties 给定也匹配"""
    if key == "":
        return True
    parts = key.split(",")
    for part in parts:
        if "=" not in part:
            return False
        k, v = part.split("=", 1)
        if k in properties and str(properties[k]) != v:
            return False
    return True


# ============================================================
# 3. 主贴图加载（含 alpha 用于半透明）
# ============================================================
def load_texture_rgba(name: str) -> Image.Image:
    """加载贴图返回 RGBA（用于半透明 X 射线）"""
    if not name:
        return None
    # 去掉路径前缀
    if "/" in name:
        name = name.split("/")[-1]
    cache_key = f"rgba:{name}"
    if cache_key in _TEXTURE_CACHE:
        return _TEXTURE_CACHE[cache_key]
    path = TEXTURE_DIR / f"{name}.png"
    if not path.exists():
        # 紫黑格 fallback
        img = Image.new("RGBA", (16, 16), (122, 31, 162, 255))
        for x in range(16):
            for y in range(16):
                if x % 4 == 0 or y % 4 == 0:
                    img.putpixel((x, y), (0, 0, 0, 255))
    else:
        img = Image.open(path).convert("RGBA")
        if img.size != (16, 16):
            img = img.resize((16, 16), Image.LANCZOS)
    _TEXTURE_CACHE[cache_key] = img
    return img


# ============================================================
# 4. X 射线三视图（半透明叠加）
# ============================================================
def render_xz_xray(reg, px: int = 16, max_layers: int = 5) -> Image.Image:
    """
    俯视 (XZ) X 射线 - 看向 -y 方向
    每个 (x, z) 位置：取从 max_y 往下到 min_y 最多 max_layers 个方块
    后层 50% 透明，前层不透明
    """
    rx = list(range(reg.minx(), reg.maxx() + 1))
    rz = list(range(reg.minz(), reg.maxz() + 1))
    ry = list(range(reg.miny(), reg.maxy() + 1))
    W = len(rx) * px
    H = len(rz) * px
    canvas = Image.new("RGB", (W, H), (255, 255, 255))

    for ix, x in enumerate(rx):
        for iz, z in enumerate(rz):
            # 从最上往下找非空方块（最多 max_layers 层）
            layers = []
            for y in reversed(ry):
                b = reg[x, y, z]
                if b is None or is_air(b.id):
                    continue
                layers.append((y, b))
                if len(layers) >= max_layers:
                    break
            if not layers:
                continue
            # 绘制：最前面的（y 最大）不透明，其后半透明
            for i, (y, b) in enumerate(layers):
                tex = _get_face_tex(b, "up")
                if tex is None:
                    continue
                # alpha: 第一个 255, 之后逐层减半
                alpha = int(255 * (0.4 ** i)) if i > 0 else 255
                if alpha < 30:
                    continue
                # 缩放贴图
                tex_resized = tex.resize((px, px), Image.NEAREST)
                # 应用 alpha
                if alpha < 255:
                    r, g, bb, a = tex_resized.split()
                    a = a.point(lambda v: int(v * alpha / 255))
                    tex_resized = Image.merge("RGBA", (r, g, bb, a))
                canvas.paste(tex_resized, (ix * px, iz * px), tex_resized)
    return canvas


def render_xy_xray(reg, px: int = 16, max_layers: int = 5) -> Image.Image:
    """
    正视 (XY) X 射线 - 看向 +z 方向
    每个 (x, y) 位置：取 z 方向从 min_z 往里到 max_z 最多 max_layers 个方块
    """
    rx = list(range(reg.minx(), reg.maxx() + 1))
    ry = list(range(reg.miny(), reg.maxy() + 1))
    rz = list(range(reg.minz(), reg.maxz() + 1))
    W = len(rx) * px
    H = len(ry) * px
    canvas = Image.new("RGB", (W, H), (255, 255, 255))

    for ix, x in enumerate(rx):
        for iy, y in enumerate(ry):
            layers = []
            for z in rz:
                b = reg[x, y, z]
                if b is None or is_air(b.id):
                    continue
                layers.append((z, b))
                if len(layers) >= max_layers:
                    break
            if not layers:
                continue
            for i, (z, b) in enumerate(layers):
                tex = _get_face_tex(b, "south")
                if tex is None:
                    continue
                alpha = int(255 * (0.4 ** i)) if i > 0 else 255
                if alpha < 30:
                    continue
                tex_resized = tex.resize((px, px), Image.NEAREST)
                if alpha < 255:
                    r, g, bb, a = tex_resized.split()
                    a = a.point(lambda v: int(v * alpha / 255))
                    tex_resized = Image.merge("RGBA", (r, g, bb, a))
                canvas.paste(tex_resized, (ix * px, iy * px), tex_resized)
    return canvas


def render_yz_xray(reg, px: int = 16, max_layers: int = 5) -> Image.Image:
    """
    侧视 (YZ) X 射线 - 看向 -x 方向
    每个 (z, y) 位置：取 x 方向从 max_x 往里到 min_x 最多 max_layers 个方块
    """
    rz = list(range(reg.minz(), reg.maxz() + 1))
    ry = list(range(reg.miny(), reg.maxy() + 1))
    rx = list(range(reg.minx(), reg.maxx() + 1))
    W = len(rz) * px
    H = len(ry) * px
    canvas = Image.new("RGB", (W, H), (255, 255, 255))

    for iz, z in enumerate(rz):
        for iy, y in enumerate(ry):
            layers = []
            for x in reversed(rx):
                b = reg[x, y, z]
                if b is None or is_air(b.id):
                    continue
                layers.append((x, b))
                if len(layers) >= max_layers:
                    break
            if not layers:
                continue
            for i, (x, b) in enumerate(layers):
                tex = _get_face_tex(b, "west")
                if tex is None:
                    continue
                alpha = int(255 * (0.4 ** i)) if i > 0 else 255
                if alpha < 30:
                    continue
                tex_resized = tex.resize((px, px), Image.NEAREST)
                if alpha < 255:
                    r, g, bb, a = tex_resized.split()
                    a = a.point(lambda v: int(v * alpha / 255))
                    tex_resized = Image.merge("RGBA", (r, g, bb, a))
                canvas.paste(tex_resized, (iz * px, iy * px), tex_resized)
    return canvas


# ============================================================
# 5. BlockState → 贴图（按 model JSON + blockstate 解析）
# ============================================================
def _get_face_tex(block, face: str) -> Image.Image | None:
    """
    block (litemapy BlockState) + face (up/down/north/south/east/west)
    → 返回该面贴图（PIL RGBA Image）

    策略:
    1. 拿 block id + properties → 走 blockstate JSON → 拿 model_id + 旋转 (x/y/z rotation)
    2. 解析 model JSON 拿到 faces dict
    3. 按 rotation 调整 face (例如 facing=north 的方块, 看 south 面 = 看 model 的 north 面)
    4. 加载对应贴图
    """
    block_id = block.id
    raw_props = block.properties() if hasattr(block, 'properties') else {}
    properties = dict(raw_props) if raw_props else {}

    # 1. 解析 blockstate → model_id
    model_id = resolve_model_for_blockstate(block_id, properties)
    if not model_id:
        # fallback: 用 block id 当贴图名
        tex = load_texture_rgba(block_id.replace("minecraft:", ""))
        return tex

    # 2. 解析 model
    model = resolve_model(model_id)
    if not model:
        tex = load_texture_rgba(model_id)
        return tex

    faces = model.get("faces", {})

    # 3. 按 facing 调整 face 方向
    # MC blockstate 里 facing= 表示方块的"前"朝哪, model 的"front"= north 是默认
    # 我们的视图: up=俯视 top, south=正视 south, west=侧视 west
    actual_face = _resolve_actual_face(face, properties, model_id, block_id)
    tex_name = faces.get(actual_face, faces.get(_default_face_key(actual_face)))
    if not tex_name:
        # 兜底用 block id
        tex_name = block_id.replace("minecraft:", "")

    return load_texture_rgba(tex_name)


def _default_face_key(face: str) -> str:
    """up→up, down→down, north→north, south→south, east→east, west→west"""
    return face


def _resolve_actual_face(view_face: str, properties: dict, model_id: str, block_id: str) -> str:
    """
    给定视图方向（up/south/west）和方块 properties，返回 model 里对应的 face 名。

    例如:
    - view=south, facing=north → 看的是方块的北面 → model 里 = north
    - view=south, facing=south → 看的是方块的南面 → model 里 = south
    - view=south, facing=east → 看的是方块的东面 → model 里 = east
    - view=south, facing=west → 看的是方块的西面 → model 里 = west
    - view=south, facing=up → 看的是方块的顶面 → model 里 = up
    """
    facing = properties.get("facing")
    if not facing:
        return view_face

    # facing 旋转: north=0°, east=90°, south=180°, west=270°
    facing_to_y_rotation = {
        "north": 0, "east": 90, "south": 180, "west": 270,
        "up": 0, "down": 0,  # 上下方向无 y 旋转
    }
    y_rot = facing_to_y_rotation.get(facing, 0)

    # view_face 是我们看过去的"实际"方向
    # 模型的 north 方向默认指向世界 north
    # facing=north: 模型 face="north" 在世界 north
    # facing=east: 模型 face="north" 在世界 east
    # 所以要旋转 y_rot 度的方向

    # view_face 是绝对世界方向
    # 要找的 model face 是: 让方块在面对 view_face 时的那一面
    # = 反向旋转 view_face 角度等于 y_rot
    # = 旋转 (view_face) - y_rot 后的方向

    # 简单做: facing 旋转决定了模型 face 映射到世界方向
    # 世界 view_face = model face + y_rot 旋转
    # 找 model face: model_face = world_face - y_rot 旋转

    face_to_angle = {
        "north": 0, "east": 90, "south": 180, "west": 270,
        "up": None, "down": None,
    }

    if view_face in ("up", "down"):
        # 上下方向: 看 facing=up 时看 up, 否则 model 上下不变
        if facing == "up":
            return "up"
        elif facing == "down":
            return "down"
        else:
            # facing 在水平: 看顶/底仍然是 model 的 up/down
            return view_face
    else:
        # 水平方向
        target_angle = (face_to_angle[view_face] - y_rot) % 360
        # 找匹配
        for f, ang in face_to_angle.items():
            if ang == target_angle:
                return f
        return view_face


def is_air(block_id: str) -> bool:
    return block_id == "minecraft:air"


# ============================================================
# 6. 拼图 + 输出
# ============================================================
def make_combined(img_xz, img_xy, img_yz, labels, title="", stats=None) -> Image.Image:
    pad = 24
    label_h = 24
    title_h = 36 if title else 0
    stats_h = 200 if stats else 0
    total_w = img_xz.width + img_xy.width + img_yz.width + 4 * pad
    total_h = max(img_xz.height, img_xy.height, img_yz.height) + label_h + title_h + stats_h + 2 * pad
    bg = Image.new("RGB", (total_w, total_h), (255, 255, 255))
    draw = ImageDraw.Draw(bg)
    try:
        font_title = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc", 18)
        font_label = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc", 14)
        font_small = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK.ttc", 12)
    except Exception:
        font_title = font_label = font_small = ImageFont.load_default()

    if title:
        draw.text((pad, pad // 2), title, fill=(0, 0, 0), font=font_title)
    y_off = pad + label_h + title_h
    bg.paste(img_xz, (pad, y_off))
    bg.paste(img_xy, (2 * pad + img_xz.width, y_off))
    bg.paste(img_yz, (3 * pad + img_xz.width + img_xy.width, y_off))
    for i, (label, x_pos) in enumerate(zip(labels,
                                          [pad,
                                           2 * pad + img_xz.width,
                                           3 * pad + img_xz.width + img_xy.width])):
        draw.text((x_pos, pad // 2 + title_h), label, fill=(0, 0, 0), font=font_label)

    # 底部方块统计
    if stats:
        y_stats = total_h - stats_h - pad // 2
        draw.text((pad, y_stats), "方块统计 top 20:", fill=(0, 0, 0), font=font_label)
        for i, (bid, cnt) in enumerate(stats[:20]):
            line = f"  {cnt:4d}  {bid}"
            col = i // 10
            row = i % 10
            draw.text((pad + col * 320, y_stats + 24 + row * 16), line, fill=(60, 60, 60), font=font_small)

    return bg


def main():
    if len(sys.argv) < 2:
        print("用法: python a_render_v3.py <path/to/file.litematic> [out.png] [px=16] [layers=5]")
        sys.exit(1)

    path = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) >= 3 else path.with_suffix(".png")
    px = int(sys.argv[3]) if len(sys.argv) >= 4 else 16
    layers = int(sys.argv[4]) if len(sys.argv) >= 5 else 5

    schem = litemapy.Schematic.load(str(path))
    print(f"=== {path.name} ===")
    print(f"width: {schem.width}, height: {schem.height}, length: {schem.length}")
    print(f"regions: {list(schem.regions.keys())}")

    reg = schem.regions[list(schem.regions.keys())[0]]

    print(f"\n=== 渲染 (每方块 {px}px, X 射线 {layers} 层) ===")
    img_xz = render_xz_xray(reg, px, layers)
    print(f"  俯视图: {img_xz.width}x{img_xz.height}")
    img_xy = render_xy_xray(reg, px, layers)
    print(f"  正视图: {img_xy.width}x{img_xy.height}")
    img_yz = render_yz_xray(reg, px, layers)
    print(f"  侧视图: {img_yz.width}x{img_yz.height}")

    # 统计
    counts = Counter()
    rx = list(range(reg.minx(), reg.maxx() + 1))
    ry = list(range(reg.miny(), reg.maxy() + 1))
    rz = list(range(reg.minz(), reg.maxz() + 1))
    for x in rx:
        for y in ry:
            for z in rz:
                b = reg[x, y, z]
                if b and not is_air(b.id):
                    counts[b.id] += 1
    stats = counts.most_common()

    title = f"{path.stem}  ({len(rx)}×{len(ry)}×{len(rz)})  X 射线 {layers} 层 | 贴图源: MC 1.21.1 client.jar"
    combined = make_combined(img_xz, img_xy, img_yz,
                             ["俯视 (XZ) 看向 -y | top 面贴图",
                              "正视 (XY) 看向 +z | south 面贴图 (按 facing 旋转)",
                              "侧视 (YZ) 看向 -x | west 面贴图 (按 facing 旋转)"],
                             title=title, stats=stats)
    combined.save(out)
    print(f"\n✅ 输出: {out} ({combined.width}x{combined.height})")
    print(f"\n方块统计（top 20）:")
    for bid, cnt in stats[:20]:
        print(f"  {bid}: {cnt}")


if __name__ == "__main__":
    main()
