package dev.chronotone.render.art;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlbumArtTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 19);
    private static final String FINGERPRINT = "a1".repeat(32);
    private static final String OTHER = "b2".repeat(32);

    @Test
    void theSameAlbumDrawsTheSameBytes() {
        AlbumArt.Album album = albumWith(FINGERPRINT, 5, 0, 1, 3, 4);
        Png.Image first = AlbumArt.cover(album, 480);
        Png.Image second = AlbumArt.cover(album, 480);
        assertTrue(java.util.Arrays.equals(first.bytes(), second.bytes()));

        Png.Image cardOne = AlbumArt.share(album);
        Png.Image cardTwo = AlbumArt.share(album);
        assertTrue(java.util.Arrays.equals(cardOne.bytes(), cardTwo.bytes()));
    }

    @Test
    void aDifferentAlbumDrawsADifferentPicture() throws IOException {
        BufferedImage one = ImageIO.read(new ByteArrayInputStream(
                AlbumArt.cover(albumWith(FINGERPRINT, 5, 0, 1, 3, 4), 480).bytes()));
        BufferedImage two = ImageIO.read(new ByteArrayInputStream(
                AlbumArt.cover(albumWith(OTHER, 5, 0, 1, 3, 4), 480).bytes()));
        assertNotEquals(one.getRGB(5, 5), two.getRGB(5, 5), "the palette is chosen by the fingerprint");
    }

    @Test
    void anEmptyDayIsAGapNotABar() {
        // Day index 2 is missing: requested five days, rendered four.
        AlbumArt.Album album = albumWith(FINGERPRINT, 5, 0, 1, 3, 4);
        Canvas canvas = AlbumArt.canvas(album, 900);

        int margin = 900 / 18;
        int slot = (900 - 2 * margin) / 5;
        int horizon = (int) StrictMath.round(900 * 0.70);

        int rendered = canvas.pixels()[570 * 900 + (margin + slot + slot / 2)];
        int empty = canvas.pixels()[570 * 900 + (margin + 2 * slot + slot / 2)];

        assertTrue(rendered == 5 || rendered == 6, "a rendered day is a bar");
        assertEquals(0, empty, "an empty day stays background");
    }

    @Test
    void theManifestAloneIsEnough() {
        AlbumArt.Album album = AlbumArt.Album.fromManifest(manifest());
        assertEquals(FROM, album.from());
        assertEquals(5, album.requestedDays());
        assertEquals(42L, album.seed());
        assertEquals(FINGERPRINT, album.fingerprint());
        assertEquals(2, album.days().size());
        assertEquals("phrygian", album.days().getFirst().mode());
        assertEquals(8, album.days().getFirst().root());
        assertEquals("OCTOBER 2026", album.monthLabel());
        assertEquals(5, album.columns());
        assertTrue(AlbumArt.cover(album, 480).bytes().length > 0);
    }

    @Test
    void theShareCardIsTheSizeSocialPreviewsWant() {
        Png.Image card = AlbumArt.share(albumWith(FINGERPRINT, 5, 0, 1, 3, 4));
        assertEquals(AlbumArt.CARD_WIDTH, card.width());
        assertEquals(AlbumArt.CARD_HEIGHT, card.height());
    }

    @Test
    void theLongestLinesFitTheCardPanel() {
        int panel = AlbumArt.CARD_WIDTH - AlbumArt.CARD_HEIGHT - 96;
        assertTrue(Canvas.textWidth("RENDERED AS SOUND", 4) < panel);
        assertTrue(Canvas.textWidth("CHRONOTONE", 5) < panel);
        assertTrue(Canvas.textWidth("OCTOBER 2026", 4) < panel);
        assertTrue(Canvas.textWidth("MAPPING V1.0 / GRID 1M", 2) < panel);
        assertTrue(Canvas.textWidth(FINGERPRINT.substring(0, 32).toUpperCase(java.util.Locale.ROOT), 2) < panel);
    }

    private static AlbumArt.Album albumWith(String fingerprint, int requested, int... renderedIndexes) {
        List<AlbumArt.Day> days = new ArrayList<>();
        for (int index : renderedIndexes) {
            days.add(new AlbumArt.Day(FROM.plusDays(index), "dorian", index % 2 == 0 ? 8 : 3,
                    72 + index, 120 + index, 90, 35.333, 0.2 + 0.1 * index, 0.5, 0.2, 0.3));
        }
        return new AlbumArt.Album(FROM, requested, 42, "v1.0", fingerprint, 171.161, List.copyOf(days));
    }

    private static Map<String, Object> manifest() {
        Map<String, Object> music = new LinkedHashMap<>();
        music.put("mode", "phrygian");
        music.put("root", 8L);
        music.put("tempo_min", 72L);
        music.put("tempo_max", 98L);
        music.put("energy", 0.235);
        music.put("brightness", 0.333);
        music.put("hats", 0.113);
        music.put("reverb", 0.297);

        List<Object> tracks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> track = new LinkedHashMap<>();
            track.put("day", FROM.plusDays(i).toString());
            track.put("events", 3L);
            track.put("slots", 10L);
            track.put("notes", 90L);
            track.put("seconds", 35.333);
            track.put("music", music);
            track.put("wav_sha256", "c3".repeat(32));
            tracks.add(track);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "chronotone.album/1");
        manifest.put("from", FROM.toString());
        manifest.put("to", FROM.plusDays(5).toString());
        manifest.put("requested_days", 5L);
        manifest.put("mapping", "v1.0");
        manifest.put("seed", 42L);
        manifest.put("seconds", 171.161);
        manifest.put("fingerprint", FINGERPRINT);
        manifest.put("tracks", tracks);
        return manifest;
    }
}
