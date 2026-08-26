package com.rsegordon.poc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    private static final float ORTHO_HALF_SIZE = 5.25f;
    private static Job job;
    private static boolean worldStartRequested;
    private static View activeView;
    static {
        ClientTickEvents.END_CLIENT_TICK.register(OffscreenRenderer::tick);
        WorldRenderEvents.START.register(context -> {
            if (activeView == null || !activeView.orthographic) return;
            MinecraftClient client = MinecraftClient.getInstance();
            float aspect = (float) client.getWindow().getFramebufferWidth()
                    / client.getWindow().getFramebufferHeight();
            Matrix4f projection = context.projectionMatrix().setOrtho(
                    -ORTHO_HALF_SIZE * aspect, ORTHO_HALF_SIZE * aspect,
                    -ORTHO_HALF_SIZE, ORTHO_HALF_SIZE, 0.05f, 256.0f);
            RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_DISTANCE);
        });
    }
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
            activeView = view;
            client.player.setPosition(view.x, view.y - client.player.getStandingEyeHeight(), view.z);
            client.player.setYaw(view.yaw); client.player.setPitch(view.pitch);
            if (++job.wait < 35) return; // chunk rebuild + one fully rendered frame
            job.capture(client, view.name().toLowerCase());
            job.view++; job.wait = 0;
            if (job.view == View.values().length) { System.out.println("LITEMATIC_RENDER_DONE " + job.out); client.scheduleStop(); job = null; activeView = null; worldStartRequested = false; }
        } catch (Exception error) { error.printStackTrace(); client.scheduleStop(); job = null; activeView = null; }
    }

    private enum View {
        // All four views use orthographic projection so iso is a flat technical
        // drawing without perspective distortion (matches engineering style).
        // TOP/FRONT/SIDE are the canonical 3-view orthographic. ISO uses the
        // same ortho but with the camera rotated 45° yaw / 30° pitch so it
        // reads as a single axonometric view next to the cardinal views.
        TOP(2.5, 110, 2.5, 0, 90, true), FRONT(2.5, 104, 12, 180, 0, true),
        SIDE(12, 104, 2.5, 90, 0, true), ISO(15, 110, 15, 135, 30, true);
        final double x,y,z; final float yaw,pitch; final boolean orthographic;
        View(double x,double y,double z,float yaw,float pitch,boolean orthographic) {
            this.x=x;this.y=y;this.z=z;this.yaw=yaw;this.pitch=pitch;this.orthographic=orthographic;
        }
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
            // The vanilla the_void flat preset deliberately adds a stone spawn platform.
            // Remove only the preset terrain below the model so orthographic top renders
            // contain the litematic and the white background, not the spawn platform.
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
            Files.createDirectories(out); client.options.hudHidden=true; client.options.getFov().setValue(50);
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
