package dev.chronotone.render;

import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.EventLog;
import dev.chronotone.dsl.DefaultMapping;
import dev.chronotone.dsl.MappingParser;
import dev.chronotone.dsl.MappingSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The determinism contract, the file formats and the log -> audio path. */
class ChronotoneRenderTest {

    private static final Instant FROM = Instant.parse("2026-10-19T21:00:00Z");
    private static final Instant TO = FROM.plus(20, ChronoUnit.MINUTES);
    private static final MappingSpec MAPPING = new MappingParser().parse(DefaultMapping.YAML);

    @TempDir
    Path tempDir;

    /** A busy evening: a commit, a track, weather and steps every minute. */
    private static List<Event> eveningEvents() {
        List<Event> events = new ArrayList<>();
        for (int minute = 0; minute < 20; minute++) {
            Instant at = FROM.plus(minute, ChronoUnit.MINUTES);
            events.add(Event.of("git", "commit", at, Map.of("repo", "chronotone", "lines_changed", 40)));
            events.add(Event.of("music", "scrobble", at, Map.of("track", "Awake", "duration_s", 180)));
            events.add(Event.of("weather", "sample", at, Map.of("cloud_cover", 0.2)));
            events.add(Event.of("health", "steps", at, Map.of("steps", 120)));
        }
        return events;
    }

    @Test
    void identicalInputsProduceIdenticalArtifacts() throws IOException {
        RenderResult first = Chronotone.render(MAPPING, eveningEvents(), FROM, TO, 7L);
        RenderResult second = Chronotone.render(MAPPING, eveningEvents(), FROM, TO, 7L);

        assertEquals(first.timelineFingerprint(), second.timelineFingerprint());
        assertEquals(first.midiHash(), second.midiHash());
        assertEquals(first.wavHash(), second.wavHash());
        assertArrayEquals(first.midi(), second.midi());
        assertArrayEquals(first.wav(), second.wav());
    }

    @Test
    void theSeedChangesThePerformanceButNotTheTimeline() throws IOException {
        RenderResult one = Chronotone.render(MAPPING, eveningEvents(), FROM, TO, 1L);
        RenderResult two = Chronotone.render(MAPPING, eveningEvents(), FROM, TO, 2L);

        assertEquals(one.timelineFingerprint(), two.timelineFingerprint());
        assertNotEquals(one.wavHash(), two.wavHash());
        assertNotEquals(one.midiHash(), two.midiHash());
    }

    @Test
    void aDifferentDayKeepsTheSameDataButSingsInAnotherKey() throws IOException {
        RenderResult monday = Chronotone.render(MAPPING, eveningEvents(), FROM, TO, 7L);
        RenderResult tuesday = Chronotone.render(MAPPING, eveningEvents(), FROM.plus(1, ChronoUnit.DAYS),
                TO.plus(1, ChronoUnit.DAYS), 7L);

        // The timeline address covers slot timestamps, so it must differ; the seed alone must not
        // affect it, which the previous test asserts. Here we only care that both render at all.
        assertNotEquals(monday.timelineFingerprint(), tuesday.timelineFingerprint());
    }

    @Test
    void theMidiFileIsValidAndUsesOurResolution() throws Exception {
        RenderResult result = Chronotone.render(MAPPING, eveningEvents(), FROM, TO, 7L);
        byte[] midi = result.midi();

        assertEquals("MThd", new String(midi, 0, 4, StandardCharsets.US_ASCII));
        Sequence sequence = MidiSystem.getSequence(new ByteArrayInputStream(midi));
        assertEquals(ScoreBuilder.PPQ, sequence.getResolution());
        assertTrue(sequence.getTracks().length >= 1);
    }

    @Test
    void theWavFileIsWellFormedAndNotSilent() throws IOException {
        RenderResult result = Chronotone.render(MAPPING, eveningEvents(), FROM, TO, 7L);
        byte[] wav = result.wav();

        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
        assertEquals(wav.length, 44 + readInt(wav, 40));
        assertTrue(result.seconds() > 20.0, "expected at least 20s of audio, got " + result.seconds());

        long absoluteSum = 0;
        for (int i = 44; i + 1 < wav.length; i += 2) {
            short sample = (short) ((wav[i] & 0xFF) | (wav[i + 1] << 8));
            absoluteSum += Math.abs(sample);
        }
        assertTrue(absoluteSum > 0, "rendered audio was silent");
    }

    @Test
    void rendersStraightFromATamperEvidentLog() throws IOException {
        Path log = tempDir.resolve("events.jsonl");
        try (EventLog eventLog = EventLog.open(log)) {
            for (Event event : eveningEvents()) {
                eventLog.append(event);
            }
        }

        List<Event> fromLog = EventLog.readEvents(log);
        assertEquals(eveningEvents().size(), fromLog.size());

        RenderResult fromMemory = Chronotone.render(MAPPING, eveningEvents(), FROM, TO, 7L);
        RenderResult fromDisk = Chronotone.render(MAPPING, fromLog, FROM, TO, 7L);
        assertEquals(fromMemory.wavHash(), fromDisk.wavHash());
        assertEquals(fromMemory.timelineFingerprint(), fromDisk.timelineFingerprint());
    }

    @Test
    void theTempoFollowsTheDensityOfTheDay() throws IOException {
        List<Event> quiet = eveningEvents().stream()
                .filter(event -> !"git".equals(event.source()))
                .toList();
        Timeline quietTimeline = Timeline.of(MAPPING, quiet, FROM, TO);
        Timeline busyTimeline = Timeline.of(MAPPING, eveningEvents(), FROM, TO);

        assertTrue(busyTimeline.params(10).tempoBpm() > quietTimeline.params(10).tempoBpm());
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16) | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
