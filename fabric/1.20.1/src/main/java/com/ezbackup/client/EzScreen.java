package com.ezbackup.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Base of the two EzBackup screens ({@link SettingsScreen}, {@link SuggestionScreen}): remembers the screen it was
 * opened from, goes back to it on Escape / Done / Cancel, and keeps the game paused in single player like the escape
 * menu does.
 */
public abstract class EzScreen extends Screen {

    /** The screen to return to. */
    protected final Screen parent;

    protected EzScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    /** Escape (and the screens' Done / Cancel buttons) go back to the screen this one was opened from. */
    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    /** Keep the game paused in single player, like the escape menu does. */
    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
