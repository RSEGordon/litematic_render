package io.github.rsegordon.litematic_render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OffscreenRendererWorldCreationTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/rsegordon/litematic_render/OffscreenRenderer.java");

    @Test
    void createsVanillaTheVoidFlatWorldInsteadOfOpeningSavedOverworld() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("preset=THE_VOID generator=FlatLevelSource"));
        assertTrue(source.contains(".createFreshLevel("));
        assertTrue(source.contains("configuredWorldName()"));
        assertTrue(source.contains("deleteStaleRenderWorld(worldName)"));
        assertTrue(source.contains("FlatLevelGeneratorPresets.THE_VOID"));
        assertTrue(source.contains("new FlatLevelSource(theVoid)"));
        assertFalse(source.contains(".openWorld(\"World\""));
        assertFalse(source.contains("minecraft:overworld"));
    }

    @Test
    void clearsSpawnPlatformOnceBeforeLoadingTheLitematic() throws Exception {
        String source = Files.readString(SOURCE);

        int clear = source.indexOf("if (!job.platformCleared)");
        int load = source.indexOf("job.load(client)");
        assertTrue(clear >= 0 && clear < load);
        assertFalse(source.contains("int range = RENDER_DISTANCE_CHUNKS * 16"));
        assertTrue(source.contains("originX - 16"));
        assertTrue(source.contains("LITEMATIC_RENDER_PLATFORM_CLEARED"));
        assertTrue(source.contains("new BlockPos(x, y, z)"));
        assertTrue(source.contains("Blocks.AIR.defaultBlockState()"));
        assertTrue(source.contains("job.platformCleared = true"));
    }
}
