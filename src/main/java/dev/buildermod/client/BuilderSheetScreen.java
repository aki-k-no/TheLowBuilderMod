package dev.buildermod.client;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Minecraft-native settings screen for the read-only Google Sheets connection. */
public final class BuilderSheetScreen extends GuiScreen {
    private static final int SAVE = 1, REMOVE = 2, LOGIN = 3, PREVIOUS = 4, NEXT = 5, CLOSE = 6, IMPORT = 7;
    private static final int ROW_HEIGHT = 20;
    private final GoogleSheetService sheets;
    private final List<String> names = new ArrayList<String>();
    private GuiTextField nameField, urlField;
    private int panelX, panelWidth, listTop, listBottom, page, rowsPerPage;
    private String selectedName;
    private volatile String message = "";
    private boolean pickingOAuthClient;

    public BuilderSheetScreen(GoogleSheetService sheets) { this.sheets = sheets; }

    @Override public void initGui() {
        String previousName = nameField == null ? "" : nameField.getText();
        String previousUrl = urlField == null ? "" : urlField.getText();
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        panelWidth = Math.min(560, width - 24);
        panelX = (width - panelWidth) / 2;
        listTop = 53;
        listBottom = Math.max(listTop + ROW_HEIGHT, height - 162);
        rowsPerPage = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        listBottom = listTop + rowsPerPage * ROW_HEIGHT;
        int fieldWidth = Math.max(90, panelWidth - 122);
        nameField = new GuiTextField(10, fontRendererObj, panelX + 102, height - 139, fieldWidth, 18);
        nameField.setMaxStringLength(32);
        nameField.setText(previousName);
        urlField = new GuiTextField(11, fontRendererObj, panelX + 102, height - 115, fieldWidth, 18);
        urlField.setMaxStringLength(512);
        urlField.setText(previousUrl);
        nameField.setFocused(true);
        int gap = 4, firstWidth = (panelWidth - 3 * gap) / 4, actionWidth = (panelWidth - 2 * gap) / 3;
        buttonList.add(new GuiButton(SAVE, panelX, height - 87, firstWidth, 20, "Save"));
        buttonList.add(new GuiButton(REMOVE, panelX + firstWidth + gap, height - 87, firstWidth, 20, "Remove"));
        buttonList.add(new GuiButton(IMPORT, panelX + (firstWidth + gap) * 2, height - 87, firstWidth, 20, "OAuth JSON"));
        buttonList.add(new GuiButton(LOGIN, panelX + (firstWidth + gap) * 3, height - 87, firstWidth, 20, "Sign in"));
        buttonList.add(new GuiButton(PREVIOUS, panelX, height - 63, actionWidth, 20, "Previous"));
        buttonList.add(new GuiButton(NEXT, panelX + actionWidth + gap, height - 63, actionWidth, 20, "Next"));
        buttonList.add(new GuiButton(CLOSE, panelX + (actionWidth + gap) * 2, height - 63, actionWidth, 20, "Close"));
        refreshNames();
    }

    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }

    @Override public void updateScreen() {
        nameField.updateCursorCounter(); urlField.updateCursorCounter();
        button(SAVE).enabled = !nameField.getText().trim().isEmpty() && !urlField.getText().trim().isEmpty();
        button(REMOVE).enabled = selectedName != null;
        button(PREVIOUS).enabled = page > 0;
        button(NEXT).enabled = (page + 1) * rowsPerPage < names.size();
        button(LOGIN).enabled = sheets.hasOAuthClient();
        button(IMPORT).enabled = !pickingOAuthClient;
    }

    @Override protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == SAVE) {
            try {
                String name = nameField.getText().trim();
                if (selectedName != null && !selectedName.equals(name) && sheets.configuredSheets().containsKey(name))
                    throw new IllegalArgumentException("That sheet name already exists.");
                message = sheets.addSheet(name, urlField.getText().trim());
                if (selectedName != null && !selectedName.equals(name)) {
                    sheets.removeSheet(selectedName);
                    message = "Google spreadsheet renamed to '" + name + "'.";
                }
                selectedName = name;
                refreshNames();
                page = names.indexOf(name) / rowsPerPage;
            } catch (IllegalArgumentException error) { message = error.getMessage(); }
        } else if (button.id == REMOVE) {
            message = sheets.removeSheet(selectedName);
            selectedName = null;
            nameField.setText(""); urlField.setText("");
            refreshNames();
        } else if (button.id == LOGIN) {
            message = "Opening the Google sign-in page...";
            boolean started = sheets.connect(new Consumer<String>() {
                @Override public void accept(String result) { message = result; }
            });
            if (!started) message = "Google sign-in is already running.";
        } else if (button.id == IMPORT) {
            openOAuthPicker();
        } else if (button.id == PREVIOUS) {
            page = Math.max(0, page - 1);
        } else if (button.id == NEXT) {
            page = Math.min((names.size() - 1) / rowsPerPage, page + 1);
        } else if (button.id == CLOSE) {
            mc.displayGuiScreen(null);
        }
    }

    private void openOAuthPicker() {
        if (pickingOAuthClient) return;
        pickingOAuthClient = true;
        message = "Choose the Google Desktop OAuth JSON...";
        EventQueue.invokeLater(new Runnable() {
            @Override public void run() {
                File chosen = null;
                String failure = null;
                FileDialog dialog = null;
                try {
                    dialog = new FileDialog((Frame) null, "Import Google Desktop OAuth JSON", FileDialog.LOAD);
                    dialog.setFilenameFilter(new java.io.FilenameFilter() {
                        @Override public boolean accept(File directory, String name) {
                            return name.toLowerCase(java.util.Locale.ROOT).endsWith(".json");
                        }
                    });
                    dialog.setVisible(true);
                    if (dialog.getFile() != null) chosen = new File(dialog.getDirectory(), dialog.getFile());
                } catch (Throwable error) {
                    failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                } finally { if (dialog != null) dialog.dispose(); }
                final File selected = chosen;
                final String errorText = failure;
                mc.addScheduledTask(new Runnable() {
                    @Override public void run() {
                        pickingOAuthClient = false;
                        if (errorText != null) message = "File picker failed: " + errorText;
                        else if (selected == null) message = "OAuth JSON selection cancelled.";
                        else try { message = sheets.importOAuthClient(selected); }
                        catch (IOException error) { message = "Import failed: " + error.getMessage(); }
                    }
                });
            }
        });
    }

    private GuiButton button(int id) {
        for (Object item : buttonList) {
            GuiButton value = (GuiButton) item;
            if (value.id == id) return value;
        }
        throw new IllegalStateException("Missing button " + id);
    }

    private void refreshNames() {
        names.clear(); names.addAll(sheets.configuredSheets().keySet());
        page = Math.min(page, names.isEmpty() ? 0 : (names.size() - 1) / rowsPerPage);
        if (selectedName != null && !names.contains(selectedName)) selectedName = null;
    }

    @Override protected void mouseClicked(int x, int y, int mouseButton) throws IOException {
        if (mouseButton == 0 && x >= panelX && x < panelX + panelWidth && y >= listTop && y < listBottom) {
            int index = page * rowsPerPage + (y - listTop) / ROW_HEIGHT;
            if (index < names.size()) {
                selectedName = names.get(index);
                nameField.setText(selectedName);
                urlField.setText(sheets.configuredSheets().get(selectedName));
            }
        }
        nameField.mouseClicked(x, y, mouseButton);
        urlField.mouseClicked(x, y, mouseButton);
        super.mouseClicked(x, y, mouseButton);
    }

    @Override protected void keyTyped(char character, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_TAB) {
            boolean nameFocused = nameField.isFocused();
            nameField.setFocused(!nameFocused); urlField.setFocused(nameFocused);
            return;
        }
        if (nameField.textboxKeyTyped(character, keyCode) || urlField.textboxKeyTyped(character, keyCode)) return;
        super.keyTyped(character, keyCode);
    }

    @Override public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) page = Math.max(0, Math.min(names.isEmpty() ? 0 : (names.size() - 1) / rowsPerPage, page + (wheel < 0 ? 1 : -1)));
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Google Sheets (read-only)", width / 2, 12, 0xFFFFFF);
        drawString(fontRendererObj, sheets.status(), panelX, 31, 0xA8D6B0);
        drawRect(panelX - 2, listTop - 2, panelX + panelWidth + 2, listBottom + 2, 0xAA11161B);
        Map<String, String> configured = sheets.configuredSheets();
        if (names.isEmpty()) drawCenteredString(fontRendererObj, "No spreadsheets registered", width / 2, listTop + 8, 0xAAAAAA);
        for (int row = 0; row < rowsPerPage; row++) {
            int index = page * rowsPerPage + row;
            if (index >= names.size()) break;
            String name = names.get(index);
            int rowY = listTop + row * ROW_HEIGHT;
            if (name.equals(selectedName)) drawRect(panelX, rowY, panelX + panelWidth, rowY + ROW_HEIGHT - 1, 0xAA315477);
            String id;
            try { id = GoogleSheetService.spreadsheetId(configured.get(name)); }
            catch (IllegalArgumentException error) { id = "invalid URL"; }
            drawString(fontRendererObj, fontRendererObj.trimStringToWidth(name + "  -  " + id, panelWidth - 12), panelX + 6, rowY + 5, 0xE6E6E6);
        }
        drawString(fontRendererObj, "Name", panelX, height - 134, 0xDDDDDD);
        drawString(fontRendererObj, "Sheet URL", panelX, height - 110, 0xDDDDDD);
        nameField.drawTextBox(); urlField.drawTextBox();
        String note = sheets.hasOAuthClient() ? "OAuth client: ready" : "Click OAuth JSON to import your Google Desktop client";
        drawString(fontRendererObj, fontRendererObj.trimStringToWidth(note, panelWidth), panelX, height - 37, sheets.hasOAuthClient() ? 0xA8D6B0 : 0xFFCC88);
        if (!message.isEmpty()) drawString(fontRendererObj, fontRendererObj.trimStringToWidth(message, panelWidth), panelX, height - 24, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
