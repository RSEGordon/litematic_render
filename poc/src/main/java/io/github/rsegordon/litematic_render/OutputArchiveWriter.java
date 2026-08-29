package io.github.rsegordon.litematic_render;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Packages the four customer-facing overview variants and material workbook. */
final class OutputArchiveWriter {
    private OutputArchiveWriter() {}

    static Path write(Path outputDirectory, String projectName, Path workbook) throws IOException {
        if (!workbook.getParent().toAbsolutePath().normalize()
                .equals(outputDirectory.toAbsolutePath().normalize())) {
            throw new IOException("Material workbook must be in the output directory: " + workbook);
        }
        String baseName = MaterialWorkbookWriter.safeName(projectName);
        String[] overviews = {
                baseName + "_overview.png",
                baseName + "_overview_paper.png",
                baseName + "_overview_no_materials.png",
                baseName + "_overview_paper_no_materials.png"};
        Path archive = outputDirectory.resolve("outputs.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(archive)))) {
            for (String name : overviews) add(zip, outputDirectory.resolve(name), name);
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
