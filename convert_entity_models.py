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

# Vanilla 26.2 EntityTypes.Builder.sized(width, height) values.  Entity hitboxes
# are square on the horizontal plane, hence width applies to both X and Z.
ENTITY_DIMENSIONS = {
    # Generated from Minecraft Java 26.2 EntityTypes.<clinit> Builder.sized calls.
    'acacia_boat': (1.375, 0.5625),
    'acacia_chest_boat': (1.375, 0.5625),
    'allay': (0.35, 0.6),
    'area_effect_cloud': (6.0, 0.5),
    'armadillo': (0.7, 0.65),
    'armor_stand': (0.5, 1.975),
    'arrow': (0.5, 0.5),
    'axolotl': (0.75, 0.42),
    'bamboo_chest_raft': (1.375, 0.5625),
    'bamboo_raft': (1.375, 0.5625),
    'bat': (0.5, 0.9),
    'bee': (0.55, 0.5),
    'birch_boat': (1.375, 0.5625),
    'birch_chest_boat': (1.375, 0.5625),
    'blaze': (0.6, 1.8),
    'block_display': (0.0, 0.0),
    'bogged': (0.6, 1.99),
    'breeze': (0.6, 1.77),
    'breeze_wind_charge': (0.3125, 0.3125),
    'camel': (1.7, 2.375),
    'camel_husk': (1.7, 2.375),
    'cat': (0.6, 0.7),
    'cave_spider': (0.7, 0.5),
    'cherry_boat': (1.375, 0.5625),
    'cherry_chest_boat': (1.375, 0.5625),
    'chest_minecart': (0.98, 0.7),
    'chicken': (0.4, 0.7),
    'cod': (0.5, 0.3),
    'copper_golem': (0.49, 0.98),
    'command_block_minecart': (0.98, 0.7),
    'cow': (0.9, 1.4),
    'creaking': (0.9, 2.7),
    'creeper': (0.6, 1.7),
    'dark_oak_boat': (1.375, 0.5625),
    'dark_oak_chest_boat': (1.375, 0.5625),
    'dolphin': (0.9, 0.6),
    'donkey': (1.3964844, 1.5),
    'dragon_fireball': (1.0, 1.0),
    'drowned': (0.6, 1.95),
    'egg': (0.25, 0.25),
    'elder_guardian': (1.9975, 1.9975),
    'enderman': (0.6, 2.9),
    'endermite': (0.4, 0.3),
    'ender_dragon': (16.0, 8.0),
    'ender_pearl': (0.25, 0.25),
    'end_crystal': (2.0, 2.0),
    'evoker': (0.6, 1.95),
    'evoker_fangs': (0.5, 0.8),
    'experience_bottle': (0.25, 0.25),
    'experience_orb': (0.5, 0.5),
    'eye_of_ender': (0.25, 0.25),
    'falling_block': (0.98, 0.98),
    'fireball': (1.0, 1.0),
    'firework_rocket': (0.25, 0.25),
    'fox': (0.6, 0.7),
    'frog': (0.5, 0.5),
    'furnace_minecart': (0.98, 0.7),
    'ghast': (4.0, 4.0),
    'happy_ghast': (4.0, 4.0),
    'giant': (3.6, 12.0),
    'glow_item_frame': (0.5, 0.5),
    'glow_squid': (0.8, 0.8),
    'goat': (0.9, 1.3),
    'guardian': (0.85, 0.85),
    'hoglin': (1.3964844, 1.4),
    'hopper_minecart': (0.98, 0.7),
    'horse': (1.3964844, 1.6),
    'husk': (0.6, 1.95),
    'illusioner': (0.6, 1.95),
    'interaction': (0.0, 0.0),
    'iron_golem': (1.4, 2.7),
    'item': (0.25, 0.25),
    'item_display': (0.0, 0.0),
    'item_frame': (0.5, 0.5),
    'jungle_boat': (1.375, 0.5625),
    'jungle_chest_boat': (1.375, 0.5625),
    'leash_knot': (0.375, 0.5),
    'lightning_bolt': (0.0, 0.0),
    'llama': (0.9, 1.87),
    'llama_spit': (0.25, 0.25),
    'magma_cube': (0.52, 0.52),
    'mangrove_boat': (1.375, 0.5625),
    'mangrove_chest_boat': (1.375, 0.5625),
    'mannequin': (0.6, 1.8),
    'marker': (0.0, 0.0),
    'minecart': (0.98, 0.7),
    'mooshroom': (0.9, 1.4),
    'mule': (1.3964844, 1.6),
    'nautilus': (0.875, 0.95),
    'oak_boat': (1.375, 0.5625),
    'oak_chest_boat': (1.375, 0.5625),
    'ocelot': (0.6, 0.7),
    'ominous_item_spawner': (0.25, 0.25),
    'painting': (0.5, 0.5),
    'pale_oak_boat': (1.375, 0.5625),
    'pale_oak_chest_boat': (1.375, 0.5625),
    'panda': (1.3, 1.25),
    'parched': (0.6, 1.99),
    'parrot': (0.5, 0.9),
    'phantom': (0.9, 0.5),
    'pig': (0.9, 0.9),
    'piglin': (0.6, 1.95),
    'piglin_brute': (0.6, 1.95),
    'pillager': (0.6, 1.95),
    'polar_bear': (1.4, 1.4),
    'splash_potion': (0.25, 0.25),
    'lingering_potion': (0.25, 0.25),
    'pufferfish': (0.7, 0.7),
    'rabbit': (0.49, 0.6),
    'ravager': (1.95, 2.2),
    'salmon': (0.7, 0.4),
    'sheep': (0.9, 1.3),
    'shulker': (1.0, 1.0),
    'shulker_bullet': (0.3125, 0.3125),
    'silverfish': (0.4, 0.3),
    'skeleton': (0.6, 1.99),
    'skeleton_horse': (1.3964844, 1.6),
    'slime': (0.52, 0.52),
    'small_fireball': (0.3125, 0.3125),
    'sniffer': (1.9, 1.75),
    'snowball': (0.25, 0.25),
    'snow_golem': (0.7, 1.9),
    'spawner_minecart': (0.98, 0.7),
    'spectral_arrow': (0.5, 0.5),
    'spider': (1.4, 0.9),
    'spruce_boat': (1.375, 0.5625),
    'spruce_chest_boat': (1.375, 0.5625),
    'squid': (0.8, 0.8),
    'stray': (0.6, 1.99),
    'strider': (0.9, 1.7),
    'sulfur_cube': (0.49, 0.49),
    'tadpole': (0.4, 0.3),
    'text_display': (0.0, 0.0),
    'tnt': (0.98, 0.98),
    'tnt_minecart': (0.98, 0.7),
    'trader_llama': (0.9, 1.87),
    'trident': (0.5, 0.5),
    'tropical_fish': (0.5, 0.4),
    'turtle': (1.2, 0.4),
    'vex': (0.4, 0.8),
    'villager': (0.6, 1.95),
    'vindicator': (0.6, 1.95),
    'wandering_trader': (0.6, 1.95),
    'warden': (0.9, 2.9),
    'wind_charge': (0.3125, 0.3125),
    'witch': (0.6, 1.95),
    'wither': (0.9, 3.5),
    'wither_skeleton': (0.7, 2.4),
    'wither_skull': (0.3125, 0.3125),
    'wolf': (0.6, 0.85),
    'zoglin': (1.3964844, 1.4),
    'zombie': (0.6, 1.95),
    'zombie_horse': (1.3964844, 1.6),
    'zombie_nautilus': (0.875, 0.95),
    'zombie_villager': (0.6, 1.95),
    'zombified_piglin': (0.6, 1.95),
    'player': (0.6, 1.8),
    'fishing_bobber': (0.25, 0.25),
}

# Exact output of Minecraft 26.2 RaftModel.createRaftModel().  The version of
# EntityModelJson available locally predates rafts, so retain Mojang's cubes,
# part poses, texture offsets and 128x64 material here as a source layer.
VANILLA_LAYER_OVERRIDES = {
    'bamboo_raft': {
        'material': {'xTexSize': 128, 'yTexSize': 64},
        'mesh': {'root': {'children': {
            'bottom': {
                'cubes': [
                    {'texCoord': {'u': 0, 'v': 0}, 'origin': [-14, -11, -4],
                     'dimensions': [28, 20, 4]},
                    {'texCoord': {'u': 0, 'v': 0}, 'origin': [-14, -9, -8],
                     'dimensions': [28, 16, 4]},
                ],
                'partPose': {'xRot': 1.5708, 'y': -2.1, 'z': 1},
            },
            'left_paddle': {
                'cubes': [
                    {'texCoord': {'u': 0, 'v': 24}, 'origin': [-1, 0, -5],
                     'dimensions': [2, 2, 18]},
                    {'texCoord': {'u': 0, 'v': 24}, 'origin': [-1.001, -3, 8],
                     'dimensions': [1, 6, 7]},
                ],
                'partPose': {'x': 3, 'y': -4, 'z': 9, 'zRot': 0.19634955},
            },
            'right_paddle': {
                'cubes': [
                    {'texCoord': {'u': 40, 'v': 24}, 'origin': [-1, 0, -5],
                     'dimensions': [2, 2, 18]},
                    {'texCoord': {'u': 40, 'v': 24}, 'origin': [0.001, -3, 8],
                     'dimensions': [1, 6, 7]},
                ],
                'partPose': {'x': 3, 'y': -4, 'z': -9, 'yRot': 3.1415927,
                             'zRot': 0.19634955},
            },
        }}},
    },
}


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
    if name in VANILLA_LAYER_OVERRIDES:
        data = VANILLA_LAYER_OVERRIDES[name]
    elif src.exists():
        with open(src) as f:
            data = json.load(f)
    else:
        return False

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
    # Scale each model axis to the vanilla 26.2 hitbox instead of fitting the
    # largest model span into an arbitrary one-block cube. ModelPart Y grows
    # downward, opposite to THREE.js, so reflect Y after grounding the minimum.
    points = [point for element in elements for face in element['vertices'].values() for point in face]
    mins = [min(p[i] for p in points) for i in range(3)]
    maxs = [max(p[i] for p in points) for i in range(3)]
    spans = [maxs[i] - mins[i] for i in range(3)]
    dimensions = ENTITY_DIMENSIONS.get(name)
    if dimensions is None:
        # The layer dump also contains non-entity render layers (beds, chests,
        # shields, skulls, etc.). They have no EntityType hitbox and must not be
        # silently assigned the old arbitrary one-block fit.
        return False
    width, height = dimensions
    targets = [width * 16, height * 16, width * 16]
    scales = [targets[i] / max(spans[i], 1e-9) for i in range(3)]
    offsets = [8 - (mins[0] + maxs[0]) * scales[0] / 2,
               -mins[1] * scales[1],
               8 - (mins[2] + maxs[2]) * scales[2] / 2]
    for element in elements:
        for face, vertices in element['vertices'].items():
            normalized = [[p[i] * scales[i] + offsets[i] for i in range(3)] for p in vertices]
            element['vertices'][face] = [[round(p[0], 5), round(targets[1] - p[1], 5), round(p[2], 5)]
                                         for p in normalized]

    if 'minecart' in name:
        texture_name = 'minecart/minecart'
    else:
        # Special-case the few vanilla entities whose MC texture path
        # differs from the entity id. Detect by on-disk existence rather
        # than hard-coding each variant, since the 26.x asset pack
        # already exposes the canonical Mojang layout.
        candidates = [name, f'{name}/{name}']
        # Common vanilla families use a subdirectory for their base skin.
        family = name.removeprefix('cave_').removeprefix('zombie_')
        candidates += [f'{family}/{name}', f'{family}/{family}']
        # Joined-word fallback: armor_stand -> armorstand/ subdir.
        joined = name.replace('_', '')
        candidates += [joined, f'{joined}/{joined}', f'{joined}/{name}']
        # Boat/oak_boat lives under textures/entity/boat/oak.png because
        # the on-disk file is split by wood type, not by entity id.
        if name.endswith('_boat'):
            wood = name.removesuffix('_boat')
            candidates += [f'boat/{wood}', f'{wood}/{name}']
        if name.endswith('_raft'):
            wood = name.removesuffix('_raft')
            candidates += [f'boat/{wood}', f'{wood}/{name}']
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
