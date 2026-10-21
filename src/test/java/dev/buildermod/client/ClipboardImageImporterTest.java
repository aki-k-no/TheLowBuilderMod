package dev.buildermod.client;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import org.junit.Assert;
import org.junit.Test;

public final class ClipboardImageImporterTest {
    @Test
    public void writesClipboardPixelsAsPng() throws Exception {
        BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(1, 1, 0xFF3366CC);
        File output = Files.createTempFile("buildermod-clipboard-test-", ".png").toFile();
        try {
            ClipboardImageImporter.writePng(source, output);
            BufferedImage decoded = ImageIO.read(output);
            Assert.assertNotNull(decoded);
            Assert.assertEquals(3, decoded.getWidth());
            Assert.assertEquals(2, decoded.getHeight());
            Assert.assertEquals(0xFF3366CC, decoded.getRGB(1, 1));
        } finally {
            output.delete();
        }
    }
}
