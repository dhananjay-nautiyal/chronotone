package dev.chronotone.render;

import dev.chronotone.core.connector.SyntheticConnector;
import dev.chronotone.core.log.Event;
import dev.chronotone.dsl.DefaultMapping;
import dev.chronotone.dsl.MappingParser;
import dev.chronotone.dsl.MappingSpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live station mode. The property that matters is that going live changes nothing about the audio:
 * a station frame is the very same bytes {@code chronotone render} would have produced for that
 * window, cut on the grid so that slots follow the wall clock.
 */
class StationTest {

    private static final MappingSpec MAPPING = new MappingParser().parse(DefaultMapping.YAML);
    private static final Duration WINDOW = Duration.ofMinutes(30);
    private static final Instant DAY = Instant.parse("2026-10-19T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-19T21:07:43Z");

    private static List<Event> day() {
        return new SyntheticConnector(42L).fetch(DAY, DAY.plus(24, ChronoUnit.HOURS));
    }

    @Test
    void aStationFrameIsTheSameBytesAsABatchRender() throws Exception {
        Station.Frame frame = Station.frame(MAPPING, day(), NOW, WINDOW, 7L);
        RenderResult batch = Chronotone.render(MAPPING, day(), frame.from(), frame.to(), 7L);

        assertEquals(batch.wavHash(), frame.wavHash(), "the station must master exactly what render does");
        assertArrayEquals(batch.wav(), frame.wav());
        assertEquals(batch.timelineFingerprint(), frame.timelineFingerprint());
        assertEquals(batch.notes(), frame.notes());
    }

    @Test
    void theWindowEndsOnAGridBoundarySoSlotsFollowTheWallClock() {
        Station.Frame frame = Station.frame(MAPPING, day(), NOW, WINDOW, 7L);

        assertEquals(Instant.parse("2026-10-19T21:07:00Z"), frame.to());
        assertEquals(Instant.parse("2026-10-19T20:37:00Z"), frame.from());
        assertEquals(30, frame.slots());
        assertEquals(frame.to().minus(MAPPING.grid()), frame.timeline().slot(frame.slots() - 1));
    }

    @Test
    void alignTruncatesDownToThePreviousBoundary() {
        Duration minute = Duration.ofMinutes(1);
        assertEquals(Instant.parse("2026-10-19T21:07:00Z"), Station.align(Instant.parse("2026-10-19T21:07:59Z"), minute));
        assertEquals(Instant.parse("2026-10-19T21:07:00Z"), Station.align(Instant.parse("2026-10-19T21:07:00Z"), minute));
        assertEquals(Instant.parse("2026-10-19T21:00:00Z"),
                Station.align(Instant.parse("2026-10-19T21:14:00Z"), Duration.ofMinutes(15)));
    }

    @Test
    void theSameInstantGivesTheSameFrame() {
        assertEquals(Station.frame(MAPPING, day(), NOW, WINDOW, 7L).wavHash(),
                Station.frame(MAPPING, day(), NOW, WINDOW, 7L).wavHash());
    }

    @Test
    void aFreshListenerIsHandedExactlyTheSlotThatJustClosed() {
        Station.Frame first = Station.frame(MAPPING, day(), NOW, WINDOW, 7L);
        assertEquals(List.of(first.slots() - 1), Station.blocksAfter(first, Station.listenFrom(first)),
                "a listener should start with one slot, not with a window of backlog");

        // One minute later exactly one more slot has closed, and it is a different one.
        Station.Frame second = Station.frame(MAPPING, day(), NOW.plus(1, ChronoUnit.MINUTES), WINDOW, 7L);
        Instant cursor = second.timeline().slot(second.slots() - 2);
        assertEquals(List.of(second.slots() - 1), Station.blocksAfter(second, cursor));
        assertNotEquals(first.timeline().slot(first.slots() - 1), second.timeline().slot(second.slots() - 1));
    }

    @Test
    void catchingUpAfterASlowTickPlaysEveryMissedSlotInOrder() {
        Station.Frame first = Station.frame(MAPPING, day(), NOW, WINDOW, 7L);
        Instant cursor = Station.listenFrom(first);

        // The process slept for five minutes, so six slots closed after the cursor and none may be lost.
        Station.Frame later = Station.frame(MAPPING, day(), NOW.plus(5, ChronoUnit.MINUTES), WINDOW, 7L);
        List<Integer> blocks = Station.blocksAfter(later, cursor);

        assertEquals(List.of(24, 25, 26, 27, 28, 29), blocks);
        assertEquals(List.of(
                        Instant.parse("2026-10-19T21:06:00Z"),
                        Instant.parse("2026-10-19T21:07:00Z"),
                        Instant.parse("2026-10-19T21:08:00Z"),
                        Instant.parse("2026-10-19T21:09:00Z"),
                        Instant.parse("2026-10-19T21:10:00Z"),
                        Instant.parse("2026-10-19T21:11:00Z")),
                blocks.stream().map(later.timeline()::slot).toList());
        assertTrue(Station.blocksAfter(later, later.to()).isEmpty(), "nothing has closed at the instant itself");
    }

    @Test
    void everyBlockOfAFrameMatchesTheBatchRenderByteForByte() throws Exception {
        Station.Frame frame = Station.frame(MAPPING, day(), NOW, WINDOW, 7L);
        byte[] batch = Chronotone.render(MAPPING, day(), frame.from(), frame.to(), 7L).wav();
        int bytesPerSample = WavRenderer.BITS_PER_SAMPLE / 8;

        for (int block = 0; block <= frame.slots(); block++) {
            int start = WavRenderer.HEADER_BYTES + frame.bounds()[block] * bytesPerSample;
            int end = WavRenderer.HEADER_BYTES + frame.bounds()[block + 1] * bytesPerSample;
            assertArrayEquals(Arrays.copyOfRange(batch, start, end), frame.pcmOfBlock(block),
                    "streamed block " + block + " must be archival");
        }
    }

    @Test
    void theBoundsTileTheWavExactly() {
        Station.Frame frame = Station.frame(MAPPING, day(), NOW, WINDOW, 7L);
        int[] bounds = frame.bounds();

        assertEquals(frame.slots() + 2, bounds.length);
        assertEquals(0, bounds[0]);
        for (int i = 0; i < bounds.length - 1; i++) {
            assertTrue(bounds[i] < bounds[i + 1], "bounds must strictly increase: " + Arrays.toString(bounds));
        }
        assertEquals(frame.wav().length, WavRenderer.HEADER_BYTES + bounds[bounds.length - 1] * 2);
    }

    @Test
    void theLastBlockOfAFrameIsTheReleaseTail() {
        Station.Frame frame = Station.frame(MAPPING, day(), NOW, WINDOW, 7L);
        int tail = frame.bounds()[frame.slots() + 1] - frame.bounds()[frame.slots()];

        assertTrue(Math.abs(tail - 2 * WavRenderer.SAMPLE_RATE) <= 1, "expected a ~2s tail, got " + tail);
        assertTrue(frame.pcmOfBlock(frame.slots()).length > 0);
    }

    @Test
    void aWindowShorterThanTheGridIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Station.frame(MAPPING, day(), NOW, Duration.ofSeconds(30), 7L));
    }

    @Test
    void aLaterWindowHearsTheEventsThatArrivedSince() {
        List<Event> events = new ArrayList<>(day());
        List<Event> later = new ArrayList<>(events);
        later.add(Event.of("git", "commit", NOW.plus(3, ChronoUnit.MINUTES),
                Map.of("repo", "chronotone", "lines_changed", 300)));

        Station.Frame before = Station.frame(MAPPING, events, NOW, WINDOW, 7L);
        Station.Frame after = Station.frame(MAPPING, later, NOW.plus(10, ChronoUnit.MINUTES), WINDOW, 7L);

        assertNotEquals(before.wavHash(), after.wavHash());
        assertTrue(after.to().isAfter(before.to()));
        assertTrue(after.timeline().slot(after.slots() - 1).isAfter(before.timeline().slot(before.slots() - 1)));
    }

    @Test
    void aWindowWithNoEventsIsStillAValidMaster() throws Exception {
        // Counts clamp to zero rather than failing, so an empty log plays its gentlest form.
        Instant quiet = Instant.parse("2026-10-19T03:07:00Z");
        Station.Frame frame = Station.frame(MAPPING, List.of(), quiet, Duration.ofMinutes(5), 7L);

        assertEquals("RIFF", new String(frame.wav(), 0, 4, StandardCharsets.US_ASCII));
        assertEquals(5, frame.slots());
        assertEquals(Chronotone.render(MAPPING, List.of(), frame.from(), frame.to(), 7L).wavHash(), frame.wavHash());
    }
}
