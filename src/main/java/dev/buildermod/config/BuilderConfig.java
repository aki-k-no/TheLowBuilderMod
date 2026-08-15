package dev.buildermod.config;

import java.io.File;
import net.minecraftforge.common.config.Configuration;

public final class BuilderConfig {
    private static final String CATEGORY_MCP = "mcp";
    private static final String CATEGORY_CODEX = "codex";

    private final File configFile;
    private final boolean enabled;
    private final int port;
    private final File pairingTokenFile;
    private volatile boolean autoApproveWrites;

    private BuilderConfig(File configFile, boolean enabled, int port, File pairingTokenFile, boolean autoApproveWrites) {
        this.configFile = configFile;
        this.enabled = enabled;
        this.port = port;
        this.pairingTokenFile = pairingTokenFile;
        this.autoApproveWrites = autoApproveWrites;
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
            File parent = file.getAbsoluteFile().getParentFile();
            return new BuilderConfig(file, enabled, port, new File(parent, "buildermod-pairing.token"), autoApproveWrites);
        } finally {
            if (config.hasChanged()) {
                config.save();
            }
        }
    }

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
