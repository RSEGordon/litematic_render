package com.rsegordon.poc;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Fills material data into the owner's workbook without rebuilding its design. */
final class MaterialWorkbookWriter {
    private static final Path OWNER_TEMPLATE = Path.of(
            "/home/rsegordon/.hermes/cache/documents/doc_2079455ba095_刷怪塔材料清单.xlsx");
    private static final String SHEET = "xl/worksheets/sheet1.xml";
    private static final String TABLE = "xl/tables/table1.xml";
    private static final Pattern ROW = Pattern.compile("<row r=\"(\\d+)\"[^>]*>.*?</row>");

    record Row(String name, long count) {}
    private MaterialWorkbookWriter() {}

    static Path write(Path outputDirectory, String projectName, List<Row> materials) throws IOException {
        if (materials.size() > 75) throw new IOException("Owner workbook supports at most 75 material rows");
        if (!Files.isRegularFile(OWNER_TEMPLATE)) throw new IOException("Owner workbook template not found: " + OWNER_TEMPLATE);
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve(safeName(projectName) + "_备货清单.xlsx");
        try (ZipFile template = new ZipFile(OWNER_TEMPLATE.toFile());
             OutputStream raw = Files.newOutputStream(output);
             ZipOutputStream result = new ZipOutputStream(new BufferedOutputStream(raw))) {
            var entries = template.entries();
            while (entries.hasMoreElements()) {
                ZipEntry source = entries.nextElement();
                ZipEntry target = new ZipEntry(source.getName());
                target.setTime(source.getTime());
                result.putNextEntry(target);
                if (!source.isDirectory()) {
                    try (InputStream input = template.getInputStream(source)) {
                        if (SHEET.equals(source.getName())) {
                            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                            result.write(fillSheet(xml, materials).getBytes(StandardCharsets.UTF_8));
                        } else if (TABLE.equals(source.getName())) {
                            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                            result.write(updateTable(xml).getBytes(StandardCharsets.UTF_8));
                        } else input.transferTo(result);
                    }
                }
                result.closeEntry();
            }
        }
        return output;
    }

    private static String fillSheet(String template, List<Row> materials) throws IOException {
        template = fillHeaders(template)
                .replace("=COUNTIF(E2:E700,&quot;已完成&quot;)", "=COUNTIF(F2:F700,&quot;已完成&quot;)")
                .replaceAll("<dataValidations>.*?</dataValidations>", "");
        Matcher matcher = ROW.matcher(template);
        StringBuilder output = new StringBuilder(template.length());
        boolean found = false;
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            String row = matcher.group();
            if (number >= 2 && number <= 76) {
                found = true;
                Row material = number - 2 < materials.size() ? materials.get(number - 2) : null;
                row = fillMaterialRow(row, number, material);
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(row));
        }
        matcher.appendTail(output);
        if (!found) throw new IOException("Owner workbook has an unexpected worksheet structure");
        return output.toString();
    }

    private static String fillHeaders(String template) throws IOException {
        Matcher matcher = ROW.matcher(template);
        if (!matcher.find() || !"1".equals(matcher.group(1))) {
            throw new IOException("Owner workbook is missing its header row");
        }
        String row = matcher.group();
        row = replaceCell(row, "E1", inlineTextCell("E1", "6", "已备数量"));
        row = replaceCell(row, "F1", inlineTextCell("F1", "6", "状态"));
        row = replaceCell(row, "G1", inlineTextCell("G1", "7", "备注"));
        return matcher.replaceFirst(Matcher.quoteReplacement(row));
    }

    private static String fillMaterialRow(String row, int number, Row material) throws IOException {
        String reference = "A" + number;
        row = replaceCell(row, reference, material == null
                ? cell(reference, "22", "s", "")
                : cell(reference, "10", "inlineStr", "<is><t xml:space=\"preserve\">" + escape(material.name()) + "</t></is>"));
        reference = "B" + number;
        row = replaceCell(row, reference, material == null
                ? cell(reference, "22", "s", "") : cell(reference, "11", null, "<v>" + material.count() + "</v>"));
        reference = "C" + number;
        row = replaceCell(row, reference, material == null
                ? cell(reference, "12", "s", "") : formulaCell(reference, "12", "=B" + number + "/64"));
        reference = "D" + number;
        row = replaceCell(row, reference, material == null
                ? cell(reference, "12", "s", "") : formulaCell(reference, "12", "=B" + number + "/64/27"));
        row = replaceCell(row, "E" + number, material == null
                ? cell("E" + number, "9", "s", "") : cell("E" + number, "13", null, "<v>0</v>"));
        row = replaceCell(row, "F" + number, material == null
                ? cell("F" + number, "9", "s", "")
                : formulaStringCell("F" + number, "14", "=IF(E" + number + ">=B" + number
                        + ",&quot;已完成&quot;,IF(E" + number + ">0,&quot;准备中&quot;,&quot;未开始&quot;))", "未开始"));
        reference = "K" + number;
        row = replaceCell(row, reference, material == null
                ? cell(reference, "21", "s", "")
                : formulaCell(reference, "16", "=IF(F" + number + "=&quot;已完成&quot;,1,0)"));
        reference = "M" + number;
        return replaceCell(row, reference, material == null
                ? cell(reference, "21", "s", "") : formulaCell(reference, "16", "=K" + number + "*B" + number));
    }

    private static String replaceCell(String row, String reference, String replacement) throws IOException {
        String start = "<c r=\"" + Pattern.quote(reference) + "\"";
        Matcher matcher = Pattern.compile(start + "[^<>]*/>|" + start + "[^<>]*>.*?</c>").matcher(row);
        if (!matcher.find()) throw new IOException("Owner workbook is missing cell " + reference);
        return matcher.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private static String formulaCell(String reference, String style, String formula) {
        return cell(reference, style, null, "<f>" + formula + "</f><v></v>");
    }

    private static String formulaStringCell(String reference, String style, String formula, String fallback) {
        return cell(reference, style, "str", "<f>" + formula + "</f><v>" + escape(fallback) + "</v>");
    }

    private static String inlineTextCell(String reference, String style, String value) {
        return cell(reference, style, "inlineStr", "<is><t>" + escape(value) + "</t></is>");
    }

    private static String updateTable(String table) {
        return table.replace("ref=\"A1:F700\"", "ref=\"A1:G700\"")
                .replace("<tableColumns count=\"6\">", "<tableColumns count=\"7\">")
                .replace("<tableColumn id=\"5\" name=\"状态\"/><tableColumn id=\"6\" name=\"备注\"/>",
                        "<tableColumn id=\"5\" name=\"已备数量\"/><tableColumn id=\"6\" name=\"状态\"/>"
                                + "<tableColumn id=\"7\" name=\"备注\"/>");
    }

    private static String cell(String reference, String style, String type, String content) {
        return "<c r=\"" + reference + "\" s=\"" + style + "\"" + (type == null ? "" : " t=\"" + type + "\"")
                + ">" + content + "</c>";
    }

    private static String safeName(String value) {
        String cleaned = value.replaceAll("(?i)\\.litematic$", "").replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "litematic" : cleaned;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
