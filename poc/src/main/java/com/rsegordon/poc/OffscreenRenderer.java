package com.rsegordon.poc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import org.joml.Matrix4f;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/** Minimal proof: populate a ClientWorld, let the normal WorldRenderer draw it, copy its framebuffer. */
public final class OffscreenRenderer {
    private static Job job;
    private static boolean worldStartRequested;
    private static ViewState activeView;
    static {
        ClientTickEvents.END_CLIENT_TICK.register(OffscreenRenderer::tick);
        WorldRenderEvents.START.register(context -> {
            if (activeView == null) return;
            MinecraftClient client = MinecraftClient.getInstance();
            float aspect = (float) client.getWindow().getFramebufferWidth()
                    / client.getWindow().getFramebufferHeight();
            Matrix4f projection = context.projectionMatrix().setOrtho(
                    -activeView.halfSize * aspect, activeView.halfSize * aspect,
                    -activeView.halfSize, activeView.halfSize, 0.05f, activeView.farPlane);
            RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_DISTANCE);
        });
    }
    private OffscreenRenderer() {}

    public static void arm(String input, String output) { job = new Job(Path.of(input), Path.of(output)); }

    private static void tick(MinecraftClient client) {
        if (job == null) return;
        if (client.world == null) {
            if (client.getOverlay() != null) return;
            if (!worldStartRequested) {
                worldStartRequested = true;
                System.out.println("LITEMATIC_RENDER_STARTING_WORLD World");
                client.createIntegratedServerLoader().start("World", () -> client.setScreen(new TitleScreen()));
            }
            return;
        }
        if (client.player == null) return;
        try {
            if (!job.loaded) { job.load(client); return; }
            job.freezeEntities();
            View view = View.values()[Math.min(job.view, View.values().length - 1)];
            activeView = job.cameraFor(view, client);
            client.player.setPosition(activeView.position.x,
                    activeView.position.y - client.player.getStandingEyeHeight(), activeView.position.z);
            client.player.setYaw(view.yaw); client.player.setPitch(view.pitch);
            job.wait++;
            if (job.blackPass == null) {
                if (job.wait < 35) return;
                job.blackPass=ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
                BackgroundPass.setWhite(true);
                job.wait=0;
                return;
            }
            if (job.wait < 5) return;
            job.capture(client, view);
            BackgroundPass.setWhite(false);
            job.view++; job.wait = 0;
            if (job.view == View.values().length) {
                job.assembleComposites();
                System.out.println("LITEMATIC_RENDER_DONE " + job.out);
                client.scheduleStop(); job = null; activeView = null; worldStartRequested = false;
            }
        } catch (Exception error) { error.printStackTrace(); client.scheduleStop(); job = null; activeView = null; }
    }

    /** 7 views for V64:
     *  - TOP/FRONT/SIDE: cardinal 3-view orthographic
     *  - ISO_135: standard iso at yaw=135 pitch=30
     *  - ANGLE_45/225/315: 3 more axonometric views at yaw 45/225/315 pitch=30
     *    so the 4-angle strip shows the model from 4 cardinal iso corners
     */
    private enum View {
        TOP(0, 90), FRONT(180, 0), SIDE(90, 0),
        ISO_135(135, 30), ANGLE_45(45, 30), ANGLE_225(225, 30), ANGLE_315(315, 30);
        final float yaw,pitch;
        View(float yaw,float pitch) { this.yaw=yaw;this.pitch=pitch; }
    }

    private record ViewState(Vec3d position, float halfSize, float farPlane) {}

    private static final class Job {
        final Path input, out; boolean loaded; int wait, view; NativeImage blackPass;
        double minX, minY, minZ, maxX, maxY, maxZ;
        final List<FrozenEntity> frozenEntities=new ArrayList<>();
        // Per-view saved native image (post alpha matting) for composite assembly
        final NativeImage[] composites = new NativeImage[View.values().length];
        Job(Path input, Path out) { this.input=input; this.out=out; }

        void load(MinecraftClient client) throws Exception {
            NbtCompound root = NbtIo.readCompressed(input, NbtSizeTracker.ofUnlimitedBytes());
            NbtCompound regions = root.getCompound("Regions");
            if (regions.getKeys().isEmpty()) throw new IllegalArgumentException("No litematic regions");
            NbtCompound region = regions.getCompound(regions.getKeys().iterator().next());
            NbtCompound size = region.getCompound("Size");
            int sizeX=size.getInt("x"),sizeY=size.getInt("y"),sizeZ=size.getInt("z");
            int sx=Math.abs(sizeX),sy=Math.abs(sizeY),sz=Math.abs(sizeZ);
            minX=0; minY=100; minZ=0; maxX=sx; maxY=100+sy; maxZ=sz;
            NbtList paletteNbt = region.getList("BlockStatePalette", NbtElement.COMPOUND_TYPE);
            List<BlockState> palette = new ArrayList<>();
            for (int i=0;i<paletteNbt.size();i++) palette.add(NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), paletteNbt.getCompound(i)));
            long[] packed=region.getLongArray("BlockStates"); int bits=Math.max(2, 32-Integer.numberOfLeadingZeros(palette.size()-1)); long mask=(1L<<bits)-1;
            BlockPos origin=new BlockPos(0,100,0);
            BlockState air = Blocks.AIR.getDefaultState();
            for (int y=client.world.getBottomY();y<origin.getY();y++) {
                for (int z=-32;z<=32;z++) for (int x=-32;x<=32;x++) {
                    client.world.setBlockState(new BlockPos(x,y,z),air,19);
                }
            }
            for (int y=0;y<sy;y++) for (int z=0;z<sz;z++) for (int x=0;x<sx;x++) {
                int n=(y*sz+z)*sx+x, start=n*bits, word=start>>>6, shift=start&63;
                long value=packed[word]>>>shift; if (shift+bits>64) value|=packed[word+1]<<(64-shift);
                client.world.setBlockState(origin.add(x,y,z), palette.get((int)(value&mask)), 19);
            }
            NbtList tiles=region.getList("TileEntities", NbtElement.COMPOUND_TYPE);
            for (int i=0;i<tiles.size();i++) {
                NbtCompound tag=tiles.getCompound(i).copy(); BlockPos p=origin.add(tag.getInt("x"),tag.getInt("y"),tag.getInt("z"));
                tag.putInt("x",p.getX());tag.putInt("y",p.getY());tag.putInt("z",p.getZ());
                BlockEntity be=BlockEntity.createFromNbt(p,client.world.getBlockState(p),tag,client.world.getRegistryManager());
                if (be!=null) client.world.addBlockEntity(be);
            }
            NbtList entities=region.getList("Entities", NbtElement.COMPOUND_TYPE);
            Vec3d entityOrigin=new Vec3d(
                    origin.getX()+(sizeX<0?sx-1:0),
                    origin.getY()+(sizeY<0?sy-1:0),
                    origin.getZ()+(sizeZ<0?sz-1:0));
            int entityCount=0;
            for (int i=0;i<entities.size();i++) {
                final int entityIndex=i;
                NbtCompound tag=entities.getCompound(i).copy();
                NbtList nbtPos=tag.getList("Pos",NbtElement.DOUBLE_TYPE);
                Entity rootEntity=EntityType.loadEntityWithPassengers(tag,client.world,entity -> {
                    entity.setUuid(UUID.randomUUID());
                    entity.refreshPositionAfterTeleport(entity.getPos().add(entityOrigin));
                    return entity;
                });
                if (rootEntity==null) continue;
                for (Entity entity : rootEntity.streamSelfAndPassengers().toList()) {
                    entity.setVelocity(Vec3d.ZERO);
                    entity.setNoGravity(true);
                    client.world.addEntity(entity);
                    include(entity.getBoundingBox());
                    frozenEntities.add(new FrozenEntity(entity,entity.getPos()));
                    entityCount++;
                }
            }
            Files.createDirectories(out); client.options.hudHidden=true; client.options.getFov().setValue(50);
            client.worldRenderer.reload(); loaded=true;
            System.out.printf("Loaded %dx%dx%d palette=%d tiles=%d entities=%d bounds=[%.2f,%.2f,%.2f]-[%.2f,%.2f,%.2f]%n",
                    sx,sy,sz,palette.size(),tiles.size(),entityCount,minX,minY,minZ,maxX,maxY,maxZ);
        }

        void include(Box box) {
            minX=Math.min(minX,box.minX); minY=Math.min(minY,box.minY); minZ=Math.min(minZ,box.minZ);
            maxX=Math.max(maxX,box.maxX); maxY=Math.max(maxY,box.maxY); maxZ=Math.max(maxZ,box.maxZ);
        }

        void freezeEntities() {
            for (FrozenEntity frozen : frozenEntities) {
                frozen.entity.setVelocity(Vec3d.ZERO);
                frozen.entity.refreshPositionAfterTeleport(frozen.position);
            }
        }

        ViewState cameraFor(View view, MinecraftClient client) {
            Vec3d center=new Vec3d((minX+maxX)/2.0,(minY+maxY)/2.0,(minZ+maxZ)/2.0);
            double yaw=Math.toRadians(view.yaw), pitch=Math.toRadians(view.pitch);
            Vec3d forward=new Vec3d(-Math.sin(yaw)*Math.cos(pitch),-Math.sin(pitch),Math.cos(yaw)*Math.cos(pitch));
            Vec3d right=new Vec3d(Math.cos(yaw),0,Math.sin(yaw)).normalize();
            Vec3d up=forward.crossProduct(right).normalize();
            double horizontal=0, vertical=0, radius=0;
            for (double x : new double[]{minX,maxX}) for (double y : new double[]{minY,maxY})
                for (double z : new double[]{minZ,maxZ}) {
                    Vec3d delta=new Vec3d(x,y,z).subtract(center);
                    horizontal=Math.max(horizontal,Math.abs(delta.dotProduct(right)));
                    vertical=Math.max(vertical,Math.abs(delta.dotProduct(up)));
                    radius=Math.max(radius,delta.length());
                }
            float aspect=(float)client.getWindow().getFramebufferWidth()/client.getWindow().getFramebufferHeight();
            float halfSize=(float)(Math.max(vertical,horizontal/aspect)*1.2);
            halfSize=Math.max(halfSize,1.0f);
            double distance=radius+Math.max(8.0,halfSize*1.2);
            Vec3d position=center.subtract(forward.multiply(distance));
            float farPlane=(float)Math.max(256.0,distance+radius+32.0);
            return new ViewState(position,halfSize,farPlane);
        }

        void capture(MinecraftClient client, View view) throws Exception {
            // We capture the white-background pass second; the black-background
            // pass was captured at the start of this view. matte(black, white)
            // derives per-pixel transparency from the difference between the two
            // opaque reads.
            // Keep the black framebuffer alive in blackPassBackup until matte
            // has consumed it, then close both.
            blackPassBackup = blackPass;
            blackPass = null;
            NativeImage whitePass;
            try {
                whitePass = ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
            } finally {
                // We close the backup AFTER matte() uses it.
            }
            NativeImage image = matte(blackPassBackup, whitePass);
            composites[view.ordinal()] = image;
            // Also write the individual view as a standalone PNG.
            Path file = out.resolve("mcoo_" + view.name().toLowerCase() + ".png");
            image.writeTo(file);
            System.out.println("WROTE " + file);
            // Close both framebuffer reads now that matte has consumed them.
            blackPassBackup.close();
            whitePass.close();
            blackPassBackup = null;
            // Reset for next view: capture a new black pass on next tick.
            BackgroundPass.setWhite(false);
        }

        private NativeImage blackPassBackup;

        NativeImage matte(NativeImage black, NativeImage white) {
            int width=black.getWidth(), height=black.getHeight();
            NativeImage result=new NativeImage(NativeImage.Format.RGBA,width,height,false);
            for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
                int br=black.getRed(x,y)&255, bg=black.getGreen(x,y)&255, bb=black.getBlue(x,y)&255;
                int wr=white.getRed(x,y)&255, wg=white.getGreen(x,y)&255, wb=white.getBlue(x,y)&255;
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
                result.setColor(x,y,(alpha<<24)|(red<<16)|(green<<8)|blue);
            }
            return result;
        }

        /** Build the two composites and write them out. */
        void assembleComposites() throws Exception {
            // Composite A: three-view ortho (TOP / FRONT / SIDE)
            compositeStrip(new View[]{View.TOP, View.FRONT, View.SIDE},
                    "mcoo_3view.png");
            // Composite B: four-angle ortho strip (45/135/225/315)
            compositeStrip(new View[]{View.ANGLE_45, View.ISO_135, View.ANGLE_225, View.ANGLE_315},
                    "mcoo_4angle.png");
        }

        private void compositeStrip(View[] views, String outName) throws Exception {
            int vw = composites[views[0].ordinal()].getWidth();
            int vh = composites[views[0].ordinal()].getHeight();
            int targetH = 1024;          // final strip height
            int targetW = (int)Math.round(vw * (targetH / (double)vh));
            int totalW = targetW * views.length;
            java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(
                    totalW, targetH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int i = 0; i < views.length; i++) {
                NativeImage ni = composites[views[i].ordinal()];
                BufferedImage src = nativeToBuffered(ni);
                g.drawImage(src, i * targetW, 0, targetW, targetH, null);
            }
            g.dispose();
            Path file = out.resolve(outName);
            ImageIO.write(canvas, "PNG", file.toFile());
            System.out.println("WROTE COMPOSITE " + file + " (" + totalW + "x" + targetH + ")");
        }

        private static BufferedImage nativeToBuffered(NativeImage ni) {
            // NativeImage in MC 1.21.1 is top-left origin (same as BufferedImage),
            // so we copy pixel-for-pixel without Y flip. The earlier code flipped
            // Y which produced upside-down composites.
            int w = ni.getWidth(), h = ni.getHeight();
            BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = ni.getColor(x, y);
                    bi.setRGB(x, y, c);
                }
            }
            return bi;
        }
    }

    private record FrozenEntity(Entity entity, Vec3d position) {}
}
