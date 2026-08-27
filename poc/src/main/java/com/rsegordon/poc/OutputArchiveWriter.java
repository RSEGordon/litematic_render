package com.rsegordon.poc;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Packages the four customer-facing overview variants and material workbook. */
final class OutputArchiveWriter {
    private static final List<String> OVERVIEWS = List.of(
            "mcoo_overview.png",
            "mcoo_overview_paper.png",
            "mcoo_overview_no_materials.png",
            "mcoo_overview_paper_no_materials.png");

    private OutputArchiveWriter() {}

    static Path write(Path outputDirectory, Path workbook) throws IOException {
        if (!workbook.getParent().toAbsolutePath().normalize()
                .equals(outputDirectory.toAbsolutePath().normalize())) {
            throw new IOException("Material workbook must be in the output directory: " + workbook);
        }
        Path archive = outputDirectory.resolve("outputs.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(archive)))) {
            for (String name : OVERVIEWS) add(zip, outputDirectory.resolve(name), name);
            add(zip, workbook, workbook.getFileName().toString());
        } catch (IOException error) {
            Files.deleteIfExists(archive);
            throw error;
        }
        return archive;
    }

    private static void add(ZipOutputStream zip, Path source, String name) throws IOException {
        if (!Files.isRegularFile(source)) throw new IOException("Required output is missing: " + source);
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(source, zip);
        zip.closeEntry();
    }
}
