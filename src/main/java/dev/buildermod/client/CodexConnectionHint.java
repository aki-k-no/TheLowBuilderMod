package dev.buildermod.client;

import dev.buildermod.mcp.McpServiceCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Shows one unobtrusive, clickable pairing hint after the player enters a world. */
public final class CodexConnectionHint {
    private static final int HINT_DELAY_TICKS = 120;

    private final McpServiceCoordinator mcpService;
    private int ticksInWorld;
    private boolean shown;

    public CodexConnectionHint(McpServiceCoordinator mcpService) {
        this.mcpService = mcpService;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (shown || event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld == null || minecraft.thePlayer == null) {
            ticksInWorld = 0;
            return;
        }
        if (++ticksInWorld < HINT_DELAY_TICKS) {
            return;
        }
        shown = true;
        if (mcpService.hasConnectedClient()) {
            return;
        }

        ChatComponentText message = new ChatComponentText("[BuilderMod] Codex is not connected. ");
        ChatComponentText action = new ChatComponentText("[Connect]");
        action.setChatStyle(new ChatStyle()
            .setColor(EnumChatFormatting.GREEN)
            .setUnderlined(true)
            .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/buildermod codex connect"))
            .setChatHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText("Install the local BuilderMod connection into Codex")
            ))
        );
        message.appendSibling(action);
        minecraft.thePlayer.addChatMessage(message);
    }
}
