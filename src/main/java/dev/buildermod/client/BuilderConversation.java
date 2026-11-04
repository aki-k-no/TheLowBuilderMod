package dev.buildermod.client;

import dev.buildermod.config.BuilderConfig;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** In-memory conversation state shared by the command and the BuilderMod GUI. */
public final class BuilderConversation {
    private static final int MAX_MESSAGES = 5000;

    private final CodexCliService codexCliService;
    private final BuilderConfig config;
    private final List<Message> messages = new ArrayList<Message>();
    private String progress = "Ready";
    private String sessionId;
    private String pendingApprovalRequest;
    private List<File> pendingApprovalImages = Collections.emptyList();
    private File latestConceptImage;
    private String lastProgressMessage;

    public BuilderConversation(CodexCliService codexCliService, BuilderConfig config) {
        this.codexCliService = codexCliService;
        this.config = config;
    }

    public boolean send(final String request, final Observer observer) {
        return send(request, Collections.<File>emptyList(), observer);
    }

    public boolean send(final String request, final List<File> images, final Observer observer) {
        String trimmed = request == null ? "" : request.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("message must not be empty");
        }
        if (trimmed.length() > 4000) {
            throw new IllegalArgumentException("message is too long (maximum 4000 characters)");
        }
        if (codexCliService.isBusy()) {
            return false;
        }
        List<File> validatedImages = CodexCliService.validateImages(images);
        return startRequest(trimmed, config.isAutoApproveWrites(), true, validatedImages, observer);
    }

    private boolean startRequest(final String request, boolean approveWrites, boolean addUserMessage,
                                 final List<File> images, final Observer observer) {
        if (addUserMessage) addMessage(Role.USER, request + attachmentSummary(images));
        String providerName = "claude".equals(config.getProvider()) ? "Claude" : "Codex";
        progress = approveWrites ? "Starting " + providerName + " (auto-approved)..." : "Starting " + providerName + " (approval required for writes)...";
        final String sessionToResume = getSessionId();
        boolean started;
        try {
            started = "claude".equals(config.getProvider())
                ? codexCliService.askClaude(request, sessionToResume, approveWrites, config.getClaudeModel(), images, new CodexCliService.Listener() {
                    @Override public void onSessionStarted(String id) { setSessionId(id); }
                    @Override public void onProgress(String message) { handleProgress(message, observer); }
                    @Override public void onTokenUsage(String summary) { if (observer != null) observer.onProgress(summary); }
                    @Override public void onReply(String reply) { handleReply(reply, request, images, observer); }
                    @Override public void onError(String message) { handleError(message, request, images, observer); }
                    @Override public void onCancelled() { handleCancelled(observer); }
                })
                : codexCliService.ask(request, sessionToResume, approveWrites, config.getCodexModel(), config.getCodexReasoningEffort(), images, new CodexCliService.Listener() {
            @Override
            public void onSessionStarted(String startedSessionId) {
                setSessionId(startedSessionId);
            }

            @Override
            public void onProgress(String message) {
                progress = message;
                if (message != null && !message.equals(lastProgressMessage)) {
                    lastProgressMessage = message;
                    addMessage(Role.PROGRESS, message);
                }
                if (observer != null) {
                    observer.onProgress(message);
                }
            }

            @Override
            public void onTokenUsage(String summary) {
                if (observer != null) observer.onProgress(summary);
            }

            @Override
            public void onReply(String reply) {
                if (CodexCliService.indicatesApprovalRequired(reply)) {
                    markApprovalRequired(request, images, observer);
                    return;
                }
                File concept = extractConceptImage(reply);
                addMessage(Role.ASSISTANT, reply.replaceAll("(?m)^BUILDERMOD_CONCEPT_IMAGE:.*(?:\\r?\\n|$)", "").trim(), concept);
                progress = "Completed";
                if (observer != null) {
                    observer.onReply(reply);
                }
            }

            @Override
            public void onError(String message) {
                if (CodexCliService.indicatesApprovalRequired(message)) {
                    markApprovalRequired(request, images, observer);
                    return;
                }
                addMessage(Role.ERROR, message);
                progress = "Failed";
                if (observer != null) {
                    observer.onError(message);
                }
            }

            @Override
            public void onCancelled() {
                addMessage(Role.ERROR, "Request cancelled by user.");
                progress = "Cancelled";
                if (observer != null) {
                    observer.onError("Codex request cancelled by user.");
                }
            }
            });
        } catch (RuntimeException exception) {
            if (addUserMessage) removeLastUserMessage();
            throw exception;
        }
        if (!started && addUserMessage) {
            removeLastUserMessage();
        }
        return started;
    }

    private void handleProgress(String message, Observer observer) { progress = message; if (message != null && !message.equals(lastProgressMessage)) { lastProgressMessage = message; addMessage(Role.PROGRESS, message); } if (observer != null) observer.onProgress(message); }
    private void handleReply(String reply, String request, List<File> images, Observer observer) { if (CodexCliService.indicatesApprovalRequired(reply)) { markApprovalRequired(request, images, observer); return; } File concept = extractConceptImage(reply); addMessage(Role.ASSISTANT, reply.replaceAll("(?m)^BUILDERMOD_CONCEPT_IMAGE:.*(?:\\r?\\n|$)", "").trim(), concept); progress = "Completed"; if (observer != null) observer.onReply(reply); }
    private void handleError(String message, String request, List<File> images, Observer observer) { if (CodexCliService.indicatesApprovalRequired(message)) { markApprovalRequired(request, images, observer); return; } addMessage(Role.ERROR, message); progress = "Failed"; if (observer != null) observer.onError(message); }
    private void handleCancelled(Observer observer) { addMessage(Role.ERROR, "Request cancelled by user."); progress = "Cancelled"; if (observer != null) observer.onError("AI request cancelled by user."); }

    private void markApprovalRequired(String request, List<File> images, Observer observer) {
        synchronized (this) {
            pendingApprovalRequest = request;
            pendingApprovalImages = images;
            progress = "Approval required - click Approve once";
        }
        addMessage(Role.APPROVAL, "Builder write requested. Click 'Approve once' to retry this request with one-time approval.");
        if (observer != null) observer.onError("Builder write approval required. Open /builder gui and click Approve once.");
    }

    public boolean approvePending() {
        final String request;
        final List<File> images;
        synchronized (this) {
            if (pendingApprovalRequest == null || codexCliService.isBusy()) return false;
            request = pendingApprovalRequest;
            images = pendingApprovalImages;
            pendingApprovalRequest = null;
            pendingApprovalImages = Collections.emptyList();
        }
        addMessage(Role.APPROVAL, "Approved once. Retrying the pending request...");
        boolean started = startRequest(request, true, false, images, null);
        if (!started) {
            synchronized (this) {
                pendingApprovalRequest = request;
                pendingApprovalImages = images;
            }
        }
        return started;
    }

    public synchronized boolean isApprovalPending() {
        return pendingApprovalRequest != null;
    }

    public void cycleModel() {
        if ("claude".equals(config.getProvider())) { String[] values = {"", "sonnet", "opus", "haiku"}; config.setClaudeModel(next(values, config.getClaudeModel())); return; }
        String[] values = {"", "gpt-6-astra", "gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"};
        config.setCodexModel(next(values, config.getCodexModel()));
    }
    public void cycleReasoningEffort() {
        if ("claude".equals(config.getProvider())) return;
        String[] values = {"", "low", "medium", "high", "xhigh", "max", "ultra"};
        config.setCodexReasoningEffort(next(values, config.getCodexReasoningEffort()));
    }
    private String next(String[] values, String current) { for (int i=0;i<values.length;i++) if (values[i].equals(current)) return values[(i+1)%values.length]; return values[0]; }
    public String getModelLabel() { String value="claude".equals(config.getProvider()) ? config.getClaudeModel() : config.getCodexModel(); return value.isEmpty() ? "Default" : value; }
    public String getReasoningLabel() { return "claude".equals(config.getProvider()) ? "Claude managed" : (config.getCodexReasoningEffort().isEmpty() ? "Default" : config.getCodexReasoningEffort()); }
    public void cycleProvider() { config.setProvider("claude".equals(config.getProvider()) ? "codex" : "claude"); clear(); }
    public String getProviderLabel() { return "claude".equals(config.getProvider()) ? "Claude" : "Codex"; }

    public boolean isAutoApproveWrites() {
        return config.isAutoApproveWrites();
    }

    public void setAutoApproveWrites(boolean value) {
        config.setAutoApproveWrites(value);
        synchronized (this) {
            progress = value ? "Auto-approval enabled" : "Write approval required";
        }
    }

    public synchronized String copyLastAssistantReply() {
        for (int i = messages.size() - 1; i >= 0; i--) if (messages.get(i).getRole() == Role.ASSISTANT) return messages.get(i).getText();
        return "";
    }
    public synchronized String copyTranscript() {
        StringBuilder value = new StringBuilder();
        for (Message message : messages) { if (value.length() > 0) value.append("\n\n"); value.append(message.getRole().name()).append(": ").append(message.getText()); }
        return value.toString();
    }

    public synchronized List<Message> snapshot() {
        return Collections.unmodifiableList(new ArrayList<Message>(messages));
    }

    public synchronized void clear() {
        if (codexCliService.isBusy()) {
            return;
        }
        messages.clear();
        sessionId = null;
        pendingApprovalRequest = null;
        pendingApprovalImages = Collections.emptyList();
        latestConceptImage = null;
        lastProgressMessage = null;
        progress = "New session ready";
        codexCliService.resetTokenUsage();
    }

    public boolean isRunning() {
        return codexCliService.isBusy();
    }

    public boolean cancel() {
        boolean requested = codexCliService.cancel();
        if (requested) {
            synchronized (this) {
                progress = "Cancelling...";
            }
        }
        return requested;
    }

    public synchronized String getProgress() {
        return progress;
    }

    public String getTokenUsage() {
        return codexCliService.describeTokenUsage();
    }

    public synchronized File getLatestConceptImage() {
        return latestConceptImage;
    }

    private synchronized File extractConceptImage(String reply) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?m)^BUILDERMOD_CONCEPT_IMAGE:[ \t]*(.+?)[ \t]*$").matcher(reply == null ? "" : reply);
        if (!match.find()) return null;
        try {
            String relative = match.group(1).trim();
            File root = codexCliService.getConceptDirectory().getCanonicalFile();
            File candidate = new File(root, relative).getCanonicalFile();
            String lower = candidate.getName().toLowerCase(java.util.Locale.ROOT);
            if (!new File(relative).isAbsolute() && candidate.toPath().startsWith(root.toPath()) && candidate.isFile()
                && candidate.length() > 0L && candidate.length() <= CodexCliService.MAX_IMAGE_BYTES
                && (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) { latestConceptImage = candidate; return candidate; }
        } catch (Exception ignored) { }
        return null;
    }

    public synchronized boolean hasSession() {
        return sessionId != null && !sessionId.isEmpty();
    }

    synchronized String getSessionId() {
        return sessionId;
    }

    private synchronized void setSessionId(String value) {
        sessionId = value;
    }

    private synchronized void addMessage(Role role, String text) { addMessage(role, text, null); }

    private synchronized void addMessage(Role role, String text, File image) {
        messages.add(new Message(role, text, image));
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
    }

    private synchronized void removeLastUserMessage() {
        int last = messages.size() - 1;
        if (last >= 0 && messages.get(last).getRole() == Role.USER) {
            messages.remove(last);
        }
    }

    private String attachmentSummary(List<File> images) {
        if (images == null || images.isEmpty()) return "";
        StringBuilder summary = new StringBuilder("\n[Images: ");
        for (int i = 0; i < images.size(); i++) {
            if (i > 0) summary.append(", ");
            summary.append(images.get(i).getName());
        }
        return summary.append(']').toString();
    }

    public enum Role {
        USER,
        ASSISTANT,
        APPROVAL,
        PROGRESS,
        ERROR
    }

    public static final class Message {
        private final Role role;
        private final String text;
        private final File image;

        private Message(Role role, String text, File image) {
            this.role = role;
            this.text = text;
            this.image = image;
        }

        public Role getRole() {
            return role;
        }

        public String getText() { return text; }
        public File getImage() { return image; }
    }

    public interface Observer {
        void onProgress(String message);

        void onReply(String reply);

        void onError(String message);
    }
}
