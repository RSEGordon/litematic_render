package io.github.rsegordon.litematic_render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutputArchiveWriterTest {
    @TempDir Path directory;

    @Test
    void packagesExactlyFourOverviewsAndWorkbook() throws Exception {
        List<String> names = List.of(
                "测试_overview.png", "测试_overview_paper.png",
                "测试_overview_no_materials.png", "测试_overview_paper_no_materials.png",
                "测试_备货清单.xlsx");
        for (String name : names) Files.writeString(directory.resolve(name), name);

        Path archive = OutputArchiveWriter.write(directory, "测试.litematic", directory.resolve(names.get(4)));

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertEquals(names, zip.stream().map(entry -> entry.getName()).toList());
        }
    }
}
