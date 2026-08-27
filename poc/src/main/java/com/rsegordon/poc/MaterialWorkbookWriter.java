package com.rsegordon.poc;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a material workbook matching the owner's V97 template. */
final class MaterialWorkbookWriter {
    record Row(String name, long count) {}
    private MaterialWorkbookWriter() {}

    static Path write(Path outputDirectory, String projectName, List<Row> materials) throws IOException {
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve(safeName(projectName) + "_备货清单.xlsx");
        try (OutputStream raw = Files.newOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
            entry(zip, "[Content_Types].xml", contentTypes());
            entry(zip, "_rels/.rels", rootRelationships());
            entry(zip, "docProps/app.xml", appProperties());
            entry(zip, "docProps/core.xml", coreProperties());
            entry(zip, "xl/workbook.xml", workbook());
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships());
            entry(zip, "xl/styles.xml", styles());
            entry(zip, "xl/worksheets/sheet1.xml", sheet(materials));
        }
        return output;
    }

    private static String sheet(List<Row> materials) {
        int dimensionRow = Math.max(6, materials.size() + 1);
        StringBuilder rows = new StringBuilder();
        rows.append("<row r=\"1\">")
                .append(textCell("A1", "Item", 1)).append(textCell("B1", "Total", 1))
                .append(textCell("C1", "组", 1)).append(textCell("D1", "盒", 1))
                .append(textCell("E1", "状态", 1)).append(textCell("F1", "备注", 1))
                .append(textCell("I1", "总方块数", 5)).append("</row>");
        int rowsToWrite = Math.max(5, materials.size());
        for (int i = 0; i < rowsToWrite; i++) {
            int r = i + 2;
            rows.append("<row r=\"").append(r).append("\">");
            if (i < materials.size()) {
                Row material = materials.get(i);
                rows.append(textCell("A" + r, material.name(), 2))
                        .append(numberCell("B" + r, material.count(), 3))
                        .append(formulaCell("C" + r, "=B" + r + "/64", 4))
                        .append(formulaCell("D" + r, "=B" + r + "/64/27", 4))
                        .append(textCell("E" + r, "", 6)).append(textCell("F" + r, "", 2));
            }
            if (r == 2) {
                rows.append(formulaCell("I2", "=SUM(B2:B76)", 7))
                        .append(formulaCell("L2", "=COUNTIF(E2:E700,\"已完成\")/COUNTA(E2:E700)", 9))
                        .append(formulaCell("P2", "=SUM(M2:M700)/I2", 8));
            } else if (r == 3) {
                rows.append(textCell("I3", "总进度（种类）", 5));
            } else if (r == 4) {
                rows.append(formulaStringCell("I4", "=REPT(\"|\",L2*50)&amp;TEXT(L2,\"0.00%\")&amp;REPT(\" \",(1-L2)*50)", 10));
            } else if (r == 5) {
                rows.append(textCell("I5", "总进度（方块）", 5));
            } else if (r == 6) {
                rows.append(formulaStringCell("I6", "=REPT(\"|\",P2*50)&amp;TEXT(P2,\"0.00%\")&amp;REPT(\" \",(1-P2)*50)", 10));
            }
            if (i < materials.size()) {
                rows.append(formulaCell("K" + r, "=IF(E" + r + "=\"已完成\",1,0)", 8))
                        .append(formulaCell("M" + r, "=K" + r + "*B" + r, 8));
            }
            rows.append("</row>");
        }
        return xmlHeader() + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<dimension ref=\"A1:P" + dimensionRow + "\"/><sheetViews><sheetView showGridLines=\"1\" workbookViewId=\"0\"/></sheetViews>"
                + "<sheetFormatPr baseColWidth=\"13\" defaultRowHeight=\"18\" customHeight=\"1\"/><cols>"
                + col(1, 24.7109) + col(2, 8.71094) + col(3, 9.71094) + col(4, 8.71094)
                + col(5, 11.7109) + col(6, 13.7109) + col(7, 2.71094) + col(8, 1.71094)
                + col(9, 43.7109) + col(10, 13.7109)
                + "</cols><sheetData>" + rows + "</sheetData></worksheet>";
    }

    private static String col(int index, double width) {
        return "<col min=\"" + index + "\" max=\"" + index + "\" width=\"" + width + "\" customWidth=\"1\"/>";
    }
    private static String textCell(String ref, String value, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + escape(value) + "</t></is></c>";
    }
    private static String numberCell(String ref, long value, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\"><v>" + value + "</v></c>";
    }
    private static String formulaCell(String ref, String formula, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\"><f>" + escape(formula) + "</f><v>0</v></c>";
    }
    private static String formulaStringCell(String ref, String formula, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\" t=\"str\"><f>" + formula + "</f><v></v></c>";
    }

    private static String styles() {
        return xmlHeader() + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"0.00\"/></numFmts>"
                + "<fonts count=\"4\"><font><name val=\"Microsoft YaHei\"/><sz val=\"11\"/></font>"
                + "<font><name val=\"SimSun\"/><b/><color rgb=\"FFFFFFFF\"/><sz val=\"11\"/></font>"
                + "<font><color rgb=\"FFFFFFFF\"/><sz val=\"11\"/></font>"
                + "<font><name val=\"Menlo\"/><color rgb=\"FF191B1F\"/><sz val=\"11\"/></font></fonts>"
                + "<fills count=\"5\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF8CDDFA\"/></patternFill></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF000000\"/></patternFill></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFC7ECFF\"/></patternFill></fill></fills>"
                + "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style=\"thin\"><color rgb=\"FF000000\"/></left><right style=\"thin\"><color rgb=\"FF000000\"/></right><top style=\"thin\"><color rgb=\"FF000000\"/></top><bottom style=\"thin\"><color rgb=\"FF000000\"/></bottom><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"11\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
                + xf(0, 1, 2, 0, "center") + xf(0, 0, 0, 0, "left") + xf(0, 0, 0, 0, "right")
                + xf(164, 0, 0, 0, "center") + xf(0, 1, 3, 1, "center") + xf(0, 0, 0, 0, "center")
                + xf(0, 0, 4, 0, "center") + xf(0, 2, 0, 0, "center") + xf(0, 2, 0, 0, "center")
                + xf(0, 3, 4, 0, "left") + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";
    }
    private static String xf(int numFmt, int font, int fill, int border, String align) {
        return "<xf numFmtId=\"" + numFmt + "\" fontId=\"" + font + "\" fillId=\"" + fill + "\" borderId=\"" + border + "\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment horizontal=\"" + align + "\" vertical=\"center\"/></xf>";
    }

    private static String contentTypes() { return xmlHeader() + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/><Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/><Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/></Types>"; }
    private static String rootRelationships() { return xmlHeader() + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/></Relationships>"; }
    private static String workbook() { return xmlHeader() + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView/></bookViews><sheets><sheet name=\"工作表1\" sheetId=\"1\" r:id=\"rId1\"/></sheets><calcPr fullCalcOnLoad=\"1\" forceFullCalc=\"1\"/></workbook>"; }
    private static String workbookRelationships() { return xmlHeader() + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"; }
    private static String appProperties() { return xmlHeader() + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>Litematic Render V97</Application></Properties>"; }
    private static String coreProperties() { return xmlHeader() + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:creator>Litematic Render V97</dc:creator><dc:title>备货清单</dc:title></cp:coreProperties>"; }
    private static String xmlHeader() { return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"; }
    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
    private static String safeName(String value) {
        String cleaned = value.replaceAll("(?i)\\.litematic$", "").replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "litematic" : cleaned;
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
