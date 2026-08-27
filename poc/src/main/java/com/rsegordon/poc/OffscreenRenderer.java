package com.rsegordon.poc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Matrix4f;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.state.level.CameraRenderState;
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
    /** 32 chunks covers a 100-block structure plus the most distant axonometric camera. */
    private static final int RENDER_DISTANCE_CHUNKS = 32;
    private static Job job;
    private static boolean worldStartRequested;
    private static int worldSettleTicks;
    private static ViewState activeView;
    private static volatile boolean paperFullbright;
    static {
        ClientTickEvents.END_CLIENT_TICK.register(OffscreenRenderer::tick);
    }
    private OffscreenRenderer() {}

    public static void arm(String input, String output) {
        setPaperFullbright(false);
        job = new Job(Path.of(input), Path.of(output));
    }

    public static boolean isPaperFullbright() { return paperFullbright; }

    public static void setPaperFullbright(boolean value) { paperFullbright = value; }

    /** Called after vanilla extracts the camera, but before 26.2 builds its culling frustum. */
    public static void applyProjection(CameraRenderState camera) {
        if (activeView == null) return;
        Minecraft client = Minecraft.getInstance();
        float aspect = (float) client.getWindow().getWidth() / client.getWindow().getHeight();
        camera.projectionMatrix.setOrtho(
                -activeView.halfSize * aspect, activeView.halfSize * aspect,
                -activeView.halfSize, activeView.halfSize,
                activeView.farPlane, 0.05f);
        camera.depthFar = activeView.farPlane;
    }

    private static void tick(Minecraft client) {
        if (job == null) return;
        if (client.level == null) {
            if (!client.isGameLoadFinished() || client.gui.overlay() != null) return;
            if (!worldStartRequested) {
                worldStartRequested = true;
                client.options.renderDistance().set(RENDER_DISTANCE_CHUNKS);
                System.out.println("LITEMATIC_RENDER_STARTING_WORLD World");
                client.createWorldOpenFlows().openWorld("World", () -> client.setScreenAndShow(new TitleScreen()));
            }
            return;
        }
        if (client.player == null) return;
        try {
            // The player object appears before the initial client chunks have
            // arrived. Writing into missing chunks silently fails and leaves
            // block entities attached to void_air, so wait for the 32-chunk
            // view-distance update and initial chunk packets to settle.
            if (!job.loaded && worldSettleTicks++ < 60) return;
            if (!job.loaded) { job.load(client); return; }
            job.enforceCaptureTime(client);
            job.freezeEntities();
            View view = View.values()[Math.min(job.view, View.values().length - 1)];
            activeView = job.cameraFor(view, client);
            client.player.setPos(activeView.position.x,
                    activeView.position.y - client.player.getEyeHeight(), activeView.position.z);
            client.player.setYRot(view.yaw); client.player.setXRot(view.pitch);
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
                job.passRebuildPending = false;
                job.wait = -120;
                return;
            }
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
            error.printStackTrace();
            job.clearNightVision(client);
            setPaperFullbright(false);
            client.stop();
            job = null;
            activeView = null;
            worldSettleTicks = 0;
        }
    }

    /**
     * V76 engineering views.  The yaw/pitch values are Minecraft camera angles;
     * cameraFor() converts them to a look vector with sin/cos.  Cardinal names
     * describe the observation station, e.g. FRONT_X_POS is viewed from +X.
     */
    private enum View {
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

    private record ViewState(Vec3 position, float halfSize, float farPlane) {}

    private static final class Job {
        final Path input, out; final Style style; final long renderTime;
        final double blueprintNightVision, blueprintGamma;
        boolean loaded, screenshotPending, nightVisionApplied, passRebuildPending;
        int wait, view;
        NativeImage blackPass;
        CapturePass capturePass;
        MobEffectInstance previousNightVision;
        double previousGamma;
        long previousDayTime;
        double minX, minY, minZ, maxX, maxY, maxZ;
        final List<FrozenEntity> frozenEntities=new ArrayList<>();
        int nextEntityId = -1000;
        // Per-view saved native image (post alpha matting) for composite assembly
        final NativeImage[] composites = new NativeImage[View.values().length];
        Job(Path input, Path out) {
            this.input=input;
            this.out=out;
            this.style=Style.configured();
            this.renderTime=configuredRenderTime();
            this.blueprintNightVision=unitDoubleProperty("litematic.render.nightvision", 1.0);
            this.blueprintGamma=positiveDoubleProperty("litematic.render.gamma", 2.5);
            this.capturePass=style.writesPaper() ? CapturePass.PAPER_COLOR : CapturePass.BLUEPRINT_EDGE;
        }

        void load(Minecraft client) throws Exception {
            CompoundTag root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            CompoundTag regions = root.getCompoundOrEmpty("Regions");
            if (regions.keySet().isEmpty()) throw new IllegalArgumentException("No litematic regions");
            CompoundTag region = regions.getCompoundOrEmpty(regions.keySet().iterator().next());
            CompoundTag size = region.getCompoundOrEmpty("Size");
            int sizeX=size.getIntOr("x",0),sizeY=size.getIntOr("y",0),sizeZ=size.getIntOr("z",0);
            int sx=Math.abs(sizeX),sy=Math.abs(sizeY),sz=Math.abs(sizeZ);
            // Keep the render volume above normal terrain while retaining at
            // least 32 blocks of headroom inside the build-height limit.
            int originY=Math.max(160,client.level.getMaxY()-sy-64);
            minX=0; minY=originY; minZ=0; maxX=sx; maxY=originY+sy; maxZ=sz;
            ListTag paletteNbt = region.getListOrEmpty("BlockStatePalette");
            List<BlockState> palette = new ArrayList<>();
            for (int i=0;i<paletteNbt.size();i++) palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, paletteNbt.getCompoundOrEmpty(i)));
            long[] packed=region.getLongArray("BlockStates").orElseThrow(); int bits=Math.max(2, 32-Integer.numberOfLeadingZeros(palette.size()-1)); long mask=(1L<<bits)-1;
            BlockPos origin=new BlockPos(0,originY,0);
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int y=client.level.getMinY();y<origin.getY();y++) {
                for (int z=-32;z<=32;z++) for (int x=-32;x<=32;x++) {
                    client.level.setBlock(new BlockPos(x,y,z),air,19,512);
                }
            }
            for (int y=0;y<sy;y++) for (int z=0;z<sz;z++) for (int x=0;x<sx;x++) {
                int n=(y*sz+z)*sx+x, start=n*bits, word=start>>>6, shift=start&63;
                long value=packed[word]>>>shift; if (shift+bits>64) value|=packed[word+1]<<(64-shift);
                client.level.setBlock(origin.offset(x,y,z), palette.get((int)(value&mask)), 19,512);
            }
            ListTag tiles=region.getListOrEmpty("TileEntities");
            for (int i=0;i<tiles.size();i++) {
                CompoundTag tag=tiles.getCompoundOrEmpty(i).copy(); BlockPos p=origin.offset(tag.getIntOr("x",0),tag.getIntOr("y",0),tag.getIntOr("z",0));
                tag.putInt("x",p.getX());tag.putInt("y",p.getY());tag.putInt("z",p.getZ());
                BlockEntity be=BlockEntity.loadStatic(p,client.level.getBlockState(p),tag,client.level.registryAccess());
                if (be!=null) client.level.setBlockEntity(be);
            }
            ListTag entities=region.getListOrEmpty("Entities");
            Vec3 entityOrigin=new Vec3(
                    origin.getX()+(sizeX<0?sx-1:0),
                    origin.getY()+(sizeY<0?sy-1:0),
                    origin.getZ()+(sizeZ<0?sz-1:0));
            int entityCount=0;
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
                for (Entity entity : rootEntity.getSelfAndPassengers().toList()) {
                    entity.setId(nextEntityId--);
                    entity.setDeltaMovement(Vec3.ZERO);
                    entity.setNoGravity(true);
                    client.level.addEntity(entity);
                    include(entity.getBoundingBox());
                    frozenEntities.add(new FrozenEntity(entity,entity.position()));
                    entityCount++;
                }
            }
            Files.createDirectories(out);
            if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
            client.options.fov().set(50);
            previousGamma = client.options.gamma().get();
            previousDayTime = client.level.getOverworldClockTime();
            client.options.renderDistance().set(RENDER_DISTANCE_CHUNKS);
            configureCapturePass(client);
            // Recreate section geometry with the safe 26.2 path. Unlike
            // resetLevelRenderData(), this waits for the occlusion graph reset
            // before the next extraction and includes newly non-empty sections.
            client.levelRenderer.invalidateCompiledGeometry(
                    client.level,client.options,client.gameRenderer.mainCamera(),client.getBlockColors());
            loaded=true;
            System.out.printf("Loaded %dx%dx%d palette=%d tiles=%d entities=%d time=%d pass=%s gamma=%.2f nightvision=%.2f bounds=[%.2f,%.2f,%.2f]-[%.2f,%.2f,%.2f]%n",
                    sx,sy,sz,palette.size(),tiles.size(),entityCount,renderTime,capturePass,
                    activeGamma(),activeNightVision(),
                    minX,minY,minZ,maxX,maxY,maxZ);
        }

        void configureCapturePass(Minecraft client) {
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
            System.out.printf("CAPTURE_PASS %s time=%d gamma=%.2f nightvision=%.2f fullbright=%s%n",
                    capturePass, actualTime, activeGamma(), nightVision, isPaperFullbright());
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
            float aspect=(float)client.getWindow().getWidth()/client.getWindow().getHeight();
            float halfSize;
            if (isPrincipalView(view)) {
                // All six principal views use one drawing scale.
                // Individually fitting each view makes the sheet look aligned,
                // but violates length/height/depth correspondence in pixels.
                double maxSpan = Math.max(maxX - minX,
                        Math.max(maxY - minY, maxZ - minZ));
                halfSize = (float)(maxSpan * 0.6); // 10% margin on each side
            } else {
                halfSize=(float)(Math.max(vertical,horizontal/aspect)*1.2);
            }
            halfSize=Math.max(halfSize,1.0f);
            double distance=radius+Math.max(8.0,halfSize*1.2);
            Vec3 position=center.subtract(forward.scale(distance));
            float farPlane=(float)Math.max(256.0,distance+radius+32.0);
            return new ViewState(position,halfSize,farPlane);
        }

        private static boolean isPrincipalView(View view) {
            return view == View.FRONT_X_POS || view == View.LEFT_Z_NEG ||
                    view == View.RIGHT_Z_POS || view == View.BACK_X_NEG ||
                    view == View.TOP_X_UP || view == View.BOTTOM_X_UP;
        }

        void requestBlackPass(Minecraft client) {
            screenshotPending = true;
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                blackPass = image;
                BackgroundPass.setWhite(true);
                wait = 0;
                screenshotPending = false;
            });
        }

        void requestCapture(Minecraft client, View view) {
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
                    System.out.printf("MATTE %s corners black=%08x white=%08x%n",
                            view.name(), blackPassBackup.getPixel(0, 0), whitePass.getPixel(0, 0));
                    NativeImage image = matte(blackPassBackup, whitePass);
                    boolean retainImage = composites[view.ordinal()] == null;
                    if (retainImage) composites[view.ordinal()] = image;
                    BufferedImage color = nativeToBuffered(image);
                    String baseName = "mcoo_" + view.name().toLowerCase(Locale.ROOT);
                    if (capturePass == CapturePass.BLUEPRINT_EDGE) {
                        BufferedImage gammaCorrected = applyGamma(color, blueprintGamma);
                        BufferedImage blueprint = blueprintEffect(gammaCorrected);
                        blueprintViews[view.ordinal()] = blueprint;
                        writeSingleView(blueprint, out.resolve(baseName + ".png"));
                        gammaCorrected.flush();
                        color.flush();
                    } else {
                        colorViews[view.ordinal()] = color;
                        writeSingleView(color, out.resolve(baseName + "_paper.png"));
                    }
                    if (!retainImage) image.close();
                    viewComplete(client);
                } catch (Exception error) {
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
            view++;
            wait = 0;
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
                assembleComposites();
                closeCapturedViews();
                clearNightVision(client);
                setPaperFullbright(false);
                client.options.gamma().set(previousGamma);
                setOverworldClock(client, previousDayTime, 1.0f);
                System.out.println("LITEMATIC_RENDER_DONE " + out);
                client.stop();
                job = null;
                activeView = null;
                worldStartRequested = false;
                worldSettleTicks = 0;
            }
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
            if (style.writesBlueprint()) assembleStyle(Style.BLUEPRINT, "");
            if (style.writesPaper()) assembleStyle(Style.PAPER, "_paper");
        }

        private void assembleStyle(Style outputStyle, String suffix) throws Exception {
            compositeEngineeringSheet("mcoo_3view" + suffix + ".png", outputStyle);
            compositeStrip(new View[]{View.AXON_X_POS_Z_NEG, View.AXON_X_POS_Z_POS,
                            View.AXON_X_NEG_Z_POS, View.AXON_X_NEG_Z_NEG},
                    "mcoo_4angle" + suffix + ".png", outputStyle);
            compositeOverview(suffix);
        }

        private void compositeOverview(String suffix) throws Exception {
            // The overview is intentionally the same complete engineering sheet
            // as the legacy 3view name. Reuse the already encoded result instead
            // of running ten native pencil passes a second time.
            Path destination = out.resolve("mcoo_overview" + suffix + ".png");
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
        private void compositeEngineeringSheet(String outName, Style outputStyle) throws Exception {
            int captureWidth = composites[0].getWidth();
            // A 2048 capture becomes a 1024-pixel drawing cell.  This keeps
            // four times as many pixels per view as V76's fixed 540px cells.
            int cell = Math.max(540, captureWidth / 2);
            double scale = cell / 540.0;
            int gap = (int)Math.round(42 * scale), margin = (int)Math.round(56 * scale);
            int titleH = (int)Math.round(82 * scale), footerH = (int)Math.round(82 * scale);
            int totalW = margin * 2 + cell * 4 + gap * 3;
            int totalH = margin * 2 + titleH + cell * 3 + gap * 2 + footerH;
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

            int x0 = margin, x1 = x0 + cell + gap, x2 = x1 + cell + gap, x3 = x2 + cell + gap;
            int y0 = margin + titleH, y1 = y0 + cell + gap, y2 = y1 + cell + gap;
            drawViewCell(g, View.AXON_X_POS_Z_POS, x0, y0, cell, "AXON +X +Z", false, outputStyle);
            drawViewCell(g, View.BOTTOM_X_UP, x1, y0, cell, "BOTTOM (+X UP)", false, outputStyle);
            drawViewCell(g, View.AXON_X_NEG_Z_POS, x3, y0, cell, "AXON -X +Z", false, outputStyle);
            drawViewCell(g, View.LEFT_Z_NEG, x0, y1, cell, "RIGHT (-Z LOOK)", false, outputStyle);
            drawViewCell(g, View.FRONT_X_POS, x1, y1, cell, "FRONT (+X)", false, outputStyle);
            drawViewCell(g, View.RIGHT_Z_POS, x2, y1, cell, "LEFT (V72)", false, outputStyle);
            drawViewCell(g, View.BACK_X_NEG, x3, y1, cell, "BACK (-X)", false, outputStyle);
            drawViewCell(g, View.AXON_X_POS_Z_NEG, x0, y2, cell, "AXON +X -Z", false, outputStyle);
            drawViewCell(g, View.TOP_X_UP, x1, y2, cell, "TOP (+X UP)", false, outputStyle);
            drawViewCell(g, View.AXON_X_NEG_Z_NEG, x3, y2, cell, "AXON -X -Z", false, outputStyle);

            g.setColor(java.awt.Color.BLACK);
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, (int)Math.round(34 * scale)));
            g.drawString("SIX PRINCIPAL VIEWS + FOUR AXONOMETRIC VIEWS", margin, margin + (int)Math.round(38 * scale));
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, (int)Math.round(20 * scale)));
            g.setColor(new java.awt.Color(101, 94, 84));
            g.drawString("Owner-defined layout · common principal-view scale · observation stations labeled", margin, margin + (int)Math.round(69 * scale));

            // A scale bar remains meaningful if the PNG is resized or printed.
            double maxSpan = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
            int blocks = niceScaleBar(maxSpan);
            int sourcePixels = composites[View.TOP_X_UP.ordinal()].getWidth();
            double sourcePixelsPerBlock = sourcePixels / (maxSpan * 1.2);
            int barPixels = (int)Math.round(blocks * sourcePixelsPerBlock *
                    (cell / (double)sourcePixels));
            int barY = totalH - margin - (int)Math.round(35 * scale);
            int barX = margin;
            g.setColor(java.awt.Color.BLACK);
            g.setStroke(new java.awt.BasicStroke((float)(3 * scale)));
            g.drawLine(barX, barY, barX + barPixels, barY);
            g.drawLine(barX, barY - (int)Math.round(8 * scale), barX, barY + (int)Math.round(8 * scale));
            g.drawLine(barX + barPixels, barY - (int)Math.round(8 * scale), barX + barPixels, barY + (int)Math.round(8 * scale));
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, (int)Math.round(19 * scale)));
            g.drawString(blocks + " blocks", barX, barY - (int)Math.round(14 * scale));

            g.setStroke(new java.awt.BasicStroke(2f));
            g.setColor(new java.awt.Color(78, 72, 64));
            g.drawRect(18, 18, totalW - 37, totalH - 37);
            g.dispose();
            Path file = out.resolve(outName);
            ImageIO.write(canvas, "PNG", file.toFile());
            System.out.println("WROTE COMPOSITE " + file + " (" + totalW + "x" + totalH + ")");
        }

        private static void drawEngineeringGrid(java.awt.Graphics2D g, int width, int height, Style style) {
            int size = nonNegativeIntProperty("litematic.sheet.grid.size", 64);
            if (size == 0) return;
            java.awt.Color fallback = style == Style.PAPER
                    ? new java.awt.Color(210, 205, 195) : new java.awt.Color(125, 145, 165);
            g.setColor(rgbProperty("litematic.sheet.grid.color", fallback));
            g.setStroke(new java.awt.BasicStroke(1f));
            for (int x = size; x < width; x += size) g.drawLine(x, 0, x, height);
            for (int y = size; y < height; y += size) g.drawLine(0, y, width, y);
        }

        private static java.awt.Color sheetBackground(Style style) {
            if (style == Style.PAPER) return new java.awt.Color(245, 240, 225);
            int b = colorProperty("litematic.sheet.bg.b", 95);
            int g = colorProperty("litematic.sheet.bg.g", 50);
            int r = colorProperty("litematic.sheet.bg.r", 18);
            return new java.awt.Color(r, g, b);
        }

        private void drawViewCell(java.awt.Graphics2D g, View view, int x, int y,
                                  int size, String label, boolean rotateCounterClockwise,
                                  Style style) {
            BufferedImage source = viewsFor(style)[view.ordinal()];
            if (rotateCounterClockwise) {
                java.awt.Graphics2D rotated = (java.awt.Graphics2D)g.create();
                // Java2D's Y axis points down, so a negative angle is visually CCW.
                rotated.rotate(-Math.PI / 2.0, x + size / 2.0, y + size / 2.0);
                rotated.drawImage(source, x, y, size, size, null);
                rotated.dispose();
            } else {
                g.drawImage(source, x, y, size, size, null);
            }
            g.setColor(java.awt.Color.BLACK);
            int labelFontSize = Math.max(22, (int)Math.round(size * 22.0 / 540.0));
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, labelFontSize));
            java.awt.FontMetrics fm = g.getFontMetrics();
            int labelW = fm.stringWidth(label);
            int labelX = x + (size - labelW) / 2;
            int labelY = y + size - Math.max(18, size * 18 / 540);
            g.setColor(new java.awt.Color(245, 240, 225, 226));
            g.fillRoundRect(labelX - 12, labelY - fm.getAscent(), labelW + 24,
                    fm.getHeight() + 4, 10, 10);
            g.setColor(java.awt.Color.BLACK);
            g.drawString(label, labelX, labelY);
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
            int totalW = targetW * views.length;
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
                g.drawImage(src, i * targetW, 0, targetW, targetH, null);
            }
            g.dispose();
            Path file = out.resolve(outName);
            ImageIO.write(canvas, "PNG", file.toFile());
            System.out.println("WROTE COMPOSITE " + file + " (" + totalW + "x" + targetH + ")");
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
            int cannyLow = positiveIntProperty("litematic.blueprint.canny.low", 3);
            int cannyHigh = Math.max(cannyLow,
                    positiveIntProperty("litematic.blueprint.canny.high", 12));
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

            int dilateKernel = positiveIntProperty("litematic.blueprint.dilate.kernel", 1);
            int dilateIterations = nonNegativeIntProperty("litematic.blueprint.dilate.iterations", 0);
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = result.createGraphics();
            graphics.setColor(BLUEPRINT_LINE);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (edges.getRaster().getSample(x, y, 0) != 0) {
                        int grown = 1 + dilateIterations * (dilateKernel - 1);
                        graphics.fillRect(x, y, grown, grown);
                    }
                }
            }
            graphics.dispose();
            return result;
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
        assertBareSingleView(file);
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

    private record FrozenEntity(Entity entity, Vec3 position) {}
}
