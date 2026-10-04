package com.ezbackup.client;

/**
 * GLFW key codes used by the screens (the same numbers as {@code org.lwjgl.glfw.GLFW.GLFW_KEY_*}). Kept here as plain
 * constants so every screen uses one set of names; switching to the GLFW constants directly is possible where the
 * LWJGL classes are on the compile classpath (not checked in this project).
 */
final class GuiKeys {

    static final int SPACE = 32;
    static final int ESCAPE = 256;
    static final int TAB = 258;
    static final int ENTER = 257;
    static final int KEYPAD_ENTER = 335;
    static final int BACKSPACE = 259;
    static final int DELETE = 261;
    static final int RIGHT = 262;
    static final int LEFT = 263;
    static final int DOWN = 264;
    static final int UP = 265;
    static final int HOME = 268;
    static final int END = 269;
    static final int PAGE_UP = 266;
    static final int PAGE_DOWN = 267;

    private GuiKeys() {
    }

    /** Enter or keypad Enter. */
    static boolean isEnter(int keyCode) {
        return keyCode == ENTER || keyCode == KEYPAD_ENTER;
    }
}
