package io.github.rsegordon.litematic_render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class OffscreenRendererChunkCoverageTest {
    @Test
    void requiredChunksUseInclusiveOccupiedBlocksAndCenteredBounds() {
        var chunks = OffscreenRenderer.Job.requiredChunks(-16, 16, -16, 16);

        assertEquals(4, chunks.size());
        assertTrue(chunks.contains(new ChunkPos(-1, -1)));
        assertTrue(chunks.contains(new ChunkPos(0, 0)));
    }

    @Test
    void vanillaTrackingAcceptsBoundaryAndRejectsCylindricalCorner() {
        ChunkPos camera = new ChunkPos(0, 0);
        assertTrue(OffscreenRenderer.Job.outsideChunks(
                Set.of(new ChunkPos(0, 0), new ChunkPos(9, 0)), camera, 10).isEmpty());
        assertFalse(OffscreenRenderer.Job.outsideChunks(
                Set.of(new ChunkPos(10, 10)), camera, 10).isEmpty());
    }

    @Test
    void centeredPlacementImprovesCoverageOverPositiveQuadrantPlacement() {
        var centered = OffscreenRenderer.Job.requiredChunks(-160, 160, -160, 160);
        var oldPlacement = OffscreenRenderer.Job.requiredChunks(0, 320, 0, 320);
        ChunkPos camera = new ChunkPos(0, 0);

        int centeredOutside = OffscreenRenderer.Job.outsideChunks(centered, camera, 12).size();
        int oldOutside = OffscreenRenderer.Job.outsideChunks(oldPlacement, camera, 12).size();
        assertTrue(centeredOutside < oldOutside);
    }

    @Test
    void chunkReadyBarrierWaitsForTheFinalChunk() {
        var required = OffscreenRenderer.Job.requiredChunks(0, 80, 0, 64);
        assertEquals(20, required.size());
        Set<ChunkPos> loaded = new HashSet<>(required);
        ChunkPos last = loaded.iterator().next();
        loaded.remove(last);

        assertEquals(1, OffscreenRenderer.Job.missingChunks(required, loaded::contains).size());
        loaded.add(last);
        assertTrue(OffscreenRenderer.Job.missingChunks(required, loaded::contains).isEmpty());
    }

    @Test
    void rendererDefinesAndValidatesAllTenViews() throws Exception {
        Class<?> viewClass = Class.forName("io.github.rsegordon.litematic_render.OffscreenRenderer$View");
        assertEquals(10, viewClass.getEnumConstants().length);
    }
}
