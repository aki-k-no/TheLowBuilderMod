package dev.buildermod.mcp;

import dev.buildermod.client.ClientTaskQueue;
import dev.buildermod.client.MinecraftToolExecutor;
import dev.buildermod.config.BuilderConfig;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Owns the embedded MCP server and keeps transport concerns away from game code.
 */
public final class McpServiceCoordinator {
    private static final Logger LOGGER = LogManager.getLogger("BuilderMod/MCP");

    private final BuilderConfig config;
    private final ClientTaskQueue taskQueue;
    private PairingTokenStore tokenStore;
    private EmbeddedMcpServer server;
    private Thread shutdownHook;
    private State state = State.STOPPED;

    public McpServiceCoordinator(BuilderConfig config, ClientTaskQueue taskQueue) {
        this.config = config;
        this.taskQueue = taskQueue;
    }

    public synchronized void start() {
        if (!config.isEnabled()) {
            state = State.DISABLED;
            LOGGER.info("Embedded MCP service is disabled in buildermod.cfg");
            return;
        }

        try {
            tokenStore = new PairingTokenStore(config.getPairingTokenFile());
            server = new EmbeddedMcpServer(config.getPort(), tokenStore, new MinecraftToolExecutor(taskQueue));
            server.start();
            state = State.RUNNING;
            shutdownHook = new Thread(new Runnable() {
                @Override
                public void run() {
                    stopFromShutdownHook();
                }
            }, "BuilderMod-MCP-Shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            LOGGER.info("Embedded MCP server listening at {}", server.getEndpoint());
        } catch (IOException exception) {
            server = null;
            tokenStore = null;
            state = State.FAILED;
            LOGGER.error("Could not start the embedded MCP server on 127.0.0.1:{}", config.getPort(), exception);
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // The JVM is already shutting down.
            }
            shutdownHook = null;
        }
        state = State.STOPPED;
    }

    private synchronized void stopFromShutdownHook() {
        if (server != null) {
            server.stop();
            server = null;
        }
        state = State.STOPPED;
    }

    public synchronized String describeStatus() {
        String endpoint = server == null ? "" : ", endpoint: " + server.getEndpoint();
        String connection = hasConnectedClient() ? ", Codex connected" : ", Codex not connected";
        return state.displayName + endpoint + connection + ", queued client tasks: " + taskQueue.pendingTasks();
    }

    public synchronized String connectCodex() {
        if (server == null || tokenStore == null) {
            return "MCP server is not running. Check buildermod.cfg and the Minecraft log.";
        }
        try {
            CodexConfigInstaller.InstallResult result = new CodexConfigInstaller().install(
                server.getEndpoint(),
                tokenStore.getToken()
            );
            if (result.isChanged()) {
                return "Codex connection was installed. Future Builder requests can use MCP.";
            }
            return "Codex is already configured.";
        } catch (IOException exception) {
            LOGGER.error("Could not install BuilderMod into the Codex configuration", exception);
            return "Could not update the Codex configuration. See the Minecraft log for details.";
        }
    }

    public synchronized boolean isCodexConfigured() {
        return server != null && new CodexConfigInstaller().isInstalled(server.getEndpoint());
    }

    public synchronized boolean hasConnectedClient() {
        return server != null && server.getLastAuthenticatedAt() > 0L;
    }

    private enum State {
        STOPPED("stopped"),
        DISABLED("disabled"),
        RUNNING("running"),
        FAILED("failed to bind port");

        private final String displayName;

        State(String displayName) {
            this.displayName = displayName;
        }
    }
}
