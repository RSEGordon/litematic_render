# litematic_render — MC Schematic 三视图 + 模型渲染器

把 Minecraft 的 `.litematic` 文件渲染成 PNG 三视图（俯视/正视/侧视）+ 可选 3D 图。

## 文件

| 文件 | 功能 |
|---|---|
| `f_render_ascii.py` | 方案 F：ASCII 三视图（最简单，调试用） |
| `a_render_texture.py` | 方案 A：matplotlib 渲染贴图三视图（基础版） |
| `a_render_v3.py` | 方案 A v3：解析 blockstate JSON + model JSON（含 parent 递归），按 facing 选面贴图，X 射线半透明 |

## 用法

```bash
# 装依赖
pip install litemapy matplotlib Pillow

# 提取 MC 客户端资源（1.21.1 兼容 26.x 大部分方块）
unzip -o -q /path/to/minecraft-client.jar \
    'assets/minecraft/textures/block/*' \
    'assets/minecraft/models/block/*' \
    'assets/minecraft/blockstates/*' \
    -d client_assets/

# 跑 v3
python3 a_render_v3.py path/to/file.litematic out.png 16 3
# 参数: input output.png px-per-block xray-layers
```

## 当前限制（要修的）

1. **方块形状** = 当 1×1×1 立方体画
   - 铁轨/红石线/红石火把实际是 1/16 高的薄片贴地
   - 中继器/比较器实际是 2/16 高的矮立方体
   - 漏斗模型由多个 element 组成（主体 + 4 个漏斗嘴）
2. **红石线** = 点状 `redstone_dust_dot`
   - 实际应按 power + 4 个方向 neighbors 拼 line0/line1/side0/side1/up/cross
3. **X 射线半透明**代码已写但效果不明显
4. **紫黑格 fallback** 太多

## 修法（计划）

- 解析 `assets/minecraft/models/block/*.json` 的 `from/to` + `elements[].faces`
- 递归 parent 链
- 真实按方块在 y 方向的 from/to 范围画
- 按 facing 选面贴图 + 处理 y/x rotation
- X 射线半透明叠加
