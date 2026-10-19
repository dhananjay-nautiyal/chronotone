package dev.chronotone.connectors;

import dev.chronotone.core.log.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileConnectorTest {

    private static final Instant FROM = Instant.parse("2026-10-19T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-20T00:00:00Z");

    @TempDir
    Path tempDir;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void readsCsvRowsAsEvents() throws IOException {
        Path csv = write("steps.csv", """
                start_time,steps,distance_m
                2026-10-19T07:12:00Z,412,530
                2026-10-19T09:30:00Z,286,340
                """);
        ConnectorConfig config = new ConnectorConfig("steps", "file", Map.of(
                "path", csv.toString(),
                "time_column", "start_time",
                "event_source", "health",
                "event_type", "steps",
                "payload", "steps,distance_m"));

        List<Event> events = new FileConnector(config).fetch(FROM, TO);

        assertEquals(2, events.size());
        assertEquals("health", events.getFirst().source());
        assertEquals("steps", events.getFirst().type());
        assertEquals(Instant.parse("2026-10-19T07:12:00Z"), events.getFirst().timestamp());
        assertEquals(412L, events.getFirst().payload().get("steps"));
        assertEquals(530L, events.getFirst().payload().get("distance_m"));
    }

    @Test
    void onlyTheConfiguredPayloadColumnsBecomeTheEvent() throws IOException {
        Path csv = write("rows.csv", """
                start_time,steps,secret_note
                2026-10-19T07:12:00Z,412,private
                """);
        ConnectorConfig config = new ConnectorConfig("steps", "file", Map.of(
                "path", csv.toString(),
                "time_column", "start_time",
                "payload", "steps"));

        Event event = new FileConnector(config).fetch(FROM, TO).getFirst();
        assertEquals(Map.of("steps", 412L), event.payload());
    }

    @Test
    void withoutAPayloadListEveryOtherColumnIsTaken() throws IOException {
        Path csv = write("all.csv", """
                start_time,steps,note
                2026-10-19T07:12:00Z,412,hello
                """);
        ConnectorConfig config = new ConnectorConfig("steps", "file", Map.of(
                "path", csv.toString(),
                "time_column", "start_time"));

        Event event = new FileConnector(config).fetch(FROM, TO).getFirst();
        assertEquals(412L, event.payload().get("steps"));
        assertEquals("hello", event.payload().get("note"));
    }

    @Test
    void theWindowIsAppliedToTheRows() throws IOException {
        Path csv = write("spread.csv", """
                start_time,steps
                2026-10-18T07:00:00Z,10
                2026-10-19T07:00:00Z,20
                2026-10-20T07:00:00Z,30
                """);
        ConnectorConfig config = new ConnectorConfig("steps", "file", Map.of(
                "path", csv.toString(),
                "time_column", "start_time"));

        List<Event> events = new FileConnector(config).fetch(FROM, TO);
        assertEquals(1, events.size());
        assertEquals(20L, events.getFirst().payload().get("steps"));
    }

    @Test
    void readsJsonlRowsToo() throws IOException {
        Path jsonl = write("plays.jsonl", """
                {"played_at":"2026-10-19T21:05:00Z","artist":"Tycho","track":"Awake","duration_s":263}
                {"played_at":"2026-10-19T21:12:00Z","artist":"Aphex Twin","track":"Xtal","duration_s":295}
                """);
        ConnectorConfig config = new ConnectorConfig("scrobbles", "file", Map.of(
                "path", jsonl.toString(),
                "format", "jsonl",
                "time_column", "played_at",
                "event_source", "music",
                "event_type", "scrobble"));

        List<Event> events = new FileConnector(config).fetch(FROM, TO);
        assertEquals(2, events.size());
        assertEquals("Tycho", events.getFirst().payload().get("artist"));
        assertEquals(263L, events.getFirst().payload().get("duration_s"));
    }

    @Test
    void quotedFieldsSurviveCommasInsideThem() throws IOException {
        Path csv = write("quoted.csv", """
                start_time,track,artist
                2026-10-19T21:05:00Z,"Two Thousand and Seventeen, Pt. 2","Four Tet"
                """);
        ConnectorConfig config = new ConnectorConfig("plays", "file", Map.of(
                "path", csv.toString(),
                "time_column", "start_time"));

        Event event = new FileConnector(config).fetch(FROM, TO).getFirst();
        assertEquals("Two Thousand and Seventeen, Pt. 2", event.payload().get("track"));
        assertEquals("Four Tet", event.payload().get("artist"));
    }

    @Test
    void supportsEpochTimestamps() throws IOException {
        // 1792393920 == 2026-10-19T07:12:00Z
        Path csv = write("epoch.csv", """
                ts,steps
                1792393920,412
                """);
        ConnectorConfig config = new ConnectorConfig("steps", "file", Map.of(
                "path", csv.toString(),
                "time_column", "ts",
                "time_format", "epoch_s"));

        List<Event> events = new FileConnector(config).fetch(FROM, TO);
        assertEquals(1, events.size());
        assertEquals(Instant.parse("2026-10-19T07:12:00Z"), events.getFirst().timestamp());
    }

    @Test
    void aMissingExportFailsWithAClearMessage() {
        ConnectorConfig config = new ConnectorConfig("steps", "file", Map.of(
                "path", tempDir.resolve("absent.csv").toString(),
                "time_column", "start_time"));
        ConnectorException error = assertThrows(ConnectorException.class, () -> new FileConnector(config).fetch(FROM, TO));
        assertTrue(error.getMessage().contains("absent.csv"));
    }

    @Test
    void unparseableTimestampsAreRejected() throws IOException {
        Path csv = write("broken.csv", """
                start_time,steps
                midday,412
                """);
        ConnectorConfig config = new ConnectorConfig("steps", "file", Map.of(
                "path", csv.toString(),
                "time_column", "start_time"));
        assertThrows(ConnectorConfigException.class, () -> new FileConnector(config).fetch(FROM, TO));
    }

    @Test
    void detectsJsonlFromTheExtension() throws IOException {
        Path jsonl = write("auto.jsonl", """
                {"played_at":"2026-10-19T21:05:00Z","track":"Awake"}
                """);
        ConnectorConfig config = new ConnectorConfig("plays", "file", Map.of(
                "path", jsonl.toString(),
                "time_column", "played_at"));
        assertEquals(1, new FileConnector(config).fetch(FROM, TO).size());
    }

    @Test
    void theExampleExportIsReadable() {
        ConnectorConfig config = new ConnectorConfig("steps", "file", Map.of(
                "path", Path.of("..", "examples", "steps.csv").toString(),
                "time_column", "start_time",
                "event_source", "health",
                "event_type", "steps"));
        List<Event> events = new FileConnector(config).fetch(FROM, TO);
        assertEquals(10, events.size());
        assertTrue(events.getFirst().timestamp().isBefore(events.getLast().timestamp()));
        assertTrue(events.getFirst().timestamp().isAfter(FROM.minus(1, ChronoUnit.DAYS)));
    }
}
