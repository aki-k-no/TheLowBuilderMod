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
import java.util.Collections;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.gui.GuiScreen;

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
    private static final int PROVIDER_BUTTON_ID = 12;
    private static final int REASONING_BUTTON_ID = 8;
    private static final int COPY_LAST_BUTTON_ID = 9;
    private static final int COPY_ALL_BUTTON_ID = 10;
    private static final int MARGIN = 12;

    private final BuilderConversation conversation;
    private final List<File> selectedImages = new ArrayList<File>();
    private final List<AppButton> controls = new ArrayList<AppButton>();
    private BuilderMultilineTextField input;
    /** Pixel offset from the first history row. Keeping this top-based makes wheel direction unambiguous. */
    private int historyScrollTop;
    private int historyMaxScroll;
    private boolean followHistoryTail = true;
    private boolean pickingImages;
    private String attachmentStatus = "";
    private final Map<File, ResourceLocation> conceptTextures = new HashMap<File, ResourceLocation>();
    private final Map<File, int[]> conceptSizes = new HashMap<File, int[]>();
    private File clickedConcept;
    private int conceptX, conceptY, conceptWidth, conceptHeight;
    private final List<HistoryRow> visibleRows = new ArrayList<HistoryRow>();
    private String selectionSource;
    private int selectionAnchor = -1;
    private int selectionEnd = -1;

    public BuilderChatScreen(BuilderConversation conversation) {
        this.conversation = conversation;
    }

    @Override
    public void initGui() {
        String previousInput = input == null ? "" : input.getText();
        Keyboard.enableRepeatEvents(true);
        controls.clear();

        int inputY = height - 76;
        int inputHeight = 54;
        int sendWidth = 52;
        int attachWidth = 70;
        int removeWidth = 20;
        int closeWidth = 46;
        int sendX = width - MARGIN - sendWidth;
        int attachX = sendX - 6 - attachWidth;
        int removeX = attachX - 6 - removeWidth;
        input = new BuilderMultilineTextField(fontRendererObj);
        input.setBounds(MARGIN, inputY, removeX - MARGIN - 6, inputHeight);
        input.setText(previousInput);
        input.setFocused(true);

        controls.add(new AppButton(SEND_BUTTON_ID, sendX, inputY + 34, sendWidth, 20, "Send"));
        controls.add(new AppButton(ATTACH_IMAGE_BUTTON_ID, attachX, inputY + 34, attachWidth, 20, imageButtonLabel()));
        AppButton removeImages = new AppButton(CLEAR_IMAGES_BUTTON_ID, removeX, inputY + 34, removeWidth, 20, "X");
        removeImages.visible = !selectedImages.isEmpty();
        controls.add(removeImages);
        controls.add(new AppButton(CLEAR_BUTTON_ID, MARGIN, 6, 46, 20, "Clear"));
        controls.add(new AppButton(PROVIDER_BUTTON_ID, MARGIN + 52, 6, 72, 20, "AI: " + conversation.getProviderLabel()));
        controls.add(new AppButton(MODEL_BUTTON_ID, MARGIN + 130, 6, 104, 20, modelLabel()));
        controls.add(new AppButton(REASONING_BUTTON_ID, MARGIN + 240, 6, 92, 20, reasoningLabel()));
        controls.add(new AppButton(APPROVAL_MODE_BUTTON_ID, MARGIN + 338, 6, 100, 20, approvalModeLabel()));
        AppButton approve = new AppButton(APPROVE_ONCE_BUTTON_ID, MARGIN + 444, 6, 92, 20, "Approve once");
        approve.visible = conversation.isApprovalPending();
        controls.add(approve);
        controls.add(new AppButton(COPY_LAST_BUTTON_ID, width - MARGIN - closeWidth - 102, 6, 46, 20, "Copy"));
        controls.add(new AppButton(COPY_ALL_BUTTON_ID, width - MARGIN - closeWidth - 52, 6, 46, 20, "All"));
        controls.add(new AppButton(CLOSE_BUTTON_ID, width - MARGIN - closeWidth, 6, closeWidth, 20, "Close"));
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {

        AppButton send = findButton(SEND_BUTTON_ID);
        if (send != null) {
            send.enabled = conversation.isRunning() || !input.getText().trim().isEmpty() || !selectedImages.isEmpty();
            send.displayString = conversation.isRunning() ? "Cancel" : "Send";
        }
        AppButton clear = findButton(CLEAR_BUTTON_ID);
        if (clear != null) {
            clear.enabled = !conversation.isRunning();
        }
        AppButton model = findButton(MODEL_BUTTON_ID);
        if (model != null) { model.enabled = !conversation.isRunning(); model.displayString = modelLabel(); }
        AppButton provider = findButton(PROVIDER_BUTTON_ID);
        if (provider != null) { provider.enabled = !conversation.isRunning(); provider.displayString = "AI: " + conversation.getProviderLabel(); }
        AppButton reasoning = findButton(REASONING_BUTTON_ID);
        if (reasoning != null) { reasoning.enabled = !conversation.isRunning(); reasoning.displayString = reasoningLabel(); }
        AppButton mode = findButton(APPROVAL_MODE_BUTTON_ID);
        if (mode != null) {
            mode.enabled = !conversation.isRunning();
            mode.displayString = approvalModeLabel();
        }
        AppButton approve = findButton(APPROVE_ONCE_BUTTON_ID);
        if (approve != null) {
            approve.visible = conversation.isApprovalPending();
            approve.enabled = !conversation.isRunning();
        }
        AppButton attach = findButton(ATTACH_IMAGE_BUTTON_ID);
        if (attach != null) {
            attach.enabled = !conversation.isRunning() && !pickingImages && selectedImages.size() < CodexCliService.MAX_IMAGES;
            attach.displayString = imageButtonLabel();
        }
        AppButton removeImages = findButton(CLEAR_IMAGES_BUTTON_ID);
        if (removeImages != null) {
            removeImages.visible = !selectedImages.isEmpty();
            removeImages.enabled = !conversation.isRunning() && !pickingImages;
        }
    }

    private void activate(AppButton button) throws IOException {
        if (button.id == SEND_BUTTON_ID) {
            if (conversation.isRunning()) conversation.cancel();
            else sendInput();
        } else if (button.id == COPY_LAST_BUTTON_ID) {
            copyToClipboard(conversation.copyLastAssistantReply());
        } else if (button.id == COPY_ALL_BUTTON_ID) {
            copyToClipboard(conversation.copyTranscript());
        } else if (button.id == CLEAR_BUTTON_ID) {
            conversation.clear();
            followHistoryTail = true;
        } else if (button.id == CLOSE_BUTTON_ID) {
            mc.displayGuiScreen(null);
        } else if (button.id == MODEL_BUTTON_ID) {
            conversation.cycleModel();
        } else if (button.id == PROVIDER_BUTTON_ID) {
            conversation.cycleProvider();
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
        if (keyCode == Keyboard.KEY_HOME) { historyScrollTop = 0; followHistoryTail = false; return; }
        if (keyCode == Keyboard.KEY_END) { followHistoryTail = true; return; }
        if (keyCode == Keyboard.KEY_PRIOR) { historyScrollTop = Math.max(0, historyScrollTop - 220); followHistoryTail = false; return; }
        if (keyCode == Keyboard.KEY_NEXT) { historyScrollTop = Math.min(historyMaxScroll, historyScrollTop + 220); followHistoryTail = historyScrollTop >= historyMaxScroll; return; }
        if (isCtrlKeyDown() && keyCode == Keyboard.KEY_C && hasSelection()) { copyToClipboard(selectedText()); return; }
        if (isCtrlKeyDown() && isShiftKeyDown() && keyCode == Keyboard.KEY_V && ClipboardImageImporter.hasImage()) { pasteClipboardImages(); return; }
        if (isCtrlKeyDown() && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) && !conversation.isRunning()) { sendInput(); return; }
        input.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && clickedConcept != null && mouseX >= conceptX && mouseX < conceptX + conceptWidth
            && mouseY >= conceptY && mouseY < conceptY + conceptHeight) {
            mc.displayGuiScreen(new BuilderImageScreen(clickedConcept));
            return;
        }
        if (mouseButton == 0) {
            for (AppButton button : controls) if (button.hit(mouseX, mouseY) && button.enabled && button.visible) { activate(button); return; }
            for (HistoryRow row : visibleRows) if (row.source != null && mouseX >= MARGIN && mouseX < width - MARGIN && mouseY >= row.y && mouseY < row.y + row.height) {
                selectionSource = row.source; selectionAnchor = selectionEnd = selectionOffset(row, mouseX); attachmentStatus = "Text selected - drag, then Ctrl+C to copy"; input.setFocused(false); return;
            }
        }
        input.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton != 0 || selectionSource == null) return;
        for (HistoryRow row : visibleRows) if (row.source == selectionSource && mouseY >= row.y && mouseY < row.y + row.height) { selectionEnd = selectionOffset(row, mouseX); return; }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            // LWJGL reports a positive delta when the wheel moves up. Move the viewport toward older rows.
            historyScrollTop = Math.max(0, Math.min(historyMaxScroll, historyScrollTop + (wheel > 0 ? -180 : 180)));
            followHistoryTail = historyScrollTop >= historyMaxScroll;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xFF101417);
        drawRect(0, 0, width, 30, 0xFF171E24);
        fontRendererObj.drawStringWithShadow("BuilderMod", MARGIN, 11, 0xFF67E8F9);
        fontRendererObj.drawStringWithShadow("Codex conversation", MARGIN + 66, 11, 0xFFCBD5E1);

        int historyTop = 32;
        int historyBottom = height - 112;
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

        input.draw();
        fontRendererObj.drawStringWithShadow("Enter: new line  |  Ctrl+Enter: send  |  Ctrl+Shift+V: attach image  |  Home/End, PgUp/PgDn: history", MARGIN, height - 16, 0x777777);
        for (AppButton button : controls) button.draw(fontRendererObj, mouseX, mouseY);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void copyToClipboard(String value) {
        if (value == null || value.isEmpty()) { attachmentStatus = "Nothing to copy"; return; }
        try { java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(value), null); attachmentStatus = "Copied to clipboard"; }
        catch (Exception exception) { attachmentStatus = "Clipboard unavailable"; }
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
                followHistoryTail = true;
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
        historyMaxScroll = Math.max(0, total - (bottom - top));
        if (followHistoryTail) historyScrollTop = historyMaxScroll;
        else historyScrollTop = Math.max(0, Math.min(historyScrollTop, historyMaxScroll));
        int y = top - historyScrollTop; clickedConcept = null; visibleRows.clear();
        for (HistoryRow row : rows) {
            row.y = y; visibleRows.add(row);
            if (row.text != null && y + row.height > top && y < bottom) {
                if (row.source == selectionSource && hasSelection() && row.sourceEnd > selectionStart() && row.sourceStart < selectionStop()) drawRect(MARGIN - 2, y - 1, width - MARGIN + 2, y + row.height, 0x5538BDF8);
                fontRendererObj.drawStringWithShadow(row.text, MARGIN, y, 0xFFFFFF);
            } else if (row.image != null && y + row.height > top && y < bottom) drawInlineConcept(row.image, y, contentWidth, top, bottom);
            y += row.height;
        }
    }
    private List<HistoryRow> buildRows(int availableWidth) {
        List<HistoryRow> rows = new ArrayList<HistoryRow>();
        for (BuilderConversation.Message message : conversation.snapshot()) {
            String prefix = message.getRole() == BuilderConversation.Role.USER ? "\u00A7bYou: \u00A7f" : message.getRole() == BuilderConversation.Role.ASSISTANT ? "\u00A7aCodex: \u00A7f" : message.getRole() == BuilderConversation.Role.APPROVAL ? "\u00A7eApproval: \u00A7f" : message.getRole() == BuilderConversation.Role.PROGRESS ? "\u00A77Working: \u00A7f" : "\u00A7cError: \u00A7f";
            String prefixPlain = stripFormatting(prefix); String source = message.getText(); int search = 0; boolean first = true;
            String[] paragraphs = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (String paragraph : paragraphs) {
                List<String> wrapped = fontRendererObj.listFormattedStringToWidth((first ? prefix : "  ") + paragraph, availableWidth);
                for (String rendered : wrapped) {
                    String plain = stripFormatting(rendered); String fragment = plain;
                    int contentX = MARGIN;
                    if (first && fragment.startsWith(prefixPlain)) { fragment = fragment.substring(prefixPlain.length()); contentX += fontRendererObj.getStringWidth(prefixPlain); }
                    else if (!first && fragment.startsWith("  ")) { fragment = fragment.substring(2); contentX += fontRendererObj.getStringWidth("  "); }
                    int at = fragment.isEmpty() ? search : source.indexOf(fragment, search);
                    if (at < 0) at = search;
                    int until = Math.min(source.length(), at + fragment.length());
                    rows.add(new HistoryRow(rendered, null, fontRendererObj.FONT_HEIGHT + 2, source, at, until, fragment, contentX));
                    search = until; first = false;
                }
                if (search < source.length() && source.charAt(search) == '\n') search++;
            }
            if (message.getImage() != null) rows.add(new HistoryRow(null, message.getImage(), 190, null, 0, 0, "", MARGIN));
            rows.add(new HistoryRow("", null, fontRendererObj.FONT_HEIGHT + 2, null, 0, 0, "", MARGIN));
        }
        if (rows.isEmpty()) rows.add(new HistoryRow("Type a message below to talk to Codex.", null, fontRendererObj.FONT_HEIGHT + 2, null, 0, 0, "", MARGIN)); return rows;
    }
    private static String stripFormatting(String value) { return value.replaceAll("\\u00A7.", ""); }
    private int selectionOffset(HistoryRow row, int mouseX) {
        int column = 0; int relative = Math.max(0, mouseX - row.contentX);
        while (column < row.selectText.length() && fontRendererObj.getStringWidth(row.selectText.substring(0, column + 1)) < relative) column++;
        return Math.min(row.sourceEnd, row.sourceStart + column);
    }
    private boolean hasSelection() { return selectionSource != null && selectionAnchor >= 0 && selectionEnd >= 0 && selectionAnchor != selectionEnd; }
    private int selectionStart() { return Math.min(selectionAnchor, selectionEnd); }
    private int selectionStop() { return Math.max(selectionAnchor, selectionEnd); }
    private String selectedText() { return !hasSelection() ? "" : selectionSource.substring(selectionStart(), selectionStop()); }

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
    private static final class HistoryRow { final String text; final File image; final int height; final String source; final int sourceStart, sourceEnd, contentX; final String selectText; int y; HistoryRow(String text, File image, int height, String source, int sourceStart, int sourceEnd, String selectText, int contentX) { this.text=text; this.image=image; this.height=height; this.source=source; this.sourceStart=sourceStart; this.sourceEnd=sourceEnd; this.selectText=selectText; this.contentX=contentX; } }

    private AppButton findButton(int id) {
        for (AppButton object : controls) {
            AppButton button = (AppButton) object;
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
    private static final class AppButton {
        final int id, x, y, width, height; String displayString; boolean enabled = true, visible = true;
        AppButton(int id, int x, int y, int width, int height, String label) { this.id=id; this.x=x; this.y=y; this.width=width; this.height=height; this.displayString=label; }
        boolean hit(int mx, int my) { return mx >= x && mx < x + width && my >= y && my < y + height; }
        void draw(net.minecraft.client.gui.FontRenderer font, int mx, int my) {
            if (!visible) return; boolean hover=hit(mx,my); int fill=!enabled ? 0xFF29323A : hover ? 0xFF334B5B : 0xFF26343E;
            net.minecraft.client.gui.Gui.drawRect(x,y,x+width,y+height,fill);
            net.minecraft.client.gui.Gui.drawRect(x,y,x+width,y+1,hover ? 0xFF67E8F9 : 0xFF425466);
            int color=enabled ? 0xFFE2E8F0 : 0xFF64748B;
            font.drawStringWithShadow(font.trimStringToWidth(displayString,width-8),x+4,y+(height-font.FONT_HEIGHT)/2,color);
        }
    }

}
