package dev.buildermod.proxy;

import dev.buildermod.client.BuilderClientCommand;
import dev.buildermod.client.BuilderConversation;
import dev.buildermod.client.ClientTaskQueue;
import dev.buildermod.client.CodexConnectionHint;
import dev.buildermod.client.CodexCliService;
import dev.buildermod.config.BuilderConfig;
import dev.buildermod.mcp.McpServiceCoordinator;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraft.client.Minecraft;

public final class ClientProxy extends CommonProxy {
    private final ClientTaskQueue taskQueue = new ClientTaskQueue();
    private BuilderConfig builderConfig;
    private McpServiceCoordinator mcpService;
    private CodexCliService codexCliService;
    private BuilderConversation conversation;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        builderConfig = BuilderConfig.load(event.getSuggestedConfigurationFile());
        mcpService = new McpServiceCoordinator(builderConfig, taskQueue);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        codexCliService = new CodexCliService(taskQueue, Minecraft.getMinecraft().mcDataDir);
        conversation = new BuilderConversation(codexCliService, builderConfig);
        MinecraftForge.EVENT_BUS.register(taskQueue);
        MinecraftForge.EVENT_BUS.register(new CodexConnectionHint(mcpService));
        ClientCommandHandler.instance.registerCommand(
            new BuilderClientCommand(mcpService, codexCliService, conversation, taskQueue)
        );
        mcpService.start();
    }

    @Override
    public void shutdown() {
        if (mcpService != null) {
            mcpService.stop();
        }
        if (codexCliService != null) {
            codexCliService.shutdown();
        }
    }
}
