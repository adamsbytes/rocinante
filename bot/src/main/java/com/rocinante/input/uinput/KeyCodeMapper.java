package com.rocinante.input.uinput;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

import static com.rocinante.input.uinput.LinuxInputConstants.*;

/**
 * Maps AWT KeyEvent codes to Linux KEY_* codes.
 * 
 * AWT and Linux use different key code schemes:
 * - AWT: KeyEvent.VK_A = 65, KeyEvent.VK_1 = 49, etc.
 * - Linux: KEY_A = 30, KEY_1 = 2, etc.
 */
public final class KeyCodeMapper {

    private static final Map<Integer, Short> AWT_TO_LINUX = new HashMap<>();
    private static final Map<Character, short[]> CHAR_TO_KEYS = new HashMap<>();

    static {
        // Initialize AWT to Linux key code mapping
        initAwtToLinuxMap();
        initCharToKeysMap();
    }

    private KeyCodeMapper() {
        // Utility class
    }

    private static void initAwtToLinuxMap() {
        // Letters
        AWT_TO_LINUX.put(KeyEvent.VK_A, KEY_A);
        AWT_TO_LINUX.put(KeyEvent.VK_B, KEY_B);
        AWT_TO_LINUX.put(KeyEvent.VK_C, KEY_C);
        AWT_TO_LINUX.put(KeyEvent.VK_D, KEY_D);
        AWT_TO_LINUX.put(KeyEvent.VK_E, KEY_E);
        AWT_TO_LINUX.put(KeyEvent.VK_F, KEY_F);
        AWT_TO_LINUX.put(KeyEvent.VK_G, KEY_G);
        AWT_TO_LINUX.put(KeyEvent.VK_H, KEY_H);
        AWT_TO_LINUX.put(KeyEvent.VK_I, KEY_I);
        AWT_TO_LINUX.put(KeyEvent.VK_J, KEY_J);
        AWT_TO_LINUX.put(KeyEvent.VK_K, KEY_K);
        AWT_TO_LINUX.put(KeyEvent.VK_L, KEY_L);
        AWT_TO_LINUX.put(KeyEvent.VK_M, KEY_M);
        AWT_TO_LINUX.put(KeyEvent.VK_N, KEY_N);
        AWT_TO_LINUX.put(KeyEvent.VK_O, KEY_O);
        AWT_TO_LINUX.put(KeyEvent.VK_P, KEY_P);
        AWT_TO_LINUX.put(KeyEvent.VK_Q, KEY_Q);
        AWT_TO_LINUX.put(KeyEvent.VK_R, KEY_R);
        AWT_TO_LINUX.put(KeyEvent.VK_S, KEY_S);
        AWT_TO_LINUX.put(KeyEvent.VK_T, KEY_T);
        AWT_TO_LINUX.put(KeyEvent.VK_U, KEY_U);
        AWT_TO_LINUX.put(KeyEvent.VK_V, KEY_V);
        AWT_TO_LINUX.put(KeyEvent.VK_W, KEY_W);
        AWT_TO_LINUX.put(KeyEvent.VK_X, KEY_X);
        AWT_TO_LINUX.put(KeyEvent.VK_Y, KEY_Y);
        AWT_TO_LINUX.put(KeyEvent.VK_Z, KEY_Z);

        // Numbers (top row)
        AWT_TO_LINUX.put(KeyEvent.VK_0, KEY_0);
        AWT_TO_LINUX.put(KeyEvent.VK_1, KEY_1);
        AWT_TO_LINUX.put(KeyEvent.VK_2, KEY_2);
        AWT_TO_LINUX.put(KeyEvent.VK_3, KEY_3);
        AWT_TO_LINUX.put(KeyEvent.VK_4, KEY_4);
        AWT_TO_LINUX.put(KeyEvent.VK_5, KEY_5);
        AWT_TO_LINUX.put(KeyEvent.VK_6, KEY_6);
        AWT_TO_LINUX.put(KeyEvent.VK_7, KEY_7);
        AWT_TO_LINUX.put(KeyEvent.VK_8, KEY_8);
        AWT_TO_LINUX.put(KeyEvent.VK_9, KEY_9);

        // Function keys
        AWT_TO_LINUX.put(KeyEvent.VK_F1, KEY_F1);
        AWT_TO_LINUX.put(KeyEvent.VK_F2, KEY_F2);
        AWT_TO_LINUX.put(KeyEvent.VK_F3, KEY_F3);
        AWT_TO_LINUX.put(KeyEvent.VK_F4, KEY_F4);
        AWT_TO_LINUX.put(KeyEvent.VK_F5, KEY_F5);
        AWT_TO_LINUX.put(KeyEvent.VK_F6, KEY_F6);
        AWT_TO_LINUX.put(KeyEvent.VK_F7, KEY_F7);
        AWT_TO_LINUX.put(KeyEvent.VK_F8, KEY_F8);
        AWT_TO_LINUX.put(KeyEvent.VK_F9, KEY_F9);
        AWT_TO_LINUX.put(KeyEvent.VK_F10, KEY_F10);
        AWT_TO_LINUX.put(KeyEvent.VK_F11, KEY_F11);
        AWT_TO_LINUX.put(KeyEvent.VK_F12, KEY_F12);

        // Modifiers
        AWT_TO_LINUX.put(KeyEvent.VK_SHIFT, KEY_LEFTSHIFT);
        AWT_TO_LINUX.put(KeyEvent.VK_CONTROL, KEY_LEFTCTRL);
        AWT_TO_LINUX.put(KeyEvent.VK_ALT, KEY_LEFTALT);
        AWT_TO_LINUX.put(KeyEvent.VK_META, KEY_LEFTMETA);
        AWT_TO_LINUX.put(KeyEvent.VK_CAPS_LOCK, KEY_CAPSLOCK);
        AWT_TO_LINUX.put(KeyEvent.VK_NUM_LOCK, KEY_NUMLOCK);
        AWT_TO_LINUX.put(KeyEvent.VK_SCROLL_LOCK, KEY_SCROLLLOCK);

        // Special keys
        AWT_TO_LINUX.put(KeyEvent.VK_ESCAPE, KEY_ESC);
        AWT_TO_LINUX.put(KeyEvent.VK_TAB, KEY_TAB);
        AWT_TO_LINUX.put(KeyEvent.VK_ENTER, KEY_ENTER);
        AWT_TO_LINUX.put(KeyEvent.VK_BACK_SPACE, KEY_BACKSPACE);
        AWT_TO_LINUX.put(KeyEvent.VK_SPACE, KEY_SPACE);
        AWT_TO_LINUX.put(KeyEvent.VK_DELETE, KEY_DELETE);
        AWT_TO_LINUX.put(KeyEvent.VK_INSERT, KEY_INSERT);
        AWT_TO_LINUX.put(KeyEvent.VK_HOME, KEY_HOME);
        AWT_TO_LINUX.put(KeyEvent.VK_END, KEY_END);
        AWT_TO_LINUX.put(KeyEvent.VK_PAGE_UP, KEY_PAGEUP);
        AWT_TO_LINUX.put(KeyEvent.VK_PAGE_DOWN, KEY_PAGEDOWN);
        AWT_TO_LINUX.put(KeyEvent.VK_PAUSE, KEY_PAUSE);
        AWT_TO_LINUX.put(KeyEvent.VK_PRINTSCREEN, KEY_SYSRQ);

        // Arrow keys
        AWT_TO_LINUX.put(KeyEvent.VK_UP, KEY_UP);
        AWT_TO_LINUX.put(KeyEvent.VK_DOWN, KEY_DOWN);
        AWT_TO_LINUX.put(KeyEvent.VK_LEFT, KEY_LEFT);
        AWT_TO_LINUX.put(KeyEvent.VK_RIGHT, KEY_RIGHT);

        // Punctuation
        AWT_TO_LINUX.put(KeyEvent.VK_MINUS, KEY_MINUS);
        AWT_TO_LINUX.put(KeyEvent.VK_EQUALS, KEY_EQUAL);
        AWT_TO_LINUX.put(KeyEvent.VK_OPEN_BRACKET, KEY_LEFTBRACE);
        AWT_TO_LINUX.put(KeyEvent.VK_CLOSE_BRACKET, KEY_RIGHTBRACE);
        AWT_TO_LINUX.put(KeyEvent.VK_SEMICOLON, KEY_SEMICOLON);
        AWT_TO_LINUX.put(KeyEvent.VK_QUOTE, KEY_APOSTROPHE);
        AWT_TO_LINUX.put(KeyEvent.VK_BACK_QUOTE, KEY_GRAVE);
        AWT_TO_LINUX.put(KeyEvent.VK_BACK_SLASH, KEY_BACKSLASH);
        AWT_TO_LINUX.put(KeyEvent.VK_COMMA, KEY_COMMA);
        AWT_TO_LINUX.put(KeyEvent.VK_PERIOD, KEY_DOT);
        AWT_TO_LINUX.put(KeyEvent.VK_SLASH, KEY_SLASH);

        // Numpad
        AWT_TO_LINUX.put(KeyEvent.VK_NUMPAD0, KEY_KP0);
        AWT_TO_LINUX.put(KeyEvent.VK_NUMPAD1, KEY_KP1);
        AWT_TO_LINUX.put(KeyEvent.VK_NUMPAD2, KEY_KP2);
        AWT_TO_LINUX.put(KeyEvent.VK_NUMPAD3, KEY_KP3);
        AWT_TO_LINUX.put(KeyEvent.VK_NUMPAD4, KEY_KP4);
        AWT_TO_LINUX.put(KeyEvent.VK_NUMPAD5, KEY_KP5);
        AWT_TO_LINUX.put(KeyEvent.VK_NUMPAD6, KEY_KP6);
        AWT_TO_LINUX.put(KeyEvent.VK_NUMPAD7, KEY_KP7);
        AWT_TO_LINUX.put(KeyEvent.VK_NUMPAD8, KEY_KP8);
        AWT_TO_LINUX.put(KeyEvent.VK_NUMPAD9, KEY_KP9);
        AWT_TO_LINUX.put(KeyEvent.VK_MULTIPLY, KEY_KPASTERISK);
        AWT_TO_LINUX.put(KeyEvent.VK_ADD, KEY_KPPLUS);
        AWT_TO_LINUX.put(KeyEvent.VK_SUBTRACT, KEY_KPMINUS);
        AWT_TO_LINUX.put(KeyEvent.VK_DECIMAL, KEY_KPDOT);
        AWT_TO_LINUX.put(KeyEvent.VK_DIVIDE, KEY_KPSLASH);
    }

    private static void initCharToKeysMap() {
        // Lowercase letters (no modifier)
        for (char c = 'a'; c <= 'z'; c++) {
            int offset = c - 'a';
            short keyCode = (short) (KEY_A + offset);
            // Correct mapping: KEY_A=30, then QWERTY layout
            keyCode = getLetterKeyCode(c);
            CHAR_TO_KEYS.put(c, new short[]{keyCode});
        }
        
        // Uppercase letters (shift + letter)
        for (char c = 'A'; c <= 'Z'; c++) {
            short keyCode = getLetterKeyCode(Character.toLowerCase(c));
            CHAR_TO_KEYS.put(c, new short[]{KEY_LEFTSHIFT, keyCode});
        }
        
        // Numbers (no modifier)
        for (char c = '0'; c <= '9'; c++) {
            short keyCode = c == '0' ? KEY_0 : (short) (KEY_1 + (c - '1'));
            CHAR_TO_KEYS.put(c, new short[]{keyCode});
        }
        
        // Shifted number row symbols
        CHAR_TO_KEYS.put('!', new short[]{KEY_LEFTSHIFT, KEY_1});
        CHAR_TO_KEYS.put('@', new short[]{KEY_LEFTSHIFT, KEY_2});
        CHAR_TO_KEYS.put('#', new short[]{KEY_LEFTSHIFT, KEY_3});
        CHAR_TO_KEYS.put('$', new short[]{KEY_LEFTSHIFT, KEY_4});
        CHAR_TO_KEYS.put('%', new short[]{KEY_LEFTSHIFT, KEY_5});
        CHAR_TO_KEYS.put('^', new short[]{KEY_LEFTSHIFT, KEY_6});
        CHAR_TO_KEYS.put('&', new short[]{KEY_LEFTSHIFT, KEY_7});
        CHAR_TO_KEYS.put('*', new short[]{KEY_LEFTSHIFT, KEY_8});
        CHAR_TO_KEYS.put('(', new short[]{KEY_LEFTSHIFT, KEY_9});
        CHAR_TO_KEYS.put(')', new short[]{KEY_LEFTSHIFT, KEY_0});
        
        // Unshifted punctuation
        CHAR_TO_KEYS.put('-', new short[]{KEY_MINUS});
        CHAR_TO_KEYS.put('=', new short[]{KEY_EQUAL});
        CHAR_TO_KEYS.put('[', new short[]{KEY_LEFTBRACE});
        CHAR_TO_KEYS.put(']', new short[]{KEY_RIGHTBRACE});
        CHAR_TO_KEYS.put(';', new short[]{KEY_SEMICOLON});
        CHAR_TO_KEYS.put('\'', new short[]{KEY_APOSTROPHE});
        CHAR_TO_KEYS.put('`', new short[]{KEY_GRAVE});
        CHAR_TO_KEYS.put('\\', new short[]{KEY_BACKSLASH});
        CHAR_TO_KEYS.put(',', new short[]{KEY_COMMA});
        CHAR_TO_KEYS.put('.', new short[]{KEY_DOT});
        CHAR_TO_KEYS.put('/', new short[]{KEY_SLASH});
        
        // Shifted punctuation
        CHAR_TO_KEYS.put('_', new short[]{KEY_LEFTSHIFT, KEY_MINUS});
        CHAR_TO_KEYS.put('+', new short[]{KEY_LEFTSHIFT, KEY_EQUAL});
        CHAR_TO_KEYS.put('{', new short[]{KEY_LEFTSHIFT, KEY_LEFTBRACE});
        CHAR_TO_KEYS.put('}', new short[]{KEY_LEFTSHIFT, KEY_RIGHTBRACE});
        CHAR_TO_KEYS.put(':', new short[]{KEY_LEFTSHIFT, KEY_SEMICOLON});
        CHAR_TO_KEYS.put('"', new short[]{KEY_LEFTSHIFT, KEY_APOSTROPHE});
        CHAR_TO_KEYS.put('~', new short[]{KEY_LEFTSHIFT, KEY_GRAVE});
        CHAR_TO_KEYS.put('|', new short[]{KEY_LEFTSHIFT, KEY_BACKSLASH});
        CHAR_TO_KEYS.put('<', new short[]{KEY_LEFTSHIFT, KEY_COMMA});
        CHAR_TO_KEYS.put('>', new short[]{KEY_LEFTSHIFT, KEY_DOT});
        CHAR_TO_KEYS.put('?', new short[]{KEY_LEFTSHIFT, KEY_SLASH});
        
        // Whitespace
        CHAR_TO_KEYS.put(' ', new short[]{KEY_SPACE});
        CHAR_TO_KEYS.put('\t', new short[]{KEY_TAB});
        CHAR_TO_KEYS.put('\n', new short[]{KEY_ENTER});
    }
    
    /**
     * Get Linux key code for a letter (QWERTY layout).
     */
    private static short getLetterKeyCode(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'a' -> KEY_A;
            case 'b' -> KEY_B;
            case 'c' -> KEY_C;
            case 'd' -> KEY_D;
            case 'e' -> KEY_E;
            case 'f' -> KEY_F;
            case 'g' -> KEY_G;
            case 'h' -> KEY_H;
            case 'i' -> KEY_I;
            case 'j' -> KEY_J;
            case 'k' -> KEY_K;
            case 'l' -> KEY_L;
            case 'm' -> KEY_M;
            case 'n' -> KEY_N;
            case 'o' -> KEY_O;
            case 'p' -> KEY_P;
            case 'q' -> KEY_Q;
            case 'r' -> KEY_R;
            case 's' -> KEY_S;
            case 't' -> KEY_T;
            case 'u' -> KEY_U;
            case 'v' -> KEY_V;
            case 'w' -> KEY_W;
            case 'x' -> KEY_X;
            case 'y' -> KEY_Y;
            case 'z' -> KEY_Z;
            default -> 0;
        };
    }

    /**
     * Convert an AWT KeyEvent code to a Linux KEY_* code.
     * 
     * @param awtKeyCode the AWT key code (e.g., KeyEvent.VK_A)
     * @return the Linux key code, or 0 if not mapped
     */
    public static short awtToLinux(int awtKeyCode) {
        Short linuxCode = AWT_TO_LINUX.get(awtKeyCode);
        return linuxCode != null ? linuxCode : 0;
    }

    /**
     * Get the Linux key codes needed to type a character.
     * Returns array of [modifierKey, mainKey] or just [mainKey] if no modifier needed.
     * 
     * @param c the character to type
     * @return array of Linux key codes, empty array if character not mappable
     */
    public static short[] charToLinuxKeys(char c) {
        short[] keys = CHAR_TO_KEYS.get(c);
        return keys != null ? keys : new short[0];
    }

    /**
     * Check if a character requires shift modifier.
     * 
     * @param c the character
     * @return true if shift is needed
     */
    public static boolean needsShift(char c) {
        short[] keys = CHAR_TO_KEYS.get(c);
        return keys != null && keys.length == 2;
    }
}
