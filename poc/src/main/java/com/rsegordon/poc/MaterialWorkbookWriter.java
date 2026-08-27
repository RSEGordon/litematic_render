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

/** Writes the V96 stocking workbook without adding a runtime spreadsheet dependency. */
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
        int lastRow = Math.max(2, materials.size() + 1);
        int summaryRow = lastRow + 2;
        StringBuilder rows = new StringBuilder();
        rows.append("<row r=\"1\" ht=\"25\" customHeight=\"1\">")
                .append(textCell("A1", "Item", 1)).append(textCell("B1", "Total", 1))
                .append(textCell("C1", "组", 1)).append(textCell("D1", "盒", 1))
                .append(textCell("E1", "已备数量", 1)).append(textCell("F1", "状态", 1))
                .append(textCell("G1", "备注", 1)).append("</row>");
        for (int i = 0; i < materials.size(); i++) {
            int r = i + 2;
            Row material = materials.get(i);
            rows.append("<row r=\"").append(r).append("\">")
                    .append(textCell("A" + r, material.name(), 2))
                    .append(numberCell("B" + r, material.count(), 3))
                    .append(formulaCell("C" + r, "B" + r + "/64", 4))
                    .append(formulaCell("D" + r, "B" + r + "/64/27", 4))
                    .append(numberCell("E" + r, 0, 5))
                    .append(formulaStringCell("F" + r,
                            "IF(E" + r + ">=B" + r + ",\"已完成\",IF(E" + r + ">0,\"准备中\",\"未开始\"))", 6))
                    .append(textCell("G" + r, "", 2)).append("</row>");
        }
        if (materials.isEmpty()) {
            rows.append("<row r=\"2\">").append(textCell("A2", "未检测到可备货方块", 2)).append("</row>");
        }

        String totalFormula = materials.isEmpty() ? "0" : "SUM(B2:B" + lastRow + ")";
        String stockedFormula = materials.isEmpty() ? "0" : "SUMPRODUCT((E2:E" + lastRow + "&gt;=B2:B" + lastRow + ")*B2:B" + lastRow + ")";
        String kindFormula = materials.isEmpty() ? "0" : "COUNTIF(F2:F" + lastRow + ",\"已完成\")/COUNTA(A2:A" + lastRow + ")";
        String blockFormula = materials.isEmpty() ? "0" : "IF(J" + summaryRow + "=0,0,J" + (summaryRow + 1) + "/J" + summaryRow + ")";
        rows.append("<row r=\"").append(summaryRow).append("\">").append(textCell("I" + summaryRow, "总方块数", 7))
                .append(formulaCell("J" + summaryRow, totalFormula, 8)).append("</row>")
                .append("<row r=\"").append(summaryRow + 1).append("\">").append(textCell("I" + (summaryRow + 1), "已备齐方块数", 7))
                .append(formulaCell("J" + (summaryRow + 1), stockedFormula, 8)).append("</row>")
                .append("<row r=\"").append(summaryRow + 2).append("\">").append(textCell("I" + (summaryRow + 2), "总进度（种类）", 7))
                .append(formulaCell("J" + (summaryRow + 2), kindFormula, 9)).append("</row>")
                .append("<row r=\"").append(summaryRow + 3).append("\">").append(formulaStringCell("I" + (summaryRow + 3), "REPT(\"|\",J" + (summaryRow + 2) + "*50)&amp;TEXT(J" + (summaryRow + 2) + ",\"0.00%\")&amp;REPT(\" \",(1-J" + (summaryRow + 2) + ")*50)", 10)).append("</row>")
                .append("<row r=\"").append(summaryRow + 4).append("\">").append(textCell("I" + (summaryRow + 4), "总进度（方块）", 7))
                .append(formulaCell("J" + (summaryRow + 4), blockFormula, 9)).append("</row>")
                .append("<row r=\"").append(summaryRow + 5).append("\">").append(formulaStringCell("I" + (summaryRow + 5), "REPT(\"|\",J" + (summaryRow + 4) + "*50)&amp;TEXT(J" + (summaryRow + 4) + ",\"0.00%\")&amp;REPT(\" \",(1-J" + (summaryRow + 4) + ")*50)", 10)).append("</row>");

        return xmlHeader() + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<dimension ref=\"A1:J" + (summaryRow + 5) + "\"/><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>"
                + "<sheetFormatPr defaultRowHeight=\"20\"/><cols>"
                + "<col min=\"1\" max=\"1\" width=\"24.7\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"11\" customWidth=\"1\"/>"
                + "<col min=\"3\" max=\"4\" width=\"10\" customWidth=\"1\"/><col min=\"5\" max=\"6\" width=\"13\" customWidth=\"1\"/>"
                + "<col min=\"7\" max=\"7\" width=\"22\" customWidth=\"1\"/><col min=\"8\" max=\"8\" width=\"3\" customWidth=\"1\"/>"
                + "<col min=\"9\" max=\"9\" width=\"58\" customWidth=\"1\"/><col min=\"10\" max=\"10\" width=\"16\" customWidth=\"1\"/></cols>"
                + "<sheetData>" + rows + "</sheetData><autoFilter ref=\"A1:G" + lastRow + "\"/>"
                + "<conditionalFormatting sqref=\"F2:F" + lastRow + "\"><cfRule type=\"containsText\" dxfId=\"0\" priority=\"1\" operator=\"containsText\" text=\"已完成\"><formula>NOT(ISERROR(SEARCH(\"已完成\",F2)))</formula></cfRule>"
                + "<cfRule type=\"containsText\" dxfId=\"1\" priority=\"2\" operator=\"containsText\" text=\"准备中\"><formula>NOT(ISERROR(SEARCH(\"准备中\",F2)))</formula></cfRule>"
                + "<cfRule type=\"containsText\" dxfId=\"2\" priority=\"3\" operator=\"containsText\" text=\"未开始\"><formula>NOT(ISERROR(SEARCH(\"未开始\",F2)))</formula></cfRule></conditionalFormatting>"
                + "<pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/>"
                + "</worksheet>";
    }

    private static String textCell(String ref, String value, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + escape(value) + "</t></is></c>";
    }

    private static String numberCell(String ref, long value, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\"><v>" + value + "</v></c>";
    }

    private static String formulaCell(String ref, String formula, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\"><f>" + formula + "</f><v>0</v></c>";
    }

    private static String formulaStringCell(String ref, String formula, int style) {
        return "<c r=\"" + ref + "\" s=\"" + style + "\" t=\"str\"><f>" + formula + "</f><v></v></c>";
    }

    private static String styles() {
        return xmlHeader() + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"0.00\"/><numFmt numFmtId=\"165\" formatCode=\"0.00%\"/></numFmts>"
                + "<fonts count=\"3\"><font><sz val=\"11\"/><name val=\"Microsoft YaHei\"/></font><font><b/><color rgb=\"FFFFFFFF\"/><sz val=\"11\"/><name val=\"Microsoft YaHei\"/></font><font><b/><color rgb=\"FF1D4E68\"/><sz val=\"11\"/><name val=\"Microsoft YaHei\"/></font></fonts>"
                + "<fills count=\"5\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF00A3F5\"/></patternFill></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFEAF7FD\"/></patternFill></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDDEBF7\"/></patternFill></fill></fills>"
                + "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style=\"thin\"><color rgb=\"FFD9E2E8\"/></left><right style=\"thin\"><color rgb=\"FFD9E2E8\"/></right><top style=\"thin\"><color rgb=\"FFD9E2E8\"/></top><bottom style=\"thin\"><color rgb=\"FFD9E2E8\"/></bottom><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"11\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
                + xf(0,1,2,1,"center") + xf(0,0,0,1,"left") + xf(0,0,0,1,"right") + xf(164,0,0,1,"right")
                + xf(0,0,3,1,"right") + xf(0,0,0,1,"center") + xf(0,2,4,1,"left") + xf(0,2,4,1,"right")
                + xf(165,2,4,1,"right") + xf(0,2,4,1,"left") + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "<dxfs count=\"3\"><dxf><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFC6EFCE\"/></patternFill></fill><font><color rgb=\"FF006100\"/></font></dxf><dxf><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFEB9C\"/></patternFill></fill><font><color rgb=\"FF9C6500\"/></font></dxf><dxf><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFC7CE\"/></patternFill></fill><font><color rgb=\"FF9C0006\"/></font></dxf></dxfs>"
                + "</styleSheet>";
    }

    private static String xf(int numFmt, int font, int fill, int border, String align) {
        return "<xf numFmtId=\"" + numFmt + "\" fontId=\"" + font + "\" fillId=\"" + fill + "\" borderId=\"" + border + "\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment horizontal=\"" + align + "\" vertical=\"center\"/></xf>";
    }

    private static String contentTypes() { return xmlHeader() + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/><Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/><Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/></Types>"; }
    private static String rootRelationships() { return xmlHeader() + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/></Relationships>"; }
    private static String workbook() { return xmlHeader() + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView/></bookViews><sheets><sheet name=\"备货清单\" sheetId=\"1\" r:id=\"rId1\"/></sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\" forceFullCalc=\"1\"/></workbook>"; }
    private static String workbookRelationships() { return xmlHeader() + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"; }
    private static String appProperties() { return xmlHeader() + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>Litematic Render V96</Application></Properties>"; }
    private static String coreProperties() { return xmlHeader() + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:creator>Litematic Render V96</dc:creator><dc:title>备货清单</dc:title></cp:coreProperties>"; }
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
