package com.ezbackup.client;

/**
 * Sizes and colours shared by the EzBackup screens and widgets (ARGB colours, pixel sizes). One place to change the
 * look; a value that only one class uses stays in that class.
 */
final class GuiStyle {

    /** Height of a button, text box, switch or dropdown row. */
    static final int ROW_HEIGHT = 20;
    /** Gap between neighbouring widgets in a row. */
    static final int GAP = 8;

    static final int COLOR_TEXT = 0xFFFFFFFF;
    static final int COLOR_BLACK = 0xFF000000;
    static final int COLOR_ERROR = 0xFFFF5555;
    static final int COLOR_SUCCESS = 0xFF55FF55;
    /** Secondary text (notes, counters, disabled borders). */
    static final int COLOR_NOTE = 0xFFA0A0A0;
    /** Placeholder text and scroll bars. */
    static final int COLOR_HINT = 0xFF808080;
    /** The countdown and the selected entry of a dropdown. */
    static final int COLOR_HIGHLIGHT = 0xFFFFFF55;
    static final int COLOR_LIST_FILL = 0xFF3A3A3A;
    static final int COLOR_LIST_FILL_HOVER = 0xFF6F6F6F;

    private GuiStyle() {
    }
}
