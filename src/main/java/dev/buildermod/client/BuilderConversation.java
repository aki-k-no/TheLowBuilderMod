package dev.buildermod.client;

import dev.buildermod.config.BuilderConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** In-memory conversation state shared by the command and the BuilderMod GUI. */
public final class BuilderConversation {
    private static final int MAX_MESSAGES = 200;

    private final CodexCliService codexCliService;
    private final BuilderConfig config;
    private final List<Message> messages = new ArrayList<Message>();
    private String progress = "Ready";
    private String sessionId;
    private String pendingApprovalRequest;

    public BuilderConversation(CodexCliService codexCliService, BuilderConfig config) {
        this.codexCliService = codexCliService;
        this.config = config;
    }

    public boolean send(final String request, final Observer observer) {
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

        return startRequest(trimmed, config.isAutoApproveWrites(), true, observer);
    }

    private boolean startRequest(final String request, boolean approveWrites, boolean addUserMessage, final Observer observer) {
        if (addUserMessage) addMessage(Role.USER, request);
        progress = approveWrites ? "Starting Codex (auto-approved)..." : "Starting Codex (approval required for writes)...";
        final String sessionToResume = getSessionId();
        boolean started = codexCliService.ask(request, sessionToResume, approveWrites, new CodexCliService.Listener() {
            @Override
            public void onSessionStarted(String startedSessionId) {
                setSessionId(startedSessionId);
            }

            @Override
            public void onProgress(String message) {
                progress = message;
                if (observer != null) {
                    observer.onProgress(message);
                }
            }

            @Override
            public void onReply(String reply) {
                if (CodexCliService.indicatesApprovalRequired(reply)) {
                    markApprovalRequired(request, observer);
                    return;
                }
                addMessage(Role.ASSISTANT, reply);
                progress = "Completed";
                if (observer != null) {
                    observer.onReply(reply);
                }
            }

            @Override
            public void onError(String message) {
                if (CodexCliService.indicatesApprovalRequired(message)) {
                    markApprovalRequired(request, observer);
                    return;
                }
                addMessage(Role.ERROR, message);
                progress = "Failed";
                if (observer != null) {
                    observer.onError(message);
                }
            }
        });
        if (!started && addUserMessage) {
            removeLastUserMessage();
        }
        return started;
    }

    private void markApprovalRequired(String request, Observer observer) {
        synchronized (this) {
            pendingApprovalRequest = request;
            progress = "Approval required - click Approve once";
        }
        addMessage(Role.APPROVAL, "Builder write requested. Click 'Approve once' to retry this request with one-time approval.");
        if (observer != null) observer.onError("Builder write approval required. Open /builder gui and click Approve once.");
    }

    public boolean approvePending() {
        final String request;
        synchronized (this) {
            if (pendingApprovalRequest == null || codexCliService.isBusy()) return false;
            request = pendingApprovalRequest;
            pendingApprovalRequest = null;
        }
        addMessage(Role.APPROVAL, "Approved once. Retrying the pending request...");
        boolean started = startRequest(request, true, false, null);
        if (!started) {
            synchronized (this) { pendingApprovalRequest = request; }
        }
        return started;
    }

    public synchronized boolean isApprovalPending() {
        return pendingApprovalRequest != null;
    }

    public boolean isAutoApproveWrites() {
        return config.isAutoApproveWrites();
    }

    public void setAutoApproveWrites(boolean value) {
        config.setAutoApproveWrites(value);
        synchronized (this) {
            progress = value ? "Auto-approval enabled" : "Write approval required";
        }
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
        progress = "New session ready";
    }

    public boolean isRunning() {
        return codexCliService.isBusy();
    }

    public synchronized String getProgress() {
        return progress;
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

    private synchronized void addMessage(Role role, String text) {
        messages.add(new Message(role, text));
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

    public enum Role {
        USER,
        ASSISTANT,
        APPROVAL,
        ERROR
    }

    public static final class Message {
        private final Role role;
        private final String text;

        private Message(Role role, String text) {
            this.role = role;
            this.text = text;
        }

        public Role getRole() {
            return role;
        }

        public String getText() {
            return text;
        }
    }

    public interface Observer {
        void onProgress(String message);

        void onReply(String reply);

        void onError(String message);
    }
}
