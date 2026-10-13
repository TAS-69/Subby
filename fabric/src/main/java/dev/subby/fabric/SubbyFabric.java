package dev.subby.fabric;

import dev.subby.core.SubbyService;
import dev.subby.minecraft.MinecraftPlatform;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.LoggerFactory;

public final class SubbyFabric implements ModInitializer {
    private SubbyService service;
    private int ticks;
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> MinecraftPlatform.register(dispatcher, () -> service));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                service = new SubbyService(FabricLoader.getInstance().getConfigDir().resolve("subby"),
                        server.getWorldPath(LevelResource.ROOT).resolve("subby"), new MinecraftPlatform(server));
            } catch (Exception e) { LoggerFactory.getLogger("Subby").error("Subby could not start; existing data has been preserved.", e); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (++ticks % 20 == 0 && service != null) service.tick(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (service != null) { service.close(); service = null; } });
    }
}
