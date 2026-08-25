#!/usr/bin/env python3
"""
litematic → ASCII 三视图（方案 F）
输入: .litematic 文件
输出: 俯视图(XZ) / 正视图(XY) / 侧视图(YZ) 三张字符图
"""

import sys
from pathlib import Path
import litemapy

# block id -> 1 字符 映射（精简版，覆盖常用方块）
BLOCK_CHARS = {
    "minecraft:air": " ",
    "minecraft:stone": "#",
    "minecraft:cobblestone": ":",
    "minecraft:dirt": ".",
    "minecraft:grass_block": ",",
    "minecraft:water": "~",
    "minecraft:lava": "L",
    "minecraft:sand": "s",
    "minecraft:gravel": "g",
    "minecraft:oak_log": "T",
    "minecraft:spruce_log": "T",
    "minecraft:birch_log": "T",
    "minecraft:jungle_log": "T",
    "minecraft:acacia_log": "T",
    "minecraft:dark_oak_log": "T",
    "minecraft:oak_leaves": "*",
    "minecraft:spruce_leaves": "*",
    "minecraft:birch_leaves": "*",
    "minecraft:jungle_leaves": "*",
    "minecraft:acacia_leaves": "*",
    "minecraft:dark_oak_leaves": "*",
    "minecraft:glass": "+",
    "minecraft:oak_planks": "=",
    "minecraft:spruce_planks": "=",
    "minecraft:birch_planks": "=",
    "minecraft:iron_block": "I",
    "minecraft:gold_block": "G",
    "minecraft:diamond_block": "D",
    "minecraft:emerald_block": "E",
    "minecraft:redstone_block": "R",
    "minecraft:coal_block": "C",
    "minecraft:obsidian": "@",
    "minecraft:netherrack": "n",
    "minecraft:soul_sand": "u",
    "minecraft:chest": "B",
    "minecraft:crafting_table": "x",
    "minecraft:furnace": "F",
    "minecraft:hopper": "h",
    "minecraft:dispenser": "d",
    "minecraft:dropper": "D",
    "minecraft:piston": "P",
    "minecraft:sticky_piston": "p",
    "minecraft:redstone_wire": "r",
    "minecraft:redstone_torch": "t",
    "minecraft:repeater": "=",
    "minecraft:comparator": "=",
    "minecraft:lever": "l",
    "minecraft:stone_button": "b",
    "minecraft:tripwire_hook": "=",
    "minecraft:tripwire": "=",
    "minecraft:rail": "_",
    "minecraft:powered_rail": "_",
    "minecraft:detector_rail": "_",
    "minecraft:activator_rail": "_",
    "minecraft:tnt": "^",
    "minecraft:torch": "T",
    "minecraft:soul_torch": "T",
    "minecraft:ladder": "H",
    "minecraft:vine": "v",
    "minecraft:ladder": "H",
    "minecraft:barrel": "B",
    "minecraft:shulker_box": "B",
    "minecraft:sandstone": "S",
    "minecraft:brick": "K",
    "minecraft:stone_bricks": "k",
    "minecraft:mossy_stone_bricks": "k",
    "minecraft:nether_bricks": "k",
    "minecraft:smooth_stone": "=",
    "minecraft:slime": "%",
    "minecraft:honeycomb_block": "%",
    "minecraft:sponge": "S",
    "minecraft:wet_sponge": "S",
    "minecraft:bookshelf": "=",
    "minecraft:end_stone": "e",
    "minecraft:end_stone_bricks": "e",
    "minecraft:purpur_block": "p",
    "minecraft:quartz_block": "q",
    "minecraft:smooth_quartz": "q",
    "minecraft:hay_block": "y",
    "minecraft:dried_kelp_block": "=",
    "minecraft:bone_block": "=",
    "minecraft:melon": "M",
    "minecraft:pumpkin": "P",
    "minecraft:carved_pumpkin": "P",
    "minecraft:jack_o_lantern": "P",
    "minecraft:glowstone": "g",
    "minecraft:sea_lantern": "g",
    "minecraft:redstone_lamp": "=",
    "minecraft:snow": "*",
    "minecraft:snow_block": "S",
    "minecraft:clay": "=",
    "minecraft:terracotta": "=",
    "minecraft:cyan_terracotta": "=",
    "minecraft:white_terracotta": "=",
    "minecraft:black_concrete": "#",
    "minecraft:white_concrete": " ",
    "minecraft:red_concrete": "R",
    "minecraft:blue_concrete": "B",
    "minecraft:green_concrete": "G",
    "minecraft:yellow_concrete": "Y",
    "minecraft:cyan_concrete": "C",
    "minecraft:magenta_concrete": "M",
    "minecraft:wool": "w",
    "minecraft:white_wool": "w",
    "minecraft:carpet": "c",
}


def block_to_char(block):
    if block is None:
        return " "
    return BLOCK_CHARS.get(block.id, "?")


def render_layer(reg, y):
    """渲染 y 这一层的 xz 平面字符图，返回 str"""
    rx = list(range(reg.minx(), reg.maxx() + 1))
    rz = list(range(reg.minz(), reg.maxz() + 1))
    rows = []
    for x in rx:
        row = ""
        for z in rz:
            b = reg[x, y, z]
            row += block_to_char(b)
        rows.append(row)
    return "\n".join(rows)


def render_layer_yz(reg, x):
    """渲染 x 这一列的 yz 平面（侧视图）"""
    ry = list(range(reg.miny(), reg.maxy() + 1))
    rz = list(range(reg.minz(), reg.maxz() + 1))
    rows = []
    for z in rz:
        row = ""
        for y in ry:
            b = reg[x, y, z]
            row += block_to_char(b)
        rows.append(row)
    return "\n".join(rows)


def render_layer_xy(reg, z):
    """渲染 z 这一列的 xy 平面（正视图）"""
    rx = list(range(reg.minx(), reg.maxx() + 1))
    ry = list(range(reg.miny(), reg.maxy() + 1))
    rows = []
    for x in rx:
        row = ""
        for y in ry:
            b = reg[x, y, z]
            row += block_to_char(b)
        rows.append(row)
    return "\n".join(rows)


def main():
    if len(sys.argv) < 2:
        print("用法: python f_render_ascii.py <path/to/file.litematic> [xz|xy|yz|all] [y_slice]")
        print("  xz  俯视图（默认 y 取中位层）")
        print("  xy  正视图")
        print("  yz  侧视图")
        print("  all 三视图 + 方块统计")
        sys.exit(1)

    path = Path(sys.argv[1])
    if not path.exists():
        print(f"文件不存在: {path}")
        sys.exit(1)

    schem = litemapy.Schematic.load(str(path))
    print(f"=== {path.name} ===")
    print(f"regions: {list(schem.regions.keys())}")
    print(f"width: {schem.width}, height: {schem.height}, length: {schem.length}")

    for name, reg in schem.regions.items():
        rx = list(range(reg.minx(), reg.maxx() + 1))
        ry = list(range(reg.miny(), reg.maxy() + 1))
        rz = list(range(reg.minz(), reg.maxz() + 1))
        print(f"\n### region: {name}")
        print(f"range_x: {reg.minx()} ~ {reg.maxx()} ({len(rx)})")
        print(f"range_y: {reg.miny()} ~ {reg.maxy()} ({len(ry)})")
        print(f"range_z: {reg.minz()} ~ {reg.maxz()} ({len(rz)})")
        print(f"volume: {reg.getvolume()}, count_blocks: {reg.count_blocks()}")

        # 方块统计
        counts = {}
        for x in rx:
            for y in ry:
                for z in rz:
                    b = reg.getblock(x, y, z)
                    if b and b.id != "minecraft:air":
                        counts[b.id] = counts.get(b.id, 0) + 1
        print(f"\n方块统计（top 20）:")
        for bid, cnt in sorted(counts.items(), key=lambda kv: -kv[1])[:20]:
            print(f"  {bid}: {cnt}")

        # 三视图
        view = sys.argv[2] if len(sys.argv) >= 3 else "all"
        if view in ("xz", "all"):
            y_mid = reg.miny() + (reg.maxy() - reg.miny()) // 2
            y_slice = int(sys.argv[3]) if len(sys.argv) >= 4 else y_mid
            y_slice = max(reg.miny(), min(reg.maxy(), y_slice))
            print(f"\n--- 俯视图 (XZ 平面, y={y_slice}) ---")
            print(render_layer(reg, y_slice))

        if view in ("xy", "all"):
            z_mid = reg.minz() + (reg.maxz() - reg.minz()) // 2
            print(f"\n--- 正视图 (XY 平面, z={z_mid}) ---")
            print(render_layer_xy(reg, z_mid))

        if view in ("yz", "all"):
            x_mid = reg.minx() + (reg.maxx() - reg.minx()) // 2
            print(f"\n--- 侧视图 (YZ 平面, x={x_mid}) ---")
            print(render_layer_yz(reg, x_mid))


if __name__ == "__main__":
    main()
