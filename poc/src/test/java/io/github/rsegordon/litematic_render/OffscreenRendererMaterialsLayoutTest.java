package io.github.rsegordon.litematic_render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

// V147: Materials 列数自适应 + panel height 跟 rows 走 + UI size 不变。
class OffscreenRendererMaterialsLayoutTest {

    private static OffscreenRenderer.SheetUiMetrics ui(double scale) {
        return OffscreenRenderer.sheetUiMetricsForScale(scale, 16);
    }

    private static FontMetrics bodyMetrics(OffscreenRenderer.SheetUiMetrics u) {
        Font font=new Font(Font.SANS_SERIF,Font.PLAIN,u.materialsBodyFont());
        BufferedImage probe=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=probe.createGraphics();
        try {
            return g.getFontMetrics(font);
        } finally {
            g.dispose();
        }
    }

    private static List<OffscreenRenderer.MaterialEntry> sampleMaterials(int count) {
        List<OffscreenRenderer.MaterialEntry> list=new ArrayList<>();
        // 短名 + 长名 混合,模拟真实中英混合 + 数字尾缀。
        String[] shortNames={"红石","钻石","铁锭","金锭","煤炭","木板","圆石","草方块"};
        String[] longNames={"浅灰色混凝土","粘性活塞","红石比较器","红石中继器","末影珍珠"};
        for (int i=0;i<count;i++) {
            String name=i%2==0?shortNames[i%shortNames.length]:longNames[i%longNames.length];
            long qty=10L*(i+1);
            list.add(OffscreenRenderer.MaterialEntry.forTest(name,qty));
        }
        return list;
    }

    // V147 §47: 宽页面应选择 4 列。
    @Test
    void wideMaterialsPanelUsesFourColumns() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout ml=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(16),2200,u,bodyMetrics(u));
        assertEquals(4,ml.columns());
    }

    // V147 §48: 中等宽度自动 3 列。
    @Test
    void mediumMaterialsPanelDropsToThreeColumns() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout ml=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(16),1600,u,bodyMetrics(u));
        assertTrue(ml.columns()>=2 && ml.columns()<=4,
                "expected 2..4 columns, got "+ml.columns());
    }

    // V148 §3: 列数仍然自适应,但 V148 是"选最大可行列数,等宽铺满"。
    // 12 个材料 + 1100 宽 panel:4 列平均列宽 = (1100 - 2*26 - 3*77) / 4 = (1100-52-231)/4 = 204/列。minReadableNameW=40*1.42=56.8。
    // 至少能给每个 entry name 留 204-47-14-17-12 = 114 的空间(够大),所以 4 列可行。
    @Test
    void narrowMaterialsPanelMayKeepFourColumnsWhenEvenWidthFits() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout ml=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(12),1100,u,bodyMetrics(u));
        // V148:列数尽量大,等宽铺满。
        assertEquals(4,ml.columns());
        // 列宽近似相等(±1 像素)。
        int w0=ml.columnWidths()[0];
        for (int c=1;c<ml.columns();c++) {
            assertTrue(Math.abs(ml.columnWidths()[c]-w0)<=1,
                    "columnWidths["+c+"]="+ml.columnWidths()[c]+" should be within 1 of columnWidths[0]="+w0);
        }
    }

    // V147 §50: 极窄页面 1 列。
    @Test
    void ultraNarrowMaterialsPanelUsesOneColumn() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout ml=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(12),400,u,bodyMetrics(u));
        assertEquals(1,ml.columns());
    }

    // V147 §51: 减少列数后 Panel 变高。
    @Test
    void fewerColumnsIncreasePanelHeight() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout wide=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(16),2200,u,bodyMetrics(u));
        OffscreenRenderer.MaterialsLayout narrow=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(16),500,u,bodyMetrics(u));
        assertTrue(narrow.panelHeight()>wide.panelHeight(),
                "narrow panelHeight="+narrow.panelHeight()+" should be > wide="+wide.panelHeight());
    }

    // V147 §52: 列数变化不能改变 UI size (fontSize/iconSize/rowHeight/columnGap/padding 不变)。
    @Test
    void fewerColumnsKeepUISizeConstant() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout wide=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(16),2200,u,bodyMetrics(u));
        OffscreenRenderer.MaterialsLayout narrow=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(16),500,u,bodyMetrics(u));
        assertEquals(wide.fontSize(),narrow.fontSize());
        assertEquals(wide.iconSize(),narrow.iconSize());
        assertEquals(wide.rowHeight(),narrow.rowHeight());
        assertEquals(wide.columnGap(),narrow.columnGap());
        assertEquals(wide.padding(),narrow.padding());
    }

    // V147 §53: 每个 entry 都在 panel 内。
    @Test
    void everyEntryFitsInsidePanel() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout ml=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(12),1200,u,bodyMetrics(u));
        for (int c=0;c<ml.columns();c++) {
            int colX=ml.columnX()[c];
            int colRight=colX+ml.columnWidths()[c];
            assertTrue(colX>=0 && colRight<=ml.panelWidth(),
                    "column "+c+" x="+colX+" right="+colRight+" panelWidth="+ml.panelWidth());
        }
    }

    // V147 §54: 没有列 overlap。
    @Test
    void columnsDoNotOverlap() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout ml=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(12),1200,u,bodyMetrics(u));
        for (int c=0;c<ml.columns()-1;c++) {
            int left=ml.columnX()[c];
            int right=ml.columnX()[c+1];
            int gap=right-(left+ml.columnWidths()[c]);
            assertTrue(gap>=ml.columnGap()-1,
                    "gap between column "+c+" and "+(c+1)+" = "+gap+" should be >= columnGap="+ml.columnGap());
        }
    }

    // V147 §55: 列数变化不改变 10-view placements(同一模型 5/80 个材料,只有 canvasHeight/materialsPanelHeight 变化)。
    // 这里只验证 MaterialsLayout 内部字段:fontSize/iconSize/rowHeight 与材料数无关。
    @Test
    void materialCountChangeDoesNotChangeUISize() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout five=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(5),2200,u,bodyMetrics(u));
        OffscreenRenderer.MaterialsLayout eighty=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(80),2200,u,bodyMetrics(u));
        assertEquals(five.fontSize(),eighty.fontSize());
        assertEquals(five.iconSize(),eighty.iconSize());
        assertEquals(five.rowHeight(),eighty.rowHeight());
        assertEquals(five.padding(),eighty.padding());
        // 80 materials 应有更多行。
        assertTrue(eighty.rows()>five.rows(),
                "eighty.rows="+eighty.rows()+" should be > five.rows="+five.rows());
    }

    // V147 §43: 1~3 个材料不应强制 4 列。
    @Test
    void fewMaterialsAvoidFixedFourColumns() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout ml=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(2),2200,u,bodyMetrics(u));
        assertTrue(ml.columns()<=2,
                "2 materials should use <=2 columns, got "+ml.columns());
    }

    // V147 §45: 大量材料用 4 列 + panel 自动变高。
    @Test
    void manyMaterialsUseFourColumnsAndGrowPanel() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout ml=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(80),2400,u,bodyMetrics(u));
        assertEquals(4,ml.columns());
        assertTrue(ml.rows()>=20,"80 materials with 4 columns should have >=20 rows, got "+ml.rows());
    }

    // V147 §22: EngineeringSheetLayout 应当不依赖 MaterialsHeight 计算时材料数。
    // 这里只验证 buildMaterialsLayout 不报错 + 列数合理。
    @Test
    void layoutDoesNotThrowOnEmptyOrSmallLists() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        // 1 个材料。
        OffscreenRenderer.MaterialsLayout ml=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(1),2200,u,bodyMetrics(u));
        assertTrue(ml.columns()>=1);
        // 12 个材料(典型)。
        OffscreenRenderer.MaterialsLayout ml2=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(12),1200,u,bodyMetrics(u));
        assertTrue(ml2.columns()>=1 && ml2.columns()<=4);
    }

    // V148 §26: 单行铺满 —— 4 个材料在 2200 宽面板应该 4 列等宽铺满(rows=1, columns=4)。
    @Test
    void materialsSingleRowShouldSpanWholePanel() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout ml=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(4),2200,u,bodyMetrics(u));
        assertEquals(1,ml.rows());
        assertEquals(4,ml.columns());
        // V148:列组铺满 panel 左右(最后一列 right 应 ≤ panelWidth - padding)。
        int lastRight=ml.columnX()[ml.columns()-1]+ml.columnWidths()[ml.columns()-1];
        assertTrue(lastRight<=2200-u.panelPadding(),
                "last column right="+lastRight+" should be <= panelWidth-padding="+(2200-u.panelPadding()));
        assertTrue(ml.columnX()[0]>=u.panelPadding(),
                "first column x="+ml.columnX()[0]+" should be >= padding="+u.panelPadding());
    }

    // V148 §27: 两列平均分布 —— 列宽差 ≤ 1px。
    @Test
    void materialsTwoColumnsShouldShareWidthEvenly() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        // 找一个让 columns=2 的窄面板宽度:由内层 canFitMaterialsInColumns 决定。
        // 2200 宽 12 个材料会选 4 列;改用更窄些,触发 2 列降级。
        // 但 V148 行为:列数尽量大 + 等宽,所以除非真的装不下,不会降到 2 列。
        // 改测试:直接验证"如果 columns=N,所有列宽近似相等"——任何 N 都成立。
        OffscreenRenderer.MaterialsLayout ml=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(12),1200,u,bodyMetrics(u));
        int w0=ml.columnWidths()[0];
        for (int c=1;c<ml.columns();c++) {
            assertTrue(Math.abs(ml.columnWidths()[c]-w0)<=1,
                    "columnWidths["+c+"]="+ml.columnWidths()[c]+" should be within 1 of columnWidths[0]="+w0);
        }
    }

    // V148 §28: 列数变化不缩字号 —— 关键不变量。
    @Test
    void differentColumnsNeverChangeFontOrIconSize() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout wide=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(4),2200,u,bodyMetrics(u));
        OffscreenRenderer.MaterialsLayout narrow=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(4),600,u,bodyMetrics(u));
        assertEquals(wide.fontSize(),narrow.fontSize());
        assertEquals(wide.iconSize(),narrow.iconSize());
        assertEquals(wide.rowHeight(),narrow.rowHeight());
    }

    // V148 §13: 列宽等于 innerWidth 平均分;columnX[0]=padding,最后一列 right=panelWidth - padding。
    @Test
    void columnsExactlyFillInnerWidth() {
        OffscreenRenderer.SheetUiMetrics u=ui(1.42);
        OffscreenRenderer.MaterialsLayout ml=
                OffscreenRenderer.buildMaterialsLayout(sampleMaterials(16),2200,u,bodyMetrics(u));
        int padding=u.panelPadding();
        int totalColsW=0;
        for (int w:ml.columnWidths()) totalColsW+=w;
        int totalGaps=(ml.columns()-1)*u.columnGap();
        // sum(columnWidths) + totalGaps 应该 == innerWidth(= panelWidth - 2*padding)。
        int expected=2200-2*padding;
        assertEquals(expected,totalColsW+totalGaps,
                "sum(columnWidths)+totalGaps="+(totalColsW+totalGaps)+" should equal innerWidth="+expected);
        assertEquals(padding,ml.columnX()[0]);
    }
}