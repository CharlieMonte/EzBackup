package com.ezbackup;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Mod entry point. Only wires things up: loads the config, registers /backup, drives the restore state machine from the
 * server tick, recovers from an interrupted restore before the world opens, and reacts to world load / shutdown.
 * Must match {@code mod_id} in gradle.properties.
 */
@Mod(EzBackup.MOD_ID)
public class EzBackup {

    public static final String MOD_ID = "ezbackup";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EzBackup() {
        BackupConfig.load(McCompat.configDirectory().resolve("ezbackup.properties"));
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BackupCommand.register(event.getDispatcher());
    }

    /** Before the world's files are opened: undo / clean up after a restore that was interrupted. */
    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        RestoreManager.recoverAtStart(event.getServer());
    }

    /** Drives /backup restore (it works in steps spread over server ticks). */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (RestoreManager.needsTick()) {
            RestoreManager.tick(event.getServer());
        }
    }

    /** A world has been loaded: "backed up since load" starts again from false. */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        BackupManager.worldLoaded();
    }

    /** The world is closing: stop a running restore (undoing it if needed), then cancel a running backup. */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        RestoreManager.shutdown();
        BackupManager.shutdown();
    }
}
