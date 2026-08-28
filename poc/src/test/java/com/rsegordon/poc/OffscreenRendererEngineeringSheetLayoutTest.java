package com.rsegordon.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OffscreenRendererEngineeringSheetLayoutTest {
    @Test
    void oneLayoutProvidesStyleIndependentPlacementsForAllTenViews() {
        OffscreenRenderer.EngineeringSheetLayout shared=layout(48,16,16);
        OffscreenRenderer.EngineeringSheetLayout paper=shared;
        OffscreenRenderer.EngineeringSheetLayout blueprint=shared;
        for (OffscreenRenderer.View view:OffscreenRenderer.View.values())
            assertSame(paper.placement(view),blueprint.placement(view),view.name());
        assertEquals(10,shared.placements().size());
    }

    @Test
    void principalGroupIsCenteredInDrawingArea() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(40,12,9);
        int groupCenter=(layout.principalGroupTop()+layout.principalGroupBottom())/2;
        assertTrue(Math.abs(groupCenter-layout.drawingCenterY())<=1);
    }

    @Test
    void mainRowUsesOneBaselineAndBottomEdge() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(16,16,48);
        OffscreenRenderer.ViewPlacement left=layout.placement(OffscreenRenderer.View.LEFT_Z_NEG);
        OffscreenRenderer.ViewPlacement front=layout.placement(OffscreenRenderer.View.FRONT_X_POS);
        OffscreenRenderer.ViewPlacement right=layout.placement(OffscreenRenderer.View.RIGHT_Z_POS);
        OffscreenRenderer.ViewPlacement back=layout.placement(OffscreenRenderer.View.BACK_X_NEG);
        assertEquals(left.y(),front.y());
        assertEquals(front.y(),right.y());
        assertEquals(right.y(),back.y());
        assertEquals(left.bottom(),front.bottom());
        assertEquals(front.bottom(),right.bottom());
        assertEquals(right.bottom(),back.bottom());
    }

    @Test
    void topAndBottomDependOnFrontWithFixedGap() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(48,16,16);
        OffscreenRenderer.ViewPlacement top=layout.placement(OffscreenRenderer.View.TOP_X_UP);
        OffscreenRenderer.ViewPlacement front=layout.placement(OffscreenRenderer.View.FRONT_X_POS);
        OffscreenRenderer.ViewPlacement bottom=layout.placement(OffscreenRenderer.View.BOTTOM_X_UP);
        assertEquals(front.centerX(),top.centerX());
        assertEquals(front.centerX(),bottom.centerX());
        assertEquals(front.y(),top.bottom()+layout.principalGapY());
        assertEquals(bottom.y(),front.bottom()+layout.principalGapY());
    }

    @Test
    void axonContentCannotMovePrincipalGroup() {
        Map<OffscreenRenderer.View,Dimension> normalSizes=sizes(48,16,16);
        OffscreenRenderer.EngineeringSheetLayout normal=build(normalSizes);
        for (OffscreenRenderer.View view:OffscreenRenderer.View.values())
            if (view.name().startsWith("AXON_")) normalSizes.put(view,new Dimension(4000,3000));
        OffscreenRenderer.EngineeringSheetLayout hugeAxons=build(normalSizes);
        assertEquals(normal.principalGroupTop(),hugeAxons.principalGroupTop());
        assertEquals(normal.principalMainRowY(),hugeAxons.principalMainRowY());
        assertEquals(normal.principalGroupBottom(),hugeAxons.principalGroupBottom());
    }

    private static OffscreenRenderer.EngineeringSheetLayout layout(int x,int y,int z) {
        return build(sizes(x,y,z));
    }

    private static OffscreenRenderer.EngineeringSheetLayout build(
            Map<OffscreenRenderer.View,Dimension> sizes) {
        return OffscreenRenderer.buildEngineeringSheetLayout(sizes,56,82,36,48,74,146);
    }

    private static Map<OffscreenRenderer.View,Dimension> sizes(int x,int y,int z) {
        Map<OffscreenRenderer.View,Dimension> sizes=new EnumMap<>(OffscreenRenderer.View.class);
        sizes.put(OffscreenRenderer.View.LEFT_Z_NEG,new Dimension(z*8,y*8));
        sizes.put(OffscreenRenderer.View.FRONT_X_POS,new Dimension(x*8,y*8));
        sizes.put(OffscreenRenderer.View.RIGHT_Z_POS,new Dimension(z*8,y*8));
        sizes.put(OffscreenRenderer.View.BACK_X_NEG,new Dimension(x*8,y*8));
        sizes.put(OffscreenRenderer.View.TOP_X_UP,new Dimension(z*8,x*8));
        sizes.put(OffscreenRenderer.View.BOTTOM_X_UP,new Dimension(z*8,x*8));
        sizes.put(OffscreenRenderer.View.AXON_X_POS_Z_POS,new Dimension(240,190));
        sizes.put(OffscreenRenderer.View.AXON_X_NEG_Z_POS,new Dimension(230,200));
        sizes.put(OffscreenRenderer.View.AXON_X_POS_Z_NEG,new Dimension(250,180));
        sizes.put(OffscreenRenderer.View.AXON_X_NEG_Z_NEG,new Dimension(220,210));
        return sizes;
    }
}
