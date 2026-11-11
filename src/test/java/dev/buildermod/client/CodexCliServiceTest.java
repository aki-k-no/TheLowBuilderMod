package dev.buildermod.client;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public final class CodexCliServiceTest {
    @Test
    public void buildsNonInteractiveCommandWithoutOverridingModel() {
        File output = new File("reply.txt").getAbsoluteFile();
        List<String> command = CodexCliService.buildCommand(
            "codex.exe", output, "hello world", null, false, Collections.<File>emptyList()
        );

        Assert.assertEquals("codex.exe", command.get(0));
        Assert.assertEquals("exec", command.get(1));
        Assert.assertTrue(command.contains("--json"));
        Assert.assertTrue(command.contains("--skip-git-repo-check"));
        Assert.assertTrue(command.contains("workspace-write"));
        Assert.assertTrue(command.contains("--output-last-message"));
        Assert.assertFalse(command.contains("--model"));
        Assert.assertTrue(command.contains("mcp_servers.buildermod.default_tools_approval_mode=\"writes\""));
        Assert.assertEquals("hello world", command.get(command.size() - 1));
    }

    @Test
    public void buildsResumeCommandWithSessionIdAndWorkspaceWriteSandbox() {
        File output = new File("reply.txt").getAbsoluteFile();
        List<String> command = CodexCliService.buildCommand(
            "codex.exe", output, "follow up", "01a00432-dddb-7d43-90ef-134938942d78", true,
            Collections.<File>emptyList()
        );

        Assert.assertEquals(Arrays.asList("codex.exe", "exec", "resume"), command.subList(0, 3));
        Assert.assertTrue(command.contains("--json"));
        Assert.assertTrue(command.contains("sandbox_mode=\"workspace-write\""));
        Assert.assertEquals("01a00432-dddb-7d43-90ef-134938942d78", command.get(command.size() - 2));
        Assert.assertEquals("follow up", command.get(command.size() - 1));
        Assert.assertFalse(command.contains("--model"));
        Assert.assertTrue(command.contains("mcp_servers.buildermod.default_tools_approval_mode=\"approve\""));
        Assert.assertTrue(command.contains("mcp_servers.buildermod.tools.fawe_region_edit.approval_mode=\"approve\""));
        Assert.assertTrue(command.contains("mcp_servers.buildermod.tools.fawe_workflow.approval_mode=\"approve\""));
        Assert.assertTrue(command.contains("mcp_servers.buildermod.tools.place_command_block.approval_mode=\"approve\""));
    }

    @Test
    public void addsSelectedModelAndReasoningOverrides() {
        List<String> command = CodexCliService.buildCommand("codex.exe", new File("reply.txt"), "hello", null, false,
            "gpt-6.1-sol", "high", Collections.<File>emptyList());
        Assert.assertTrue(command.contains("model=\"gpt-6.1-sol\""));
        Assert.assertTrue(command.contains("model_reasoning_effort=\"high\""));
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
    public void claudeFollowUpResumesTheCapturedSession() {
        String id = "01a00432-dddb-7d43-90ef-134938942d78";
        com.google.gson.JsonObject init = new com.google.gson.JsonParser().parse(
            "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"" + id + "\"}"
        ).getAsJsonObject();
        Assert.assertEquals(id, CodexCliService.claudeSessionId(init));
        List<String> command = CodexCliService.buildClaudeCommand("claude.exe", new File("mcp.json"), id, false, "sonnet");
        Assert.assertEquals(id, command.get(command.indexOf("--resume") + 1));
        Assert.assertTrue(command.contains("--include-partial-messages"));
        Assert.assertFalse(command.contains("--permission-mode"));
        Assert.assertFalse(CodexCliService.buildClaudeCommand("claude.exe", new File("mcp.json"), null, false, "").contains("--resume"));
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
    public void extractsTokenUsageFromCompletedTurn() {
        CodexCliService.TokenUsage usage = CodexCliService.tokenUsage(
            "{\"type\":\"turn.completed\",\"usage\":{"
                + "\"input_tokens\":12345,\"cached_input_tokens\":10000,\"output_tokens\":678}}"
        );
        Assert.assertNotNull(usage);
        Assert.assertEquals(12345L, usage.inputTokens);
        Assert.assertEquals(10000L, usage.cachedInputTokens);
        Assert.assertEquals(678L, usage.outputTokens);
        Assert.assertNull(CodexCliService.tokenUsage("{\"type\":\"turn.started\"}"));
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
    public void recognizesUnavailableModelErrorsForDefaultRetry() {
        Assert.assertTrue(CodexCliService.isUnsupportedModelError("The 'gpt-6-sol' model is not supported when using Codex with a ChatGPT account."));
        Assert.assertFalse(CodexCliService.isUnsupportedModelError("Network request failed"));
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
        Assert.assertTrue(prompt.contains("only as needed to generate or validate schematic files"));
        Assert.assertTrue(prompt.contains("generate a Minecraft-style 3D concept image"));
        Assert.assertTrue(prompt.contains("If image generation is unavailable"));
        Assert.assertTrue(prompt.contains("capture_view from a viewpoint comparable to the reference"));
        Assert.assertTrue(prompt.contains("numeric legacy block IDs"));
        Assert.assertTrue(prompt.contains("70%1,30%4"));
        Assert.assertTrue(prompt.contains("prefer FAWE by default"));
        Assert.assertTrue(prompt.contains("sequence_set_blocks as a last resort"));
        Assert.assertTrue(prompt.contains("Before any building write, classify the task"));
        Assert.assertTrue(prompt.contains("Do not place blocks while this strategy is still unsettled"));
        Assert.assertTrue(prompt.contains("A single localized correction or one simple isolated edit may be executed directly"));
        Assert.assertTrue(prompt.contains("freely consider non-full blocks"));
        Assert.assertTrue(prompt.contains("optional design possibility, not a structural rule or constraint"));
        Assert.assertTrue(prompt.contains("choose whatever forms best fit the build"));
    }

    @Test
    public void attachesImagesToInitialAndResumedCommands() throws Exception {
        File image = File.createTempFile("buildermod-test-", ".png");
        try {
            Files.write(image.toPath(), new byte[] {1, 2, 3});
            File output = new File("reply.txt").getAbsoluteFile();
            List<String> command = CodexCliService.buildCommand(
                "codex.exe", output, "build this", "01a00432-dddb-7d43-90ef-134938942d78", false,
                Collections.singletonList(image)
            );
            int imageFlag = command.indexOf("--image");
            Assert.assertTrue(imageFlag > 0);
            Assert.assertEquals(image.getAbsolutePath(), command.get(imageFlag + 1));
            Assert.assertEquals("01a00432-dddb-7d43-90ef-134938942d78", command.get(command.size() - 2));
        } finally {
            image.delete();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedImageTypes() throws Exception {
        File image = File.createTempFile("buildermod-test-", ".txt");
        try {
            Files.write(image.toPath(), new byte[] {1});
            CodexCliService.validateImages(Collections.singletonList(image));
        } finally {
            image.delete();
        }
    }
}
