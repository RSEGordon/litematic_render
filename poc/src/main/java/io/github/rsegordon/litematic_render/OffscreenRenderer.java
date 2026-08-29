package io.github.rsegordon.litematic_render;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorPresets;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.network.chat.Component;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.image.ColorConvertOp;
import java.awt.image.ConvolveOp;
import java.awt.image.BufferedImage;
import java.awt.image.Kernel;

/** Minimal proof: populate a ClientWorld, let the normal WorldRenderer draw it, copy its framebuffer. */
public final class OffscreenRenderer {
    private static final java.awt.Color BLUEPRINT_LINE = java.awt.Color.WHITE;
    private static final long PAPER_DAY_TIME = 6000L;
    private static final int RENDER_DISTANCE_CHUNKS =
            Math.max(2, Integer.getInteger("litematic.render.distanceChunks", 32));
    private static final int SERVER_VIEW_DISTANCE_CHUNKS = Math.max(2,
            Integer.getInteger("litematic.server.viewDistanceChunks", RENDER_DISTANCE_CHUNKS));
    private static final int RENDER_SAFETY_CHUNKS = Math.max(0,
            Integer.getInteger("litematic.render.safetyChunks", 2));
    private static final int CAPTURE_BASE_RESOLUTION = 1536;
    private static final int CAPTURE_MAX_RESOLUTION = 2048;
    private static final double CAPTURE_LONG_VIEW_BOOST = 1.35;
    private static final int CONTENT_GUTTER_X = 36;
    private static final int CONTENT_GUTTER_Y = 48;
    private static final double PRINCIPAL_GAP_MULTIPLIER = 1.20;
    private static final int BASE_SHEET_WIDTH = 4096;
    private static final int BASE_SHEET_HEIGHT = 3072;
    private static final double MIN_SHEET_UI_SCALE = 0.85;
    private static final double MAX_SHEET_UI_SCALE = 2.0;
    private static final double LONG_VIEW_COMPACTION = 0.75;
    private static final String RENDER_WORLD_PREFIX = "LitematicRender_";
    private static final Pattern RENDER_WORLD_NAME = Pattern.compile(
            RENDER_WORLD_PREFIX + "[A-Za-z0-9_-]{1,64}");

    // 工程图固定版式 helper:
    // View.LEFT_Z_NEG 显示在页面"右视图"列(View.RIGHT_Z_POS 显示在"左视图"列)。
    // 使用这两个 helper 避免下次又把字面量当显示语义。
    static ViewPlacement displayedRightPlacement(EngineeringSheetLayout layout) {
        return layout.placement(View.LEFT_Z_NEG);
    }

    static ViewPlacement displayedLeftPlacement(EngineeringSheetLayout layout) {
        return layout.placement(View.RIGHT_Z_POS);
    }
    private static Job job;
    private static boolean worldStartRequested;
    private static ViewState activeView;
    private static volatile boolean paperFullbright;
    private static final int REQUIRED_STABLE_RENDER_FRAMES = Math.max(1,
            Integer.getInteger("litematic.render.stableFrames", 5));
    private static final long VIEW_RENDER_READY_TIMEOUT_MS = Math.max(1_000L,
            Long.getLong("litematic.render.readyTimeoutMs", 30_000L));
    static {
        ClientTickEvents.END_CLIENT_TICK.register(OffscreenRenderer::tick);
    }
    private OffscreenRenderer() {}

    public static void arm(String input, String output) {
        arm(input, output, null);
    }

    public static void arm(String input, String output, String title) {
        setPaperFullbright(false);
        job = new Job(Path.of(input), Path.of(output), title);
        System.out.printf("LITEMATIC_RENDER_ARMED input=%s output=%s title=%s style=%s%n",
                job.input.toAbsolutePath(), job.out.toAbsolutePath(), job.sheetTitle(), job.style);
    }

    public static boolean isPaperFullbright() { return paperFullbright; }

    public static void setPaperFullbright(boolean value) { paperFullbright = value; }

    /** Called after vanilla extracts the camera, but before 26.2 builds its culling frustum. */
    public static void applyProjection(CameraRenderState camera) {
        if (activeView == null) return;
        Minecraft client = Minecraft.getInstance();
        float aspect = renderTargetAspect(client);
        camera.projectionMatrix.setOrtho(
                -activeView.halfSize * aspect, activeView.halfSize * aspect,
                -activeView.halfSize, activeView.halfSize,
                activeView.farPlane, 0.05f);
        camera.depthFar = activeView.farPlane;
        if (job != null) job.logProjection(client, aspect);
    }

    /** Read-only callback after vanilla LevelExtractor has populated entityRenderStates. */
    public static void diagnoseEntityExtraction(LevelRenderState state) {
        if (job != null && activeView != null) job.diagnoseEntityExtraction(state);
    }

    /** Called after the GUI renderer has composited its ItemStack atlas. */
    public static void afterGuiRendered() {
        Minecraft client=Minecraft.getInstance();
        if (job!=null && job.materialCapture && job.materialFrameReady && !job.screenshotPending)
            job.captureMaterialFrame(client);
    }

    /** Called at LevelRenderer.render TAIL: the world is complete and GUI has not been drawn. */
    public static void afterWorldRendered() {
        Minecraft client=Minecraft.getInstance();
        if (job!=null && activeView!=null) job.afterWorldRendered(client);
    }

    private static float renderTargetAspect(Minecraft client) {
        var target = client.gameRenderer.mainRenderTarget();
        return target.height == 0 ? 1.0f : (float)target.width / target.height;
    }

    private static void tick(Minecraft client) {
        if (job == null) return;
        job.logTickProgress();
        if (client.level == null) {
            if (!client.isGameLoadFinished() || client.gui.overlay() != null) return;
            if (!worldStartRequested) {
                worldStartRequested = true;
                job.progress.emit(6,"CREATE_VOID_WORLD",null,"Creating isolated void world");
                String worldName = configuredWorldName();
                deleteStaleRenderWorld(worldName);
                client.options.renderDistance().set(RENDER_DISTANCE_CHUNKS);
                System.out.println("RENDER_WORLD_CREATE name=" + worldName + " renderDistance="
                        + RENDER_DISTANCE_CHUNKS + " preset=THE_VOID generator=FlatLevelSource");
                LevelSettings levelSettings = new LevelSettings(
                        worldName, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT,
                        true, WorldDataConfiguration.DEFAULT);
                client.createWorldOpenFlows().createFreshLevel(
                        worldName,
                        levelSettings,
                        WorldOptions.defaultWithRandomSeed(),
                        registries -> {
                            FlatLevelGeneratorSettings theVoid = registries
                                    .lookupOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET)
                                    .getOrThrow(FlatLevelGeneratorPresets.THE_VOID)
                                    .value()
                                    .settings();
                            return WorldPresets.createNormalWorldDimensions(registries)
                                    .replaceOverworldGenerator(registries, new FlatLevelSource(theVoid));
                        },
                        new TitleScreen());
            }
            return;
        }
        try {
            if (!job.worldValidated) {
                job.progress.emit(10,"WAIT_WORLD_READY",null,"Waiting for render world");
                job.requestWorldValidation(client);
                return;
            }
            if (job.worldValidationError != null) {
                throw new IllegalStateException(job.worldValidationError);
            }
            if (!job.platformCleared) {
                job.progress.emit(14,"VOID_WORLD_CHECK",null,"Validating void terrain");
                int[] dimensions = job.readModelDimensions();
                int originX = -Math.floorDiv(dimensions[0], 2);
                int originZ = -Math.floorDiv(dimensions[2], 2);
                int minX = job.spawnPlatformDetected ? job.spawnPlatformMinX : originX - 16;
                int maxX = job.spawnPlatformDetected ? job.spawnPlatformMaxX
                        : originX + dimensions[0] - 1 + 16;
                int minZ = job.spawnPlatformDetected ? job.spawnPlatformMinZ : originZ - 16;
                int maxZ = job.spawnPlatformDetected ? job.spawnPlatformMaxZ
                        : originZ + dimensions[2] - 1 + 16;
                int platformY = job.spawnPlatformDetected ? job.spawnPlatformY : 0;
                int blocksCleared = clearSpawnPlatform(minX, maxX, platformY, minZ, maxZ,
                        Blocks.AIR.defaultBlockState(),
                        (pos, state) -> client.level.setBlock(pos, state, 3));
                job.platformCleared = true;
                System.out.println("LITEMATIC_RENDER_PLATFORM_CLEARED bounds=[" + minX + ","
                        + platformY + "," + minZ + "]-[" + maxX + "," + platformY + "," + maxZ + "]"
                        + " blocks=" + blocksCleared);
            }
            job.progress.emit(18,"CHUNK_DISTANCE_CHECK",null,"Checking runtime chunk coverage");
            if (client.player == null) return;
            if (client.player.isDeadOrDying()) {
                if (!job.respawnRequested) {
                    job.respawnRequested = true;
                    client.player.respawn();
                    System.out.println("[STEP 1] persisted dead player: respawn requested");
                }
                return;
            }
            if (!job.playerSecured) job.securePlayer(client);
            if (!job.loaded && !job.requiredChunksReady(client, "LOAD", job.initialRequiredChunks())) return;
            if (!job.loaded) { job.progress.emit(22,"LOAD_LITEMATIC",null,"Loading litematic"); job.load(client); return; }
            job.enforceCaptureTime(client);
            job.logEntityTickDiagnostic();
            job.freezeEntities();
            if (job.materialCapture) {
                job.captureMaterialsTick(client);
                return;
            }
            View view = View.values()[Math.min(job.view, View.values().length - 1)];
            if (job.prepareCaptureTarget(client, view)) return;
            activeView = job.cameraFor(view, client);
            if (!job.synchronizeViewPosition(client, view, activeView)) return;
            LinkedHashSet<ChunkPos> viewChunks=job.requiredChunks();
            if (!job.requiredChunksReady(client, job.capturePass + ":" + view, viewChunks)) return;
            if (job.passRebuildPending) {
                client.levelRenderer.invalidateCompiledGeometry(
                        client.level,client.options,client.gameRenderer.mainCamera(),client.getBlockColors());
                client.level.setSectionRangeDirty(
                        SectionPos.blockToSectionCoord((int)Math.floor(job.minX)),
                        SectionPos.blockToSectionCoord((int)Math.floor(job.minY)),
                        SectionPos.blockToSectionCoord((int)Math.floor(job.minZ)),
                        SectionPos.blockToSectionCoord((int)Math.ceil(job.maxX)),
                        SectionPos.blockToSectionCoord((int)Math.ceil(job.maxY)),
                        SectionPos.blockToSectionCoord((int)Math.ceil(job.maxZ)));
                job.meshInvalidatedAt = System.nanoTime();
                System.out.printf("PAPER_MESH_REBUILD pass=%s requested=true settled=false invalidateNanos=%d%n",
                        job.capturePass,job.meshInvalidatedAt);
                job.passRebuildPending = false;
                job.wait = -120;
                job.resetRenderReady(view);
                return;
            }
            if (!job.viewRenderReady(client, view, activeView, viewChunks)) return;
            job.wait++;
            if (job.blackPass == null) {
                if (job.screenshotPending) return;
                if (job.wait < 35) return;
                job.requestBlackPass(client);
                return;
            }
            if (job.screenshotPending) return;
            if (job.wait < 5) return;
            job.requestCapture(client, view);
        } catch (Exception error) {
            if (job!=null) job.progress.error("RENDER_FAILED", error.getMessage());
            System.out.printf("[STEP ERROR] render failed%n  - type: %s%n  - message: %s%n",
                    error.getClass().getName(), error.getMessage());
            error.printStackTrace();
            job.clearNightVision(client);
            setPaperFullbright(false);
            client.stop();
            job = null;
            activeView = null;
        }
    }

    @FunctionalInterface
    interface BlockSetter {
        void set(BlockPos pos, BlockState state);
    }

    static String configuredWorldName() {
        String worldName = System.getProperty(
                "litematic.render.worldName", RENDER_WORLD_PREFIX + "Temporary");
        if (!RENDER_WORLD_NAME.matcher(worldName).matches()) {
            throw new IllegalArgumentException("Invalid temporary render world name: " + worldName);
        }
        return worldName;
    }

    static void deleteStaleRenderWorld(String worldName) {
        if (!RENDER_WORLD_NAME.matcher(worldName).matches()) {
            throw new IllegalArgumentException("Refusing to delete non-render world: " + worldName);
        }
        Path worldPath = Path.of("saves", worldName);
        if (!Files.exists(worldPath)) return;
        try (var paths = Files.walk(worldPath)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
            System.out.println("RENDER_WORLD_STALE_DELETE name=" + worldName + " result=PASS");
        } catch (Exception error) {
            throw new IllegalStateException("Unable to delete stale render world " + worldName, error);
        }
    }

    static int clearSpawnPlatform(int minX, int maxX, int y, int minZ, int maxZ,
                                  BlockState fillState, BlockSetter setter) {
        if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("invalid platform bounds");
        int blocksCleared = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                setter.set(new BlockPos(x, y, z), fillState);
                blocksCleared++;
            }
        }
        return blocksCleared;
    }

    /**
     * V76 engineering views.  The yaw/pitch values are Minecraft camera angles;
     * cameraFor() converts them to a look vector with sin/cos.  Cardinal names
     * describe the observation station, e.g. FRONT_X_POS is viewed from +X.
     */
    enum View {
        FRONT_X_POS(90, 0), LEFT_Z_NEG(0, 0), RIGHT_Z_POS(180, 0),
        BACK_X_NEG(270, 0), TOP_X_UP(90, 90), BOTTOM_X_UP(90, -90),
        AXON_X_POS_Z_POS(135, 30), AXON_X_NEG_Z_POS(225, 30),
        AXON_X_POS_Z_NEG(45, 30), AXON_X_NEG_Z_NEG(315, 30);
        final float yaw,pitch;
        View(float yaw,float pitch) { this.yaw=yaw;this.pitch=pitch; }
    }

    private enum Style {
        BLUEPRINT, PAPER, BOTH;

        static Style configured() {
            String value = System.getProperty("litematic.style", "both").trim().toUpperCase(Locale.ROOT);
            try {
                return valueOf(value);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid litematic.style='" +
                        value.toLowerCase(Locale.ROOT) + "' (expected blueprint, paper, or both)", error);
            }
        }

        boolean writesBlueprint() { return this != PAPER; }
        boolean writesPaper() { return this != BLUEPRINT; }
    }

    private enum CapturePass {
        PAPER_COLOR, BLUEPRINT_EDGE
    }

    static final class RenderReadyState {
        private final int requiredFrames;
        private int stableFrames;
        RenderReadyState(int requiredFrames) {
            if (requiredFrames < 1) throw new IllegalArgumentException("requiredFrames must be positive");
            this.requiredFrames=requiredFrames;
        }
        boolean observe(boolean chunksReady, boolean overlayAbsent, boolean loadingScreenAbsent,
                        boolean playerCorrect, boolean cameraCorrect) {
            if (chunksReady && overlayAbsent && loadingScreenAbsent && playerCorrect && cameraCorrect)
                stableFrames++;
            else stableFrames=0;
            return stableFrames>=requiredFrames;
        }
        int stableFrames() { return stableFrames; }
        void reset() { stableFrames=0; }
    }

    static final class ProgressReporter {
        private int progress=-1;
        private String lastStep;
        private View lastView;
        private final Set<View> completedViews=java.util.EnumSet.noneOf(View.class);
        synchronized void emit(int value,String step,View view,String message) {
            if (value<0 || value>100) throw new IllegalArgumentException("progress outside 0..100: "+value);
            value=Math.max(value,progress);
            if (value==progress && step.equals(lastStep) && view==lastView) return;
            progress=value;
            lastStep=step;
            lastView=view;
            String viewValue=view==null?"none":view.name();
            String escaped=message==null?"":message.replace('"','\'');
            System.out.printf("RENDER_PROGRESS progress=%d step=%s view=%s message=\"%s\"%n",
                    value,step,viewValue,escaped);
            System.out.printf("[%3d%%] %s%s%n",value,step,view==null?"":" "+view.name());
        }
        synchronized void viewDone(View view,int value) {
            completedViews.add(view); emit(value,"VIEW_DONE",view,"View capture complete");
        }
        synchronized void error(String code,String message) {
            System.out.printf("RENDER_ERROR progress=%d step=FAILED view=none code=%s message=\"%s\"%n",
                    Math.max(0,progress),code,message==null?"":message.replace('"','\''));
        }
        int progress() { return progress; }
        Set<View> completedViews() { return Set.copyOf(completedViews); }
    }

    private record ViewState(Vec3 position, Vec3 forward, Vec3 right, Vec3 up,
                             float halfSize, float farPlane,
                             double projectedWorldWidth, double projectedWorldHeight) {}

    record ProjectedSpan(double width, double height) {}

    record ViewPlacement(View view, int x, int y, int width, int height) {
        int bottom() { return y + height; }
        int right() { return x + width; }
        int centerX() { return x + width / 2; }
        int centerY() { return y + height / 2; }
    }

    record LayoutRect(int x,int y,int width,int height) {
        int right() { return x+width; }
        int bottom() { return y+height; }
        int intersectionArea(ViewPlacement placement) {
            int width=Math.max(0,Math.min(right(),placement.right())-Math.max(x,placement.x()));
            int height=Math.max(0,Math.min(bottom(),placement.bottom())-Math.max(y,placement.y()));
            return width*height;
        }
        boolean contains(ViewPlacement placement) {
            return placement.x()>=x && placement.y()>=y
                    && placement.right()<=right() && placement.bottom()<=bottom();
        }
        // V146 §57: 测试用 —— drawingArea 是否包含 principalReservedRect。
        boolean contains(LayoutRect other) {
            return other.x()>=x && other.y()>=y
                    && other.right()<=right() && other.bottom()<=bottom();
        }
    }

    // V146: CornerSlot enum 已删除(教学 §7)。Axon 通过列 anchor (displayRight / back) + 行 anchor (TOP / BOTTOM centerY) 直接放。
    // V146: 顶层 helper — buildEngineeringSheetLayout / unionPrincipalPlacements 需要。
    private static boolean isPrincipalView(View view) {
        return view == View.FRONT_X_POS || view == View.LEFT_Z_NEG ||
                view == View.RIGHT_Z_POS || view == View.BACK_X_NEG ||
                view == View.TOP_X_UP || view == View.BOTTOM_X_UP;
    }

    record SheetUiMetrics(double scale,int titleFont,int subtitleFont,int materialsTitleFont,
            int materialsBodyFont,int iconSize,int rowHeight,int columnGap,int panelPadding,
            int scaleBarFont,float scaleBarStroke,float outerBorderStroke,float panelBorderStroke,
            int scaleBarHeight,int materialsHeight) {}

    // V147 §4: Materials panel 完整布局记录 —— 列数自适应后所有绘制都从这里读。
    record MaterialsLayout(int columns,int rows,
            int panelWidth,int panelHeight,int innerWidth,
            int[] columnWidths,int[] columnX,
            int fontSize,int iconSize,int rowHeight,int columnGap,int padding,
            int titleGap) {}

    // V144 新增: scale 直接驱动,不再依赖 Canvas 尺寸。
    static SheetUiMetrics sheetUiMetricsForScale(double requestedScale,int materialCount) {
        double scale=Math.max(MIN_SHEET_UI_SCALE,Math.min(MAX_SHEET_UI_SCALE,requestedScale));
        int titleFont=scaled(34,scale),subtitleFont=scaled(20,scale);
        int materialsTitleFont=scaled(22,scale),materialsBodyFont=scaled(17,scale);
        int iconSize=scaled(33,scale),padding=scaled(18,scale),columnGap=scaled(54,scale);
        int fontHeight=(int)Math.ceil(materialsBodyFont*1.25);
        int rowHeight=Math.max(iconSize+scaled(9,scale),fontHeight+scaled(10,scale));
        int rows=Math.max(1,(materialCount+3)/4);
        int materialsHeight=padding+materialsTitleFont+scaled(12,scale)+rows*rowHeight+padding;
        return new SheetUiMetrics(scale,titleFont,subtitleFont,materialsTitleFont,materialsBodyFont,
                iconSize,rowHeight,columnGap,padding,scaled(19,scale),
                (float)Math.max(1.0,3*scale),(float)Math.max(1.0,2*scale),
                (float)Math.max(1.0,1.2*scale),scaled(74,scale),materialsHeight);
    }

    // V144 保留旧 API 给测试用;实际生产路径走 sheetUiMetricsForScale。
    static SheetUiMetrics sheetUiMetrics(int canvasWidth,int canvasHeight,int materialCount) {
        double scale=Math.min(canvasWidth/(double)BASE_SHEET_WIDTH,
                canvasHeight/(double)BASE_SHEET_HEIGHT);
        return sheetUiMetricsForScale(scale,materialCount);
    }

    private static int scaled(int base,double scale) { return Math.max(1,(int)Math.round(base*scale)); }

    // V146: 删除 CornerSlot(V141/V144 slot architecture 已废弃),axonSlots / axonSlotAssignments 不再保留。
    record EngineeringSheetLayout(int canvasWidth, int canvasHeight,
            int drawingTop, int drawingBottom, int drawingCenterY,
            int principalGroupTop, int principalGroupBottom, int principalMainRowY,
            int principalGapX, int principalGapY, int drawingsBottom,
            double globalAxonScale,String axonAspectMode,
            LayoutRect drawingArea,LayoutRect principalReservedRect,
            Map<View,java.awt.Dimension> sourceSizes,
            Map<View,ViewPlacement> placements) {
        ViewPlacement placement(View view) {
            ViewPlacement placement=placements.get(view);
            if (placement==null) throw new IllegalArgumentException("Missing placement for "+view);
            return placement;
        }
    }

    // V146 唯一权威 builder:一个函数一次生成 10 个 placements + 真实 union Canvas。
    // 测试与生产必须只调用这一份。
    static EngineeringSheetLayout buildEngineeringSheetLayout(Map<View,java.awt.Dimension> sizes,
            int margin,int titleHeight,int gapX,int gapY,int scaleBarHeight,int materialsHeight) {
        // V146 §12: 检查 10 个尺寸都存在。
        for (View view:View.values()) {
            java.awt.Dimension size=sizes.get(view);
            if (size==null) throw new IllegalArgumentException("Missing source size for "+view);
            if (size.width<=0 || size.height<=0) throw new IllegalArgumentException("Invalid source size for "+view);
        }
        // V146 §13: gapX/gapY 进入 builder 后乘 1.20 一次,后续不再乘。
        int baseGapX=scaled(gapX,PRINCIPAL_GAP_MULTIPLIER);
        int baseGapY=scaled(gapY,PRINCIPAL_GAP_MULTIPLIER);
        Map<View,java.awt.Dimension> srcSizes=new java.util.EnumMap<>(View.class);
        srcSizes.putAll(sizes);
        View[] main={View.LEFT_Z_NEG,View.FRONT_X_POS,View.RIGHT_Z_POS,View.BACK_X_NEG};
        // V146 §14: Principal cell = 六个 Principal 中 max(width, height)。
        int principalCell=1;
        for (View view:View.values()) if (isPrincipalView(view)) {
            java.awt.Dimension s=srcSizes.get(view);
            principalCell=Math.max(principalCell,Math.max(s.width,s.height));
        }
        // V146 §15: Axon 长宽比 + maxRawLong。
        View[] axons={View.AXON_X_POS_Z_POS,View.AXON_X_NEG_Z_POS,
                View.AXON_X_POS_Z_NEG,View.AXON_X_NEG_Z_NEG};
        double maxAxonAspect=1.0;
        int maxRawLong=1;
        for (View view:axons) {
            java.awt.Dimension s=srcSizes.get(view);
            int longEdge=Math.max(s.width,s.height);
            int shortEdge=Math.max(1,Math.min(s.width,s.height));
            maxRawLong=Math.max(maxRawLong,longEdge);
            maxAxonAspect=Math.max(maxAxonAspect,longEdge/(double)shortEdge);
        }
        // V146 §16: aspect mode + target long ratio。
        String axonAspectMode;
        double targetLongRatio;
        if (maxAxonAspect>AXON_ASPECT_EXTREME) {
            axonAspectMode="EXTREME_ELONGATED";
            targetLongRatio=0.82;
        } else if (maxAxonAspect>AXON_ASPECT_ELONGATED) {
            axonAspectMode="ELONGATED";
            targetLongRatio=0.78;
        } else {
            axonAspectMode="NORMAL";
            targetLongRatio=0.72;
        }
        // V146 §17: globalAxonScale。
        int targetAxonLong=(int)Math.round(principalCell*targetLongRatio);
        double globalAxonScale=targetAxonLong/(double)maxRawLong;
        globalAxonScale=Math.min(1.0,globalAxonScale); // §17 禁止 source 无限放大。
        // V146 §18: axonDisplaySizes + uniformAxonBoxW/H。
        Map<View,java.awt.Dimension> axonDisplaySizes=new java.util.EnumMap<>(View.class);
        int uniformAxonBoxW=1,uniformAxonBoxH=1;
        for (View view:axons) {
            java.awt.Dimension raw=srcSizes.get(view);
            int w=Math.max(1,(int)Math.round(raw.width*globalAxonScale));
            int h=Math.max(1,(int)Math.round(raw.height*globalAxonScale));
            axonDisplaySizes.put(view,new java.awt.Dimension(w,h));
            uniformAxonBoxW=Math.max(uniformAxonBoxW,w);
            uniformAxonBoxH=Math.max(uniformAxonBoxH,h);
        }
        // V146 §21: mainHeight = 4 个 main 的 max height(项目内都是 common scale,所以等高)。
        int mainHeight=0;
        for (View view:main) mainHeight=Math.max(mainHeight,srcSizes.get(view).height);
        java.awt.Dimension top=srcSizes.get(View.TOP_X_UP);
        java.awt.Dimension bottom=srcSizes.get(View.BOTTOM_X_UP);
        // V146 §28: 局部坐标 mainY=0 / x=0,后整体平移。
        int mainY=0;
        int x=0;
        Map<View,ViewPlacement> placements=new java.util.EnumMap<>(View.class);
        // V146 §29-30: 用 finalGapX 放 main row,这里先用一个临时 gapX(finalGapX 在 §25 算),先放 main 占位。
        // 我们先按 baseGapX 放,等 finalGapX 算出来再 align。
        // §23/§24 给出 left/right 双向约束。
        double sheetScale=principalCell/(double)BASE_PRINCIPAL_CELL;
        int axonSafetyGapX=(int)Math.round(AXON_SAFETY_GAP_BASE*sheetScale);
        int axonSafetyGapY=axonSafetyGapX;
        // 临时放 main 取 front centerX。
        for (View view:main) {
            java.awt.Dimension s=srcSizes.get(view);
            placements.put(view,new ViewPlacement(view,x,mainY,s.width,mainHeight));
            x+=s.width+baseGapX;
        }
        ViewPlacement tempDisplayRight=placements.get(View.LEFT_Z_NEG);
        ViewPlacement tempFront=placements.get(View.FRONT_X_POS);
        ViewPlacement tempBack=placements.get(View.BACK_X_NEG);
        // V146 §23 requiredGapLeft。
        int requiredGapLeft=(int)Math.ceil(
                uniformAxonBoxW/2.0+axonSafetyGapX+top.width/2.0
                        -tempDisplayRight.width()/2.0-tempFront.width()/2.0);
        // V146 §24 requiredGapRight (FRONT ↔ BACK 中有两个 gap,需要各承担 (required-frontToBackWithoutGap)/2)。
        double fixedFrontToBackWithoutGap=tempFront.width()/2.0
                +placements.get(View.RIGHT_Z_POS).width()
                +tempBack.width()/2.0;
        double requiredDistance=top.width/2.0+axonSafetyGapX+uniformAxonBoxW/2.0;
        int requiredGapRight=(int)Math.ceil(Math.max(0,(requiredDistance-fixedFrontToBackWithoutGap)/2.0));
        // V146 §25: finalGapX = max(baseGapX, requiredGapLeft, requiredGapRight)。
        int finalGapX=Math.max(baseGapX,Math.max(requiredGapLeft,requiredGapRight));
        // §26 requiredGapYTop/Bottom。
        int requiredGapYTop=(int)Math.ceil(
                uniformAxonBoxH/2.0+axonSafetyGapY-top.height/2.0);
        int requiredGapYBottom=(int)Math.ceil(
                uniformAxonBoxH/2.0+axonSafetyGapY-bottom.height/2.0);
        int finalGapY=Math.max(baseGapY,Math.max(requiredGapYTop,requiredGapYBottom));
        // 重新按 finalGapX 放 main row。
        placements.clear();
        x=0;
        for (View view:main) {
            java.awt.Dimension s=srcSizes.get(view);
            placements.put(view,new ViewPlacement(view,x,mainY,s.width,mainHeight));
            x+=s.width+finalGapX;
        }
        // V146 §30: 从最终 main row 取 anchor。
        ViewPlacement displayRight=placements.get(View.LEFT_Z_NEG);
        ViewPlacement front=placements.get(View.FRONT_X_POS);
        ViewPlacement displayLeft=placements.get(View.RIGHT_Z_POS);
        ViewPlacement back=placements.get(View.BACK_X_NEG);
        // V146 §31: TOP/BOTTOM 严格:TOP.bottom + finalGapY == FRONT.top。
        int topY=mainY-finalGapY-top.height;
        int bottomY=mainY+mainHeight+finalGapY;
        placements.put(View.TOP_X_UP,new ViewPlacement(View.TOP_X_UP,
                front.centerX()-top.width/2,topY,top.width,top.height));
        placements.put(View.BOTTOM_X_UP,new ViewPlacement(View.BOTTOM_X_UP,
                front.centerX()-bottom.width/2,bottomY,bottom.width,bottom.height));
        // V146 §32: upperCenterY / lowerCenterY 直接绑定 TOP/BOTTOM centerY。
        int upperCenterY=placements.get(View.TOP_X_UP).centerY();
        int lowerCenterY=placements.get(View.BOTTOM_X_UP).centerY();
        // V146 §33: 四个 Axon 直接放锚点,使用 globalAxonScale 后的尺寸。
        for (View av:axons) {
            java.awt.Dimension ds=axonDisplaySizes.get(av);
            int centerX=av==View.AXON_X_POS_Z_POS||av==View.AXON_X_POS_Z_NEG?displayRight.centerX():back.centerX();
            int centerY=av==View.AXON_X_POS_Z_POS||av==View.AXON_X_NEG_Z_POS?upperCenterY:lowerCenterY;
            placeAxonAtAnchor(placements,av,centerX,centerY,ds.width,ds.height);
        }
        // V146 §35-37: union 10 placements → 整体平移到 margin/title 下方。
        LayoutRect rawBounds=unionPlacements(placements);
        int dx=margin-rawBounds.x();
        int dy=margin+titleHeight-rawBounds.y();
        placements=shiftPlacements(placements,dx,dy);
        // V146 §40: drawingArea = 10 views 的真实 union。
        LayoutRect drawingArea=unionPlacements(placements);
        LayoutRect principalReserved=unionPrincipalPlacements(placements);
        // V146 §43-44: Principal group 数据从真实 Principal union 得到。
        int principalGroupTop=principalReserved.y();
        int principalGroupBottom=principalReserved.bottom();
        int principalMainRowY=placements.get(View.FRONT_X_POS).y();
        int drawingTop=drawingArea.y();
        int drawingBottom=drawingArea.bottom();
        int drawingCenterY=(drawingTop+drawingBottom)/2;
        // V146 §41: Canvas width = drawingArea.right + margin。
        int canvasWidth=drawingArea.right()+margin;
        // V146 §42: Canvas height = drawingBottom + scaleBar + materials + margin。
        int canvasHeight=drawingBottom+scaleBarHeight+materialsHeight+margin;
        return new EngineeringSheetLayout(canvasWidth,canvasHeight,
                drawingTop,drawingBottom,drawingCenterY,
                principalGroupTop,principalGroupBottom,principalMainRowY,
                finalGapX,finalGapY,
                drawingBottom,
                globalAxonScale,axonAspectMode,
                drawingArea,principalReserved,
                java.util.Collections.unmodifiableMap(srcSizes),
                java.util.Collections.unmodifiableMap(placements));
    }

    // V146 §78: 删除 centeredSlot / insetRect(V141/V144 slot architecture 已废弃)。

    // V144: Axon 在 slot 内最大相对缩放比例(V146 §19 已弃用:不允许 Axon 单独 fit slot)。
    private static final double MAX_AXON_RELATIVE_SCALE=0.85;

    // V144: UI metrics 的基础常量 (跟 sheetUiMetricsForScale 配合使用)。
    private static final int BASE_PRINCIPAL_CELL=540;

    // V146: Axon 目标 long edge 范围,按模型 axon 长宽比动态选择。
    private static final double MIN_AXON_LONG_RATIO=0.55;
    private static final double MAX_AXON_LONG_RATIO=0.90;
    private static final double PREFERRED_SHORT_EDGE_RATIO=0.08;
    private static final int MIN_SHORT_EDGE_PX=48;
    // Aspect 阈值:NORMAL / ELONGATED / EXTREME_ELONGATED。
    private static final double AXON_ASPECT_ELONGATED=2.5;
    private static final double AXON_ASPECT_EXTREME=5.0;
    // Axon 与 Principal 之间的安全间距 (按 sheetScale)。
    private static final double AXON_SAFETY_GAP_BASE=24.0;
    // 降级阶梯:0.82 → 0.76 → 0.70 → 0.64 → 0.55 (最高 → 最低)
    private static final double[] AXON_LONG_DOWNGRADE_STEPS={0.82,0.76,0.70,0.64,0.55};

    // V146 §40: Axon 放锚点 — 接收已确定的 scaledW/scaledH,放到指定 anchor center。
    private static void placeAxonAtAnchor(Map<View,ViewPlacement> placements,
            View view,int centerX,int centerY,int width,int height) {
        int x=centerX-width/2;
        int y=centerY-height/2;
        placements.put(view,new ViewPlacement(view,x,y,width,height));
    }

    // V146 §35-36: 10 views union & 仅 Principal union。
    static LayoutRect unionPlacements(Map<View,ViewPlacement> placements) {
        int minX=Integer.MAX_VALUE;
        int minY=Integer.MAX_VALUE;
        int maxX=Integer.MIN_VALUE;
        int maxY=Integer.MIN_VALUE;
        for (ViewPlacement p:placements.values()) {
            minX=Math.min(minX,p.x());
            minY=Math.min(minY,p.y());
            maxX=Math.max(maxX,p.right());
            maxY=Math.max(maxY,p.bottom());
        }
        return new LayoutRect(minX,minY,Math.max(0,maxX-minX),Math.max(0,maxY-minY));
    }

    static LayoutRect unionPrincipalPlacements(Map<View,ViewPlacement> placements) {
        int minX=Integer.MAX_VALUE;
        int minY=Integer.MAX_VALUE;
        int maxX=Integer.MIN_VALUE;
        int maxY=Integer.MIN_VALUE;
        for (Map.Entry<View,ViewPlacement> entry:placements.entrySet()) {
            if (!isPrincipalView(entry.getKey())) continue;
            ViewPlacement p=entry.getValue();
            minX=Math.min(minX,p.x());
            minY=Math.min(minY,p.y());
            maxX=Math.max(maxX,p.right());
            maxY=Math.max(maxY,p.bottom());
        }
        return new LayoutRect(minX,minY,Math.max(0,maxX-minX),Math.max(0,maxY-minY));
    }

    // V146 §38: 平移 helper。
    static Map<View,ViewPlacement> shiftPlacements(Map<View,ViewPlacement> source,int dx,int dy) {
        Map<View,ViewPlacement> result=new java.util.EnumMap<>(View.class);
        for (Map.Entry<View,ViewPlacement> entry:source.entrySet()) {
            ViewPlacement p=entry.getValue();
            result.put(entry.getKey(),
                    new ViewPlacement(p.view(),p.x()+dx,p.y()+dy,p.width(),p.height()));
        }
        return result;
    }

    // V147: Materials panel 列数 4→3→2→1 自适应。列宽用真实 FontMetrics 计算。
    // 返回的 MaterialsLayout 包含列数、每列宽度、每列 x 偏移、行高、panel 高度等。
    // 绘制 drawMaterials() 和 compositeEngineeringSheet() 都只读这里。
    static final int MAX_MATERIAL_COLUMNS=4;

    // V148: Materials 列宽 = 平均分配 innerWidth(列组铺满 panel);name/icon 左对齐 + quantity 右对齐本列。
    static MaterialsLayout buildMaterialsLayout(java.util.List<MaterialEntry> materials,
            int availablePanelWidth,SheetUiMetrics ui,java.awt.FontMetrics metrics) {
        return buildMaterialsLayout(materials,availablePanelWidth,ui,metrics,MAX_MATERIAL_COLUMNS);
    }

    private static MaterialsLayout buildMaterialsLayout(java.util.List<MaterialEntry> materials,
            int availablePanelWidth,SheetUiMetrics ui,java.awt.FontMetrics metrics,
            int requestedMaxColumns) {
        int padding=ui.panelPadding();
        int innerWidth=Math.max(0,availablePanelWidth-2*padding);
        int iconSize=ui.iconSize();
        int columnGap=ui.columnGap();
        int rowHeight=ui.rowHeight();
        int titleGap=scaled(32,ui.scale());
        int fontSize=ui.materialsBodyFont();
        int iconTextGap=scaled(10,ui.scale());
        int nameQuantityGap=scaled(12,ui.scale());
        // V148 §21: 预先测量每条 entry 的 quantity 宽度(列宽 = 平均分,每列能装下最小 name)。
        int[] quantityWidths=new int[materials.size()];
        for (int i=0;i<materials.size();i++) {
            String quantity=Long.toString(materials.get(i).count);
            quantityWidths[i]=Math.max(1,metrics.stringWidth(quantity));
        }
        // 最小可读 name 空间:起码能让一个短汉字 name 不被 ellipsis。
        int minReadableNameW=scaled(40,ui.scale());
        // V148 §22: 列数 = 选最大可行列数(按平均列宽能装下最小 entry)。
        int maxColumns=Math.max(1,Math.min(requestedMaxColumns,
                Math.min(MAX_MATERIAL_COLUMNS,Math.max(1,materials.size()))));
        int chosen=1;
        for (int candidate=maxColumns;candidate>=1;candidate--) {
            // V148 §9: 平均列宽 = (innerWidth - (candidate-1)*columnGap) / candidate。
            int totalColumnWidth=innerWidth-(candidate-1)*columnGap;
            if (totalColumnWidth<=0) continue;
            int baseColumnWidth=totalColumnWidth/candidate;
            if (baseColumnWidth<=0) continue;
            // V148 §21: 检查所有 entry 在平均列宽下,至少有 minReadableNameW 留给 name。
            boolean ok=true;
            for (int i=0;i<materials.size();i++) {
                int nameMaxWidth=baseColumnWidth-iconSize-iconTextGap-nameQuantityGap-quantityWidths[i];
                if (nameMaxWidth<minReadableNameW) { ok=false; break; }
            }
            if (ok) { chosen=candidate; break; }
        }
        // V148 §22: 真正生成 MaterialsLayout —— 列宽严格等宽,左 padding 起铺满。
        int rows=(int)Math.ceil(materials.size()/(double)chosen);
        int totalColumnWidth=innerWidth-(chosen-1)*columnGap;
        int baseColumnWidth=chosen>0?totalColumnWidth/chosen:innerWidth;
        int remainder=chosen>0?totalColumnWidth%chosen:0;
        int[] columnWidths=new int[chosen];
        int[] columnX=new int[chosen];
        for (int c=0;c<chosen;c++) {
            columnWidths[c]=baseColumnWidth+(c<remainder?1:0);
        }
        // V148 §22: columnX[0] = padding,columnX[c] = columnX[c-1] + columnWidths[c-1] + gap。
        columnX[0]=padding;
        for (int c=1;c<chosen;c++) {
            columnX[c]=columnX[c-1]+columnWidths[c-1]+columnGap;
        }
        int panelHeight=padding+ui.materialsTitleFont()+titleGap+rows*rowHeight+padding;
        MaterialsLayout chosenLayout=new MaterialsLayout(chosen,rows,
                availablePanelWidth,panelHeight,innerWidth,
                columnWidths,columnX,
                fontSize,iconSize,rowHeight,columnGap,padding,titleGap);
        // V148 §24: 内容占满率日志。
        int totalColW=0;
        for (int w:columnWidths) totalColW+=w;
        double fillRatio=innerWidth>0?(totalColW+(chosen-1)*columnGap)/(double)innerWidth:0.0;
        System.out.printf(Locale.ROOT,
                "MATERIALS_LAYOUT mode=JUSTIFIED chosenColumns=%d rows=%d panelWidth=%d innerWidth=%d columnGap=%d",
                chosen,rows,availablePanelWidth,innerWidth,columnGap);
        for (int c=0;c<chosen;c++) {
            System.out.printf(Locale.ROOT," columnWidth[%d]=%d",c,columnWidths[c]);
        }
        System.out.printf(Locale.ROOT," fillRatio=%.3f%n",fillRatio);
        if (rows==1) {
            System.out.printf(Locale.ROOT,
                    "MATERIALS_LAYOUT_SINGLE_ROW justified=true columns=%d%n",chosen);
        }
        for (int c=0;c<chosen;c++) {
            System.out.printf(Locale.ROOT,
                    "MATERIALS_COLUMN index=%d x=%d width=%d items=%d%n",
                    c,columnX[c],columnWidths[c],chosenLayout.rows());
        }
        System.out.printf(Locale.ROOT,
                "MATERIALS_UI fontSize=%d iconSize=%d rowHeight=%d columnGap=%d padding=%d%n",
                chosenLayout.fontSize(),chosenLayout.iconSize(),chosenLayout.rowHeight(),
                chosenLayout.columnGap(),chosenLayout.padding());
        return chosenLayout;
    }

    static ProjectedSpan projectedSpan(double minX, double minY, double minZ,
                                       double maxX, double maxY, double maxZ,
                                       float yawDegrees, float pitchDegrees) {
        Vec3 center=new Vec3((minX+maxX)/2.0,(minY+maxY)/2.0,(minZ+maxZ)/2.0);
        double yaw=Math.toRadians(yawDegrees), pitch=Math.toRadians(pitchDegrees);
        Vec3 forward=new Vec3(-Math.sin(yaw)*Math.cos(pitch),-Math.sin(pitch),
                Math.cos(yaw)*Math.cos(pitch));
        Vec3 right=new Vec3(Math.cos(yaw),0,Math.sin(yaw)).normalize();
        Vec3 up=forward.cross(right).normalize();
        double horizontalHalfExtent=0, verticalHalfExtent=0;
        for (double x : new double[]{minX,maxX}) for (double y : new double[]{minY,maxY})
            for (double z : new double[]{minZ,maxZ}) {
                Vec3 delta=new Vec3(x,y,z).subtract(center);
                horizontalHalfExtent=Math.max(horizontalHalfExtent,Math.abs(delta.dot(right)));
                verticalHalfExtent=Math.max(verticalHalfExtent,Math.abs(delta.dot(up)));
            }
        return new ProjectedSpan(horizontalHalfExtent*2.0,verticalHalfExtent*2.0);
    }

    static final class Job {
        final Path input, out; final String title; final Style style; final long renderTime, jobStarted;
        final double blueprintNightVision, blueprintGamma;
        boolean loaded, platformCleared, respawnRequested, playerSecured, screenshotPending, nightVisionApplied, passRebuildPending;
        boolean worldCaptureBlackRequested,worldCaptureWhiteRequested;
        boolean coverageValidated;
        volatile boolean worldValidationStarted, worldValidated;
        volatile String worldValidationError;
        volatile int clientRenderDistance, serverViewDistance, serverSimulationDistance,
                effectiveViewDistance;
        volatile String teleportRequestedKey, teleportReadyKey, teleportError;
        volatile boolean spawnPlatformDetected;
        volatile int spawnPlatformMinX, spawnPlatformMaxX, spawnPlatformY,
                spawnPlatformMinZ, spawnPlatformMaxZ;
        String chunkReadyKey;
        String chunkReadyPassedKey;
        long chunkReadyStarted, chunkReadyLastLog;
        String renderReadyKey,renderReadyPassedKey;
        long renderReadyStarted,renderReadyLastLog;
        final RenderReadyState renderReadyState=new RenderReadyState(REQUIRED_STABLE_RENDER_FRAMES);
        long worldRenderFrame;
        long readinessObservedFrame=-1;
        boolean materialCapture, materialFrameReady;
        int materialCapturePhase, materialWait, writtenSingleViews;
        long passStarted, viewStarted, materialStarted;
        long meshInvalidatedAt;
        int matteBlackFrame,matteCameraHash;
        long matteGeometryRevision;
        int wait, view, tickCount;
        int captureWidth, captureHeight;
        // 诊断日志限频字段:
        // - lastEntityTickDiagnosticNanos: ENTITY_TICK_DIAG 上次输出时间
        // - lastCameraDiagnosticKey: 上次 CAMERA_VIEW/AXON_DISTANCE_CHECK 的 (pass+view+resolution) key
        long lastEntityTickDiagnosticNanos;
        String lastCameraDiagnosticKey;
        NativeImage blackPass;
        CapturePass capturePass;
        MobEffectInstance previousNightVision;
        double previousGamma;
        long previousDayTime;
        double minX, minY, minZ, maxX, maxY, maxZ;
        final List<FrozenEntity> frozenEntities=new ArrayList<>();
        final List<MaterialEntry> materials=new ArrayList<>();
        NativeImage materialBlackPass;
        int nextEntityId = -1000;
        String lastEntityDiagnosticKey, lastProjectionDiagnosticKey;
        // V144: 单一 UI metrics,Paper/Blueprint/Materials/日志共享同一份。
        SheetUiMetrics engineeringSheetUiMetrics;
        // Per-view saved native image (post alpha matting) for composite assembly
        final NativeImage[] composites = new NativeImage[View.values().length];
        final float[] principalHalfSizes = new float[View.values().length];
        final int[] principalCaptureWidths = new int[View.values().length];
        final int[] principalCaptureHeights = new int[View.values().length];
        final double[] principalProjectedWidths = new double[View.values().length];
        final double[] principalProjectedHeights = new double[View.values().length];
        final Map<View,int[]> paperPrincipalRects = new HashMap<>();
        final Map<View,int[]> blueprintPrincipalRects = new HashMap<>();
        EngineeringSheetLayout engineeringSheetLayout;
        final PrincipalProjectionFrame[] sharedPrincipalFrames = new PrincipalProjectionFrame[View.values().length];
        final ProgressReporter progress=new ProgressReporter();
        Job(Path input, Path out, String title) {
            this.input=input;
            this.out=out;
            this.title=title;
            this.jobStarted=System.nanoTime();
            this.style=Style.configured();
            this.renderTime=configuredRenderTime();
            this.blueprintNightVision=unitDoubleProperty("litematic.render.nightvision", 1.0);
            this.blueprintGamma=positiveDoubleProperty("litematic.render.gamma", 2.5);
            this.capturePass=style.writesPaper() ? CapturePass.PAPER_COLOR : CapturePass.BLUEPRINT_EDGE;
            progress.emit(0,"START",null,"Render job started");
            progress.emit(3,"VALIDATE_INPUT",null,"Validating litematic input");
        }

        private static boolean isLoadingScreen(Screen screen) {
            if (screen==null) return false;
            String name=screen.getClass().getSimpleName();
            return name.equals("ReceivingLevelScreen") || name.equals("ProgressScreen")
                    || name.equals("LevelLoadingScreen") || name.contains("TerrainLoading")
                    || name.equals("LoadingOverlay");
        }

        void resetRenderReady(View view) {
            renderReadyKey=null;
            renderReadyPassedKey=null;
            renderReadyState.reset();
            readinessObservedFrame=-1;
        }

        boolean viewRenderReady(Minecraft client,View view,ViewState state,
                                LinkedHashSet<ChunkPos> required) {
            String key=capturePass+":"+view;
            long now=System.nanoTime();
            if (!key.equals(renderReadyKey)) {
                renderReadyKey=key; renderReadyStarted=now; renderReadyLastLog=0;
                renderReadyPassedKey=null;
                renderReadyState.reset(); readinessObservedFrame=-1;
                progress.emit(viewProgress(view,1),"WAIT_RENDER_READY",view,
                        "Waiting for stable world render frames");
            }
            if (key.equals(renderReadyPassedKey)) return true;
            boolean chunksReady=missingChunks(required,
                    chunk -> client.level!=null && client.level.hasChunk(chunk.x(),chunk.z())).isEmpty();
            boolean overlayAbsent=client.gui.overlay()==null;
            Screen screen=client.gui.screen();
            boolean loadingAbsent=client.isGameLoadFinished() && !isLoadingScreen(screen);
            double playerY=state.position.y-client.player.getEyeHeight();
            boolean playerCorrect=client.player.position().distanceToSqr(
                    state.position.x,playerY,state.position.z)<0.0001;
            boolean cameraCorrect=activeView==state;
            if (readinessObservedFrame!=worldRenderFrame) {
                readinessObservedFrame=worldRenderFrame;
                renderReadyState.observe(chunksReady,overlayAbsent,loadingAbsent,playerCorrect,cameraCorrect);
            }
            long elapsedMs=(now-renderReadyStarted)/1_000_000;
            boolean ready=renderReadyState.stableFrames()>=REQUIRED_STABLE_RENDER_FRAMES;
            if (ready) {
                renderReadyPassedKey=key;
                System.out.printf("VIEW_RENDER_READY view=%s chunksReady=%s overlay=%s screen=%s "
                                +"stableFrames=%d elapsedMs=%d result=PASS%n",view,chunksReady,
                        !overlayAbsent,screen==null?"none":screen.getClass().getSimpleName(),
                        renderReadyState.stableFrames(),elapsedMs);
                return true;
            }
            if (elapsedMs>=VIEW_RENDER_READY_TIMEOUT_MS) {
                System.out.printf("VIEW_RENDER_READY_TIMEOUT view=%s chunksReady=%s overlay=%s screen=%s "
                                +"stableFrames=%d elapsedMs=%d%n",view,chunksReady,!overlayAbsent,
                        screen==null?"none":screen.getClass().getSimpleName(),
                        renderReadyState.stableFrames(),elapsedMs);
                progress.error("VIEW_RENDER_READY_TIMEOUT",
                        "World render did not stabilize within 30 seconds");
                throw new IllegalStateException("VIEW_RENDER_READY_TIMEOUT for "+view);
            }
            if (renderReadyLastLog==0 || now-renderReadyLastLog>=1_000_000_000L) {
                renderReadyLastLog=now;
                System.out.printf("VIEW_RENDER_READY view=%s chunksReady=%s overlay=%s screen=%s "
                                +"stableFrames=%d/%d elapsedMs=%d result=WAIT%n",view,chunksReady,
                        !overlayAbsent,screen==null?"none":screen.getClass().getSimpleName(),
                        renderReadyState.stableFrames(),REQUIRED_STABLE_RENDER_FRAMES,elapsedMs);
            }
            return false;
        }

        private static int viewProgress(View view,int phase) {
            return Math.min(75,40+view.ordinal()*3+Math.max(0,Math.min(3,phase)));
        }

        void afterWorldRendered(Minecraft client) {
            worldRenderFrame++;
            if (screenshotPending) return;
            if (worldCaptureBlackRequested) {
                worldCaptureBlackRequested=false;
                captureBlackWorldFrame(client);
            } else if (worldCaptureWhiteRequested) {
                worldCaptureWhiteRequested=false;
                captureWhiteWorldFrame(client,View.values()[Math.min(view,View.values().length-1)]);
            }
        }

        void requestWorldValidation(Minecraft client) throws Exception {
            if (worldValidationError != null) throw new IllegalStateException(worldValidationError);
            if (worldValidationStarted) return;
            worldValidationStarted = true;
            int[] dimensions = readModelDimensions();
            int originX = -Math.floorDiv(dimensions[0], 2);
            int originZ = -Math.floorDiv(dimensions[2], 2);
            int minChunkX = SectionPos.blockToSectionCoord(originX) - 1;
            int maxChunkX = SectionPos.blockToSectionCoord(originX + dimensions[0] - 1) + 1;
            int minChunkZ = SectionPos.blockToSectionCoord(originZ) - 1;
            int maxChunkZ = SectionPos.blockToSectionCoord(originZ + dimensions[2] - 1) + 1;
            IntegratedServer server = client.getSingleplayerServer();
            if (server == null) throw new IllegalStateException("Render world has no integrated server");
            server.execute(() -> {
                try {
                    var overworld = server.overworld();
                    var generator = overworld.getChunkSource().getGenerator();
                    boolean flat = generator instanceof FlatLevelSource;
                    System.out.printf("VOID_WORLD_CHECK world=%s generator=%s result=%s%n",
                            configuredWorldName(), generator.getClass().getSimpleName(), flat ? "PASS" : "FAIL");
                    if (!flat) throw new IllegalStateException(
                            "Render world is not THE_VOID FlatLevelSource");

                    server.getPlayerList().setViewDistance(SERVER_VIEW_DISTANCE_CHUNKS);
                    server.getPlayerList().setSimulationDistance(SERVER_VIEW_DISTANCE_CHUNKS);
                    clientRenderDistance = client.options.renderDistance().get();
                    serverViewDistance = server.getPlayerList().getViewDistance();
                    serverSimulationDistance = server.getPlayerList().getSimulationDistance();
                    effectiveViewDistance = Math.min(clientRenderDistance, serverViewDistance);
                    System.out.printf("CHUNK_DISTANCE_CONFIG clientRender=%d serverView=%d "
                                    + "serverSimulation=%d effectiveView=%d safety=%d%n",
                            clientRenderDistance, serverViewDistance, serverSimulationDistance,
                            effectiveViewDistance, RENDER_SAFETY_CHUNKS);

                    int checkedChunks = 0;
                    long nonAirTerrain = 0;
                    int terrainMinX = Integer.MAX_VALUE, terrainMaxX = Integer.MIN_VALUE;
                    int terrainMinY = Integer.MAX_VALUE, terrainMaxY = Integer.MIN_VALUE;
                    int terrainMinZ = Integer.MAX_VALUE, terrainMaxZ = Integer.MIN_VALUE;
                    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                            ChunkAccess chunk = overworld.getChunk(chunkX, chunkZ);
                            checkedChunks++;
                            for (int sectionIndex = 0; sectionIndex < chunk.getSections().length; sectionIndex++) {
                                var section = chunk.getSections()[sectionIndex];
                                if (section.hasOnlyAir()) continue;
                                int sectionY = chunk.getMinY() + sectionIndex * 16;
                                for (int localY = 0; localY < 16; localY++) {
                                    for (int localZ = 0; localZ < 16; localZ++) {
                                        for (int localX = 0; localX < 16; localX++) {
                                            BlockState state = section.getBlockState(localX, localY, localZ);
                                            if (!isOrdinaryTerrain(state)) continue;
                                            int blockX = chunkX * 16 + localX;
                                            int blockY = sectionY + localY;
                                            int blockZ = chunkZ * 16 + localZ;
                                            nonAirTerrain++;
                                            terrainMinX = Math.min(terrainMinX, blockX);
                                            terrainMaxX = Math.max(terrainMaxX, blockX);
                                            terrainMinY = Math.min(terrainMinY, blockY);
                                            terrainMaxY = Math.max(terrainMaxY, blockY);
                                            terrainMinZ = Math.min(terrainMinZ, blockZ);
                                            terrainMaxZ = Math.max(terrainMaxZ, blockZ);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    System.out.printf("VOID_TERRAIN_SCAN nonAir=%d bounds=[%d,%d,%d]-[%d,%d,%d]%n",
                            nonAirTerrain, terrainMinX, terrainMinY, terrainMinZ,
                            terrainMaxX, terrainMaxY, terrainMaxZ);
                    if (isRecognizedVoidSpawnPlatform(nonAirTerrain,
                            terrainMinX, terrainMaxX, terrainMinY, terrainMaxY,
                            terrainMinZ, terrainMaxZ)) {
                        spawnPlatformDetected = true;
                        spawnPlatformMinX = terrainMinX;
                        spawnPlatformMaxX = terrainMaxX;
                        spawnPlatformY = terrainMinY;
                        spawnPlatformMinZ = terrainMinZ;
                        spawnPlatformMaxZ = terrainMaxZ;
                        for (int x = terrainMinX; x <= terrainMaxX; x++) {
                            for (int z = terrainMinZ; z <= terrainMaxZ; z++) {
                                overworld.setBlock(new BlockPos(x, terrainMinY, z),
                                        Blocks.AIR.defaultBlockState(), 3);
                            }
                        }
                        nonAirTerrain = 0;
                        for (int x = terrainMinX; x <= terrainMaxX; x++) {
                            for (int z = terrainMinZ; z <= terrainMaxZ; z++) {
                                if (!overworld.getBlockState(new BlockPos(x, terrainMinY, z)).isAir()) {
                                    nonAirTerrain++;
                                }
                            }
                        }
                        System.out.printf("VOID_SPAWN_PLATFORM_CLEAR bounds=[%d,%d,%d]-[%d,%d,%d] "
                                        + "result=%s%n",
                                terrainMinX, terrainMinY, terrainMinZ,
                                terrainMaxX, terrainMaxY, terrainMaxZ,
                                nonAirTerrain == 0 ? "PASS" : "FAIL");
                    }
                    boolean clean = nonAirTerrain == 0;
                    System.out.printf("VOID_TERRAIN_CHECK checkedChunks=%d nonAirTerrain=%d result=%s%n",
                            checkedChunks, nonAirTerrain, clean ? "PASS" : "FAIL");
                    if (!clean) throw new IllegalStateException(
                            "Render world contains ordinary terrain before litematic load: " + nonAirTerrain);
                    worldValidated = true;
                } catch (Exception error) {
                    worldValidationError = error.getMessage() == null
                            ? error.getClass().getName() : error.getMessage();
                    worldValidated = true;
                }
            });
        }

        int[] readModelDimensions() throws Exception {
            CompoundTag root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            CompoundTag regions = root.getCompoundOrEmpty("Regions");
            if (regions.keySet().isEmpty()) throw new IllegalArgumentException("No litematic regions");
            CompoundTag region = regions.getCompoundOrEmpty(regions.keySet().iterator().next());
            CompoundTag size = region.getCompoundOrEmpty("Size");
            int sx = Math.abs(size.getIntOr("x", 0));
            int sy = Math.abs(size.getIntOr("y", 0));
            int sz = Math.abs(size.getIntOr("z", 0));
            if (sx == 0 || sy == 0 || sz == 0) {
                throw new IllegalArgumentException("Litematic region has a zero dimension");
            }
            return new int[]{sx, sy, sz};
        }

        static boolean isOrdinaryTerrain(BlockState state) {
            // Before job.load() a true void world has no blocks at all outside
            // the known spawn platform. Treat every non-air state as terrain so
            // ores, bedrock, vegetation, snow and modded blocks cannot evade the
            // sanity check merely because they are not in a short allow-list.
            return !state.isAir();
        }

        static boolean isRecognizedVoidSpawnPlatform(long count,
                int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            if (count == 0 || minY != maxY) return false;
            int width = maxX - minX + 1;
            int depth = maxZ - minZ + 1;
            return width <= 33 && depth <= 33 && count == (long)width * depth;
        }

        /**
         * Protect both sides of the integrated-server player before waiting for
         * chunks or placing any litematic blocks. Client-only health/position
         * changes are overwritten by the server and can still leave a death
         * screen over every framebuffer capture.
         */
        void securePlayer(Minecraft client) {
            client.player.setInvulnerable(true);
            client.player.setHealth(20.0f);
            client.player.getFoodData().setFoodLevel(20);
            client.player.getFoodData().setSaturation(20.0f);
            client.player.getAbilities().mayfly = true;
            client.player.getAbilities().flying = true;
            client.player.resetFallDistance();
            client.player.removeAllEffects();

            IntegratedServer server = client.getSingleplayerServer();
            if (server == null) throw new IllegalStateException("Render world has no integrated server");
            UUID playerId = client.player.getUUID();
            server.execute(() -> {
                var serverPlayer = server.getPlayerList().getPlayer(playerId);
                if (serverPlayer == null) return;
                serverPlayer.setGameMode(GameType.CREATIVE);
                serverPlayer.setInvulnerable(true);
                serverPlayer.setHealth(20.0f);
                serverPlayer.getFoodData().setFoodLevel(20);
                serverPlayer.getFoodData().setSaturation(20.0f);
                serverPlayer.getAbilities().mayfly = true;
                serverPlayer.getAbilities().flying = true;
                serverPlayer.resetFallDistance();
                serverPlayer.removeAllEffects();
                serverPlayer.onUpdateAbilities();
            });
            playerSecured = true;
            System.out.printf("[STEP 1] secure player%n  - gamemode: creative%n"
                    + "  - invulnerable: true%n  - flying: true%n  - health: 20%n  - food: 20%n"
                    + "  - effects: cleared%n");
        }

        void load(Minecraft client) throws Exception {
            long loadStarted = System.nanoTime();
            System.out.printf("[STEP 2] load litematic%n  - input: %s%n  - size: %d bytes%n",
                    input.toAbsolutePath(), Files.size(input));
            // Material names are part of the deliverable, rather than UI chrome.
            // Load vanilla Simplified Chinese translations before resolving the
            // ItemStack hover names so the result is independent of options.txt.
            if (!"zh_cn".equals(client.getLanguageManager().getSelected())) {
                client.getLanguageManager().setSelected("zh_cn");
                client.getLanguageManager().onResourceManagerReload(client.getResourceManager());
            }
            CompoundTag root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            CompoundTag regions = root.getCompoundOrEmpty("Regions");
            if (regions.keySet().isEmpty()) throw new IllegalArgumentException("No litematic regions");
            CompoundTag region = regions.getCompoundOrEmpty(regions.keySet().iterator().next());
            CompoundTag size = region.getCompoundOrEmpty("Size");
            int sizeX=size.getIntOr("x",0),sizeY=size.getIntOr("y",0),sizeZ=size.getIntOr("z",0);
            int sx=Math.abs(sizeX),sy=Math.abs(sizeY),sz=Math.abs(sizeZ);
            captureWidth=client.gameRenderer.mainRenderTarget().width;
            captureHeight=client.gameRenderer.mainRenderTarget().height;
            int lowestSafeOriginY=client.level.getMinY();
            int highestSafeOriginY=client.level.getMaxY()-sy;
            if (highestSafeOriginY<lowestSafeOriginY) {
                throw new IllegalArgumentException("Litematic height exceeds world build height: "+sy);
            }
            int originY=Math.max(lowestSafeOriginY,Math.min(160,highestSafeOriginY));
            int originX=-Math.floorDiv(sx,2),originZ=-Math.floorDiv(sz,2);
            minX=originX; minY=originY; minZ=originZ;
            maxX=originX+sx; maxY=originY+sy; maxZ=originZ+sz;
            ListTag paletteNbt = region.getListOrEmpty("BlockStatePalette");
            List<BlockState> palette = new ArrayList<>();
            for (int i=0;i<paletteNbt.size();i++) palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, paletteNbt.getCompoundOrEmpty(i)));
            long[] packed=region.getLongArray("BlockStates").orElseThrow(); int bits=Math.max(2, 32-Integer.numberOfLeadingZeros(palette.size()-1)); long mask=(1L<<bits)-1;
            BlockPos origin=new BlockPos(originX,originY,originZ);
            clearRenderBounds(client,origin,sx,sy,sz);
            progress.emit(26,"PLACE_BLOCKS",null,"Placing litematic blocks");
            long[] paletteCounts = new long[palette.size()];
            for (int y=0;y<sy;y++) for (int z=0;z<sz;z++) for (int x=0;x<sx;x++) {
                int n=(y*sz+z)*sx+x, start=n*bits, word=start>>>6, shift=start&63;
                long value=packed[word]>>>shift; if (shift+bits>64) value|=packed[word+1]<<(64-shift);
                int paletteIndex=(int)(value&mask);
                paletteCounts[paletteIndex]++;
                client.level.setBlock(origin.offset(x,y,z), palette.get(paletteIndex), 19,512);
            }
            HashMap<Item, Long> itemCounts = new HashMap<>();
            for (int i=0;i<palette.size();i++) {
                Item item=palette.get(i).getBlock().asItem();
                ItemStack stack=item.getDefaultInstance();
                if (!stack.isEmpty() && paletteCounts[i] > 0) itemCounts.merge(item,paletteCounts[i],Long::sum);
            }
            itemCounts.forEach((item,count) -> materials.add(new MaterialEntry(
                    item.getDefaultInstance(),item.getDefaultInstance().getHoverName().getString(),count)));
            materials.sort(Comparator.comparingLong(MaterialEntry::count).reversed()
                    .thenComparing(MaterialEntry::name,String.CASE_INSENSITIVE_ORDER));
            ListTag tiles=region.getListOrEmpty("TileEntities");
            for (int i=0;i<tiles.size();i++) {
                CompoundTag tag=tiles.getCompoundOrEmpty(i).copy(); BlockPos p=origin.offset(tag.getIntOr("x",0),tag.getIntOr("y",0),tag.getIntOr("z",0));
                tag.putInt("x",p.getX());tag.putInt("y",p.getY());tag.putInt("z",p.getZ());
                BlockEntity be=BlockEntity.loadStatic(p,client.level.getBlockState(p),tag,client.level.registryAccess());
                if (be!=null) client.level.setBlockEntity(be);
            }
            ListTag entities=region.getListOrEmpty("Entities");
            progress.emit(30,"LOAD_ENTITIES",null,"Loading litematic entities");
            Vec3 entityOrigin=new Vec3(
                    origin.getX()+(sizeX<0?sx-1:0),
                    origin.getY()+(sizeY<0?sy-1:0),
                    origin.getZ()+(sizeZ<0?sz-1:0));
            int entityCount=0,mountedEntityCount=0;
            for (int i=0;i<entities.size();i++) {
                final int entityIndex=i;
                CompoundTag tag=entities.getCompoundOrEmpty(i).copy();
                Entity rootEntity=EntityType.loadEntityRecursive(tag,client.level,
                        new EntitySpawnRequest(EntitySpawnReason.LOAD,false), entity -> {
                    entity.setUUID(UUID.randomUUID());
                    entity.setPos(entity.position().add(entityOrigin));
                    return entity;
                });
                if (rootEntity==null) continue;
                List<Entity> entityTree=rootEntity.getSelfAndPassengers().toList();
                Map<Entity,Entity> vehicles=new IdentityHashMap<>();
                for (Entity entity : entityTree) {
                    if (entity.getVehicle()!=null) vehicles.put(entity,entity.getVehicle());
                    entity.stopRiding();
                    entity.setId(nextEntityId--);
                    // Litematica stores positions relative to the region.  The
                    // recursive loader has decoded each entity's Pos already;
                    // pin that translated position explicitly before adding it
                    // to the client level, including nested passengers.
                    Vec3 spawnPosition=entity.position();
                    entity.setPos(spawnPosition.x,spawnPosition.y,spawnPosition.z);
                    entity.setDeltaMovement(Vec3.ZERO);
                    entity.setNoGravity(true);
                    client.level.addEntity(entity);
                    if (client.level.getEntity(entity.getId()) != entity) {
                        throw new IllegalStateException("Client level did not register entity "
                                + entity.getType() + " at " + entity.position());
                    }
                    entityCount++;
                }
                for (Map.Entry<Entity,Entity> riding : vehicles.entrySet()) {
                    // loadEntityRecursive attaches passengers before the root's
                    // region translation is applied, which can leave riders at
                    // a wildly displaced inherited position.  Align them with
                    // the translated vehicle before restoring the relationship.
                    riding.getKey().setPos(riding.getValue().position());
                    if (!riding.getKey().startRiding(riding.getValue(),true,false)) {
                        throw new IllegalStateException("Failed to restore riding relation for entity "
                                + riding.getKey().getType());
                    }
                    riding.getValue().positionRider(riding.getKey());
                    mountedEntityCount++;
                }
                for (Entity entity : entityTree) {
                    frozenEntities.add(new FrozenEntity(entity,entity.position()));
                    include(entity.getBoundingBox());
                    System.out.printf("ENTITY_LOAD type=%s id=%d uuid=%s position=(%.4f,%.4f,%.4f) "
                                    + "boundingBox=%s registered=%s removed=%s removalReason=%s alive=%s "
                                    + "vehicle=%s passengers=%s%n",
                            entity.getType(),entity.getId(),entity.getUUID(),entity.getX(),entity.getY(),entity.getZ(),
                            entity.getBoundingBox(),client.level.getEntity(entity.getId()) == entity,
                            entity.isRemoved(),entity.getRemovalReason(),entity.isAlive(),
                            entity.getVehicle() == null ? "none" : entity.getVehicle().getId(),
                            entity.getPassengers().stream().map(passenger -> Integer.toString(passenger.getId())).toList());
                }
            }
            Files.createDirectories(out);
            if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            client.options.fov().set(50);
            previousGamma = client.options.gamma().get();
            previousDayTime = client.level.getOverworldClockTime();
            client.options.renderDistance().set(RENDER_DISTANCE_CHUNKS);
            progress.emit(34,"PREPARE_RENDERER",null,"Preparing capture renderer");
            configureCapturePass(client);
            validateAllViewCoverage(client);
            // Recreate section geometry with the safe 26.2 path. Unlike
            // resetLevelRenderData(), this waits for the occlusion graph reset
            // before the next extraction and includes newly non-empty sections.
            client.levelRenderer.invalidateCompiledGeometry(
                    client.level,client.options,client.gameRenderer.mainCamera(),client.getBlockColors());
            meshInvalidatedAt = System.nanoTime();
            System.out.printf("PAPER_MESH_REBUILD pass=%s requested=true settled=false invalidateNanos=%d%n",
                    capturePass,meshInvalidatedAt);
            loaded=true;
            progress.emit(38,"START_VIEWS",null,"Starting ten engineering views");
            System.out.printf("  - loaded: %dx%dx%d palette=%d tiles=%d entities=%d mounted=%d%n  - elapsed: %d ms%n  - bounds: [%.2f,%.2f,%.2f]-[%.2f,%.2f,%.2f]%n",
                    sx,sy,sz,palette.size(),tiles.size(),entityCount,mountedEntityCount,
                    (System.nanoTime()-loadStarted)/1_000_000,
                    minX,minY,minZ,maxX,maxY,maxZ);
        }

        LinkedHashSet<ChunkPos> requiredChunks() {
            return requiredChunks(minX, maxX, minZ, maxZ);
        }

        LinkedHashSet<ChunkPos> initialRequiredChunks() throws Exception {
            int[] dimensions = readModelDimensions();
            int originX = -Math.floorDiv(dimensions[0], 2);
            int originZ = -Math.floorDiv(dimensions[2], 2);
            return requiredChunks(originX, originX + dimensions[0],
                    originZ, originZ + dimensions[2]);
        }

        static LinkedHashSet<ChunkPos> requiredChunks(
                double minX, double maxX, double minZ, double maxZ) {
            int minChunkX = SectionPos.blockToSectionCoord((int)Math.floor(minX));
            int maxChunkX = SectionPos.blockToSectionCoord((int)Math.ceil(maxX) - 1);
            int minChunkZ = SectionPos.blockToSectionCoord((int)Math.floor(minZ));
            int maxChunkZ = SectionPos.blockToSectionCoord((int)Math.ceil(maxZ) - 1);
            LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    chunks.add(new ChunkPos(chunkX, chunkZ));
                }
            }
            return chunks;
        }

        static List<ChunkPos> outsideChunks(
                Iterable<ChunkPos> required, ChunkPos cameraChunk, int viewDistance) {
            ChunkTrackingView trackingView = ChunkTrackingView.of(cameraChunk, viewDistance);
            List<ChunkPos> outside = new ArrayList<>();
            for (ChunkPos chunk : required) {
                if (!trackingView.contains(chunk)) outside.add(chunk);
            }
            return outside;
        }

        static List<ChunkPos> missingChunks(
                Iterable<ChunkPos> required, java.util.function.Predicate<ChunkPos> loaded) {
            List<ChunkPos> missing = new ArrayList<>();
            for (ChunkPos chunk : required) {
                if (!loaded.test(chunk)) missing.add(chunk);
            }
            return missing;
        }

        boolean requiredChunksReady(Minecraft client, String key,
                                    LinkedHashSet<ChunkPos> required) {
            long now = System.nanoTime();
            if (!key.equals(chunkReadyKey)) {
                chunkReadyKey = key;
                chunkReadyPassedKey = null;
                chunkReadyStarted = now;
                chunkReadyLastLog = 0;
            }
            if (key.equals(chunkReadyPassedKey)) return true;
            List<ChunkPos> missing = missingChunks(required,
                    chunk -> client.level.hasChunk(chunk.x(), chunk.z()));
            long elapsedMs = (now - chunkReadyStarted) / 1_000_000;
            if (missing.isEmpty()) {
                chunkReadyPassedKey = key;
                System.out.printf("CHUNK_READY view=%s required=%d loaded=%d missing=0 "
                                + "elapsedMs=%d result=PASS%n",
                        key, required.size(), required.size(), elapsedMs);
                if ("LOAD".equals(key)) {
                    System.out.printf("LOAD_CHUNKS_READY required=%d loaded=%d result=PASS%n",
                            required.size(), required.size());
                }
                return true;
            }
            if (elapsedMs >= 30_000) {
                System.out.printf("CHUNK_LOAD_TIMEOUT view=%s required=%d loaded=%d missing=%d "
                                + "chunks=%s%n",
                        key, required.size(), required.size() - missing.size(), missing.size(),
                        missing.stream().limit(12).toList());
                throw new IllegalStateException("Timed out waiting for required chunks for " + key
                        + ": missing=" + missing.size());
            }
            if (chunkReadyLastLog == 0 || now - chunkReadyLastLog >= 1_000_000_000L) {
                chunkReadyLastLog = now;
                System.out.printf("CHUNK_READY view=%s required=%d loaded=%d missing=%d "
                                + "elapsedMs=%d result=WAIT%n",
                        key, required.size(), required.size() - missing.size(), missing.size(), elapsedMs);
            }
            return false;
        }

        void validateAllViewCoverage(Minecraft client) {
            LinkedHashSet<ChunkPos> required = requiredChunks();
            int minChunkX = required.stream().mapToInt(ChunkPos::x).min().orElseThrow();
            int maxChunkX = required.stream().mapToInt(ChunkPos::x).max().orElseThrow();
            int minChunkZ = required.stream().mapToInt(ChunkPos::z).min().orElseThrow();
            int maxChunkZ = required.stream().mapToInt(ChunkPos::z).max().orElseThrow();
            int validatedViewDistance = Math.max(2, effectiveViewDistance - RENDER_SAFETY_CHUNKS);
            System.out.printf(Locale.ROOT,
                    "MODEL_BOUNDS blockBounds=[%.4f,%.4f]-[%.4f,%.4f] "
                            + "chunkBounds=[%d,%d]-[%d,%d] requiredChunks=%d%n",
                    minX, minZ, maxX, maxZ, minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                    required.size());
            for (View candidate : View.values()) {
                ViewState state = cameraFor(candidate, client);
                ChunkPos cameraChunk = new ChunkPos(
                        SectionPos.blockToSectionCoord((int)Math.floor(state.position.x)),
                        SectionPos.blockToSectionCoord((int)Math.floor(state.position.z)));
                List<ChunkPos> outside = outsideChunks(required, cameraChunk, validatedViewDistance);
                String result = outside.isEmpty() ? "PASS" : "FAIL";
                System.out.printf(Locale.ROOT,
                        "CHUNK_COVERAGE view=%s camera=Vec3(%.4f,%.4f,%.4f) cameraChunk=[%d,%d] "
                                + "viewDistance=%d required=%d inside=%d outside=%d result=%s%n",
                        candidate, state.position.x, state.position.y, state.position.z,
                        cameraChunk.x(), cameraChunk.z(), validatedViewDistance, required.size(),
                        required.size() - outside.size(), outside.size(), result);
                if (!outside.isEmpty()) {
                    System.out.println("CHUNK_COVERAGE_OUTSIDE view=" + candidate + " chunks="
                            + outside.stream().limit(12).toList());
                    throw new IllegalArgumentException("Required chunks exceed real view distance for "
                            + candidate + ": outside=" + outside.size());
                }
            }
            coverageValidated = true;
        }

        boolean synchronizeViewPosition(Minecraft client, View targetView, ViewState state) {
            if (teleportError != null) throw new IllegalStateException(teleportError);
            String key = capturePass + ":" + targetView;
            double playerY = state.position.y - client.player.getEyeHeight();
            if (!key.equals(teleportRequestedKey)) {
                teleportRequestedKey = key;
                teleportReadyKey = null;
                IntegratedServer server = client.getSingleplayerServer();
                if (server == null) throw new IllegalStateException("Render world has no integrated server");
                UUID playerId = client.player.getUUID();
                server.execute(() -> {
                    try {
                        var serverPlayer = server.getPlayerList().getPlayer(playerId);
                        if (serverPlayer == null) {
                            throw new IllegalStateException("Integrated ServerPlayer is unavailable");
                        }
                        serverPlayer.teleportTo(state.position.x, playerY, state.position.z);
                        serverPlayer.setYRot(targetView.yaw);
                        serverPlayer.setXRot(targetView.pitch);
                        serverPlayer.resetFallDistance();
                        teleportReadyKey = key;
                        System.out.printf(Locale.ROOT,
                                "SERVER_PLAYER_TELEPORT view=%s player=(%.4f,%.4f,%.4f) "
                                        + "camera=(%.4f,%.4f,%.4f) result=PASS%n",
                                targetView, state.position.x, playerY, state.position.z,
                                state.position.x, state.position.y, state.position.z);
                    } catch (Exception error) {
                        teleportError = error.getMessage() == null
                                ? error.getClass().getName() : error.getMessage();
                    }
                });
                return false;
            }
            if (!key.equals(teleportReadyKey)) return false;
            client.player.setPos(state.position.x, playerY, state.position.z);
            client.player.setYRot(targetView.yaw);
            client.player.setXRot(targetView.pitch);
            return true;
        }

        /** Clear every block inside the camera-visible bounds before pasting. */
        void clearRenderBounds(Minecraft client, BlockPos origin, int sx, int sy, int sz) {
            int padding=Math.max(2,(int)Math.ceil(Math.max(sx,Math.max(sy,sz))*0.1));
            int fromX=origin.getX()-padding,toX=origin.getX()+sx-1+padding;
            int fromY=Math.max(client.level.getMinY(),origin.getY()-padding);
            int toY=Math.min(client.level.getMaxY()-1,origin.getY()+sy-1+padding);
            int fromZ=origin.getZ()-padding,toZ=origin.getZ()+sz-1+padding;
            BlockState air=Blocks.AIR.defaultBlockState();
            long cleared=0;
            for (int y=fromY;y<=toY;y++) for (int z=fromZ;z<=toZ;z++) for (int x=fromX;x<=toX;x++) {
                client.level.setBlock(new BlockPos(x,y,z),air,19,512);
                cleared++;
            }
            System.out.printf("  - cleared render bounds: [%d,%d,%d]-[%d,%d,%d] blocks=%d%n",
                    fromX,fromY,fromZ,toX,toY,toZ,cleared);
        }

        void configureCapturePass(Minecraft client) {
            passStarted = System.nanoTime();
            clearNightVision(client);
            setPaperFullbright(capturePass == CapturePass.PAPER_COLOR);
            long targetTime = capturePass == CapturePass.PAPER_COLOR ? PAPER_DAY_TIME : renderTime;
            setOverworldClock(client, targetTime, 0.0f);
            long actualTime = client.level.getOverworldClockTime();
            if (actualTime != targetTime) {
                throw new IllegalStateException("Failed to set " + capturePass
                        + " time: expected=" + targetTime + " actual=" + actualTime);
            }
            // Vanilla 26.2 constrains the Brightness option to 0..1. Keep its
            // neutral value here; BLUEPRINT_EDGE applies the requested render
            // gamma directly to the captured RGBA pixels below.
            client.options.gamma().set(1.0);
            double nightVision = activeNightVision();
            if (nightVision > 0.0) {
                MobEffectInstance current = client.player.getEffect(MobEffects.NIGHT_VISION);
                if (current != null) previousNightVision = new MobEffectInstance(current);
                client.player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, Integer.MAX_VALUE,
                        Math.max(0, (int)Math.round(nightVision)), false, false));
                nightVisionApplied = true;
            }
            int step = capturePass == CapturePass.PAPER_COLOR ? 3 : 4;
            if (capturePass == CapturePass.PAPER_COLOR) {
                System.out.printf("[STEP %d] capture PAPER_COLOR%n  - time: %d%n  - gamma: %.2f%n  - fullbright: %s%n  - elapsed: %d ms%n",
                        step, actualTime, activeGamma(), isPaperFullbright(),
                        (System.nanoTime() - passStarted) / 1_000_000);
            } else {
                System.out.printf("[STEP %d] capture BLUEPRINT_EDGE%n  - time: %d%n  - gamma: %.2f%n  - fullbright: %s%n  - noise minPixels: %d%n  - noise radius: %d%n  - elapsed: %d ms%n",
                        step, actualTime, activeGamma(), isPaperFullbright(),
                        positiveIntProperty("litematic.blueprint.noise.minPixels", 6),
                        nonNegativeIntProperty("litematic.blueprint.noise.radius", 1),
                        (System.nanoTime() - passStarted) / 1_000_000);
            }
        }

        void enforceCaptureTime(Minecraft client) {
            long targetTime = capturePass == CapturePass.PAPER_COLOR ? PAPER_DAY_TIME : renderTime;
            if (client.level.getOverworldClockTime() != targetTime) {
                setOverworldClock(client, targetTime, 0.0f);
            }
        }

        double activeGamma() {
            return capturePass == CapturePass.PAPER_COLOR ? 1.0 : blueprintGamma;
        }

        double activeNightVision() {
            return capturePass == CapturePass.PAPER_COLOR ? 0.0 : blueprintNightVision;
        }

        void include(AABB box) {
            minX=Math.min(minX,box.minX); minY=Math.min(minY,box.minY); minZ=Math.min(minZ,box.minZ);
            maxX=Math.max(maxX,box.maxX); maxY=Math.max(maxY,box.maxY); maxZ=Math.max(maxZ,box.maxZ);
        }

        void freezeEntities() {
            for (FrozenEntity frozen : frozenEntities) {
                frozen.entity.setDeltaMovement(Vec3.ZERO);
                frozen.entity.setPos(frozen.position);
            }
        }

        void logTickProgress() {
            tickCount++;
            if (tickCount % 100 == 0) {
                System.out.printf("TICK_PROGRESS ticks=%d/2000 elapsed=%ds%n",
                        tickCount, (System.nanoTime() - jobStarted) / 1_000_000_000);
            }
        }

        void logEntityTickDiagnostic() {
            // 无投影实体时整条跳过,避免每 tick 刷屏。
            if (frozenEntities.isEmpty()) return;
            long now=System.nanoTime();
            // 有实体时限频:每秒最多一行。
            if (lastEntityTickDiagnosticNanos!=0
                    && now-lastEntityTickDiagnosticNanos<1_000_000_000L) {
                return;
            }
            lastEntityTickDiagnosticNanos=now;
            System.out.printf("ENTITY_TICK_DIAG time=%dms entityCount=%d%n",
                    (now-jobStarted)/1_000_000, frozenEntities.size());
        }

        ViewState cameraFor(View view, Minecraft client) {
            Vec3 center=new Vec3((minX+maxX)/2.0,(minY+maxY)/2.0,(minZ+maxZ)/2.0);
            double yaw=Math.toRadians(view.yaw), pitch=Math.toRadians(view.pitch);
            Vec3 forward=new Vec3(-Math.sin(yaw)*Math.cos(pitch),-Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch));
            Vec3 right=new Vec3(Math.cos(yaw),0,Math.sin(yaw)).normalize();
            Vec3 up=forward.cross(right).normalize();
            double horizontal=0, vertical=0, radius=0;
            for (double x : new double[]{minX,maxX}) for (double y : new double[]{minY,maxY})
                for (double z : new double[]{minZ,maxZ}) {
                    Vec3 delta=new Vec3(x,y,z).subtract(center);
                    horizontal=Math.max(horizontal,Math.abs(delta.dot(right)));
                    vertical=Math.max(vertical,Math.abs(delta.dot(up)));
                    radius=Math.max(radius,delta.length());
                }
            float aspect=renderTargetAspect(client);
            float halfSize;
            if (isPrincipalView(view)) {
                // All six principal views use one drawing scale.
                double maxSpan = Math.max(maxX - minX,
                        Math.max(maxY - minY, maxZ - minZ));
                double contentAspect=Math.max(horizontal/Math.max(0.05,vertical),
                        vertical/Math.max(0.05,horizontal));
                double densityResolution=captureBaseResolution()
                        *(contentAspect>=1.8?captureLongViewBoost():1.0);
                densityResolution=Math.min(captureMaxResolution(),densityResolution);
                // Derive the ortho height from framebuffer pixels so adaptive
                // captures retain the common principal-view pixels/block scale.
                halfSize=(float)(client.gameRenderer.mainRenderTarget().height
                        *maxSpan*0.6/densityResolution);
            } else {
                halfSize=(float)Math.max(vertical,horizontal/aspect);
            }
            halfSize=Math.max(halfSize,1.0f);
            // Orthographic scale is independent of camera distance. Stay just
            // outside the complete bounds so its chunks remain inside the
            // client's render distance even when entities make the bounds wide.
            double distance=Math.max(radius*1.05,radius+0.5);
            Vec3 position=center.subtract(forward.scale(distance));
            float farPlane=(float)Math.max(256.0,distance+radius+32.0);
            ViewState state=new ViewState(position,forward,right,up,halfSize,farPlane,
                    horizontal*2.0,vertical*2.0);
            // 限频:同一 (pass + view + resolution) 只打印一次 CAMERA_VIEW/AXON_DISTANCE_CHECK。
            // 覆盖率校验阶段用 COVERAGE 占位,进入 capture 后用真实 pass 名。
            String cameraDiagnosticKey=(coverageValidated?capturePass.name():"COVERAGE")
                    +":"+view.name()
                    +":"+Minecraft.getInstance().gameRenderer.mainRenderTarget().width
                    +"x"+Minecraft.getInstance().gameRenderer.mainRenderTarget().height;
            if (!cameraDiagnosticKey.equals(lastCameraDiagnosticKey)) {
                lastCameraDiagnosticKey=cameraDiagnosticKey;
                System.out.printf(Locale.ROOT,
                        "CAMERA_VIEW view=%s center=Vec3(%.4f,%.4f,%.4f) position=Vec3(%.4f,%.4f,%.4f) "
                                + "radius=%.4f distance=%.4f halfSize=%.4f farPlane=%.4f "
                                + "projectedWorld=%.4fx%.4f elapsed=%dms%n",
                        view,center.x,center.y,center.z,state.position.x,state.position.y,state.position.z,
                        radius,distance,state.halfSize,state.farPlane,
                        state.projectedWorldWidth,state.projectedWorldHeight,
                        (System.nanoTime() - jobStarted) / 1_000_000);
                if (!isPrincipalView(view)) {
                    double farthestCornerDistance=0.0;
                    for (double x : new double[]{minX,maxX}) for (double y : new double[]{minY,maxY})
                        for (double z : new double[]{minZ,maxZ}) {
                            farthestCornerDistance=Math.max(farthestCornerDistance,
                                    new Vec3(x,y,z).distanceTo(position));
                        }
                    System.out.printf(Locale.ROOT,
                            "AXON_DISTANCE_CHECK view=%s radius=%.4f cameraDistance=%.4f "
                                    + "farthestCornerDistance=%.4f renderDistanceBlocks=%d%n",
                            view,radius,distance,farthestCornerDistance,RENDER_DISTANCE_CHUNKS*16);
                }
            }
            return state;
        }

        /** Resize the real framebuffer for each view; composite scaling never invents capture detail. */
        boolean prepareCaptureTarget(Minecraft client, View view) {
            CaptureSize desired=captureSizeFor(view);
            if (captureWidth==desired.width && captureHeight==desired.height) return false;
            captureWidth=desired.width;
            captureHeight=desired.height;
            client.getWindow().setWindowed(captureWidth,captureHeight);
            client.gameRenderer.resize(captureWidth,captureHeight);
            wait=-10;
            System.out.printf(Locale.ROOT,
                    "ADAPTIVE_CAPTURE_SIZE view=%s contentAspect=%.4f resolution=%dx%d base=%d max=%d longBoost=%.2f%n",
                    view,desired.contentAspect,captureWidth,captureHeight,captureBaseResolution(),
                    captureMaxResolution(),captureLongViewBoost());
            return true;
        }

        private CaptureSize captureSizeFor(View view) {
            Vec3 center=new Vec3((minX+maxX)/2.0,(minY+maxY)/2.0,(minZ+maxZ)/2.0);
            double yaw=Math.toRadians(view.yaw),pitch=Math.toRadians(view.pitch);
            Vec3 forward=new Vec3(-Math.sin(yaw)*Math.cos(pitch),-Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch));
            Vec3 right=new Vec3(Math.cos(yaw),0,Math.sin(yaw)).normalize();
            Vec3 up=forward.cross(right).normalize();
            double horizontal=0,vertical=0;
            for (double x:new double[]{minX,maxX}) for (double y:new double[]{minY,maxY})
                for (double z:new double[]{minZ,maxZ}) {
                    Vec3 delta=new Vec3(x,y,z).subtract(center);
                    horizontal=Math.max(horizontal,Math.abs(delta.dot(right)));
                    vertical=Math.max(vertical,Math.abs(delta.dot(up)));
                }
            double aspect=Math.max(0.05,(horizontal*2.0)/Math.max(0.1,vertical*2.0));
            int base=captureBaseResolution(),max=captureMaxResolution();
            boolean longView=aspect>=1.8 || aspect<=1.0/1.8;
            int longAxis=Math.min(max,(int)Math.round(base*(longView?captureLongViewBoost():1.0)));
            // GLFW/Xvfb can asynchronously clamp very wide/tall window shapes.
            // Keep the framebuffer square, but allocate 1536 for ordinary views
            // and the boosted 2048 only for content whose projected aspect is long.
            return new CaptureSize(roundEven(longAxis),roundEven(longAxis),aspect);
        }

        private record CaptureSize(int width,int height,double contentAspect) {}
        private static int roundEven(int value) { return Math.max(2,(value+1)&~1); }
        private static int captureBaseResolution() { return positiveIntProperty("litematic.capture.baseResolution",CAPTURE_BASE_RESOLUTION); }
        private static int captureMaxResolution() { return Math.max(captureBaseResolution(),positiveIntProperty("litematic.capture.maxResolution",CAPTURE_MAX_RESOLUTION)); }
        private static double captureLongViewBoost() { return positiveDoubleProperty("litematic.capture.longViewBoost",CAPTURE_LONG_VIEW_BOOST); }

        void logProjection(Minecraft client, float projectionAspect) {
            View captureView=View.values()[Math.min(view,View.values().length-1)];
            String key=capturePass+":"+captureView;
            if (key.equals(lastProjectionDiagnosticKey)) return;
            lastProjectionDiagnosticKey=key;
            var window=client.getWindow();
            var target=client.gameRenderer.mainRenderTarget();
            double windowAspect=aspect(window.getWidth(),window.getHeight());
            double framebufferAspect=aspect(window.getScreenWidth(),window.getScreenHeight());
            double targetAspect=aspect(target.width,target.height);
            System.out.printf(Locale.ROOT,
                    "CAPTURE_DIMENSIONS view=%s pass=%s REQUESTED_WINDOW=%s WINDOW_LOGICAL=%dx%d "
                            + "FRAMEBUFFER=%dx%d RENDER_TARGET=%dx%d WINDOW_ASPECT=%.6f "
                            + "FRAMEBUFFER_ASPECT=%.6f TARGET_ASPECT=%.6f PROJECTION_ASPECT=%.6f%n",
                    captureView,capturePass,requestedWindowSize(),window.getWidth(),window.getHeight(),
                    window.getScreenWidth(),window.getScreenHeight(),target.width,target.height,
                    windowAspect,framebufferAspect,targetAspect,projectionAspect);
            if (!sameAspect(projectionAspect,targetAspect))
                System.out.printf(Locale.ROOT,"WARN ASPECT_MISMATCH projection=%.6f target=%.6f%n",
                        projectionAspect,targetAspect);
            if (!sameAspect(windowAspect,targetAspect))
                System.out.printf(Locale.ROOT,"WARN ASPECT_MISMATCH window=%.6f target=%.6f%n",
                        windowAspect,targetAspect);
            if (!sameAspect(framebufferAspect,targetAspect))
                System.out.printf(Locale.ROOT,"WARN ASPECT_MISMATCH framebuffer=%.6f target=%.6f%n",
                        framebufferAspect,targetAspect);
        }

        void diagnoseEntityExtraction(LevelRenderState state) {
            // Diagnose the settled frame immediately before the black capture,
            // not the transitional frame where the player camera just moved.
            if (blackPass == null && wait < 30) return;
            View captureView=View.values()[Math.min(view,View.values().length-1)];
            String key=capturePass+":"+captureView;
            if (key.equals(lastEntityDiagnosticKey)) return;
            lastEntityDiagnosticKey=key;
            // trackedEntityCount=0 表示当前 litematic 没有任何投影实体。
            // 这是正常状态:明确打 SKIP 后 return,避免输出让人误以为是错误的 client/renderState 计数。
            if (frozenEntities.isEmpty()) {
                System.out.printf("ENTITY_EXTRACTION view=%s pass=%s trackedEntityCount=0 result=SKIP reason=NO_TRACKED_LITEMATIC_ENTITIES%n",
                        captureView,capturePass);
                return;
            }
            int clientEntityCount=0;
            for (Entity ignored : Minecraft.getInstance().level.entitiesForRendering()) clientEntityCount++;
            System.out.printf("ENTITY_EXTRACTION view=%s pass=%s clientLevelEntityCount=%d renderStateCount=%d trackedEntityCount=%d%n",
                    captureView,capturePass,clientEntityCount,state.entityRenderStates.size(),frozenEntities.size());
            CameraRenderState camera=state.cameraRenderState;
            for (FrozenEntity frozen : frozenEntities) {
                Entity entity=frozen.entity;
                double distance=entity.position().distanceTo(camera.pos);
                boolean registered=Minecraft.getInstance().level.getEntity(entity.getId()) == entity;
                System.out.printf(Locale.ROOT,
                        "ENTITY_VIEW_CHECK view=%s entityId=%d type=%s position=(%.4f,%.4f,%.4f) "
                                + "boundingBox=%s cameraPosition=(%.4f,%.4f,%.4f) distanceToCamera=%.4f "
                                + "registered=%s removed=%s alive=%s%n",
                        captureView,entity.getId(),entity.getType(),entity.getX(),entity.getY(),entity.getZ(),
                        entity.getBoundingBox(),camera.pos.x,camera.pos.y,camera.pos.z,distance,
                        registered,entity.isRemoved(),entity.isAlive());
                ClipBounds clip=clipBounds(entity.getBoundingBox(),camera);
                boolean frustumVisible=camera.cullFrustum != null && camera.cullFrustum.isVisible(entity.getBoundingBox());
                boolean shouldRender=camera.cullFrustum != null && Minecraft.getInstance().levelRenderer
                        .entityRenderDispatcher().shouldRender(entity,camera.cullFrustum,
                                camera.pos.x,camera.pos.y,camera.pos.z);
                System.out.printf(Locale.ROOT,
                        "ENTITY_CLIP view=%s entityId=%d clipMinX=%.6f clipMaxX=%.6f "
                                + "clipMinY=%.6f clipMaxY=%.6f clipMinZ=%.6f clipMaxZ=%.6f "
                                + "IN_CLIP=%s FRUSTUM_VISIBLE=%s shouldRender=%s renderDistanceDecision=%s%n",
                        captureView,entity.getId(),clip.minX,clip.maxX,clip.minY,clip.maxY,
                        clip.minZ,clip.maxZ,clip.inClip,frustumVisible,shouldRender,shouldRender);
                EntityRenderState matched=null;
                for (EntityRenderState candidate : state.entityRenderStates) {
                    if (candidate.entityType == entity.getType()
                            && close(candidate.x,entity.getX()) && close(candidate.y,entity.getY())
                            && close(candidate.z,entity.getZ())) { matched=candidate; break; }
                }
                System.out.printf(Locale.ROOT,
                        "ENTITY_RENDER_STATE id=%d type=%s found=%s matchBasis=type+position%s%n",
                        entity.getId(),entity.getType(),matched != null,
                        matched == null ? "" : String.format(Locale.ROOT," distanceToCameraSq=%.6f",matched.distanceToCameraSq));
                System.out.printf("ENTITY_EXTRACTION view=%s trackedEntityId=%d trackedEntityType=%s renderStateFound=%s%n",
                        captureView,entity.getId(),entity.getType(),matched != null);
            }
        }

        private static ClipBounds clipBounds(AABB box, CameraRenderState camera) {
            Matrix4f viewProjection=new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix);
            double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY;
            double maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;
            for (double x : new double[]{box.minX,box.maxX}) for (double y : new double[]{box.minY,box.maxY})
                for (double z : new double[]{box.minZ,box.maxZ}) {
                    Vector4f clip=viewProjection.transform(new Vector4f(
                            (float)(x-camera.pos.x),(float)(y-camera.pos.y),(float)(z-camera.pos.z),1.0f));
                    double divisor=clip.w == 0.0f ? 1.0 : clip.w;
                    double nx=clip.x/divisor,ny=clip.y/divisor,nz=clip.z/divisor;
                    minX=Math.min(minX,nx);maxX=Math.max(maxX,nx);
                    minY=Math.min(minY,ny);maxY=Math.max(maxY,ny);
                    minZ=Math.min(minZ,nz);maxZ=Math.max(maxZ,nz);
                }
            boolean inClip=minX<=1 && maxX>=-1 && minY<=1 && maxY>=-1 && minZ<=1 && maxZ>=-1;
            return new ClipBounds(minX,maxX,minY,maxY,minZ,maxZ,inClip);
        }

        private record ClipBounds(double minX,double maxX,double minY,double maxY,
                                  double minZ,double maxZ,boolean inClip) {}

        private static boolean close(double left,double right) { return Math.abs(left-right)<1.0e-3; }
        private static double aspect(int width,int height) { return height == 0 ? Double.NaN : width/(double)height; }
        private static boolean sameAspect(double left,double right) { return Math.abs(left-right)<1.0e-4; }

        private static String requestedWindowSize() {
            String requestedWidth=System.getProperty("litematic.requested.width");
            String requestedHeight=System.getProperty("litematic.requested.height");
            if (requestedWidth != null && requestedHeight != null)
                return requestedWidth+"x"+requestedHeight;
            String command=System.getProperty("sun.java.command","");
            java.util.regex.Matcher width=java.util.regex.Pattern.compile("(?:^|\\s)--width(?:=|\\s+)(\\d+)").matcher(command);
            java.util.regex.Matcher height=java.util.regex.Pattern.compile("(?:^|\\s)--height(?:=|\\s+)(\\d+)").matcher(command);
            return width.find() && height.find() ? width.group(1)+"x"+height.group(1) : "UNAVAILABLE";
        }

        private static boolean isPrincipalView(View view) {
            return view == View.FRONT_X_POS || view == View.LEFT_Z_NEG ||
                    view == View.RIGHT_Z_POS || view == View.BACK_X_NEG ||
                    view == View.TOP_X_UP || view == View.BOTTOM_X_UP;
        }

        void requestBlackPass(Minecraft client) {
            if (worldCaptureBlackRequested) return;
            View captureView=View.values()[Math.min(view,View.values().length-1)];
            progress.emit(viewProgress(captureView,2),"CAPTURE_BLACK",captureView,"Capturing world-only black matte");
            worldCaptureBlackRequested=true;
        }

        private void captureBlackWorldFrame(Minecraft client) {
            View captureView = View.values()[Math.min(view, View.values().length - 1)];
            viewStarted = System.nanoTime();
            if (isPrincipalView(captureView)) {
                principalHalfSizes[captureView.ordinal()] = activeView.halfSize();
                principalCaptureWidths[captureView.ordinal()] = client.gameRenderer.mainRenderTarget().width;
                principalCaptureHeights[captureView.ordinal()] = client.gameRenderer.mainRenderTarget().height;
                principalProjectedWidths[captureView.ordinal()] = activeView.projectedWorldWidth();
                principalProjectedHeights[captureView.ordinal()] = activeView.projectedWorldHeight();
            }
            if (capturePass == CapturePass.PAPER_COLOR && isPrincipalView(captureView)) {
                long captureNanos=System.nanoTime();
                System.out.printf(Locale.ROOT,
                        "PAPER_VIEW_STATE view=%s capturePass=%s paperFullbright=%s nightVision=%.1f "
                                + "gamma=%.1f time=%d BackgroundPassWhite=%s camera=%s targetSize=%dx%d%n",
                        captureView,capturePass,isPaperFullbright(),activeNightVision(),activeGamma(),
                        client.level.getOverworldClockTime(),BackgroundPass.isWhite(),activeView.position(),
                        client.gameRenderer.mainRenderTarget().width,client.gameRenderer.mainRenderTarget().height);
                System.out.printf(Locale.ROOT,
                        "PAPER_MESH_REBUILD pass=PAPER_COLOR requested=true settled=true "
                                + "invalidateNanos=%d captureNanos=%d elapsedMs=%d%n",
                        meshInvalidatedAt,captureNanos,meshInvalidatedAt == 0 ? -1
                                : (captureNanos-meshInvalidatedAt)/1_000_000);
            }
            matteBlackFrame=tickCount;
            matteCameraHash=activeView.hashCode();
            matteGeometryRevision=meshInvalidatedAt;
            System.out.printf("PASS_START pass=%s view=%s%n", capturePass, captureView);
            System.out.printf("[STEP 5] capture views%n  - pass: %s%n  - view: %s%n  - status: started%n",
                    capturePass, captureView.name());
            screenshotPending = true;
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                blackPass = image;
                BackgroundPass.setWhite(true);
                wait = 0;
                screenshotPending = false;
            });
        }

        void requestCapture(Minecraft client, View view) {
            if (worldCaptureWhiteRequested) return;
            progress.emit(viewProgress(view,3),"CAPTURE_WHITE",view,"Capturing world-only white matte");
            worldCaptureWhiteRequested=true;
        }

        private void captureWhiteWorldFrame(Minecraft client, View view) {
            // We capture the white-background pass second; the black-background
            // pass was captured at the start of this view. matte(black, white)
            // derives per-pixel transparency from the difference between the two
            // opaque reads.
            // Keep the black framebuffer alive in blackPassBackup until matte
            // has consumed it, then close both.
            screenshotPending = true;
            blackPassBackup = blackPass;
            blackPass = null;
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), whitePass -> {
                try {
                    boolean sameCamera=activeView!=null && matteCameraHash==activeView.hashCode();
                    boolean sameGeometry=matteGeometryRevision==meshInvalidatedAt;
                    System.out.printf("MATTE_PAIR view=%s cameraHash=%d geometryRevision=%d "
                                    +"blackFrame=%d whiteFrame=%d sameCamera=%s sameGeometry=%s%n",
                            view,matteCameraHash,matteGeometryRevision,matteBlackFrame,tickCount,
                            sameCamera,sameGeometry);
                    if (!sameCamera || !sameGeometry)
                        throw new IllegalStateException("Matte pair state changed for "+view);
                    System.out.printf("MATTE %s corners black=%08x white=%08x%n",
                            view.name(), blackPassBackup.getPixel(0, 0), whitePass.getPixel(0, 0));
                    NativeImage image = matte(blackPassBackup, whitePass);
                    boolean retainImage = composites[view.ordinal()] == null;
                    if (retainImage) composites[view.ordinal()] = image;
                    BufferedImage color = nativeToBuffered(image);
                    String baseName = "mcoo_" + view.name().toLowerCase(Locale.ROOT);
                    long writeStarted = System.nanoTime();
                    if (capturePass == CapturePass.BLUEPRINT_EDGE) {
                        BufferedImage gammaCorrected = applyGamma(color, blueprintGamma);
                        BufferedImage blueprint = blueprintEffect(gammaCorrected);
                        blueprintViews[view.ordinal()] = blueprint;
                        writeSingleView(blueprint, out.resolve(baseName + ".png"));
                        writtenSingleViews++;
                        System.out.printf("[STEP 6] write single views%n  - count: %d%n  - path: %s%n  - elapsed: %d ms%n",
                                writtenSingleViews, out.resolve(baseName + ".png").toAbsolutePath(),
                                (System.nanoTime() - writeStarted) / 1_000_000);
                        gammaCorrected.flush();
                        color.flush();
                    } else {
                        if (isPrincipalView(view)) {
                            writePaperDebug(color,view,"01_matte_raw");
                            logPaperColorStats(view,"01_matte_raw",color);
                        }
                        BufferedImage adjusted=applyPaperColor(color,
                                positiveDoubleProperty("litematic.paper.brightness",0.97),
                                positiveDoubleProperty("litematic.paper.contrast",1.05),
                                positiveDoubleProperty("litematic.paper.saturation",1.00));
                        colorViews[view.ordinal()] = adjusted;
                        if (isPrincipalView(view)) {
                            writePaperDebug(adjusted,view,"02_adjusted");
                            logPaperColorStats(view,"02_adjusted",adjusted);
                        }
                        writeSingleView(adjusted, out.resolve(baseName + "_paper.png"));
                        writtenSingleViews++;
                        System.out.printf("[STEP 6] write single views%n  - count: %d%n  - path: %s%n  - elapsed: %d ms%n",
                                writtenSingleViews, out.resolve(baseName + "_paper.png").toAbsolutePath(),
                                (System.nanoTime() - writeStarted) / 1_000_000);
                        color.flush();
                    }
                    if (!retainImage) image.close();
                    System.out.printf("[STEP 5] capture views%n  - pass: %s%n  - view: %s%n  - status: OK%n  - elapsed: %d ms%n",
                            capturePass, view.name(), (System.nanoTime() - viewStarted) / 1_000_000);
                    System.out.printf("PASS_END pass=%s view=%s elapsed=%dms%n",
                            capturePass, view.name(), (System.nanoTime() - viewStarted) / 1_000_000);
                    viewComplete(client);
                } catch (Exception error) {
                    System.out.printf("[STEP 5] capture views%n  - pass: %s%n  - view: %s%n  - status: failed%n  - elapsed: %d ms%n  - message: %s%n",
                            capturePass, view.name(), (System.nanoTime() - viewStarted) / 1_000_000,
                            error.getMessage());
                    System.out.printf("PASS_END pass=%s view=%s elapsed=%dms status=failed%n",
                            capturePass, view.name(), (System.nanoTime() - viewStarted) / 1_000_000);
                    error.printStackTrace();
                    clearNightVision(client);
                    setPaperFullbright(false);
                    client.stop();
                    job = null;
                    activeView = null;
                } finally {
                    blackPassBackup.close();
                    whitePass.close();
                    blackPassBackup = null;
                    BackgroundPass.setWhite(false);
                    screenshotPending = false;
                }
            });
        }

        void viewComplete(Minecraft client) throws Exception {
            View completed=View.values()[view];
            progress.viewDone(completed,viewProgress(completed,3));
            view++;
            wait = 0;
            resetRenderReady(completed);
            if (view == View.values().length) {
                if (capturePass == CapturePass.PAPER_COLOR && style.writesBlueprint()) {
                    capturePass = CapturePass.BLUEPRINT_EDGE;
                    view = 0;
                    configureCapturePass(client);
                    // Fullbright and directional shading are baked into chunk
                    // meshes. Rebuild on the next normal client tick, after the
                    // white matte callback has unwound and the front camera is
                    // active, then give the async rebuild a settling window.
                    passRebuildPending = true;
                    return;
                }
                beginMaterialCapture(client);
                return;
            }
        }

        void beginMaterialCapture(Minecraft client) {
            materialStarted = System.nanoTime();
            System.out.printf("[STEP 8] render material icons%n  - materials: %d%n", materials.size());
            setPaperFullbright(false);
            int iconTarget=captureBaseResolution();
            captureWidth=iconTarget;
            captureHeight=iconTarget;
            client.getWindow().setWindowed(iconTarget,iconTarget);
            client.gameRenderer.resize(iconTarget,iconTarget);
            materialCapture=true;
            materialCapturePhase=0;
            materialWait=0;
            client.setScreenAndShow(new MaterialIconScreen(materials,false));
        }

        void captureMaterialsTick(Minecraft client) {
            if (screenshotPending || materialFrameReady || ++materialWait < 5) return;
            materialFrameReady=true;
        }

        void captureMaterialFrame(Minecraft client) {
            materialFrameReady=false;
            screenshotPending=true;
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                try {
                    if (materialCapturePhase == 0) {
                        materialBlackPass=image;
                        materialCapturePhase=1;
                        materialWait=0;
                        client.setScreenAndShow(new MaterialIconScreen(materials,true));
                        image=null;
                    } else {
                        BufferedImage rawGuiCapture=nativeToBuffered(materialBlackPass);
                        ImageIO.write(rawGuiCapture,"PNG",out.resolve("materials_icons_gui_raw.png").toFile());
                        rawGuiCapture.flush();
                        BufferedImage iconSheet=materialIconSheet(materialBlackPass,image);
                        extractMaterialIcons(iconSheet,client.getWindow().getGuiScaledWidth(),
                                client.getWindow().getGuiScaledHeight());
                        writeRawIconStrip();
                        System.out.printf("[STEP 8] render material icons complete%n  - icons captured: %d%n  - elapsed: %d ms%n", materials.size(),
                                (System.nanoTime() - materialStarted) / 1_000_000);
                        iconSheet.flush();
                        materialBlackPass.close();
                        materialBlackPass=null;
                        client.setScreenAndShow(null);
                        assembleComposites();
                        progress.emit(93,"MATERIALS",null,"Material section assembled");
                        progress.emit(96,"WORKBOOK",null,"Writing material workbook");
                        Path workbook = MaterialWorkbookWriter.write(out, input.getFileName().toString(),
                                materials.stream().map(entry -> new MaterialWorkbookWriter.Row(entry.name, entry.count)).toList());
                        System.out.println("WROTE MATERIAL WORKBOOK " + workbook.toAbsolutePath());
                        Path archive = OutputArchiveWriter.write(out, outputBaseName(), workbook);
                        System.out.println("WROTE OUTPUT ARCHIVE " + archive.toAbsolutePath());
                        progress.emit(98,"FINALIZE",null,"Finalizing output archive");
                        finish(client);
                    }
                } catch (Exception error) {
                    progress.error("MATERIAL_OR_COMPOSITE_FAILED",error.getMessage());
                    System.out.printf("[STEP ERROR] material/composite render failed%n  - message: %s%n",
                            error.getMessage());
                    error.printStackTrace();
                    client.stop();
                    job=null;
                } finally {
                    if (image != null) image.close();
                    screenshotPending=false;
                }
            });
        }

        void extractMaterialIcons(BufferedImage sheet, int guiWidth, int guiHeight) {
            double scaleX=sheet.getWidth()/(double)guiWidth;
            double scaleY=sheet.getHeight()/(double)guiHeight;
            int columns=MaterialIconScreen.columns(guiWidth);
            for (int i=0;i<materials.size();i++) {
                int logicalX=MaterialIconScreen.iconX(i,columns);
                int logicalY=MaterialIconScreen.iconY(i,columns);
                int x=(int)Math.round(logicalX*scaleX), y=(int)Math.round(logicalY*scaleY);
                int w=Math.max(1,(int)Math.round(16*scaleX)), h=Math.max(1,(int)Math.round(16*scaleY));
                BufferedImage icon=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
                for (int py=0;py<h;py++) for (int px=0;px<w;px++)
                    icon.setRGB(px,py,sheet.getRGB(Math.min(sheet.getWidth()-1,x+px),Math.min(sheet.getHeight()-1,y+py)));
                materials.get(i).icon=icon;
            }
        }

        /** Recover unmodified GUI ItemStack RGB from black/white renders into a true ARGB sheet. */
        private static BufferedImage materialIconSheet(NativeImage black,NativeImage white) {
            int width=black.getWidth(),height=black.getHeight();
            BufferedImage result=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
                int bc=black.getPixel(x,y),wc=white.getPixel(x,y);
                int br=(bc>>>16)&255,bg=(bc>>>8)&255,bb=bc&255;
                int[] differences={((wc>>>16)&255)-br,((wc>>>8)&255)-bg,(wc&255)-bb};
                java.util.Arrays.sort(differences);
                int alpha=255-Math.max(0,Math.min(255,differences[1]));
                int red=unpremultiply(br,alpha),green=unpremultiply(bg,alpha),blue=unpremultiply(bb,alpha);
                result.setRGB(x,y,(alpha<<24)|(red<<16)|(green<<8)|blue);
            }
            return result;
        }

        private static int unpremultiply(int channel,int alpha) {
            return alpha==0?0:Math.min(255,(channel*255+alpha/2)/alpha);
        }

        private void writeRawIconStrip() throws Exception {
            int count=Math.min(materials.size(),16),size=48;
            BufferedImage strip=new BufferedImage(Math.max(1,count)*size,size,BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics=strip.createGraphics();
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (int i=0;i<count;i++) if (materials.get(i).icon!=null)
                graphics.drawImage(materials.get(i).icon,i*size,0,size,size,null);
            graphics.dispose();
            Path file=out.resolve("materials_icon_strip_original.png");
            ImageIO.write(strip,"PNG",file.toFile());
            strip.flush();
            System.out.println("WROTE MATERIAL ICON STRIP "+file+" (original ItemStack GUI color)");
        }

        void finish(Minecraft client) {
                closeCapturedViews();
                clearNightVision(client);
                setPaperFullbright(false);
                client.options.gamma().set(previousGamma);
                setOverworldClock(client, previousDayTime, 1.0f);
                System.out.printf("[STEP 9] done%n  - output: %s%n  - total elapsed: %d ms%n",
                        out.toAbsolutePath(), (System.nanoTime() - jobStarted) / 1_000_000);
                progress.emit(100,"DONE",null,"Render complete");
                System.out.println("LITEMATIC_RENDER_DONE " + out);
                client.stop();
                job = null;
                activeView = null;
                worldStartRequested = false;
        }

        void clearNightVision(Minecraft client) {
            if (!nightVisionApplied || client.player == null) return;
            client.player.removeEffect(MobEffects.NIGHT_VISION);
            if (previousNightVision != null) client.player.addEffect(previousNightVision);
            previousNightVision = null;
            nightVisionApplied = false;
        }

        private void closeCapturedViews() {
            for (int i = 0; i < composites.length; i++) {
                if (composites[i] != null) {
                    composites[i].close();
                    composites[i] = null;
                }
                if (blueprintViews[i] != null) {
                    blueprintViews[i].flush();
                    blueprintViews[i] = null;
                }
                if (colorViews[i] != null) {
                    colorViews[i].flush();
                    colorViews[i] = null;
                }
            }
            for (MaterialEntry material : materials) {
                if (material.icon != null) {
                    material.icon.flush();
                    material.icon=null;
                }
            }
        }

        private final BufferedImage[] blueprintViews = new BufferedImage[View.values().length];
        private final BufferedImage[] colorViews = new BufferedImage[View.values().length];

        private NativeImage blackPassBackup;

        NativeImage matte(NativeImage black, NativeImage white) {
            int width=black.getWidth(), height=black.getHeight();
            NativeImage result=new NativeImage(NativeImage.Format.RGBA,width,height,false);
            for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
                int bc=black.getPixel(x,y), wc=white.getPixel(x,y);
                int br=(bc>>>16)&255, bg=(bc>>>8)&255, bb=bc&255;
                int wr=(wc>>>16)&255, wg=(wc>>>8)&255, wb=wc&255;
                int transparency=Math.max(0,Math.min(255,Math.max(wr-br,Math.max(wg-bg,wb-bb))));
                int alpha=255-transparency;
                int red=alpha==0?0:Math.min(255,(br*255+alpha/2)/alpha);
                int green=alpha==0?0:Math.min(255,(bg*255+alpha/2)/alpha);
                int blue=alpha==0?0:Math.min(255,(bb*255+alpha/2)/alpha);
                // NativeImage.setColor expects little-endian RGBA: alpha at
                // bits 24..31, red at 16..23, green at 8..15, blue at 0..7.
                // Earlier code had blue and red swapped, producing cyan-tinted
                // pixels where the alpha matting passed values through wrong
                // channels.
                result.setPixel(x,y,(alpha<<24)|(red<<16)|(green<<8)|blue);
            }
            return result;
        }

        /** Build the two composites and write them out. */
        void assembleComposites() throws Exception {
            long started = System.nanoTime();
            progress.emit(76,"BUILD_PRINCIPAL_LAYOUT",null,"Freezing principal layout");
            System.out.printf("[STEP 7] build composites%n  - title: %s%n  - output directory: %s%n",
                    sheetTitle(), out.toAbsolutePath());
            buildSharedEngineeringSheetLayout();
            if (style.writesPaper()) {
                progress.emit(86,"ASSEMBLE_PAPER",null,"Assembling paper engineering sheet");
                assembleStyle(Style.PAPER, "_paper");
            }
            if (style.writesBlueprint()) {
                progress.emit(90,"ASSEMBLE_BLUEPRINT",null,"Assembling blueprint engineering sheet");
                assembleStyle(Style.BLUEPRINT, "");
            }
            writeMaterialsOnly();
            System.out.printf("  - outputs: %s%n  - elapsed: %d ms%n",
                    compositeOutputPaths(), (System.nanoTime() - started) / 1_000_000);
        }

        private String compositeOutputPaths() {
            List<String> paths = new ArrayList<>();
            if (style.writesBlueprint()) addCompositeOutputPaths(paths, "");
            if (style.writesPaper()) addCompositeOutputPaths(paths, "_paper");
            return String.join(", ", paths);
        }

        private void addCompositeOutputPaths(List<String> paths, String suffix) {
            paths.add(out.resolve("mcoo_3view" + suffix + ".png").toAbsolutePath().toString());
            paths.add(out.resolve("mcoo_4angle" + suffix + ".png").toAbsolutePath().toString());
            paths.add(out.resolve(outputBaseName() + "_overview" + suffix + ".png").toAbsolutePath().toString());
            paths.add(out.resolve(outputBaseName() + "_overview" + suffix + "_no_materials.png").toAbsolutePath().toString());
        }

        private void buildSharedEngineeringSheetLayout() {
            if (engineeringSheetLayout!=null) return;
            int cell=Math.max(BASE_PRINCIPAL_CELL,captureBaseResolution()/2);
            double sheetScale=cell/(double)BASE_PRINCIPAL_CELL;
            int gapX=(int)Math.round(positiveIntProperty("litematic.sheet.contentGutterX",CONTENT_GUTTER_X)*sheetScale);
            int gapY=(int)Math.round(positiveIntProperty("litematic.sheet.contentGutterY",CONTENT_GUTTER_Y)*sheetScale);
            int margin=(int)Math.round(56*sheetScale),titleH=(int)Math.round(82*sheetScale);
            // V146: UI metrics 一次计算,所有下游共用。
            SheetUiMetrics ui=sheetUiMetricsForScale(sheetScale,materials.size());
            engineeringSheetUiMetrics=ui;
            double sizeX=maxX-minX,sizeY=maxY-minY,sizeZ=maxZ-minZ;
            double principalScale=cell/Math.max(1.0,Math.max(sizeX,Math.max(sizeY,sizeZ)));
            System.out.printf(Locale.ROOT,
                    "PRINCIPAL_SCALE sizeX=%.4f sizeY=%.4f sizeZ=%.4f pixelsPerBlock=%.6f%n",
                    sizeX,sizeY,sizeZ,principalScale);
            Map<View,java.awt.Dimension> sizes=new java.util.EnumMap<>(View.class);
            for (View view:View.values()) {
                if (isPrincipalView(view)) {
                    BufferedImage source=style.writesPaper()?colorViews[view.ordinal()]:blueprintViews[view.ordinal()];
                    PrincipalProjectionFrame frame=principalProjectionFrame(view,source,principalScale);
                    sharedPrincipalFrames[view.ordinal()]=frame;
                    sizes.put(view,new java.awt.Dimension(frame.sheetWidth(),frame.sheetHeight()));
                } else {
                    // V146 §10: Axon 不预 fit 到 cell,直接 raw content box。
                    int rawW=1,rawH=1;
                    if (style.writesBlueprint()) {
                        ContentBox box=contentBox(blueprintViews[view.ordinal()]);
                        rawW=Math.max(rawW,box.width());
                        rawH=Math.max(rawH,box.height());
                    }
                    if (style.writesPaper()) {
                        ContentBox box=contentBox(colorViews[view.ordinal()]);
                        rawW=Math.max(rawW,box.width());
                        rawH=Math.max(rawH,box.height());
                    }
                    sizes.put(view,new java.awt.Dimension(rawW,rawH));
                }
            }
            // V146 §11: 只调用 builder 一次。
            progress.emit(78,"BUILD_ENGINEERING_LAYOUT",null,"Freezing 10-view engineering layout");
            engineeringSheetLayout=OffscreenRenderer.buildEngineeringSheetLayout(sizes,margin,titleH,
                    gapX,gapY,ui.scaleBarHeight(),ui.materialsHeight());
            progress.emit(80,"SCALE_AXON_VIEWS",null,"Computing uniform axon scale");
            logPrincipalViewSizes(sizeX,sizeY,sizeZ,principalScale);
            logEngineeringSheetLayout(engineeringSheetLayout);
            validateEngineeringSheetLayout(engineeringSheetLayout);
            progress.emit(82,"VALIDATE_ENGINEERING_LAYOUT",null,"Validating engineering layout");
            progress.emit(83,"AXON_COLLISION_CHECK",null,"Validating view collisions");
        }

        private void assembleStyle(Style outputStyle, String suffix) throws Exception {
            compositeEngineeringSheet("mcoo_3view" + suffix + ".png", outputStyle, true);
            compositeStrip(new View[]{View.AXON_X_POS_Z_NEG, View.AXON_X_POS_Z_POS,
                            View.AXON_X_NEG_Z_POS, View.AXON_X_NEG_Z_NEG},
                    "mcoo_4angle" + suffix + ".png", outputStyle);
            compositeOverview(suffix);
            compositeEngineeringSheet(outputBaseName() + "_overview" + suffix + "_no_materials.png", outputStyle, false);
        }

        private void compositeOverview(String suffix) throws Exception {
            // The overview is intentionally the same complete engineering sheet
            // as the legacy 3view name. Reuse the already encoded result instead
            // of running ten native pencil passes a second time.
            Path destination = out.resolve(outputBaseName() + "_overview" + suffix + ".png");
            Files.copy(out.resolve("mcoo_3view" + suffix + ".png"), destination,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("WROTE COMPOSITE " + destination + " (reused engineering sheet)");
        }

        /**
         * Owner-specified principal row: RIGHT, FRONT, LEFT, BACK; TOP is above
         * FRONT and BOTTOM below it.  This deliberately follows the requested
         * layout rather than claiming first- or third-angle conformance.  The
         * four axonometric observation quadrants occupy the sheet corners.
         */
        private void compositeEngineeringSheet(String outName, Style outputStyle, boolean includeMaterials) throws Exception {
            int cell = Math.max(540, captureBaseResolution() / 2);
            double scale = cell / 540.0;
            int margin = (int)Math.round(56 * scale);
            EngineeringSheetLayout layout=engineeringSheetLayout;
            SheetUiMetrics ui=engineeringSheetUiMetrics;
            if (ui==null) throw new IllegalStateException("Engineering sheet UI metrics not initialized");
            int scaleBarH=ui.scaleBarHeight();
            // V147 §20-22: 先用 view layout 的 canvasHeight 当 base,再用 buildMaterialsLayout 重新算 materialsHeight 并修正 canvasHeight。
            // V147 §18: materialsHeight 必须来自 MaterialsLayout.panelHeight(),不再是固定 4 列估算。
            MaterialsLayout ml=null;
            int materialsPanelWidth=0;
            int materialsH=ui.materialsHeight();
            int finalCanvasHeight=layout.canvasHeight();
            if (includeMaterials) {
                java.awt.Font font=new java.awt.Font(java.awt.Font.SANS_SERIF,java.awt.Font.PLAIN,ui.materialsBodyFont());
                BufferedImage probe=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D probeGraphics=probe.createGraphics();
                probeGraphics.setFont(font);
                java.awt.FontMetrics metrics=probeGraphics.getFontMetrics();
                int widestName=1;
                int widestQuantity=1;
                for (MaterialEntry entry:materials) {
                    widestName=Math.max(widestName,metrics.stringWidth(entry.name));
                    widestQuantity=Math.max(widestQuantity,
                            metrics.stringWidth(Long.toString(entry.count)));
                }
                int minimumColumnWidth=ui.iconSize()+scaled(10,ui.scale())+scaled(12,ui.scale())
                        +widestName+widestQuantity+scaled(24,ui.scale());
                // Keep a compact, balanced panel: roughly two rows per column, capped at four.
                int probeColumns=Math.min(4,Math.max(1,
                        (int)Math.ceil(Math.sqrt(materials.size()/2.0))));
                int probePanelWidth=2*ui.panelPadding()+probeColumns*minimumColumnWidth
                        +(probeColumns-1)*ui.columnGap();
                ml=buildMaterialsLayout(materials,probePanelWidth,ui,metrics,probeColumns);
                materialsPanelWidth=layout.canvasWidth()-2*margin;
                ml=buildMaterialsLayout(materials,materialsPanelWidth,ui,metrics,probeColumns);
                probeGraphics.dispose();
                probe.flush();
                materialsH=ml.panelHeight();
                finalCanvasHeight=layout.drawingsBottom()+scaleBarH+materialsH+margin;
            } else {
                finalCanvasHeight=layout.drawingsBottom()+scaleBarH+margin;
            }
            // V147 §48: LOGGER 打印 SHEET_UI_SCALE。
            System.out.printf(Locale.ROOT,
                    "SHEET_UI_SCALE source=PRINCIPAL_CELL principalCell=%d basePrincipalCell=%d scale=%.4f canvas=%dx%d%n",
                    cell,BASE_PRINCIPAL_CELL,ui.scale(),layout.canvasWidth(),finalCanvasHeight);
            if (ml!=null)
                System.out.printf(Locale.ROOT,
                        "MATERIALS_LAYOUT fontSize=%d iconSize=%d rowHeight=%d columnGap=%d panelHeight=%d columns=%d%n",
                        ml.fontSize(),ml.iconSize(),ml.rowHeight(),ml.columnGap(),ml.panelHeight(),ml.columns());
            PrincipalProjectionFrame[][] principalFrames=new PrincipalProjectionFrame[3][4];
            double sizeX=maxX-minX,sizeY=maxY-minY,sizeZ=maxZ-minZ;
            double maxPrincipalSpan=Math.max(sizeX,Math.max(sizeY,sizeZ));
            double principalScale=cell/Math.max(1.0,maxPrincipalSpan);
            int totalW=layout.canvasWidth();
            // V147: totalH 跟 ml.panelHeight() 同步。
            int totalH=includeMaterials?finalCanvasHeight:layout.drawingsBottom()+scaleBarH+margin;
            BufferedImage canvas = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = canvas.createGraphics();
            java.awt.Color sheetBackground = sheetBackground(outputStyle);
            g.setColor(sheetBackground);
            g.fillRect(0, 0, totalW, totalH);
            drawEngineeringGrid(g, totalW, totalH, outputStyle);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

            int principalIndex=0;
            for (View view:View.values()) {
                ViewPlacement placement=layout.placement(view);
                if (isPrincipalView(view)) {
                    PrincipalProjectionFrame frame=sharedPrincipalFrames[view.ordinal()]
                            .withSheetPlacement(placement);
                    principalFrames[principalIndex/4][principalIndex%4]=frame;
                    principalIndex++;
                    drawPrincipalFrame(g,frame,outputStyle);
                } else {
                    ContentBox sourceBox=contentBox(viewsFor(outputStyle)[view.ordinal()]);
                    drawViewContent(g,view,sourceBox.withDrawSize(placement.width(),placement.height()),
                            placement.x(),placement.y(),placement.width(),placement.height(),outputStyle);
                }
            }
            validatePrincipalAlignment(principalFrames,outputStyle);
            if (outputStyle == Style.PAPER && includeMaterials)
                writePaperSheetCrops(canvas,principalFrames);

            java.awt.Color primary=outputStyle == Style.BLUEPRINT ? java.awt.Color.WHITE : java.awt.Color.BLACK;
            java.awt.Color secondary=outputStyle == Style.BLUEPRINT ? new java.awt.Color(232,238,244) : new java.awt.Color(101,94,84);
            g.setColor(primary);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, ui.titleFont()));
            g.drawString(sheetTitle(), margin, margin + ui.titleFont()+scaled(4,ui.scale()));
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, ui.subtitleFont()));
            g.setColor(secondary);
            g.drawString("Orthographic and axonometric views · common principal-view scale", margin,
                    margin+ui.titleFont()+ui.subtitleFont()+scaled(11,ui.scale()));

            // A scale bar remains meaningful if the PNG is resized or printed.
            double maxSpan = Math.max(sizeX, Math.max(sizeY, sizeZ));
            int blocks = niceScaleBar(maxSpan);
            int barPixels = (int)Math.round(blocks * principalScale);
            int drawingsBottom=layout.drawingsBottom();
            int barY = drawingsBottom + scaled(45,ui.scale());
            int barX = margin;
            g.setColor(primary);
            g.setStroke(new java.awt.BasicStroke(ui.scaleBarStroke()));
            g.drawLine(barX, barY, barX + barPixels, barY);
            g.drawLine(barX, barY-scaled(8,ui.scale()), barX, barY+scaled(8,ui.scale()));
            g.drawLine(barX+barPixels,barY-scaled(8,ui.scale()),barX+barPixels,barY+scaled(8,ui.scale()));
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN,ui.scaleBarFont()));
            g.drawString(blocks+" blocks",barX,barY-scaled(14,ui.scale()));

            int materialsY=drawingsBottom+scaleBarH;
            if (includeMaterials) {
                // V147: 传 ml 给 drawMaterials。
                int materialsX=margin;
                drawMaterials(g,materialsX,materialsY,materialsPanelWidth,materialsH,ml,ui,outputStyle,primary);
            }

            g.setStroke(new java.awt.BasicStroke(ui.outerBorderStroke()));
            g.setColor(outputStyle == Style.BLUEPRINT ? java.awt.Color.WHITE : new java.awt.Color(78,72,64));
            g.drawRect(18, 18, totalW - 37, totalH - 37);
            g.dispose();
            Path file = out.resolve(outName);
            ImageIO.write(canvas, "PNG", file.toFile());
            System.out.println("WROTE COMPOSITE " + file + " (" + totalW + "x" + totalH + ")");
            if (includeMaterials) {
                int materialsX=margin;
                writeMaterialsDetail(canvas,materialsX,materialsY,materialsPanelWidth,materialsH,outputStyle);
            }
        }

        private void logPrincipalViewSizes(double sizeX,double sizeY,double sizeZ,double pixelsPerBlock) {
            for (View view : View.values()) if (isPrincipalView(view))
                logPrincipalViewSize(view,principalProjectedWidths[view.ordinal()],
                        principalProjectedHeights[view.ordinal()],pixelsPerBlock);
        }

        private record PrincipalProjectionFrame(View view,double worldHorizontal,double worldVertical,
                                                int captureX,int captureY,int captureWidth,int captureHeight,
                                                int sheetX,int sheetY,int sheetWidth,int sheetHeight,
                                                double pixelsPerBlock) {
            PrincipalProjectionFrame withSheetPlacement(ViewPlacement placement) {
                return new PrincipalProjectionFrame(view,worldHorizontal,worldVertical,
                        captureX,captureY,captureWidth,captureHeight,placement.x(),placement.y(),
                        placement.width(),placement.height(),pixelsPerBlock);
            }
        }

        private PrincipalProjectionFrame principalProjectionFrame(View view,BufferedImage source,
                double principalScale) {
            double worldWidth=principalProjectedWidths[view.ordinal()];
            double worldHeight=principalProjectedHeights[view.ordinal()];
            ProjectedSpan basisSpan=projectedSpan(minX,minY,minZ,maxX,maxY,maxZ,view.yaw,view.pitch);
            boolean cameraFrameMatches=Math.abs(worldWidth-basisSpan.width())<1.0e-6
                    && Math.abs(worldHeight-basisSpan.height())<1.0e-6;
            System.out.printf(Locale.ROOT,
                    "PRINCIPAL_CAMERA_FRAME_CHECK view=%s cameraProjected=%.4fx%.4f "
                            + "frameWorld=%.4fx%.4f result=%s%n",
                    view,basisSpan.width(),basisSpan.height(),worldWidth,worldHeight,
                    cameraFrameMatches?"PASS":"FAIL");
            if (!cameraFrameMatches)
                throw new IllegalStateException("Principal camera/frame mismatch for "+view);
            float halfSize=principalHalfSizes[view.ordinal()];
            int capturedWidth=principalCaptureWidths[view.ordinal()];
            int capturedHeight=principalCaptureHeights[view.ordinal()];
            if (halfSize<=0 || capturedWidth!=source.getWidth() || capturedHeight!=source.getHeight())
                throw new IllegalStateException("Missing principal capture geometry for "+view
                        +": recorded="+capturedWidth+"x"+capturedHeight+" source="
                        +source.getWidth()+"x"+source.getHeight()+" halfSize="+halfSize);
            double capturePixelsPerBlock=source.getHeight()/(2.0*halfSize);
            int cropWidth=Math.max(1,Math.min(source.getWidth(),
                    (int)Math.round(worldWidth*capturePixelsPerBlock)));
            int cropHeight=Math.max(1,Math.min(source.getHeight(),
                    (int)Math.round(worldHeight*capturePixelsPerBlock)));
            int cropX=Math.max(0,Math.min(source.getWidth()-cropWidth,
                    (int)Math.round(source.getWidth()/2.0-cropWidth/2.0)));
            int cropY=Math.max(0,Math.min(source.getHeight()-cropHeight,
                    (int)Math.round(source.getHeight()/2.0-cropHeight/2.0)));
            int drawWidth=Math.max(1,(int)Math.round(worldWidth*principalScale));
            int drawHeight=Math.max(1,(int)Math.round(worldHeight*principalScale));
            return new PrincipalProjectionFrame(view,worldWidth,worldHeight,cropX,cropY,cropWidth,
                    cropHeight,0,0,drawWidth,drawHeight,principalScale);
        }

        private void drawPrincipalFrame(java.awt.Graphics2D g,PrincipalProjectionFrame frame,Style style) {
            BufferedImage source=viewsFor(style)[frame.view().ordinal()];
            g.drawImage(source,frame.sheetX(),frame.sheetY(),
                    frame.sheetX()+frame.sheetWidth(),frame.sheetY()+frame.sheetHeight(),
                    frame.captureX(),frame.captureY(),frame.captureX()+frame.captureWidth(),
                    frame.captureY()+frame.captureHeight(),null);
            System.out.printf(Locale.ROOT,
                    "PRINCIPAL_FRAME style=%s view=%s worldHorizontal=%.4f worldVertical=%.4f "
                            +"capture=(%d,%d %dx%d) sheetX=%d sheetY=%d sheetWidth=%d "
                            +"sheetHeight=%d pixelsPerBlock=%.6f%n",
                    style,frame.view(),frame.worldHorizontal(),frame.worldVertical(),frame.captureX(),
                    frame.captureY(),frame.captureWidth(),frame.captureHeight(),frame.sheetX(),
                    frame.sheetY(),frame.sheetWidth(),frame.sheetHeight(),frame.pixelsPerBlock());
            int[] rect={frame.sheetX(),frame.sheetY(),frame.sheetWidth(),frame.sheetHeight()};
            Map<View,int[]> own=style==Style.PAPER?paperPrincipalRects:blueprintPrincipalRects;
            Map<View,int[]> other=style==Style.PAPER?blueprintPrincipalRects:paperPrincipalRects;
            own.put(frame.view(),rect);
            int[] prior=other.get(frame.view());
            int deltaMax=0;
            if (prior!=null) for (int index=0;index<rect.length;index++)
                deltaMax=Math.max(deltaMax,Math.abs(prior[index]-rect[index]));
            String result=prior==null||deltaMax==0?"PASS":deltaMax<=1?"PASS_WITH_ROUNDING_WARN":"FAIL";
            System.out.printf("STYLE_FRAME_PARITY view=%s paperRect=%s blueprintRect=%s deltaMax=%d result=%s%n",
                    frame.view(),java.util.Arrays.toString(style==Style.PAPER?rect:prior),
                    java.util.Arrays.toString(style==Style.BLUEPRINT?rect:prior),deltaMax,result);
            if (deltaMax>1) throw new IllegalStateException("Paper/Blueprint principal frame mismatch: "+frame.view());
        }

        private static PrincipalProjectionFrame findFrame(PrincipalProjectionFrame[][] frames,View view) {
            for (PrincipalProjectionFrame[] row:frames) for (PrincipalProjectionFrame frame:row)
                if (frame!=null && frame.view()==view) return frame;
            throw new IllegalStateException("Missing principal frame "+view);
        }

        private static void validatePrincipalAlignment(PrincipalProjectionFrame[][] frames,Style style) {
            PrincipalProjectionFrame left=findFrame(frames,View.LEFT_Z_NEG);
            PrincipalProjectionFrame front=findFrame(frames,View.FRONT_X_POS);
            PrincipalProjectionFrame right=findFrame(frames,View.RIGHT_Z_POS);
            PrincipalProjectionFrame back=findFrame(frames,View.BACK_X_NEG);
            PrincipalProjectionFrame top=findFrame(frames,View.TOP_X_UP);
            PrincipalProjectionFrame bottom=findFrame(frames,View.BOTTOM_X_UP);
            boolean height=left.sheetY()==front.sheetY() && front.sheetY()==right.sheetY()
                    && right.sheetY()==back.sheetY()
                    && withinPixel(left.sheetHeight(),front.sheetHeight())
                    && withinPixel(front.sheetHeight(),right.sheetHeight())
                    && withinPixel(right.sheetHeight(),back.sheetHeight());
            boolean zSpan=withinPixel(front.sheetWidth(),back.sheetWidth())
                    && withinPixel(front.sheetWidth(),top.sheetWidth())
                    && withinPixel(front.sheetWidth(),bottom.sheetWidth());
            boolean xSpan=withinPixel(left.sheetWidth(),right.sheetWidth())
                    && withinPixel(left.sheetWidth(),top.sheetHeight())
                    && withinPixel(left.sheetWidth(),bottom.sheetHeight());
            System.out.printf("PRINCIPAL_ALIGNMENT_CHECK style=%s yHeight=%s zSpan=%s xSpan=%s result=%s%n",
                    style,height?"PASS":"FAIL",zSpan?"PASS":"FAIL",xSpan?"PASS":"FAIL",
                    height&&xSpan&&zSpan?"PASS":"FAIL");
            if (!height) System.out.println("PRINCIPAL_ALIGNMENT_FAIL type=Y_HEIGHT_ALIGNMENT");
            if (!zSpan) System.out.println("PRINCIPAL_ALIGNMENT_FAIL type=Z_SPAN");
            if (!xSpan) System.out.println("PRINCIPAL_ALIGNMENT_FAIL type=X_SPAN");
            if (!(height&&xSpan&&zSpan)) throw new IllegalStateException("Principal engineering alignment failed");
        }

        // V146 §46-48: 重写 logger — 不再用 slot 字段,改用 union 10 placements + AXON_LAYOUT 格式。
        private static void logEngineeringSheetLayout(EngineeringSheetLayout layout) {
            System.out.printf("DRAWING_AREA top=%d bottom=%d centerY=%d%n",layout.drawingTop(),
                    layout.drawingBottom(),layout.drawingCenterY());
            System.out.printf("PRINCIPAL_GROUP top=%d bottom=%d height=%d centerY=%d mainRowY=%d%n",
                    layout.principalGroupTop(),layout.principalGroupBottom(),
                    layout.principalGroupBottom()-layout.principalGroupTop(),
                    (layout.principalGroupTop()+layout.principalGroupBottom())/2,
                    layout.principalMainRowY());
            System.out.printf(Locale.ROOT,"PRINCIPAL_LAYOUT gapX=%d gapY=%d scale=common%n",
                    layout.principalGapX(),layout.principalGapY());
            System.out.printf(Locale.ROOT,
                    "AXON_UNIFORM_SCALE aspectMode=%s principalCell=%d globalScale=%.6f%n",
                    layout.axonAspectMode(),layout.principalGapX()>0?Math.max(1,Math.max(layout.drawingArea().width(),layout.drawingArea().height())):540,
                    layout.globalAxonScale());
            System.out.printf(Locale.ROOT,
                    "FINAL_LAYOUT_BOUNDS drawing=[%d,%d,%d,%d] principal=[%d,%d,%d,%d] canvas=%dx%d margin=%d%n",
                    layout.drawingArea().x(),layout.drawingArea().y(),
                    layout.drawingArea().width(),layout.drawingArea().height(),
                    layout.principalReservedRect().x(),layout.principalReservedRect().y(),
                    layout.principalReservedRect().width(),layout.principalReservedRect().height(),
                    layout.canvasWidth(),layout.canvasHeight(),
                    layout.drawingArea().x());
            for (View view:View.values()) if (isPrincipalView(view)) {
                ViewPlacement p=layout.placement(view);
                System.out.printf("PRINCIPAL_PLACEMENT view=%s x=%d y=%d width=%d height=%d%n",
                        view,p.x(),p.y(),p.width(),p.height());
            }
            System.out.println("AXON_LAYOUT_BEGIN");
            for (View av:View.values()) if (!isPrincipalView(av)) {
                ViewPlacement p=layout.placement(av);
                java.awt.Dimension source=layout.sourceSizes().get(av);
                String anchorColumn=av==View.AXON_X_POS_Z_POS||av==View.AXON_X_POS_Z_NEG
                        ?"displayRight":"back";
                String anchorRow=av==View.AXON_X_POS_Z_POS||av==View.AXON_X_NEG_Z_POS
                        ?"TOP":"BOTTOM";
                System.out.printf(Locale.ROOT,
                        "AXON_LAYOUT view=%s raw=%dx%d final=%dx%d globalScale=%.6f center=%d,%d anchorColumn=%s anchorRow=%s%n",
                        av,source.width,source.height,p.width(),p.height(),
                        layout.globalAxonScale(),p.centerX(),p.centerY(),anchorColumn,anchorRow);
            }
            System.out.println("AXON_LAYOUT_DONE");
        }

        // V146 §49-58: 新 Validator — 10 条检查。
        private static void validateEngineeringSheetLayout(EngineeringSheetLayout layout) {
            // §49: 必须正好 10 placements。
            if (layout.placements().size()!=10)
                throw new IllegalStateException("Expected 10 engineering placements, got "+layout.placements().size());
            ViewPlacement displayRight=layout.placement(View.LEFT_Z_NEG);
            ViewPlacement front=layout.placement(View.FRONT_X_POS);
            ViewPlacement displayLeft=layout.placement(View.RIGHT_Z_POS);
            ViewPlacement back=layout.placement(View.BACK_X_NEG);
            ViewPlacement top=layout.placement(View.TOP_X_UP),bottom=layout.placement(View.BOTTOM_X_UP);
            // §50: Main row 严格共顶/共底。
            int topDelta=Math.max(Math.max(displayRight.y(),front.y()),
                    Math.max(displayLeft.y(),back.y()))-Math.min(Math.min(displayRight.y(),front.y()),
                    Math.min(displayLeft.y(),back.y()));
            int bottomDelta=Math.max(Math.max(displayRight.bottom(),front.bottom()),
                    Math.max(displayLeft.bottom(),back.bottom()))-Math.min(Math.min(displayRight.bottom(),front.bottom()),
                    Math.min(displayLeft.bottom(),back.bottom()));
            System.out.printf("PRINCIPAL_MAIN_ROW_ALIGNMENT topDelta=%d bottomDelta=%d result=%s%n",
                    topDelta,bottomDelta,topDelta==0&&bottomDelta==0?"PASS":"FAIL");
            // §51: TOP/BOTTOM 与 FRONT 中心 X ±1px。
            int centerMax=Math.max(Math.abs(top.centerX()-front.centerX()),
                    Math.abs(bottom.centerX()-front.centerX()));
            System.out.printf("PRINCIPAL_X_CENTER_ALIGNMENT top=%d front=%d bottom=%d deltaMax=%d result=%s%n",
                    top.centerX(),front.centerX(),bottom.centerX(),centerMax,centerMax<=1?"PASS":"FAIL");
            // §52: Principal gap 正确 (TOP.bottom + gapY == FRONT.top 等)。
            boolean topGap=Math.abs(top.bottom()+layout.principalGapY()-front.y())<=1;
            boolean bottomGap=Math.abs(front.bottom()+layout.principalGapY()-bottom.y())<=1;
            System.out.printf("PRINCIPAL_GAP_CHECK topGap=%s bottomGap=%s result=%s%n",
                    topGap,bottomGap,topGap&&bottomGap?"PASS":"FAIL");
            // §53: 四个 Axon anchor 不再依赖 CornerSlot。
            ViewPlacement tl=layout.placement(View.AXON_X_POS_Z_POS);
            ViewPlacement tr=layout.placement(View.AXON_X_NEG_Z_POS);
            ViewPlacement bl=layout.placement(View.AXON_X_POS_Z_NEG);
            ViewPlacement br=layout.placement(View.AXON_X_NEG_Z_NEG);
            boolean tlAnchor=Math.abs(tl.centerX()-displayRight.centerX())<=1 && Math.abs(tl.centerY()-top.centerY())<=1;
            boolean trAnchor=Math.abs(tr.centerX()-back.centerX())<=1 && Math.abs(tr.centerY()-top.centerY())<=1;
            boolean blAnchor=Math.abs(bl.centerX()-displayRight.centerX())<=1 && Math.abs(bl.centerY()-bottom.centerY())<=1;
            boolean brAnchor=Math.abs(br.centerX()-back.centerX())<=1 && Math.abs(br.centerY()-bottom.centerY())<=1;
            System.out.printf("AXON_ANCHOR_CHECK TL=%s TR=%s BL=%s BR=%s result=%s%n",
                    tlAnchor,trAnchor,blAnchor,brAnchor,
                    tlAnchor&&trAnchor&&blAnchor&&brAnchor?"PASS":"FAIL");
            // §54: 统一 Axon scale (rounding tolerance 0.5%)。
            double minScale=Double.POSITIVE_INFINITY,maxScale=0.0;
            View[] axons={View.AXON_X_POS_Z_POS,View.AXON_X_NEG_Z_POS,View.AXON_X_POS_Z_NEG,View.AXON_X_NEG_Z_NEG};
            for (View view:axons) {
                java.awt.Dimension raw=layout.sourceSizes().get(view);
                ViewPlacement p=layout.placement(view);
                double scale=Math.max(p.width(),p.height())/(double)Math.max(raw.width,raw.height);
                minScale=Math.min(minScale,scale);
                maxScale=Math.max(maxScale,scale);
            }
            boolean uniformScale=(maxScale-minScale)<=0.005;
            System.out.printf("AXON_UNIFORM_SCALE_CHECK minScale=%.6f maxScale=%.6f result=%s%n",
                    minScale,maxScale,uniformScale?"PASS":"FAIL");
            // §55: 所有 Axon vs Principal collision = 0。
            boolean axonVsPrincipal=true;
            for (View axon:axons) {
                ViewPlacement ap=layout.placement(axon);
                for (View principal:View.values()) if (isPrincipalView(principal)) {
                    ViewPlacement p=layout.placement(principal);
                    int width=Math.max(0,Math.min(ap.right(),p.right())-Math.max(ap.x(),p.x()));
                    int height=Math.max(0,Math.min(ap.bottom(),p.bottom())-Math.max(ap.y(),p.y()));
                    int area=width*height;
                    System.out.printf("VIEW_COLLISION_CHECK axon=%s principal=%s intersectionArea=%d result=%s%n",
                            axon,principal,area,area==0?"PASS":"FAIL");
                    axonVsPrincipal&=area==0;
                }
            }
            // §56: 四个 Axon 互相也不得碰撞。
            boolean axonVsAxon=true;
            for (int i=0;i<axons.length;i++) for (int j=i+1;j<axons.length;j++) {
                ViewPlacement first=layout.placement(axons[i]),second=layout.placement(axons[j]);
                int width=Math.max(0,Math.min(first.right(),second.right())-Math.max(first.x(),second.x()));
                int height=Math.max(0,Math.min(first.bottom(),second.bottom())-Math.max(first.y(),second.y()));
                int area=width*height;
                System.out.printf("AXON_COLLISION_CHECK first=%s second=%s intersectionArea=%d result=%s%n",
                        axons[i],axons[j],area,area==0?"PASS":"FAIL");
                axonVsAxon&=area==0;
            }
            // §57: Drawing Area 必须包含全部 10 views。
            boolean drawingContainsAll=true;
            for (View view:View.values()) {
                if (!layout.drawingArea().contains(layout.placement(view))) {
                    System.out.printf("DRAWING_AREA_CONTAINS view=%s result=FAIL%n",view);
                    drawingContainsAll=false;
                }
            }
            System.out.printf("DRAWING_AREA_CONTAINS_ALL result=%s%n",drawingContainsAll?"PASS":"FAIL");
            // §58: Drawing Area 就是真实 union(union should equal drawingArea)。
            LayoutRect union=unionPlacements(layout.placements());
            boolean unionMatches=union.x()==layout.drawingArea().x()
                    &&union.y()==layout.drawingArea().y()
                    &&union.width()==layout.drawingArea().width()
                    &&union.height()==layout.drawingArea().height();
            System.out.printf("DRAWING_AREA_UNION_MATCH result=%s%n",unionMatches?"PASS":"FAIL");
            // 汇总。
            if (!axonVsPrincipal) throw new IllegalStateException("Axonometric view overlaps principal reserved area");
            if (!axonVsAxon) throw new IllegalStateException("Axonometric views collide with each other");
            if (!drawingContainsAll) throw new IllegalStateException("DrawingArea does not contain all 10 views");
            if (!unionMatches) throw new IllegalStateException("DrawingArea != union of 10 placements");
            if (topDelta!=0||bottomDelta!=0||centerMax>1||!topGap||!bottomGap)
                throw new IllegalStateException("Principal alignment failed");
            if (!tlAnchor||!trAnchor||!blAnchor||!brAnchor)
                throw new IllegalStateException("Axon anchor check failed");
            if (!uniformScale)
                throw new IllegalStateException("Axon uniform scale check failed");
        }

        private static int principalLayoutHash(EngineeringSheetLayout layout) {
            int hash=1;
            for (View view:View.values()) if (isPrincipalView(view))
                hash=31*hash+layout.placement(view).hashCode();
            return hash;
        }

        private static boolean withinPixel(int left,int right) { return Math.abs(left-right)<=1; }

        private void writePaperDebug(BufferedImage image,View view,String stage) throws Exception {
            Path directory=out.resolve("debug_paper");
            Files.createDirectories(directory);
            ImageIO.write(image,"PNG",directory.resolve(view.name().toLowerCase(Locale.ROOT)
                    +"_"+stage+".png").toFile());
        }

        private void writePaperSheetCrops(BufferedImage sheet,PrincipalProjectionFrame[][] frames) throws Exception {
            for (PrincipalProjectionFrame[] row:frames) for (PrincipalProjectionFrame frame:row) {
                if (frame==null) continue;
                BufferedImage crop=new BufferedImage(frame.sheetWidth(),frame.sheetHeight(),BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D graphics=crop.createGraphics();
                graphics.drawImage(sheet,0,0,frame.sheetWidth(),frame.sheetHeight(),frame.sheetX(),
                        frame.sheetY(),frame.sheetX()+frame.sheetWidth(),frame.sheetY()+frame.sheetHeight(),null);
                graphics.dispose();
                writePaperDebug(crop,frame.view(),"03_sheet_crop");
                logPaperColorStats(frame.view(),"03_sheet_crop",crop);
                crop.flush();
            }
        }

        private static void logPaperColorStats(View view,String stage,BufferedImage image) {
            long count=0; double saturationSum=0;
            int[] histogram=new int[101];
            java.util.HashSet<Integer> colors=new java.util.HashSet<>();
            for (int y=0;y<image.getHeight();y++) for (int x=0;x<image.getWidth();x++) {
                int pixel=image.getRGB(x,y);
                if ((pixel>>>24)==0) continue;
                float[] hsb=java.awt.Color.RGBtoHSB((pixel>>>16)&255,(pixel>>>8)&255,pixel&255,null);
                int bucket=Math.min(100,(int)Math.floor(hsb[1]*100));
                histogram[bucket]++; saturationSum+=hsb[1]; count++; colors.add(pixel&0x00ffffff);
            }
            long threshold=(long)Math.ceil(count*0.9),seen=0; int p90=0;
            for (int index=0;index<histogram.length;index++) { seen+=histogram[index]; if (seen>=threshold) { p90=index; break; } }
            System.out.printf(Locale.ROOT,
                    "PAPER_COLOR_STATS view=%s stage=%s nonTransparentPixels=%d meanSaturation=%.6f "
                            +"p90Saturation=%.2f uniqueColorCount=%d%n",
                    view,stage,count,count==0?0:saturationSum/count,p90/100.0,colors.size());
        }

        private static void logPrincipalViewSize(View view,double worldWidth,double worldHeight,
                                                 double pixelsPerBlock) {
            int drawWidth=Math.max(1,(int)Math.round(worldWidth*pixelsPerBlock));
            int drawHeight=Math.max(1,(int)Math.round(worldHeight*pixelsPerBlock));
            System.out.printf(Locale.ROOT,
                    "PRINCIPAL_VIEW_SIZE view=%s world=%.4fx%.4f draw=%dx%d pixelsPerBlock=%.6f%n",
                    view,worldWidth,worldHeight,drawWidth,drawHeight,pixelsPerBlock);
        }

        private record ContentBox(int x,int y,int width,int height,int drawWidth,int drawHeight) {
            ContentBox withDrawSize(int width,int height) { return new ContentBox(x,y,this.width,this.height,width,height); }
        }

        private static ContentBox contentBox(BufferedImage image) {
            int minX=image.getWidth(),minY=image.getHeight(),maxX=-1,maxY=-1;
            for (int y=0;y<image.getHeight();y++) for (int x=0;x<image.getWidth();x++) {
                if ((image.getRGB(x,y)>>>24)==0) continue;
                minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
            }
            if (maxX<minX) return new ContentBox(0,0,1,1,1,1);
            return new ContentBox(minX,minY,maxX-minX+1,maxY-minY+1,maxX-minX+1,maxY-minY+1);
        }

        private void drawViewContent(java.awt.Graphics2D g,View view,ContentBox box,int x,int y,
                                     int width,int height,Style style) {
            BufferedImage source=viewsFor(style)[view.ordinal()];
            int drawX=x+(width-box.drawWidth())/2,drawY=y+(height-box.drawHeight())/2;
            g.drawImage(source,drawX,drawY,drawX+box.drawWidth(),drawY+box.drawHeight(),
                    box.x(),box.y(),box.x()+box.width(),box.y()+box.height(),null);
            System.out.printf(Locale.ROOT,
                    "CONTENT_LAYOUT view=%s bounds=(%d,%d %dx%d) draw=(%d,%d %dx%d) gutter=%dx%d compaction=%.2f%n",
                    view,box.x(),box.y(),box.width(),box.height(),drawX,drawY,box.drawWidth(),box.drawHeight(),
                    positiveIntProperty("litematic.sheet.contentGutterX",CONTENT_GUTTER_X),
                    positiveIntProperty("litematic.sheet.contentGutterY",CONTENT_GUTTER_Y),
                    unitDoubleProperty("litematic.sheet.longViewCompaction",LONG_VIEW_COMPACTION));
        }

        /** Writes a 2x nearest-neighbour proof crop for reviewing the material UI. */
        private void writeMaterialsDetail(BufferedImage sheet, int x, int y, int width, int height,
                                          Style outputStyle) throws Exception {
            BufferedImage detail=new BufferedImage(width*2,height*2,BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D detailGraphics=detail.createGraphics();
            detailGraphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            detailGraphics.drawImage(sheet,0,0,width*2,height*2,x,y,x+width,y+height,null);
            detailGraphics.dispose();
            String styleName=outputStyle == Style.PAPER ? "paper" : "blueprint";
            Path file=out.resolve("materials_detail_"+styleName+".png");
            ImageIO.write(detail,"PNG",file.toFile());
            detail.flush();
            System.out.println("WROTE MATERIAL DETAIL " + file + " (" + width*2 + "x" + height*2 + ")");
        }

        private void writeMaterialsOnly() throws Exception {
            SheetUiMetrics ui=engineeringSheetUiMetrics;
            if (ui==null) throw new IllegalStateException("Engineering sheet UI metrics not initialized");
            java.awt.Font font=new java.awt.Font(java.awt.Font.SANS_SERIF,
                    java.awt.Font.PLAIN,ui.materialsBodyFont());
            BufferedImage probe=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D probeGraphics=probe.createGraphics();
            probeGraphics.setFont(font);
            java.awt.FontMetrics metrics=probeGraphics.getFontMetrics();
            int widestName=1;
            int widestQuantity=1;
            for (MaterialEntry entry:materials) {
                widestName=Math.max(widestName,metrics.stringWidth(entry.name));
                widestQuantity=Math.max(widestQuantity,
                        metrics.stringWidth(Long.toString(entry.count)));
            }
            int minimumColumnWidth=ui.iconSize()+scaled(10,ui.scale())+scaled(12,ui.scale())
                    +widestName+widestQuantity+scaled(24,ui.scale());
            int probeColumns=Math.min(4,Math.max(1,materials.size()));
            int probePanelWidth=2*ui.panelPadding()+probeColumns*minimumColumnWidth
                    +(probeColumns-1)*ui.columnGap();
            MaterialsLayout ml=buildMaterialsLayout(materials,probePanelWidth,ui,metrics);
            int actualColumns=ml.columns();
            int panelWidth=2*ui.panelPadding()+actualColumns*minimumColumnWidth
                    +(actualColumns-1)*ui.columnGap();
            ml=buildMaterialsLayout(materials,panelWidth,ui,metrics);
            probeGraphics.dispose();
            probe.flush();

            // V153: materials_only is a standalone export, so render its existing logical
            // layout at 2x. This keeps buildMaterialsLayout as the single layout source while
            // doubling the output resolution, text, icons, and other panel details.
            int standaloneScale=2;
            int outputWidth=panelWidth*standaloneScale;
            int outputHeight=ml.panelHeight()*standaloneScale;
            BufferedImage canvas=new BufferedImage(outputWidth,outputHeight,
                    BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g=canvas.createGraphics();
            g.scale(standaloneScale,standaloneScale);
            g.setColor(sheetBackground(Style.PAPER));
            g.fillRect(0,0,panelWidth,ml.panelHeight());
            drawEngineeringGrid(g,panelWidth,ml.panelHeight(),Style.PAPER);
            drawMaterials(g,0,0,panelWidth,ml.panelHeight(),ml,ui,Style.PAPER,
                    java.awt.Color.BLACK);
            g.dispose();
            Path file=out.resolve(outputBaseName()+"_materials_only.png");
            ImageIO.write(canvas,"PNG",file.toFile());
            canvas.flush();
            System.out.println("WROTE MATERIALS ONLY "+file+" ("+outputWidth+"x"
                    +outputHeight+", columns="+ml.columns()+")");
        }

        private static void drawEngineeringGrid(java.awt.Graphics2D g, int width, int height, Style style) {
            int size = nonNegativeIntProperty("litematic.sheet.grid.size", 64);
            if (size == 0) return;
            java.awt.Color fallback = style == Style.PAPER
                    ? new java.awt.Color(210, 205, 195) : new java.awt.Color(58, 82, 112);
            java.awt.Color minor=rgbProperty("litematic.sheet.grid.color", fallback);
            java.awt.Color major=style == Style.PAPER ? new java.awt.Color(194,188,177)
                    : new java.awt.Color(72,99,132);
            for (int x = size, n=1; x < width; x += size,n++) {
                g.setColor(n%4==0 ? major : minor);
                g.setStroke(new java.awt.BasicStroke(n%4==0 ? 1.35f : 1f));
                g.drawLine(x,0,x,height);
            }
            for (int y = size, n=1; y < height; y += size,n++) {
                g.setColor(n%4==0 ? major : minor);
                g.setStroke(new java.awt.BasicStroke(n%4==0 ? 1.35f : 1f));
                g.drawLine(0,y,width,y);
            }
        }

        private static java.awt.Color sheetBackground(Style style) {
            if (style == Style.PAPER) return new java.awt.Color(245, 240, 225);
            int b = colorProperty("litematic.sheet.bg.b", 95);
            int g = colorProperty("litematic.sheet.bg.g", 50);
            int r = colorProperty("litematic.sheet.bg.r", 18);
            return new java.awt.Color(r, g, b);
        }

        private void drawViewCell(java.awt.Graphics2D g, View view, int x, int y,
                                  int size, boolean rotateCounterClockwise,
                                  Style style) {
            BufferedImage source = viewsFor(style)[view.ordinal()];
            int sourceWidth=source.getWidth(),sourceHeight=source.getHeight();
            double fitScale=Math.min(size/(double)sourceWidth,size/(double)sourceHeight);
            int drawWidth=Math.max(1,(int)Math.round(sourceWidth*fitScale));
            int drawHeight=Math.max(1,(int)Math.round(sourceHeight*fitScale));
            int drawX=x+(size-drawWidth)/2,drawY=y+(size-drawHeight)/2;
            System.out.printf(Locale.ROOT,
                    "VIEW_FIT %s source=%dx%d sourceAspect=%.6f cell=%dx%d draw=%dx%d "
                            + "drawAspect=%.6f offset=(%d,%d)%n",
                    view,sourceWidth,sourceHeight,sourceWidth/(double)sourceHeight,size,size,
                    drawWidth,drawHeight,drawWidth/(double)drawHeight,drawX-x,drawY-y);
            if (rotateCounterClockwise) {
                java.awt.Graphics2D rotated = (java.awt.Graphics2D)g.create();
                // Java2D's Y axis points down, so a negative angle is visually CCW.
                rotated.rotate(-Math.PI / 2.0, x + size / 2.0, y + size / 2.0);
                rotated.drawImage(source, drawX, drawY, drawWidth, drawHeight, null);
                rotated.dispose();
            } else {
                g.drawImage(source, drawX, drawY, drawWidth, drawHeight, null);
            }
        }

        private String sheetTitle() {
            String name=title == null || title.isBlank() ? input.getFileName().toString() : title;
            return name.toLowerCase(Locale.ROOT).endsWith(".litematic")
                    ? name.substring(0,name.length()-".litematic".length()) : name;
        }

        private String outputBaseName() {
            return MaterialWorkbookWriter.safeName(sheetTitle());
        }

        // V147 §33-37: drawMaterials 完全使用 MaterialsLayout 提供的位置/列数,不再自己算 rows/columns/columnW。
        private void drawMaterials(java.awt.Graphics2D g, int x, int y, int width, int height,
                                   MaterialsLayout ml, SheetUiMetrics ui, Style style, java.awt.Color textColor) {
            int scale=(int)Math.round(ui.scale()*100);
            int heading=ui.materialsTitleFont(),rowH=ml.rowHeight();
            int panelPad=ml.padding();
            int iconSize=ml.iconSize();
            int iconPlatePad=Math.max(2,(int)Math.round(2*ui.scale()));
            int iconPlateSize=iconSize+iconPlatePad*2;
            int columns=ml.columns(),rows=ml.rows();
            int[] columnWidths=ml.columnWidths();
            int[] columnX=ml.columnX();
            g.setColor(style == Style.BLUEPRINT ? new java.awt.Color(10,35,75,190)
                    : new java.awt.Color(238,232,216,235));
            g.fillRoundRect(x,y,width,height,(int)Math.round(10*ui.scale()),(int)Math.round(10*ui.scale()));
            g.setColor(style == Style.BLUEPRINT ? new java.awt.Color(255,255,255,210)
                    : new java.awt.Color(145,137,123));
            g.setStroke(new java.awt.BasicStroke(ui.panelBorderStroke()));
            g.drawRoundRect(x,y,width-1,height-1,(int)Math.round(10*ui.scale()),(int)Math.round(10*ui.scale()));
            g.setColor(textColor);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF,java.awt.Font.BOLD,heading));
            g.drawString("Materials",x+panelPad,y+panelPad+heading);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF,java.awt.Font.PLAIN,ml.fontSize()));
            int iconTextGap=(int)Math.round(7*ui.scale());
            int quantityGap=(int)Math.round(5*ui.scale());
            for (int i=0;i<materials.size();i++) {
                int column=i/rows, row=i%rows;
                int cellX=x+columnX[column];
                int cellY=y+panelPad+ml.titleGap()+row*rowH;
                MaterialEntry entry=materials.get(i);
                g.setColor(style == Style.BLUEPRINT ? new java.awt.Color(218,235,250,238)
                        : new java.awt.Color(248,245,235,242));
                g.fillRoundRect(cellX,cellY,iconPlateSize,iconPlateSize,
                        (int)Math.round(5*ui.scale()),(int)Math.round(5*ui.scale()));
                g.setColor(style == Style.BLUEPRINT ? new java.awt.Color(255,255,255,225)
                        : new java.awt.Color(174,167,153,220));
                g.setStroke(new java.awt.BasicStroke((float)Math.max(1.0,ui.scale())));
                g.drawRoundRect(cellX,cellY,iconPlateSize-1,iconPlateSize-1,
                        (int)Math.round(5*ui.scale()),(int)Math.round(5*ui.scale()));
                if (entry.icon != null) {
                    Object oldInterpolation=g.getRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION);
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(entry.icon,cellX+iconPlatePad,cellY+iconPlatePad,iconSize,iconSize,null);
                    if (oldInterpolation != null)
                        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,oldInterpolation);
                }
                int textX=cellX+iconPlateSize+iconTextGap;
                int baseline=cellY+(int)Math.round(24*ui.scale());
                String quantity=Long.toString(entry.count);
                java.awt.FontMetrics fm=g.getFontMetrics();
                // V147 §37: 数量右对齐本列。
                int columnRight=cellX+columnWidths[column];
                int quantityX=columnRight-fm.stringWidth(quantity);
                int nameAvailable=textX<=quantityX-quantityGap?quantityX-quantityGap-textX:Math.max(10,columnWidths[column]-(textX-cellX)-fm.stringWidth(quantity)-quantityGap);
                String name=fitText(entry.name,fm,nameAvailable);
                g.setColor(textColor);
                g.drawString(name,textX,baseline);
                g.drawString(quantity,quantityX,baseline);
            }
        }

        private static String fitText(String value, java.awt.FontMetrics fm, int width) {
            if (fm.stringWidth(value)<=width) return value;
            String suffix="…";
            int end=value.length();
            while (end>0 && fm.stringWidth(value.substring(0,end)+suffix)>width) end--;
            return value.substring(0,end)+suffix;
        }

        private static int niceScaleBar(double maxSpan) {
            if (maxSpan >= 100) return 50;
            if (maxSpan >= 40) return 20;
            if (maxSpan >= 20) return 10;
            if (maxSpan >= 10) return 5;
            return Math.max(1, (int)Math.floor(maxSpan / 2.0));
        }

        private void compositeStrip(View[] views, String outName, Style outputStyle) throws Exception {
            int vw = composites[views[0].ordinal()].getWidth();
            int vh = composites[views[0].ordinal()].getHeight();
            int targetH = vh;            // preserve the full capture resolution
            int targetW = (int)Math.round(vw * (targetH / (double)vh));
            int gap = adaptiveGap(targetW * views.length, views.length);
            int totalW = targetW * views.length + gap * (views.length - 1);
            java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(
                    totalW, targetH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = canvas.createGraphics();
            g.setColor(sheetBackground(outputStyle));
            g.fillRect(0, 0, totalW, targetH);
            drawEngineeringGrid(g, totalW, targetH, outputStyle);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int i = 0; i < views.length; i++) {
                BufferedImage src = viewsFor(outputStyle)[views[i].ordinal()];
                g.drawImage(src, i * (targetW + gap), 0, targetW, targetH, null);
            }
            g.dispose();
            Path file = out.resolve(outName);
            ImageIO.write(canvas, "PNG", file.toFile());
            System.out.println("WROTE COMPOSITE " + file + " (" + totalW + "x" + targetH + ")");
        }

        /** Scale inter-view whitespace with the source canvas and view count. */
        private static int adaptiveGap(int canvasExtent, int viewCount) {
            return Math.max(4, canvasExtent / Math.max(1, viewCount) / 20);
        }

        private BufferedImage[] viewsFor(Style outputStyle) {
            return outputStyle == Style.PAPER ? colorViews : blueprintViews;
        }

        private static BufferedImage nativeToBuffered(NativeImage ni) {
            // NativeImage in MC 1.21.1 is top-left origin (same as BufferedImage),
            // so we copy pixel-for-pixel without Y flip. The earlier code flipped
            // Y which produced upside-down composites.
            int w = ni.getWidth(), h = ni.getHeight();
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = ni.getPixel(x, y);
                    bi.setRGB(x, y, c);
                }
            }
            return bi;
        }

        private static BufferedImage applyGamma(BufferedImage source, double gamma) {
            int width = source.getWidth(), height = source.getHeight();
            BufferedImage corrected = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            double exponent = 1.0 / gamma;
            int[] lookup = new int[256];
            for (int value = 0; value < lookup.length; value++) {
                lookup[value] = (int)Math.round(255.0 * Math.pow(value / 255.0, exponent));
            }
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int pixel = source.getRGB(x, y);
                int alpha = pixel >>> 24;
                int red = lookup[(pixel >>> 16) & 255];
                int green = lookup[(pixel >>> 8) & 255];
                int blue = lookup[pixel & 255];
                corrected.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
            return corrected;
        }

        private static BufferedImage applyPaperColor(BufferedImage source, double brightness,
                                                     double contrast, double saturation) {
            int width=source.getWidth(),height=source.getHeight();
            BufferedImage result=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
                int pixel=source.getRGB(x,y),alpha=pixel>>>24;
                double red=((pixel>>>16)&255)*brightness;
                double green=((pixel>>>8)&255)*brightness;
                double blue=(pixel&255)*brightness;
                double luminance=red*0.2126+green*0.7152+blue*0.0722;
                red=luminance+(red-luminance)*saturation;
                green=luminance+(green-luminance)*saturation;
                blue=luminance+(blue-luminance)*saturation;
                int redOut=contrastChannel(red,contrast);
                int greenOut=contrastChannel(green,contrast);
                int blueOut=contrastChannel(blue,contrast);
                result.setRGB(x,y,(alpha<<24)|(redOut<<16)|(greenOut<<8)|blueOut);
            }
            return result;
        }

        private static int contrastChannel(double value,double contrast) {
            return Math.max(0,Math.min(255,(int)Math.round(128+(value-128)*contrast)));
        }

        /**
         * Pure-Java approximation of the owner-provided OpenCV blueprint_effect:
         * grayscale, a small edge-preserving-filter substitute, Canny-like Sobel
         * thresholds and dilation, then transparent-background white edges.
         * This method receives view pixels only; sheet furniture is drawn later.
         */
        private static BufferedImage blueprintEffect(BufferedImage source) {
            int width = source.getWidth();
            int height = source.getHeight();

            // Flatten the alpha-matted color view onto white before edge finding,
            // matching the colorful render's original white background.
            BufferedImage opaque = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D opaqueGraphics = opaque.createGraphics();
            opaqueGraphics.setColor(java.awt.Color.WHITE);
            opaqueGraphics.fillRect(0, 0, width, height);
            opaqueGraphics.drawImage(source, 0, 0, null);
            opaqueGraphics.dispose();

            BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null)
                    .filter(opaque, gray);

            int bilateralD = oddPositiveIntProperty("litematic.blueprint.bilateral.d", 3);
            double sigmaColor = positiveDoubleProperty("litematic.blueprint.bilateral.sigmaColor", 1.5);
            double sigmaSpace = positiveDoubleProperty("litematic.blueprint.bilateral.sigmaSpace", 5.0);
            // Low-memory bilateral approximation: spatial Gaussian strength is
            // controlled by sigmaSpace; sigmaColor controls how much smoothing
            // is mixed back into the original luminance.
            float[] gaussian = gaussianKernel(bilateralD, sigmaSpace);
            BufferedImage filtered = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            new ConvolveOp(new Kernel(bilateralD, bilateralD, gaussian), ConvolveOp.EDGE_NO_OP, null)
                    .filter(gray, filtered);
            double smoothMix = sigmaColor / (sigmaColor + 35.0);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int original = gray.getRaster().getSample(x, y, 0);
                int blurred = filtered.getRaster().getSample(x, y, 0);
                filtered.getRaster().setSample(x, y, 0,
                        (int)Math.round(original * (1.0 - smoothMix) + blurred * smoothMix));
            }

            byte[] edgeClass = new byte[width * height];
            int[] sobelX = {-1, 0, 1, -2, 0, 2, -1, 0, 1};
            int[] sobelY = {-1, -2, -1, 0, 0, 0, 1, 2, 1};
            int cannyLow = positiveIntProperty("litematic.blueprint.canny.low", 5);
            int cannyHigh = Math.max(cannyLow,
                    positiveIntProperty("litematic.blueprint.canny.high", 16));
            for (int y = 1; y < height - 1; y++) {
                for (int x = 1; x < width - 1; x++) {
                    int gx = 0;
                    int gy = 0;
                    int kernelIndex = 0;
                    for (int ky = -1; ky <= 1; ky++) {
                        for (int kx = -1; kx <= 1; kx++) {
                            int sample = filtered.getRaster().getSample(x + kx, y + ky, 0);
                            gx += sample * sobelX[kernelIndex];
                            gy += sample * sobelY[kernelIndex++];
                        }
                    }
                    int magnitude = Math.min(255, (Math.abs(gx) + Math.abs(gy)) / 4);
                    edgeClass[y * width + x] = (byte)(magnitude >= cannyHigh ? 2 : magnitude >= cannyLow ? 1 : 0);
                }
            }

            // Lightweight hysteresis: retain a weak pixel when it touches a
            // strong one, corresponding to Canny's low/high thresholds.
            BufferedImage edges = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 1; y < height - 1; y++) {
                for (int x = 1; x < width - 1; x++) {
                    int index = y * width + x;
                    boolean keep = edgeClass[index] == 2;
                    if (!keep && edgeClass[index] == 1) {
                        for (int ky = -1; ky <= 1 && !keep; ky++) {
                            for (int kx = -1; kx <= 1; kx++) {
                                if (edgeClass[(y + ky) * width + x + kx] == 2) {
                                    keep = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (keep) edges.getRaster().setSample(x, y, 0, 1);
                }
            }

            edges=cleanupNoise(edges,
                    positiveIntProperty("litematic.blueprint.noise.minPixels",6),
                    nonNegativeIntProperty("litematic.blueprint.noise.radius",1));

            int dilateKernel = positiveIntProperty("litematic.blueprint.dilate.kernel", 1);
            int dilateIterations = nonNegativeIntProperty("litematic.blueprint.dilate.iterations", 0);
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (edges.getRaster().getSample(x, y, 0) != 0) {
                        // Strong Canny responses carry the main silhouette and
                        // major structure. Hysteresis-only detail stays finer
                        // and translucent, giving the drawing an actual line
                        // hierarchy instead of an equally-white edge cloud.
                        int alpha=edgeClass[y*width+x] == 2 ? 255 : 150;
                        result.setRGB(x,y,(alpha<<24)|0x00ffffff);
                        int grown = 1 + dilateIterations * (dilateKernel - 1);
                        if (grown>1) for (int dy=0;dy<grown && y+dy<height;dy++)
                            for (int dx=0;dx<grown && x+dx<width;dx++)
                                result.setRGB(x+dx,y+dy,(alpha<<24)|0x00ffffff);
                    }
                }
            }
            return result;
        }

        private static BufferedImage cleanupNoise(BufferedImage edges,int minPixels,int radius) {
            int width=edges.getWidth(),height=edges.getHeight(),length=width*height;
            byte[] visited=new byte[length];
            int[] queue=new int[length];
            BufferedImage connected=new BufferedImage(width,height,BufferedImage.TYPE_BYTE_BINARY);
            for (int start=0;start<length;start++) {
                int sx=start%width,sy=start/width;
                if (visited[start]!=0 || edges.getRaster().getSample(sx,sy,0)==0) continue;
                int head=0,tail=0;
                queue[tail++]=start; visited[start]=1;
                while (head<tail) {
                    int point=queue[head++],px=point%width,py=point/width;
                    for (int dy=-1;dy<=1;dy++) for (int dx=-1;dx<=1;dx++) {
                        if (dx==0 && dy==0) continue;
                        int nx=px+dx,ny=py+dy;
                        if (nx<0 || nx>=width || ny<0 || ny>=height) continue;
                        int next=ny*width+nx;
                        if (visited[next]==0 && edges.getRaster().getSample(nx,ny,0)!=0) {
                            visited[next]=1; queue[tail++]=next;
                        }
                    }
                }
                if (tail>=minPixels) for (int i=0;i<tail;i++)
                    connected.getRaster().setSample(queue[i]%width,queue[i]/width,0,1);
            }
            if (radius==0) return connected;
            BufferedImage cleaned=new BufferedImage(width,height,BufferedImage.TYPE_BYTE_BINARY);
            for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
                if (connected.getRaster().getSample(x,y,0)==0) continue;
                boolean neighbor=false;
                for (int dy=-radius;dy<=radius && !neighbor;dy++) for (int dx=-radius;dx<=radius;dx++) {
                    if (dx==0 && dy==0) continue;
                    int nx=x+dx,ny=y+dy;
                    if (nx>=0 && nx<width && ny>=0 && ny<height &&
                            connected.getRaster().getSample(nx,ny,0)!=0) { neighbor=true; break; }
                }
                if (neighbor) cleaned.getRaster().setSample(x,y,0,1);
            }
            connected.flush();
            return cleaned;
        }

        private static float[] gaussianKernel(int size, double sigma) {
            float[] values = new float[size * size];
            int radius = size / 2;
            double effectiveSigma = Math.max(0.1, sigma * size / 35.0);
            double sum = 0;
            for (int y = -radius; y <= radius; y++) for (int x = -radius; x <= radius; x++) {
                double value = Math.exp(-(x * x + y * y) / (2.0 * effectiveSigma * effectiveSigma));
                values[(y + radius) * size + x + radius] = (float)value;
                sum += value;
            }
            for (int i = 0; i < values.length; i++) values[i] /= (float)sum;
            return values;
        }

        private static int positiveIntProperty(String name, int fallback) {
            return Math.max(1, Integer.getInteger(name, fallback));
        }

        private static int nonNegativeIntProperty(String name, int fallback) {
            return Math.max(0, Integer.getInteger(name, fallback));
        }

        private static int oddPositiveIntProperty(String name, int fallback) {
            int value = positiveIntProperty(name, fallback);
            return (value & 1) == 0 ? value + 1 : value;
        }

        private static double positiveDoubleProperty(String name, double fallback) {
            try {
                return Math.max(0.1, Double.parseDouble(System.getProperty(name, Double.toString(fallback))));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static double unitDoubleProperty(String name, double fallback) {
            try {
                return Math.max(0.0, Math.min(1.0,
                        Double.parseDouble(System.getProperty(name, Double.toString(fallback)))));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static long configuredRenderTime() {
            String value = System.getProperty("litematic.render.time", "noon").trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "day" -> 1000L;
                case "noon" -> 6000L;
                case "night" -> 13000L;
                case "midnight" -> 18000L;
                default -> {
                    try {
                        yield Long.parseLong(value);
                    } catch (NumberFormatException error) {
                        throw new IllegalArgumentException("Invalid litematic.render.time='" + value
                                + "' (expected day, noon, night, midnight, or a tick number)", error);
                    }
                }
            };
        }

        private static int colorProperty(String name, int fallback) {
            return Math.max(0, Math.min(255, Integer.getInteger(name, fallback)));
        }

        private static java.awt.Color rgbProperty(String name, java.awt.Color fallback) {
            String[] values = System.getProperty(name, "").split(",");
            if (values.length != 3) return fallback;
            try {
                return new java.awt.Color(
                        Math.max(0, Math.min(255, Integer.parseInt(values[0].trim()))),
                        Math.max(0, Math.min(255, Integer.parseInt(values[1].trim()))),
                        Math.max(0, Math.min(255, Integer.parseInt(values[2].trim()))));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }

        private static void setOverworldClock(Minecraft client, long time, float rate) {
            Holder<WorldClock> overworldClock = client.level.registryAccess()
                    .lookupOrThrow(Registries.WORLD_CLOCK)
                    .getOrThrow(WorldClocks.OVERWORLD);
            client.level.clockManager().handleUpdates(
                    client.level.getDefaultClockTime(),
                    Map.of(overworldClock, new ClockNetworkState(time, 0.0f, rate)));
        }

    }

    static void writeSingleView(BufferedImage source, Path file) throws Exception {
        // This is the only single-view output path. It copies view pixels onto a
        // fresh transparent canvas and never calls any sheet/background helper.
        BufferedImage bare = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] pixels = source.getRGB(0, 0, source.getWidth(), source.getHeight(),
                null, 0, source.getWidth());
        bare.setRGB(0, 0, source.getWidth(), source.getHeight(),
                pixels, 0, source.getWidth());
        if (!ImageIO.write(bare, "PNG", file.toFile())) {
            throw new IllegalStateException("No PNG writer available for " + file);
        }
        bare.flush();
        try {
            assertBareSingleView(file);
        } catch (IllegalStateException warning) {
            // A bad camera position can produce one empty angle. Preserve the
            // diagnostic, but let the remaining views and composite sheets render.
            System.out.println("WARN " + warning.getMessage());
        }
        System.out.println("WROTE BARE SINGLE VIEW " + file);
    }

    static void assertBareSingleView(Path file) throws Exception {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null || !image.getColorModel().hasAlpha()) {
            throw new IllegalStateException("Single view is not an alpha PNG: " + file);
        }
        boolean transparent = false;
        boolean opaque = false;
        for (int y = 0; y < image.getHeight() && !(transparent && opaque); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                transparent |= alpha == 0;
                opaque |= alpha == 255;
            }
        }
        image.flush();
        if (!transparent || !opaque) {
            throw new IllegalStateException("Single view must contain transparent background"
                    + " and opaque view content: " + file + " transparent=" + transparent
                    + " opaque=" + opaque);
        }
    }

    /** A deliberately minimal screen used only to ask Minecraft's own GUI item
     * renderer for the exact Inventory/Hotbar representation of each stack. */
    private static final class MaterialIconScreen extends Screen {
        private final List<MaterialEntry> entries;
        private final boolean white;

        MaterialIconScreen(List<MaterialEntry> entries,boolean white) {
            super(Component.literal("Material icons"));
            this.entries=entries;
            this.white=white;
        }

        static int columns(int guiWidth) { return Math.max(1,(guiWidth-16)/24); }
        static int iconX(int index,int columns) { return 8+(index%columns)*24; }
        static int iconY(int index,int columns) { return 8+(index/columns)*24; }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick) {
            graphics.fill(0,0,width,height,white ? 0xffffffff : 0xff000000);
            int columns=columns(width);
            for (int i=0;i<entries.size();i++)
                // Match recipe-book/ingredient previews: resolve the GUI model
                // without inheriting the camera player's transient render state.
                graphics.fakeItem(entries.get(i).stack,iconX(i,columns),iconY(i,columns));
        }

        @Override public boolean isPauseScreen() { return false; }
    }

    // V147: 改 package-private (去掉 private),让同包测试可以 forTest factory 访问。
    static final class MaterialEntry {
        final ItemStack stack;
        final String name;
        final long count;
        BufferedImage icon;
        MaterialEntry(ItemStack stack,String name,long count) {
            this.stack=stack; this.name=name; this.count=count;
        }
        String name() { return name; }
        long count() { return count; }
        // V147 §47-55 测试用 factory —— 保持 MaterialEntry 内部数据不变,只是给同包测试一个 entry 点。
        static MaterialEntry forTest(String name,long count) {
            return new MaterialEntry(null,name,count);
        }
    }

    private record FrozenEntity(Entity entity, Vec3 position) {}
}
