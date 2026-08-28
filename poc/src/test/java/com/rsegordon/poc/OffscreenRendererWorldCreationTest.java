package com.rsegordon.poc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OffscreenRendererWorldCreationTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/com/rsegordon/poc/OffscreenRenderer.java");

    @Test
    void createsVanillaTheVoidFlatWorldInsteadOfOpeningSavedOverworld() throws Exception {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("preset=the_void generator=FlatLevelSource"));
        assertTrue(source.contains(".createFreshLevel("));
        assertTrue(source.contains("FlatLevelGeneratorPresets.THE_VOID"));
        assertTrue(source.contains("new FlatLevelSource(theVoid)"));
        assertFalse(source.contains(".openWorld(\"World\""));
        assertFalse(source.contains("minecraft:overworld"));
    }
}
