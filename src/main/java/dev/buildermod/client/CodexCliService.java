package dev.buildermod.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs non-interactive Codex CLI turns without blocking Minecraft's client thread. */
public final class CodexCliService {
    private static final long TIMEOUT_SECONDS = 180L;
    private static final int MAX_REQUEST_LENGTH = 4000;
    private static final int MAX_DIAGNOSTIC_BYTES = 8192;
    private static final int CHAT_CHUNK_LENGTH = 220;
    private static final String[] BUILDER_WRITE_TOOLS = {
        "set_block", "fill_blocks", "sequence_set_blocks", "fawe_region_edit", "fawe_workflow"
    };

    private final ClientTaskQueue clientTaskQueue;
    private final File workingDirectory;
    private final ExecutorService executor;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile Process currentProcess;
    private volatile long startedAtMillis;
    private volatile String lastProgress = "No Codex request has run yet.";

    public CodexCliService(ClientTaskQueue clientTaskQueue, File workingDirectory) {
        this.clientTaskQueue = clientTaskQueue;
        this.workingDirectory = workingDirectory;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "BuilderMod-Codex-CLI");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public boolean ask(final String request, final String sessionId, final boolean approveWrites, final Listener listener) {
        if (request == null || request.trim().isEmpty()) {
            throw new IllegalArgumentException("request must not be empty");
        }
        if (request.length() > MAX_REQUEST_LENGTH) {
            throw new IllegalArgumentException("request is too long (maximum 4000 characters)");
        }
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        startedAtMillis = System.currentTimeMillis();
        lastProgress = "Starting Codex CLI...";

        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final String reply = runCodex(request, sessionId, approveWrites, listener);
                    lastProgress = "Completed.";
                    dispatch(new Runnable() {
                        @Override
                        public void run() {
                            listener.onReply(reply);
                        }
                    });
                } catch (final Exception exception) {
                    lastProgress = "Failed: " + toUserMessage(exception);
                    dispatch(new Runnable() {
                        @Override
                        public void run() {
                            listener.onError(toUserMessage(exception));
                        }
                    });
                } finally {
                    currentProcess = null;
                    busy.set(false);
                }
            }
        });
        return true;
    }

    public boolean isBusy() {
        return busy.get();
    }

    public String describeStatus() {
        if (!busy.get()) {
            return "Codex idle. Last progress: " + lastProgress;
        }
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1000L);
        return "Codex running for " + elapsedSeconds + "s. Last progress: " + lastProgress;
    }

    public void shutdown() {
        Process process = currentProcess;
        if (process != null) {
            process.destroy();
        }
        executor.shutdownNow();
    }

    private String runCodex(String request, String sessionId, boolean approveWrites, Listener listener) throws Exception {
        File outputFile = File.createTempFile("buildermod-codex-", ".txt");
        try {
            String executable = CodexExecutableResolver.resolve();

            ProcessBuilder builder = new ProcessBuilder(
                buildCommand(executable, outputFile, buildPrompt(request), sessionId, approveWrites)
            );
            builder.directory(workingDirectory);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            currentProcess = process;
            // codex exec accepts follow-up prompts on stdin. Close the pipe so a one-shot
            // Minecraft request exits after its first turn instead of waiting until timeout.
            process.getOutputStream().close();

            final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
            Thread outputReader = startOutputReader(process.getInputStream(), diagnostics, listener);
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                throw new IOException("Codex CLI timed out after " + TIMEOUT_SECONDS + " seconds");
            }
            outputReader.join(2000L);

            String diagnosticText = new String(diagnostics.toByteArray(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new CodexExitException(process.exitValue(), lastUsefulText(diagnosticText));
            }
            if (!outputFile.isFile() || outputFile.length() == 0L) {
                throw new IOException("Codex CLI completed without a reply" + suffix(diagnosticText));
            }

            String reply = new String(Files.readAllBytes(outputFile.toPath()), StandardCharsets.UTF_8).trim();
            if (reply.isEmpty()) {
                throw new IOException("Codex CLI returned an empty reply");
            }
            return reply;
        } finally {
            if (!outputFile.delete()) {
                outputFile.deleteOnExit();
            }
        }
    }

    static List<String> buildCommand(String executable, File outputFile, String prompt, String sessionId, boolean approveWrites) {
        List<String> command = new ArrayList<String>();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            command.addAll(Arrays.asList(
                executable,
                "exec",
                "--json",
                "--skip-git-repo-check",
                "--sandbox",
                "read-only"
            ));
        } else {
            command.addAll(Arrays.asList(
                executable,
                "exec",
                "resume",
                "--json",
                "--skip-git-repo-check",
                "-c",
                "sandbox_mode=\"read-only\""
            ));
        }
        addBuilderApprovalOverrides(command, approveWrites);
        command.add("--output-last-message");
        command.add(outputFile.getAbsolutePath());
        if (sessionId != null && !sessionId.trim().isEmpty()) command.add(sessionId);
        command.add(prompt);
        return command;
    }

    private static void addBuilderApprovalOverrides(List<String> command, boolean approveWrites) {
        String mode = approveWrites ? "approve" : "writes";
        command.add("-c");
        command.add("mcp_servers.buildermod.default_tools_approval_mode=\"" + mode + "\"");
        for (String tool : BUILDER_WRITE_TOOLS) {
            command.add("-c");
            command.add("mcp_servers.buildermod.tools." + tool + ".approval_mode=\"" + mode + "\"");
        }
    }

    static boolean indicatesApprovalRequired(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("approval required")
            || lower.contains("approval was denied")
            || lower.contains("tool call was denied")
            || lower.contains("tool call canceled")
            || lower.contains("tool call cancelled")
            || lower.contains("request canceled")
            || lower.contains("request cancelled")
            || text.contains("\u627f\u8a8d\u304c\u5fc5\u8981")
            || text.contains("\u8a31\u53ef\u3055\u308c\u307e\u305b\u3093")
            || text.contains("\u30ad\u30e3\u30f3\u30bb\u30eb\u3055\u308c");
    }

    static List<String> splitForChat(String reply) {
        List<String> chunks = new ArrayList<String>();
        String normalized = reply == null ? "" : reply.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            String remaining = line.trim();
            if (remaining.isEmpty()) {
                continue;
            }
            while (remaining.length() > CHAT_CHUNK_LENGTH) {
                int end = CHAT_CHUNK_LENGTH;
                if (Character.isHighSurrogate(remaining.charAt(end - 1))) {
                    end--;
                }
                chunks.add(remaining.substring(0, end));
                remaining = remaining.substring(end);
            }
            if (!remaining.isEmpty()) {
                chunks.add(remaining);
            }
        }
        return chunks;
    }

    static String buildPrompt(String request) {
        return "You are replying to a Minecraft player through BuilderMod. "
            + "Answer in concise Japanese plain text suitable for Minecraft chat. "
            + "Do not modify files or run shell commands. "
            + "The target is Minecraft 1.8.9. For blocks, always default to numeric legacy block IDs, not names or modern IDs. "
            + "For FAWE patterns use forms such as 1, 1:1, 35:14, or 70%1,30%4; for set_block/fill_blocks put metadata in the data field. "
            + "If BuilderMod MCP tools are available and the request requires Minecraft actions, use them.\n\n"
            + "Player request:\n" + request;
    }

    static String progressMessage(String line) {
        try {
            JsonElement parsed = new JsonParser().parse(line);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject event = parsed.getAsJsonObject();
            String type = string(event, "type");
            if ("thread.started".equals(type)) {
                return "Codex thread started.";
            }
            if ("turn.started".equals(type)) {
                return "Codex is thinking...";
            }
            if ("turn.completed".equals(type)) {
                return "Codex finished processing.";
            }
            if ("turn.failed".equals(type)) {
                return "Codex turn failed.";
            }
            if ("item.started".equals(type) && event.has("item") && event.get("item").isJsonObject()) {
                JsonObject item = event.getAsJsonObject("item");
                String itemType = string(item, "type");
                if ("mcp_tool_call".equals(itemType)) {
                    String server = string(item, "server");
                    String tool = string(item, "tool");
                    String name = (server + (server.isEmpty() || tool.isEmpty() ? "" : ".") + tool).trim();
                    return name.isEmpty() ? "Codex is using an MCP tool..." : "Using MCP tool: " + name;
                }
                if ("command_execution".equals(itemType)) {
                    return "Codex is running a local check...";
                }
                if ("web_search".equals(itemType)) {
                    return "Codex is searching the web...";
                }
                if ("reasoning".equals(itemType)) {
                    return "Codex is reasoning...";
                }
            }
        } catch (RuntimeException ignored) {
            // Warnings written to stderr are mixed into this stream and are not JSON.
        }
        return null;
    }

    static String sessionId(String line) {
        try {
            JsonElement parsed = new JsonParser().parse(line);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject event = parsed.getAsJsonObject();
            if (!"thread.started".equals(string(event, "type"))) {
                return null;
            }
            String id = string(event, "thread_id").trim();
            return id.isEmpty() ? null : id;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private Thread startOutputReader(
        final InputStream input,
        final ByteArrayOutputStream output,
        final Listener listener
    ) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                        int remaining = MAX_DIAGNOSTIC_BYTES - output.size();
                        if (remaining > 0) {
                            output.write(bytes, 0, Math.min(bytes.length, remaining));
                        }
                        final String startedSessionId = sessionId(line);
                        if (startedSessionId != null) {
                            dispatch(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onSessionStarted(startedSessionId);
                                }
                            });
                        }
                        final String progress = progressMessage(line);
                        if (progress != null && !progress.equals(lastProgress)) {
                            lastProgress = progress;
                            dispatch(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onProgress(progress);
                                }
                            });
                        }
                    }
                } catch (IOException ignored) {
                    // The process may close its pipe while being cancelled or timed out.
                } finally {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                        // Nothing else can be done during cleanup.
                    }
                }
            }
        }, "BuilderMod-Codex-Output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void dispatch(Runnable runnable) {
        clientTaskQueue.submit(runnable);
    }

    private static String toUserMessage(Exception exception) {
        if (exception instanceof CodexExitException) {
            CodexExitException exit = (CodexExitException) exception;
            return "Codex CLI failed (exit " + exit.exitCode + ")" + suffix(exit.details);
        }
        if (exception instanceof IOException) {
            return exception.getMessage()
                + ". Check that the Codex app/CLI is installed and signed in."
                + " If needed, set BUILDERMOD_CODEX_PATH to the full codex.exe path.";
        }
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static String suffix(String text) {
        String useful = lastUsefulText(text);
        return useful.isEmpty() ? "" : ": " + useful;
    }

    private static String lastUsefulText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        int newline = normalized.lastIndexOf('\n');
        String lastLine = newline >= 0 ? normalized.substring(newline + 1).trim() : normalized;
        return lastLine.length() <= 300 ? lastLine : lastLine.substring(0, 300);
    }

    public interface Listener {
        void onSessionStarted(String sessionId);

        void onProgress(String message);

        void onReply(String reply);

        void onError(String message);
    }

    private static final class CodexExitException extends IOException {
        private final int exitCode;
        private final String details;

        private CodexExitException(int exitCode, String details) {
            super("Codex CLI exited with " + exitCode);
            this.exitCode = exitCode;
            this.details = details;
        }
    }
}
