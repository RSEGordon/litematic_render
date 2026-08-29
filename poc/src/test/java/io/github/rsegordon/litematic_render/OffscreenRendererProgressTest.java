package io.github.rsegordon.litematic_render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OffscreenRendererProgressTest {
    @Test
    void progressIsBoundedMonotonicAndCoversTenViews() {
        var reporter=new OffscreenRenderer.ProgressReporter();
        reporter.emit(0,"START",null,"start");
        int progress=40;
        for (OffscreenRenderer.View view:OffscreenRenderer.View.values())
            reporter.viewDone(view,Math.min(75,progress+=3));
        reporter.emit(100,"DONE",null,"done");
        assertEquals(100,reporter.progress());
        assertEquals(10,reporter.completedViews().size());
        assertTrue(reporter.completedViews().containsAll(java.util.List.of(OffscreenRenderer.View.values())));
    }
}
