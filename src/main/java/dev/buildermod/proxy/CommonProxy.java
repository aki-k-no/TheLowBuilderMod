package dev.buildermod.proxy;

import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {
    public void preInit(FMLPreInitializationEvent event) {
        // Intentionally empty. Client-only code belongs in ClientProxy.
    }

    public void init(FMLInitializationEvent event) {
        // Intentionally empty. This keeps accidental dedicated-server loading safe.
    }

    public void shutdown() {
        // Intentionally empty.
    }
}

