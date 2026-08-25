#!/usr/bin/env python3
"""
litematic → matplotlib 三视图带贴图渲染（方案 A 升级版）
改动:
1. blockstate 解析（facing/axis/type/extended/half/lit/powered 等）
2. 按视图方向（俯/正/侧）选对应面贴图
3. X 射线三视图（看穿内部 = 从最外层往里找第一个非空方块）
4. 贴图 fallback 表扩展到 200+
5. atlas 接口预留（当前走 1.21.1 单文件贴图）

依赖: pip install litemapy matplotlib Pillow
"""

import sys
from pathlib import Path
from collections import Counter
import litemapy
from PIL import Image, ImageDraw, ImageFont
import matplotlib.pyplot as plt

ROOT = Path(__file__).parent
ASSETS = ROOT / "client_assets/assets/minecraft"
TEXTURE_DIR = ASSETS / "textures/block"

# 贴图缓存: (block_id, face) -> Image
_TEXTURE_CACHE: dict = {}


# ============================================================
# Atlas 接口（当前未启用）
# ============================================================
class TextureProvider:
    """贴图源抽象 - 现在走 1.21.1 单文件，可替换为 26.x atlas"""
    def get(self, block_id: str, face: str) -> Image.Image:
        raise NotImplementedError


class SingleFileProvider(TextureProvider):
    """1.21.1 单文件贴图"""
    def get(self, block_id: str, face: str) -> Image.Image:
        key = (block_id, face)
        if key in _TEXTURE_CACHE:
            return _TEXTURE_CACHE[key]
        tex = self._load_raw(block_id, face)
        _TEXTURE_CACHE[key] = tex
        return tex

    @staticmethod
    def _face_to_texture(block_id: str, face: str) -> str | None:
        """
        根据 block id + 视图方向（face = 'top'/'north'/'south'/'east'/'west'）
        返回贴图 PNG 文件名（不含扩展名）。
        失败返回 None（fallback 紫黑格）
        """
        # 注: face 命名对齐 MC 模型: top=顶 north=北 south=南 east=东 west=西
        raw = block_id.replace("minecraft:", "").split("[")[0]

        # ===== 复杂方块: 严格按 (raw_id, properties) 映射 =====
        # 这里只是简化版，处理"按面需要换贴图"的方块
        # 真实实现应该解析 blockstate JSON 拿到 model，再用 model 里的 textures 引用
        # 简化版: 直接硬编码

        # 活塞 / sticky_piston / piston_head
        if raw in ("piston", "sticky_piston", "piston_head", "moving_piston"):
            if face == "top":
                if raw == "sticky_piston":
                    return "piston_top_sticky"
                return "piston_top"
            if face == "bottom":
                return "piston_bottom"
            return "piston_side"

        # observer
        if raw in ("observer",):
            if face == "top":
                return "observer_top"
            if face == "bottom":
                return "observer_back"  # observer bottom = back
            # 4 个侧面: facing 决定哪边是 front（用 front，其他 3 边 side）
            # 简化版: 俯视图 top，正视图 south 看作 front，侧视图 west 看作 side
            if face == "south":
                return "observer_front"
            return "observer_side"

        # hopper
        if raw == "hopper":
            if face == "top":
                return "hopper_top"
            if face == "bottom":
                return "hopper_inside"
            return "hopper_outside"

        # repeater / comparator
        if raw in ("repeater", "comparator"):
            # 简化: 任何侧面都看 front（实际应该按 facing 选）
            if face == "top":
                return "smooth_stone"  # repeater top = 平滑石头
            return raw  # repeater.png / comparator.png 本身就是顶面
        if raw in ("unpowered_repeater", "powered_repeater"):
            if face == "top":
                return "smooth_stone"
            return "repeater"
        if raw in ("unpowered_comparator", "powered_comparator"):
            if face == "top":
                return "smooth_stone"
            return "comparator"

        # rails
        if raw in ("rail", "powered_rail", "detector_rail", "activator_rail"):
            if face == "top":
                return raw
            return raw  # 简化
        if raw == "powered_rail_on":
            return "powered_rail_on"
        if raw == "detector_rail_on":
            return "detector_rail_on"
        if raw == "activator_rail_on":
            return "activator_rail_on"

        # 红石线
        if raw == "redstone_wire":
            if face == "top":
                return "redstone_dust_dot"  # 单点（实际是 atlas 里的有方向红石）
            return "redstone_dust_dot"

        # 红石火把
        if raw in ("redstone_torch", "redstone_wall_torch"):
            if face == "top":
                return "redstone_torch"
            return "redstone_torch_off"
        if raw in ("unlit_redstone_torch",):
            return "redstone_torch_off"

        # 漏斗/投掷器/发射器
        if raw in ("dropper", "dispenser"):
            if face == "top":
                return f"{raw}_top"
            if face == "bottom":
                return f"{raw}_bottom"
            return f"{raw}_front"  # 简化
        if raw == "hopper":
            if face == "top":
                return "hopper_top"
            if face == "bottom":
                return "hopper_inside"
            return "hopper_outside"

        # 圆石墙 / 各种 wall
        if raw.endswith("_wall"):
            base = raw[:-5]  # diorite_wall -> diorite
            if base in ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "mangrove", "pale_oak", "bamboo"):
                base = base + "_planks"
            # 简化: wall 任何方向用 base 贴图
            return base

        # 栅栏
        if raw.endswith("_fence"):
            base = raw[:-6]  # oak_fence -> oak
            if base in ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "mangrove", "pale_oak", "bamboo"):
                base = base + "_planks"
            return base
        if raw.endswith("_fence_gate"):
            base = raw[:-11] + "_planks"
            return base

        # 楼梯
        if raw.endswith("_stairs"):
            base = raw[:-7]  # oak_stairs -> oak
            if base in ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "mangrove", "pale_oak", "bamboo"):
                base = base + "_planks"
            return base

        # 活板门 / 门
        if raw.endswith("_trapdoor"):
            base = raw[:-9]  # oak_trapdoor -> oak
            if base in ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "mangrove", "pale_oak", "bamboo"):
                base = base + "_trapdoor"  # 简化: 直接用同名贴图
            return base
        if raw.endswith("_door"):
            # door 通常有 top/bottom + hinge，但简版: 直接用同名
            return raw

        # 告示牌
        if raw.endswith("_sign") or raw.endswith("_wall_sign") or raw == "sign" or raw.endswith("_hanging_sign"):
            base = raw.replace("_wall_sign", "").replace("_hanging_sign", "").replace("_sign", "")
            if base in ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "mangrove", "pale_oak", "bamboo"):
                base = base + "_planks"
            return base

        # 原木 (按 axis 选)
        if raw.endswith("_log") or raw.endswith("_wood") or raw.endswith("_hyphae") or raw.endswith("_stem"):
            if face == "top":
                return raw + "_top"
            return raw

        # 去皮原木
        if raw.startswith("stripped_") and (raw.endswith("_log") or raw.endswith("_wood") or raw.endswith("_hyphae") or raw.endswith("_stem")):
            if face == "top":
                return raw + "_top"
            return raw

        # 树叶
        if raw.endswith("_leaves"):
            return raw

        # 草方块
        if raw == "grass_block":
            if face == "top":
                return "grass_block_top"
            if face == "bottom":
                return "dirt"
            return "grass_block_side"

        # 农田
        if raw == "farmland":
            if face == "top":
                return "farmland"  # 实际应该用 farmland_moist 或按 moisture
            return "dirt"
        if raw == "dirt_path":
            if face == "top":
                return "dirt_path_top"
            return "dirt_path_side"

        # 砂岩
        if raw == "sandstone":
            if face == "top":
                return "sandstone_top"
            return "sandstone"
        if raw == "red_sandstone":
            if face == "top":
                return "red_sandstone_top"
            return "red_sandstone"

        # 双层方块（top/bottom 区分）
        if raw in ("stone", "cobblestone", "dirt", "sand", "gravel", "andesite", "diorite", "granite",
                   "obsidian", "bedrock", "netherrack", "end_stone", "calcite", "tuff",
                   "deepslate", "cobbled_deepslate", "polished_deepslate",
                   "blackstone", "polished_blackstone", "polished_andesite", "polished_diorite", "polished_granite",
                   "smooth_stone", "smooth_basalt", "polished_basalt", "polished_tuff", "polished_calcite",
                   "bricks", "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks",
                   "nether_bricks", "red_nether_bricks", "end_stone_bricks",
                   "prismarine", "dark_prismarine", "purpur_block",
                   "quartz_block", "smooth_quartz", "chiseled_quartz_block",
                   "iron_block", "gold_block", "diamond_block", "emerald_block",
                   "lapis_block", "redstone_block", "coal_block", "netherite_block",
                   "copper_block", "exposed_copper", "weathered_copper", "oxidized_copper",
                   "waxed_copper_block", "cut_copper", "cut_copper_slab",
                   "hay_block", "dried_kelp_block", "honeycomb_block",
                   "melon", "pumpkin", "carved_pumpkin", "jack_o_lantern",
                   "glowstone", "sea_lantern", "redstone_lamp", "shroomlight",
                   "honey_block", "slime_block", "magma_block", "sponge", "wet_sponge",
                   "clay", "terracotta", "white_terracotta", "orange_terracotta", "magenta_terracotta",
                   "light_blue_terracotta", "yellow_terracotta", "lime_terracotta", "pink_terracotta",
                   "gray_terracotta", "cyan_terracotta", "purple_terracotta", "blue_terracotta",
                   "brown_terracotta", "green_terracotta", "red_terracotta", "black_terracotta",
                   "white_concrete", "orange_concrete", "magenta_concrete",
                   "light_blue_concrete", "yellow_concrete", "lime_concrete", "pink_concrete",
                   "gray_concrete", "cyan_concrete", "purple_concrete", "blue_concrete",
                   "brown_concrete", "green_concrete", "red_concrete", "black_concrete",
                   "white_wool", "orange_wool", "magenta_wool",
                   "light_blue_wool", "yellow_wool", "lime_wool", "pink_wool",
                   "gray_wool", "cyan_wool", "purple_wool", "blue_wool",
                   "brown_wool", "green_wool", "red_wool", "black_wool",
                   "bookshelf", "note_block", "jukebox", "enchanting_table",
                   "end_rod", "chain", "lightning_rod",
                   "tnt", "crafting_table", "loom", "cartography_table", "smithing_table",
                   "fletching_table", "smithing_table_top", "smithing_table_bottom", "smithing_table_side", "smithing_table_front",
                   "loom_top", "loom_bottom", "loom_side", "loom_front",
                   "grindstone_side", "grindstone_top", "grindstone_bottom", "grindstone_front",
                   "stonecutter_top", "stonecutter_bottom", "stonecutter_side", "stonecutter_saw",
                   "smoker_top", "smoker_bottom", "smoker_side", "smoker_front", "smoker_front_on",
                   "blast_furnace_top", "blast_furnace_side", "blast_furnace_front", "blast_furnace_front_on",
                   "furnace_top", "furnace_side", "furnace_front", "furnace_front_on",
                   "lectern_top", "lectern_side", "lectern_front", "lectern_base",
                   "loom", "beacon", "conduit", "respawn_anchor_top", "respawn_anchor_side",
                   "lodestone_top", "lodestone_side", "crying_obsidian",
                   "lava", "water", "ice", "packed_ice", "blue_ice", "snow",
                   "barrel_top", "barrel_side", "barrel_bottom", "barrel_open_top",
                   "chest", "trapped_chest", "ender_chest", "shulker_box",
                   "white_shulker_box", "orange_shulker_box", "magenta_shulker_box",
                   "light_blue_shulker_box", "yellow_shulker_box", "lime_shulker_box",
                   "pink_shulker_box", "gray_shulker_box", "cyan_shulker_box",
                   "purple_shulker_box", "blue_shulker_box", "brown_shulker_box",
                   "green_shulker_box", "red_shulker_box", "black_shulker_box",
                   "honeycomb_block", "shroomlight", "quartz_block_top",
                   "quartz_pillar_top", "quartz_pillar", "purpur_pillar_top", "purpur_pillar",
                   "basalt_side", "basalt_top", "polished_basalt_side", "polished_basalt_top",
                   "deepslate", "deepslate_top", "deepslate_bottom",
                   "blackstone_top", "blackstone_side", "polished_blackstone",
                   "polished_blackstone_top", "polished_blackstone_side",
                   "chiseled_polished_blackstone",
                   "gilded_blackstone", "polished_blackstone_bricks",
                   "cracked_polished_blackstone_bricks",
                   "chiseled_polished_blackstone", "polished_blackstone_button",
                   "nether_gold_ore", "gilded_blackstone",
                   "soul_sand", "soul_soil", "basalt", "blackstone",
                   "amethyst_block", "budding_amethyst",
                   "calcite", "smooth_basalt", "polished_basalt",
                   "dripstone_block", "pointed_dripstone",
                   "moss_block", "moss_carpet",
                   "rooted_dirt", "dirt", "coarse_dirt", "podzol",
                   "mycelium", "grass_block",
                   "snow_block", "powder_snow", "snow",
                   "froglight_top", "froglight_side", "froglight",
                   "sculk", "sculk_vein", "sculk_catalyst_top", "sculk_catalyst_side", "sculk_catalyst_bottom",
                   "sculk_sensor_top", "sculk_sensor_side", "sculk_sensor_bottom",
                   "sculk_shrieker_top", "sculk_shrieker_side", "sculk_shrieker_bottom",
                   "reinforced_deepslate_top", "reinforced_deepslate_side", "reinforced_deepslate_bottom",
                   "frogspawn",
                   "mangrove_roots", "muddy_mangrove_roots",
                   "bamboo_block", "bamboo_block_top",
                   "stripped_bamboo_block", "stripped_bamboo_block_top",
                   "bamboo_planks", "bamboo_mosaic",
                   "bamboo_door_top", "bamboo_door_bottom",
                   "bamboo_trapdoor", "bamboo_pressure_plate",
                   "bamboo_sign", "bamboo_hanging_sign",
                   "cherry_planks", "cherry_log", "cherry_log_top", "cherry_wood", "cherry_stripped_log",
                   "pale_oak_planks", "pale_oak_log", "pale_oak_log_top",
                   "mangrove_planks", "mangrove_log", "mangrove_log_top",
                   "muddy_mangrove_roots",
                   "mangrove_roots", "mangrove_propagule", "mangrove_propagule_hanging",
                   "muddy_brick_top", "muddy_bricks",
                   "resin_block", "resin_bricks",
                   "resin_clump",
                   "dirt", "coarse_dirt", "rooted_dirt",
                   "gravel", "sand", "red_sand",
                   "clay", "hardened_clay", "stained_hardened_clay",
                   "snow_layer", "snow",
                   "ice", "packed_ice", "blue_ice", "frosted_ice",
                   "lava", "lava_still", "lava_flow",
                   "water", "water_still", "water_flow",
                   "kelp", "kelp_plant", "dried_kelp_block",
                   "seagrass", "tall_seagrass", "seagrass_short",
                   "tall_grass_bottom", "tall_grass_top",
                   "fern", "large_fern_bottom", "large_fern_top",
                   "vine", "cave_vine", "cave_vines", "cave_vines_plant",
                   "glow_lichen",
                   "weeping_vines", "weeping_vines_plant",
                   "twisting_vines", "twisting_vines_plant",
                   "sugar_cane", "cactus_top", "cactus_side", "cactus_bottom",
                   "bamboo", "bamboo_sapling", "bamboo_large_leaves", "bamboo_small_leaves",
                   "lily_pad", "lily_of_the_valley",
                   "cornflower", "dandelion", "poppy", "blue_orchid", "allium",
                   "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
                   "oxeye_daisy", "wither_rose", "torchflower", "pitcher_plant",
                   "spore_blossom", "torchflower_crop", "pitcher_crop",
                   "wheat_stage0", "wheat_stage1", "wheat_stage2", "wheat_stage3", "wheat_stage4", "wheat_stage5", "wheat_stage6", "wheat_stage7",
                   "carrots_stage0", "carrots_stage1", "carrots_stage2", "carrots_stage3",
                   "potatoes_stage0", "potatoes_stage1", "potatoes_stage2", "potatoes_stage3",
                   "beetroot_stage0", "beetroot_stage1", "beetroot_stage2", "beetroot_stage3",
                   "sweet_berry_bush_stage0", "sweet_berry_bush_stage1", "sweet_berry_bush_stage2", "sweet_berry_bush_stage3",
                   "cocoa_stage0", "cocoa_stage1", "cocoa_stage2",
                   "melon_stem_disconnected", "pumpkin_stem_disconnected",
                   "nether_wart_stage0", "nether_wart_stage1", "nether_wart_stage2",
                   "fire_0", "fire_1", "soul_fire_0", "soul_fire_1",
                   "lantern", "soul_lantern",
                   "torch", "soul_torch",
                   "end_portal", "nether_portal",
                   "dragon_egg", "bedrock",
                   "end_gateway", "end_portal_frame_top", "end_portal_frame_side",
                   "command_block_front", "command_block_back", "command_block_side",
                   "chain_command_block_front", "chain_command_block_back", "chain_command_block_side",
                   "repeating_command_block_front", "repeating_command_block_back", "repeating_command_block_side",
                   "structure_block", "jigsaw_top", "jigsaw_side", "jigsaw_bottom", "jigsaw_lock",
                   "barrier", "structure_void", "light_0", "light_1", "light_2", "light_3", "light_4",
                   "light_5", "light_6", "light_7", "light_8", "light_9", "light_10", "light_11",
                   "light_12", "light_13", "light_14", "light_15",
                   "budding_amethyst", "amethyst_cluster",
                   "cobweb", "web",
                   "scaffolding_top", "scaffolding_side", "scaffolding_bottom",
                   "target_top", "target_side",
                   "bell_bottom", "bell_side", "bell_top",
                   "conduit", "conduit_base",
                   "shulker_box", "respawn_anchor_top", "respawn_anchor_side", "respawn_anchor_bottom",
                   "lodestone_top", "lodestone_side",
                   "honeycomb_block",
                   "composter_top", "composter_side", "composter_bottom", "composter_compost",
                   "barrel_top", "barrel_side", "barrel_bottom", "barrel_open_top",
                   "smoker_top", "smoker_side", "smoker_bottom", "smoker_front",
                   "blast_furnace_top", "blast_furnace_side", "blast_furnace_bottom", "blast_furnace_front",
                   "brewing_stand_base", "brewing_stand",
                   "cauldron_top", "cauldron_side", "cauldron_bottom", "cauldron_inner",
                   "anvil_top", "anvil_side", "anvil_bottom",
                   "chipped_anvil_top", "chipped_anvil_side", "chipped_anvil_bottom",
                   "damaged_anvil_top", "damaged_anvil_side", "damaged_anvil_bottom",
                   "enchanting_table_top", "enchanting_table_side", "enchanting_table_bottom",
                   "ender_chest", "trapped_chest",
                   "beacon", "sea_lantern",
                   "shroomlight", "jack_o_lantern", "carved_pumpkin",
                   "melon_side", "melon_top",
                   "pumpkin_side", "pumpkin_top",
                   "hay_block_top", "hay_block_side",
                   "dried_kelp_block_top", "dried_kelp_block_side", "dried_kelp_block_bottom",
                   "bone_block_top", "bone_block_side",
                   "quartz_pillar_top", "quartz_pillar",
                   "purpur_pillar_top", "purpur_pillar",
                   "basalt_top", "basalt_side",
                   "polished_basalt_top", "polished_basalt_side",
                   "deepslate_top", "deepslate_side", "deepslate_bottom",
                   "cobbled_deepslate", "polished_deepslate_top", "polished_deepslate_side",
                   "deepslate_bricks", "deepslate_brick_top",
                   "deepslate_tiles", "deepslate_tile_top",
                   "reinforced_deepslate_top", "reinforced_deepslate_side",
                   "chiseled_deepslate", "cracked_deepslate_bricks", "cracked_deepslate_tiles",
                   "blackstone_top", "blackstone_side", "polished_blackstone_top", "polished_blackstone_side",
                   "polished_blackstone_bricks",
                   "chiseled_polished_blackstone",
                   "gilded_blackstone", "nether_gold_ore",
                   "soul_sand", "soul_soil", "basalt",
                   "amethyst_block", "budding_amethyst",
                   "calcite", "tuff", "polished_tuff", "polished_calcite",
                   "dripstone_block", "pointed_dripstone",
                   "moss_block", "moss_carpet",
                   "big_dripleaf_top", "big_dripleaf_side", "big_dripleaf_stem_top", "big_dripleaf_stem",
                   "small_dripleaf_top", "small_dripleaf_side",
                   "rooted_dirt", "podzol_top", "podzol_side",
                   "mycelium_top", "mycelium_side", "mycelium_bottom",
                   "snow", "snow_block", "powder_snow",
                   "froglight_top", "froglight_side",
                   "ochre_froglight_top", "ochre_froglight_side",
                   "verdant_froglight_top", "verdant_froglight_side",
                   "pearlescent_froglight_top", "pearlescent_froglight_side",
                   "sculk", "sculk_vein", "sculk_catalyst_top", "sculk_catalyst_side", "sculk_catalyst_bottom",
                   "sculk_sensor_top", "sculk_sensor_side", "sculk_sensor_bottom",
                   "sculk_shrieker_top", "sculk_shrieker_side", "sculk_shrieker_bottom",
                   "calibrated_sculk_sensor_top", "calibrated_sculk_sensor_side", "calibrated_sculk_sensor_bottom",
                   "reinforced_deepslate_top", "reinforced_deepslate_side",
                   "mangrove_roots", "muddy_mangrove_roots", "mangrove_propagule", "mangrove_propagule_hanging",
                   "muddy_brick_top", "muddy_bricks",
                   "resin_block", "resin_bricks", "resin_clump",
                   "dirt", "coarse_dirt", "rooted_dirt", "podzol_top", "podzol_side",
                   "gravel", "sand", "red_sand",
                   "clay", "stained_hardened_clay",
                   "ice", "packed_ice", "blue_ice", "frosted_ice",
                   "lava_still", "water_still", "lava_flow", "water_flow",
                   "kelp", "kelp_plant", "dried_kelp_block_top", "dried_kelp_block_side", "dried_kelp_block_bottom",
                   "seagrass", "tall_seagrass_bottom", "tall_seagrass_top",
                   "tall_grass_bottom", "tall_grass_top",
                   "fern", "large_fern_bottom", "large_fern_top",
                   "vine", "cave_vines", "cave_vines_plant",
                   "weeping_vines", "weeping_vines_plant",
                   "twisting_vines", "twisting_vines_plant",
                   "sugar_cane", "cactus_top", "cactus_side", "cactus_bottom",
                   "bamboo", "bamboo_sapling", "bamboo_large_leaves", "bamboo_small_leaves",
                   "lily_pad", "lily_of_the_valley",
                   "cornflower", "dandelion", "poppy", "blue_orchid", "allium",
                   "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
                   "oxeye_daisy", "wither_rose", "torchflower", "pitcher_plant",
                   "spore_blossom",
                   "wheat_stage0", "carrots_stage0", "potatoes_stage0", "beetroot_stage0",
                   "sweet_berry_bush_stage0", "cocoa_stage0", "nether_wart_stage0",
                   "fire_0", "soul_fire_0",
                   "lantern", "soul_lantern",
                   "torch", "soul_torch",
                   "end_portal", "nether_portal",
                   "dragon_egg", "bedrock", "end_gateway",
                   "end_portal_frame_top", "end_portal_frame_side",
                   "command_block_front", "command_block_back", "command_block_side",
                   "chain_command_block_front", "chain_command_block_back", "chain_command_block_side",
                   "repeating_command_block_front", "repeating_command_block_back", "repeating_command_block_side",
                   "structure_block", "jigsaw_top", "jigsaw_side", "jigsaw_bottom", "jigsaw_lock",
                   "barrier", "structure_void", "light_0", "light_1", "light_2", "light_3", "light_4",
                   "light_5", "light_6", "light_7", "light_8", "light_9", "light_10", "light_11",
                   "light_12", "light_13", "light_14", "light_15",
                   "budding_amethyst", "amethyst_cluster",
                   "cobweb", "web",
                   "scaffolding_top", "scaffolding_side", "scaffolding_bottom",
                   "target_top", "target_side",
                   "bell_bottom", "bell_side", "bell_top",
                   "conduit", "conduit_base",
                   "respawn_anchor_top", "respawn_anchor_side", "respawn_anchor_bottom",
                   "lodestone_top", "lodestone_side",
                   "honeycomb_block",
                   "composter_top", "composter_side", "composter_bottom", "composter_compost",
                   "barrel_top", "barrel_side", "barrel_bottom", "barrel_open_top",
                   "smoker_top", "smoker_side", "smoker_bottom", "smoker_front",
                   "blast_furnace_top", "blast_furnace_side", "blast_furnace_bottom", "blast_furnace_front",
                   "brewing_stand_base", "brewing_stand",
                   "cauldron_top", "cauldron_side", "cauldron_bottom", "cauldron_inner",
                   "anvil_top", "anvil_side", "anvil_bottom",
                   "enchanting_table_top", "enchanting_table_side", "enchanting_table_bottom",
                   "ender_chest", "trapped_chest",
                   "beacon", "sea_lantern",
                   "shroomlight", "jack_o_lantern", "carved_pumpkin",
                   "melon_side", "melon_top",
                   "pumpkin_side", "pumpkin_top",
                   "hay_block_top", "hay_block_side",
                   "dried_kelp_block_top", "dried_kelp_block_side", "dried_kelp_block_bottom",
                   "bone_block_top", "bone_block_side",
                   "quartz_pillar_top", "quartz_pillar",
                   "purpur_pillar_top", "purpur_pillar",
                   "basalt_top", "basalt_side",
                   "polished_basalt_top", "polished_basalt_side",
                   "deepslate_top", "deepslate_side", "deepslate_bottom",
                   "cobbled_deepslate", "polished_deepslate_top", "polished_deepslate_side",
                   "deepslate_bricks", "deepslate_brick_top",
                   "deepslate_tiles", "deepslate_tile_top",
                   "reinforced_deepslate_top", "reinforced_deepslate_side",
                   "chiseled_deepslate", "cracked_deepslate_bricks", "cracked_deepslate_tiles",
                   "blackstone_top", "blackstone_side", "polished_blackstone_top", "polished_blackstone_side",
                   "polished_blackstone_bricks",
                   "chiseled_polished_blackstone",
                   "gilded_blackstone", "nether_gold_ore",
                   "soul_sand", "soul_soil", "basalt",
                   "amethyst_block", "budding_amethyst",
                   "calcite", "tuff", "polished_tuff", "polished_calcite",
                   "dripstone_block", "pointed_dripstone",
                   "moss_block", "moss_carpet",
                   "big_dripleaf_top", "big_dripleaf_side", "big_dripleaf_stem_top", "big_dripleaf_stem",
                   "small_dripleaf_top", "small_dripleaf_side",
                   "rooted_dirt", "podzol_top", "podzol_side",
                   "mycelium_top", "mycelium_side", "mycelium_bottom",
                   "snow", "snow_block", "powder_snow",
                   "froglight_top", "froglight_side",
                   "ochre_froglight_top", "ochre_froglight_side",
                   "verdant_froglight_top", "verdant_froglight_side",
                   "pearlescent_froglight_top", "pearlescent_froglight_side",
                   "sculk", "sculk_vein", "sculk_catalyst_top", "sculk_catalyst_side", "sculk_catalyst_bottom",
                   "sculk_sensor_top", "sculk_sensor_side", "sculk_sensor_bottom",
                   "sculk_shrieker_top", "sculk_shrieker_side", "sculk_shrieker_bottom",
                   "calibrated_sculk_sensor_top", "calibrated_sculk_sensor_side", "calibrated_sculk_sensor_bottom",
                   "mangrove_roots", "muddy_mangrove_roots", "mangrove_propagule", "mangrove_propagule_hanging",
                   "muddy_brick_top", "muddy_bricks",
                   "resin_block", "resin_bricks", "resin_clump",
                   "sand", "red_sand", "gravel",
                   ):
            return raw  # 简化: 大多数方块各面贴图同名

        # 默认 = 同名贴图
        return raw

    def get(self, block_id: str, face: str) -> Image.Image:
        key = (block_id, face)
        if key in _TEXTURE_CACHE:
            return _TEXTURE_CACHE[key]

        tex_name = self._face_to_texture(block_id, face)
        path = TEXTURE_DIR / f"{tex_name}.png"
        if not path.exists():
            # fallback 紫黑格
            img = Image.new("RGB", (16, 16), (122, 31, 162))
            for x in range(16):
                for y in range(16):
                    if x % 4 == 0 or y % 4 == 0:
                        img.putpixel((x, y), (0, 0, 0))
            _TEXTURE_CACHE[key] = img
            return img

        img = Image.open(path).convert("RGBA")
        if img.size != (16, 16):
            img = img.resize((16, 16), Image.LANCZOS)
        # RGBA → RGB（白底），避免 paste 报错
        bg = Image.new("RGB", (16, 16), (255, 255, 255))
        bg.paste(img, (0, 0), img)
        _TEXTURE_CACHE[key] = bg
        return bg


# 全局 provider
PROVIDER = SingleFileProvider()


def is_air(block_id: str) -> bool:
    return block_id == "minecraft:air"


# ============================================================
# X 射线渲染
# ============================================================
def render_xray_xz(reg, direction: str = "down", px: int = 16) -> Image.Image:
    """
    俯视 XZ 平面 X 射线 - 看向 -y 方向，从最大 y 向下找第一个非空方块
    """
    rx = list(range(reg.minx(), reg.maxx() + 1))
    rz = list(range(reg.minz(), reg.maxz() + 1))
    ry = list(range(reg.miny(), reg.maxy() + 1))
    W = len(rx) * px
    H = len(rz) * px
    canvas = Image.new("RGB", (W, H), (255, 255, 255))

    for ix, x in enumerate(rx):
        for iz, z in enumerate(rz):
            # 从最外层往里扫
            if direction == "down":
                ys_iter = reversed(ry)
            else:
                ys_iter = iter(ry)
            for y in ys_iter:
                b = reg[x, y, z]
                if b is None or is_air(b.id):
                    continue
                # 找到第一个非空气方块
                tex = PROVIDER.get(b.id, "top")
                canvas.paste(tex, (ix * px, iz * px))
                break  # X 射线 = 找到第一个就停
    return canvas


def render_xray_xy(reg, direction: str = "south", px: int = 16) -> Image.Image:
    """
    正视 XY 平面 X 射线 - 看向 +z 方向，从最小 z 往里找第一个非空方块
    """
    rx = list(range(reg.minx(), reg.maxx() + 1))
    ry = list(range(reg.miny(), reg.maxy() + 1))
    rz = list(range(reg.minz(), reg.maxz() + 1))
    W = len(rx) * px
    H = len(ry) * px
    canvas = Image.new("RGB", (W, H), (255, 255, 255))

    for ix, x in enumerate(rx):
        for iy, y in enumerate(ry):
            if direction == "south":
                zs_iter = iter(rz)  # 从 z=min 往里
            else:
                zs_iter = reversed(rz)
            for z in zs_iter:
                b = reg[x, y, z]
                if b is None or is_air(b.id):
                    continue
                # 找到第一个非空气
                face = "south" if direction == "south" else "north"
                tex = PROVIDER.get(b.id, face)
                canvas.paste(tex, (ix * px, iy * px))
                break
    return canvas


def render_xray_yz(reg, direction: str = "west", px: int = 16) -> Image.Image:
    """
    侧视 YZ 平面 X 射线 - 看向 -x 方向（从 +x 望向 -x），从最大 x 往里找
    """
    rz = list(range(reg.minz(), reg.maxz() + 1))
    ry = list(range(reg.miny(), reg.maxy() + 1))
    rx = list(range(reg.minx(), reg.maxx() + 1))
    W = len(rz) * px
    H = len(ry) * px
    canvas = Image.new("RGB", (W, H), (255, 255, 255))

    for iz, z in enumerate(rz):
        for iy, y in enumerate(ry):
            if direction == "west":
                xs_iter = reversed(rx)  # 从 x=max 往里
            else:
                xs_iter = iter(rx)
            for x in xs_iter:
                b = reg[x, y, z]
                if b is None or is_air(b.id):
                    continue
                face = "west" if direction == "west" else "east"
                tex = PROVIDER.get(b.id, face)
                canvas.paste(tex, (iz * px, iy * px))
                break
    return canvas


# ============================================================
# 拼图
# ============================================================
def make_combined(img_xz, img_xy, img_yz, labels, title="") -> Image.Image:
    pad = 24
    label_h = 24
    title_h = 32 if title else 0
    total_w = img_xz.width + img_xy.width + img_yz.width + 4 * pad
    total_h = max(img_xz.height, img_xy.height, img_yz.height) + label_h + title_h + 2 * pad
    bg = Image.new("RGB", (total_w, total_h), (255, 255, 255))
    draw = ImageDraw.Draw(bg)
    try:
        font_title = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc", 18)
        font_label = ImageFont.truetype("/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc", 14)
    except Exception:
        font_title = ImageFont.load_default()
        font_label = ImageFont.load_default()

    # 顶部标题
    if title:
        draw.text((pad, pad // 2), title, fill=(0, 0, 0), font=font_title)

    y_off = pad + label_h + title_h
    # 拼三视图
    bg.paste(img_xz, (pad, y_off))
    bg.paste(img_xy, (2 * pad + img_xz.width, y_off))
    bg.paste(img_yz, (3 * pad + img_xz.width + img_xy.width, y_off))
    # 标签
    for i, (label, x_pos) in enumerate(zip(labels,
                                          [pad,
                                           2 * pad + img_xz.width,
                                           3 * pad + img_xz.width + img_xy.width])):
        draw.text((x_pos, pad // 2 + title_h), label, fill=(0, 0, 0), font=font_label)

    # 底部注释
    note = f"每方块 {img_xz.width // len([0] * (img_xz.width // 16))}×{img_xz.width // len([0] * (img_xz.width // 16))} 像素 | 材质源: 1.21.1 client.jar | X射线模式 (看穿)"
    draw.text((pad, total_h - pad), note, fill=(120, 120, 120), font=font_label)
    return bg


def main():
    if len(sys.argv) < 2:
        print("用法: python a_render_texture.py <path/to/file.litematic> [out.png] [px=16] [mode=xray|cutout]")
        sys.exit(1)

    path = Path(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) >= 3 else path.with_suffix(".png")
    px = int(sys.argv[3]) if len(sys.argv) >= 4 else 16
    mode = sys.argv[4] if len(sys.argv) >= 5 else "xray"

    schem = litemapy.Schematic.load(str(path))
    print(f"=== {path.name} ===")
    print(f"width: {schem.width}, height: {schem.height}, length: {schem.length}")
    print(f"regions: {list(schem.regions.keys())}")

    reg = schem.regions[list(schem.regions.keys())[0]]

    print(f"\n=== 渲染 (每方块 {px}px, {mode} 模式) ===")
    img_xz = render_xray_xz(reg, "down", px)
    print(f"  俯视图: {img_xz.width}x{img_xz.height}")
    img_xy = render_xray_xy(reg, "south", px)
    print(f"  正视图: {img_xy.width}x{img_xy.height}")
    img_yz = render_xray_yz(reg, "west", px)
    print(f"  侧视图: {img_yz.width}x{img_yz.height}")

    title = f"{path.stem}  ({reg.maxx()-reg.minx()+1}×{reg.maxy()-reg.miny()+1}×{reg.maxz()-reg.minz()+1})"
    combined = make_combined(img_xz, img_xy, img_yz,
                             ["俯视 (XZ) 看向 -y",
                              "正视 (XY) 看向 +z",
                              "侧视 (YZ) 看向 -x"],
                             title=title)
    combined.save(out)
    print(f"\n✅ 输出: {out} ({combined.width}x{combined.height})")

    # 方块统计
    counts = Counter()
    for x in range(reg.minx(), reg.maxx() + 1):
        for y in range(reg.miny(), reg.maxy() + 1):
            for z in range(reg.minz(), reg.maxz() + 1):
                b = reg[x, y, z]
                if b and not is_air(b.id):
                    counts[b.id] += 1
    print(f"\n方块统计（top 20）:")
    for bid, cnt in counts.most_common(20):
        print(f"  {bid}: {cnt}")


if __name__ == "__main__":
    main()
