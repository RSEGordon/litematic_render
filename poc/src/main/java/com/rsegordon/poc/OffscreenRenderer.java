package com.rsegordon.poc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Matrix4f;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Minimal proof: populate a ClientWorld, let the normal WorldRenderer draw it, copy its framebuffer. */
public final class OffscreenRenderer {
    private static final float ORTHO_HALF_SIZE = 5.25f;
    private static Job job;
    private static boolean worldStartRequested;
    private static View activeView;
    private static ProjectionMatrixBuffer orthoProjectionBuffer;
    static {
        ClientTickEvents.END_CLIENT_TICK.register(OffscreenRenderer::tick);
        LevelRenderEvents.START_MAIN.register(context -> {
            if (activeView == null || !activeView.orthographic) return;
            Minecraft client = Minecraft.getInstance();
            float aspect = (float) client.getWindow().getWidth() / client.getWindow().getHeight();
            Matrix4f projection = new Matrix4f().setOrtho(
                    -ORTHO_HALF_SIZE * aspect, ORTHO_HALF_SIZE * aspect,
                    -ORTHO_HALF_SIZE, ORTHO_HALF_SIZE, 0.05f, 256.0f);
            if (orthoProjectionBuffer == null) orthoProjectionBuffer = new ProjectionMatrixBuffer("litematic ortho");
            RenderSystem.setProjectionMatrix(orthoProjectionBuffer.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
        });
    }
    private OffscreenRenderer() {}

    public static void arm(String input, String output) { job = new Job(Path.of(input), Path.of(output)); }

    private static void tick(Minecraft client) {
        if (job == null) return;
        if (client.level == null) {
            // Starting an integrated server while the resource reload overlay is active deadlocks
            // MinecraftClient.startIntegratedServer(), which waits for that overlay to disappear.
            if (!client.isGameLoadFinished() || client.gui.overlay() != null) return;
            if (!worldStartRequested) {
                worldStartRequested = true;
                System.out.println("LITEMATIC_RENDER_STARTING_WORLD World");
                client.createWorldOpenFlows().openWorld("World", () -> client.setScreenAndShow(new TitleScreen()));
            }
            return;
        }
        if (client.player == null) return;
        try {
            if (!job.loaded) { job.load(client); return; }
            View view = View.values()[Math.min(job.view, View.values().length - 1)];
            activeView = view;
            client.player.setPos(view.x, view.y - client.player.getEyeHeight(), view.z);
            client.player.setYRot(view.yaw); client.player.setXRot(view.pitch);
            if (++job.wait < 35) return; // chunk rebuild + one fully rendered frame
            job.capture(client, view.name().toLowerCase());
            job.view++; job.wait = 0;
            if (job.view == View.values().length) { System.out.println("LITEMATIC_RENDER_DONE " + job.out); client.stop(); job = null; activeView = null; worldStartRequested = false; }
        } catch (Exception error) { error.printStackTrace(); client.stop(); job = null; activeView = null; }
    }

    private enum View {
        TOP(2.5, 110, 2.5, 0, 90, true), FRONT(2.5, 104, 12, 180, 0, true),
        SIDE(12, 104, 2.5, 90, 0, true), ISO(15, 110, 15, 135, 20, false);
        final double x,y,z; final float yaw,pitch; final boolean orthographic;
        View(double x,double y,double z,float yaw,float pitch,boolean orthographic) {
            this.x=x;this.y=y;this.z=z;this.yaw=yaw;this.pitch=pitch;this.orthographic=orthographic;
        }
    }

    private static final class Job {
        final Path input, out; boolean loaded; int wait, view;
        Job(Path input, Path out) { this.input=input; this.out=out; }

        void load(Minecraft client) throws Exception {
            CompoundTag root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            CompoundTag regions = root.getCompoundOrEmpty("Regions");
            if (regions.keySet().isEmpty()) throw new IllegalArgumentException("No litematic regions");
            CompoundTag region = regions.getCompoundOrEmpty(regions.keySet().iterator().next());
            CompoundTag size = region.getCompoundOrEmpty("Size");
            int sx=Math.abs(size.getIntOr("x", 0)), sy=Math.abs(size.getIntOr("y", 0)), sz=Math.abs(size.getIntOr("z", 0));
            ListTag paletteNbt = region.getListOrEmpty("BlockStatePalette");
            List<BlockState> palette = new ArrayList<>();
            for (int i=0;i<paletteNbt.size();i++) palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, paletteNbt.getCompoundOrEmpty(i)));
            long[] packed=region.getLongArray("BlockStates").orElseThrow(); int bits=Math.max(2, 32-Integer.numberOfLeadingZeros(palette.size()-1)); long mask=(1L<<bits)-1;
            BlockPos origin=new BlockPos(0,100,0);
            for (int y=0;y<sy;y++) for (int z=0;z<sz;z++) for (int x=0;x<sx;x++) {
                int n=(y*sz+z)*sx+x, start=n*bits, word=start>>>6, shift=start&63;
                long value=packed[word]>>>shift; if (shift+bits>64) value|=packed[word+1]<<(64-shift);
                client.level.setBlock(origin.offset(x,y,z), palette.get((int)(value&mask)), 19);
            }
            ListTag tiles=region.getListOrEmpty("TileEntities");
            for (int i=0;i<tiles.size();i++) {
                CompoundTag tag=tiles.getCompoundOrEmpty(i).copy(); BlockPos p=origin.offset(tag.getIntOr("x",0),tag.getIntOr("y",0),tag.getIntOr("z",0));
                tag.putInt("x",p.getX());tag.putInt("y",p.getY());tag.putInt("z",p.getZ());
                BlockEntity be=BlockEntity.loadStatic(p,client.level.getBlockState(p),tag,client.level.registryAccess());
                if (be!=null) client.level.setBlockEntity(be);
            }
            Files.createDirectories(out); if (!client.gui.hud.isHidden()) client.gui.hud.toggle(); client.options.fov().set(50);
            client.levelRenderer.resetLevelRenderData(); loaded=true;
            System.out.printf("Loaded %dx%dx%d palette=%d tiles=%d%n",sx,sy,sz,palette.size(),tiles.size());
        }

        void capture(Minecraft client,String name) throws Exception {
            Path file=out.resolve("mcoo_"+name+".png");
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                try (NativeImage owned = image) { owned.writeToFile(file); }
                catch (Exception error) { error.printStackTrace(); }
                System.out.println("WROTE " + file);
            });
        }
    }
}
