package com.rsegordon.poc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

/** Minimal proof: populate a ClientWorld, let the normal WorldRenderer draw it, copy its framebuffer. */
public final class OffscreenRenderer {
    private static Job job;
    private static boolean worldStartRequested;
    static { ClientTickEvents.END_CLIENT_TICK.register(OffscreenRenderer::tick); }
    private OffscreenRenderer() {}

    public static void arm(String input, String output) { job = new Job(Path.of(input), Path.of(output)); }

    private static void tick(MinecraftClient client) {
        if (job == null) return;
        if (client.world == null) {
            // Starting an integrated server while the resource reload overlay is active deadlocks
            // MinecraftClient.startIntegratedServer(), which waits for that overlay to disappear.
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
            View view = View.values()[Math.min(job.view, View.values().length - 1)];
            client.player.setPosition(view.x, view.y, view.z);
            client.player.setYaw(view.yaw); client.player.setPitch(view.pitch);
            if (++job.wait < 35) return; // chunk rebuild + one fully rendered frame
            job.capture(client, view.name().toLowerCase());
            job.view++; job.wait = 0;
            if (job.view == View.values().length) { System.out.println("LITEMATIC_RENDER_DONE " + job.out); client.scheduleStop(); job = null; worldStartRequested = false; }
        } catch (Exception error) { error.printStackTrace(); client.scheduleStop(); job = null; }
    }

    private enum View {
        TOP(2.5, 124, -2.5, 0, 90), FRONT(2.5, 104, 30, 180, 0),
        SIDE(30, 104, -2.5, 90, 0), ISO(24, 119, 20, 135, 28);
        final double x,y,z; final float yaw,pitch;
        View(double x,double y,double z,float yaw,float pitch) { this.x=x;this.y=y;this.z=z;this.yaw=yaw;this.pitch=pitch; }
    }

    private static final class Job {
        final Path input, out; boolean loaded; int wait, view;
        Job(Path input, Path out) { this.input=input; this.out=out; }

        void load(MinecraftClient client) throws Exception {
            NbtCompound root = NbtIo.readCompressed(input, NbtSizeTracker.ofUnlimitedBytes());
            NbtCompound regions = root.getCompound("Regions");
            if (regions.getKeys().isEmpty()) throw new IllegalArgumentException("No litematic regions");
            NbtCompound region = regions.getCompound(regions.getKeys().iterator().next());
            NbtCompound size = region.getCompound("Size");
            int sx=Math.abs(size.getInt("x")), sy=Math.abs(size.getInt("y")), sz=Math.abs(size.getInt("z"));
            NbtList paletteNbt = region.getList("BlockStatePalette", NbtElement.COMPOUND_TYPE);
            List<BlockState> palette = new ArrayList<>();
            for (int i=0;i<paletteNbt.size();i++) palette.add(NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), paletteNbt.getCompound(i)));
            long[] packed=region.getLongArray("BlockStates"); int bits=Math.max(2, 32-Integer.numberOfLeadingZeros(palette.size()-1)); long mask=(1L<<bits)-1;
            BlockPos origin=new BlockPos(0,100,0);
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
            Files.createDirectories(out); client.options.hudHidden=true; client.options.getFov().setValue(30);
            client.worldRenderer.reload(); loaded=true;
            System.out.printf("Loaded %dx%dx%d palette=%d tiles=%d%n",sx,sy,sz,palette.size(),tiles.size());
        }

        void capture(MinecraftClient client,String name) throws Exception {
            Path file=out.resolve("mcoo_"+name+".png");
            try (NativeImage image=ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) { image.writeTo(file); }
            System.out.println("WROTE " + file);
        }
    }
}
