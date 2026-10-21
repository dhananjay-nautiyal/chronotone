package dev.chronotone.render;

import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.EventLog;
import dev.chronotone.core.log.LoggedEvent;
import dev.chronotone.core.merkle.Commitment;
import dev.chronotone.dsl.DefaultMapping;
import dev.chronotone.dsl.MappingParser;
import dev.chronotone.dsl.MappingSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The album's promises are all checkable: one gain, tracks that are their own day, stems that add up,
 * and the same bytes every time.
 */
class AlbumTest {

    private static final MappingSpec MAPPING = new MappingParser().parse(DefaultMapping.YAML);
    private static final LocalDate DAY_ONE = LocalDate.of(2026, 3, 1);
    private static final LocalDate DAY_TWO = DAY_ONE.plusDays(1);
    private static final Duration WINDOW = Duration.ofMinutes(4);

    @Test
    void aOneDayAlbumIsByteIdenticalToARender() throws IOException {
        Path dir = Files.createTempDirectory("chronotone-album-single");
        Path log = logWith(dir, Map.of(DAY_ONE, 12));
        List<LoggedEvent> ledger = EventLog.readEntries(log);

        Album.Result album = Album.render(MAPPING, ledger, DAY_ONE, 1, LocalTime.MIDNIGHT, WINDOW,
                42L, dir.resolve("album"), false);

        Instant from = DAY_ONE.atStartOfDay(ZoneOffset.UTC).toInstant();
        RenderResult render = Chronotone.render(MAPPING, eventsOf(ledger), from, from.plus(WINDOW), 42L);

        assertEquals(render.wavHash(), album.tracks().getFirst().wavHash(),
                "an album of one day must not master differently from render");
        assertEquals(render.wav().length, Files.size(dir.resolve("album/tracks/" + DAY_ONE + ".wav")));
    }

    @Test
    void oneGainForTheWholeAlbum() throws IOException {
        Path dir = Files.createTempDirectory("chronotone-album-gain");
        Path log = logWith(dir, Map.of(DAY_ONE, 40, DAY_TWO, 1));

        Album.Result album = Album.render(MAPPING, EventLog.readEntries(log), DAY_ONE, 2,
                LocalTime.MIDNIGHT, WINDOW, 42L, dir.resolve("out"), false);

        short[] loud = pcm(dir.resolve("out/tracks/" + DAY_ONE + ".wav"));
        short[] quiet = pcm(dir.resolve("out/tracks/" + DAY_TWO + ".wav"));

        assertEquals(StrictMath.round(WavRenderer.PEAK * 32767), Math.max(peak(loud), peak(quiet)),
                "the loudest sample of the album sits exactly at the album's peak");
        assertTrue(peak(quiet) < peak(loud),
                "per-track normalisation would flatten a quiet day up to the album's peak");
    }

    @Test
    void stemsAddBackUpToTheTrack() throws IOException {
        Path dir = Files.createTempDirectory("chronotone-album-stems");
        Path log = logWith(dir, Map.of(DAY_ONE, 24));
        addScrobbles(log, DAY_ONE, 8);

        Album.render(MAPPING, EventLog.readEntries(log), DAY_ONE, 1, LocalTime.MIDNIGHT, WINDOW,
                42L, dir.resolve("out"), true);

        short[] track = pcm(dir.resolve("out/tracks/" + DAY_ONE + ".wav"));
        List<short[]> stems = new ArrayList<>();
        for (String name : Album.STEMS.keySet()) {
            short[] stem = pcm(dir.resolve("out/stems/" + DAY_ONE + "/" + name + ".wav"));
            assertEquals(track.length, stem.length, name + " must cover the whole track");
            stems.add(stem);
        }

        int worst = 0;
        for (int i = 0; i < track.length; i++) {
            int sum = 0;
            for (short[] stem : stems) {
                sum += stem[i];
            }
            worst = Math.max(worst, Math.abs(sum - track[i]));
        }
        assertTrue(worst <= stems.size(),
                "stem rounding may cost one least-significant bit per stem, but no more: " + worst);
        for (short[] stem : stems) {
            assertTrue(peak(stem) > 0, "every stem must carry something in a day with all five voices");
        }
    }

    @Test
    void sameInputsSameBytesEveryTime() throws IOException {
        Path dir = Files.createTempDirectory("chronotone-album-determinism");
        Path log = logWith(dir, Map.of(DAY_ONE, 9, DAY_TWO, 5));
        List<LoggedEvent> ledger = EventLog.readEntries(log);

        Album.Result first = Album.render(MAPPING, ledger, DAY_ONE, 2, LocalTime.MIDNIGHT, WINDOW,
                42L, dir.resolve("a"), false);
        Album.Result second = Album.render(MAPPING, ledger, DAY_ONE, 2, LocalTime.MIDNIGHT, WINDOW,
                42L, dir.resolve("b"), false);

        assertEquals(first.tracks().size(), second.tracks().size());
        for (int i = 0; i < first.tracks().size(); i++) {
            assertEquals(first.tracks().get(i).wavHash(), second.tracks().get(i).wavHash());
            assertEquals(first.tracks().get(i).timelineFingerprint(),
                    second.tracks().get(i).timelineFingerprint());
        }
        assertEquals(-1L, Files.mismatch(
                dir.resolve("a/tracks/" + DAY_ONE + ".wav"),
                dir.resolve("b/tracks/" + DAY_ONE + ".wav")));

        Album.Result reseeded = Album.render(MAPPING, ledger, DAY_ONE, 2, LocalTime.MIDNIGHT, WINDOW,
                43L, dir.resolve("c"), false);
        assertFalse(first.tracks().getFirst().wavHash().equals(reseeded.tracks().getFirst().wavHash()),
                "the seed must still matter");
    }

    @Test
    void emptyDaysAreSkippedAndNamed() throws IOException {
        Path dir = Files.createTempDirectory("chronotone-album-empty");
        Path log = logWith(dir, Map.of(DAY_ONE, 3, DAY_ONE.plusDays(2), 3));

        Album.Result album = Album.render(MAPPING, EventLog.readEntries(log), DAY_ONE, 3,
                LocalTime.MIDNIGHT, WINDOW, 42L, dir.resolve("out"), false);

        assertEquals(List.of(DAY_ONE, DAY_ONE.plusDays(2)),
                album.tracks().stream().map(Album.Track::day).toList());
        assertEquals(List.of(DAY_TWO), album.emptyDays());
        assertTrue(Files.notExists(dir.resolve("out/tracks/" + DAY_TWO + ".wav")));
    }

    @Test
    void eachTrackCarriesItsOwnDayCommitment() throws IOException {
        Path dir = Files.createTempDirectory("chronotone-album-commit");
        Path log = logWith(dir, Map.of(DAY_ONE, 4, DAY_TWO, 7));
        List<LoggedEvent> ledger = EventLog.readEntries(log);

        Album.Result album = Album.render(MAPPING, ledger, DAY_ONE, 2, LocalTime.MIDNIGHT, WINDOW,
                42L, dir.resolve("out"), false);

        for (Album.Track track : album.tracks()) {
            List<LoggedEvent> day = ledger.stream()
                    .filter(e -> !e.event().timestamp().isBefore(track.from()))
                    .filter(e -> e.event().timestamp().isBefore(track.to()))
                    .toList();
            Commitment commitment = Commitment.of(day);
            assertEquals(commitment.merkleRoot(), track.merkleRoot());
            assertEquals(commitment.chainHead(), track.chainHead());
            assertEquals(day.size(), track.events());
        }
    }

    @Test
    void aRangeWithNoEventsSaysSo() throws IOException {
        Path dir = Files.createTempDirectory("chronotone-album-silent");
        Path log = logWith(dir, Map.of(DAY_ONE, 2));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> Album.render(MAPPING, EventLog.readEntries(log), DAY_ONE.plusDays(5), 2,
                        LocalTime.MIDNIGHT, WINDOW, 42L, dir.resolve("out"), false));
        assertTrue(error.getMessage().contains("has any events"), error.getMessage());
    }

    @Test
    void leavesNoHalfWrittenTrackBehindOnFailure() throws IOException {
        Path dir = Files.createTempDirectory("chronotone-album-window");
        Path log = logWith(dir, Map.of(DAY_ONE, 2));

        assertThrows(IllegalArgumentException.class,
                () -> Album.render(MAPPING, EventLog.readEntries(log), DAY_ONE, 1,
                        LocalTime.MIDNIGHT, Duration.ofSeconds(30), 42L, dir.resolve("out"), false),
                "a window shorter than the grid cannot describe a day");
    }

    private static List<Event> eventsOf(List<LoggedEvent> ledger) {
        return ledger.stream().map(LoggedEvent::event).toList();
    }

    private static Path logWith(Path dir, Map<LocalDate, Integer> commitsPerDay) throws IOException {
        Path log = dir.resolve("log.jsonl");
        try (EventLog eventLog = EventLog.open(log)) {
            for (Map.Entry<LocalDate, Integer> entry : commitsPerDay.entrySet()) {
                Instant start = entry.getKey().atStartOfDay(ZoneOffset.UTC).toInstant();
                for (int i = 0; i < entry.getValue(); i++) {
                    eventLog.append(new Event("git", "commit", start.plusSeconds(5L * i),
                            Map.of("lines_changed", 40)));
                }
            }
        }
        return log;
    }

    private static void addScrobbles(Path log, LocalDate day, int count) throws IOException {
        try (EventLog eventLog = EventLog.open(log)) {
            Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
            for (int i = 0; i < count; i++) {
                eventLog.append(new Event("music", "scrobble", start.plusSeconds(7L * i), Map.of()));
            }
        }
    }

    private static short[] pcm(Path wav) throws IOException {
        byte[] bytes = Files.readAllBytes(wav);
        short[] samples = new short[(bytes.length - 44) / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) ((bytes[44 + 2 * i] & 0xff) | (bytes[45 + 2 * i] << 8));
        }
        return samples;
    }

    private static int peak(short[] samples) {
        int peak = 0;
        for (short sample : samples) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }
}
