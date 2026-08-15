package dev.buildermod.client;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public final class CodexCliServiceTest {
    @Test
    public void buildsNonInteractiveCommandWithoutOverridingModel() {
        File output = new File("reply.txt").getAbsoluteFile();
        List<String> command = CodexCliService.buildCommand("codex.exe", output, "hello world", null, false);

        Assert.assertEquals("codex.exe", command.get(0));
        Assert.assertEquals("exec", command.get(1));
        Assert.assertTrue(command.contains("--json"));
        Assert.assertTrue(command.contains("--skip-git-repo-check"));
        Assert.assertTrue(command.contains("--output-last-message"));
        Assert.assertFalse(command.contains("--model"));
        Assert.assertTrue(command.contains("mcp_servers.buildermod.default_tools_approval_mode=\"writes\""));
        Assert.assertEquals("hello world", command.get(command.size() - 1));
    }

    @Test
    public void buildsResumeCommandWithSessionIdAndReadOnlySandbox() {
        File output = new File("reply.txt").getAbsoluteFile();
        List<String> command = CodexCliService.buildCommand(
            "codex.exe", output, "follow up", "01a00432-dddb-7d43-90ef-134938942d78", true
        );

        Assert.assertEquals(Arrays.asList("codex.exe", "exec", "resume"), command.subList(0, 3));
        Assert.assertTrue(command.contains("--json"));
        Assert.assertTrue(command.contains("sandbox_mode=\"read-only\""));
        Assert.assertEquals("01a00432-dddb-7d43-90ef-134938942d78", command.get(command.size() - 2));
        Assert.assertEquals("follow up", command.get(command.size() - 1));
        Assert.assertFalse(command.contains("--model"));
        Assert.assertTrue(command.contains("mcp_servers.buildermod.default_tools_approval_mode=\"approve\""));
        Assert.assertTrue(command.contains("mcp_servers.buildermod.tools.fawe_region_edit.approval_mode=\"approve\""));
        Assert.assertTrue(command.contains("mcp_servers.buildermod.tools.fawe_workflow.approval_mode=\"approve\""));
    }

    @Test
    public void extractsSessionIdFromThreadStartedEvent() {
        Assert.assertEquals(
            "01a00432-dddb-7d43-90ef-134938942d78",
            CodexCliService.sessionId(
                "{\"type\":\"thread.started\",\"thread_id\":\"01a00432-dddb-7d43-90ef-134938942d78\"}"
            )
        );
        Assert.assertNull(CodexCliService.sessionId("{\"type\":\"turn.started\"}"));
    }

    @Test
    public void convertsJsonEventsToProgressMessages() {
        Assert.assertEquals(
            "Codex is thinking...",
            CodexCliService.progressMessage("{\"type\":\"turn.started\"}")
        );
        Assert.assertEquals(
            "Using MCP tool: buildermod.send_command",
            CodexCliService.progressMessage(
                "{\"type\":\"item.started\",\"item\":{" +
                    "\"type\":\"mcp_tool_call\",\"server\":\"buildermod\",\"tool\":\"send_command\"}}"
            )
        );
        Assert.assertNull(CodexCliService.progressMessage("not json"));
    }

    @Test
    public void splitsMultilineAndLongRepliesForMinecraftChat() {
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < 450; i++) {
            longLine.append('a');
        }

        List<String> chunks = CodexCliService.splitForChat(" first \r\n\r\n" + longLine);

        Assert.assertEquals("first", chunks.get(0));
        Assert.assertEquals(4, chunks.size());
        for (String chunk : chunks) {
            Assert.assertTrue(chunk.length() <= 220);
        }
    }

    @Test
    public void detectsApprovalCancellationMessages() {
        Assert.assertTrue(CodexCliService.indicatesApprovalRequired("MCP tool call canceled because approval was denied"));
        Assert.assertTrue(CodexCliService.indicatesApprovalRequired("\u30c4\u30fc\u30eb\u306e\u5b9f\u884c\u306b\u306f\u627f\u8a8d\u304c\u5fc5\u8981\u3067\u3059"));
        Assert.assertFalse(CodexCliService.indicatesApprovalRequired("Build completed successfully"));
    }

    @Test
    public void promptRequiresMinecraft189NumericBlockIds() {
        String prompt = CodexCliService.buildPrompt("build a wall");
        Assert.assertTrue(prompt.contains("Minecraft 1.8.9"));
        Assert.assertTrue(prompt.contains("numeric legacy block IDs"));
        Assert.assertTrue(prompt.contains("70%1,30%4"));
    }
}
