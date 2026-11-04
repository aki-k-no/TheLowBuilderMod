package dev.buildermod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.buildermod.mcp.CompactIntArray;
import dev.buildermod.mcp.McpToolExecutor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CompressedStreamTools;
import java.math.BigDecimal;
import java.nio.IntBuffer;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityCommandBlock;
import net.minecraft.command.server.CommandBlockLogic;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.WorldServer;
import net.minecraft.server.integrated.IntegratedServer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/** Minecraft MCP observation and structured building tools. All game access runs on the client thread. */
public final class MinecraftToolExecutor implements McpToolExecutor {
    private static final long CLIENT_TASK_TIMEOUT_SECONDS = 20L;
    private static final int MAX_HEIGHT_RADIUS = 32;
    private static final int MAX_BLOCK_VOLUME = 32768;
    private static final int MAX_BLOCK_RUNS = 8192;
    private static final int MAX_SUMMARY_VOLUME = 262144;
    private static final int MAX_FAWE_REGION_VOLUME = 2000000;
    private static final int MAX_FAWE_WORKFLOW_STEPS = 6;
    private static final int MAX_FAWE_RADIUS = 128;
    private static final int MAX_CAPTURE_DIMENSION = 512;
    private static final int MAX_CAPTURE_PIXELS = 262144;
    private static final int MAX_CHAT_COMMAND_LENGTH = 100;
    private static final long FAWE_FEEDBACK_TIMEOUT_MILLIS = 1500L;

    private final ClientTaskQueue taskQueue;
    private final ServerChatFeedback serverChatFeedback;

    public MinecraftToolExecutor(ClientTaskQueue taskQueue) {
        this(taskQueue, new ServerChatFeedback());
    }

    public MinecraftToolExecutor(ClientTaskQueue taskQueue, ServerChatFeedback serverChatFeedback) {
        this.taskQueue = taskQueue;
        this.serverChatFeedback = serverChatFeedback;
    }

    @Override
    public JsonObject call(final String name, final JsonObject arguments) throws Exception {
        if ("get_thelow_wiki_page".equals(name)) return TheLowWikiReader.read(arguments);
        if ("local_paste_schematic".equals(name)) return localPasteSchematic(arguments);
        if ("local_fill_blocks".equals(name)) return localFillBlocks(arguments);
        if ("fawe_region_edit".equals(name) || "fawe_workflow".equals(name)) return callFaweWithFeedback(name, arguments);
        return onClientThread(new Callable<JsonObject>() {
            @Override
            public JsonObject call() throws Exception {
                if ("get_player_context".equals(name)) return getPlayerContext();
                if ("get_height_map".equals(name)) return getHeightMap(arguments);
                if ("get_blocks".equals(name)) return getBlocks(arguments);
                if ("inspect_position".equals(name)) return inspectPosition(arguments);
                if ("get_command_block".equals(name)) return getCommandBlock(arguments);
                if ("get_command_blocks".equals(name)) return getCommandBlocks(arguments);
                if ("summarize_blocks".equals(name)) return summarizeBlocks(arguments);
                if ("capture_view".equals(name)) return captureView(arguments);
                if ("set_block".equals(name)) return setBlock(arguments);
                if ("place_command_block".equals(name)) return placeCommandBlock(arguments);
                if ("fill_blocks".equals(name)) return fillBlocks(arguments);
                if ("sequence_set_blocks".equals(name)) return sequenceSetBlocks(arguments);
                throw new ToolFailure("Unknown tool: " + name);
            }
        });
    }

    private JsonObject callFaweWithFeedback(final String name, final JsonObject arguments) throws Exception {
        final ServerChatFeedback.Capture capture = serverChatFeedback.begin();
        JsonObject response;
        try {
            response = onClientThread(new Callable<JsonObject>() {
                @Override public JsonObject call() throws Exception {
                    if ("fawe_region_edit".equals(name)) return faweRegionEdit(arguments);
                    return faweWorkflow(arguments);
                }
            });
        } catch (Exception exception) {
            serverChatFeedback.finish(capture, 0L);
            throw exception;
        }
        List<String> messages = serverChatFeedback.finish(capture, FAWE_FEEDBACK_TIMEOUT_MILLIS);
        JsonObject data = response.getAsJsonObject("structuredContent");
        JsonArray feedback = new JsonArray();
        boolean failure = false;
        String failureMessage = null;
        for (String message : messages) {
            feedback.add(new com.google.gson.JsonPrimitive(message));
            if (looksLikeFaweFailure(message)) {
                failure = true;
                failureMessage = message;
            }
        }
        data.add("server_messages", feedback);
        data.addProperty("server_confirmed", !messages.isEmpty());
        data.addProperty("server_reported_error", failure);
        if (failure) {
            response.addProperty("isError", true);
            response.getAsJsonArray("content").get(0).getAsJsonObject().addProperty("text",
                "FAWE reported an error: " + failureMessage);
        } else if (!messages.isEmpty()) {
            response.getAsJsonArray("content").get(0).getAsJsonObject().addProperty("text",
                "FAWE server response received: " + messages.get(messages.size() - 1));
        }
        return response;
    }

    static boolean looksLikeFaweFailure(String message) {
        String text = message.toLowerCase(java.util.Locale.ROOT);
        return text.contains("error") || text.contains("exception") || text.contains("failed")
            || text.contains("cannot") || text.contains("not permitted") || text.contains("unknown command")
            || message.contains("\\u30a8\\u30e9\\u30fc") || message.contains("\\u5931\\u6557") || message.contains("\\u6a29\\u9650")
            || message.contains("\\u3067\\u304d\\u307e\\u305b\\u3093") || message.contains("\\u4e0d\\u660e\\u306a\\u30b3\\u30de\\u30f3\\u30c9");
    }

    private JsonObject getPlayerContext() throws ToolFailure {
        Minecraft mc = requireWorld();
        JsonObject data = new JsonObject();
        data.addProperty("x", mc.thePlayer.posX);
        data.addProperty("y", mc.thePlayer.posY);
        data.addProperty("z", mc.thePlayer.posZ);
        data.addProperty("yaw", mc.thePlayer.rotationYaw);
        data.addProperty("pitch", mc.thePlayer.rotationPitch);
        data.addProperty("singleplayer", mc.isSingleplayer() && mc.getIntegratedServer() != null);
        data.addProperty("dimension_id", mc.thePlayer.dimension);
        data.addProperty("dimension_name", mc.theWorld.provider.getDimensionName());
        data.addProperty("game_mode", mc.playerController.getCurrentGameType().getName());
        data.addProperty("render_distance_chunks", mc.gameSettings.renderDistanceChunks);
        data.addProperty("world_time", mc.theWorld.getWorldTime());
        data.addProperty("raining", mc.theWorld.isRaining());
        MovingObjectPosition target = mc.objectMouseOver;
        if (target != null && target.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            data.add("targeted_block", position(target.getBlockPos()));
        }
        return result(data, "Current Minecraft player and world context.");
    }

    private JsonObject getHeightMap(JsonObject arguments) throws ToolFailure {
        Minecraft mc = requireWorld();
        int centerX = requiredInt(arguments, "center_x");
        int centerZ = requiredInt(arguments, "center_z");
        int radius = optionalInt(arguments, "radius", 8);
        int sampleStep = optionalInt(arguments, "sample_step", 1);
        boolean includeTopBlocks = optionalBoolean(arguments, "include_top_blocks", false);
        if (radius < 0 || radius > MAX_HEIGHT_RADIUS) {
            throw new ToolFailure("radius must be between 0 and " + MAX_HEIGHT_RADIUS);
        }
        if (sampleStep < 1 || sampleStep > 8) {
            throw new ToolFailure("sample_step must be between 1 and 8");
        }
        int minX = centerX - radius;
        int maxX = centerX + radius;
        int minZ = centerZ - radius;
        int maxZ = centerZ + radius;
        requireChunksLoaded(mc.theWorld, minX, maxX, minZ, maxZ);
        JsonObject data = new JsonObject();
        data.addProperty("min_x", minX);
        data.addProperty("min_z", minZ);
        data.addProperty("width", (maxX - minX) / sampleStep + 1);
        data.addProperty("depth", (maxZ - minZ) / sampleStep + 1);
        data.addProperty("sample_step", sampleStep);
        data.addProperty("order", "x fastest, then z; start at min_x,min_z and advance by sample_step");
        data.addProperty("height_definition", "highest light-blocking block y; -1 means no surface");
        List<Integer> heights = new ArrayList<Integer>();
        List<Integer> topBlocks = new ArrayList<Integer>();
        Map<String, Integer> palette = new LinkedHashMap<String, Integer>();
        for (int z = minZ; z <= maxZ; z += sampleStep) {
            for (int x = minX; x <= maxX; x += sampleStep) {
                int y = mc.theWorld.getChunkFromBlockCoords(new BlockPos(x, 0, z)).getHeightValue(x & 15, z & 15) - 1;
                heights.add(y);
                if (includeTopBlocks) {
                    String state = y < 0 ? "minecraft:air" : stateName(mc.theWorld.getBlockState(new BlockPos(x, y, z)));
                    topBlocks.add(paletteIndex(palette, state));
                }
            }
        }
        data.add("heights", CompactIntArray.encode(heights));
        if (includeTopBlocks) {
            data.add("top_block_palette", stringArray(palette.keySet()));
            data.add("top_block_indices", CompactIntArray.encode(topBlocks));
        }
        return result(data, "Height map captured for " + data.get("width").getAsInt() + "x" + data.get("depth").getAsInt() + " columns.");
    }

    private JsonObject getBlocks(JsonObject arguments) throws ToolFailure {
        Minecraft mc = requireWorld();
        Bounds bounds = bounds(arguments, MAX_BLOCK_VOLUME);
        int maxRuns = optionalInt(arguments, "max_runs", 4096);
        if (maxRuns < 128 || maxRuns > MAX_BLOCK_RUNS) {
            throw new ToolFailure("max_runs must be between 128 and " + MAX_BLOCK_RUNS);
        }
        requireChunksLoaded(mc.theWorld, bounds.minX, bounds.maxX, bounds.minZ, bounds.maxZ);
        Map<String, Integer> palette = new LinkedHashMap<String, Integer>();
        JsonArray runs = new JsonArray();
        int previous = -1;
        int count = 0;
        for (int y = bounds.minY; y <= bounds.maxY; y++) {
            for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                for (int x = bounds.minX; x <= bounds.maxX; x++) {
                    int current = paletteIndex(palette, stateName(mc.theWorld.getBlockState(new BlockPos(x, y, z))));
                    if (current == previous) count++;
                    else {
                        appendRun(runs, previous, count);
                        if (runs.size() >= maxRuns) {
                            throw new ToolFailure("block data exceeds max_runs=" + maxRuns
                                + "; use summarize_blocks, increase max_runs, or request a smaller cuboid");
                        }
                        previous = current;
                        count = 1;
                    }
                }
            }
        }
        appendRun(runs, previous, count);
        JsonObject data = boundsJson(bounds);
        data.addProperty("order", "x fastest, then z, then y");
        data.addProperty("encoding", "runs are [palette_index,count]");
        data.add("palette", stringArray(palette.keySet()));
        data.add("runs", runs);
        return result(data, "Read " + bounds.volume + " blocks using palette and run-length encoding.");
    }

    private JsonObject getCommandBlock(JsonObject arguments) throws ToolFailure {
        Minecraft mc = requireWorld();
        int x = requiredInt(arguments, "x"), y = requiredInt(arguments, "y"), z = requiredInt(arguments, "z");
        checkY(y); BlockPos pos = new BlockPos(x, y, z); requireLoaded(mc.theWorld, pos);
        TileEntity tile = mc.theWorld.getTileEntity(pos);
        if (!(tile instanceof TileEntityCommandBlock)) throw new ToolFailure("No command block at " + x + "," + y + "," + z);
        JsonObject data = commandBlockJson(pos, (TileEntityCommandBlock) tile);
        return result(data, "Read command block at the requested position.");
    }

    private JsonObject getCommandBlocks(JsonObject arguments) throws ToolFailure {
        Minecraft mc = requireWorld(); Bounds area = bounds(arguments, MAX_SUMMARY_VOLUME);
        requireChunksLoaded(mc.theWorld, area.minX, area.maxX, area.minZ, area.maxZ);
        JsonArray blocks = new JsonArray();
        for (int y = area.minY; y <= area.maxY; y++) for (int z = area.minZ; z <= area.maxZ; z++) for (int x = area.minX; x <= area.maxX; x++) {
            TileEntity tile = mc.theWorld.getTileEntity(new BlockPos(x, y, z));
            if (tile instanceof TileEntityCommandBlock) blocks.add(commandBlockJson(new BlockPos(x, y, z), (TileEntityCommandBlock) tile));
        }
        JsonObject data = boundsJson(area); data.add("command_blocks", blocks); data.addProperty("count", blocks.size());
        return result(data, "Read " + blocks.size() + " command blocks in the requested region.");
    }

    private JsonObject commandBlockJson(BlockPos pos, TileEntityCommandBlock tile) {
        CommandBlockLogic logic = tile.getCommandBlockLogic(); JsonObject data = position(pos);
        data.addProperty("command", logic.getCommand()); data.addProperty("success_count", logic.getSuccessCount());
        data.addProperty("track_output", logic.shouldTrackOutput());
        if (logic.getLastOutput() != null) data.addProperty("last_output", logic.getLastOutput().getUnformattedText());
        return data;
    }

    private JsonObject inspectPosition(JsonObject arguments) throws ToolFailure {
        Minecraft mc = requireWorld();
        int x = requiredInt(arguments, "x");
        int y = requiredInt(arguments, "y");
        int z = requiredInt(arguments, "z");
        checkY(y);
        BlockPos pos = new BlockPos(x, y, z);
        requireLoaded(mc.theWorld, pos);
        IBlockState state = mc.theWorld.getBlockState(pos);
        BiomeGenBase biome = mc.theWorld.getBiomeGenForCoords(pos);
        JsonObject data = position(pos);
        data.addProperty("block", stateName(state));
        data.addProperty("is_air", state.getBlock().isAir(mc.theWorld, pos));
        data.addProperty("material", state.getBlock().getMaterial().toString());
        data.addProperty("light", mc.theWorld.getLight(pos));
        data.addProperty("sky_visible", mc.theWorld.canSeeSky(pos));
        data.addProperty("biome", biome.biomeName);
        data.addProperty("biome_id", biome.biomeID);
        if (mc.theWorld.getTileEntity(pos) != null) {
            data.addProperty("tile_entity", mc.theWorld.getTileEntity(pos).getClass().getSimpleName());
        }
        return result(data, "Detailed block information at the requested position.");
    }

    private JsonObject summarizeBlocks(JsonObject arguments) throws ToolFailure {
        Minecraft mc = requireWorld();
        Bounds bounds = bounds(arguments, MAX_SUMMARY_VOLUME);
        requireChunksLoaded(mc.theWorld, bounds.minX, bounds.maxX, bounds.minZ, bounds.maxZ);
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        int nonAir = 0;
        for (int y = bounds.minY; y <= bounds.maxY; y++) {
            for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                for (int x = bounds.minX; x <= bounds.maxX; x++) {
                    String state = stateName(mc.theWorld.getBlockState(new BlockPos(x, y, z)));
                    Integer old = counts.get(state);
                    counts.put(state, old == null ? 1 : old + 1);
                    if (!"minecraft:air".equals(state)) nonAir++;
                }
            }
        }
        JsonObject data = boundsJson(bounds);
        data.addProperty("non_air", nonAir);
        JsonObject countJson = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) countJson.addProperty(entry.getKey(), entry.getValue());
        data.add("counts", countJson);
        return result(data, "Summarized " + bounds.volume + " blocks across " + counts.size() + " states.");
    }

    private JsonObject localPasteSchematic(final JsonObject arguments) throws Exception {
        final String path = requiredString(arguments, "path");
        final int originX = requiredInt(arguments, "x"), originY = requiredInt(arguments, "y"), originZ = requiredInt(arguments, "z");
        final boolean includeAir = optionalBoolean(arguments, "include_air", false);
        final IntegratedServer[] server = new IntegratedServer[1];
        final int[] dimension = new int[1];
        final File[] root = new File[1];
        onClientThread(new Callable<JsonObject>() {
            @Override public JsonObject call() throws Exception {
                Minecraft mc = requireWorld();
                if (!mc.isSingleplayer() || mc.getIntegratedServer() == null)
                    throw new ToolFailure("local_paste_schematic requires a local singleplayer world");
                server[0] = mc.getIntegratedServer(); dimension[0] = mc.thePlayer.dimension;
                root[0] = new File(mc.mcDataDir, "schematics").getCanonicalFile();
                return new JsonObject();
            }
        });
        File file = new File(root[0], path).getCanonicalFile();
        if (new File(path).isAbsolute() || !file.toPath().startsWith(root[0].toPath()) || !file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".schematic"))
            throw new ToolFailure("path must be a .schematic file within the Minecraft schematics directory");
        if (!file.isFile() || file.length() > 4 * 1024 * 1024) throw new ToolFailure("Schematic missing or exceeds 4 MiB");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPInputStream input = new GZIPInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (bytes.size() + n > 8 * 1024 * 1024) throw new ToolFailure("Uncompressed schematic exceeds 8 MiB");
                bytes.write(buffer, 0, n);
            }
        }
        NBTTagCompound tag = CompressedStreamTools.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        if (!"Alpha".equals(tag.getString("Materials"))) throw new ToolFailure("Only classic Alpha .schematic files are supported");
        final int width = tag.getShort("Width"), height = tag.getShort("Height"), length = tag.getShort("Length");
        long volume = (long) width * height * length;
        if (width <= 0 || height <= 0 || length <= 0 || volume > 65536) throw new ToolFailure("Schematic dimensions must be positive and contain at most 65536 blocks");
        if (originY < 0 || (long) originY + height > 256 || originX < -30000000 || (long) originX + width > 30000000 || originZ < -30000000 || (long) originZ + length > 30000000)
            throw new ToolFailure("Schematic extends outside world coordinates");
        if (tag.getTagList("TileEntities", 10).tagCount() != 0 || tag.getTagList("Entities", 10).tagCount() != 0)
            throw new ToolFailure("Schematics containing entities or tile entities are not supported");
        final byte[] blocks = tag.getByteArray("Blocks"), metadata = tag.getByteArray("Data"), add = tag.getByteArray("AddBlocks");
        if (blocks.length != volume || metadata.length != volume || (add.length != 0 && add.length != (volume + 1) / 2))
            throw new ToolFailure("Invalid schematic block arrays");
        final IBlockState[] states = new IBlockState[(int) volume];
        for (int i = 0; i < states.length; i++) {
            int id = blocks[i] & 255;
            if (add.length != 0) id |= ((add[i / 2] >> ((i & 1) * 4)) & 15) << 8;
            Block block = Block.getBlockById(id);
            if (block == null || Block.getIdFromBlock(block) != id) throw new ToolFailure("Unknown schematic block ID " + id);
            int meta = metadata[i] & 255;
            if (meta > 15) throw new ToolFailure("Invalid schematic block metadata at index " + i);
            try { states[i] = block.getStateFromMeta(meta); }
            catch (RuntimeException e) { throw new ToolFailure("Invalid schematic block state at index " + i); }
            if (block.hasTileEntity(states[i])) throw new ToolFailure("Schematic tile entity blocks are not supported");
        }
        final com.google.common.util.concurrent.SettableFuture<JsonObject> completion = com.google.common.util.concurrent.SettableFuture.create();
        server[0].addScheduledTask(new Runnable() {
            @Override public void run() {
                try {
                    WorldServer world = server[0].worldServerForDimension(dimension[0]);
                    if (world == null) throw new ToolFailure("Singleplayer world is no longer available");
                    int maxX = originX + width - 1, maxZ = originZ + length - 1;
                    for (int z = originZ >> 4; z <= maxZ >> 4; z++) for (int x = originX >> 4; x <= maxX >> 4; x++)
                        if (!world.isBlockLoaded(new BlockPos((x << 4) + 8, 64, (z << 4) + 8), false)) throw new ToolFailure("Required server chunk is not loaded: " + x + "," + z);
                    if (!world.getWorldBorder().contains(new BlockPos(originX, originY, originZ)) || !world.getWorldBorder().contains(new BlockPos(maxX, originY + height - 1, maxZ))) throw new ToolFailure("Schematic extends outside the world border");
                    int changed = 0;
                    for (int y = 0; y < height; y++) for (int z = 0; z < length; z++) for (int x = 0; x < width; x++) {
                        IBlockState state = states[(y * length + z) * width + x];
                        if (!includeAir && state.getBlock() == net.minecraft.init.Blocks.air) continue;
                        if (world.setBlockState(new BlockPos(originX + x, originY + y, originZ + z), state, 3)) changed++;
                    }
                    JsonObject data = new JsonObject(); data.addProperty("changed_blocks", changed); data.addProperty("width", width); data.addProperty("height", height); data.addProperty("length", length);
                    completion.set(result(data, "Pasted schematic into the local world: " + changed + " blocks changed."));
                } catch (Throwable error) { completion.setException(error); }
            }
        });
        return completion.get();
    }

    private JsonObject localFillBlocks(final JsonObject arguments) throws Exception {
        final Bounds area = bounds(arguments, 65536);
        if (area.minX < -30000000 || area.maxX >= 30000000 || area.minZ < -30000000 || area.maxZ >= 30000000)
            throw new ToolFailure("Region is outside Minecraft world coordinates");
        final int blockId = requiredInt(arguments, "block_id");
        final int metadata = optionalInt(arguments, "data", 0);
        checkData(metadata);
        final String mode = optionalString(arguments, "mode", "replace");
        requireOneOf(mode, "mode", "replace", "keep", "outline", "hollow");
        final Block block = Block.getBlockById(blockId);
        if (block == null || Block.getIdFromBlock(block) != blockId) throw new ToolFailure("Unknown block_id: " + blockId);
        final IBlockState state;
        try { state = block.getStateFromMeta(metadata); }
        catch (RuntimeException e) { throw new ToolFailure("Invalid block metadata: " + metadata); }
        final IntegratedServer[] server = new IntegratedServer[1];
        final int[] dimension = new int[1];
        onClientThread(new Callable<JsonObject>() {
            @Override public JsonObject call() throws Exception {
                Minecraft mc = requireWorld();
                if (!mc.isSingleplayer() || mc.getIntegratedServer() == null)
                    throw new ToolFailure("local_fill_blocks requires a local singleplayer world");
                server[0] = mc.getIntegratedServer();
                dimension[0] = mc.thePlayer.dimension;
                return new JsonObject();
            }
        });
        final com.google.common.util.concurrent.SettableFuture<JsonObject> completion = com.google.common.util.concurrent.SettableFuture.create();
        server[0].addScheduledTask(new Runnable() {
            @Override public void run() {
              try {
                WorldServer world = server[0].worldServerForDimension(dimension[0]);
                if (world == null) throw new ToolFailure("Singleplayer world is no longer available");
                for (int z = area.minZ >> 4; z <= area.maxZ >> 4; z++)
                    for (int x = area.minX >> 4; x <= area.maxX >> 4; x++)
                        if (!world.isBlockLoaded(new BlockPos((x << 4) + 8, 64, (z << 4) + 8), false))
                            throw new ToolFailure("Required server chunk is not loaded: " + x + "," + z);
                if (!world.getWorldBorder().contains(new BlockPos(area.minX, area.minY, area.minZ)) ||
                    !world.getWorldBorder().contains(new BlockPos(area.maxX, area.maxY, area.maxZ)))
                    throw new ToolFailure("Region extends outside the world border");
                int changed = 0;
                for (int y = area.minY; y <= area.maxY; y++)
                    for (int z = area.minZ; z <= area.maxZ; z++)
                        for (int x = area.minX; x <= area.maxX; x++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            boolean edge = x == area.minX || x == area.maxX || y == area.minY || y == area.maxY || z == area.minZ || z == area.maxZ;
                            if ("outline".equals(mode) && !edge) continue;
                            if ("keep".equals(mode) && !world.isAirBlock(pos)) continue;
                            IBlockState target = "hollow".equals(mode) && !edge ? net.minecraft.init.Blocks.air.getDefaultState() : state;
                            if (world.setBlockState(pos, target, 3)) changed++;
                        }
                JsonObject data = boundsJson(area);
                data.addProperty("changed_blocks", changed);
                completion.set(result(data, "Updated " + changed + " blocks in the local world."));
              } catch (Throwable error) { completion.setException(error); }
            }
        });
        return completion.get();
    }

    private JsonObject setBlock(JsonObject arguments) throws ToolFailure {
        Minecraft mc = requireWorld();
        int x = requiredInt(arguments, "x");
        int y = requiredInt(arguments, "y");
        int z = requiredInt(arguments, "z");
        checkY(y);
        String block = validatedBlock(requiredString(arguments, "block"));
        int data = optionalInt(arguments, "data", 0);
        checkData(data);
        String mode = optionalString(arguments, "mode", "replace");
        requireOneOf(mode, "mode", "replace", "destroy", "keep");
        return sendBuildCommand(mc, buildSetBlockCommand(x, y, z, block, data, mode), 1, "set_block");
    }

    private JsonObject placeCommandBlock(JsonObject arguments) throws ToolFailure {
        Minecraft mc = requireWorld();
        int x = requiredInt(arguments, "x");
        int y = requiredInt(arguments, "y");
        int z = requiredInt(arguments, "z");
        checkY(y);
        String command = requiredString(arguments, "command");
        if (command.length() > 256 || command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0) {
            throw new ToolFailure("command must be at most 256 characters and must not contain line breaks");
        }
        if (command.startsWith("/")) command = command.substring(1);
        boolean trackOutput = optionalBoolean(arguments, "track_output", true);
        List<String> commands = new ArrayList<String>();
        commands.add(buildSetBlockCommand(x, y, z, "137", 0, "replace"));
        commands.add(buildCommandBlockDataCommand(x, y, z, command, trackOutput));
        return sendBuildCommands(mc, commands, 1, "place_command_block");
    }

    private JsonObject fillBlocks(JsonObject arguments) throws ToolFailure {
        Minecraft mc = requireWorld();
        Bounds bounds = bounds(arguments, MAX_BLOCK_VOLUME);
        String block = validatedBlock(requiredString(arguments, "block"));
        int data = optionalInt(arguments, "data", 0);
        checkData(data);
        String mode = optionalString(arguments, "mode", "replace");
        requireOneOf(mode, "mode", "replace", "destroy", "keep", "hollow", "outline");
        String filterBlock = null;
        Integer filterData = null;
        if (arguments.has("filter_block")) {
            if (!"replace".equals(mode)) {
                throw new ToolFailure("filter_block is only valid when mode is replace");
            }
            filterBlock = validatedBlock(requiredString(arguments, "filter_block"));
            if (arguments.has("filter_data")) {
                filterData = optionalInt(arguments, "filter_data", 0);
                checkData(filterData.intValue());
            }
        } else if (arguments.has("filter_data")) {
            throw new ToolFailure("filter_data requires filter_block");
        }
        String command = buildFillCommand(bounds, block, data, mode, filterBlock, filterData);
        return sendBuildCommand(mc, command, bounds.volume, "fill_blocks");
    }

    private JsonObject sequenceSetBlocks(JsonObject arguments) throws ToolFailure {
        Minecraft mc = requireWorld();
        double interval = requiredDouble(arguments, "interval_seconds");
        if (!Double.isFinite(interval) || interval < 0.0D || interval > 3600.0D) {
            throw new ToolFailure("interval_seconds must be between 0 and 3600");
        }
        int blockId = requiredInt(arguments, "block_id");
        if (blockId < 0 || blockId > 4095) {
            throw new ToolFailure("block_id must be a numeric legacy ID between 0 and 4095");
        }
        int data = optionalInt(arguments, "data", 0);
        checkData(data);
        JsonArray inputPositions = requiredArray(arguments, "positions");
        if (inputPositions.size() < 1 || inputPositions.size() > 64) {
            throw new ToolFailure("positions must contain between 1 and 64 coordinates");
        }
        List<SequencePosition> positions = new ArrayList<SequencePosition>();
        for (int i = 0; i < inputPositions.size(); i++) {
            JsonElement element = inputPositions.get(i);
            if (!element.isJsonObject()) {
                throw new ToolFailure("positions[" + i + "] must be an object");
            }
            JsonObject value = element.getAsJsonObject();
            int x = requiredInt(value, "x");
            int y = requiredInt(value, "y");
            int z = requiredInt(value, "z");
            checkY(y);
            positions.add(new SequencePosition(x, y, z));
        }
        String command = buildSequenceCommand(interval, blockId, data, positions);
        return sendBuildCommand(mc, command, positions.size(), "sequence_set_blocks");
    }

    private JsonObject faweRegionEdit(JsonObject arguments) throws ToolFailure {
        Minecraft mc = requireWorld();
        Bounds bounds = bounds(arguments, MAX_FAWE_REGION_VOLUME);
        String operation = optionalString(arguments, "operation", "set");
        requireOneOf(operation, "operation", "set", "replace", "walls", "faces", "overlay");
        String pattern = validatedFaweArgument(requiredString(arguments, "pattern"), "pattern");
        String sourcePattern = null;
        if ("replace".equals(operation)) {
            sourcePattern = validatedFaweArgument(requiredString(arguments, "source_pattern"), "source_pattern");
        } else if (arguments.has("source_pattern")) {
            throw new ToolFailure("source_pattern is only valid for the replace operation");
        }
        List<String> commands = buildFaweRegionCommands(bounds, operation, pattern, sourcePattern);
        return sendBuildCommands(mc, commands, bounds.volume, "fawe_region_edit");
    }

    private JsonObject faweWorkflow(JsonObject arguments) throws ToolFailure {
        Minecraft mc = requireWorld();
        JsonArray steps = requiredArray(arguments, "steps");
        if (steps.size() < 1 || steps.size() > MAX_FAWE_WORKFLOW_STEPS) {
            throw new ToolFailure("steps must contain between 1 and " + MAX_FAWE_WORKFLOW_STEPS + " operations");
        }
        boolean clearGlobalState = optionalBoolean(arguments, "clear_global_state", true);
        boolean usedGmask = false;
        boolean usedGsmask = false;
        boolean usedGtransform = false;
        List<String> commands = new ArrayList<String>();
        for (int i = 0; i < steps.size(); i++) {
            JsonElement element = steps.get(i);
            if (!element.isJsonObject()) throw new ToolFailure("steps[" + i + "] must be an object");
            JsonObject step = element.getAsJsonObject();
            String op = requiredString(step, "op").trim().toLowerCase(java.util.Locale.ROOT);
            commands.add(buildFaweWorkflowStep(step, op, i));
            if ("gmask".equals(op)) usedGmask = !optionalString(step, "value", "").trim().isEmpty();
            if ("gsmask".equals(op)) usedGsmask = !optionalString(step, "value", "").trim().isEmpty();
            if ("gtransform".equals(op)) usedGtransform = !optionalString(step, "value", "").trim().isEmpty();
        }
        if (clearGlobalState) {
            if (usedGmask) commands.add("//gmask");
            if (usedGsmask) commands.add("//gsmask");
            if (usedGtransform) commands.add("//gtransform");
        }
        return sendBuildCommands(mc, commands, 0, "fawe_workflow");
    }

    String buildFaweWorkflowStep(JsonObject step, String op, int index) throws ToolFailure {
        String prefix = "steps[" + index + "].";
        if ("pos1".equals(op) || "pos2".equals(op)) {
            int x = requiredInt(step, "x"), y = requiredInt(step, "y"), z = requiredInt(step, "z");
            checkY(y);
            return "//" + op + " " + x + "," + y + "," + z;
        }
        if ("gmask".equals(op) || "gsmask".equals(op) || "gtransform".equals(op)) {
            String value = optionalString(step, "value", "").trim();
            if (!value.isEmpty()) value = validatedFaweArgument(value, prefix + "value");
            return "//" + op + (value.isEmpty() ? "" : " " + value);
        }
        if ("set".equals(op) || "walls".equals(op) || "faces".equals(op)
            || "overlay".equals(op) || "center".equals(op)) {
            return "//" + op + " " + fawePattern(step, prefix);
        }
        if ("replace".equals(op)) {
            String pattern = fawePattern(step, prefix);
            String source = optionalString(step, "source_pattern", "").trim();
            if (!source.isEmpty()) source = validatedFaweArgument(source, prefix + "source_pattern");
            return "//replace " + (source.isEmpty() ? "" : source + " ") + pattern;
        }
        if ("sphere".equals(op)) {
            String command = optionalBoolean(step, "hollow", false) ? "//hsphere " : "//sphere ";
            int rx = rangedInt(step, "radius_x", 1, MAX_FAWE_RADIUS);
            int ry = optionalRangedInt(step, "radius_y", rx, 1, MAX_FAWE_RADIUS);
            int rz = optionalRangedInt(step, "radius_z", rx, 1, MAX_FAWE_RADIUS);
            command += fawePattern(step, prefix) + " " + rx + "," + ry + "," + rz;
            if (optionalBoolean(step, "raised", false)) command += " true";
            return command;
        }
        if ("cyl".equals(op)) {
            String command = optionalBoolean(step, "hollow", false) ? "//hcyl " : "//cyl ";
            int rx = rangedInt(step, "radius_x", 1, MAX_FAWE_RADIUS);
            int rz = optionalRangedInt(step, "radius_z", rx, 1, MAX_FAWE_RADIUS);
            int height = optionalRangedInt(step, "height", 1, 1, 256);
            return command + fawePattern(step, prefix) + " " + rx + "," + rz + " " + height;
        }
        if ("pyramid".equals(op)) {
            String command = optionalBoolean(step, "hollow", false) ? "//hpyramid " : "//pyramid ";
            return command + fawePattern(step, prefix) + " " + rangedInt(step, "size", 1, MAX_FAWE_RADIUS);
        }
        if ("line".equals(op) || "curve".equals(op)) {
            String command = "//" + op + " " + fawePattern(step, prefix) + " "
                + optionalRangedInt(step, "thickness", 1, 1, 64);
            return optionalBoolean(step, "hollow", false) ? command + " -h" : command;
        }
        if ("hollow".equals(op)) {
            return "//hollow " + optionalRangedInt(step, "thickness", 1, 1, 64) + " " + fawePattern(step, prefix);
        }
        if ("smooth".equals(op)) {
            String mask = optionalString(step, "mask", "").trim();
            if (!mask.isEmpty()) mask = validatedFaweArgument(mask, prefix + "mask");
            return "//smooth " + optionalRangedInt(step, "iterations", 1, 1, 20)
                + (mask.isEmpty() ? "" : " " + mask);
        }
        if ("naturalize".equals(op) || "undo".equals(op) || "redo".equals(op)) {
            if ("undo".equals(op) || "redo".equals(op)) {
                int times = optionalRangedInt(step, "amount", 1, 1, 20);
                return "//" + op + (times == 1 ? "" : " " + times);
            }
            return "//naturalize";
        }
        if ("lay".equals(op)) {
            return "//lay " + fawePattern(step, prefix);
        }
        if ("fall".equals(op)) {
            String command = "//fall " + optionalBoolean(step, "replace_air", false);
            return optionalBoolean(step, "selection_only", true) ? command + " -m" : command;
        }
        if ("forest".equals(op)) {
            String type = validatedSimpleName(optionalString(step, "type", "tree"), prefix + "type");
            int density = optionalRangedInt(step, "density", 5, 0, 100);
            return "//forest " + type + " " + density;
        }
        if ("flora".equals(op)) {
            return "//flora " + optionalRangedInt(step, "density", 5, 0, 100);
        }
        if ("setbiome".equals(op)) {
            return "//setbiome " + validatedSimpleName(requiredString(step, "biome"), prefix + "biome");
        }
        if ("generate".equals(op) || "deform".equals(op)) {
            String expression = validatedFaweArgument(requiredString(step, "expression"), prefix + "expression");
            String mode = optionalString(step, "coordinate_mode", "normalized").trim().toLowerCase(java.util.Locale.ROOT);
            requireOneOf(mode, prefix + "coordinate_mode", "normalized", "raw", "placement", "selection_center");
            if ("deform".equals(op) && "selection_center".equals(mode)) {
                throw new ToolFailure(prefix + "coordinate_mode selection_center is only valid for generate");
            }
            String flag = "raw".equals(mode) ? " -r" : ("placement".equals(mode) ? " -o" : ("selection_center".equals(mode) ? " -c" : ""));
            if ("generate".equals(op)) {
                String command = "//generate " + fawePattern(step, prefix) + " " + expression;
                if (optionalBoolean(step, "hollow", false)) command += " -h";
                return command + flag;
            }
            return "//deform " + expression + flag;
        }
        if ("copy".equals(op) || "lazycopy".equals(op)) {
            String mask = optionalString(step, "mask", "").trim();
            if (!mask.isEmpty()) mask = validatedFaweArgument(mask, prefix + "mask");
            return "//" + op + (mask.isEmpty() ? "" : " -m " + mask);
        }
        if ("cut".equals(op) || "lazycut".equals(op)) {
            String leave = optionalString(step, "leave_pattern", "").trim();
            if (!leave.isEmpty()) leave = validatedFaweArgument(leave, prefix + "leave_pattern");
            return "//" + op + (leave.isEmpty() ? "" : " " + leave);
        }
        if ("paste".equals(op) || "place".equals(op)) {
            String command = "//" + op;
            if (optionalBoolean(step, "select_after", false)) command += " -s";
            if (optionalBoolean(step, "ignore_air", false)) command += " -a";
            if (optionalBoolean(step, "original_position", false)) command += " -o";
            return command;
        }
        if ("rotate".equals(op)) {
            int y = rotation(step, "angle_y");
            int x = optionalRotation(step, "angle_x", 0);
            int z = optionalRotation(step, "angle_z", 0);
            return "//rotate " + y + (x == 0 && z == 0 ? "" : " " + x + " " + z);
        }
        if ("flip".equals(op)) {
            return "//flip " + validatedDirection(optionalString(step, "direction", "me"), prefix + "direction");
        }
        if ("clearclipboard".equals(op)) {
            return "/clearclipboard";
        }
        if ("expand".equals(op) || "contract".equals(op)) {
            int amount = rangedInt(step, "amount", 1, 256);
            int reverse = optionalRangedInt(step, "reverse_amount", 0, 0, 256);
            String direction = validatedDirection(requiredString(step, "direction"), prefix + "direction");
            return "//" + op + " " + amount + (reverse == 0 ? "" : " " + reverse) + " " + direction;
        }
        if ("shift".equals(op) || "move".equals(op) || "stack".equals(op)) {
            int amount = rangedInt(step, "amount", 1, 256);
            String direction = validatedDirection(requiredString(step, "direction"), prefix + "direction");
            return "//" + op + " " + amount + " " + direction;
        }
        if ("outset".equals(op) || "inset".equals(op)) {
            int amount = rangedInt(step, "amount", 1, 256);
            String scope = optionalString(step, "scope", "all").trim().toLowerCase(java.util.Locale.ROOT);
            requireOneOf(scope, prefix + "scope", "all", "horizontal", "vertical");
            String flag = "horizontal".equals(scope) ? " -h" : ("vertical".equals(scope) ? " -v" : "");
            return "//" + op + " " + amount + flag;
        }
        if ("sel".equals(op)) {
            String selector = requiredString(step, "selector").trim().toLowerCase(java.util.Locale.ROOT);
            requireOneOf(selector, prefix + "selector", "cuboid", "extend", "poly", "ellipsoid", "sphere", "cyl", "convex");
            return "//sel " + selector;
        }
        throw new ToolFailure(prefix + "op has an unsupported value: " + op);
    }

    private JsonObject sendBuildCommand(Minecraft mc, String command, int requestedBlocks, String tool) throws ToolFailure {
        validateCommandLength(command);
        mc.thePlayer.sendChatMessage(command);
        JsonObject data = new JsonObject();
        data.addProperty("sent", true);
        data.addProperty("tool", tool);
        data.addProperty("command", command);
        data.addProperty("command_length", command.length());
        data.addProperty("requested_blocks", requestedBlocks);
        data.addProperty("server_confirmed", false);
        return result(data, "Command sent to the Minecraft server. Server acceptance is not confirmed by the client.");
    }

    private JsonObject sendBuildCommands(Minecraft mc, List<String> commands, int requestedBlocks, String tool) throws ToolFailure {
        for (String command : commands) validateCommandLength(command);
        for (String command : commands) mc.thePlayer.sendChatMessage(command);
        JsonObject data = new JsonObject();
        JsonArray sentCommands = new JsonArray();
        for (String command : commands) sentCommands.add(new com.google.gson.JsonPrimitive(command));
        data.addProperty("sent", true);
        data.addProperty("tool", tool);
        data.add("commands", sentCommands);
        data.addProperty("command_count", commands.size());
        data.addProperty("requested_blocks", requestedBlocks);
        data.addProperty("server_confirmed", false);
        return result(data, commands.size() + " FAWE commands sent. Server acceptance is not confirmed by the client.");
    }

    private void validateCommandLength(String command) throws ToolFailure {
        if (command.length() > MAX_CHAT_COMMAND_LENGTH) {
            throw new ToolFailure("generated command is " + command.length() + " characters; Minecraft 1.8.9 allows "
                + MAX_CHAT_COMMAND_LENGTH + ". Use a shorter pattern or fewer sequence positions");
        }
    }

    static String buildSetBlockCommand(int x, int y, int z, String block, int data, String mode) {
        return "/setblock " + x + " " + y + " " + z + " " + block + " " + data + " " + mode;
    }

    static String buildCommandBlockDataCommand(int x, int y, int z, String command, boolean trackOutput) {
        String escaped = command.replace("\\", "\\\\").replace("\"", "\\\"");
        return "/blockdata " + x + " " + y + " " + z + " {Command:\"" + escaped + "\",TrackOutput:" + (trackOutput ? "1b" : "0b") + "}";
    }

    static String buildFillCommand(Bounds b, String block, int data, String mode, String filterBlock, Integer filterData) {
        StringBuilder command = new StringBuilder("/fill ")
            .append(b.minX).append(' ').append(b.minY).append(' ').append(b.minZ).append(' ')
            .append(b.maxX).append(' ').append(b.maxY).append(' ').append(b.maxZ).append(' ')
            .append(block).append(' ').append(data).append(' ').append(mode);
        if (filterBlock != null) {
            command.append(' ').append(filterBlock);
            if (filterData != null) command.append(' ').append(filterData.intValue());
        }
        return command.toString();
    }

    static String buildSequenceCommand(double interval, int blockId, int data, List<SequencePosition> positions) {
        StringBuilder command = new StringBuilder("/sequence_setblock ")
            .append(BigDecimal.valueOf(interval).stripTrailingZeros().toPlainString()).append(' ')
            .append(blockId);
        if (data != 0) command.append(':').append(data);
        for (int i = 0; i < positions.size(); i++) {
            SequencePosition pos = positions.get(i);
            command.append(i == 0 ? ' ' : " & ")
                .append(pos.x).append(' ').append(pos.y).append(' ').append(pos.z);
        }
        return command.toString();
    }

    static List<String> buildFaweRegionCommands(Bounds b, String operation, String pattern, String sourcePattern) {
        List<String> commands = new ArrayList<String>();
        commands.add("//pos1 " + b.minX + "," + b.minY + "," + b.minZ);
        commands.add("//pos2 " + b.maxX + "," + b.maxY + "," + b.maxZ);
        commands.add("replace".equals(operation)
            ? "//replace " + sourcePattern + " " + pattern
            : "//" + operation + " " + pattern);
        return commands;
    }

    private JsonObject captureView(JsonObject arguments) throws Exception {
        Minecraft mc = requireWorld();
        if (!OpenGlHelper.isFramebufferEnabled()) throw new ToolFailure("Framebuffer rendering is unavailable with the current video settings");
        double x = requiredDouble(arguments, "x");
        double y = requiredDouble(arguments, "y");
        double z = requiredDouble(arguments, "z");
        float yaw = (float) requiredDouble(arguments, "yaw");
        float pitch = (float) requiredDouble(arguments, "pitch");
        int width = optionalInt(arguments, "width", 320);
        int height = optionalInt(arguments, "height", 180);
        float fov = (float) optionalDouble(arguments, "fov", 70.0D);
        if (width < 64 || height < 64 || width > MAX_CAPTURE_DIMENSION || height > MAX_CAPTURE_DIMENSION || width * height > MAX_CAPTURE_PIXELS) {
            throw new ToolFailure("image dimensions must be 64..512 with at most " + MAX_CAPTURE_PIXELS + " pixels");
        }
        if (pitch < -90.0F || pitch > 90.0F || fov < 30.0F || fov > 110.0F) throw new ToolFailure("pitch must be -90..90 and fov must be 30..110");
        requireLoaded(mc.theWorld, new BlockPos(x, y, z));
        byte[] png = renderView(mc, x, y, z, yaw, pitch, width, height, fov);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("x", x);
        metadata.addProperty("y", y);
        metadata.addProperty("z", z);
        metadata.addProperty("yaw", yaw);
        metadata.addProperty("pitch", pitch);
        metadata.addProperty("fov", fov);
        metadata.addProperty("width", width);
        metadata.addProperty("height", height);
        JsonObject response = result(metadata, "Virtual camera view rendered in Minecraft.");
        JsonObject image = new JsonObject();
        image.addProperty("type", "image");
        image.addProperty("mimeType", "image/png");
        image.addProperty("data", Base64.getEncoder().encodeToString(png));
        response.getAsJsonArray("content").add(image);
        return response;
    }

    private byte[] renderView(Minecraft mc, double x, double y, double z, float yaw, float pitch, int width, int height, float fov) throws Exception {
        Entity oldView = mc.getRenderViewEntity();
        int oldWidth = mc.displayWidth;
        int oldHeight = mc.displayHeight;
        int oldThirdPerson = mc.gameSettings.thirdPersonView;
        float oldFov = mc.gameSettings.fovSetting;
        Framebuffer target = new Framebuffer(width, height, true);
        VirtualCamera camera = new VirtualCamera(mc.theWorld);
        camera.setLocationAndAngles(x, y, z, yaw, pitch);
        camera.lastTickPosX = camera.prevPosX = camera.posX;
        camera.lastTickPosY = camera.prevPosY = camera.posY;
        camera.lastTickPosZ = camera.prevPosZ = camera.posZ;
        camera.prevRotationYaw = yaw;
        camera.prevRotationPitch = pitch;
        try {
            mc.displayWidth = width;
            mc.displayHeight = height;
            mc.gameSettings.thirdPersonView = 0;
            mc.gameSettings.fovSetting = fov;
            mc.setRenderViewEntity(camera);
            target.setFramebufferColor(0.0F, 0.0F, 0.0F, 1.0F);
            target.bindFramebuffer(true);
            target.framebufferClear();
            mc.entityRenderer.renderWorld(1.0F, System.nanoTime() + 1000000000L);
            return readPng(width, height);
        } finally {
            mc.displayWidth = oldWidth;
            mc.displayHeight = oldHeight;
            mc.gameSettings.thirdPersonView = oldThirdPerson;
            mc.gameSettings.fovSetting = oldFov;
            mc.setRenderViewEntity(oldView == null ? mc.thePlayer : oldView);
            mc.getFramebuffer().bindFramebuffer(true);
            GlStateManager.viewport(0, 0, oldWidth, oldHeight);
            target.deleteFramebuffer();
        }
    }

    private byte[] readPng(int width, int height) throws Exception {
        IntBuffer pixels = BufferUtils.createIntBuffer(width * height);
        int[] values = new int[width * height];
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
        pixels.get(values);
        TextureUtil.processPixelValues(values, width, height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, values, 0, width);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private JsonObject onClientThread(final Callable<JsonObject> callable) throws Exception {
        final CountDownLatch complete = new CountDownLatch(1);
        final AtomicReference<JsonObject> result = new AtomicReference<JsonObject>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        taskQueue.submit(new Runnable() {
            @Override public void run() {
                try { result.set(callable.call()); }
                catch (Throwable throwable) { failure.set(throwable); }
                finally { complete.countDown(); }
            }
        });
        if (!complete.await(CLIENT_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw new ToolFailure("Minecraft client did not process the tool within " + CLIENT_TASK_TIMEOUT_SECONDS + " seconds");
        Throwable throwable = failure.get();
        if (throwable instanceof Exception) throw (Exception) throwable;
        if (throwable != null) throw new RuntimeException(throwable);
        return result.get();
    }

    private Minecraft requireWorld() throws ToolFailure {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) throw new ToolFailure("No Minecraft world is currently loaded");
        return mc;
    }

    private void requireChunksLoaded(WorldClient world, int minX, int maxX, int minZ, int maxZ) throws ToolFailure {
        for (int z = minZ >> 4; z <= maxZ >> 4; z++) for (int x = minX >> 4; x <= maxX >> 4; x++) {
            if (!world.isBlockLoaded(new BlockPos((x << 4) + 8, 64, (z << 4) + 8), false)) throw new ToolFailure("Required chunk is not loaded: " + x + "," + z);
        }
    }

    private void requireLoaded(WorldClient world, BlockPos pos) throws ToolFailure {
        if (!world.isBlockLoaded(pos, false)) throw new ToolFailure("Position is in an unloaded chunk: " + pos.getX() + "," + pos.getZ());
    }

    private Bounds bounds(JsonObject arguments, int maxVolume) throws ToolFailure {
        int minX = requiredInt(arguments, "min_x"), minY = requiredInt(arguments, "min_y"), minZ = requiredInt(arguments, "min_z");
        int maxX = requiredInt(arguments, "max_x"), maxY = requiredInt(arguments, "max_y"), maxZ = requiredInt(arguments, "max_z");
        checkY(minY); checkY(maxY);
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new ToolFailure("minimum coordinates must not exceed maximum coordinates");
        long sizeX = (long) maxX - minX + 1L;
        long sizeY = (long) maxY - minY + 1L;
        long sizeZ = (long) maxZ - minZ + 1L;
        if (sizeX > maxVolume || sizeY > maxVolume || sizeZ > maxVolume || sizeX * sizeY > maxVolume || sizeX * sizeY * sizeZ > maxVolume)
            throw new ToolFailure("requested volume exceeds limit " + maxVolume);
        long volume = sizeX * sizeY * sizeZ;
        if (volume > maxVolume) throw new ToolFailure("requested volume " + volume + " exceeds limit " + maxVolume);
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ, (int) volume);
    }

    private void checkY(int y) throws ToolFailure { if (y < 0 || y > 255) throw new ToolFailure("y must be between 0 and 255"); }

    private String stateName(IBlockState state) {
        Block block = state.getBlock();
        Object name = Block.blockRegistry.getNameForObject(block);
        String base = name == null ? "unknown" : name.toString();
        int metadata = block.getMetaFromState(state);
        return metadata == 0 ? base : base + "#" + metadata;
    }

    private int paletteIndex(Map<String, Integer> palette, String state) {
        Integer index = palette.get(state);
        if (index == null) { index = palette.size(); palette.put(state, index); }
        return index;
    }

    private void appendRun(JsonArray runs, int paletteIndex, int count) {
        if (paletteIndex < 0 || count == 0) return;
        JsonArray run = new JsonArray();
        run.add(new com.google.gson.JsonPrimitive(paletteIndex));
        run.add(new com.google.gson.JsonPrimitive(count));
        runs.add(run);
    }

    private JsonObject result(JsonObject data, String summary) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", summary);
        content.add(text);
        result.add("content", content);
        result.add("structuredContent", data);
        result.addProperty("isError", false);
        return result;
    }

    private JsonObject position(BlockPos pos) {
        JsonObject result = new JsonObject();
        result.addProperty("x", pos.getX()); result.addProperty("y", pos.getY()); result.addProperty("z", pos.getZ());
        return result;
    }

    private JsonObject boundsJson(Bounds b) {
        JsonObject data = new JsonObject();
        data.addProperty("min_x", b.minX); data.addProperty("min_y", b.minY); data.addProperty("min_z", b.minZ);
        data.addProperty("max_x", b.maxX); data.addProperty("max_y", b.maxY); data.addProperty("max_z", b.maxZ);
        data.addProperty("size_x", b.maxX - b.minX + 1); data.addProperty("size_y", b.maxY - b.minY + 1); data.addProperty("size_z", b.maxZ - b.minZ + 1);
        data.addProperty("volume", b.volume);
        return data;
    }

    private JsonArray stringArray(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(new com.google.gson.JsonPrimitive(value));
        return array;
    }

    private int requiredInt(JsonObject object, String name) throws ToolFailure {
        double value = requiredDouble(object, name);
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ToolFailure(name + " must be a 32-bit integer");
        }
        return (int) value;
    }
    private int optionalInt(JsonObject object, String name, int fallback) throws ToolFailure { return object.has(name) ? requiredInt(object, name) : fallback; }
    private double optionalDouble(JsonObject object, String name, double fallback) throws ToolFailure { return object.has(name) ? requiredDouble(object, name) : fallback; }
    private boolean optionalBoolean(JsonObject object, String name, boolean fallback) throws ToolFailure {
        if (!object.has(name)) return fallback;
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new ToolFailure(name + " must be a boolean");
        return value.getAsBoolean();
    }
    private String requiredString(JsonObject object, String name) throws ToolFailure {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new ToolFailure(name + " must be a string");
        return value.getAsString();
    }
    private String optionalString(JsonObject object, String name, String fallback) throws ToolFailure { return object.has(name) ? requiredString(object, name) : fallback; }
    private JsonArray requiredArray(JsonObject object, String name) throws ToolFailure {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) throw new ToolFailure(name + " must be an array");
        return value.getAsJsonArray();
    }
    private double requiredDouble(JsonObject object, String name) throws ToolFailure {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ToolFailure(name + " must be a number");
        return value.getAsDouble();
    }

    private String validatedBlock(String block) throws ToolFailure {
        String trimmed = block.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64 || !trimmed.matches("[A-Za-z0-9_.:-]+")) {
            throw new ToolFailure("block must be a Minecraft 1.8.9 numeric legacy ID (preferred) or a safe server-supported name");
        }
        return trimmed;
    }

    private String validatedFaweArgument(String value, String name) throws ToolFailure {
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 72) {
            throw new ToolFailure(name + " must contain between 1 and 72 characters");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw new ToolFailure(name + " must not contain control characters or line breaks");
            }
        }
        return trimmed;
    }

    private String fawePattern(JsonObject step, String prefix) throws ToolFailure {
        return validatedFaweArgument(requiredString(step, "pattern"), prefix + "pattern");
    }

    private int rangedInt(JsonObject object, String name, int minimum, int maximum) throws ToolFailure {
        int value = requiredInt(object, name);
        if (value < minimum || value > maximum) {
            throw new ToolFailure(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private int optionalRangedInt(JsonObject object, String name, int fallback, int minimum, int maximum) throws ToolFailure {
        if (!object.has(name)) return fallback;
        return rangedInt(object, name, minimum, maximum);
    }

    private String validatedDirection(String value, String name) throws ToolFailure {
        String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!trimmed.matches("[a-z]+")) throw new ToolFailure(name + " must be a simple FAWE direction");
        requireOneOf(trimmed, name, "up", "down", "north", "south", "east", "west", "me", "back", "left", "right");
        return trimmed;
    }

    private String validatedSimpleName(String value, String name) throws ToolFailure {
        String trimmed = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.length() > 48 || !trimmed.matches("[a-z0-9_.:-]+")) {
            throw new ToolFailure(name + " must be a simple FAWE name");
        }
        return trimmed;
    }

    private int rotation(JsonObject object, String name) throws ToolFailure {
        int value = requiredInt(object, name);
        if (value < -360 || value > 360 || value % 90 != 0) {
            throw new ToolFailure(name + " must be a multiple of 90 between -360 and 360");
        }
        return value;
    }

    private int optionalRotation(JsonObject object, String name, int fallback) throws ToolFailure {
        return object.has(name) ? rotation(object, name) : fallback;
    }

    private void checkData(int data) throws ToolFailure {
        if (data < 0 || data > 15) throw new ToolFailure("data must be between 0 and 15");
    }

    private void requireOneOf(String value, String name, String... allowed) throws ToolFailure {
        for (String candidate : allowed) if (candidate.equals(value)) return;
        throw new ToolFailure(name + " has an unsupported value: " + value);
    }

    static final class Bounds {
        private final int minX, minY, minZ, maxX, maxY, maxZ, volume;
        Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int volume) {
            this.minX=minX; this.minY=minY; this.minZ=minZ; this.maxX=maxX; this.maxY=maxY; this.maxZ=maxZ; this.volume=volume;
        }
    }

    static final class SequencePosition {
        private final int x, y, z;
        SequencePosition(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    static final class ToolFailure extends Exception {
        private static final long serialVersionUID = 1L;
        private ToolFailure(String message) { super(message); }
    }

    private static final class VirtualCamera extends Entity {
        private VirtualCamera(WorldClient world) { super(world); this.noClip = true; }
        @Override protected void entityInit() { }
        @Override protected void readEntityFromNBT(NBTTagCompound tag) { }
        @Override protected void writeEntityToNBT(NBTTagCompound tag) { }
        @Override public float getEyeHeight() { return 0.0F; }
    }
}
