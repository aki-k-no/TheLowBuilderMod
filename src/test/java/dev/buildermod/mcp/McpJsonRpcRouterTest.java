package dev.buildermod.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;

public final class McpJsonRpcRouterTest {
    @Test
    public void schematicPasteIsExposedAsDestructiveWriteAndDispatches() {
        McpJsonRpcRouter router = new McpJsonRpcRouter(new McpToolExecutor() {
            @Override public JsonObject call(String name, JsonObject arguments) {
                Assert.assertEquals("local_paste_schematic", name);
                Assert.assertEquals("house.schematic", arguments.get("path").getAsString());
                JsonObject result = new JsonObject(); result.addProperty("isError", false); return result;
            }
        });
        JsonObject listed = router.route("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
            McpJsonRpcRouter.LEGACY_PROTOCOL_VERSION, null).getBody().getAsJsonObject("result");
        boolean found = false;
        for (com.google.gson.JsonElement entry : listed.getAsJsonArray("tools")) {
            JsonObject tool = entry.getAsJsonObject();
            if ("local_paste_schematic".equals(tool.get("name").getAsString())) {
                found = true;
                Assert.assertTrue(tool.getAsJsonObject("annotations").get("destructiveHint").getAsBoolean());
                Assert.assertFalse(tool.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
            }
        }
        Assert.assertTrue(found);
        JsonObject response = router.route("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"local_paste_schematic\",\"arguments\":{\"path\":\"house.schematic\",\"x\":0,\"y\":64,\"z\":0}}}",
            McpJsonRpcRouter.LEGACY_PROTOCOL_VERSION, null).getBody().getAsJsonObject("result");
        Assert.assertFalse(response.get("isError").getAsBoolean());
    }

    @Test
    public void initializeRequiresPlanningBeforeLargeBuildWrites() {
        McpJsonRpcRouter router = new McpJsonRpcRouter();
        McpJsonRpcRouter.RouteResult response = router.route(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"" + McpJsonRpcRouter.LEGACY_PROTOCOL_VERSION + "\"}}",
            McpJsonRpcRouter.LEGACY_PROTOCOL_VERSION,
            null
        );
        String instructions = response.getBody().getAsJsonObject("result").get("instructions").getAsString();
        Assert.assertTrue(instructions.contains("Before the first write, classify the request"));
        Assert.assertTrue(instructions.contains("requires a concise internal plan first"));
        Assert.assertTrue(instructions.contains("single localized correction may execute directly"));
        Assert.assertTrue(instructions.contains("freely consider non-full blocks"));
        Assert.assertTrue(instructions.contains("optional design possibility, not a structural rule or constraint"));
        Assert.assertTrue(instructions.contains("whichever forms best fit the build"));
    }

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
        boolean teleportFound = false;
        boolean sheetFound = false;
        boolean sheetListFound = false;
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
            if ("teleport_player".equals(tool.get("name").getAsString())) {
                teleportFound = true;
                Assert.assertTrue(tool.getAsJsonObject("annotations").get("destructiveHint").getAsBoolean());
                Assert.assertFalse(tool.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
                Assert.assertTrue(tool.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("player"));
            }
            if ("read_config_sheet".equals(tool.get("name").getAsString())) {
                sheetFound = true;
                Assert.assertTrue(tool.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
                Assert.assertTrue(tool.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("range"));
                Assert.assertTrue(tool.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("sheet"));
            }
            if ("list_config_sheets".equals(tool.get("name").getAsString())) {
                sheetListFound = true;
                Assert.assertTrue(tool.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
            }
            if ("list_tabs".equals(tool.get("name").getAsString())
                || "read_config_rows".equals(tool.get("name").getAsString())
                || "search_sheet".equals(tool.get("name").getAsString())
                || "get_function".equals(tool.get("name").getAsString())) {
                Assert.assertTrue(tool.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
            }
        }
        Assert.assertTrue("sequence_set_blocks tool was not listed", sequenceFound);
        Assert.assertTrue("fawe_region_edit tool was not listed", faweFound);
        Assert.assertTrue("fawe_workflow tool was not listed", workflowFound);
        Assert.assertTrue("teleport_player tool was not listed", teleportFound);
        Assert.assertTrue("read_config_sheet tool was not listed", sheetFound);
        Assert.assertTrue("list_config_sheets tool was not listed", sheetListFound);
        java.util.Set<String> names = new java.util.HashSet<String>();
        for (JsonElement element : response.getBody().getAsJsonObject("result").getAsJsonArray("tools"))
            names.add(element.getAsJsonObject().get("name").getAsString());
        Assert.assertTrue(names.contains("list_tabs"));
        Assert.assertTrue(names.contains("read_config_rows"));
        Assert.assertTrue(names.contains("search_sheet"));
        Assert.assertTrue(names.contains("get_function"));
    }

    @Test
    public void listsFaweBeforeSequenceAndMarksSequenceAsLastResort() {
        McpJsonRpcRouter router = new McpJsonRpcRouter();
        McpJsonRpcRouter.RouteResult response = router.route(
            "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/list\",\"params\":{}}",
            McpJsonRpcRouter.LEGACY_PROTOCOL_VERSION,
            null
        );
        com.google.gson.JsonArray tools = response.getBody().getAsJsonObject("result").getAsJsonArray("tools");
        int faweRegionIndex = -1;
        int sequenceIndex = -1;
        String sequenceDescription = "";
        for (int i = 0; i < tools.size(); i++) {
            JsonObject tool = tools.get(i).getAsJsonObject();
            String name = tool.get("name").getAsString();
            if ("fawe_region_edit".equals(name)) faweRegionIndex = i;
            if ("sequence_set_blocks".equals(name)) {
                sequenceIndex = i;
                sequenceDescription = tool.get("description").getAsString();
            }
        }
        Assert.assertTrue(faweRegionIndex >= 0 && faweRegionIndex < sequenceIndex);
        Assert.assertTrue(sequenceDescription.contains("LAST RESORT"));
        Assert.assertTrue(sequenceDescription.contains("Never use it for walls"));
    }
}
