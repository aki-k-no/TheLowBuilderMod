package dev.buildermod.mcp;

import com.google.gson.JsonObject;

/** Executes a tool call and returns a complete MCP CallToolResult object. */
public interface McpToolExecutor {
    JsonObject call(String name, JsonObject arguments) throws Exception;
}
