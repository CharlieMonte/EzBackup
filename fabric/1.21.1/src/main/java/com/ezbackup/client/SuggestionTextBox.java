package com.ezbackup.client;

import com.ezbackup.SuggestionSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

import static com.ezbackup.client.GuiStyle.COLOR_BLACK;
import static com.ezbackup.client.GuiStyle.COLOR_HINT;
import static com.ezbackup.client.GuiStyle.COLOR_NOTE;
import static com.ezbackup.client.GuiStyle.COLOR_TEXT;

/**
 * A small multi-line text box for {@link SuggestionScreen}. Minecraft 1.21.1 has no multi-line text widget, so this
 * draws and edits the text itself. It is deliberately NOT a widget: the screen owns one instance and forwards typing,
 * key presses, clicks and scrolling to it. The box does not know whether it has the keyboard focus; the screen decides
 * that (typing and key presses are only forwarded while it is focused) and tells {@link #render} so the border and the
 * blinking cursor show it.
 *
 * <p>Text only, and only the characters {@code A-Z a-z 0-9}, the space and {@code - _ . ! ?} (see {@link SuggestionSender#isAllowedChar}). Typed
 * characters and pasted text are filtered to that set, so the text is always plain ASCII with no line breaks. That is what keeps
 * the editing and wrapping code below simple: one character is always one index step, and a line is simply as many
 * characters as fit the box (wrapping is per character, not per word, so a space needs no special handling). The rule itself lives in SuggestionSender (isAllowedChar / filterAllowed), which also re-checks
 * it before sending. If the rule is ever relaxed further (more punctuation, emoji, line breaks), change it there AND extend this logic again.
 * Nothing but a String comes out.
 *
 * <p>Supported: typing, Backspace, Delete, Left/Right/Up/Down, Home/End, Ctrl+V, clicking to place the cursor, mouse
 * wheel scrolling, character wrapping, a character limit. Enter is accepted but does nothing. Not supported: text
 * selection, copy and cut.
 */
final class SuggestionTextBox {

    private static final int PAD = 4;
    private static final int SCROLLBAR_WIDTH = 2;
    private static final int SCROLL_LINES_PER_NOTCH = 2;
    private static final String HINT = "Type here...";
    private static final int COLOR_BOX_TEXT = 0xFFE0E0E0;
    private static final int COLOR_SCROLLBAR = COLOR_HINT;

    private final Font font;
    private final int maxLength;

    private int x;
    private int y;
    private int width;
    private int height;

    private String text = "";
    private int cursor;          // index into text, 0..text.length()
    private int scroll;          // index of the first visible line
    private int blink;           // ticks, for the blinking cursor
    private boolean editable = true;

    /** One wrapped line: the text between index {@code start} and {@code end} (exclusive). */
    private record Line(int start, int end) {
    }

    /** Wrapped lines of the text. There is always at least one line. */
    private final List<Line> lines = new ArrayList<>();
    private boolean layoutValid;

    SuggestionTextBox(Font font, int maxLength) {
        this.font = font;
        this.maxLength = maxLength;
    }

    // ---------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------

    void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.layoutValid = false;
        ensureCursorVisible();
    }

    String getValue() {
        return this.text;
    }

    void clear() {
        this.text = "";
        this.cursor = 0;
        this.scroll = 0;
        this.layoutValid = false;
    }

    /** While not editable (a send is in progress) the box ignores all input. */
    void setEditable(boolean editable) {
        this.editable = editable;
    }

    void tick() {
        this.blink++;
    }

    // ---------------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------------

    boolean charTyped(char c) {
        if (!this.editable) {
            return false;
        }
        if (!SuggestionSender.isAllowedChar(c)) {
            return false;
        }
        insert(String.valueOf(c));
        return true;
    }

    /** Returns true if the key was used by the box (Escape and Tab are not, so the screen still handles them). */
    boolean keyPressed(int keyCode) {
        if (!this.editable) {
            return false;
        }
        if (Screen.isPaste(keyCode)) {
            insert(Minecraft.getInstance().keyboardHandler.getClipboard());
            return true;
        }
        switch (keyCode) {
            case GuiKeys.ENTER, GuiKeys.KEYPAD_ENTER -> { } // consumed, but there are no line breaks to insert
            case GuiKeys.BACKSPACE -> deleteBackward();
            case GuiKeys.DELETE -> deleteForward();
            case GuiKeys.LEFT -> moveHorizontal(-1);
            case GuiKeys.RIGHT -> moveHorizontal(1);
            case GuiKeys.UP -> moveVertical(-1);
            case GuiKeys.DOWN -> moveVertical(1);
            case GuiKeys.HOME -> moveTo(this.lines.get(lineOf(this.cursor)).start());
            case GuiKeys.END -> moveTo(lastCursorIndex(lineOf(this.cursor)));
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Places the cursor where the player clicked. Returns true if the click was inside the box. */
    boolean mouseClicked(double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        if (this.editable) {
            layout();
            int line = this.scroll + (int) ((mouseY - this.y - PAD) / this.font.lineHeight);
            line = Math.max(0, Math.min(line, this.lines.size() - 1));
            moveTo(indexAt(line, (int) mouseX - this.x - PAD));
        }
        return true;
    }

    boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        layout();
        this.scroll -= (int) Math.signum(delta) * SCROLL_LINES_PER_NOTCH;
        clampScroll();
        return true;
    }

    private boolean contains(double mouseX, double mouseY) {
        return mouseX >= this.x && mouseX < this.x + this.width && mouseY >= this.y && mouseY < this.y + this.height;
    }

    // ---------------------------------------------------------------------------------------------
    // Editing
    // ---------------------------------------------------------------------------------------------

    private void insert(String raw) {
        String added = SuggestionSender.filterAllowed(raw);
        int room = this.maxLength - this.text.length();
        if (added.isEmpty() || room <= 0) {
            return;
        }
        if (added.length() > room) {
            added = added.substring(0, room);
        }
        this.text = this.text.substring(0, this.cursor) + added + this.text.substring(this.cursor);
        this.cursor += added.length();
        changed();
    }

    private void deleteBackward() {
        if (this.cursor == 0) {
            return;
        }
        int from = this.cursor - 1;
        this.text = this.text.substring(0, from) + this.text.substring(this.cursor);
        this.cursor = from;
        changed();
    }

    private void deleteForward() {
        if (this.cursor >= this.text.length()) {
            return;
        }
        this.text = this.text.substring(0, this.cursor) + this.text.substring(this.cursor + 1);
        changed();
    }

    private void moveHorizontal(int direction) {
        moveTo(this.cursor + direction);
    }

    private void moveVertical(int direction) {
        layout();
        int line = lineOf(this.cursor);
        int target = line + direction;
        if (target < 0) {
            moveTo(0);
        } else if (target >= this.lines.size()) {
            moveTo(this.text.length());
        } else {
            moveTo(indexAt(target, xOf(line, this.cursor)));
        }
    }

    private void moveTo(int index) {
        this.cursor = Math.max(0, Math.min(index, this.text.length()));
        this.blink = 0; // show the cursor right away
        ensureCursorVisible();
    }

    private void changed() {
        this.layoutValid = false;
        this.blink = 0;
        ensureCursorVisible();
    }

    // ---------------------------------------------------------------------------------------------
    // Layout (character wrap). Recomputed only after the text or the size changed.
    // ---------------------------------------------------------------------------------------------

    private int textWidth() {
        return this.width - 2 * PAD - SCROLLBAR_WIDTH - 1;
    }

    private int visibleLines() {
        return Math.max(1, (this.height - 2 * PAD) / this.font.lineHeight);
    }

    private void layout() {
        if (this.layoutValid) {
            return;
        }
        this.lines.clear();
        int length = this.text.length();
        if (length == 0) {
            this.lines.add(new Line(0, 0)); // the empty box still has one (empty) line for the cursor
        }
        int start = 0;
        while (start < length) {
            int end = fit(start, length);
            this.lines.add(new Line(start, end));
            start = end;
        }
        this.layoutValid = true;
        clampScroll();
    }

    /**
     * The largest end (at least p + 1) so that text[p, end) is not wider than the box.
     * Performance note: font.width runs on a new substring for every character, so wrapping and indexAt() cost
     * O(n^2) font calls for n characters. That is harmless at the 1000-character limit; cache the widths of the
     * characters (all plain ASCII) if the limit is ever raised a lot.
     */
    private int fit(int p, int limit) {
        int maxWidth = textWidth();
        int end = p + 1;
        while (end < limit && this.font.width(this.text.substring(p, end + 1)) <= maxWidth) {
            end++;
        }
        return end;
    }

    /** The line the cursor is drawn on: the last line that starts at or before it. */
    private int lineOf(int position) {
        layout();
        int found = 0;
        for (int i = 0; i < this.lines.size(); i++) {
            if (this.lines.get(i).start() <= position) {
                found = i;
            } else {
                break;
            }
        }
        return found;
    }

    /** Pixel offset of {@code position} from the left edge of the text, on the given line. */
    private int xOf(int line, int position) {
        Line seg = this.lines.get(line);
        int end = Math.max(seg.start(), Math.min(position, seg.end()));
        return this.font.width(this.text.substring(seg.start(), end));
    }

    /**
     * The last cursor index that still belongs to this line. There are no line breaks in the text, so every line but
     * the last was only wrapped, and the index right after its last character belongs to the NEXT line, so it is one less.
     */
    private int lastCursorIndex(int line) {
        Line seg = this.lines.get(line);
        boolean wrapped = line + 1 < this.lines.size() && this.lines.get(line + 1).start() == seg.end();
        return wrapped ? Math.max(seg.start(), seg.end() - 1) : seg.end();
    }

    /** The cursor index on {@code line} closest to {@code pixelX}. */
    private int indexAt(int line, int pixelX) {
        Line seg = this.lines.get(line);
        int limit = lastCursorIndex(line);
        int previousWidth = 0;
        for (int i = seg.start(); i < limit; i++) {
            int nextWidth = this.font.width(this.text.substring(seg.start(), i + 1));
            if (pixelX < (previousWidth + nextWidth) / 2) {
                return i;
            }
            previousWidth = nextWidth;
        }
        return limit;
    }

    private void clampScroll() {
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, this.lines.size() - visibleLines())));
    }

    private void ensureCursorVisible() {
        int line = lineOf(this.cursor);
        int visible = visibleLines();
        if (line < this.scroll) {
            this.scroll = line;
        } else if (line >= this.scroll + visible) {
            this.scroll = line - visible + 1;
        }
        clampScroll();
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------

    /** {@code focused}: the box has the keyboard focus (white border and a blinking cursor); otherwise a grey border and no cursor. */
    void render(GuiGraphics graphics, boolean focused) {
        layout();
        int lineHeight = this.font.lineHeight;
        int visible = visibleLines();

        // Border (white while it can be edited), then the black background.
        graphics.fill(this.x - 1, this.y - 1, this.x + this.width + 1, this.y + this.height + 1,
                this.editable && focused ? COLOR_TEXT : COLOR_NOTE);
        graphics.fill(this.x, this.y, this.x + this.width, this.y + this.height, COLOR_BLACK);

        if (this.text.isEmpty()) {
            graphics.drawString(this.font, HINT, this.x + PAD, this.y + PAD, COLOR_HINT, false);
        }
        for (int i = 0; i < visible && this.scroll + i < this.lines.size(); i++) {
            Line seg = this.lines.get(this.scroll + i);
            graphics.drawString(this.font, this.text.substring(seg.start(), seg.end()),
                    this.x + PAD, this.y + PAD + i * lineHeight, COLOR_BOX_TEXT, false);
        }

        // Blinking cursor.
        int cursorLine = lineOf(this.cursor);
        if (this.editable && focused && (this.blink / 10) % 2 == 0
                && cursorLine >= this.scroll && cursorLine < this.scroll + visible) {
            int cursorX = this.x + PAD + xOf(cursorLine, this.cursor);
            int cursorY = this.y + PAD + (cursorLine - this.scroll) * lineHeight;
            graphics.fill(cursorX, cursorY - 1, cursorX + 1, cursorY + lineHeight, COLOR_TEXT);
        }

        // Scroll bar, only when there is more text than fits.
        if (this.lines.size() > visible) {
            int barHeight = Math.max(8, this.height * visible / this.lines.size());
            int barTop = this.y + (this.height - barHeight) * this.scroll / (this.lines.size() - visible);
            graphics.fill(this.x + this.width - SCROLLBAR_WIDTH - 1, barTop,
                    this.x + this.width - 1, barTop + barHeight, COLOR_SCROLLBAR);
        }
    }
}
