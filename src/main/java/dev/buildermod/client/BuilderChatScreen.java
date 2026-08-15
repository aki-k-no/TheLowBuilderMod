package dev.buildermod.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiButton;
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
    private static final int MARGIN = 12;

    private final BuilderConversation conversation;
    private GuiTextField input;
    private int scrollFromBottom;

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
        int closeWidth = 46;
        input = new GuiTextField(100, fontRendererObj, MARGIN, inputY, width - 2 * MARGIN - sendWidth - 6, 20);
        input.setMaxStringLength(4000);
        input.setText(previousInput);
        input.setFocused(true);

        buttonList.add(new GuiButton(SEND_BUTTON_ID, width - MARGIN - sendWidth, inputY, sendWidth, 20, "Send"));
        buttonList.add(new GuiButton(CLEAR_BUTTON_ID, MARGIN, 6, 46, 20, "Clear"));
        buttonList.add(new GuiButton(APPROVAL_MODE_BUTTON_ID, MARGIN + 52, 6, 124, 20, approvalModeLabel()));
        GuiButton approve = new GuiButton(APPROVE_ONCE_BUTTON_ID, MARGIN + 182, 6, 92, 20, "Approve once");
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
            send.enabled = !conversation.isRunning() && !input.getText().trim().isEmpty();
        }
        GuiButton clear = findButton(CLEAR_BUTTON_ID);
        if (clear != null) {
            clear.enabled = !conversation.isRunning();
        }
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
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == SEND_BUTTON_ID) {
            sendInput();
        } else if (button.id == CLEAR_BUTTON_ID) {
            conversation.clear();
            scrollFromBottom = 0;
        } else if (button.id == CLOSE_BUTTON_ID) {
            mc.displayGuiScreen(null);
        } else if (button.id == APPROVAL_MODE_BUTTON_ID) {
            conversation.setAutoApproveWrites(!conversation.isAutoApproveWrites());
        } else if (button.id == APPROVE_ONCE_BUTTON_ID) {
            conversation.approvePending();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
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
        super.mouseClicked(mouseX, mouseY, mouseButton);
        input.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scrollFromBottom = Math.max(0, scrollFromBottom + (wheel > 0 ? 3 : -3));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "BuilderMod - Codex", width / 2, 11, 0xFFFFFF);

        int historyTop = 32;
        int historyBottom = height - 58;
        drawRect(MARGIN - 4, historyTop - 4, width - MARGIN + 4, historyBottom + 4, 0x88000000);
        drawHistory(historyTop, historyBottom);

        String status = conversation.isRunning()
            ? "Working: " + conversation.getProgress()
            : (conversation.hasSession() ? "Session active - " : "") + conversation.getProgress();
        String visibleStatus = fontRendererObj.trimStringToWidth(status, width - 2 * MARGIN);
        int statusColor = conversation.isApprovalPending() ? 0xFFFF55 : (conversation.isRunning() ? 0xFFFF55 : 0xAAAAAA);
        fontRendererObj.drawStringWithShadow(visibleStatus, MARGIN, height - 48, statusColor);

        input.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void sendInput() {
        String message = input.getText().trim();
        if (message.isEmpty() || conversation.isRunning()) {
            return;
        }
        try {
            if (conversation.send(message, null)) {
                input.setText("");
                scrollFromBottom = 0;
            }
        } catch (IllegalArgumentException ignored) {
            // The send button already prevents empty input; keep the text for correction.
        }
    }

    private void drawHistory(int top, int bottom) {
        List<String> lines = buildLines(width - 2 * MARGIN);
        int lineHeight = fontRendererObj.FONT_HEIGHT + 2;
        int capacity = Math.max(1, (bottom - top) / lineHeight);
        int maximumScroll = Math.max(0, lines.size() - capacity);
        scrollFromBottom = Math.min(scrollFromBottom, maximumScroll);
        int end = lines.size() - scrollFromBottom;
        int start = Math.max(0, end - capacity);
        int y = top;
        for (int i = start; i < end; i++) {
            fontRendererObj.drawStringWithShadow(lines.get(i), MARGIN, y, 0xFFFFFF);
            y += lineHeight;
        }
    }

    private List<String> buildLines(int availableWidth) {
        List<String> lines = new ArrayList<String>();
        for (BuilderConversation.Message message : conversation.snapshot()) {
            String prefix;
            if (message.getRole() == BuilderConversation.Role.USER) {
                prefix = "\u00A7bYou: \u00A7f";
            } else if (message.getRole() == BuilderConversation.Role.ASSISTANT) {
                prefix = "\u00A7aCodex: \u00A7f";
            } else if (message.getRole() == BuilderConversation.Role.APPROVAL) {
                prefix = "\u00A7eApproval: \u00A7f";
            } else {
                prefix = "\u00A7cError: \u00A7f";
            }
            String[] paragraphs = message.getText().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (int i = 0; i < paragraphs.length; i++) {
                String text = (i == 0 ? prefix : "  ") + paragraphs[i];
                lines.addAll(fontRendererObj.listFormattedStringToWidth(text, availableWidth));
            }
            lines.add("");
        }
        if (lines.isEmpty()) {
            lines.add("Type a message below to talk to Codex.");
        }
        return lines;
    }

    private GuiButton findButton(int id) {
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            if (button.id == id) {
                return button;
            }
        }
        return null;
    }

    private String approvalModeLabel() {
        return conversation.isAutoApproveWrites() ? "Writes: Auto" : "Writes: Ask";
    }
}
