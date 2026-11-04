package dev.buildermod.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Collects server chat that arrives immediately after a command is sent. */
public final class ServerChatFeedback {
    public static final class Capture {
        private final List<String> messages = new ArrayList<String>();
    }

    private final List<Capture> active = new ArrayList<Capture>();

    public Capture begin() {
        Capture capture = new Capture();
        synchronized (active) {
            active.add(capture);
        }
        return capture;
    }

    public List<String> finish(Capture capture, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (capture.messages) {
            // FAWE workflows send several commands; keep collecting so a later edit error is not hidden by pos1/pos2 replies.
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) break;
                capture.messages.wait(remaining);
            }
        }
        synchronized (active) {
            active.remove(capture);
        }
        synchronized (capture.messages) {
            return Collections.unmodifiableList(new ArrayList<String>(capture.messages));
        }
    }

    @SubscribeEvent
    public void onServerChat(ClientChatReceivedEvent event) {
        if (event.message == null) return;
        String message = event.message.getUnformattedText();
        if (message == null || message.trim().isEmpty()) return;
        List<Capture> targets;
        synchronized (active) {
            targets = new ArrayList<Capture>(active);
        }
        for (Capture capture : targets) {
            synchronized (capture.messages) {
                if (capture.messages.size() < 32) capture.messages.add(message);
                capture.messages.notifyAll();
            }
        }
    }
}
