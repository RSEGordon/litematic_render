package com.rsegordon.poc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OffscreenRendererVoidTerrainTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void detectsOrdinaryTerrainButNotAir() {
        assertTrue(OffscreenRenderer.Job.isOrdinaryTerrain(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertTrue(OffscreenRenderer.Job.isOrdinaryTerrain(Blocks.DIRT.defaultBlockState()));
        assertTrue(OffscreenRenderer.Job.isOrdinaryTerrain(Blocks.STONE.defaultBlockState()));
        assertFalse(OffscreenRenderer.Job.isOrdinaryTerrain(Blocks.AIR.defaultBlockState()));
    }

    @Test
    void onlyRecognizesAFilledSingleLayerSpawnPlatform() {
        assertTrue(OffscreenRenderer.Job.isRecognizedVoidSpawnPlatform(
                1089, -16, 16, -61, -61, -16, 16));
        assertFalse(OffscreenRenderer.Job.isRecognizedVoidSpawnPlatform(
                1090, -16, 16, -61, -61, -16, 16));
        assertFalse(OffscreenRenderer.Job.isRecognizedVoidSpawnPlatform(
                1089, -16, 16, -61, -60, -16, 16));
    }
}
