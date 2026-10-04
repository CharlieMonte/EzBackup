package com.ezbackup.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.TextComponent;

import java.util.function.BooleanSupplier;

import static com.ezbackup.client.GuiStyle.COLOR_BLACK;
import static com.ezbackup.client.GuiStyle.COLOR_TEXT;
import static com.ezbackup.client.GuiStyle.ROW_HEIGHT;

/**
 * An on/off switch. Colour-blind friendly: blue means ON, orange means OFF, and the state is also shown by
 * the knob position (right = on, left = off) and by the words "ON" / "OFF" on the track.
 * The colours are the blue and orange from the Okabe-Ito colour-blind-safe palette.
 * While it has keyboard focus (Tab) it is surrounded by a thick white ring.
 */
final class SwitchButton extends Button {

    static final int WIDTH = 44;

    private static final int ON_COLOR = 0xFF0072B2;    // blue
    private static final int OFF_COLOR = 0xFFE69F00;   // orange
    private static final int KNOB_COLOR = COLOR_TEXT;
    private static final int KNOB_WIDTH = 18;
    /** How far the white keyboard-focus ring reaches beyond the track (the black border is 1 px of this). */
    private static final int FOCUS_RING = 3;

    private final BooleanSupplier state;

    SwitchButton(int x, int y, BooleanSupplier state, OnPress onPress) {
        super(x, y, WIDTH, ROW_HEIGHT, TextComponent.EMPTY, onPress);
        this.state = state;
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        boolean on = this.state.getAsBoolean();
        int left = this.x;
        int top = this.y;
        int right = left + WIDTH;
        int bottom = top + ROW_HEIGHT;

        // Keyboard focus: a thick white ring with a black line inside it, easy to see on any background.
        if (this.isFocused()) {
            fill(poseStack, left - FOCUS_RING, top - FOCUS_RING, right + FOCUS_RING, bottom + FOCUS_RING, COLOR_TEXT);
        }
        // Border (white when the mouse is over the switch, otherwise black), then the coloured track.
        int border = this.isMouseOver(mouseX, mouseY) && !this.isFocused() ? COLOR_TEXT : COLOR_BLACK;
        fill(poseStack, left - 1, top - 1, right + 1, bottom + 1, border);
        fill(poseStack, left, top, right, bottom, on ? ON_COLOR : OFF_COLOR);

        // Knob: right when on, left when off.
        int knobLeft = on ? right - KNOB_WIDTH - 1 : left + 1;
        fill(poseStack, knobLeft, top + 1, knobLeft + KNOB_WIDTH, bottom - 1, KNOB_COLOR);

        // Word on the free side of the track.
        Font font = Minecraft.getInstance().font;
        String word = on ? "ON" : "OFF";
        int wordX = on ? left + 5 : right - 5 - font.width(word);
        font.draw(poseStack, word, wordX, top + (ROW_HEIGHT - 8) / 2f, on ? COLOR_TEXT : COLOR_BLACK);
    }
}
