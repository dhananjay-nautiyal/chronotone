package dev.chronotone.render.art;

import java.util.HashMap;
import java.util.Map;

/**
 * A 5×7 bitmap font, drawn by hand.
 *
 * <p>Text on a cover would normally mean a system font, and a system font means the image depends on
 * the machine that drew it. Forty-odd glyphs of fixed bitmaps are the whole cost of not having that
 * dependency, and the blocky result suits a poster about a machine that turns days into sound.
 *
 * <p>Each glyph is seven rows of five bits, most significant bit on the left. Lower-case input is
 * folded to upper case; anything unknown renders as a space.
 */
final class Font5x7 {

    static final int WIDTH = 5;
    static final int HEIGHT = 7;

    private static final String[] TABLE = {
            "A 01110 10001 10001 11111 10001 10001 10001",
            "B 11110 10001 10001 11110 10001 10001 11110",
            "C 01110 10001 10000 10000 10000 10001 01110",
            "D 11110 10001 10001 10001 10001 10001 11110",
            "E 11111 10000 10000 11110 10000 10000 11111",
            "F 11111 10000 10000 11110 10000 10000 10000",
            "G 01110 10001 10000 10111 10001 10001 01111",
            "H 10001 10001 10001 11111 10001 10001 10001",
            "I 11111 00100 00100 00100 00100 00100 11111",
            "J 00111 00010 00010 00010 00010 10010 01100",
            "K 10001 10010 10100 11000 10100 10010 10001",
            "L 10000 10000 10000 10000 10000 10000 11111",
            "M 10001 11011 10101 10101 10001 10001 10001",
            "N 10001 11001 10101 10011 10001 10001 10001",
            "O 01110 10001 10001 10001 10001 10001 01110",
            "P 11110 10001 10001 11110 10000 10000 10000",
            "Q 01110 10001 10001 10001 10101 10010 01101",
            "R 11110 10001 10001 11110 10100 10010 10001",
            "S 01111 10000 10000 01110 00001 00001 11110",
            "T 11111 00100 00100 00100 00100 00100 00100",
            "U 10001 10001 10001 10001 10001 10001 01110",
            "V 10001 10001 10001 10001 10001 01010 00100",
            "W 10001 10001 10001 10101 10101 11011 10001",
            "X 10001 10001 01010 00100 01010 10001 10001",
            "Y 10001 10001 01010 00100 00100 00100 00100",
            "Z 11111 00001 00010 00100 01000 10000 11111",
            "0 01110 10001 10011 10101 11001 10001 01110",
            "1 00100 01100 00100 00100 00100 00100 01110",
            "2 01110 10001 00001 00010 00100 01000 11111",
            "3 11111 00010 00100 00010 00001 10001 01110",
            "4 00010 00110 01010 10010 11111 00010 00010",
            "5 11111 10000 11110 00001 00001 10001 01110",
            "6 00110 01000 10000 11110 10001 10001 01110",
            "7 11111 00001 00010 00100 01000 01000 01000",
            "8 01110 10001 10001 01110 10001 10001 01110",
            "9 01110 10001 10001 01111 00001 00010 01100",
            " 00000 00000 00000 00000 00000 00000 00000",
            "- 00000 00000 00000 11111 00000 00000 00000",
            ". 00000 00000 00000 00000 00000 01100 01100",
            ", 00000 00000 00000 00000 00110 00110 01100",
            ": 00000 01100 01100 00000 01100 01100 00000",
            "/ 00001 00010 00010 00100 01000 01000 10000",
            "# 01010 01010 11111 01010 11111 01010 01010",
            "% 11001 11010 00010 00100 01000 01011 10011",
            "| 00100 00100 00100 00100 00100 00100 00100",
            "* 00000 00000 01000 00100 01000 00000 00000",  // middle dot
            "+ 00000 00100 00100 11111 00100 00100 00000",
            "' 00100 00100 00000 00000 00000 00000 00000",
    };

    private static final Map<Character, int[]> GLYPHS = new HashMap<>();

    static {
        for (String entry : TABLE) {
            char character = entry.charAt(0);
            String[] rows = entry.substring(2).trim().split(" ");
            int[] bits = new int[HEIGHT];
            for (int row = 0; row < HEIGHT; row++) {
                bits[row] = Integer.parseInt(rows[row], 2);
            }
            GLYPHS.put(character, bits);
        }
    }

    private Font5x7() {
    }

    static int[] glyph(char character) {
        char folded = Character.toUpperCase(character);
        return GLYPHS.getOrDefault(folded, GLYPHS.get(' '));
    }

    /** Width in pixels of {@code text} at a scale, with one cell of letter-spacing. */
    static int width(String text, int scale) {
        if (text.isEmpty()) {
            return 0;
        }
        return text.length() * (WIDTH + 1) * scale - scale;
    }
}
