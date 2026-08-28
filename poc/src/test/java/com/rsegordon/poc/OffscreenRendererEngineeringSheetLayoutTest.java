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
        assertEquals(normal.principalReservedRect(),hugeAxons.principalReservedRect());
        for (OffscreenRenderer.View view:OffscreenRenderer.View.values())
            if (!view.name().startsWith("AXON_"))
                assertEquals(normal.placement(view),hugeAxons.placement(view),view.name());
    }

    @Test
    void axonsFitAssignedSlotsAndSharePrincipalGridAnchors() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(48,16,16);
        OffscreenRenderer.ViewPlacement right=layout.placement(OffscreenRenderer.View.LEFT_Z_NEG);
        OffscreenRenderer.ViewPlacement back=layout.placement(OffscreenRenderer.View.BACK_X_NEG);
        OffscreenRenderer.ViewPlacement top=layout.placement(OffscreenRenderer.View.TOP_X_UP);
        OffscreenRenderer.ViewPlacement bottom=layout.placement(OffscreenRenderer.View.BOTTOM_X_UP);
        for (Map.Entry<OffscreenRenderer.View,OffscreenRenderer.CornerSlot> entry
                :layout.axonSlotAssignments().entrySet()) {
            OffscreenRenderer.ViewPlacement placement=layout.placement(entry.getKey());
            OffscreenRenderer.LayoutRect slot=layout.axonSlots().get(entry.getValue());
            assertTrue(slot.contains(placement),entry.getKey().name());
            assertTrue(layout.drawingArea().contains(placement),entry.getKey().name());
            OffscreenRenderer.ViewPlacement column=entry.getValue()==OffscreenRenderer.CornerSlot.TOP_LEFT
                    ||entry.getValue()==OffscreenRenderer.CornerSlot.BOTTOM_LEFT?right:back;
            OffscreenRenderer.ViewPlacement row=entry.getValue()==OffscreenRenderer.CornerSlot.TOP_LEFT
                    ||entry.getValue()==OffscreenRenderer.CornerSlot.TOP_RIGHT?top:bottom;
            assertTrue(Math.abs(column.centerX()-placement.centerX())<=1,entry.getKey().name());
            assertTrue(Math.abs(row.centerY()-placement.centerY())<=1,entry.getKey().name());
        }
    }

    @Test
    void principalGapsAreTwentyPercentWider() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(16,16,48);
        assertEquals(43,layout.principalGapX());
        assertEquals(58,layout.principalGapY());
    }

    @Test
    void sheetUiScaleClampsAndScalesEveryMaterialMetric() {
        OffscreenRenderer.SheetUiMetrics small=OffscreenRenderer.sheetUiMetrics(1024,768,16);
        OffscreenRenderer.SheetUiMetrics standard=OffscreenRenderer.sheetUiMetrics(4096,3072,16);
        OffscreenRenderer.SheetUiMetrics large=OffscreenRenderer.sheetUiMetrics(8192,6144,16);
        assertEquals(0.85,small.scale());
        assertEquals(1.0,standard.scale());
        assertEquals(2.0,large.scale());
        assertTrue(large.materialsBodyFont()>standard.materialsBodyFont());
        assertTrue(large.iconSize()>standard.iconSize());
        assertTrue(large.rowHeight()>standard.rowHeight());
        assertTrue(large.columnGap()>standard.columnGap());
        assertTrue(large.materialsHeight()>standard.materialsHeight());
    }

    @Test
    void v142ModelShapesKeepEveryAxonAnchoredAndCollisionFree() {
        int[][] dimensions={{16,16,48},{48,16,16},{12,64,12},{128,16,12}};
        for (int[] dimension:dimensions) {
            OffscreenRenderer.EngineeringSheetLayout layout=layout(dimension[0],dimension[1],dimension[2]);
            for (OffscreenRenderer.View axon:OffscreenRenderer.View.values()) {
                if (!axon.name().startsWith("AXON_")) continue;
                OffscreenRenderer.ViewPlacement placement=layout.placement(axon);
                assertTrue(layout.drawingArea().contains(placement),axon+" "+java.util.Arrays.toString(dimension));
                for (OffscreenRenderer.View principal:OffscreenRenderer.View.values()) {
                    if (principal.name().startsWith("AXON_")) continue;
                    OffscreenRenderer.ViewPlacement p=layout.placement(principal);
                    int overlapWidth=Math.max(0,Math.min(placement.right(),p.right())-Math.max(placement.x(),p.x()));
                    int overlapHeight=Math.max(0,Math.min(placement.bottom(),p.bottom())-Math.max(placement.y(),p.y()));
                    assertEquals(0,overlapWidth*overlapHeight,axon+" vs "+principal);
                }
            }
        }
    }

    @Test
    void uiMetricsGrowAcrossRequestedCanvasLevels() {
        int[] levels={2048,3072,4096};
        int previousFont=0,previousIcon=0;
        for (int level:levels) {
            OffscreenRenderer.SheetUiMetrics ui=OffscreenRenderer.sheetUiMetrics(level,level,24);
            assertTrue(ui.materialsBodyFont()>=previousFont);
            assertTrue(ui.iconSize()>=previousIcon);
            previousFont=ui.materialsBodyFont();
            previousIcon=ui.iconSize();
        }
    }

    @Test
    void hugeAxonsAreFittedWithoutPrincipalIntersection() {
        Map<OffscreenRenderer.View,Dimension> values=sizes(48,16,16);
        for (OffscreenRenderer.View view:OffscreenRenderer.View.values())
            if (view.name().startsWith("AXON_")) values.put(view,new Dimension(4000,3000));
        OffscreenRenderer.EngineeringSheetLayout layout=build(values);
        for (OffscreenRenderer.View axon:OffscreenRenderer.View.values()) {
            if (!axon.name().startsWith("AXON_")) continue;
            OffscreenRenderer.ViewPlacement a=layout.placement(axon);
            for (OffscreenRenderer.View principal:OffscreenRenderer.View.values()) {
                if (principal.name().startsWith("AXON_")) continue;
                OffscreenRenderer.ViewPlacement p=layout.placement(principal);
                int width=Math.max(0,Math.min(a.right(),p.right())-Math.max(a.x(),p.x()));
                int height=Math.max(0,Math.min(a.bottom(),p.bottom())-Math.max(a.y(),p.y()));
                assertEquals(0,width*height,axon+" vs "+principal);
            }
        }
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
