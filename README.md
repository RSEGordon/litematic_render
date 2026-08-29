# Litematic Render

Minecraft schematic (.litematic) 6-view axonometric renderer for documentation
and engineering sheets. Renders any litematic file from a local Minecraft Java
client into a 6-axis composite diagram plus a standalone materials-only list.

## What it does

Given a `.litematic` file, the tool produces:

- A composite PNG showing 6 orthographic-style views (top + 5 sides)
  anchored from the principal corners of the schematic's bounding box.
- An engineering sheet with axonometric views + materials table side-by-side,
  centered on the workbook. Owner (the originating author of the litematic)
  gets a separate sheet.
- A standalone `materials_only.png` listing every block type, count, and
  percentage in a justified 2-column layout.
- An XLSX materials workbook with the same data table-form.

## Architecture

```
litematic_render/
├── poc/
│   ├── src/main/java/com/rsegordon/poc/   # OffscreenRenderer + MaterialWorkbookWriter
│   ├── src/test/java/                     # JUnit tests for engineering / materials layout
│   └── tools/litematic_render_ui/         # Combined-app five-page Flask UI
├── tests/                                 # regression test litematics
└── combined_app5.py / FileShare/工具/combined/  # runtime hosting
```

The renderer (`OffscreenRenderer.java`) runs a vanilla Minecraft client in
`Superflat the_void` mode, loads the litematic into an isolated world,
captures a 6-axis axonometric set, and composites them onto a single canvas.
The materials table is extracted from a single walk over the world and
displayed in justified-fill columns.

## Run

```bash
cd poc
./gradlew compileJava
cd tools/litematic_render_ui
python3 app.py    # listens on :19995 by default
```

Upload a `.litematic` via the Flask UI; the tool records a task, renders it
through `OffscreenRenderer`, and produces a detail page with the 6-view
composite + materials card (XLSX + PNG download buttons).

## Build status

Active development. The 6-view composite, materials list, and the XLSX
workbook are stable. The renderer runs in a real vanilla Minecraft 1.21.1
client to guarantee pixel-level accuracy against in-game blockstates.

## License

Personal project. All rights reserved unless explicitly transferred.
