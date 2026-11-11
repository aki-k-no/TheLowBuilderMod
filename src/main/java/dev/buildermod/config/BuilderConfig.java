package dev.buildermod.config;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraftforge.common.config.Configuration;

public final class BuilderConfig {
    private static final String[] SUPPORTED_MODELS = {"", "gpt-6-astra", "gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"};
    private static final String[] SUPPORTED_REASONING = {"", "low", "medium", "high", "xhigh", "max", "ultra"};
    private static final String CATEGORY_MCP = "mcp";
    private static final String CATEGORY_CODEX = "codex";
    private static final String CATEGORY_AI = "ai";
    private static final String CATEGORY_SHEETS = "google_sheets";

    private final File configFile;
    private final boolean enabled;
    private final int port;
    private final File pairingTokenFile;
    private volatile boolean autoApproveWrites;
    private volatile String codexModel;
    private volatile String codexReasoningEffort;
    private volatile String provider;
    private volatile String claudeModel;
    private volatile String googleSheetUrl;
    private volatile Map<String, String> googleSheets;

    private BuilderConfig(File configFile, boolean enabled, int port, File pairingTokenFile, boolean autoApproveWrites, String codexModel, String codexReasoningEffort, String provider, String claudeModel, String googleSheetUrl, Map<String, String> googleSheets) {
        this.configFile = configFile;
        this.enabled = enabled;
        this.port = port;
        this.pairingTokenFile = pairingTokenFile;
        this.autoApproveWrites = autoApproveWrites;
        this.codexModel = codexModel;
        this.codexReasoningEffort = codexReasoningEffort;
        this.provider = provider;
        this.claudeModel = claudeModel;
        this.googleSheetUrl = googleSheetUrl;
        this.googleSheets = Collections.unmodifiableMap(new LinkedHashMap<String, String>(googleSheets));
    }

    public static BuilderConfig load(File file) {
        Configuration config = new Configuration(file);
        try {
            config.load();
            boolean enabled = config.getBoolean(
                "enabled",
                CATEGORY_MCP,
                true,
                "Start the embedded MCP server automatically when the mod starts."
            );
            int port = config.getInt(
                "port",
                CATEGORY_MCP,
                25585,
                1024,
                65535,
                "Port used by the embedded MCP server on 127.0.0.1."
            );
            boolean autoApproveWrites = config.getBoolean(
                "autoApproveWrites",
                CATEGORY_CODEX,
                false,
                "Automatically approve BuilderMod MCP write tools for Minecraft GUI requests."
            );
            String model = config.getString("model", CATEGORY_CODEX, "", "Optional Codex model override; empty uses the Codex default.");
            String reasoning = config.getString("reasoningEffort", CATEGORY_CODEX, "", "Optional reasoning effort override; empty uses the Codex default.");
            if (!contains(SUPPORTED_MODELS, model)) { model = ""; config.get(CATEGORY_CODEX, "model", "", "Optional Codex model override; empty uses the Codex default.").set(""); }
            if (!contains(SUPPORTED_REASONING, reasoning)) { reasoning = ""; config.get(CATEGORY_CODEX, "reasoningEffort", "", "Optional reasoning effort override; empty uses the Codex default.").set(""); }
            String provider = config.getString("provider", CATEGORY_AI, "codex", "AI CLI backend: codex or claude.");
            if (!("codex".equals(provider) || "claude".equals(provider))) { provider = "codex"; config.get(CATEGORY_AI, "provider", "codex", "AI CLI backend: codex or claude.").set("codex"); }
            String claudeModel = config.getString("claudeModel", CATEGORY_AI, "", "Optional Claude model override; empty uses Claude Code default.");
            String googleSheetUrl = config.getString("url", CATEGORY_SHEETS, "", "Read-only Google spreadsheet URL used by BuilderMod MCP.");
            String[] entries = config.getStringList("entries", CATEGORY_SHEETS, new String[0], "Named Google spreadsheets as alias|URL. Use /builder sheet add to edit.");
            Map<String, String> googleSheets = parseGoogleSheets(entries, googleSheetUrl);
            File parent = file.getAbsoluteFile().getParentFile();
            return new BuilderConfig(file, enabled, port, new File(parent, "buildermod-pairing.token"), autoApproveWrites, model, reasoning, provider, claudeModel, googleSheetUrl, googleSheets);
        } finally {
            if (config.hasChanged()) {
                config.save();
            }
        }
    }

    private static boolean contains(String[] values, String value) { for (String item : values) if (item.equals(value)) return true; return false; }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPort() {
        return port;
    }

    public File getPairingTokenFile() {
        return pairingTokenFile;
    }

    public boolean isAutoApproveWrites() {
        return autoApproveWrites;
    }

    public synchronized String getCodexModel() { return codexModel; }
    public synchronized String getCodexReasoningEffort() { return codexReasoningEffort; }
    public synchronized void setCodexModel(String value) { setCodexChoice("model", value, "Optional Codex model override; empty uses the Codex default."); codexModel = value; }
    public synchronized void setCodexReasoningEffort(String value) { setCodexChoice("reasoningEffort", value, "Optional reasoning effort override; empty uses the Codex default."); codexReasoningEffort = value; }
    public synchronized String getProvider() { return provider; }
    public synchronized void setProvider(String value) { setAiChoice("provider", value, "AI CLI backend: codex or claude."); provider = value; }
    public synchronized String getClaudeModel() { return claudeModel; }
    public synchronized void setClaudeModel(String value) { setAiChoice("claudeModel", value, "Optional Claude model override; empty uses Claude Code default."); claudeModel = value; }
    public synchronized String getGoogleSheetUrl() { return googleSheetUrl; }
    public synchronized Map<String, String> getGoogleSheets() { return googleSheets; }
    public synchronized void setGoogleSheet(String name, String url) {
        Map<String, String> updated = new LinkedHashMap<String, String>(googleSheets);
        updated.put(name, url);
        saveGoogleSheets(updated);
    }
    public synchronized boolean removeGoogleSheet(String name) {
        if (!googleSheets.containsKey(name)) return false;
        Map<String, String> updated = new LinkedHashMap<String, String>(googleSheets);
        updated.remove(name);
        saveGoogleSheets(updated);
        return true;
    }
    private void saveGoogleSheets(Map<String, String> updated) {
        String[] entries = new String[updated.size()]; int index = 0;
        for (Map.Entry<String, String> item : updated.entrySet()) entries[index++] = item.getKey() + "|" + item.getValue();
        Configuration config = new Configuration(configFile);
        try {
            config.load();
            config.get(CATEGORY_SHEETS, "entries", new String[0], "Named Google spreadsheets as alias|URL. Use /builder sheet add to edit.").set(entries);
            config.get(CATEGORY_SHEETS, "url", "", "Legacy single Google spreadsheet URL; migrated to entries.").set("");
        }
        finally { if (config.hasChanged()) config.save(); }
        googleSheets = Collections.unmodifiableMap(updated);
        googleSheetUrl = "";
    }
    private static Map<String, String> parseGoogleSheets(String[] entries, String legacyUrl) {
        Map<String, String> sheets = new LinkedHashMap<String, String>();
        if (legacyUrl != null && !legacyUrl.trim().isEmpty()) sheets.put("default", legacyUrl.trim());
        for (String entry : entries) {
            int separator = entry.indexOf('|');
            if (separator <= 0) continue;
            String name = entry.substring(0, separator), url = entry.substring(separator + 1);
            if (name.matches("[A-Za-z0-9_-]{1,32}") && !url.trim().isEmpty()) sheets.put(name, url.trim());
        }
        return sheets;
    }
    public synchronized void setGoogleSheetUrl(String value) {
        setGoogleSheet("default", value);
    }
    private void setCodexChoice(String key, String value, String comment) { Configuration config = new Configuration(configFile); try { config.load(); config.get(CATEGORY_CODEX, key, "", comment).set(value); } finally { if (config.hasChanged()) config.save(); } }
    private void setAiChoice(String key, String value, String comment) { Configuration config = new Configuration(configFile); try { config.load(); config.get(CATEGORY_AI, key, "", comment).set(value); } finally { if (config.hasChanged()) config.save(); } }

    public synchronized void setAutoApproveWrites(boolean value) {
        Configuration config = new Configuration(configFile);
        try {
            config.load();
            config.get(
                CATEGORY_CODEX,
                "autoApproveWrites",
                false,
                "Automatically approve BuilderMod MCP write tools for Minecraft GUI requests."
            ).set(value);
            autoApproveWrites = value;
        } finally {
            if (config.hasChanged()) config.save();
        }
    }
}
