package com.rsegordon.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OffscreenRendererPrincipalProjectionTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void cameraBasisProjectsSixViewsForLongZModel() {
        assertPrincipalSpans(16,16,48,
                new double[][]{{48,16},{16,16},{16,16},{48,16},{48,16},{48,16}});
    }

    @Test
    void cameraBasisProjectsSixViewsForDistinctDimensions() {
        assertPrincipalSpans(40,12,9,
                new double[][]{{9,12},{40,12},{40,12},{9,12},{9,40},{9,40}});
    }

    private static void assertPrincipalSpans(double sizeX,double sizeY,double sizeZ,
                                              double[][] expected) {
        float[][] angles={{90,0},{0,0},{180,0},{270,0},{90,90},{90,-90}};
        for (int index=0;index<angles.length;index++) {
            OffscreenRenderer.ProjectedSpan span=OffscreenRenderer.projectedSpan(
                    0,0,0,sizeX,sizeY,sizeZ,angles[index][0],angles[index][1]);
            assertEquals(expected[index][0],span.width(),EPSILON,"horizontal view "+index);
            assertEquals(expected[index][1],span.height(),EPSILON,"vertical view "+index);
        }
    }
}
