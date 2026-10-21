package dev.buildermod.config;

import java.io.File;
import net.minecraftforge.common.config.Configuration;

public final class BuilderConfig {
    private static final String[] SUPPORTED_MODELS = {"", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"};
    private static final String[] SUPPORTED_REASONING = {"", "low", "medium", "high", "xhigh", "max", "ultra"};
    private static final String CATEGORY_MCP = "mcp";
    private static final String CATEGORY_CODEX = "codex";

    private final File configFile;
    private final boolean enabled;
    private final int port;
    private final File pairingTokenFile;
    private volatile boolean autoApproveWrites;
    private volatile String codexModel;
    private volatile String codexReasoningEffort;

    private BuilderConfig(File configFile, boolean enabled, int port, File pairingTokenFile, boolean autoApproveWrites, String codexModel, String codexReasoningEffort) {
        this.configFile = configFile;
        this.enabled = enabled;
        this.port = port;
        this.pairingTokenFile = pairingTokenFile;
        this.autoApproveWrites = autoApproveWrites;
        this.codexModel = codexModel;
        this.codexReasoningEffort = codexReasoningEffort;
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
            File parent = file.getAbsoluteFile().getParentFile();
            return new BuilderConfig(file, enabled, port, new File(parent, "buildermod-pairing.token"), autoApproveWrites, model, reasoning);
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
    private void setCodexChoice(String key, String value, String comment) { Configuration config = new Configuration(configFile); try { config.load(); config.get(CATEGORY_CODEX, key, "", comment).set(value); } finally { if (config.hasChanged()) config.save(); } }

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
