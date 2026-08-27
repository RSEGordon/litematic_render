package com.rsegordon.poc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class MaterialWorkbookWriterTest {
    private static final Path TEMPLATE = Path.of(
            "/home/rsegordon/.hermes/cache/documents/doc_2079455ba095_刷怪塔材料清单.xlsx");

    @Test
    void usesOwnerWorkbookAndOnlyFillsMaterialCells() throws Exception {
        Path directory = Files.createTempDirectory("v97-workbook-");
        Path workbook = MaterialWorkbookWriter.write(directory, "测试投影.litematic", List.of(
                new MaterialWorkbookWriter.Row("石头", 1728), new MaterialWorkbookWriter.Row("玻璃 & 木板", 65)));
        assertTrue(Files.isRegularFile(workbook));
        try (ZipFile expected = new ZipFile(TEMPLATE.toFile()); ZipFile actual = new ZipFile(workbook.toFile())) {
            assertEquals(expected.size(), actual.size());
            var entries = expected.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                assertTrue(actual.getEntry(entry.getName()) != null, entry.getName());
                if (!entry.isDirectory() && !entry.getName().equals("xl/worksheets/sheet1.xml")
                        && !entry.getName().equals("xl/tables/table1.xml")) {
                    assertArrayEquals(expected.getInputStream(entry).readAllBytes(),
                            actual.getInputStream(actual.getEntry(entry.getName())).readAllBytes(), entry.getName());
                }
            }
            String sheet = new String(actual.getInputStream(actual.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sheet.contains("<c r=\"A2\" s=\"10\" t=\"inlineStr\"><is><t xml:space=\"preserve\">石头</t></is></c>"));
            assertTrue(sheet.contains("玻璃 &amp; 木板"));
            assertTrue(sheet.contains("<c r=\"B2\" s=\"11\"><v>1728</v></c>"));
            assertTrue(sheet.contains("<c r=\"C2\" s=\"12\"><f>=B2/64</f><v></v></c>"));
            assertTrue(sheet.contains("<c r=\"E1\" s=\"6\" t=\"inlineStr\"><is><t>已备数量</t></is></c>"));
            assertTrue(sheet.contains("<c r=\"F1\" s=\"6\" t=\"inlineStr\"><is><t>状态</t></is></c>"));
            assertTrue(sheet.contains("<c r=\"E2\" s=\"13\"><v>0</v></c>"));
            assertTrue(sheet.contains("<c r=\"F2\" s=\"14\" t=\"str\"><f>=IF(E2>=B2,&quot;已完成&quot;,IF(E2>0,&quot;准备中&quot;,&quot;未开始&quot;))</f><v>未开始</v></c>"));
            assertTrue(sheet.contains("<f>=IF(F2=&quot;已完成&quot;,1,0)</f>"));
            assertTrue(sheet.contains("<c r=\"A4\" s=\"22\" t=\"s\"></c>"));
            assertTrue(sheet.contains("<f>=SUM(B2:B76)</f>"));
            assertTrue(sheet.contains("<f>=COUNTIF(F2:F700,&quot;已完成&quot;)/COUNTA(E2:E700)</f>"));
            assertTrue(sheet.contains("<f>=SUM(M2:M700)/I2</f>"));
            assertTrue(sheet.contains("<tablePart r:id=\"rId0\"/>"));
            assertTrue(!sheet.contains("<dataValidations>"));
            String table = new String(actual.getInputStream(actual.getEntry("xl/tables/table1.xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(table.contains("ref=\"A1:G700\""));
            assertTrue(table.contains("<tableColumns count=\"7\">"));
            assertTrue(table.contains("<tableColumn id=\"5\" name=\"已备数量\"/>"));
        }
    }
}
