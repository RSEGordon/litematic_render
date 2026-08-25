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
import math
from pathlib import Path

SRC_DIR = Path('/tmp/allay_search/EntityModelJson/vanilla_layers/main')
DST_DIR = Path('/home/rsegordon/.hermes/scripts/litematic_render/client_assets/assets/minecraft/models/entity')

# Entity textures keep their native pixel dimensions; transformed geometry is
# uniformly fitted to the renderer's 0..16 model space after all bones are read.
TEXTURE_DIR = DST_DIR.parents[1] / 'textures' / 'entity'


def mat_mul(a, b):
    return [[sum(a[r][k] * b[k][c] for k in range(4)) for c in range(4)] for r in range(4)]


def pose_matrix(pose):
    """Minecraft ModelPart pose: translate, then Z/Y/X rotations (angles are radians)."""
    x, y, z = (pose.get(k, 0.0) for k in ('x', 'y', 'z'))
    rx, ry, rz = (pose.get(k, 0.0) for k in ('xRot', 'yRot', 'zRot'))
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    t = [[1, 0, 0, x], [0, 1, 0, y], [0, 0, 1, z], [0, 0, 0, 1]]
    mx = [[1, 0, 0, 0], [0, cx, -sx, 0], [0, sx, cx, 0], [0, 0, 0, 1]]
    my = [[cy, 0, sy, 0], [0, 1, 0, 0], [-sy, 0, cy, 0], [0, 0, 0, 1]]
    mz = [[cz, -sz, 0, 0], [sz, cz, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
    return mat_mul(t, mat_mul(mz, mat_mul(my, mx)))


def transform_point(matrix, point):
    p = [*point, 1.0]
    return [sum(matrix[r][k] * p[k] for k in range(4)) for r in range(3)]


def convert_cube(cube, matrix, texture_size):
    """单个 cube → element {from, to, faces}

    origin 是 cube 最小角相对当前 bone 原点的偏移 (SizableShrimp 用 box 中心为 0,0,0)。
    加上 part_pos (父 bone 的累积位移) → 相对模型中心的坐标。
    模型中心在 (8, 8, 8) 渲染 1 块内, 加 8 偏移让 boxes 居中到 0-16 范围。
    """
    ox, oy, oz = cube['origin']
    w, h, d = cube['dimensions']
    grow = cube.get('grow', 0)

    # grow 让边缘变细 (mc 内部用), 我们忽略 (改为实际尺寸)

    # Keep native cube dimensions. Bone transforms are applied to every vertex;
    # this preserves rotated limbs instead of flattening them into an AABB.
    x1, y1, z1 = ox + w, oy + h, oz + d
    corners = {(x, y, z): transform_point(matrix, (x, y, z))
               for x in (ox, x1) for y in (oy, y1) for z in (oz, z1)}
    vertex_keys = {
        'east': [(x1, oy, oz), (x1, oy, z1), (x1, y1, z1), (x1, y1, oz)],
        'west': [(ox, oy, z1), (ox, oy, oz), (ox, y1, oz), (ox, y1, z1)],
        'up': [(ox, y1, oz), (x1, y1, oz), (x1, y1, z1), (ox, y1, z1)],
        'down': [(ox, oy, z1), (x1, oy, z1), (x1, oy, oz), (ox, oy, oz)],
        'south': [(x1, oy, z1), (ox, oy, z1), (ox, y1, z1), (x1, y1, z1)],
        'north': [(ox, oy, oz), (x1, oy, oz), (x1, y1, oz), (ox, y1, oz)],
    }

    u, v = cube.get('texCoord', {}).get('u', 0), cube.get('texCoord', {}).get('v', 0)
    # Vanilla CubeListBuilder unfolding. UVs stay in source texture pixels and
    # are normalized to the renderer's 0..16 convention using material size.
    tw, th = texture_size
    norm = lambda box: [box[0] * 16 / tw, box[1] * 16 / th,
                        box[2] * 16 / tw, box[3] * 16 / th]
    boxes = {
        'west': [u, v + d, u + d, v + d + h],
        'north': [u + d, v + d, u + d + w, v + d + h],
        'east': [u + d + w, v + d, u + 2*d + w, v + d + h],
        'south': [u + 2*d + w, v + d, u + 2*d + 2*w, v + d + h],
        'up': [u + d, v, u + d + w, v + d],
        'down': [u + d + w, v, u + d + 2*w, v + d],
    }
    # ModelPart Y is reflected into THREE.js Y-up space after conversion.  On
    # the four vertical faces that reflection swaps the first vertex pair from
    # the cube bottom to the cube top.  render_3d.js maps [u0,v0,u1,v1] as
    # [v1,v1,v0,v0], so reverse the V endpoints to keep Mojang's top texels on
    # the reflected cube top.  Up/down use texture V along Z and are unaffected
    # by the Y reflection.
    for direction in ('west', 'north', 'east', 'south'):
        u0, v0, u1, v1 = boxes[direction]
        boxes[direction] = [u0, v1, u1, v0]
    faces = {direction: {'uv': norm(box), 'texture': '#0'} for direction, box in boxes.items()}

    return {
        'vertices': {direction: [[round(c, 5) for c in corners[key]] for key in keys]
                     for direction, keys in vertex_keys.items()},
        'faces': faces,
    }


def walk_bones(bones, parent_matrix, elements, texture_size):
    """递归遍历 bones/cubes/children"""
    for name, bone in bones.items():
        if not isinstance(bone, dict):
            continue
        # partPose 是相对父 part 的偏移
        pose = bone.get('partPose', {})
        matrix = mat_mul(parent_matrix, pose_matrix(pose))

        # cubes
        for cube in bone.get('cubes', []):
            elements.append(convert_cube(cube, matrix, texture_size))

        # 递归 children
        if 'children' in bone:
            walk_bones(bone['children'], matrix, elements, texture_size)


def convert_one(name):
    src = SRC_DIR / f'{name}.json'
    if not src.exists():
        return False
    with open(src) as f:
        data = json.load(f)

    elements = []
    material = data.get('material', {})
    texture_size = (float(material.get('xTexSize', 64)), float(material.get('yTexSize', 64)))
    # 从 mesh.root.children 开始
    if 'mesh' not in data:
        return False
    root = data['mesh']['root']
    identity = [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
    walk_bones(root.get('children', {}), identity, elements, texture_size)

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
    # Uniformly fit the complete transformed model into one block. ModelPart Y
    # grows downward, opposite to THREE.js, so reflect Y inside the normalized
    # 0..16 model box. X/Z remain centered around 8.
    points = [point for element in elements for face in element['vertices'].values() for point in face]
    mins = [min(p[i] for p in points) for i in range(3)]
    maxs = [max(p[i] for p in points) for i in range(3)]
    spans = [maxs[i] - mins[i] for i in range(3)]
    scale = 16 / max(max(spans), 1e-9)
    offsets = [8 - (mins[0] + maxs[0]) * scale / 2, -mins[1] * scale,
               8 - (mins[2] + maxs[2]) * scale / 2]
    for element in elements:
        for face, vertices in element['vertices'].items():
            normalized = [[p[i] * scale + offsets[i] for i in range(3)] for p in vertices]
            element['vertices'][face] = [[round(p[0], 5), round(16 - p[1], 5), round(p[2], 5)]
                                         for p in normalized]

    if 'minecart' in name:
        texture_name = 'minecart/minecart'
    else:
        candidates = [name, f'{name}/{name}']
        # Common vanilla families use a subdirectory for their base skin.
        family = name.removeprefix('cave_').removeprefix('zombie_')
        candidates += [f'{family}/{name}', f'{family}/{family}']
        texture_name = next((candidate for candidate in candidates
                             if (TEXTURE_DIR / f'{candidate}.png').exists()), name)
    textures = {'0': texture_name}

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
