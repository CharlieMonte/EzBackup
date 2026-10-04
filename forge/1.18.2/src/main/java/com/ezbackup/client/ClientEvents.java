package com.ezbackup.client;

import com.ezbackup.BackupConfig;
import com.ezbackup.BackupManager;
import com.ezbackup.EzBackup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only hooks. {@code value = Dist.CLIENT} means Forge never loads this class on a dedicated
 * server, so the mod stays usable there.
 */
@Mod.EventBusSubscriber(modid = EzBackup.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientEvents {

    /** True only while we press the real disconnect button ourselves after the player confirmed. */
    private static boolean confirmed;

    private ClientEvents() {
    }

    /** Adds the "B" button to the lower right corner of the escape menu. */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.InitScreenEvent.Post event) {
        if (!(event.getScreen() instanceof PauseScreen screen)) {
            return;
        }
        event.addListener(new BackupMenuButton(screen,
                button -> Minecraft.getInstance().setScreen(new SettingsScreen(screen))));
    }

    /**
     * Disconnect warnings. When the player clicks "Save and Quit to Title" (or "Disconnect") we ask first if
     * <ul>
     *   <li>"Backup running" switch: a backup is still running (leaving cancels it), or</li>
     *   <li>"Not backed up" switch: this is a single-player / LAN-host world and no backup has finished since it
     *       was loaded.</li>
     * </ul>
     * Only mouse clicks are intercepted.
     */
    @SubscribeEvent
    public static void onMouseClicked(ScreenEvent.MouseClickedEvent.Pre event) {
        if (confirmed || event.getButton() != 0 || !(event.getScreen() instanceof PauseScreen screen)) {
            return; // only a left click presses a button
        }
        Warning warning = currentWarning();
        if (warning == null) {
            return;
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button && isLeaveButton(button)
                    && button.active && button.isMouseOver(event.getMouseX(), event.getMouseY())) {
                event.setCanceled(true);
                Minecraft.getInstance().setScreen(new ConfirmScreen(leave -> {
                    Minecraft.getInstance().setScreen(screen);
                    if (leave) {
                        confirmed = true;
                        try {
                            button.onPress();
                        } finally {
                            confirmed = false;
                        }
                    }
                }, warning.title(), warning.message(), new TextComponent(LEAVE_CAPTION), new TextComponent(STAY_CAPTION)));
                return;
            }
        }
    }

    private record Warning(Component title, Component message) {
    }

    private static final String RUNNING_TITLE = "A backup is still running";
    private static final String RUNNING_MESSAGE = "Leaving the world cancels it and nothing will be saved. Leave anyway?";
    private static final String NO_BACKUP_TITLE = "No backup of this world yet";
    private static final String NO_BACKUP_MESSAGE = "No backup has finished since you loaded this world. Leave anyway?";
    private static final String LEAVE_CAPTION = "Leave";
    private static final String STAY_CAPTION = "Stay";

    /** The warning to show right now, or null if the player may leave without being asked. */
    private static Warning currentWarning() {
        if (BackupConfig.warnMidBackup() && BackupManager.isRunning()) {
            return new Warning(new TextComponent(RUNNING_TITLE), new TextComponent(RUNNING_MESSAGE));
        }
        // Backups only happen in the game that hosts the world, so only ask there.
        if (BackupConfig.warnNoBackup() && Minecraft.getInstance().hasSingleplayerServer()
                && !BackupManager.hasBackedUpSinceLoad()) {
            return new Warning(new TextComponent(NO_BACKUP_TITLE), new TextComponent(NO_BACKUP_MESSAGE));
        }
        return null;
    }

    /** The escape-menu button that leaves the world ("Save and Quit to Title" / "Disconnect"). */
    private static boolean isLeaveButton(Button button) {
        if (button.getMessage() instanceof TranslatableComponent text) {
            String key = text.getKey();
            return key.equals("menu.returnToMenu") || key.equals("menu.disconnect");
        }
        return false;
    }
}
