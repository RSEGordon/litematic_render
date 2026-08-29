package io.github.rsegordon.litematic_render;

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
    /**
     * Owner-workbook template — historically read from a hardcoded
     * `/home/rsegordon/.hermes/cache/documents/doc_*` path. Now sourced
     * from the build classpath: production config under
     * `src/main/resources/`, tests shadow with `src/test/resources/`.
     * See LOCALIZE.md Bucket B-2.
     */
    private static final String OWNER_TEMPLATE_RESOURCE =
            "io/github/rsegordon/litematic_render/owner_workbook_template.xlsx";
    private static final String SHEET = "xl/worksheets/sheet1.xml";
    private static final String STYLES = "xl/styles.xml";
    private static final Pattern ROW = Pattern.compile("<row r=\"(\\d+)\"[^>]*>.*?</row>");

    record Row(String name, long count) {}
    private MaterialWorkbookWriter() {}

    static Path write(Path outputDirectory, String projectName, List<Row> materials) throws IOException {
        // Owner-workbook template is bundled on the build classpath (see LOCALIZE.md
        // Bucket B-2). Copy it to a temp File because ZipFile needs a real path; we
        // never depend on a hardcoded /home/.../...xlsx path anymore.
        Path templateFile = copyTemplateToTemp();
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve(safeName(projectName) + "_备货清单.xlsx");
        try (ZipFile template = new ZipFile(templateFile.toFile());
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
                        } else if (STYLES.equals(source.getName())) {
                            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                            result.write(alignTotalProgressBarsLeft(xml).getBytes(StandardCharsets.UTF_8));
                        } else input.transferTo(result);
                    }
                }
                result.closeEntry();
            }
        }
        return output;
    }

    private static String alignTotalProgressBarsLeft(String styles) throws IOException {
        String left = "<xf fontId=\"10\" fillId=\"4\" borderId=\"0\" xfId=\"0\">"
                + "<alignment vertical=\"center\" textRotation=\"0\" indent=\"0\" justifyLastLine=\"0\" shrinkToFit=\"0\"/>"
                + "</xf>";
        String centered = "<xf fontId=\"10\" fillId=\"4\" borderId=\"0\" xfId=\"0\">"
                + "<alignment horizontal=\"center\" vertical=\"center\" textRotation=\"0\" indent=\"0\" justifyLastLine=\"0\" shrinkToFit=\"0\"/>"
                + "</xf>";
        String leftAligned = left.replace("<alignment ", "<alignment horizontal=\"left\" ");
        String centeredLeftAligned = centered.replace("horizontal=\"center\"", "horizontal=\"left\"");
        return replaceSingleStyle(replaceSingleStyle(styles, left, leftAligned), centered, centeredLeftAligned);
    }

    private static String replaceSingleStyle(String styles, String current, String replacement) throws IOException {
        int first = styles.indexOf(current);
        if (first < 0 || styles.indexOf(current, first + current.length()) >= 0) {
            throw new IOException("Owner workbook must contain exactly one matching total progress bar style");
        }
        return styles.substring(0, first) + replacement + styles.substring(first + current.length());
    }

    private static String fillSheet(String template, List<Row> materials) throws IOException {
        Matcher matcher = ROW.matcher(template);
        StringBuilder output = new StringBuilder(template.length());
        boolean found = false;
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            String row = matcher.group();
            if (number >= 2 && number <= 700) {
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
                ? cell("E" + number, "13", "s", "") : inlineTextCell("E" + number, "13", "未开始"));
        row = replaceCell(row, "F" + number, cell("F" + number, material == null ? "9" : "14", "s", ""));
        reference = "K" + number;
        row = replaceCell(row, reference, material == null
                ? cell(reference, "21", "s", "")
                : formulaCell(reference, "16", "=IF(E" + number + "=&quot;已完成&quot;,1,0)"));
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

    private static String inlineTextCell(String reference, String style, String value) {
        return cell(reference, style, "inlineStr", "<is><t>" + escape(value) + "</t></is>");
    }

    private static String cell(String reference, String style, String type, String content) {
        return "<c r=\"" + reference + "\" s=\"" + style + "\"" + (type == null ? "" : " t=\"" + type + "\"")
                + ">" + content + "</c>";
    }

    static String safeName(String value) {
        String cleaned = value.replaceAll("(?i)\\.litematic$", "").replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "litematic" : cleaned;
    }


    private static Path copyTemplateToTemp() throws IOException {
        try (InputStream in = MaterialWorkbookWriter.class.getClassLoader()
                .getResourceAsStream(OWNER_TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Owner workbook template not on classpath: " + OWNER_TEMPLATE_RESOURCE);
            }
            Path tmp = Files.createTempFile("owner-workbook-", ".xlsx");
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
