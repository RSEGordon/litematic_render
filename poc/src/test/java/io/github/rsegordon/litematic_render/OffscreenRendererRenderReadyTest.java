package io.github.rsegordon.litematic_render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OffscreenRendererRenderReadyTest {
    @Test
    void loadingScreenBlocksCaptureEvenWhenChunksAreReady() {
        var state=new OffscreenRenderer.RenderReadyState(5);
        assertFalse(state.observe(true,true,false,true,true));
        assertEquals(0,state.stableFrames());
    }

    @Test
    void captureStartsAfterFiveConsecutiveWorldFrames() {
        var state=new OffscreenRenderer.RenderReadyState(5);
        assertFalse(state.observe(true,true,false,true,true));
        for (int frame=1;frame<5;frame++)
            assertFalse(state.observe(true,true,true,true,true));
        assertTrue(state.observe(true,true,true,true,true));
        assertEquals(5,state.stableFrames());
    }

    @Test
    void unstableFrameResetsConsecutiveCount() {
        var state=new OffscreenRenderer.RenderReadyState(5);
        state.observe(true,true,true,true,true);
        state.observe(true,true,true,true,true);
        state.observe(true,true,false,true,true);
        assertEquals(0,state.stableFrames());
        assertFalse(state.observe(true,true,true,true,true));
        assertEquals(1,state.stableFrames());
    }
}
