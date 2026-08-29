# Litematic Render

Render any Minecraft schematic (`.litematic`) into a 6-axis axonometric
projection plus a complete materials workbook — directly from a real vanilla
Minecraft client, with pixel-level blockstate fidelity.

![composite preview](docs/preview-composite.png)
*(preview placeholder — dropped into `docs/` before publishing)*

## What it does

Given a `.litematic`, the tool produces four artifacts per task:

| Artifact | Description |
|---|---|
| **Composite PNG** | Top + 5 side views of the bounding box, anchored on the principal corners. 6-view layout in a single image. |
| **Materials-only PNG** | Standalone `materials_only.png` — a justified two-column layout listing every block type, count, and percentage. Doubled font + resolution since V153. |
| **Materials XLSX** | Same data in workbook form, suitable for take-off / BOQ. Owner (the litematic's original author) gets a separate sheet. |
| **Render log** | Per-task stdout/stderr from the rendering Minecraft client — useful for debugging parity or capture stalls. |

## Why this exists

Existing tools (Litematica, 3dLitematica) render in-editor; they require a
human at the keyboard and produce screenshots, not images you can drop into a
build sheet. This project runs Minecraft headlessly through Fabric Loom,
loads the schematic into an isolated `Superflat the_void` world, captures
six orthographic-style frames, and composites them — without ever opening
the UI.

The fidelity matters: blockstates are rendered by the same code path that
ships in vanilla, so fence rotations, redstone cross shapes, and trapdoor
orientations look exactly as they would in-game. No re-implementation, no
text approximations.

## Quick start

```bash
cd poc
./gradlew :runClient -Pargs="--render FILE.litematic --out OUT_DIR"
```

That kicks off the rendering client. While it runs, the Flask UI:

```bash
cd poc/tools/litematic_render_ui
python3 app.py            # listens on :19995
```

Upload a `.litematic` through the web UI — the tool records a task, dispatches
it to the renderer, and writes the four artifacts into
`LITEMATIC_DIR/<task-id>/`.

## Requirements

- **JDK 25** (`org.gradle.jvmargs=-Xmx6g` requires 6 GB heap for the
  render client; the production renderer enforces this)
- **Minecraft 1.26.2** (snapshot) — `minecraft_version=26.2` in
  `gradle.properties`
- **Fabric Loader 0.19.3** + **Fabric API 0.158.0+26.2**
- **Python 3.10+** with `flask`, `nbtlib` (UI side)
- **Linux + Xvfb** OR **headless GPU** — the renderer spins up an offscreen
  Minecraft client per task, killed on completion

Set `LITEMATIC_RENDER_JAVA_HOME` if your JDK is not on PATH.

## Architecture

```
poc/
├── src/main/java/com/rsegordon/poc/
│   ├── LitematicRenderCommand.java    # /gradlew runClient entry point
│   ├── LitematicRenderMod.java        # mod entry; wires the renderer
│   ├── OffscreenRenderer.java         # capture + composite + materials walk
│   ├── MaterialWorkbookWriter.java    # XLSX output
│   ├── OutputArchiveWriter.java       # tar.gz bundle of all artifacts
│   ├── BackgroundPass.java            # paper background fill pass
│   └── mixin/                         # fabric mixins for off-screen capture
├── src/test/java/com/rsegordon/poc/
│   ├── OffscreenRendererEngineeringSheetLayoutTest.java
│   ├── OffscreenRendererMaterialsLayoutTest.java
│   ├── OffscreenRendererChunkCoverageTest.java
│   ├── OffscreenRendererPrincipalProjectionTest.java
│   ├── OffscreenRendererWorldCreationTest.java
│   ├── OffscreenRendererVoidTerrainTest.java
│   ├── OffscreenRendererPlatformClearTest.java
│   ├── OffscreenRendererProgressTest.java
│   ├── OffscreenRendererRenderReadyTest.java
│   ├── MaterialWorkbookWriterTest.java
│   ├── OutputArchiveWriterTest.java
│   └── SingleViewTransparencyTest.java
└── tools/litematic_render_ui/
    ├── app.py                          # Flask blueprint
    └── templates/                      # 4 Jinja2 templates
```

### Render pipeline (one task)

1. **Bootstrap**:  the Flask UI writes a `.litematic` into
   `LITEMATIC_DIR/<task-id>/raw/` and records the task in
   `LITEMATIC_DIR/tasks.json`.
2. **Spawn renderer**:  `LITEMATIC_RENDER_DISTANCE_CHUNKS` (default 32)
   controls chunk-coverage; `LITEMATIC_RENDER_DISTANCE_SAFETY_CHUNKS` (default
   2) extends the area slightly to clip edges cleanly.
3. **Isolated world**:  the renderer creates a brand-new
   `Superflat the_void` world per task (`RENDER_WORLD_PREFIX = "LitematicRender_"`),
   preventing spawn-platform leakage from a previous task.
4. **Load schematic**:  the litematic is pasted at world spawn, then the
   renderer walks the bounding box.
5. **Six-view capture**:  for each principal corner, the renderer drives an
   orthographic frame, captures the framebuffer, and composites.
6. **Materials walk**:  single pass over the loaded region; aggregates counts,
   joins back to blockstate IDs through the `client_assets/` JSON map.
7. **Write artifacts**:  composite PNG, materials-only PNG, XLSX workbook,
   and a tar.gz archive; written through `OutputArchiveWriter`.
8. **Cleanup**:  the temp world is deleted; the JLS reports
   `cleaned temporary render world /.../LitematicRender_<uuid>`.

## Configuration

### Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `LITEMATIC_RENDER_JAVA_HOME` | `/opt/java/jdk-25.0.1` | JDK location for the renderer. |
| `LITEMATIC_RENDER_DISTANCE_CHUNKS` | `32` | chunk radius covered per task. |
| `LITEMATIC_RENDER_DISTANCE_SAFETY_CHUNKS` | `2` | extra chunks rendered for safe edge clipping. |

### `gradle.properties`

| Key | Value | Note |
|---|---|---|
| `minecraft_version` | `26.2` | vanilla MC snapshot. |
| `loader_version` | `0.19.3` | Fabric Loader. |
| `fabric_version` | `0.158.0+26.2` | Fabric API. |
| `org.gradle.jvmargs` | `-Xmx6g` | gradle daemon needs 6 GB. |

## Tests

```bash
cd poc
./gradlew test
```

Eleven JUnit 5 suites cover layout parity, principal projection alignment,
the isolated-the-void world creation, the spawn-platform-clear behavior,
chunk coverage, progress reporting, and the XLSX / archive writers. They
run headlessly — no Minecraft client is booted during tests.

## History

The renderer is the result of ~150 incremental versions across three
months (V30 → V153). Major milestones:

- **V132**: replaced the size cap with a camera-distance render limit.
- **V133**: materials workbook accepts any number of rows.
- **V134**: forced the render world to a vanilla `the_void` superflat.
- **V135**: cleared the spawn-protection platform after the void-world creation.
- **V136**: isolated temp render worlds and validated real chunk coverage.
- **V138**: derived principal frames from the camera basis.
- **V139**: unified the engineering layout and stabilized parity.
- **V140**: stabilized capture, axon layout, and progress UI.
- **V141**: constrained axon views to principal corner slots.
- **V142**: anchored axons + scaled sheet UI.
- **V150**: 6-view composite + orphan sweep + PAPER_COLOR fullbright toggle.
- **V151**: standalone `materials_only.png` (justified columns).
- **V152**: dual download buttons on the materials card (XLSX + PNG).
- **V153**: 2x font + 2x resolution via `Graphics2D.scale(2,2)`.

The full iterative log lives in the commit history; early `V30`-`V90`
versions are simpler render prototypes kept on the `master` branch for
traceability.

## Repo layout (root)

```
.
├── a_render_texture.py             # V1 ASCII/texture renderer
├── a_render_v3.py / a_render_v4.py # V1 model + blockstate parser
├── f_render_ascii.py               # V1 ASCII renderer
├── render_3d.js                    # V1 Three.js prototype (initial commit)
├── convert_entity_models.py        # V1 entity-model debug helper
├── client_assets/                  # extracted vanilla MC 1.21.1 blockstates + models
├── poc/                            # the active renderer (see above)
├── tests/                          # regression test litematics
└── README.md
```

The V1 ASCII/texture/Three.js prototypes are kept around for traceability —
they show how the project started and what we discarded.

## License

Private project. All rights reserved unless explicitly transferred.
