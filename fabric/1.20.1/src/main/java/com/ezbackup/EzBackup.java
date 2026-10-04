package com.ezbackup;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;

/**
 * Mod entry point. Only wires things up: loads the config, registers /backup, drives the restore state machine from the
 * server tick, recovers from an interrupted restore before the world opens, and reacts to world load / shutdown.
 * Must match {@code mod_id} in gradle.properties.
 */
public class EzBackup implements ModInitializer {

    public static final String MOD_ID = "ezbackup";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        BackupConfig.load(McCompat.configDirectory().resolve("ezbackup.properties"));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                BackupCommand.register(dispatcher));

        // Before the world's files are opened: undo / clean up after a restore that was interrupted.
        // (Fabric fires SERVER_STARTING before the dimensions are created, like Forge's ServerAboutToStartEvent.)
        ServerLifecycleEvents.SERVER_STARTING.register(RestoreManager::recoverAtStart);

        // A world has been loaded: "backed up since load" starts again from false.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> BackupManager.worldLoaded());

        // Drives /backup restore (it works in steps spread over server ticks).
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (RestoreManager.needsTick()) {
                RestoreManager.tick(server);
            }
        });

        // The world is closing: stop a running restore (undoing it if needed), then cancel a running backup.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            RestoreManager.shutdown();
            BackupManager.shutdown();
        });
    }
}
