package dev.buildermod.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

public final class ClaudeProgressTrackerTest {
    private static JsonObject event(String json) {
        return new JsonParser().parse(json).getAsJsonObject();
    }

    @Test
    public void reportsPartialThinkingWithoutFloodingHistory() {
        CodexCliService.ClaudeProgressTracker tracker = new CodexCliService.ClaudeProgressTracker();
        Assert.assertEquals("Claude is thinking...", tracker.accept(event(
            "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\"}}}"), 1000L));
        String first = tracker.accept(event(
            "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Inspecting the world before editing.\"}}}"), 2000L);
        Assert.assertEquals("Thinking: Inspecting the world before editing.", first);
        Assert.assertNull(tracker.accept(event(
            "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\" I should verify the coordinates.\"}}}"), 2500L));
        Assert.assertEquals("Thinking: Inspecting the world before editing. I should verify the coordinates.", tracker.accept(event(
            "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_stop\",\"index\":0}}"), 2600L));
    }

    @Test
    public void reportsToolNamesFromPartialEvents() {
        CodexCliService.ClaudeProgressTracker tracker = new CodexCliService.ClaudeProgressTracker();
        Assert.assertEquals("Using tool: mcp__buildermod__get_player_context", tracker.accept(event(
            "{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"name\":\"mcp__buildermod__get_player_context\"}}}"), 1000L));
    }
}
