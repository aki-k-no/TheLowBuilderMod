package dev.buildermod.client;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Transfers work from a future MCP transport thread to Minecraft's client thread.
 */
public final class ClientTaskQueue {
    private static final int MAX_TASKS_PER_TICK = 128;
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<Runnable>();

    public void submit(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        tasks.add(task);
    }

    public int pendingTasks() {
        return tasks.size();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        for (int i = 0; i < MAX_TASKS_PER_TICK; i++) {
            Runnable task = tasks.poll();
            if (task == null) {
                return;
            }
            task.run();
        }
    }
}

