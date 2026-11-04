package dev.buildermod.client;

import com.google.gson.JsonObject;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public final class MinecraftBuildCommandTest {
    @Test
    public void formatsSetBlockCommand() {
        Assert.assertEquals(
            "/setblock 20 60 -4 17 2 keep",
            MinecraftToolExecutor.buildSetBlockCommand(20, 60, -4, "17", 2, "keep")
        );
    }

    @Test
    public void formatsCommandBlockNbtWithEscapedCommand() {
        Assert.assertEquals(
            "/blockdata 20 60 -4 {Command:\"say \\\"hello\\\"\",TrackOutput:0b}",
            MinecraftToolExecutor.buildCommandBlockDataCommand(20, 60, -4, "say \"hello\"", false)
        );
    }

    @Test
    public void formatsFilteredFillCommand() {
        MinecraftToolExecutor.Bounds bounds = new MinecraftToolExecutor.Bounds(1, 2, 3, 4, 5, 6, 48);
        Assert.assertEquals(
            "/fill 1 2 3 4 5 6 1 0 replace 3 1",
            MinecraftToolExecutor.buildFillCommand(bounds, "1", 0, "replace", "3", 1)
        );
    }

    @Test
    public void formatsSequenceSetBlockLikeTheLowSpecification() {
        Assert.assertEquals(
            "/sequence_setblock 1 5:2 20 60 20 & 20 61 20",
            MinecraftToolExecutor.buildSequenceCommand(
                1.0D,
                5,
                2,
                Arrays.asList(
                    new MinecraftToolExecutor.SequencePosition(20, 60, 20),
                    new MinecraftToolExecutor.SequencePosition(20, 61, 20)
                )
            )
        );
    }

    @Test
    public void omitsZeroDataAndKeepsDecimalInterval() {
        Assert.assertEquals(
            "/sequence_setblock 0.25 1 0 64 0",
            MinecraftToolExecutor.buildSequenceCommand(
                0.25D,
                1,
                0,
                Arrays.asList(new MinecraftToolExecutor.SequencePosition(0, 64, 0))
            )
        );
    }

    @Test
    public void formatsBoundedFaweRegionEditCommands() {
        MinecraftToolExecutor.Bounds bounds = new MinecraftToolExecutor.Bounds(1, 2, 3, 4, 5, 6, 48);
        Assert.assertEquals(
            Arrays.asList("//pos1 1,2,3", "//pos2 4,5,6", "//set 70%1,30%4"),
            MinecraftToolExecutor.buildFaweRegionCommands(bounds, "set", "70%1,30%4", null)
        );
        Assert.assertEquals(
            Arrays.asList("//pos1 1,2,3", "//pos2 4,5,6", "//replace air glass"),
            MinecraftToolExecutor.buildFaweRegionCommands(bounds, "replace", "glass", "air")
        );
    }

    @Test
    public void formatsComposableFaweWorkflowSteps() throws Exception {
        MinecraftToolExecutor executor = new MinecraftToolExecutor(new ClientTaskQueue());

        JsonObject mask = new JsonObject();
        mask.addProperty("value", "#surface&!air");
        Assert.assertEquals("//gmask #surface&!air", executor.buildFaweWorkflowStep(mask, "gmask", 0));

        JsonObject sphere = new JsonObject();
        sphere.addProperty("pattern", "70%1,30%4");
        sphere.addProperty("radius_x", 8);
        sphere.addProperty("radius_y", 5);
        sphere.addProperty("radius_z", 8);
        sphere.addProperty("hollow", true);
        Assert.assertEquals(
            "//hsphere 70%1,30%4 8,5,8",
            executor.buildFaweWorkflowStep(sphere, "sphere", 1)
        );

        JsonObject cylinder = new JsonObject();
        cylinder.addProperty("pattern", "20");
        cylinder.addProperty("radius_x", 6);
        cylinder.addProperty("radius_z", 4);
        cylinder.addProperty("height", 12);
        Assert.assertEquals("//cyl 20 6,4 12", executor.buildFaweWorkflowStep(cylinder, "cyl", 2));
    }

    @Test
    public void formatsClipboardTerrainAndExpressionSteps() throws Exception {
        MinecraftToolExecutor executor = new MinecraftToolExecutor(new ClientTaskQueue());

        JsonObject rotate = new JsonObject();
        rotate.addProperty("angle_y", 90);
        rotate.addProperty("angle_x", 180);
        Assert.assertEquals("//rotate 90 180 0", executor.buildFaweWorkflowStep(rotate, "rotate", 0));

        JsonObject paste = new JsonObject();
        paste.addProperty("ignore_air", true);
        paste.addProperty("original_position", true);
        Assert.assertEquals("//paste -a -o", executor.buildFaweWorkflowStep(paste, "paste", 1));

        JsonObject generate = new JsonObject();
        generate.addProperty("pattern", "1");
        generate.addProperty("expression", "x*x+y*y+z*z<1");
        generate.addProperty("coordinate_mode", "selection_center");
        generate.addProperty("hollow", true);
        Assert.assertEquals(
            "//generate 1 x*x+y*y+z*z<1 -h -c",
            executor.buildFaweWorkflowStep(generate, "generate", 2)
        );

        JsonObject biome = new JsonObject();
        biome.addProperty("biome", "minecraft:plains");
        Assert.assertEquals("//setbiome minecraft:plains", executor.buildFaweWorkflowStep(biome, "setbiome", 3));

        JsonObject outset = new JsonObject();
        outset.addProperty("amount", 4);
        outset.addProperty("scope", "horizontal");
        Assert.assertEquals("//outset 4 -h", executor.buildFaweWorkflowStep(outset, "outset", 4));
    }

    @Test
    public void recognizesFaweFailureMessages() {
        Assert.assertTrue(MinecraftToolExecutor.looksLikeFaweFailure("FAWE Error: invalid pattern"));
        Assert.assertTrue(MinecraftToolExecutor.looksLikeFaweFailure("\\u6a29\\u9650\\u304c\\u3042\\u308a\\u307e\\u305b\\u3093"));
        Assert.assertFalse(MinecraftToolExecutor.looksLikeFaweFailure("123 blocks changed."));
    }

    @Test
    public void extractsClaudeAssistantTextFromStreamEvent() {
        JsonObject event = new com.google.gson.JsonParser().parse(
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hello from Claude\"}]}}"
        ).getAsJsonObject();
        Assert.assertEquals("hello from Claude", CodexCliService.claudeAssistantText(event));
    }

    @Test
    public void extractsNestedClaudeResultText() {
        JsonObject event = new com.google.gson.JsonParser().parse(
            "{\"type\":\"result\",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"nested reply\"}]}}"
        ).getAsJsonObject();
        Assert.assertEquals("nested reply", CodexCliService.claudeAssistantText(event));
    }

    @Test
    public void extractsStringClaudeResultText() {
        JsonObject event = new com.google.gson.JsonParser().parse(
            "{\"type\":\"result\",\"result\":\"plain reply\"}"
        ).getAsJsonObject();
        Assert.assertEquals("plain reply", CodexCliService.claudeAssistantText(event));
    }

    @Test
    public void reportsClaudeApiRetryFromStreamEvent() {
        JsonObject event = new com.google.gson.JsonParser().parse(
            "{\"type\":\"system\",\"subtype\":\"api_retry\",\"attempt\":3,\"error\":\"unknown\"}"
        ).getAsJsonObject();
        Assert.assertTrue(CodexCliService.claudeStreamFailure(event).contains("api_retry"));
    }
}
