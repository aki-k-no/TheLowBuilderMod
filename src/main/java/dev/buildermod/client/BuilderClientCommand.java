package dev.buildermod.client;

import dev.buildermod.mcp.McpServiceCoordinator;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

public final class BuilderClientCommand extends CommandBase {
    private final McpServiceCoordinator mcpService;
    private final CodexCliService codexCliService;
    private final BuilderConversation conversation;
    private final ClientTaskQueue clientTaskQueue;

    public BuilderClientCommand(
        McpServiceCoordinator mcpService,
        CodexCliService codexCliService,
        BuilderConversation conversation,
        ClientTaskQueue clientTaskQueue
    ) {
        this.mcpService = mcpService;
        this.codexCliService = codexCliService;
        this.conversation = conversation;
        this.clientTaskQueue = clientTaskQueue;
    }

    @Override
    public String getCommandName() {
        return "buildermod";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/builder [gui|ask <message>|cancel|status|codex connect|sheet [gui|add <name> <url>|remove <name>|list|connect|status]]";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("builder");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0 || (args.length == 1 && "gui".equalsIgnoreCase(args[0]))) {
            // GuiChat closes itself after dispatching a command. Defer opening our screen
            // until the end of the client tick so that close does not immediately hide it.
            clientTaskQueue.submit(new Runnable() {
                @Override
                public void run() {
                    Minecraft.getMinecraft().displayGuiScreen(new BuilderChatScreen(conversation));
                }
            });
            return;
        }
        if ((args.length == 1 || (args.length == 2 && "gui".equalsIgnoreCase(args[1])))
            && "sheet".equalsIgnoreCase(args[0])) {
            clientTaskQueue.submit(new Runnable() {
                @Override public void run() {
                    Minecraft.getMinecraft().displayGuiScreen(new BuilderSheetScreen(mcpService.getGoogleSheets()));
                }
            });
            return;
        }
        if (args.length >= 2 && "ask".equalsIgnoreCase(args[0])) {
            askCodex(sender, join(args, 1));
            return;
        }
        if (args.length == 1 && "status".equalsIgnoreCase(args[0])) {
            sender.addChatMessage(new ChatComponentText("[BuilderMod] " + mcpService.describeStatus()));
            sender.addChatMessage(new ChatComponentText("[BuilderMod] " + codexCliService.describeStatus()));
            sender.addChatMessage(new ChatComponentText("[BuilderMod] " + mcpService.getGoogleSheets().status()));
            return;
        }
        if (args.length == 3 && "sheet".equalsIgnoreCase(args[0]) && "set".equalsIgnoreCase(args[1])) {
            try { sender.addChatMessage(new ChatComponentText("[BuilderMod] " + mcpService.getGoogleSheets().setSheetUrl(args[2]))); }
            catch (IllegalArgumentException error) { sender.addChatMessage(new ChatComponentText("[BuilderMod] " + error.getMessage())); }
            return;
        }
        if (args.length == 4 && "sheet".equalsIgnoreCase(args[0]) && "add".equalsIgnoreCase(args[1])) {
            try { sender.addChatMessage(new ChatComponentText("[BuilderMod] " + mcpService.getGoogleSheets().addSheet(args[2], args[3]))); }
            catch (IllegalArgumentException error) { sender.addChatMessage(new ChatComponentText("[BuilderMod] " + error.getMessage())); }
            return;
        }
        if (args.length == 3 && "sheet".equalsIgnoreCase(args[0]) && "remove".equalsIgnoreCase(args[1])) {
            sender.addChatMessage(new ChatComponentText("[BuilderMod] " + mcpService.getGoogleSheets().removeSheet(args[2])));
            return;
        }
        if (args.length == 2 && "sheet".equalsIgnoreCase(args[0]) && "list".equalsIgnoreCase(args[1])) {
            sender.addChatMessage(new ChatComponentText("[BuilderMod] " + mcpService.getGoogleSheets().listNames()));
            return;
        }
        if (args.length == 2 && "sheet".equalsIgnoreCase(args[0]) && "status".equalsIgnoreCase(args[1])) {
            sender.addChatMessage(new ChatComponentText("[BuilderMod] " + mcpService.getGoogleSheets().status()));
            return;
        }
        if (args.length == 2 && "sheet".equalsIgnoreCase(args[0]) && "connect".equalsIgnoreCase(args[1])) {
            boolean started = mcpService.getGoogleSheets().connect(new java.util.function.Consumer<String>() {
                @Override public void accept(final String message) {
                    clientTaskQueue.submit(new Runnable() { @Override public void run() {
                        sender.addChatMessage(new ChatComponentText("[BuilderMod] " + message));
                    }});
                }
            });
            sender.addChatMessage(new ChatComponentText("[BuilderMod] " + (started ? "Opening Google sign-in in your browser..." : "Google sign-in is already running.")));
            return;
        }
        if (args.length == 1 && "cancel".equalsIgnoreCase(args[0])) {
            sender.addChatMessage(new ChatComponentText(conversation.cancel()
                ? "[BuilderMod] Cancelling Codex request..."
                : "[BuilderMod] No Codex request is running."));
            return;
        }
        if (args.length == 2
            && "codex".equalsIgnoreCase(args[0])
            && "connect".equalsIgnoreCase(args[1])) {
            sender.addChatMessage(new ChatComponentText("[BuilderMod] " + mcpService.connectCodex()));
            return;
        }
        sender.addChatMessage(new ChatComponentText("Usage: " + getCommandUsage(sender)));
    }

    private void askCodex(final ICommandSender sender, String request) {
        try {
            boolean started = conversation.send(request, new BuilderConversation.Observer() {
                @Override
                public void onProgress(String message) {
                    sender.addChatMessage(new ChatComponentText("[" + conversation.getProviderLabel() + "] " + message));
                }

                @Override
                public void onReply(String reply) {
                    List<String> chunks = CodexCliService.splitForChat(reply);
                    if (chunks.isEmpty()) {
                        sender.addChatMessage(new ChatComponentText("[" + conversation.getProviderLabel() + "] (empty reply)"));
                        return;
                    }
                    for (String chunk : chunks) {
                        sender.addChatMessage(new ChatComponentText("[" + conversation.getProviderLabel() + "] " + chunk));
                    }
                }

                @Override
                public void onError(String message) {
                    sender.addChatMessage(new ChatComponentText("[BuilderMod] " + message));
                }
            });
            if (started) {
                sender.addChatMessage(new ChatComponentText("[BuilderMod] " + conversation.getProviderLabel() + " request started..."));
            } else {
                sender.addChatMessage(new ChatComponentText("[BuilderMod] An AI request is already running."));
            }
        } catch (IllegalArgumentException exception) {
            sender.addChatMessage(new ChatComponentText("[BuilderMod] " + exception.getMessage()));
        }
    }

    private static String join(String[] args, int start) {
        StringBuilder joined = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(args[i]);
        }
        return joined.toString();
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, net.minecraft.util.BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, Arrays.asList("gui", "ask", "cancel", "status", "codex", "sheet"));
        }
        if (args.length == 2 && "codex".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, Arrays.asList("connect"));
        }
        if (args.length == 2 && "sheet".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, Arrays.asList("gui", "add", "remove", "list", "connect", "status", "set"));
        }
        return null;
    }
}
