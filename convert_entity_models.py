#!/usr/bin/env python3
"""
convert_entity_models.py — 把 SizableShrimp/EntityModelJson 的
vanilla entity layer JSON 转换为我们 render_3d.js 能用的 box 模型 JSON。

每个 cube: {origin: [x, y, z], dimensions: [w, h, d], grow, texCoord: {u, v}}
嵌套 children 模拟骨骼, 父 partPose 决定子 part 位置

输出格式: models/entity/<name>.json (类似 block 模型 JSON 格式)
elements: [{from: [x0,y0,z0], to: [x1,y1,z1], faces: {dir: {uv: [u0,v0,u1,v1], texture: "#0"}}}]
textures: {"0": "entity/<name>/<tex>"}
"""

import json
import os
import sys
from pathlib import Path

SRC_DIR = Path('/tmp/allay_search/EntityModelJson/vanilla_layers/main')
DST_DIR = Path('/home/rsegordon/.hermes/scripts/litematic_render/client_assets/assets/minecraft/models/entity')

# 缩放因子: MC box 模型用 0-16 整数像素 (实体大小 16 像素 = 1 块)
# SizableShrimp 用 float 像素 (0-24 范围). 缩放到 0-16 范围
# entity 大小约 16 像素 = 1 块. 单位 px / SCALE
# SCALE=1.5 看起来太大, 改用 0.67 把 0-24 缩到 0-16
SCALE = 0.67


def convert_cube(cube, part_pos, textures, face_count):
    """单个 cube → element {from, to, faces}"""
    ox, oy, oz = cube['origin']
    w, h, d = cube['dimensions']
    grow = cube.get('grow', 0)

    # grow 让边缘变细 (mc 内部用), 我们忽略 (改为实际尺寸)
    # origin 是负的"中心"位置 (-w/2, ..., -h/2, ...)

    # 转 16 像素基准 + 父 part_pos 偏移
    # MC box 模型: from [0,0,0] to [16,16,16] = 1 块
    # 实体: 中心 (8, 8, 8), 实体大小约 1 块
    # 直接用 0-24 范围 (SizableShrimp 默认), 但渲染器按 (x-8)/16 居中
    fx = (ox + part_pos[0]) * SCALE
    fy = (oy + part_pos[1]) * SCALE
    fz = (oz + part_pos[2]) * SCALE
    tx = (ox + w + part_pos[0]) * SCALE
    ty = (oy + h + part_pos[1]) * SCALE
    tz = (oz + d + part_pos[2]) * SCALE

    # 如果某个维度是 0 (翅膀薄板), 给 1 像素厚度
    if tx - fx < 0.5:
        tx = fx + 1
    if ty - fy < 0.5:
        ty = fy + 1
    if tz - fz < 0.5:
        tz = fz + 1

    u, v = cube.get('texCoord', {}).get('u', 0), cube.get('texCoord', {}).get('v', 0)
    # 简化: 整个 cube 用同 UV box (按 w/d/h 拆分六个面)
    w_px, h_px, d_px = w * SCALE, h * SCALE, d * SCALE
    faces = {}
    # 简化: 所有面用全 UV
    faces['west']  = {'uv': [u, v, u + d_px, v + h_px], 'texture': '#0'}
    faces['east']  = {'uv': [u + d_px, v, u + d_px + d_px, v + h_px], 'texture': '#0'}
    faces['up']    = {'uv': [u + d_px, v, u + d_px + w_px, v + d_px], 'texture': '#0'}
    faces['down']  = {'uv': [u + d_px + w_px, v, u + d_px + w_px + w_px, v + d_px], 'texture': '#0'}
    faces['north'] = {'uv': [u + d_px + w_px + d_px, v, u + d_px + w_px + d_px + d_px, v + h_px], 'texture': '#0'}
    faces['south'] = {'uv': [u + d_px + w_px + d_px + d_px, v, u + d_px + w_px + d_px + d_px + d_px, v + h_px], 'texture': '#0'}

    return {
        'from': [round(fx, 2), round(fy, 2), round(fz, 2)],
        'to':   [round(tx, 2), round(ty, 2), round(tz, 2)],
        'faces': faces,
    }


def walk_bones(bones, pos, elements, textures):
    """递归遍历 bones/cubes/children"""
    for name, bone in bones.items():
        if not isinstance(bone, dict):
            continue
        # partPose 是相对父 part 的偏移
        pose = bone.get('partPose', {})
        new_pos = [pos[0] + pose.get('x', 0), pos[1] + pose.get('y', 0), pos[2] + pose.get('z', 0)]

        # cubes
        for cube in bone.get('cubes', []):
            elements.append(convert_cube(cube, new_pos, textures, len(elements)))

        # 递归 children
        if 'children' in bone:
            walk_bones(bone['children'], new_pos, elements, textures)


def convert_one(name):
    src = SRC_DIR / f'{name}.json'
    if not src.exists():
        return False
    with open(src) as f:
        data = json.load(f)

    elements = []
    textures = {}
    # 从 mesh.root.children 开始
    if 'mesh' not in data:
        return False
    root = data['mesh']['root']
    walk_bones(root.get('children', {}), [0, 0, 0], elements, textures)

    if not elements:
        return False

    # 默认 texture = entity/<name>
    # (实际 MC 模型按骨头分纹理, 简化: 所有面用 #0 = entity/<name>)
    # 注意: allay 等实体贴图在 textures/entity/allay/allay.png (子目录)
    # 我们的 entityTexture 期望路径 = textures/entity/<stripped>.png
    # stripped "entity/allay" = "allay"  →  textures/entity/allay.png (不存在)
    # stripped "allay/allay" = "allay/allay"  →  textures/entity/allay/allay.png ✓
    # minecart 类型 (hopper_minecart / chest_minecart / tnt_minecart 等)
    # 都用 minecart.png 在 textures/entity/minecart/ 子目录
    if name == 'allay':
        textures = {'0': 'allay/allay'}
    elif 'minecart' in name:
        textures = {'0': 'minecart/minecart'}
    else:
        # 其他实体贴图直接在 textures/entity/<name>.png
        textures = {'0': name}

    # 添加 ambientocclusion: false
    out = {
        'ambientocclusion': False,
        'textures': textures,
        'elements': elements,
    }

    # 输出文件名: 直接用 SizableShrimp 的名字 (e.g. allay.json)
    dst = DST_DIR / f'{name}.json'
    dst.parent.mkdir(parents=True, exist_ok=True)
    with open(dst, 'w') as f:
        json.dump(out, f, indent=2)
    return True


if __name__ == '__main__':
    if len(sys.argv) > 1:
        names = sys.argv[1:]
    else:
        names = [f.stem for f in sorted(SRC_DIR.glob('*.json'))]

    ok = 0
    for n in names:
        if convert_one(n):
            ok += 1
    print(f"Converted: {ok}/{len(names)}")