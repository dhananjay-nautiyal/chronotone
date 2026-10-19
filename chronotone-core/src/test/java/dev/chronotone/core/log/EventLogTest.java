package dev.chronotone.core.log;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLogTest {

    private static final Instant T0 = Instant.parse("2026-10-19T09:00:00Z");

    @TempDir
    Path tempDir;

    private Path logFile() {
        return tempDir.resolve("events.jsonl");
    }

    @Test
    void appendsAndVerifies() throws IOException {
        try (EventLog log = EventLog.open(logFile())) {
            log.append(Event.of("git", "commit", T0, Map.of("repo", "chronotone", "lines_changed", 12)));
            log.append(Event.of("music", "scrobble", T0.plusSeconds(300), Map.of("track", "Awake")));
            assertEquals(2, log.size());
        }
        VerifyResult result = EventLog.verify(logFile());
        assertTrue(result.ok(), result.message());
        assertEquals(2, result.entries());
        assertFalse(result.head().isBlank());
    }

    @Test
    void anEmptyLogCommitsToGenesis() throws IOException {
        try (EventLog log = EventLog.open(logFile())) {
            assertEquals(Hashes.GENESIS, log.commitment());
        }
        assertTrue(EventLog.verify(logFile()).ok());
    }

    @Test
    void keepsChainingAcrossReopen() throws IOException {
        String firstHash;
        try (EventLog log = EventLog.open(logFile())) {
            firstHash = log.append(Event.of("git", "commit", T0)).hash();
        }

        try (EventLog log = EventLog.open(logFile())) {
            assertEquals(1, log.size());
            LoggedEvent second = log.append(Event.of("git", "commit", T0.plusSeconds(60)));
            assertEquals(firstHash, second.previousHash());
            assertEquals(1, second.index());
        }

        VerifyResult result = EventLog.verify(logFile());
        assertTrue(result.ok(), result.message());
        assertEquals(2, result.entries());
    }

    @Test
    void detectsAnEditedEntry() throws IOException {
        try (EventLog log = EventLog.open(logFile())) {
            log.append(Event.of("git", "commit", T0, Map.of("repo", "music")));
            log.append(Event.of("git", "commit", T0.plusSeconds(60), Map.of("repo", "music")));
        }

        List<String> lines = Files.readAllLines(logFile(), StandardCharsets.UTF_8);
        lines.set(0, lines.getFirst().replace("music", "evil!"));
        Files.write(logFile(), lines, StandardCharsets.UTF_8);

        VerifyResult result = EventLog.verify(logFile());
        assertFalse(result.ok());
        assertEquals(0, result.badIndex());
        assertThrows(IllegalStateException.class, () -> EventLog.open(logFile()));
    }

    @Test
    void detectsReordering() throws IOException {
        try (EventLog log = EventLog.open(logFile())) {
            log.append(Event.of("git", "commit", T0));
            log.append(Event.of("git", "commit", T0.plusSeconds(60)));
            log.append(Event.of("git", "commit", T0.plusSeconds(120)));
        }

        List<String> lines = Files.readAllLines(logFile(), StandardCharsets.UTF_8);
        String first = lines.get(0);
        lines.set(0, lines.get(1));
        lines.set(1, first);
        Files.write(logFile(), lines, StandardCharsets.UTF_8);

        assertFalse(EventLog.verify(logFile()).ok());
    }

    @Test
    void readsEventsBackForRendering() throws IOException {
        try (EventLog log = EventLog.open(logFile())) {
            log.append(Event.of("weather", "sample", T0, Map.of("cloud_cover", 0.42)));
        }
        List<Event> events = EventLog.readEvents(logFile());
        assertEquals(1, events.size());
        assertEquals(0.42, events.getFirst().payload().get("cloud_cover"));
    }

    @Test
    void readEventsOnAMissingLogIsEmpty() throws IOException {
        assertTrue(EventLog.readEvents(tempDir.resolve("nope.jsonl")).isEmpty());
    }
}
