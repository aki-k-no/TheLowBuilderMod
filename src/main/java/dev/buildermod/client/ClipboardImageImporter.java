package dev.buildermod.client;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;

/** Reads image pixels or copied image files from the native desktop clipboard. */
final class ClipboardImageImporter {
    private static final long MAX_PIXELS = 40L * 1024L * 1024L;

    private ClipboardImageImporter() {
    }

    static boolean hasImage() {
        try {
            Transferable contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            return contents != null && (contents.isDataFlavorSupported(DataFlavor.imageFlavor)
                || contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static List<File> readImages(int maximum) throws Exception {
        if (maximum < 1) return Collections.emptyList();
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable contents = clipboard.getContents(null);
        if (contents == null) throw new IllegalArgumentException("Clipboard is empty");

        if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            Image image = (Image) contents.getTransferData(DataFlavor.imageFlavor);
            File output = File.createTempFile("buildermod-clipboard-", ".png");
            boolean written = false;
            try {
                writePng(image, output);
                written = true;
                output.deleteOnExit();
                return Collections.singletonList(output);
            } finally {
                if (!written) output.delete();
            }
        }

        if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            Object value = contents.getTransferData(DataFlavor.javaFileListFlavor);
            if (!(value instanceof List)) throw new IllegalArgumentException("Clipboard file list is unavailable");
            List<File> images = new ArrayList<File>();
            for (Object item : (List<?>) value) {
                if (item instanceof File && isImageName(((File) item).getName())) {
                    images.add((File) item);
                    if (images.size() >= maximum) break;
                }
            }
            if (!images.isEmpty()) return images;
        }
        throw new IllegalArgumentException("Clipboard does not contain a supported image");
    }

    static void writePng(Image image, File output) throws Exception {
        if (image == null) throw new IllegalArgumentException("Clipboard image is unavailable");
        int width = image.getWidth(null);
        int height = image.getHeight(null);
        if (width < 1 || height < 1 || (long) width * (long) height > MAX_PIXELS) {
            throw new IllegalArgumentException("Clipboard image dimensions are invalid or too large");
        }
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = buffered.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(buffered, "png", output)) {
            throw new IllegalArgumentException("Could not encode clipboard image as PNG");
        }
    }

    private static boolean isImageName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            || lower.endsWith(".webp") || lower.endsWith(".gif");
    }
}
