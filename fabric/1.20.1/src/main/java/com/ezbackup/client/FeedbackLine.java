package com.ezbackup.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

import static com.ezbackup.client.GuiStyle.COLOR_ERROR;
import static com.ezbackup.client.GuiStyle.COLOR_SUCCESS;
import static com.ezbackup.client.GuiStyle.COLOR_TEXT;

/**
 * The coloured result message under the boxes of a screen: white while something is happening, green after success,
 * red after an error. The text is wrapped to the available width, and the wrapped lines are kept until the text or
 * the width changes (so nothing is re-wrapped on every frame). Used by {@link SuggestionScreen} and
 * {@link SettingsScreen}.
 */
final class FeedbackLine {

    private String text = "";
    private int color = COLOR_TEXT;

    // Cached wrapping of `text`; valid while cachedFont / cachedWidth match and `cached` is not null.
    private List<String> cached;
    private Font cachedFont;
    private int cachedWidth;

    void info(String message) {
        set(message, COLOR_TEXT);
    }

    void success(String message) {
        set(message, COLOR_SUCCESS);
    }

    void error(String message) {
        set(message, COLOR_ERROR);
    }

    boolean isEmpty() {
        return this.text.isEmpty();
    }

    private void set(String message, int newColor) {
        this.text = message;
        this.color = newColor;
        this.cached = null;
    }

    /**
     * Draws the message centred on {@code centreX}, one line per row starting at {@code y}, wrapped to
     * {@code maxWidth} pixels and cut off after {@code maxLines} lines. A message that already fits is drawn as is;
     * pass a very large {@code maxWidth} (and 1 line) for a single unwrapped line.
     */
    void render(GuiGraphics graphics, Font font, int centreX, int y, int maxWidth, int maxLines) {
        if (this.text.isEmpty()) {
            return;
        }
        if (this.cached == null || this.cachedFont != font || this.cachedWidth != maxWidth) {
            this.cached = font.width(this.text) <= maxWidth ? List.of(this.text) : wrap(font, this.text, maxWidth);
            this.cachedFont = font;
            this.cachedWidth = maxWidth;
        }
        for (int i = 0; i < this.cached.size() && i < maxLines; i++) {
            String line = this.cached.get(i);
            graphics.drawString(font, line, centreX - font.width(line) / 2, y + i * font.lineHeight, this.color, false);
        }
    }

    /** Splits text into lines no wider than maxWidth pixels, breaking at spaces. */
    static List<String> wrap(Font font, String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        String line = "";
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && font.width(candidate) > maxWidth) {
                result.add(line);
                line = word;
            } else {
                line = candidate;
            }
        }
        if (!line.isEmpty()) {
            result.add(line);
        }
        return result;
    }
}
