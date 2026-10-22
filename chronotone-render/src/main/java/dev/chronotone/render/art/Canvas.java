package dev.chronotone.render.art;

/**
 * A flat, palette-indexed canvas with just enough drawing for a poster: rectangles, discs and text.
 *
 * <p>Every operation is integer-only and clipped, so a composition is a pure function of its
 * arguments — there is no anti-aliasing, no gamma and no rounding mode to disagree about.
 */
final class Canvas {

    private final int width;
    private final int height;
    private final int[] pixels;

    Canvas(int width, int height, int fill) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        java.util.Arrays.fill(pixels, fill);
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int[] pixels() {
        return pixels;
    }

    void set(int x, int y, int colour) {
        if (x >= 0 && x < width && y >= 0 && y < height) {
            pixels[y * width + x] = colour;
        }
    }

    void rect(int x, int y, int w, int h, int colour) {
        int fromX = Math.max(0, x);
        int toX = Math.min(width, x + w);
        int fromY = Math.max(0, y);
        int toY = Math.min(height, y + h);
        for (int row = fromY; row < toY; row++) {
            int base = row * width;
            for (int column = fromX; column < toX; column++) {
                pixels[base + column] = colour;
            }
        }
    }

    void disc(int cx, int cy, int radius, int colour) {
        if (radius <= 0) {
            return;
        }
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) StrictMath.sqrt((double) (radius * radius - dy * dy));
            rect(cx - dx, cy + dy, 2 * dx + 1, 1, colour);
        }
    }

    void text(String text, int x, int y, int scale, int colour) {
        int cursor = x;
        for (int i = 0; i < text.length(); i++) {
            int[] glyph = Font5x7.glyph(text.charAt(i));
            for (int row = 0; row < Font5x7.HEIGHT; row++) {
                int bits = glyph[row];
                for (int column = 0; column < Font5x7.WIDTH; column++) {
                    if ((bits & (1 << (Font5x7.WIDTH - 1 - column))) != 0) {
                        rect(cursor + column * scale, y + row * scale, scale, scale, colour);
                    }
                }
            }
            cursor += (Font5x7.WIDTH + 1) * scale;
        }
    }

    static int textWidth(String text, int scale) {
        return Font5x7.width(text, scale);
    }

    /** Copies another canvas' pixels into this one at an offset; used to place the cover on a card. */
    void blit(Canvas source, int x, int y) {
        for (int row = 0; row < source.height; row++) {
            int targetY = y + row;
            if (targetY < 0 || targetY >= height) {
                continue;
            }
            for (int column = 0; column < source.width; column++) {
                int targetX = x + column;
                if (targetX >= 0 && targetX < width) {
                    pixels[targetY * width + targetX] = source.pixels[row * source.width + column];
                }
            }
        }
    }
}
