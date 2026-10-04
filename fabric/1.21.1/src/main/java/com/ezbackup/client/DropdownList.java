package com.ezbackup.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.ezbackup.client.GuiStyle.COLOR_BLACK;
import static com.ezbackup.client.GuiStyle.COLOR_HIGHLIGHT;
import static com.ezbackup.client.GuiStyle.COLOR_HINT;
import static com.ezbackup.client.GuiStyle.COLOR_LIST_FILL;
import static com.ezbackup.client.GuiStyle.COLOR_LIST_FILL_HOVER;
import static com.ezbackup.client.GuiStyle.COLOR_NOTE;
import static com.ezbackup.client.GuiStyle.COLOR_TEXT;
import static com.ezbackup.client.GuiStyle.ROW_HEIGHT;

/**
 * A drop-down list that belongs to one {@link Button} (the "anchor"), drawn and clicked by hand so it can sit on top of
 * the widgets around it. Used by {@link SettingsScreen} (algorithm, restore) and {@link SuggestionScreen} (type).
 *
 * <p>The screen owns one instance and forwards four things to it: {@link #render} (call it LAST in the screen's
 * render()), {@link #mouseClicked}, {@link #mouseScrolled} and {@link #keyPressed} (ask it first; true means "handled,
 * stop"). While the list is open the screen should also swallow typed characters ({@link #isOpen()}). The anchor
 * button's own press action should call {@link #toggle()}, so Enter / Space on a focused anchor opens the list too.
 *
 * <p>Mouse: the list opens below the anchor, or above it when there is no room; any click closes it and is not passed
 * on (a left click on an entry also selects that entry); the wheel scrolls a long list; moving the mouse over an entry
 * highlights it.
 *
 * <p>Keyboard (while open): Up / Down move the highlight (wrapping), Home / End jump to the first / last entry,
 * Page Up / Page Down move a page, Enter / Keypad Enter / Space pick the highlighted entry, Escape closes the list,
 * Tab closes it and lets the screen move the focus on. Every other key is swallowed so nothing behind the list reacts.
 *
 * <p>The entries come from a supplier that is asked each time the list opens (so a folder listing is always fresh). A
 * long list shows at most {@code maxRows} entries (fewer when the screen is short) with a scroll bar.
 *
 * <p>The list is lifted above the widgets by translating the GuiGraphics pose stack by Z_OFFSET (the same trick
 * 1.21.1's own tooltips and dropdowns use).
 */
final class DropdownList<T> {

    private static final float Z_OFFSET = 400f;
    private static final int SCROLLBAR_WIDTH = 2;
    private static final int TEXT_PAD = 6;

    private final Font font;
    private final int width;
    private final int maxRows;
    private final Supplier<List<T>> source;
    private final String emptyText;
    private final Function<T, String> label;
    private final Consumer<T> onSelect;
    private final Supplier<T> selected;

    private List<T> items = List.of();
    private Button anchor;
    private int screenWidth;
    private int screenHeight;
    private boolean open;
    private boolean up;
    private int rows;
    private int scroll;
    private int highlight;
    private boolean mouseKnown;
    private int lastMouseX;
    private int lastMouseY;

    /** A short fixed list (every entry shown). {@code selected} is shown with a "> " and in yellow; it may return null. */
    DropdownList(Font font, int width, List<T> fixedItems, Function<T, String> label, Consumer<T> onSelect,
                 Supplier<T> selected) {
        this(font, width, Math.max(1, fixedItems.size()), () -> fixedItems, "", label, onSelect, selected);
    }

    /** A list whose entries are fetched from {@code source} each time it opens; {@code emptyText} is shown when there are none. */
    DropdownList(Font font, int width, int maxRows, Supplier<List<T>> source, String emptyText, Function<T, String> label,
                 Consumer<T> onSelect, Supplier<T> selected) {
        this.font = font;
        this.width = width;
        this.maxRows = Math.max(1, maxRows);
        this.source = source;
        this.emptyText = emptyText;
        this.label = label;
        this.onSelect = onSelect;
        this.selected = selected;
    }

    /** The button the list hangs from and the screen size (used to keep the list on screen). Closes the list. */
    void setAnchor(Button button, int screenWidth, int screenHeight) {
        this.anchor = button;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.open = false;
    }

    boolean isOpen() {
        return this.open;
    }

    void toggle() {
        if (this.open) {
            this.open = false;
        } else {
            open();
        }
    }

    void close() {
        this.open = false;
    }

    private void open() {
        this.items = List.copyOf(this.source.get());
        int wanted = Math.min(Math.max(1, this.items.size()), this.maxRows); // an empty list still shows one row of text
        int fitBelow = (this.screenHeight - 2 - (this.anchor.getY() + ROW_HEIGHT)) / ROW_HEIGHT;
        int fitAbove = (this.anchor.getY() - 2) / ROW_HEIGHT;
        this.up = fitBelow < wanted && fitAbove > fitBelow;
        this.rows = Math.max(1, Math.min(wanted, this.up ? fitAbove : fitBelow));
        T current = this.selected == null ? null : this.selected.get();
        int index = current == null ? -1 : this.items.indexOf(current);
        this.highlight = Math.max(0, index);
        this.scroll = 0;
        keepHighlightVisible();
        this.mouseKnown = false;
        this.open = true;
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------

    /** Draws the list (nothing while closed). */
    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!this.open) {
            return;
        }
        // The mouse only moves the highlight when it actually moved, so it does not fight the arrow keys.
        if (this.mouseKnown && (mouseX != this.lastMouseX || mouseY != this.lastMouseY)) {
            int over = optionAt(mouseX, mouseY);
            if (over >= 0) {
                this.highlight = over;
            }
        }
        this.mouseKnown = true;
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        T current = this.selected == null ? null : this.selected.get();
        int x = left();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0D, 0.0D, Z_OFFSET);
        if (this.items.isEmpty()) {
            int y = rowY(0);
            graphics.fill(x - 1, y - 1, x + this.width + 1, y + ROW_HEIGHT + 1, COLOR_BLACK);
            graphics.fill(x, y, x + this.width, y + ROW_HEIGHT, COLOR_LIST_FILL);
            graphics.drawString(this.font, fit(this.emptyText, this.width - 2 * TEXT_PAD), x + TEXT_PAD,
                    y + (ROW_HEIGHT - 8) / 2, COLOR_NOTE, false);
        } else {
            for (int row = 0; row < this.rows; row++) {
                int index = this.scroll + row;
                int y = rowY(row);
                T item = this.items.get(index);
                boolean isSelected = item.equals(current);
                graphics.fill(x - 1, y - 1, x + this.width + 1, y + ROW_HEIGHT + 1, COLOR_BLACK);
                graphics.fill(x, y, x + this.width, y + ROW_HEIGHT,
                        index == this.highlight ? COLOR_LIST_FILL_HOVER : COLOR_LIST_FILL);
                String text = (isSelected ? "> " : "") + this.label.apply(item);
                graphics.drawString(this.font, fit(text, this.width - 2 * TEXT_PAD - SCROLLBAR_WIDTH), x + TEXT_PAD,
                        y + (ROW_HEIGHT - 8) / 2, isSelected ? COLOR_HIGHLIGHT : COLOR_TEXT, false);
            }
            if (this.items.size() > this.rows) {
                int trackTop = rowY(0);
                int trackHeight = this.rows * ROW_HEIGHT;
                int barHeight = Math.max(8, trackHeight * this.rows / this.items.size());
                int barTop = trackTop + (trackHeight - barHeight) * this.scroll / (this.items.size() - this.rows);
                graphics.fill(x + this.width - SCROLLBAR_WIDTH - 1, barTop,
                        x + this.width - 1, barTop + barHeight, COLOR_HINT);
            }
        }
        graphics.pose().popPose();
    }

    /** {@code text} cut with "..." at the end so it is not wider than {@code maxWidth}. */
    private String fit(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && this.font.width(cut + "...") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    // ---------------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------------

    /** While open: a left click on an entry selects it; any click closes the list. Returns true if the click was used. */
    boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.open) {
            return false;
        }
        int index = optionAt(mouseX, mouseY);
        this.open = false; // any click closes the list and is not passed on (so clicking the anchor just closes it)
        if (index >= 0 && button == 0) {
            this.onSelect.accept(this.items.get(index));
        }
        return true;
    }

    /** While open, the wheel scrolls the list (and nothing behind it). Returns true if the event was used. */
    boolean mouseScrolled(double delta) {
        if (!this.open) {
            return false;
        }
        scrollBy(-(int) Math.signum(delta));
        int first = this.scroll;
        int last = this.scroll + this.rows - 1;
        this.highlight = Math.max(first, Math.min(this.highlight, Math.max(first, last)));
        return true;
    }

    /**
     * Keyboard handling while the list is open (see the class comment). Returns true if the key was used; false when
     * the list is closed, or for Tab (which closes the list but must still move the focus).
     */
    boolean keyPressed(int keyCode) {
        if (!this.open) {
            return false;
        }
        int count = this.items.size();
        switch (keyCode) {
            case GuiKeys.ESCAPE -> this.open = false;
            case GuiKeys.TAB -> {
                this.open = false;
                return false;
            }
            case GuiKeys.UP -> moveHighlight(count == 0 ? 0 : (this.highlight + count - 1) % count);
            case GuiKeys.DOWN -> moveHighlight(count == 0 ? 0 : (this.highlight + 1) % count);
            case GuiKeys.HOME -> moveHighlight(0);
            case GuiKeys.END -> moveHighlight(Math.max(0, count - 1));
            case GuiKeys.PAGE_UP -> moveHighlight(Math.max(0, this.highlight - this.rows));
            case GuiKeys.PAGE_DOWN -> moveHighlight(Math.max(0, Math.min(count - 1, this.highlight + this.rows)));
            case GuiKeys.ENTER, GuiKeys.KEYPAD_ENTER, GuiKeys.SPACE -> {
                this.open = false;
                if (this.highlight >= 0 && this.highlight < count) {
                    this.onSelect.accept(this.items.get(this.highlight));
                }
            }
            default -> {
                // swallowed: nothing behind the open list reacts to keys
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------------------------------------

    private void moveHighlight(int index) {
        this.highlight = index;
        keepHighlightVisible();
    }

    private void scrollBy(int delta) {
        this.scroll = Math.max(0, Math.min(this.scroll + delta, Math.max(0, this.items.size() - this.rows)));
    }

    private void keepHighlightVisible() {
        if (this.highlight < this.scroll) {
            this.scroll = this.highlight;
        } else if (this.highlight >= this.scroll + this.rows) {
            this.scroll = this.highlight - this.rows + 1;
        }
        scrollBy(0);
    }

    /** Left edge of the list: the anchor's, moved left if needed so the list stays on the screen. */
    private int left() {
        return Math.max(2, Math.min(this.anchor.getX(), this.screenWidth - this.width - 2));
    }

    /** Top of the given visible row (0 = first shown). */
    private int rowY(int row) {
        return this.up
                ? this.anchor.getY() - ROW_HEIGHT * (this.rows - row)
                : this.anchor.getY() + ROW_HEIGHT * (row + 1);
    }

    /** Index into {@code items} of the entry under the mouse, or -1. */
    private int optionAt(double mouseX, double mouseY) {
        int x = left();
        if (this.items.isEmpty() || mouseX < x || mouseX >= x + this.width) {
            return -1;
        }
        for (int row = 0; row < this.rows; row++) {
            int y = rowY(row);
            if (mouseY >= y && mouseY < y + ROW_HEIGHT) {
                return this.scroll + row;
            }
        }
        return -1;
    }
}
