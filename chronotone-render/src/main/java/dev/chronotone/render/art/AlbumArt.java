package dev.chronotone.render.art;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The album's cover and share card, drawn from {@code album.json} alone.
 *
 * <p>No log, no second render, no image library: a published manifest is enough to make the artwork,
 * which means a listener can regenerate the cover of an album they were given and check that it is the
 * same file. Every number on the poster comes from the manifest, and every choice of colour or
 * geometry comes from the album's {@code fingerprint}.
 *
 * <p>The picture is the month. One column per requested day, its height the day's mean energy, its
 * colour the parity of the root the timeline chose. Empty days are a tick on the horizon, not a bar.
 * The sun sits on that horizon, sized by how bright the month was; the bars rise in front of it.
 */
public final class AlbumArt {

    /** Share cards are 1200×630 — the size every social preview wants. */
    public static final int CARD_WIDTH = 1200;
    public static final int CARD_HEIGHT = 630;
    public static final int COVER_SIZE = 900;

    private static final int BACKGROUND = 0;
    private static final int GROUND = 1;
    private static final int LINE = 2;
    private static final int PAPER = 3;
    private static final int ACCENT = 4;
    private static final int BAR_EVEN = 5;
    private static final int BAR_ODD = 6;
    private static final int HIGHLIGHT = 7;

    /** Four risograph-ish palettes: ink, shade, line, paper, accent, bar, bar, highlight. */
    private static final int[][] PALETTES = {
            {0x0B0E14, 0x151C28, 0x33415A, 0xF2EFE9, 0xE8B04B, 0x4C7C8A, 0xA34A3C, 0xD9C9A8},
            {0x140D0B, 0x251714, 0x5A3428, 0xF6E7D2, 0xE2703A, 0x8C3B2E, 0xC9A227, 0xF2B134},
            {0x0C120F, 0x18241D, 0x3C5A48, 0xEDEBD7, 0xD9A62E, 0x4F7A5A, 0x8FA98F, 0xE6D5A8},
            {0x0D0B14, 0x1B1526, 0x463A63, 0xEADCE8, 0xD6A2C4, 0x6C5B9E, 0x3F8C8C, 0xF4D06F},
    };

    private AlbumArt() {
    }

    /** One rendered day, reduced to the handful of numbers a poster can use. */
    public record Day(LocalDate date, String mode, int root, int tempoMin, int tempoMax, int notes,
                      double seconds, double energy, double brightness, double hats, double reverb) {
    }

    /**
     * Everything the art needs, read back from a published {@code album.json}. The fingerprint is the
     * album's identity; the dates and the music summary are what the picture is made of.
     */
    public record Album(LocalDate from, int requestedDays, long seed, String mapping, String fingerprint,
                        double seconds, List<Day> days) {

        public Day day(int index) {
            LocalDate wanted = from.plusDays(index);
            for (Day day : days) {
                if (day.date().equals(wanted)) {
                    return day;
                }
            }
            return null;
        }

        public int columns() {
            return Math.max(requestedDays, days.size());
        }

        public double meanBrightness() {
            if (days.isEmpty()) {
                return 0.5;
            }
            double total = 0.0;
            for (Day day : days) {
                total += day.brightness();
            }
            return total / days.size();
        }

        public String monthLabel() {
            Month month = from.getMonth();
            return month.name() + " " + from.getYear();
        }

        public String span() {
            long total = StrictMath.round(seconds);
            long hours = total / 3600;
            long minutes = (total % 3600) / 60;
            if (hours > 0) {
                return hours + "H " + minutes + "M";
            }
            return minutes + "M " + (total % 60) + "S";
        }

        public static Album fromManifest(Map<String, Object> manifest) {
            String format = string(manifest.get("format"));
            if (!"chronotone.album/1".equals(format)) {
                throw new IllegalArgumentException("not a chronotone album manifest: " + format);
            }
            LocalDate from = LocalDate.parse(string(manifest.get("from")));
            String fingerprint = string(manifest.get("fingerprint"));
            if (fingerprint == null || fingerprint.length() != 64) {
                throw new IllegalArgumentException(
                        "album.json has no fingerprint — regenerate it with this version of chronotone");
            }

            List<Day> days = new ArrayList<>();
            for (Object element : list(manifest.get("tracks"))) {
                Map<String, Object> track = object(element);
                Map<String, Object> music = object(track.get("music"));
                days.add(new Day(
                        LocalDate.parse(string(track.get("day"))),
                        string(music.get("mode")),
                        (int) number(music.get("root")),
                        (int) number(music.get("tempo_min")),
                        (int) number(music.get("tempo_max")),
                        (int) number(track.get("notes")),
                        number(track.get("seconds")),
                        number(music.get("energy")),
                        number(music.get("brightness")),
                        number(music.get("hats")),
                        number(music.get("reverb"))));
            }

            return new Album(from,
                    (int) number(manifest.get("requested_days")),
                    (long) number(manifest.get("seed")),
                    string(manifest.get("mapping")),
                    fingerprint,
                    number(manifest.get("seconds")),
                    List.copyOf(days));
        }
    }

    /** The square cover: a month of bars in front of a setting sun. */
    public static Png.Image cover(Album album, int size) {
        int square = Math.max(320, size);
        int[] palette = palette(album.fingerprint());
        Canvas canvas = draw(album, square, palette);
        Map<String, String> text = new LinkedHashMap<>();
        text.put("Software", "chronotone");
        text.put("Title", "Chronotone - " + titleCase(album.monthLabel()));
        text.put("Description", album.days().size() + " days of " + album.mapping()
                + ", one gain; seed " + album.seed());
        text.put("Comment", "album fingerprint " + album.fingerprint());
        return Png.indexed(square, square, palette, indices(canvas), 4, text);
    }

    /** The 1200×630 card: the cover on the left, the album's identity on the right. */
    public static Png.Image share(Album album) {
        int[] palette = palette(album.fingerprint());
        Canvas card = new Canvas(CARD_WIDTH, CARD_HEIGHT, PAPER);
        card.blit(draw(album, CARD_HEIGHT, palette), 0, 0);

        int x = CARD_HEIGHT + 48;
        int y = 56;
        card.rect(CARD_HEIGHT - 1, 0, 1, CARD_HEIGHT, BACKGROUND);

        card.text("CHRONOTONE", x, y, 5, BACKGROUND);
        y += 48;
        card.rect(x, y, 360, 3, ACCENT);

        y += 28;
        card.text(album.monthLabel(), x, y, 4, BACKGROUND);

        y += 54;
        card.text(album.days().size() + " DAYS / " + album.span(), x, y, 3, BACKGROUND);
        y += 30;
        card.text(album.days().size() + " TRACKS / SEED " + album.seed(), x, y, 3, BACKGROUND);
        y += 30;
        card.text("MAPPING " + album.mapping() + " / GRID 1M", x, y, 2, LINE);

        y += 54;
        card.rect(x, y, 360, 1, LINE);

        y += 34;
        card.text("YOUR LIFE,", x, y, 4, BACKGROUND);
        y += 42;
        card.text("RENDERED AS SOUND", x, y, 4, BACKGROUND);

        y = CARD_HEIGHT - 150;
        card.text("SHA256", x, y, 2, LINE);
        String fingerprint = album.fingerprint().toUpperCase(Locale.ROOT);
        card.text(fingerprint.substring(0, 32), x, y + 22, 2, BACKGROUND);
        card.text(fingerprint.substring(32), x, y + 44, 2, BACKGROUND);
        card.text("GITHUB.COM/DHANANJAY-NAUTIYAL/CHRONOTONE", x, CARD_HEIGHT - 34, 1, LINE);

        Map<String, String> text = new LinkedHashMap<>();
        text.put("Software", "chronotone");
        text.put("Title", "Chronotone - " + titleCase(album.monthLabel()) + " share card");
        text.put("Comment", "album fingerprint " + album.fingerprint());
        return Png.indexed(CARD_WIDTH, CARD_HEIGHT, palette, indices(card), 4, text);
    }

    /** The cover as an index canvas, before PNG encoding. Visible for layout tests. */
    static Canvas canvas(Album album, int size) {
        return draw(album, size, palette(album.fingerprint()));
    }

    private static Canvas draw(Album album, int size, int[] palette) {
        Canvas canvas = new Canvas(size, size, BACKGROUND);
        int margin = size / 18;
        int horizon = (int) StrictMath.round(size * 0.70);
        int titleScale = Math.max(2, size / 112);
        int subScale = Math.max(1, Math.round(titleScale * 0.4f));
        int footScale = Math.max(1, size / 300);

        int columns = album.columns();
        int usable = size - 2 * margin;
        int slot = Math.max(3, usable / columns);
        int gap = Math.max(2, slot / 5);
        int barWidth = Math.max(2, slot - gap);
        int maxBar = horizon - (margin + size / 5);

        // The sun sits in the sky and the bars rise in front of it: a skyline at the end of a day.
        int sunRadius = (int) StrictMath.round(size * (0.13 + 0.09 * album.meanBrightness()));
        canvas.disc((int) StrictMath.round(size * 0.62), horizon - maxBar / 2, sunRadius, ACCENT);

        canvas.rect(0, horizon, size, size - horizon, GROUND);
        canvas.rect(0, horizon, size, Math.max(2, size / 400), LINE);
        int grid = Math.max(10, size / 26);
        int footTop = size - margin - footScale * 12;
        for (int row = horizon + grid; row < footTop; row += grid) {
            canvas.rect(0, row, size, 1, LINE);
        }

        for (int i = 0; i < columns; i++) {
            int x = margin + i * slot + gap / 2;
            Day day = album.day(i);
            if (day == null) {
                canvas.rect(x, horizon - 2, barWidth, 3, LINE);
                continue;
            }
            int height = Math.max(4, (int) StrictMath.round(day.energy() * maxBar));
            canvas.rect(x, horizon - height, barWidth, height,
                    day.root() % 2 == 0 ? BAR_EVEN : BAR_ODD);
            canvas.rect(x, horizon + 3, barWidth, Math.max(2, height / 6), LINE);
            if (day.notes() > 0) {
                int radius = Math.max(1, size / 240);
                canvas.disc(x + barWidth / 2, horizon - height - 3 * radius, radius, HIGHLIGHT);
            }
        }

        canvas.text("CHRONOTONE", margin, margin, titleScale, PAPER);
        canvas.text(album.monthLabel(), margin, margin + titleScale * 8, subScale, HIGHLIGHT);
        String days = album.days().size() + " DAYS";
        canvas.text(days, size - margin - Canvas.textWidth(days, subScale), margin, subScale, PAPER);

        int footY = size - margin - footScale * 7;
        String stamp = "SHA256 " + album.fingerprint().substring(0, 12).toUpperCase(Locale.ROOT);
        canvas.text(stamp, margin, footY, footScale, PAPER);
        String seed = "SEED " + album.seed();
        canvas.text(seed, size - margin - Canvas.textWidth(seed, footScale), footY, footScale, PAPER);
        return canvas;
    }

    private static byte[] indices(Canvas canvas) {
        int[] pixels = canvas.pixels();
        byte[] indices = new byte[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            indices[i] = (byte) pixels[i];
        }
        return indices;
    }

    private static int[] palette(String fingerprint) {
        int choice = Integer.parseInt(fingerprint.substring(0, 2), 16);
        return PALETTES[choice % PALETTES.length];
    }

    private static String titleCase(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean start = true;
        for (char character : text.toCharArray()) {
            out.append(start ? character : Character.toLowerCase(character));
            start = character == ' ';
        }
        return out.toString();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException("expected a number, got " + value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
