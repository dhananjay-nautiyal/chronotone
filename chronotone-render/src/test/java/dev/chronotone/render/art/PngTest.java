package dev.chronotone.render.art;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PngTest {

    private static final int[] PALETTE = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0x000000};

    @Test
    void anEncoderThatProducesARealPng() throws IOException {
        // 3×2 at 4 bits per pixel: two pixels per byte, so the packing is actually exercised.
        byte[] pixels = {0, 1, 2, 3, 4, 0};
        Png.Image image = Png.indexed(3, 2, PALETTE, pixels, 4, Png.text("Comment", "chronotone"));

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image.bytes()));
        assertEquals(3, decoded.getWidth());
        assertEquals(2, decoded.getHeight());
        assertEquals(0xFFFF0000, decoded.getRGB(0, 0));
        assertEquals(0xFF00FF00, decoded.getRGB(1, 0));
        assertEquals(0xFF0000FF, decoded.getRGB(2, 0));
        assertEquals(0xFFFFFFFF, decoded.getRGB(0, 1));
        assertEquals(0xFF000000, decoded.getRGB(1, 1));
    }

    @Test
    void theBytesAreTheSameEveryTime() {
        byte[] pixels = {0, 1, 2, 3, 4, 0, 1, 2, 3, 4, 0, 1};
        byte[] first = Png.indexed(4, 3, PALETTE, pixels, 4, Png.text("Software", "chronotone")).bytes();
        byte[] second = Png.indexed(4, 3, PALETTE, pixels, 4, Png.text("Software", "chronotone")).bytes();
        assertTrue(java.util.Arrays.equals(first, second));
    }

    @Test
    void theDeflateStreamIsStoredNotCompressed() {
        // zlib header then a final stored block: 0x78 0x01, 0x01, LEN, NLEN, bytes...
        byte[] pixels = new byte[64];
        byte[] bytes = Png.indexed(8, 8, PALETTE, pixels, 4, Png.text()).bytes();
        int idat = indexOf(bytes, "IDAT".getBytes(StandardCharsets.US_ASCII));
        assertEquals(0x78, bytes[idat + 4] & 0xff);
        assertEquals(0x01, bytes[idat + 5] & 0xff);
        assertEquals(0x01, bytes[idat + 6] & 0xff, "BFINAL=1, BTYPE=00");
        int length = (bytes[idat + 7] & 0xff) | ((bytes[idat + 8] & 0xff) << 8);
        int complement = (bytes[idat + 9] & 0xff) | ((bytes[idat + 10] & 0xff) << 8);
        assertEquals(8 * 5, length, "8 rows of one filter byte plus four packed pixels");
        assertEquals(~length & 0xffff, complement);
    }

    @Test
    void theAdlerChecksumMatchesTheReferenceValue() {
        assertEquals(0x11E60398L, Png.adler32("Wikipedia".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void textChunksSurviveTheRoundTrip() {
        byte[] bytes = Png.indexed(2, 1, PALETTE, new byte[]{0, 0}, 4,
                Png.text("Comment", "album fingerprint abc")).bytes();
        String haystack = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(haystack.contains("Comment\u0000album fingerprint abc"));
    }

    @Test
    void aPixelOutsideThePaletteIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Png.indexed(1, 1, PALETTE, new byte[]{9}, 4, Png.text()));
    }

    @Test
    void aPaletteTooLargeForTheBitDepthIsRejected() {
        int[] tooMany = new int[17];
        assertThrows(IllegalArgumentException.class,
                () -> Png.indexed(1, 1, tooMany, new byte[]{0}, 4, Png.text()));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
