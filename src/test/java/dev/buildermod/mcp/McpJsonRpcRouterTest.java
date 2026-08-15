package dev.buildermod.mcp;

import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;

public final class McpJsonRpcRouterTest {
    @Test
    public void dispatchesKnownToolAndReturnsStructuredContent() {
        McpJsonRpcRouter router = new McpJsonRpcRouter(new McpToolExecutor() {
            @Override
            public JsonObject call(String name, JsonObject arguments) {
                Assert.assertEquals("inspect_position", name);
                Assert.assertEquals(12, arguments.get("x").getAsInt());
                JsonObject data = new JsonObject();
                data.addProperty("block", "minecraft:stone");
                JsonObject result = new JsonObject();
                result.add("structuredContent", data);
                result.addProperty("isError", false);
                return result;
            }
        });

        McpJsonRpcRouter.RouteResult response = router.route(
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{" +
                "\"name\":\"inspect_position\",\"arguments\":{\"x\":12,\"y\":64,\"z\":4}}}",
            McpJsonRpcRouter.LEGACY_PROTOCOL_VERSION,
            null
        );

        Assert.assertEquals(200, response.getStatusCode());
        Assert.assertEquals(
            "minecraft:stone",
            response.getBody().getAsJsonObject("result").getAsJsonObject("structuredContent").get("block").getAsString()
        );
    }

    @Test
    public void convertsToolFailuresToMcpToolErrors() {
        McpJsonRpcRouter router = new McpJsonRpcRouter(new McpToolExecutor() {
            @Override
            public JsonObject call(String name, JsonObject arguments) throws Exception {
                throw new Exception("No Minecraft world is currently loaded");
            }
        });

        McpJsonRpcRouter.RouteResult response = router.route(
            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{" +
                "\"name\":\"get_player_context\",\"arguments\":{}}}",
            McpJsonRpcRouter.LEGACY_PROTOCOL_VERSION,
            null
        );

        JsonObject result = response.getBody().getAsJsonObject("result");
        Assert.assertTrue(result.get("isError").getAsBoolean());
        Assert.assertTrue(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("No Minecraft world"));
    }

    @Test
    public void rejectsUnknownToolsBeforeExecution() {
        McpJsonRpcRouter router = new McpJsonRpcRouter();
        McpJsonRpcRouter.RouteResult response = router.route(
            "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{" +
                "\"name\":\"not_a_tool\",\"arguments\":{}}}",
            McpJsonRpcRouter.LEGACY_PROTOCOL_VERSION,
            null
        );

        Assert.assertEquals(-32602, response.getBody().getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void exposesBuildingToolsAsDestructiveWrites() {
        McpJsonRpcRouter router = new McpJsonRpcRouter();
        McpJsonRpcRouter.RouteResult response = router.route(
            "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/list\",\"params\":{}}",
            McpJsonRpcRouter.LEGACY_PROTOCOL_VERSION,
            null
        );

        boolean sequenceFound = false;
        boolean faweFound = false;
        boolean workflowFound = false;
        for (com.google.gson.JsonElement element : response.getBody().getAsJsonObject("result").getAsJsonArray("tools")) {
            JsonObject tool = element.getAsJsonObject();
            if ("sequence_set_blocks".equals(tool.get("name").getAsString())) {
                sequenceFound = true;
                JsonObject annotations = tool.getAsJsonObject("annotations");
                Assert.assertFalse(annotations.get("readOnlyHint").getAsBoolean());
                Assert.assertTrue(annotations.get("destructiveHint").getAsBoolean());
            }
            if ("fawe_region_edit".equals(tool.get("name").getAsString())) {
                faweFound = true;
                Assert.assertTrue(tool.getAsJsonObject("annotations").get("destructiveHint").getAsBoolean());
                JsonObject properties = tool.getAsJsonObject("inputSchema").getAsJsonObject("properties");
                Assert.assertTrue(properties.has("source_pattern"));
                Assert.assertTrue(properties.getAsJsonObject("pattern").get("description").getAsString().contains("70%1,30%4"));
            }
            if ("fawe_workflow".equals(tool.get("name").getAsString())) {
                workflowFound = true;
                JsonObject steps = tool.getAsJsonObject("inputSchema").getAsJsonObject("properties").getAsJsonObject("steps");
                Assert.assertEquals(6, steps.get("maxItems").getAsInt());
                JsonObject stepProperties = steps.getAsJsonObject("items").getAsJsonObject("properties");
                Assert.assertTrue(stepProperties.has("expression"));
                Assert.assertTrue(stepProperties.has("angle_y"));
                Assert.assertTrue(stepProperties.has("biome"));
                Assert.assertTrue(tool.getAsJsonObject("annotations").get("destructiveHint").getAsBoolean());
            }
        }
        Assert.assertTrue("sequence_set_blocks tool was not listed", sequenceFound);
        Assert.assertTrue("fawe_region_edit tool was not listed", faweFound);
        Assert.assertTrue("fawe_workflow tool was not listed", workflowFound);
    }
}
