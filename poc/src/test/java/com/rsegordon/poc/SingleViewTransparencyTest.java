package com.rsegordon.poc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SingleViewTransparencyTest {
    @TempDir Path temporaryDirectory;

    @Test
    void singleViewKeepsTransparentBackgroundAndOpaqueContent() throws Exception {
        BufferedImage source = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(3, 4, 0xffffffff);
        Path output = temporaryDirectory.resolve("single.png");

        OffscreenRenderer.writeSingleView(source, output);

        BufferedImage written = ImageIO.read(output.toFile());
        assertEquals(0, written.getRGB(0, 0) >>> 24);
        assertEquals(255, written.getRGB(3, 4) >>> 24);
    }

    @Test
    void rejectsAnOpaqueCanvasAsASingleView() throws Exception {
        BufferedImage opaque = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            opaque.setRGB(x, y, 0xffffffff);
        }
        Path output = temporaryDirectory.resolve("opaque.png");
        ImageIO.write(opaque, "PNG", output.toFile());

        assertThrows(IllegalStateException.class,
                () -> OffscreenRenderer.assertBareSingleView(output));
    }
}
