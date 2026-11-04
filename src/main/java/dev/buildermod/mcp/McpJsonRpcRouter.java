package dev.buildermod.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.buildermod.BuilderMod;

final class McpJsonRpcRouter {
    static final String MODERN_PROTOCOL_VERSION = "2026-07-28";
    static final String LEGACY_PROTOCOL_VERSION = "2025-11-25";
    private final McpToolExecutor toolExecutor;

    McpJsonRpcRouter() {
        this(new McpToolExecutor() {
            @Override
            public JsonObject call(String name, JsonObject arguments) throws Exception {
                throw new IllegalStateException("Minecraft tool executor is unavailable");
            }
        });
    }

    McpJsonRpcRouter(McpToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    RouteResult route(String json, String protocolHeader, String methodHeader) {
        final JsonObject request;
        try {
            JsonElement parsed = new com.google.gson.JsonParser().parse(json);
            if (!parsed.isJsonObject()) {
                return RouteResult.json(400, error(null, -32600, "Request must be a JSON object"));
            }
            request = parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            return RouteResult.json(400, error(null, -32700, "Parse error"));
        }

        JsonElement id = request.get("id");
        if (!hasString(request, "jsonrpc", "2.0") || !request.has("method") || !request.get("method").isJsonPrimitive()) {
            return RouteResult.json(400, error(id, -32600, "Invalid JSON-RPC request"));
        }

        String method = request.get("method").getAsString();
        if (methodHeader != null && !method.equals(methodHeader)) {
            return RouteResult.json(400, error(id, -32600, "Mcp-Method header does not match request method"));
        }

        boolean modern = MODERN_PROTOCOL_VERSION.equals(protocolHeader);
        if (protocolHeader != null && !modern && !isLegacyVersion(protocolHeader)) {
            JsonObject data = new JsonObject();
            data.add("supported", supportedVersions());
            return RouteResult.json(400, error(id, -32022, "Unsupported protocol version", data));
        }
        if (modern && methodHeader == null) {
            return RouteResult.json(400, error(id, -32600, "Mcp-Method header is required for protocol 2026-07-28"));
        }

        boolean notification = id == null || id.isJsonNull();
        if (notification) {
            return RouteResult.empty(202);
        }

        if ("server/discover".equals(method)) {
            return RouteResult.json(200, success(id, discoverResult()));
        }
        if ("initialize".equals(method)) {
            return RouteResult.json(200, success(id, initializeResult(request)));
        }
        if ("ping".equals(method)) {
            return RouteResult.json(200, success(id, new JsonObject()));
        }
        if ("tools/list".equals(method)) {
            return RouteResult.json(200, success(id, toolsListResult(modern)));
        }
        if ("tools/call".equals(method)) {
            return callTool(id, request);
        }
        return RouteResult.json(200, error(id, -32601, "Method not found"));
    }

    private JsonObject discoverResult() {
        JsonObject result = new JsonObject();
        result.addProperty("resultType", "complete");
        result.add("supportedVersions", supportedVersions());
        result.add("capabilities", capabilities());
        result.addProperty("instructions", instructions());
        result.addProperty("ttlMs", 0);
        result.addProperty("cacheScope", "private");
        result.add("_meta", serverMeta());
        return result;
    }

    private JsonObject initializeResult(JsonObject request) {
        String requested = LEGACY_PROTOCOL_VERSION;
        if (request.has("params") && request.get("params").isJsonObject()) {
            JsonObject params = request.getAsJsonObject("params");
            if (params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()) {
                String candidate = params.get("protocolVersion").getAsString();
                if (isLegacyVersion(candidate)) {
                    requested = candidate;
                }
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", requested);
        result.add("capabilities", capabilities());
        result.add("serverInfo", serverInfo());
        result.addProperty("instructions", instructions());
        return result;
    }

    private JsonObject toolsListResult(boolean modern) {
        JsonObject result = new JsonObject();
        result.add("tools", tools());
        if (modern) {
            result.addProperty("resultType", "complete");
            result.addProperty("ttlMs", 0);
            result.addProperty("cacheScope", "private");
            result.add("_meta", serverMeta());
        }
        return result;
    }

    private RouteResult callTool(JsonElement id, JsonObject request) {
        if (!request.has("params") || !request.get("params").isJsonObject()) {
            return RouteResult.json(200, error(id, -32602, "tools/call requires params"));
        }
        JsonObject params = request.getAsJsonObject("params");
        if (!params.has("name") || !params.get("name").isJsonPrimitive()) {
            return RouteResult.json(200, error(id, -32602, "tools/call requires a tool name"));
        }
        String name = params.get("name").getAsString();
        if (!isKnownTool(name)) {
            return RouteResult.json(200, error(id, -32602, "Unknown tool: " + name));
        }
        JsonObject arguments = new JsonObject();
        if (params.has("arguments")) {
            if (!params.get("arguments").isJsonObject()) {
                return RouteResult.json(200, error(id, -32602, "tool arguments must be an object"));
            }
            arguments = params.getAsJsonObject("arguments");
        }
        try {
            return RouteResult.json(200, success(id, toolExecutor.call(name, arguments)));
        } catch (Exception exception) {
            String message = exception.getMessage();
            return RouteResult.json(200, success(id, toolError(message == null ? "Tool execution failed" : message)));
        }
    }

    private JsonArray tools() {
        JsonArray tools = new JsonArray();
        tools.add(tool("get_thelow_wiki_page",
            "Read a current public page from TheLow development Wiki (wikiwiki.jp/thelow-dev). Before creating, explaining, or running a TheLow command or dungeon-development workflow, call this with no arguments to read the top page. Then call it with a relevant page name, such as command/sequencecommand, to verify exact syntax.",
            schema(new String[0], property("page", "string", "Optional relative Wiki page name; omit for the top page")), true));
        tools.add(tool("get_player_context",
            "Get the player's position, view direction, dimension, game mode, world time, weather, and targeted block.",
            objectSchema(), true));
        tools.add(tool("get_height_map",
            "Get compact terrain heights around X/Z. Output auto-selects raw or RLE. Top blocks are optional; radius max 32.",
            schema(new String[] {"center_x", "center_z"},
                property("center_x", "integer", "Center world X"),
                property("center_z", "integer", "Center world Z"),
                rangedProperty("radius", "integer", "Radius in blocks; default 8", 0, 32),
                rangedProperty("sample_step", "integer", "Sample spacing; default 1, use 2+ for broad surveys", 1, 8),
                property("include_top_blocks", "boolean", "Include surface block palette; default false")), true));
        tools.add(tool("get_blocks",
            "Read exact cuboid blocks as palette+RLE. Maximum volume 32768; max_runs limits worst-case output.",
            getBlocksSchema(), true));
        tools.add(tool("get_command_block",
            "Read the command text and execution status from one loaded Minecraft 1.8.9 command block. Fails if the position is not a command block.",
            schema(new String[] {"x", "y", "z"}, property("x", "integer", "World X"), rangedProperty("y", "integer", "World Y", 0, 255), property("z", "integer", "World Z")), true));
        tools.add(tool("get_command_blocks",
            "Read every command block in an inclusive loaded cuboid, including command text, track-output setting, success count, and latest output. Maximum volume 262144.",
            boundsSchema(), true));
        tools.add(tool("inspect_position",
            "Inspect one block position including state, material, light, sky visibility, biome, and tile-entity type.",
            schema(new String[] {"x", "y", "z"},
                property("x", "integer", "World X"),
                rangedProperty("y", "integer", "World Y", 0, 255),
                property("z", "integer", "World Z")), true));
        tools.add(tool("summarize_blocks",
            "Count block states in an inclusive cuboid without returning every block. Maximum volume 262144.",
            boundsSchema(), true));
        tools.add(tool("capture_view",
            "Render a PNG from a virtual camera without moving the player. Minecraft yaw 0 faces south (+Z), 90 faces west (-X); pitch 90 looks down.",
            schema(new String[] {"x", "y", "z", "yaw", "pitch"},
                property("x", "number", "Camera world X"),
                property("y", "number", "Camera world Y (exact eye position)"),
                property("z", "number", "Camera world Z"),
                property("yaw", "number", "Minecraft yaw in degrees"),
                rangedProperty("pitch", "number", "Pitch in degrees", -90, 90),
                rangedProperty("width", "integer", "Image width; default 320", 64, 512),
                rangedProperty("height", "integer", "Image height; default 180", 64, 512),
                rangedProperty("fov", "number", "Field of view; default 70", 30, 110)), true));
        tools.add(writeTool("local_paste_schematic",
            "Singleplayer only: paste a classic Minecraft 1.8.9 Alpha .schematic from the Minecraft schematics directory into the integrated server. Origin is the minimum corner; internal WorldEdit offsets are ignored. Maximum 65536 blocks; loaded chunks required. Entities and tile entities are rejected. Air is skipped by default. No undo.",
            schema(new String[] {"path", "x", "y", "z"},
                property("path", "string", "Relative .schematic path under the Minecraft schematics directory"),
                property("x", "integer", "Destination minimum X"), rangedProperty("y", "integer", "Destination minimum Y", 0, 255),
                property("z", "integer", "Destination minimum Z"), property("include_air", "boolean", "Paste air too; default false"))));
        tools.add(writeTool("local_fill_blocks",
            "For local singleplayer worlds only: directly update up to 65536 blocks in the integrated server, without commands or FAWE. Prefer this for cuboids in singleplayer. Changes are saved and synchronized; requires loaded server chunks. No undo. Modes: replace, keep, outline, hollow.",
            schema(new String[] {"min_x", "min_y", "min_z", "max_x", "max_y", "max_z", "block_id"},
                property("min_x", "integer", "Minimum world X"), rangedProperty("min_y", "integer", "Minimum world Y", 0, 255),
                property("min_z", "integer", "Minimum world Z"), property("max_x", "integer", "Maximum world X"),
                rangedProperty("max_y", "integer", "Maximum world Y", 0, 255), property("max_z", "integer", "Maximum world Z"),
                rangedProperty("block_id", "integer", "Numeric legacy block ID", 0, 4095),
                rangedProperty("data", "integer", "Legacy block metadata; default 0", 0, 15),
                enumProperty("mode", "Fill mode; default replace", "replace", "keep", "outline", "hollow"))));
        tools.add(writeTool("fawe_region_edit",
            "PREFERRED building tool for one cuboid operation. Efficiently select an inclusive cuboid with FAWE //pos1 and //pos2, then run one bounded //set, //replace, //walls, //faces, or //overlay operation. Prefer this over fill_blocks or sequence_set_blocks for ordinary regions. Maximum volume 2000000 blocks.",
            schema(new String[] {"min_x", "min_y", "min_z", "max_x", "max_y", "max_z", "operation", "pattern"},
                property("min_x", "integer", "Minimum world X"),
                rangedProperty("min_y", "integer", "Minimum world Y", 0, 255),
                property("min_z", "integer", "Minimum world Z"),
                property("max_x", "integer", "Maximum world X"),
                rangedProperty("max_y", "integer", "Maximum world Y", 0, 255),
                property("max_z", "integer", "Maximum world Z"),
                enumProperty("operation", "FAWE region operation", "set", "replace", "walls", "faces", "overlay"),
                property("pattern", "string", "Minecraft 1.8.9 numeric FAWE pattern, e.g. 1, 1:1, 70%1,30%4, or #checker[1,4]"),
                property("source_pattern", "string", "Numeric legacy FAWE source mask/pattern; required only for replace"))));
        tools.add(writeTool("fawe_workflow",
            "PREFERRED building tool for shapes, masks, patterns, terrain, clipboard operations, transforms, and coordinated edits. Run 1-6 validated FAWE steps in order; call this tool multiple times for longer builds. Global masks/transforms are cleared afterward by default. Prefer this over sequence_set_blocks whenever FAWE can express the build.",
            schema(new String[] {"steps"},
                faweStepsProperty(),
                property("clear_global_state", "boolean", "Clear any gmask, gsmask, and gtransform used by this workflow afterward; default true"))));
        tools.add(writeTool("set_block",
            "Send one Minecraft 1.8.9 /setblock command. Use only for a small exact correction when FAWE would be excessive. Use a numeric legacy block ID by default because this server may reject names as invalid_block.",
            schema(new String[] {"x", "y", "z", "block"},
                property("x", "integer", "World X"),
                rangedProperty("y", "integer", "World Y", 0, 255),
                property("z", "integer", "World Z"),
                property("block", "string", "Minecraft 1.8.9 numeric legacy block ID as a string, e.g. 1 for stone; use the data field for metadata"),
                rangedProperty("data", "integer", "Legacy block data; default 0", 0, 15),
                enumProperty("mode", "Placement mode; default replace", "replace", "destroy", "keep"))));
        tools.add(writeTool("place_command_block",
            "Place a Minecraft 1.8.9 impulse command block (legacy ID 137) at an exact coordinate, then set its Command NBT with /blockdata. Supply the command without a leading slash; a leading slash is removed. This requires server permission for both /setblock and /blockdata, and Minecraft chat commands are limited to 100 characters.",
            schema(new String[] {"x", "y", "z", "command"},
                property("x", "integer", "World X"), rangedProperty("y", "integer", "World Y", 0, 255),
                property("z", "integer", "World Z"), property("command", "string", "Command-block command, normally without a leading slash"),
                property("track_output", "boolean", "Store last command output; default true"))));
        tools.add(writeTool("fill_blocks",
            "Send one Minecraft 1.8.9 /fill command for at most 32768 blocks. Prefer fawe_region_edit for ordinary building regions; use this only when exact vanilla fill semantics are useful or FAWE is unsuitable. Use numeric legacy block IDs by default. Optional filter applies only in replace mode.",
            schema(new String[] {"min_x", "min_y", "min_z", "max_x", "max_y", "max_z", "block"},
                property("min_x", "integer", "Minimum world X"),
                rangedProperty("min_y", "integer", "Minimum world Y", 0, 255),
                property("min_z", "integer", "Minimum world Z"),
                property("max_x", "integer", "Maximum world X"),
                rangedProperty("max_y", "integer", "Maximum world Y", 0, 255),
                property("max_z", "integer", "Maximum world Z"),
                property("block", "string", "Minecraft 1.8.9 numeric legacy block ID, e.g. 1; metadata goes in data"),
                rangedProperty("data", "integer", "Legacy block data; default 0", 0, 15),
                enumProperty("mode", "Fill mode; default replace", "replace", "destroy", "keep", "hollow", "outline"),
                property("filter_block", "string", "Numeric legacy ID of the block to replace; replace mode only"),
                rangedProperty("filter_data", "integer", "Optional legacy data filter", 0, 15))));
        tools.add(writeTool("sequence_set_blocks",
            "LAST RESORT, not a general building tool. Send TheLow /sequence_setblock only for explicitly requested timed ordered placement, or a small sparse irregular coordinate list that FAWE cannot reasonably express. Never use it for walls, floors, regions, outlines, repeated shapes, or large builds; prefer fawe_region_edit/fawe_workflow. Uses numeric blockId[:data], and Minecraft 1.8.9 commands are limited to 100 characters.",
            schema(new String[] {"interval_seconds", "block_id", "positions"},
                rangedProperty("interval_seconds", "number", "Seconds between placements", 0, 3600),
                rangedProperty("block_id", "integer", "Numeric legacy block ID", 0, 4095),
                rangedProperty("data", "integer", "Legacy block data; default 0", 0, 15),
                positionsProperty())));
        return tools;
    }

    private JsonObject tool(String name, String description, JsonObject inputSchema, boolean readOnly) {
        return tool(name, description, inputSchema, readOnly, false, true, false);
    }

    private JsonObject writeTool(String name, String description, JsonObject inputSchema) {
        return tool(name, description, inputSchema, false, true, false, true);
    }

    private JsonObject tool(String name, String description, JsonObject inputSchema,
                            boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("inputSchema", inputSchema);
        JsonObject annotations = new JsonObject();
        annotations.addProperty("readOnlyHint", readOnly);
        annotations.addProperty("destructiveHint", destructive);
        annotations.addProperty("idempotentHint", idempotent);
        annotations.addProperty("openWorldHint", openWorld);
        tool.add("annotations", annotations);
        return tool;
    }

    private JsonObject boundsSchema() {
        return schema(new String[] {"min_x", "min_y", "min_z", "max_x", "max_y", "max_z"},
            property("min_x", "integer", "Minimum world X"),
            rangedProperty("min_y", "integer", "Minimum world Y", 0, 255),
            property("min_z", "integer", "Minimum world Z"),
            property("max_x", "integer", "Maximum world X"),
            rangedProperty("max_y", "integer", "Maximum world Y", 0, 255),
            property("max_z", "integer", "Maximum world Z"));
    }

    private JsonObject getBlocksSchema() {
        return schema(new String[] {"min_x", "min_y", "min_z", "max_x", "max_y", "max_z"},
            property("min_x", "integer", "Minimum world X"),
            rangedProperty("min_y", "integer", "Minimum world Y", 0, 255),
            property("min_z", "integer", "Minimum world Z"),
            property("max_x", "integer", "Maximum world X"),
            rangedProperty("max_y", "integer", "Maximum world Y", 0, 255),
            property("max_z", "integer", "Maximum world Z"),
            rangedProperty("max_runs", "integer", "Maximum RLE runs returned; default 4096", 128, 8192));
    }

    private JsonObject objectSchema() {
        return schema(new String[0]);
    }

    private JsonObject schema(String[] required, JsonObject... properties) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        JsonObject propertyMap = new JsonObject();
        for (JsonObject property : properties) {
            String name = property.remove("_name").getAsString();
            propertyMap.add(name, property);
        }
        schema.add("properties", propertyMap);
        if (required.length > 0) {
            JsonArray array = new JsonArray();
            for (String name : required) array.add(new JsonPrimitive(name));
            schema.add("required", array);
        }
        return schema;
    }

    private JsonObject property(String name, String type, String description) {
        JsonObject result = new JsonObject();
        result.addProperty("_name", name);
        result.addProperty("type", type);
        result.addProperty("description", description);
        return result;
    }

    private JsonObject rangedProperty(String name, String type, String description, int minimum, int maximum) {
        JsonObject result = property(name, type, description);
        result.addProperty("minimum", minimum);
        result.addProperty("maximum", maximum);
        return result;
    }

    private JsonObject enumProperty(String name, String description, String... values) {
        JsonObject result = property(name, "string", description);
        JsonArray choices = new JsonArray();
        for (String value : values) choices.add(new JsonPrimitive(value));
        result.add("enum", choices);
        return result;
    }

    private JsonObject positionsProperty() {
        JsonObject result = property("positions", "array", "Ordered coordinates to place the same block");
        result.addProperty("minItems", 1);
        result.addProperty("maxItems", 64);
        result.add("items", schema(new String[] {"x", "y", "z"},
            property("x", "integer", "World X"),
            rangedProperty("y", "integer", "World Y", 0, 255),
            property("z", "integer", "World Z")));
        return result;
    }

    private JsonObject faweStepsProperty() {
        JsonObject result = property("steps", "array", "Ordered FAWE operations; shape origins follow the server's current FAWE placement mode");
        result.addProperty("minItems", 1);
        result.addProperty("maxItems", 6);
        result.add("items", schema(new String[] {"op"},
            enumProperty("op", "Operation", "pos1", "pos2", "sel", "gmask", "gsmask", "gtransform",
                "set", "replace", "walls", "faces", "overlay", "center", "sphere", "cyl", "pyramid",
                "line", "curve", "hollow", "smooth", "naturalize", "lay", "fall", "forest", "flora",
                "setbiome", "generate", "deform", "expand", "contract", "outset", "inset", "shift",
                "move", "stack", "copy", "lazycopy", "cut", "lazycut", "paste", "place", "rotate",
                "flip", "clearclipboard", "undo", "redo"),
            property("x", "integer", "World X for pos1/pos2"),
            rangedProperty("y", "integer", "World Y for pos1/pos2", 0, 255),
            property("z", "integer", "World Z for pos1/pos2"),
            property("value", "string", "Mask or transform value; use Minecraft 1.8.9 numeric IDs inside masks; empty clears it"),
            property("pattern", "string", "FAWE block pattern using Minecraft 1.8.9 numeric legacy IDs, e.g. 1, 1:1, or 70%1,30%4"),
            property("source_pattern", "string", "Optional source mask using numeric legacy IDs for replace"),
            property("mask", "string", "Optional smooth/copy mask; use numeric legacy IDs for blocks"),
            property("expression", "string", "FAWE generate/deform expression"),
            property("leave_pattern", "string", "Optional numeric legacy block pattern left behind by cut"),
            property("type", "string", "Forest tree type; default tree"),
            property("biome", "string", "Biome name for setbiome"),
            rangedProperty("radius_x", "integer", "Primary radius", 1, 128),
            rangedProperty("radius_y", "integer", "Vertical sphere radius; defaults to radius_x", 1, 128),
            rangedProperty("radius_z", "integer", "Secondary horizontal radius; defaults to radius_x", 1, 128),
            rangedProperty("height", "integer", "Cylinder height; default 1", 1, 256),
            rangedProperty("size", "integer", "Pyramid size", 1, 128),
            rangedProperty("thickness", "integer", "Line/curve/hollow thickness; default 1", 1, 64),
            rangedProperty("iterations", "integer", "Smooth iterations; default 1", 1, 20),
            rangedProperty("amount", "integer", "Selection/edit distance or count", 1, 256),
            rangedProperty("reverse_amount", "integer", "Optional reverse expand/contract amount", 0, 256),
            rangedProperty("density", "integer", "Forest/flora density percentage; default 5", 0, 100),
            rangedProperty("angle_y", "integer", "Required clipboard Y rotation; multiple of 90", -360, 360),
            rangedProperty("angle_x", "integer", "Optional clipboard X rotation; multiple of 90", -360, 360),
            rangedProperty("angle_z", "integer", "Optional clipboard Z rotation; multiple of 90", -360, 360),
            enumProperty("direction", "FAWE direction", "up", "down", "north", "south", "east", "west", "me", "back", "left", "right"),
            enumProperty("selector", "Selection mode", "cuboid", "extend", "poly", "ellipsoid", "sphere", "cyl", "convex"),
            enumProperty("coordinate_mode", "Expression coordinates; default normalized", "normalized", "raw", "placement", "selection_center"),
            enumProperty("scope", "Outset/inset axes; default all", "all", "horizontal", "vertical"),
            property("hollow", "boolean", "Generate a hollow shape/line; default false"),
            property("raised", "boolean", "Use raised sphere generation; default false"),
            property("replace_air", "boolean", "Whether fall replaces air; default false"),
            property("selection_only", "boolean", "Restrict fall vertically to selection; default true"),
            property("select_after", "boolean", "Select clipboard result after paste/place; default false"),
            property("ignore_air", "boolean", "Skip air when pasting/placing; default false"),
            property("original_position", "boolean", "Paste/place at original copied position; default false")));
        return result;
    }

    private JsonObject toolError(String message) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", message);
        content.add(text);
        result.add("content", content);
        result.addProperty("isError", true);
        return result;
    }

    private boolean isKnownTool(String name) {
        return "get_thelow_wiki_page".equals(name)
            || "get_player_context".equals(name)
            || "get_height_map".equals(name)
            || "get_blocks".equals(name)
            || "get_command_block".equals(name)
            || "get_command_blocks".equals(name)
            || "inspect_position".equals(name)
            || "summarize_blocks".equals(name)
            || "capture_view".equals(name)
            || "local_paste_schematic".equals(name)
            || "local_fill_blocks".equals(name)
            || "set_block".equals(name)
            || "place_command_block".equals(name)
            || "fill_blocks".equals(name)
            || "sequence_set_blocks".equals(name)
            || "fawe_region_edit".equals(name)
            || "fawe_workflow".equals(name);
    }

    private String instructions() {
        return "Before any TheLow command, dungeon-development, or server-specific command request, first call get_thelow_wiki_page with no arguments. Read a relevant linked page too before generating syntax or executing a command; the public Wiki is the source of truth. This is Minecraft 1.8.9. For every placed, replaced, masked, or generated block, use numeric legacy block IDs by default; do not use modern or namespaced block names unless the player explicitly confirms support. "
            + "Use ID:data in FAWE patterns (for example 1, 1:1, 35:14, or 70%1,30%4) and use the separate data field in set_block/fill_blocks. Common IDs: air=0, stone=1, grass=2, dirt=3, cobblestone=4, planks=5, log=17, glass=20, wool=35, bricks=45, stonebrick=98, quartz=155, stained_clay=159. "
            + "Observe the loaded Minecraft client world, then build using the available tools. Use get_command_block or get_command_blocks to inspect existing command-block logic before editing or extending a command-driven mechanism. For substantial new builds, use the player's reference or generate a Minecraft-style 3D concept image if an image generation tool is available; if not, be honest and proceed with an internal design. Translate the target into a block-scale design, build, capture_view from a comparable viewpoint, compare silhouette, proportions, palette and details, and correct meaningful differences at milestones. Simple isolated edits do not need a concept image. Call get_player_context when coordinates are unknown. "
            + "For low token use: summarize_blocks first, then get_blocks only on needed subregions; omit height-map top blocks unless needed; "
            + "use sample_step for broad surveys and capture_view last. In singleplayer, for complex custom builds you may create a classic Alpha .schematic file in the Minecraft schematics directory and paste it with local_paste_schematic. For cuboids prefer local_fill_blocks; it directly updates the local world. In multiplayer FAWE is the default for building because it minimizes tool calls, commands, tokens, and time: prefer fawe_region_edit for cuboids and fawe_workflow for shapes, masks, patterns, terrain, clipboard operations, and coordinated steps; make multiple FAWE calls when needed. "
            + "Use set_block/fill_blocks only for small exact corrections or special vanilla semantics. Use place_command_block when a Minecraft 1.8.9 impulse command block must be placed with its command text; inspect it afterward with get_command_block when server permission allows. sequence_set_blocks is a last resort only for explicitly requested timed placement or small sparse irregular coordinates that FAWE cannot express; never use it for ordinary walls, floors, regions, outlines, repeated shapes, or large builds. "
            + "Before the first write, classify the request: a single localized correction may execute directly, but a build with multiple components, materials, shapes, stages, or expected tool calls requires a concise internal plan first. Gather only necessary context, then settle footprint/orientation, proportions, palette, components, FAWE operations, execution order, and verification milestones before placing blocks. Execute coarse-to-fine, verify after meaningful stages, and adapt remaining steps from observations; do not spend tokens narrating the internal plan unless asked. "
            + "When selecting blocks, freely consider non-full blocks such as stairs, slabs, fences, walls, panes, and trapdoors alongside full blocks if they naturally improve the result. This is an optional design possibility, not a structural rule or constraint; use whichever forms best fit the build. Apply Minecraft 1.8.9 numeric legacy IDs and metadata when needed. "
            + "Include explicit pos1/pos2 steps for selection-based edits and leave clear_global_state enabled unless persistence is intentional. All building tools write to the server and may require approval. "
            + "The client reports command transmission, not server success; inspect the world afterward to verify changes.";
    }

    private JsonObject capabilities() {
        JsonObject result = new JsonObject();
        result.add("tools", new JsonObject());
        return result;
    }

    private com.google.gson.JsonArray supportedVersions() {
        com.google.gson.JsonArray versions = new com.google.gson.JsonArray();
        versions.add(new JsonPrimitive(MODERN_PROTOCOL_VERSION));
        return versions;
    }

    private JsonObject serverMeta() {
        JsonObject meta = new JsonObject();
        meta.add("io.modelcontextprotocol/serverInfo", serverInfo());
        return meta;
    }

    private JsonObject serverInfo() {
        JsonObject info = new JsonObject();
        info.addProperty("name", "BuilderMod");
        info.addProperty("version", BuilderMod.VERSION);
        return info;
    }

    private boolean isLegacyVersion(String version) {
        return LEGACY_PROTOCOL_VERSION.equals(version)
            || "2025-06-18".equals(version)
            || "2025-03-26".equals(version)
            || "2024-11-05".equals(version);
    }

    private boolean hasString(JsonObject object, String name, String value) {
        return object.has(name) && object.get(name).isJsonPrimitive() && value.equals(object.get(name).getAsString());
    }

    private JsonObject success(JsonElement id, JsonObject result) {
        JsonObject response = baseResponse(id);
        response.add("result", result);
        return response;
    }

    private JsonObject error(JsonElement id, int code, String message) {
        return error(id, code, message, null);
    }

    private JsonObject error(JsonElement id, int code, String message, JsonObject data) {
        JsonObject detail = new JsonObject();
        detail.addProperty("code", code);
        detail.addProperty("message", message);
        if (data != null) {
            detail.add("data", data);
        }
        JsonObject response = baseResponse(id);
        response.add("error", detail);
        return response;
    }

    private JsonObject baseResponse(JsonElement id) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        return response;
    }

    static final class RouteResult {
        private final int statusCode;
        private final JsonObject body;

        private RouteResult(int statusCode, JsonObject body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        static RouteResult json(int statusCode, JsonObject body) {
            return new RouteResult(statusCode, body);
        }

        static RouteResult empty(int statusCode) {
            return new RouteResult(statusCode, null);
        }

        int getStatusCode() {
            return statusCode;
        }

        JsonObject getBody() {
            return body;
        }
    }
}
