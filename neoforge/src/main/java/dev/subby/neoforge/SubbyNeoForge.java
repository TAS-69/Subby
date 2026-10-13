package dev.subby.neoforge;

import dev.subby.core.SubbyManager;
import dev.subby.minecraft.MinecraftPlatform;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

@Mod("subby")
public final class SubbyNeoForge {
    private SubbyManager service;
    private int ticks;
    public SubbyNeoForge() {
        NeoForge.EVENT_BUS.addListener(this::commands);
        NeoForge.EVENT_BUS.addListener(this::start);
        NeoForge.EVENT_BUS.addListener(this::stop);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::chat);
    }
    private void chat(ServerChatEvent event) {
        if (service != null && service.captureChat(event.getPlayer().getUUID().toString(), event.getRawText())) event.setCanceled(true);
    }
    private void commands(RegisterCommandsEvent event) { MinecraftPlatform.register(event.getDispatcher(), () -> service); }
    private void start(ServerStartedEvent event) {
        try {
            service = new SubbyManager(FMLPaths.CONFIGDIR.get().resolve("subby"),
                    event.getServer().getWorldPath(LevelResource.ROOT).resolve("subby"), new MinecraftPlatform(event.getServer()));
        } catch (Exception e) { LoggerFactory.getLogger("Subby").error("Subby could not start; existing data has been preserved.", e); }
    }
    private void stop(ServerStoppingEvent event) { if (service != null) { service.close(); service = null; } }
    private void tick(ServerTickEvent.Post event) { if (++ticks % 20 == 0 && service != null) service.tick(); }
}
