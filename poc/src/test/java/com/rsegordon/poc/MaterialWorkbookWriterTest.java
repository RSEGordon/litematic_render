package com.rsegordon.poc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
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
                        && !entry.getName().equals("xl/styles.xml")) {
                    assertArrayEquals(expected.getInputStream(entry).readAllBytes(),
                            actual.getInputStream(actual.getEntry(entry.getName())).readAllBytes(), entry.getName());
                }
            }
            String sheet = new String(actual.getInputStream(actual.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sheet.contains("<c r=\"A2\" s=\"10\" t=\"inlineStr\"><is><t xml:space=\"preserve\">石头</t></is></c>"));
            assertTrue(sheet.contains("玻璃 &amp; 木板"));
            assertTrue(sheet.contains("<c r=\"B2\" s=\"11\"><v>1728</v></c>"));
            assertTrue(sheet.contains("<c r=\"C2\" s=\"12\"><f>=B2/64</f><v></v></c>"));
            assertTrue(sheet.contains("<c r=\"E2\" s=\"13\" t=\"inlineStr\"><is><t>未开始</t></is></c>"));
            assertTrue(sheet.contains("<c r=\"F2\" s=\"14\" t=\"s\"></c>"));
            assertTrue(!sheet.contains("<c r=\"E2\" s=\"13\" t=\"inlineStr\"><f>"));
            assertTrue(sheet.contains("<f>=IF(E2=&quot;已完成&quot;,1,0)</f>"));
            assertTrue(sheet.contains("<c r=\"A4\" s=\"22\" t=\"s\"></c>"));
            assertTrue(sheet.contains("<f>=SUM(B2:B76)</f>"));
            assertTrue(sheet.contains("<f>=COUNTIF(E2:E700,&quot;已完成&quot;)/COUNTA(E2:E700)</f>"));
            assertTrue(sheet.contains("<f>=SUM(M2:M700)/I2</f>"));
            assertTrue(sheet.contains("<tablePart r:id=\"rId0\"/>"));
            assertTrue(sheet.contains("<dataValidations>"));
            String table = new String(actual.getInputStream(actual.getEntry("xl/tables/table1.xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(table.contains("ref=\"A1:F700\""));
            assertTrue(table.contains("<tableColumns count=\"6\">"));
            assertTrue(table.contains("<tableColumn id=\"5\" name=\"状态\"/>"));
            assertTrue(table.contains("<tableColumn id=\"6\" name=\"备注\"/>"));
            assertTrue(!table.contains("已备数量"));
            String styles = new String(actual.getInputStream(actual.getEntry("xl/styles.xml")).readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(2, styles.split("horizontal=\"left\"", -1).length - 1);
            assertTrue(styles.contains("<alignment horizontal=\"left\" vertical=\"center\" textRotation=\"0\""));
        }
    }

    @Test
    void writesAll214MaterialRows() throws Exception {
        Path directory = Files.createTempDirectory("v133-workbook-");
        List<MaterialWorkbookWriter.Row> materials = IntStream.rangeClosed(1, 214)
                .mapToObj(number -> new MaterialWorkbookWriter.Row("材料 " + number, number * 64L))
                .toList();

        Path workbook = MaterialWorkbookWriter.write(directory, "新年小镇.litematic", materials);

        try (ZipFile actual = new ZipFile(workbook.toFile())) {
            String sheet = new String(actual.getInputStream(actual.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            long populatedRows = IntStream.rangeClosed(2, 215)
                    .filter(row -> sheet.contains("<c r=\"A" + row
                            + "\" s=\"10\" t=\"inlineStr\"><is><t xml:space=\"preserve\">材料 "))
                    .count();
            assertEquals(214, populatedRows);
            assertTrue(sheet.contains("<c r=\"A215\" s=\"10\" t=\"inlineStr\"><is><t xml:space=\"preserve\">材料 214</t></is></c>"));
            assertTrue(sheet.contains("<c r=\"A216\" s=\"22\" t=\"s\"></c>"));
        }
    }
}
