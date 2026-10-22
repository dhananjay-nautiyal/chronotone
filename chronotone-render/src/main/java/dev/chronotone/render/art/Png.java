package dev.chronotone.render.art;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * A very small indexed-colour PNG writer.
 *
 * <p>Chronotone publishes images the same way it publishes audio: as a hash. That only means something
 * if the bytes are identical on every machine, and a normal PNG encoder cannot promise that — it hands
 * the pixels to zlib, and zlib's compressed output depends on the zlib build. So this writer does not
 * compress at all: it emits stored (uncompressed) deflate blocks, whose layout is fixed by RFC 1951.
 * The files are bigger; the bytes are ours to guarantee.
 *
 * <p>Colour type 3 (indexed) with a 4-bit palette is deliberate too: the art is flat colour, so a
 * palette is all it needs, and 4 bits per pixel keeps an uncompressed 900×900 cover around 400 KB.
 */
public final class Png {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private Png() {
    }

    /** The encoded file plus the facts a caller wants to print. */
    public record Image(int width, int height, byte[] bytes) {
    }

    /**
     * Encodes {@code pixels} (one palette index per pixel, row-major) as an indexed PNG.
     *
     * @param bitDepth 1, 2, 4 or 8; the palette may hold at most {@code 1 << bitDepth} entries
     * @param text     tEXt chunks, written in iteration order; must be Latin-1-safe
     */
    public static Image indexed(int width, int height, int[] palette, byte[] pixels, int bitDepth,
                                Map<String, String> text) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("an image needs a positive size");
        }
        if (pixels.length != width * height) {
            throw new IllegalArgumentException("expected " + (width * height) + " pixels, got " + pixels.length);
        }
        if (bitDepth != 1 && bitDepth != 2 && bitDepth != 4 && bitDepth != 8) {
            throw new IllegalArgumentException("unsupported bit depth " + bitDepth);
        }
        int maxColours = 1 << bitDepth;
        if (palette.length == 0 || palette.length > maxColours) {
            throw new IllegalArgumentException("a " + bitDepth + "-bit image holds at most " + maxColours + " colours");
        }
        for (byte pixel : pixels) {
            if ((pixel & 0xff) >= palette.length) {
                throw new IllegalArgumentException("pixel index " + (pixel & 0xff) + " is not in the palette");
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(pixels.length / 2 + 4096);
        out.writeBytes(SIGNATURE);

        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = (byte) bitDepth;
        ihdr[9] = 3;   // indexed colour
        ihdr[10] = 0;  // deflate
        ihdr[11] = 0;  // adaptive filtering
        ihdr[12] = 0;  // no interlace
        chunk(out, "IHDR", ihdr);

        byte[] plte = new byte[palette.length * 3];
        for (int i = 0; i < palette.length; i++) {
            plte[3 * i] = (byte) (palette[i] >>> 16);
            plte[3 * i + 1] = (byte) (palette[i] >>> 8);
            plte[3 * i + 2] = (byte) palette[i];
        }
        chunk(out, "PLTE", plte);

        for (Map.Entry<String, String> entry : text.entrySet()) {
            byte[] keyword = entry.getKey().getBytes(StandardCharsets.ISO_8859_1);
            byte[] value = entry.getValue().getBytes(StandardCharsets.ISO_8859_1);
            byte[] data = new byte[keyword.length + 1 + value.length];
            System.arraycopy(keyword, 0, data, 0, keyword.length);
            System.arraycopy(value, 0, data, keyword.length + 1, value.length);
            chunk(out, "tEXt", data);
        }

        int rowBytes = (width * bitDepth + 7) / 8;
        byte[] raw = new byte[height * (rowBytes + 1)];
        int at = 0;
        for (int y = 0; y < height; y++) {
            raw[at++] = 0; // filter: None, so the scanline is exactly the packed pixels
            int rowStart = y * width;
            int bit = 0;
            int value = 0;
            for (int x = 0; x < width; x++) {
                value = (value << bitDepth) | (pixels[rowStart + x] & 0xff);
                bit += bitDepth;
                if (bit == 8) {
                    raw[at++] = (byte) value;
                    bit = 0;
                    value = 0;
                }
            }
            if (bit > 0) {
                raw[at++] = (byte) (value << (8 - bit));
            }
        }
        chunk(out, "IDAT", zlibStored(raw));

        chunk(out, "IEND", new byte[0]);
        return new Image(width, height, out.toByteArray());
    }

    /** RFC 1951 §3.2.4: a zlib stream whose deflate payload is one or more stored blocks. */
    private static byte[] zlibStored(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + raw.length / 65535 * 5 + 16);
        out.write(0x78); // CMF: deflate, 32K window
        out.write(0x01); // FLG: (0x78 << 8 | 0x01) % 31 == 0, no dictionary

        int pos = 0;
        while (pos < raw.length) {
            int length = Math.min(65535, raw.length - pos);
            boolean last = pos + length == raw.length;
            out.write(last ? 1 : 0);
            out.write(length & 0xff);
            out.write((length >>> 8) & 0xff);
            int complement = ~length & 0xffff;
            out.write(complement & 0xff);
            out.write((complement >>> 8) & 0xff);
            out.write(raw, pos, length);
            pos += length;
        }
        if (raw.length == 0) {
            out.write(1);
            out.write(0);
            out.write(0);
            out.write(0xff);
            out.write(0xff);
        }

        long adler = adler32(raw);
        for (int shift = 24; shift >= 0; shift -= 8) {
            out.write((int) (adler >>> shift) & 0xff);
        }
        return out.toByteArray();
    }

    static long adler32(byte[] data) {
        final int mod = 65521;
        int a = 1;
        int b = 0;
        for (byte value : data) {
            a = (a + (value & 0xff)) % mod;
            b = (b + a) % mod;
        }
        return ((long) b << 16) | a;
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        byte[] length = new byte[4];
        putInt(length, 0, data.length);
        out.writeBytes(length);
        out.writeBytes(name);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        byte[] sum = new byte[4];
        putInt(sum, 0, (int) crc.getValue());
        out.writeBytes(sum);
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    /** Small helper for callers that want ordered metadata. */
    public static Map<String, String> text(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
