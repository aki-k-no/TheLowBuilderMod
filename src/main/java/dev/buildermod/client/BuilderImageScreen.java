package dev.buildermod.client;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;

/** Displays the most recently generated BuilderMod concept image. */
public final class BuilderImageScreen extends GuiScreen {
    private final File imageFile;
    private ResourceLocation texture;
    private int imageWidth;
    private int imageHeight;
    private String error;
    public BuilderImageScreen(File imageFile) { this.imageFile = imageFile; }
    @Override public void initGui() {
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1) throw new IllegalArgumentException("Unsupported image");
            imageWidth = image.getWidth(); imageHeight = image.getHeight();
            texture = mc.getTextureManager().getDynamicTextureLocation("buildermod-concept", new DynamicTexture(image));
        } catch (Exception exception) { error = exception.getMessage() == null ? "Could not load image" : exception.getMessage(); }
    }
    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        if (error != null) { drawCenteredString(fontRendererObj, "Image error: " + error, width / 2, height / 2, 0xFF5555); return; }
        if (texture == null) return;
        int maxWidth = width - 24, maxHeight = height - 48;
        float scale = Math.min((float) maxWidth / imageWidth, (float) maxHeight / imageHeight);
        int drawWidth = Math.max(1, (int) (imageWidth * scale)), drawHeight = Math.max(1, (int) (imageHeight * scale));
        int x = (width - drawWidth) / 2, y = 28 + (maxHeight - drawHeight) / 2;
        drawCenteredString(fontRendererObj, imageFile.getName() + " - Esc to return", width / 2, 10, 0xFFFFFF);
        mc.getTextureManager().bindTexture(texture);
        drawScaledCustomSizeModalRect(x, y, 0, 0, imageWidth, imageHeight, drawWidth, drawHeight, imageWidth, imageHeight);
    }
    @Override protected void keyTyped(char typedChar, int keyCode) { if (keyCode == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(null); }
    @Override public boolean doesGuiPauseGame() { return false; }
}
