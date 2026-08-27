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
        Path directory = Files.createTempDirectory("v97-workbook-");
        Path workbook = MaterialWorkbookWriter.write(directory, "测试投影.litematic", List.of(
                new MaterialWorkbookWriter.Row("石头", 1728),
                new MaterialWorkbookWriter.Row("玻璃 & 木板", 65)));

        assertTrue(Files.isRegularFile(workbook));
        try (ZipFile zip = new ZipFile(workbook.toFile())) {
            String sheet = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(sheet.contains("石头"));
            assertTrue(sheet.contains("玻璃 &amp; 木板"));
            assertTrue(sheet.contains("=B2/64</f>"));
            assertTrue(sheet.contains("=B2/64/27</f>"));
            assertTrue(sheet.contains("=SUM(B2:B76)</f>"));
            assertTrue(sheet.contains("=IF(E2=&quot;已完成&quot;,1,0)</f>"));
            assertTrue(sheet.contains("=COUNTIF(E2:E700,&quot;已完成&quot;)/COUNTA(E2:E700)</f>"));
            assertTrue(sheet.contains("=K2*B2</f>"));
            assertTrue(sheet.contains("=SUM(M2:M700)/I2</f>"));
            assertTrue(sheet.contains("=REPT(\"|\",L2*50)&amp;TEXT(L2,\"0.00%\")&amp;REPT(\" \",(1-L2)*50)</f>"));

            String workbookXml = new String(zip.getInputStream(zip.getEntry("xl/workbook.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(workbookXml.contains("sheet name=\"工作表1\""));
            String styles = new String(zip.getInputStream(zip.getEntry("xl/styles.xml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(styles.contains("rgb=\"FF8CDDFA\""));
            assertTrue(styles.contains("rgb=\"FF000000\""));
            assertTrue(styles.contains("rgb=\"FFC7ECFF\""));
        }
    }
}
