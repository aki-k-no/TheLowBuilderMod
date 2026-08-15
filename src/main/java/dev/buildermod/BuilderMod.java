package dev.buildermod;

import dev.buildermod.proxy.CommonProxy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = BuilderMod.MOD_ID,
    name = BuilderMod.NAME,
    version = BuilderMod.VERSION,
    acceptedMinecraftVersions = "[1.8.9]",
    acceptableRemoteVersions = "*"
)
public final class BuilderMod {
    public static final String MOD_ID = "buildermod";
    public static final String NAME = "BuilderMod";
    public static final String VERSION = "0.8.0";

    @SidedProxy(
        clientSide = "dev.buildermod.proxy.ClientProxy",
        serverSide = "dev.buildermod.proxy.CommonProxy"
    )
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

}
