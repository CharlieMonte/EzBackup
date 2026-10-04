package com.ezbackup;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
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
        McCompat.markServerSideOnly();
        BackupConfig.load(McCompat.configDirectory().resolve("ezbackup.properties"));
        MinecraftForge.EVENT_BUS.register(this);
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
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && RestoreManager.needsTick()) {
            RestoreManager.tick(ServerLifecycleHooks.getCurrentServer());
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
