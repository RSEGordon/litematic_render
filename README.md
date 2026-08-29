# Litematic Render / Minecraft Schematic 6 视图投影渲染器

> 用真实的原版 Minecraft 客户端渲染 `.litematic` 投影文件,生成 6 轴轴测投影图 + 完整材料清单工作簿。逐像素 blockstate 保真,不近似、不偷工。

<p align="right">
  🌐 <b><a href="./README.md">中文 (默认)</a></b> · <a href="./README.en.md">English</a>
</p>

![composite preview](docs/preview-composite.png)

*`【单区块】海泡菜 1470k` 的 V153 6 视图合成图样张 —— sea-pickle farm schematic。*

---

## ⚠️ 本地化提示

历史背景:Java 包名迁到 `io.github.rsegordon.litematic_render`;`app.py` 的硬编码绝对路径改读 env-var + `~/litematic_render_tasks/` fallback;`LITEMATIC_RENDER_JAVA_HOME` 改为强制 env-var(不再有 embedded default);`gradle.properties` group 重命名;JUnit 测试 fixture 改用 classpath resource;Fabric Loader / mixins 配置同步改成新包名;UI 标签翻成英文。

⚠️ **历史调研 md 已清.** 之前残留的 V 字头 + CODEX_TASK 系列 md 在公开化之前用 `git-filter-repo` 重写历史去除。当前公开历史不再包含作者 home 绝对路径。`LOCALIZE.md` 保留迁移说明仅供参考。

详见 [`LOCALIZE.md`](./LOCALIZE.md)。

---

## 📚 目录

1. [工具产出](#-工具产出)
2. [为什么做](#-为什么做)
3. [快速上手](#-快速上手)
4. [运行环境](#-运行环境)
5. [架构](#-架构)
6. [渲染流程](#-渲染流程)
7. [配置](#-配置)
8. [测试](#-测试)
9. [版本历史](#-版本历史)
10. [仓库结构](#-仓库结构)
11. [许可证](#-许可证)

---

## 📦 工具产出

每个 `.litematic` 任务产出以下四件:

| 产物 | 说明 |
|---|---|
| **合成 PNG** (`composite`) | 主视图 + 5 个侧视图,以 bbox 的主角点为锚。6 视图排版成一张图。 |
| **材料独立 PNG** (`materials_only`) | `materials_only.png` 独立文件。两列两端对齐排版,列出每种方块、数量、占比。V153 起字号 + 分辨率双倍。 |
| **材料 XLSX** (`workbook`) | 同数据的工作簿格式,可直接做 BOQ。原作者(owner)另起独立 sheet。 |
| **渲染日志** (`log`) | 每个任务对应的 Minecraft 客户端 stdout/stderr。便于排查 parity / 捕获 stall。 |

---

## 🎯 为什么做

现有工具 (Litematica / 3dLitematica) 必须在编辑器内、手动操作、截屏。本项目通过 Fabric Loom 引导 Minecraft 无头模式,把投影加载到独立 `Superflat the_void` 世界,捕获六个正交视图,合成到一起 —— 全程不开 UI。

保真度是核心。方块状态由原版 MC 同款渲染管线输出,栅栏朝向、红石十字、活板门方向全部与游戏内一致。不重写、不近似。

---

## 🚀 快速上手

```bash
# 1. 编译渲染客户端 (需 JDK 25, 6 GB 堆)
cd poc
./gradlew :runClient -Pargs="--render FILE.litematic --out OUT_DIR"

# 2. (可选) 启动 Flask UI
cd poc/tools/litematic_render_ui
python3 app.py            # 监听 :19995
```

通过网页 UI 上传 `.litematic` (或直接调渲染命令行)。工具记录任务、派发渲染、把四件产物写到任务目录。

---

## 🛠 运行环境

- **JDK 25** —— `org.gradle.jvmargs=-Xmx6g`,渲染客户端需 6 GB 堆。
- **Minecraft 26.2 (snapshot)** —— 见 `gradle.properties`。
- **Fabric Loader 0.19.3** + **Fabric API 0.158.0+26.2**。
- **Python 3.10+** —— UI 端依赖 `flask`、`nbtlib`。
- **Linux + Xvfb** 或 **无头 GPU** —— 每个任务启一个离屏 MC,完成后退出。

JDK 不在 `PATH` 上时,设置 `LITEMATIC_RENDER_JAVA_HOME`。生产环境渲染器每次任务后强杀重启,避免状态泄漏。

---

## 🏛 架构

```
poc/
├── src/main/java/io/github/rsegordon/litematic_render/
│   ├── LitematicRenderCommand.java    # /gradlew runClient 入口
│   ├── LitematicRenderMod.java        # mod 入口,装配渲染器
│   ├── OffscreenRenderer.java         # 捕获 + 合成 + 材料遍历
│   ├── MaterialWorkbookWriter.java    # XLSX 输出
│   ├── OutputArchiveWriter.java       # tar.gz 打包所有产物
│   ├── BackgroundPass.java            # paper 底色填充 pass
│   └── mixin/                         # Fabric mixins,离屏捕获
├── src/test/java/io/github/rsegordon/litematic_render/
│   ├── ...11 个 JUnit 5 测试...
└── tools/litematic_render_ui/
    ├── app.py                          # Flask 蓝图 (env-var-driven)
    └── templates/                      # 4 个 Jinja2 模板 (英文 UI 标签)
```

---

## ⚙️ 渲染流程

每个任务分八步:

1. **引导** —— Flask UI 把 `.litematic` 写到 `LITEMATIC_DIR/<task-id>/raw/`,并在 `LITEMATIC_DIR/tasks.json` 记录任务。
2. **启动渲染器** —— `LITEMATIC_RENDER_DISTANCE_CHUNKS` (默认 32) 控制区块覆盖范围;`LITEMATIC_RENDER_DISTANCE_SAFETY_CHUNKS` (默认 2) 多渲一圈保证边缘裁切干净。
3. **隔离世界** —— 每个任务创建一个全新 `Superflat the_void` 世界(`RENDER_WORLD_PREFIX = "LitematicRender_"`),避免上次任务 spawn 平台泄漏。
4. **加载投影** —— 投影 paste 到世界出生点,渲染器走遍 bbox。
5. **6 视图捕获** —— 每个主角点驱动一个正交帧、捕获 framebuffer、合成。
6. **材料遍历** —— 单次遍历加载区域,统计数量,通过 `client_assets/` JSON map 关联回 blockstate ID。
7. **写产物** —— 合成 PNG、独立材料 PNG、XLSX 工作簿、tar.gz 归档,由 `OutputArchiveWriter` 输出。
8. **清理** —— 临时世界被删除,日志显示 `cleaned temporary render world /.../LitematicRender_<uuid>`。

---

## 🔧 配置

### 环境变量

| Variable | Default | Meaning |
|---|---|---|
| `LITEMATIC_RENDER_DIR` | `~/litematic_render_tasks/` | 上传投影和任务产物的存储根目录。 |
| `LITEMATIC_RENDER_JAVA_HOME` | (required, no default) | 渲染器 JDK 位置。部署时设置。 |
| `LITEMATIC_RENDER_DISTANCE_CHUNKS` | `32` | 单任务覆盖区块半径。 |
| `LITEMATIC_RENDER_DISTANCE_SAFETY_CHUNKS` | `2` | 边缘裁切安全余量区块数。 |

### `poc/gradle.properties`

| Key | Value | Note |
|---|---|---|
| `minecraft_version` | `26.2` | vanilla MC snapshot。 |
| `loader_version` | `0.19.3` | Fabric Loader。 |
| `fabric_version` | `0.158.0+26.2` | Fabric API。 |
| `org.gradle.jvmargs` | `-Xmx6g` | Gradle daemon 需 6 GB。 |

---

## 🧪 测试

```bash
cd poc
./gradlew test
```

11 个 JUnit 5 测试套件,覆盖:布局 parity、主角投影对齐、独立 the_void 世界创建、spawn 平台清除、区块覆盖、进度上报、XLSX / 归档写入器。无头执行,测试时不启动 MC 客户端。

> 注:owner workbook 测试 fixture (`owner_workbook_template.xlsx`) 走 classpath,见 `LOCALIZE.md` 迁移说明。

---

## 🕓 版本历史

渲染器从 V30 一路演化到 V153,约 150 个迭代版本。主要里程碑:

- **V132** —— 用相机距离限制代替尺寸硬性上限。
- **V133** —— 材料工作簿支持任意行数。
- **V134** —— 强制渲染世界为原版 `the_void` 超平坦。
- **V135** —— the_void 世界创建后清除 spawn 保护平台。
- **V136** —— 隔离临时渲染世界,验证区块真覆盖。
- **V138** —— 从相机基推导主角帧。
- **V139** —— 统一工程图布局 + parity 稳定化。
- **V140** —— 捕获 / axon 布局 / 进度 UI 稳定。
- **V141** —— axon 视图约束到主角点槽位。
- **V142** —— axon 锚定 + 表格 UI 缩放。
- **V150** —— 6 视图合成 + orphan sweep + PAPER_COLOR fullbright 开关。
- **V151** —— 独立 `materials_only.png`(两端对齐列)。
- **V152** —— 材料卡片双下载按钮 (XLSX + PNG)。
- **V153** —— 通过 `Graphics2D.scale(2,2)` 字号 / 分辨率双倍。

完整迭代日志见 commit 历史;早期 V30-V90 是更简单的渲染原型,留在 `master` 分支便于追溯。

---

## 🗂 仓库结构

```
.
├── a_render_texture.py             # V1 ASCII/texture renderer
├── a_render_v3.py / a_render_v4.py # V1 model + blockstate parser
├── f_render_ascii.py               # V1 ASCII renderer
├── render_3d.js                    # V1 Three.js prototype (initial commit)
├── convert_entity_models.py        # V1 entity-model debug helper
├── client_assets/                  # extracted vanilla MC 1.21.1 blockstates + models
├── poc/                            # the active renderer (see 架构)
├── tests/                          # regression test litematics
├── docs/
│   └── preview-composite.png       # README 头图,海泡菜 1470k 渲染样张
├── README.md                       # 本文件 (中文, 默认)
├── README.en.md                    # English version
├── LOCALIZE.md                     # 本地化迁移说明
└── LICENSE                         # (尚未添加)
```

V1 的 ASCII / texture / Three.js 原型代码保留,便于追溯项目起点和当时被淘汰的思路。

---

## 📝 许可证

[MIT License](./LICENSE)。`poc/src/main/java/` 下作者原创代码按 MIT 公开。说明性限制:

- `client_assets/` 内的 Minecraft blockstate / 模型 JSON 来自 Mojang 的 vanilla client,**这一部分受 Mojang proprietary 约束,不归本仓库 license 管**。
- `src/test/resources/owner_workbook_template.xlsx` 是作者自己设计的 owner workbook schema。如需重用本仓库渲染管线 + 自己的 schema,使用者应替换成自己的 XLSX。

---

## 🌐 Localization / 本地化

详见 [`LOCALIZE.md`](./LOCALIZE.md)。Bucket A 和 B 已 ship;Bucket C (历史 V 字头 / CODEX_TASK md 等历史调研记录) 已在公开化之前用 `git-filter-repo` 重写历史。

跑 `git grep -n "rsegordon\|/home/rsegordon"` 列出剩余本地化点(主要是 README + LOCALIZE.md 的描述性引用,以及新包名本身,不是泄露)。
