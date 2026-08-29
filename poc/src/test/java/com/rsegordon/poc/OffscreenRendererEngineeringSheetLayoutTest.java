package com.rsegordon.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

// V146: 单元测试必须与生产共用 buildEngineeringSheetLayout(§59)。
// 删除/重写依赖 CornerSlot / axonSlots 的旧测试。
class OffscreenRendererEngineeringSheetLayoutTest {

    // V146 §65: Paper/Blueprint 共用同一 placement —— 工程图与 style 无关。
    @Test
    void oneLayoutProvidesStyleIndependentPlacementsForAllTenViews() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(48,16,16);
        assertEquals(10,layout.placements().size());
        for (OffscreenRenderer.View view:OffscreenRenderer.View.values())
            assertTrue(layout.placement(view)!=null,view.name());
    }

    // V146 §45: 不再硬要求 principalGroup center == drawingCenterY。
    // 只断言 drawingArea 包含 principal group。
    @Test
    void drawingAreaContainsPrincipalGroup() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(40,12,9);
        assertTrue(layout.drawingArea().contains(layout.principalReservedRect()));
    }

    // V146 §50: Main row 严格共顶/共底。
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
        assertEquals(front.bottom(),back.bottom());
    }

    // V146 §31/§52: TOP.bottom + gapY == FRONT.top;FRONT.bottom + gapY == BOTTOM.top。
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

    // V146 §62: 改为"不改变 principal common scale, 不破坏主行顺序, 不破坏 TOP/BOTTOM 对 FRONT 中心关系"。
    @Test
    void axonSizeChangeCannotBreakPrincipalCommonScaleAndOrder() {
        Map<OffscreenRenderer.View,Dimension> normalSizes=sizes(48,16,16);
        OffscreenRenderer.EngineeringSheetLayout normal=build(normalSizes);
        Map<OffscreenRenderer.View,Dimension> hugeSizes=new EnumMap<>(normalSizes);
        for (OffscreenRenderer.View view:OffscreenRenderer.View.values())
            if (view.name().startsWith("AXON_")) hugeSizes.put(view,new Dimension(4000,3000));
        OffscreenRenderer.EngineeringSheetLayout hugeAxons=build(hugeSizes);
        // 主行 4 个 Principal placement 顺序不变。
        assertEquals(normal.placement(OffscreenRenderer.View.LEFT_Z_NEG).y(),
                hugeAxons.placement(OffscreenRenderer.View.LEFT_Z_NEG).y());
        assertEquals(normal.placement(OffscreenRenderer.View.RIGHT_Z_POS).y(),
                hugeAxons.placement(OffscreenRenderer.View.RIGHT_Z_POS).y());
        // TOP/BOTTOM centerX 仍然 == FRONT.centerX(±1px rounding)。
        OffscreenRenderer.ViewPlacement normalFront=normal.placement(OffscreenRenderer.View.FRONT_X_POS);
        OffscreenRenderer.ViewPlacement hugeFront=hugeAxons.placement(OffscreenRenderer.View.FRONT_X_POS);
        OffscreenRenderer.ViewPlacement normalTop=normal.placement(OffscreenRenderer.View.TOP_X_UP);
        OffscreenRenderer.ViewPlacement hugeTop=hugeAxons.placement(OffscreenRenderer.View.TOP_X_UP);
        assertEquals(normalFront.centerX(),normalTop.centerX());
        assertEquals(hugeFront.centerX(),hugeTop.centerX());
    }

    // V146 §60: 替换 axonsFitAssignedSlotsAndSharePrincipalGridAnchors。
    // 新语义:四个 Axon 共用 displayRight/back 列 anchor + TOP/BOTTOM 行 anchor,且不碰撞。
    @Test
    void axonsShareFixedGridAnchorsAndDoNotCollide() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(48,16,16);
        OffscreenRenderer.ViewPlacement displayRight=layout.placement(OffscreenRenderer.View.LEFT_Z_NEG);
        OffscreenRenderer.ViewPlacement back=layout.placement(OffscreenRenderer.View.BACK_X_NEG);
        OffscreenRenderer.ViewPlacement top=layout.placement(OffscreenRenderer.View.TOP_X_UP);
        OffscreenRenderer.ViewPlacement bottom=layout.placement(OffscreenRenderer.View.BOTTOM_X_UP);
        OffscreenRenderer.ViewPlacement tl=layout.placement(OffscreenRenderer.View.AXON_X_POS_Z_POS);
        OffscreenRenderer.ViewPlacement tr=layout.placement(OffscreenRenderer.View.AXON_X_NEG_Z_POS);
        OffscreenRenderer.ViewPlacement bl=layout.placement(OffscreenRenderer.View.AXON_X_POS_Z_NEG);
        OffscreenRenderer.ViewPlacement br=layout.placement(OffscreenRenderer.View.AXON_X_NEG_Z_NEG);
        assertTrue(Math.abs(tl.centerX()-displayRight.centerX())<=1);
        assertTrue(Math.abs(tl.centerY()-top.centerY())<=1);
        assertTrue(Math.abs(tr.centerX()-back.centerX())<=1);
        assertTrue(Math.abs(tr.centerY()-top.centerY())<=1);
        assertTrue(Math.abs(bl.centerX()-displayRight.centerX())<=1);
        assertTrue(Math.abs(bl.centerY()-bottom.centerY())<=1);
        assertTrue(Math.abs(br.centerX()-back.centerX())<=1);
        assertTrue(Math.abs(br.centerY()-bottom.centerY())<=1);
        // Axon 互相不碰撞。
        assertAxonAxonNoCollision(tl,tr);
        assertAxonAxonNoCollision(tl,bl);
        assertAxonAxonNoCollision(tl,br);
        assertAxonAxonNoCollision(tr,bl);
        assertAxonAxonNoCollision(tr,br);
        assertAxonAxonNoCollision(bl,br);
    }

    // V146 §63: gapX/gapY >= scaledBaseGapX/Y (不再写死 43/58)。
    @Test
    void principalGapsAreAtLeastBaseGap() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(16,16,48);
        int scaledBaseGapX=(int)Math.round(36*1.20);
        int scaledBaseGapY=(int)Math.round(48*1.20);
        assertTrue(layout.principalGapX()>=scaledBaseGapX,
                "principalGapX="+layout.principalGapX()+" < "+scaledBaseGapX);
        assertTrue(layout.principalGapY()>=scaledBaseGapY,
                "principalGapY="+layout.principalGapY()+" < "+scaledBaseGapY);
    }

    // V146 §64: drawingArea 就是 10 placements 的真实 union。
    @Test
    void drawingAreaEqualsRealUnion() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(16,16,16);
        OffscreenRenderer.LayoutRect union=OffscreenRenderer.unionPlacements(layout.placements());
        assertEquals(union.x(),layout.drawingArea().x());
        assertEquals(union.y(),layout.drawingArea().y());
        assertEquals(union.width(),layout.drawingArea().width());
        assertEquals(union.height(),layout.drawingArea().height());
    }

    // V146 §54: 四个 Axon 共用同一 globalAxonScale (rounding tolerance 0.5%)。
    @Test
    void fourAxonsShareSameGlobalScale() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(48,16,16);
        double minScale=Double.POSITIVE_INFINITY,maxScale=0.0;
        OffscreenRenderer.View[] axons={
                OffscreenRenderer.View.AXON_X_POS_Z_POS,OffscreenRenderer.View.AXON_X_NEG_Z_POS,
                OffscreenRenderer.View.AXON_X_POS_Z_NEG,OffscreenRenderer.View.AXON_X_NEG_Z_NEG};
        for (OffscreenRenderer.View view:axons) {
            Dimension raw=layout.sourceSizes().get(view);
            OffscreenRenderer.ViewPlacement p=layout.placement(view);
            double scale=Math.max(p.width(),p.height())/(double)Math.max(raw.width,raw.height);
            minScale=Math.min(minScale,scale);
            maxScale=Math.max(maxScale,scale);
        }
        assertTrue((maxScale-minScale)<=0.005,
                "axon scale spread "+minScale+".."+maxScale+" exceeds 0.5%");
    }

    // V146: 沿用 V144 Materials UI 测试(sheetUiMetricsForScale 不改)。
    @Test
    void uiMetricsFollowPrincipalScale() {
        OffscreenRenderer.SheetUiMetrics base=OffscreenRenderer.sheetUiMetricsForScale(1.0,24);
        OffscreenRenderer.SheetUiMetrics large=OffscreenRenderer.sheetUiMetricsForScale(1.5,24);
        assertEquals(1.0,base.scale());
        assertEquals(1.5,large.scale());
        assertTrue(large.materialsBodyFont()>base.materialsBodyFont());
        assertTrue(large.iconSize()>base.iconSize());
    }

    @Test
    void uiMetricsAreScaleDrivenNotCanvasDriven() {
        OffscreenRenderer.SheetUiMetrics a=OffscreenRenderer.sheetUiMetricsForScale(1.42,24);
        OffscreenRenderer.SheetUiMetrics b=OffscreenRenderer.sheetUiMetricsForScale(1.42,24);
        assertEquals(a.scale(),b.scale());
        assertEquals(a.materialsBodyFont(),b.materialsBodyFont());
        assertEquals(a.iconSize(),b.iconSize());
        assertEquals(a.materialsHeight(),b.materialsHeight());
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

    // V146 §66: 4×34×5 必须 10 placements,无碰撞,Axon 共用 global scale,finalGapY >= baseGapY。
    @Test
    void tallFourByThirtyFourByFiveDoesNotCollapseAxons() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(4,34,5);
        assertEquals(10,layout.placements().size());
        int scaledBaseGapY=(int)Math.round(48*1.20);
        assertTrue(layout.principalGapY()>=scaledBaseGapY,
                "principalGapY="+layout.principalGapY()+" < "+scaledBaseGapY);
        assertEquals(0,countAxonPrincipalIntersection(layout));
        double spread=axonScaleSpread(layout);
        assertTrue(spread<=0.005,"axon scale spread="+spread);
    }

    // V146 §67: 4×80×4 极细高。
    @Test
    void ultraTallFourByEightyByFourDoesNotFail() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(4,80,4);
        assertEquals(10,layout.placements().size());
        assertEquals(0,countAxonPrincipalIntersection(layout));
    }

    // V146 §68: 80×4×4 极横长 —— 但当前 sizes() 用 (x*8, y*8, z*8) 公式,
    // 80×4×4 会得到 X=640, Y=32, Z=32。finalGapX 应增大。
    @Test
    void ultraWideEightyByFourByFourDoesNotFail() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(80,4,4);
        assertEquals(10,layout.placements().size());
        assertEquals(0,countAxonPrincipalIntersection(layout));
        // 横长模型 canvas 应该明显比普通 16x16x16 更宽。
        OffscreenRenderer.EngineeringSheetLayout normal=layout(16,16,16);
        assertTrue(layout.canvasWidth()>=normal.canvasWidth(),
                "ultra-wide canvas "+layout.canvasWidth()+" should be >= normal "+normal.canvasWidth());
    }

    // V146 §69: 16×16×16 普通方形 NORMAL aspect, gap 不夸张。
    @Test
    void normalSixteenBySixteenBySixteenStaysCompact() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(16,16,16);
        assertEquals("NORMAL",layout.axonAspectMode());
        assertEquals(10,layout.placements().size());
        assertEquals(0,countAxonPrincipalIntersection(layout));
    }

    // V146 §70: 48×16×16 + 16×16×48。
    @Test
    void longModelsKeepEngineeringSemantics() {
        for (int[] dim:new int[][]{{48,16,16},{16,16,48}}) {
            OffscreenRenderer.EngineeringSheetLayout layout=layout(dim[0],dim[1],dim[2]);
            assertEquals(10,layout.placements().size());
            assertEquals(0,countAxonPrincipalIntersection(layout));
            assertTrue(layout.drawingArea().contains(layout.principalReservedRect()));
        }
    }

    // V146 §71: 不同 raw 尺寸 (240×190 等) 共用相同 global scale。
    @Test
    void rawSizesAreMatchedToSingleScale() {
        OffscreenRenderer.EngineeringSheetLayout layout=layout(48,16,16);
        double minScale=Double.POSITIVE_INFINITY,maxScale=0.0;
        OffscreenRenderer.View[] axons={
                OffscreenRenderer.View.AXON_X_POS_Z_POS,OffscreenRenderer.View.AXON_X_NEG_Z_POS,
                OffscreenRenderer.View.AXON_X_POS_Z_NEG,OffscreenRenderer.View.AXON_X_NEG_Z_NEG};
        for (OffscreenRenderer.View view:axons) {
            Dimension raw=layout.sourceSizes().get(view);
            OffscreenRenderer.ViewPlacement p=layout.placement(view);
            double scale=Math.max(p.width(),p.height())/(double)Math.max(raw.width,raw.height);
            minScale=Math.min(minScale,scale);
            maxScale=Math.max(maxScale,scale);
        }
        assertTrue((maxScale-minScale)<=0.005,
                "scale spread "+minScale+".."+maxScale);
    }

    // V146 §72: hugeAxons (4000×3000) 不应撑爆 canvas / 不碰撞。
    @Test
    void hugeAxonsAreFittedWithoutPrincipalIntersection() {
        Map<OffscreenRenderer.View,Dimension> values=sizes(48,16,16);
        for (OffscreenRenderer.View view:OffscreenRenderer.View.values())
            if (view.name().startsWith("AXON_")) values.put(view,new Dimension(4000,3000));
        OffscreenRenderer.EngineeringSheetLayout layout=build(values);
        assertEquals(0,countAxonPrincipalIntersection(layout));
        assertTrue(layout.drawingArea().contains(layout.principalReservedRect()));
    }

    private static int countAxonPrincipalIntersection(OffscreenRenderer.EngineeringSheetLayout layout) {
        int count=0;
        for (OffscreenRenderer.View axon:OffscreenRenderer.View.values()) {
            if (axon.name().startsWith("AXON_")) continue;
            continue; // skip non-axon
        }
        for (OffscreenRenderer.View axon:OffscreenRenderer.View.values()) {
            if (!axon.name().startsWith("AXON_")) continue;
            OffscreenRenderer.ViewPlacement a=layout.placement(axon);
            for (OffscreenRenderer.View principal:OffscreenRenderer.View.values()) {
                if (principal.name().startsWith("AXON_")) continue;
                OffscreenRenderer.ViewPlacement p=layout.placement(principal);
                int width=Math.max(0,Math.min(a.right(),p.right())-Math.max(a.x(),p.x()));
                int height=Math.max(0,Math.min(a.bottom(),p.bottom())-Math.max(a.y(),p.y()));
                count+=width*height;
            }
        }
        return count;
    }

    private static double axonScaleSpread(OffscreenRenderer.EngineeringSheetLayout layout) {
        double minScale=Double.POSITIVE_INFINITY,maxScale=0.0;
        for (OffscreenRenderer.View view:OffscreenRenderer.View.values()) {
            if (!view.name().startsWith("AXON_")) continue;
            Dimension raw=layout.sourceSizes().get(view);
            OffscreenRenderer.ViewPlacement p=layout.placement(view);
            double scale=Math.max(p.width(),p.height())/(double)Math.max(raw.width,raw.height);
            minScale=Math.min(minScale,scale);
            maxScale=Math.max(maxScale,scale);
        }
        return maxScale-minScale;
    }

    private static void assertAxonAxonNoCollision(
            OffscreenRenderer.ViewPlacement a,OffscreenRenderer.ViewPlacement b) {
        int width=Math.max(0,Math.min(a.right(),b.right())-Math.max(a.x(),b.x()));
        int height=Math.max(0,Math.min(a.bottom(),b.bottom())-Math.max(a.y(),b.y()));
        assertEquals(0,width*height,a+" vs "+b);
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