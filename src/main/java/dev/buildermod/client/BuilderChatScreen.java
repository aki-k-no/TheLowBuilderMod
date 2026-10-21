package dev.buildermod.client;

import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Dedicated in-game conversation screen for BuilderMod. */
public final class BuilderChatScreen extends GuiScreen {
    private static final int SEND_BUTTON_ID = 0;
    private static final int CLEAR_BUTTON_ID = 1;
    private static final int CLOSE_BUTTON_ID = 2;
    private static final int APPROVAL_MODE_BUTTON_ID = 3;
    private static final int APPROVE_ONCE_BUTTON_ID = 4;
    private static final int ATTACH_IMAGE_BUTTON_ID = 5;
    private static final int CLEAR_IMAGES_BUTTON_ID = 6;
    private static final int MODEL_BUTTON_ID = 7;
    private static final int REASONING_BUTTON_ID = 8;
    private static final int MARGIN = 12;

    private final BuilderConversation conversation;
    private final List<File> selectedImages = new ArrayList<File>();
    private GuiTextField input;
    private int scrollFromBottom;
    private boolean pickingImages;
    private String attachmentStatus = "";
    private final Map<File, ResourceLocation> conceptTextures = new HashMap<File, ResourceLocation>();
    private final Map<File, int[]> conceptSizes = new HashMap<File, int[]>();
    private File clickedConcept;
    private int conceptX, conceptY, conceptWidth, conceptHeight;

    public BuilderChatScreen(BuilderConversation conversation) {
        this.conversation = conversation;
    }

    @Override
    public void initGui() {
        String previousInput = input == null ? "" : input.getText();
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();

        int inputY = height - 34;
        int sendWidth = 52;
        int attachWidth = 70;
        int removeWidth = 20;
        int closeWidth = 46;
        int sendX = width - MARGIN - sendWidth;
        int attachX = sendX - 6 - attachWidth;
        int removeX = attachX - 6 - removeWidth;
        input = new GuiTextField(100, fontRendererObj, MARGIN, inputY, removeX - MARGIN - 6, 20);
        input.setMaxStringLength(4000);
        input.setText(previousInput);
        input.setFocused(true);

        buttonList.add(new GuiButton(SEND_BUTTON_ID, sendX, inputY, sendWidth, 20, "Send"));
        buttonList.add(new GuiButton(ATTACH_IMAGE_BUTTON_ID, attachX, inputY, attachWidth, 20, imageButtonLabel()));
        GuiButton removeImages = new GuiButton(CLEAR_IMAGES_BUTTON_ID, removeX, inputY, removeWidth, 20, "X");
        removeImages.visible = !selectedImages.isEmpty();
        buttonList.add(removeImages);
        buttonList.add(new GuiButton(CLEAR_BUTTON_ID, MARGIN, 6, 46, 20, "Clear"));
        buttonList.add(new GuiButton(MODEL_BUTTON_ID, MARGIN + 52, 6, 148, 20, modelLabel()));
        buttonList.add(new GuiButton(REASONING_BUTTON_ID, MARGIN + 206, 6, 122, 20, reasoningLabel()));
        buttonList.add(new GuiButton(APPROVAL_MODE_BUTTON_ID, MARGIN + 334, 6, 124, 20, approvalModeLabel()));
        GuiButton approve = new GuiButton(APPROVE_ONCE_BUTTON_ID, MARGIN + 464, 6, 92, 20, "Approve once");
        approve.visible = conversation.isApprovalPending();
        buttonList.add(approve);
        buttonList.add(new GuiButton(CLOSE_BUTTON_ID, width - MARGIN - closeWidth, 6, closeWidth, 20, "Close"));
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        input.updateCursorCounter();
        GuiButton send = findButton(SEND_BUTTON_ID);
        if (send != null) {
            send.enabled = conversation.isRunning() || !input.getText().trim().isEmpty() || !selectedImages.isEmpty();
            send.displayString = conversation.isRunning() ? "Cancel" : "Send";
        }
        GuiButton clear = findButton(CLEAR_BUTTON_ID);
        if (clear != null) {
            clear.enabled = !conversation.isRunning();
        }
        GuiButton model = findButton(MODEL_BUTTON_ID);
        if (model != null) { model.enabled = !conversation.isRunning(); model.displayString = modelLabel(); }
        GuiButton reasoning = findButton(REASONING_BUTTON_ID);
        if (reasoning != null) { reasoning.enabled = !conversation.isRunning(); reasoning.displayString = reasoningLabel(); }
        GuiButton mode = findButton(APPROVAL_MODE_BUTTON_ID);
        if (mode != null) {
            mode.enabled = !conversation.isRunning();
            mode.displayString = approvalModeLabel();
        }
        GuiButton approve = findButton(APPROVE_ONCE_BUTTON_ID);
        if (approve != null) {
            approve.visible = conversation.isApprovalPending();
            approve.enabled = !conversation.isRunning();
        }
        GuiButton attach = findButton(ATTACH_IMAGE_BUTTON_ID);
        if (attach != null) {
            attach.enabled = !conversation.isRunning() && !pickingImages && selectedImages.size() < CodexCliService.MAX_IMAGES;
            attach.displayString = imageButtonLabel();
        }
        GuiButton removeImages = findButton(CLEAR_IMAGES_BUTTON_ID);
        if (removeImages != null) {
            removeImages.visible = !selectedImages.isEmpty();
            removeImages.enabled = !conversation.isRunning() && !pickingImages;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == SEND_BUTTON_ID) {
            if (conversation.isRunning()) conversation.cancel();
            else sendInput();
        } else if (button.id == CLEAR_BUTTON_ID) {
            conversation.clear();
            scrollFromBottom = 0;
        } else if (button.id == CLOSE_BUTTON_ID) {
            mc.displayGuiScreen(null);
        } else if (button.id == MODEL_BUTTON_ID) {
            conversation.cycleModel();
        } else if (button.id == REASONING_BUTTON_ID) {
            conversation.cycleReasoningEffort();
        } else if (button.id == APPROVAL_MODE_BUTTON_ID) {
            conversation.setAutoApproveWrites(!conversation.isAutoApproveWrites());
        } else if (button.id == APPROVE_ONCE_BUTTON_ID) {
            conversation.approvePending();
        } else if (button.id == ATTACH_IMAGE_BUTTON_ID) {
            openImagePicker();
        } else if (button.id == CLEAR_IMAGES_BUTTON_ID) {
            selectedImages.clear();
            attachmentStatus = "Images cleared";
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (isCtrlKeyDown() && keyCode == Keyboard.KEY_V && ClipboardImageImporter.hasImage()) {
            pasteClipboardImages();
            return;
        }
        if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)
            && !conversation.isRunning()) {
            sendInput();
            return;
        }
        input.textboxKeyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && clickedConcept != null && mouseX >= conceptX && mouseX < conceptX + conceptWidth
            && mouseY >= conceptY && mouseY < conceptY + conceptHeight) {
            mc.displayGuiScreen(new BuilderImageScreen(clickedConcept));
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        input.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scrollFromBottom = Math.max(0, scrollFromBottom + (wheel > 0 ? 30 : -30));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "BuilderMod - Codex", width / 2, 11, 0xFFFFFF);

        int historyTop = 32;
        int historyBottom = height - 70;
        drawRect(MARGIN - 4, historyTop - 4, width - MARGIN + 4, historyBottom + 4, 0x88000000);
        drawHistory(historyTop, historyBottom, width - 2 * MARGIN);

        String status = conversation.isRunning()
            ? "Working: " + conversation.getProgress()
            : (conversation.hasSession() ? "Session active - " : "") + conversation.getProgress();
        if (!attachmentStatus.isEmpty()) status = attachmentStatus + " | " + status;
        else if (!selectedImages.isEmpty()) status = selectedImages.size() + " image(s) attached | " + status;
        String visibleStatus = fontRendererObj.trimStringToWidth(status, width - 2 * MARGIN);
        int statusColor = conversation.isApprovalPending() ? 0xFFFF55 : (conversation.isRunning() ? 0xFFFF55 : 0xAAAAAA);
        fontRendererObj.drawStringWithShadow(visibleStatus, MARGIN, height - 48, statusColor);
        String tokenUsage = fontRendererObj.trimStringToWidth(conversation.getTokenUsage(), width - 2 * MARGIN);
        fontRendererObj.drawStringWithShadow(tokenUsage, MARGIN, height - 59, 0x55FFFF);

        input.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void sendInput() {
        String message = input.getText().trim();
        if (conversation.isRunning() || (message.isEmpty() && selectedImages.isEmpty())) {
            return;
        }
        if (message.isEmpty()) message = "Use the attached image as a reference for the Minecraft build.";
        try {
            if (conversation.send(message, new ArrayList<File>(selectedImages), null)) {
                input.setText("");
                selectedImages.clear();
                attachmentStatus = "";
                scrollFromBottom = 0;
            }
        } catch (IllegalArgumentException exception) {
            attachmentStatus = "Image error: " + exception.getMessage();
        }
    }

    private void openImagePicker() {
        if (pickingImages || conversation.isRunning()) return;
        pickingImages = true;
        attachmentStatus = "Opening image picker...";
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                File[] chosen = new File[0];
                String failure = null;
                FileDialog dialog = null;
                try {
                    dialog = new FileDialog((Frame) null, "Attach images to BuilderMod", FileDialog.LOAD);
                    dialog.setMultipleMode(true);
                    dialog.setFilenameFilter(new FilenameFilter() {
                        @Override
                        public boolean accept(File dir, String name) {
                            String lower = name.toLowerCase(java.util.Locale.ROOT);
                            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                                || lower.endsWith(".webp") || lower.endsWith(".gif");
                        }
                    });
                    dialog.setVisible(true);
                    chosen = dialog.getFiles();
                } catch (Throwable throwable) {
                    failure = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
                } finally {
                    if (dialog != null) dialog.dispose();
                }
                final File[] selected = chosen;
                final String error = failure;
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        pickingImages = false;
                        if (error != null) {
                            attachmentStatus = "Image picker failed: " + error;
                            return;
                        }
                        if (selected.length == 0) {
                            attachmentStatus = selectedImages.isEmpty() ? "" : selectedImages.size() + " image(s) attached";
                            return;
                        }
                        addSelectedImages(selected);
                    }
                });
            }
        });
    }

    private void pasteClipboardImages() {
        if (pickingImages || conversation.isRunning()) return;
        final int remaining = CodexCliService.MAX_IMAGES - selectedImages.size();
        if (remaining <= 0) {
            attachmentStatus = "Image limit: " + CodexCliService.MAX_IMAGES;
            return;
        }
        pickingImages = true;
        attachmentStatus = "Reading clipboard image...";
        EventQueue.invokeLater(new Runnable() {
            @Override
            public void run() {
                List<File> clipboardImages = new ArrayList<File>();
                String failure = null;
                try {
                    clipboardImages = ClipboardImageImporter.readImages(remaining);
                } catch (Throwable throwable) {
                    failure = throwable.getMessage() == null
                        ? throwable.getClass().getSimpleName()
                        : throwable.getMessage();
                }
                final File[] selected = clipboardImages.toArray(new File[clipboardImages.size()]);
                final String error = failure;
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        pickingImages = false;
                        if (error != null) {
                            attachmentStatus = "Clipboard image failed: " + error;
                        } else if (selected.length == 0) {
                            attachmentStatus = "No image found on clipboard";
                        } else {
                            addSelectedImages(selected);
                        }
                    }
                });
            }
        });
    }

    private void addSelectedImages(File[] files) {
        List<File> merged = new ArrayList<File>(selectedImages);
        for (File file : files) {
            boolean duplicate = false;
            for (File existing : merged) {
                if (existing.getAbsoluteFile().equals(file.getAbsoluteFile())) duplicate = true;
            }
            if (!duplicate) merged.add(file);
        }
        try {
            List<File> validated = CodexCliService.validateImages(merged);
            selectedImages.clear();
            selectedImages.addAll(validated);
            attachmentStatus = selectedImages.size() + " image(s) attached";
        } catch (IllegalArgumentException exception) {
            attachmentStatus = "Image error: " + exception.getMessage();
        }
    }

    private void drawHistory(int top, int bottom, int contentWidth) {
        List<HistoryRow> rows = buildRows(contentWidth); int total = 0;
        for (HistoryRow row : rows) total += row.height;
        scrollFromBottom = Math.min(scrollFromBottom, Math.max(0, total - (bottom - top)));
        int y = bottom - scrollFromBottom - total; clickedConcept = null;
        for (HistoryRow row : rows) {
            if (row.text != null && y + row.height > top && y < bottom) fontRendererObj.drawStringWithShadow(row.text, MARGIN, y, 0xFFFFFF);
            else if (row.image != null && y + row.height > top && y < bottom) drawInlineConcept(row.image, y, contentWidth, top, bottom);
            y += row.height;
        }
    }
    private List<HistoryRow> buildRows(int availableWidth) {
        List<HistoryRow> rows = new ArrayList<HistoryRow>();
        for (BuilderConversation.Message message : conversation.snapshot()) {
            String prefix = message.getRole() == BuilderConversation.Role.USER ? "\u00A7bYou: \u00A7f" : message.getRole() == BuilderConversation.Role.ASSISTANT ? "\u00A7aCodex: \u00A7f" : message.getRole() == BuilderConversation.Role.APPROVAL ? "\u00A7eApproval: \u00A7f" : message.getRole() == BuilderConversation.Role.PROGRESS ? "\u00A77Working: \u00A7f" : "\u00A7cError: \u00A7f";
            String[] ps = message.getText().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (int i=0;i<ps.length;i++) for (String line : fontRendererObj.listFormattedStringToWidth((i == 0 ? prefix : "  ") + ps[i], availableWidth)) rows.add(new HistoryRow(line, null, fontRendererObj.FONT_HEIGHT + 2));
            if (message.getImage() != null) rows.add(new HistoryRow(null, message.getImage(), 190));
            rows.add(new HistoryRow("", null, fontRendererObj.FONT_HEIGHT + 2));
        }
        if (rows.isEmpty()) rows.add(new HistoryRow("Type a message below to talk to Codex.", null, fontRendererObj.FONT_HEIGHT + 2)); return rows;
    }
    private void drawInlineConcept(File image, int rowY, int availableWidth, int top, int bottom) {
        ResourceLocation texture = textureFor(image); int[] size = conceptSizes.get(image);
        if (texture == null || size == null) { fontRendererObj.drawStringWithShadow("[Concept image unavailable]", MARGIN, rowY, 0xFF5555); return; }
        float scale = Math.min((float)Math.min(availableWidth - 20, 300) / size[0], 160f / size[1]);
        int w=Math.max(1,(int)(size[0]*scale)), h=Math.max(1,(int)(size[1]*scale)), x=MARGIN+(availableWidth-w)/2, y=rowY+14;
        drawRect(x-3,y-3,x+w+3,y+h+15,0xCC202020); mc.getTextureManager().bindTexture(texture);
        drawScaledCustomSizeModalRect(x,y,0,0,size[0],size[1],w,h,size[0],size[1]); drawCenteredString(fontRendererObj,"Concept - click to enlarge",x+w/2,y+h+3,0xAAAAAA);
        clickedConcept=image; conceptX=x; conceptY=y; conceptWidth=w; conceptHeight=h+15;
    }
    private ResourceLocation textureFor(File image) {
        if (conceptTextures.containsKey(image)) return conceptTextures.get(image);
        try { BufferedImage loaded=ImageIO.read(image); if (loaded == null) return null; conceptSizes.put(image,new int[]{loaded.getWidth(),loaded.getHeight()}); ResourceLocation texture=mc.getTextureManager().getDynamicTextureLocation("buildermod-concept-inline",new DynamicTexture(loaded)); conceptTextures.put(image,texture); return texture; } catch(Exception ignored) { return null; }
    }
    private static final class HistoryRow { final String text; final File image; final int height; HistoryRow(String text, File image, int height) { this.text=text; this.image=image; this.height=height; } }

    private GuiButton findButton(int id) {
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            if (button.id == id) {
                return button;
            }
        }
        return null;
    }

    private String modelLabel() { return "Model: " + conversation.getModelLabel(); }
    private String reasoningLabel() { return "Think: " + conversation.getReasoningLabel(); }

    private String approvalModeLabel() {
        return conversation.isAutoApproveWrites() ? "Writes: Auto" : "Writes: Ask";
    }

    private String imageButtonLabel() {
        if (pickingImages) return "Opening...";
        return selectedImages.isEmpty() ? "Image" : "Image (" + selectedImages.size() + ")";
    }
}
