package com.ezbackup.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;

/**
 * The "B" button on the escape menu, pinned to the lower right corner of the screen.
 * Its position is fixed by the margins below (GUI-scaled pixels, measured from the screen edges).
 */
final class BackupMenuButton extends Button {

    private static final int SIZE = 20;
    private static final int RIGHT_MARGIN = 20;
    private static final int BOTTOM_MARGIN = 20;

    private final Screen screen;

    BackupMenuButton(Screen screen, OnPress onPress) {
        super(screen.width - SIZE - RIGHT_MARGIN, screen.height - SIZE - BOTTOM_MARGIN,
                SIZE, SIZE, new TextComponent("B"), onPress);
        this.screen = screen;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        super.render(poseStack, mouseX, mouseY, partialTick);
        if (this.isHovered) {
            this.screen.renderTooltip(poseStack, new TextComponent("EzBackup"), mouseX, mouseY);
        }
    }
}
