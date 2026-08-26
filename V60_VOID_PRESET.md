# V60: superflat `the_void` white-background render

## World configuration

`poc/run/server.properties` is configured as requested:

```properties
generator-settings={"preset":"minecraft:the_void"}
level-type=minecraft\:flat
```

The old `poc/run/saves/World` was removed before testing. Minecraft 1.21.1's
integrated-server loader only loads an existing save, so the replacement World
was created once through the equivalent vanilla UI path: **Superflat → Presets
→ The Void**. The resulting integrated server started successfully.

Vanilla's The Void preset adds a stone spawn platform. `OffscreenRenderer`
removes that preset terrain below the litematic before placing the model so the
orthographic top view also has a clean background.

## WorldRenderer mixin

The Yarn 1.21.1 merged jar was inspected with `javap`; the actual methods are:

```text
renderSky(Matrix4f, Matrix4f, float, Camera, boolean, Runnable)
renderClouds(MatrixStack, Matrix4f, Matrix4f, float, double, double, double)
```

`WorldRendererMixin` cancels both methods. At the head of `renderSky`, it clears
the color buffer to opaque white before cancelling the vanilla blue sky pass.
The mixin was validated by a clean compile and by Fabric's runtime injector with
`defaultRequire: 1`; the complete render exited successfully.

## Run result

Command:

```bash
JAVA_HOME=/usr/lib/jvm/jdk-21.0.10-oracle-x64 \
PATH=/usr/lib/jvm/jdk-21.0.10-oracle-x64/bin:$PATH \
JAVA_TOOL_OPTIONS='-Dlitematic.input=/tmp/mcoo.litematic -Dlitematic.output=/tmp/poc_v60' \
  xvfb-run -a ./gradlew runClient '--args=--width 1024 --height 1024'
```

The log recorded `Loaded 5x8x6 palette=67 tiles=23`, wrote all four images, and
ended with `BUILD SUCCESSFUL`.

| View | Output | SHA-256 |
| --- | --- | --- |
| top | `/tmp/poc_v60/mcoo_top.png` | `a1bfdc2046a8c0de0382de8ace2dbfe112d06d296b7d4eaede87eec4e018fbb5` |
| front | `/tmp/poc_v60/mcoo_front.png` | `f35c65af810de4544e37a636ae4ada215ab1a9471fbf699f80775fa7bf2169c2` |
| side | `/tmp/poc_v60/mcoo_side.png` | `473930179d1e2951f8e7ecb8752122187433f68adf7d6b6b0713c755947200f2` |
| iso | `/tmp/poc_v60/mcoo_iso.png` | `9dd8d25b3b9ffe83433cca70f392aa5ac6936b2928481b943d0d571df3c5e744` |

All files are 1024×1024 RGBA PNGs.

## Visual acceptance

- PASS: all four backgrounds are opaque white.
- PASS: no blue sky, clouds, grass terrain, or void spawn platform is visible.
- PASS: the trapped chest is rendered as a chest block entity, including its
  three-dimensional lid/latch form.
- PASS: glass remains translucent; blocks, rails, and water are visible through
  overlapping glass in the isometric view.

The four final PNGs were inspected directly at their original resolution.
