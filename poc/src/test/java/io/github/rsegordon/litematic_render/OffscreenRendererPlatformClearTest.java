package io.github.rsegordon.litematic_render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

class OffscreenRendererPlatformClearTest {
    @Test
    void fillsTheWholeYZeroPlatformWithAir() {
        List<BlockPos> positions = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();

        BlockState mockedAir = null;
        int cleared = OffscreenRenderer.clearSpawnPlatform(-2, 2, -7, -1, 3, mockedAir, (pos, state) -> {
            positions.add(pos);
            states.add(state);
        });

        assertEquals(25, cleared);
        assertEquals(cleared, positions.size());
        assertTrue(positions.contains(new BlockPos(-2, -7, -1)));
        assertTrue(positions.contains(new BlockPos(2, -7, 3)));
        assertTrue(positions.stream().allMatch(pos -> pos.getY() == -7));
        assertTrue(states.stream().allMatch(state -> state == mockedAir));
    }
}
