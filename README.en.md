# Litematic Render

> Render any Minecraft schematic (`.litematic`) into a 6-axis axonometric projection plus a complete materials workbook — directly from a real vanilla Minecraft client, with pixel-level blockstate fidelity.

<p align="right">
  🌐 <a href="./README.md">中文</a> · <b><a href="./README.en.md">English</a></b> (default)
</p>

![composite preview](docs/preview-composite.png)

*Composite render of `【单区块】海泡菜 1470k` — a sea-pickle farm schematic, rendered through the V153 6-view pipeline.*

---

## ⚠️ Localization Notice

**Historical context.** Java package moved to `io.github.rsegordon.litematic_render`; hardcoded absolute paths in `app.py` are now env-var-driven with a `~/litematic_render_tasks/` fallback; `LITEMATIC_RENDER_JAVA_HOME` is required (no embedded default); `gradle.properties` group matches the new package; test fixture XLSX is on the classpath; UI labels translated to English.

**Caveat:** historical V-plan and CODEX_TASK markdown files have been scrubbed from the public history with `git-filter-repo`. See `LOCALIZE.md` for historical context.

---

## 📚 Contents

1. [What it does](#-what-it-does)
2. [Why this exists](#-why-this-exists)
3. [Quick start](#-quick-start)
4. [Requirements](#-requirements)
5. [Architecture](#-architecture)
6. [Render pipeline](#-render-pipeline)
7. [Configuration](#-configuration)
8. [Tests](#-tests)
9. [History](#-history)
10. [Repo layout](#-repo-layout)
11. [License](#-license)

---

## 📦 What it does

Given a `.litematic` file, the tool produces four artifacts per task:

| Artifact | Description |
|---|---|
| **Composite PNG** | Top + 5 side views of the bounding box, anchored on the principal corners. 6-view layout in a single image. |
| **Materials-only PNG** | Standalone `materials_only.png` — a justified two-column layout listing every block type, count, and percentage. Doubled font + resolution since V153. |
| **Materials XLSX** | Same data in workbook form, suitable for take-off / BOQ. Owner (the litematic's original author) gets a separate sheet. |
| **Render log** | Per-task stdout/stderr from the rendering Minecraft client — useful for debugging parity or capture stalls. |

---

## 🎯 Why this exists

Existing tools (Litematica, 3dLitematica) render in-editor; they require a human at the keyboard and produce screenshots, not images you can drop into a build sheet. This project runs Minecraft headlessly through Fabric Loom, loads the schematic into an isolated `Superflat the_void` world, captures six orthographic-style frames, and composites them — without ever opening the UI.

The fidelity matters: blockstates are rendered by the same code path that ships in vanilla, so fence rotations, redstone cross shapes, and trapdoor orientations look exactly as they would in-game. No re-implementation, no text approximations.

---

## 🚀 Quick start

```bash
# 1. Compile the rendering client (needs JDK 25, 6 GB heap)
cd poc
./gradlew :runClient -Pargs="--render FILE.litematic --out OUT_DIR"

# 2. (optional) Spin up the Flask UI
cd poc/tools/litematic_render_ui
python3 app.py            # listens on :19995
```

Upload a `.litematic` through the web UI (or call the renderer CLI directly). The tool records a task, dispatches it to the renderer, and writes the four artifacts into the task directory.

---

## 🛠 Requirements

- **JDK 25** — `org.gradle.jvmargs=-Xmx6g` requires 6 GB heap for the rendering client.
- **Minecraft 26.2** (snapshot) — `minecraft_version=26.2` in `gradle.properties`.
- **Fabric Loader 0.19.3** + **Fabric API 0.158.0+26.2**.
- **Python 3.10+** with `flask`, `nbtlib` (UI side).
- **Linux + Xvfb** OR a **headless GPU** — the renderer spins up an offscreen Minecraft client per task, killed on completion.

Set `LITEMATIC_RENDER_JAVA_HOME` on the deploy host. Production deploys kill + restart the renderer per task to avoid state leakage between litematics.

---

## 🏛 Architecture

```
poc/
├── src/main/java/io/github/rsegordon/litematic_render/
│   ├── LitematicRenderCommand.java    # /gradlew runClient entry point
│   ├── LitematicRenderMod.java        # mod entry; wires the renderer
│   ├── OffscreenRenderer.java         # capture + composite + materials walk
│   ├── MaterialWorkbookWriter.java    # XLSX output
│   ├── OutputArchiveWriter.java       # tar.gz bundle of all artifacts
│   ├── BackgroundPass.java            # paper background fill pass
│   └── mixin/                         # fabric mixins for off-screen capture
├── src/test/java/io/github/rsegordon/litematic_render/
│   └── ...11 JUnit 5 test classes...
└── tools/litematic_render_ui/
    ├── app.py                          # Flask blueprint (env-var-driven paths)
    └── templates/                      # 4 Jinja2 templates (English UI labels)
```

---

## ⚙️ Render pipeline

Each task follows eight steps:

1. **Bootstrap** — Flask UI writes the `.litematic` into `LITEMATIC_DIR/<task-id>/raw/` and records the task in `LITEMATIC_DIR/tasks.json`.
2. **Spawn renderer** — `LITEMATIC_RENDER_DISTANCE_CHUNKS` (default 32) controls chunk coverage; `LITEMATIC_RENDER_DISTANCE_SAFETY_CHUNKS` (default 2) extends the area slightly to clip edges cleanly.
3. **Isolated world** — the renderer creates a brand-new `Superflat the_void` world per task (`RENDER_WORLD_PREFIX = "LitematicRender_"`), preventing spawn-platform leakage from a previous task.
4. **Load schematic** — the litematic is pasted at world spawn, then the renderer walks the bounding box.
5. **Six-view capture** — for each principal corner, the renderer drives an orthographic frame, captures the framebuffer, and composites.
6. **Materials walk** — single pass over the loaded region; aggregates counts, joins back to blockstate IDs through the `client_assets/` JSON map.
7. **Write artifacts** — composite PNG, materials-only PNG, XLSX workbook, and a tar.gz archive; written through `OutputArchiveWriter`.
8. **Cleanup** — the temp world is deleted; the JLS reports `cleaned temporary render world /.../LitematicRender_<uuid>`.

---

## 🔧 Configuration

### Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `LITEMATIC_RENDER_DIR` | `~/litematic_render_tasks/` | Storage root for uploaded litematics and per-task artifacts. |
| `LITEMATIC_RENDER_JAVA_HOME` | (required, no default) | JDK location for the renderer. Set on the deploy host. |
| `LITEMATIC_RENDER_DISTANCE_CHUNKS` | `32` | chunk radius covered per task. |
| `LITEMATIC_RENDER_DISTANCE_SAFETY_CHUNKS` | `2` | extra chunks for safe edge clipping. |

### `poc/gradle.properties`

| Key | Value | Note |
|---|---|---|
| `minecraft_version` | `26.2` | vanilla MC snapshot. |
| `loader_version` | `0.19.3` | Fabric Loader. |
| `fabric_version` | `0.158.0+26.2` | Fabric API. |
| `org.gradle.jvmargs` | `-Xmx6g` | gradle daemon needs 6 GB. |

---

## 🧪 Tests

```bash
cd poc
./gradlew test
```

Eleven JUnit 5 suites cover layout parity, principal projection alignment, the isolated-the-void world creation, the spawn-platform-clear behavior, chunk coverage, progress reporting, and the XLSX / archive writers. They run headlessly — no Minecraft client is booted during tests.

> Note: the owner workbook test fixture (`owner_workbook_template.xlsx`) rides on the classpath — see `LOCALIZE.md` for migration history.

---

## 🕓 History

The renderer is the result of ~150 incremental versions across three months (V30 → V153). Major milestones:

- **V132** — replaced the size cap with a camera-distance render limit.
- **V133** — materials workbook accepts any number of rows.
- **V134** — forced the render world to a vanilla `the_void` superflat.
- **V135** — cleared the spawn-protection platform after the void-world creation.
- **V136** — isolated temp render worlds and validated real chunk coverage.
- **V138** — derived principal frames from the camera basis.
- **V139** — unified the engineering layout and stabilized parity.
- **V140** — stabilized capture, axon layout, and progress UI.
- **V141** — constrained axon views to principal corner slots.
- **V142** — anchored axons + scaled sheet UI.
- **V150** — 6-view composite + orphan sweep + PAPER_COLOR fullbright toggle.
- **V151** — standalone `materials_only.png` (justified columns).
- **V152** — dual download buttons on the materials card (XLSX + PNG).
- **V153** — 2x font + 2x resolution via `Graphics2D.scale(2,2)`.

The full iterative log lives in the commit history; early `V30`-`V90` versions are simpler render prototypes kept on the `master` branch for traceability.

---

## 🗂 Repo layout

```
.
├── a_render_texture.py             # V1 ASCII/texture renderer
├── a_render_v3.py / a_render_v4.py # V1 model + blockstate parser
├── f_render_ascii.py               # V1 ASCII renderer
├── render_3d.js                    # V1 Three.js prototype (initial commit)
├── convert_entity_models.py        # V1 entity-model debug helper
├── client_assets/                  # extracted vanilla MC 1.21.1 blockstates + models
├── poc/                            # the active renderer (see Architecture)
├── tests/                          # regression test litematics
├── docs/
│   └── preview-composite.png       # README header image, sea-pickle 1470k sample
├── README.md                       # 中文 version (default in this repo)
├── README.en.md                    # This file (English)
├── LOCALIZE.md                     # localization migration notes
└── LICENSE                         # (not yet added)
```

The V1 ASCII/texture/Three.js prototypes are kept around for traceability — they show how the project started and what we discarded.

---

## 📝 License

Personal project. The render client (`poc/src/main/java/`) is the author's own code. The materials workbook template XLSX bundled in `src/test/resources/` is the author's own — third-party owners of their own Minecraft schematics should provide their own template.

No explicit open-source license is granted. By default, "all rights reserved" applies (since no LICENSE file is shipped). The author may add a license in a future commit.

---

## 🌐 Localization

See [`LOCALIZE.md`](./LOCALIZE.md). Buckets A and B are completed in the public tree; historical V-plan and CODEX_TASK markdown records (Bucket C) have been scrubbed from the public history with `git-filter-repo` before the visibility flip.

Run `git grep -n "rsegordon\|/home/rsegordon"` to enumerate every localization reference (most remaining hits are descriptive references in `README.md` / `LOCALIZE.md`, or the new package name itself).
