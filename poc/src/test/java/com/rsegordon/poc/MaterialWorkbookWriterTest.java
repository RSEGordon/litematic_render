package com.rsegordon.poc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

class MaterialWorkbookWriterTest {
    @Test
    void writesTemplateCompatibleWorkbookWithStockingFormulas() throws Exception {
        Path directory = Files.createTempDirectory("v96-workbook-");
        Path workbook = MaterialWorkbookWriter.write(directory, "测试投影.litematic", List.of(
                new MaterialWorkbookWriter.Row("石头", 1728),
                new MaterialWorkbookWriter.Row("玻璃 & 木板", 65)));

        assertTrue(Files.isRegularFile(workbook));
        try (ZipFile zip = new ZipFile(workbook.toFile())) {
            String sheet = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(sheet.contains("石头"));
            assertTrue(sheet.contains("玻璃 &amp; 木板"));
            assertTrue(sheet.contains("B2/64/27"));
            assertTrue(sheet.contains("已完成"));
            assertTrue(sheet.contains("REPT(\"|\""));
        }
    }
}
