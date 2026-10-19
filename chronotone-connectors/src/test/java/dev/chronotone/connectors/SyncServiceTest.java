package dev.chronotone.connectors;

import dev.chronotone.core.log.EventLog;
import dev.chronotone.core.log.VerifyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two guarantees that make sync safe to run on a schedule: incremental and idempotent. */
class SyncServiceTest {

    private static final Instant FROM = Instant.parse("2026-10-19T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-20T00:00:00Z");
    private static final String CSV = """
            start_time,steps
            2026-10-19T07:12:00Z,412
            2026-10-19T09:30:00Z,286
            2026-10-19T17:40:00Z,1540
            """;

    @TempDir
    Path tempDir;

    private Path csv;
    private Path log;
    private Path watermarks;

    private void prepare() throws IOException {
        csv = tempDir.resolve("steps.csv");
        Files.writeString(csv, CSV);
        log = tempDir.resolve("events.jsonl");
        watermarks = tempDir.resolve("events.jsonl.watermarks.json");
    }

    private ConnectorConfig connector() {
        return new ConnectorConfig("steps", "file", Map.of(
                "path", csv.toString(),
                "time_column", "start_time",
                "event_source", "health",
                "event_type", "steps"));
    }

    @Test
    void appendsEventsAndAdvancesTheWatermark() throws IOException {
        prepare();
        SyncResult result = new SyncService(log, watermarks).sync(List.of(connector()), FROM, TO, false);

        assertEquals(3, result.appended());
        assertEquals(3, result.logSize());
        assertEquals(3, result.connectors().getFirst().fetched());
        assertFalse(result.dryRun());
        assertTrue(Files.exists(watermarks));
        assertEquals(TO, Watermarks.load(watermarks).get("steps").orElseThrow());
        assertTrue(EventLog.verify(log).ok());
    }

    @Test
    void runningTwiceAppendsNothingTheSecondTime() throws IOException {
        prepare();
        SyncService service = new SyncService(log, watermarks);

        assertEquals(3, service.sync(List.of(connector()), FROM, TO, false).appended());
        SyncResult second = service.sync(List.of(connector()), FROM, TO, false);

        // The watermark moved past the window, so nothing is even fetched.
        assertEquals(0, second.appended());
        assertEquals(0, second.connectors().getFirst().fetched());
        assertEquals(3, second.logSize());
    }

    @Test
    void losingTheWatermarkDoesNotDuplicateHistory() throws IOException {
        prepare();
        new SyncService(log, watermarks).sync(List.of(connector()), FROM, TO, false);

        // A fresh watermark file simulates a re-clone, a wiped cache, or an overlapping backfill.
        Path freshWatermarks = tempDir.resolve("other.json");
        SyncResult again = new SyncService(log, freshWatermarks).sync(List.of(connector()), FROM, TO, false);

        assertEquals(0, again.appended());
        assertEquals(3, again.connectors().getFirst().duplicates());
        assertEquals(3, again.logSize());
        assertEquals(3, EventLog.readEvents(log).size());
    }

    @Test
    void aDryRunWritesNothingAtAll() throws IOException {
        prepare();
        SyncResult result = new SyncService(log, watermarks).sync(List.of(connector()), FROM, TO, true);

        assertEquals(3, result.appended());
        assertTrue(result.dryRun());
        assertFalse(Files.exists(log), "dry run must not create the log");
        assertFalse(Files.exists(watermarks), "dry run must not move the watermark");
        assertTrue(result.summary().contains("dry run"));
    }

    @Test
    void aResumedSyncOnlyReadsWhatIsNew() throws IOException {
        prepare();
        SyncService service = new SyncService(log, watermarks);
        Instant midday = Instant.parse("2026-10-19T12:00:00Z");
        service.sync(List.of(connector()), FROM, midday, false);

        SyncResult rest = service.sync(List.of(connector()), FROM, TO, false);

        assertEquals(1, rest.appended());          // the 17:40 row only
        assertEquals(1, rest.connectors().getFirst().fetched());
        assertEquals(3, rest.logSize());           // 07:12 and 09:30 came in with the first sync
    }

    @Test
    void severalConnectorsLandInOneVerifiableLog() throws IOException {
        prepare();
        Path scrobbles = tempDir.resolve("plays.csv");
        Files.writeString(scrobbles, """
                played_at,artist,track
                2026-10-19T21:05:00Z,Tycho,Awake
                2026-10-19T21:12:00Z,Aphex Twin,Xtal
                """);
        ConnectorConfig music = new ConnectorConfig("scrobbles", "file", Map.of(
                "path", scrobbles.toString(),
                "time_column", "played_at",
                "event_source", "music",
                "event_type", "scrobble"));

        SyncResult result = new SyncService(log, watermarks).sync(List.of(connector(), music), FROM, TO, false);

        assertEquals(5, result.appended());
        assertEquals(2, result.connectors().size());
        VerifyResult verified = EventLog.verify(log);
        assertTrue(verified.ok(), verified.message());
        assertEquals(5, verified.entries());
    }

    @Test
    void theResultReportsWhereWatermarksLive() throws IOException {
        prepare();
        SyncResult result = new SyncService(log, watermarks).sync(List.of(connector()), FROM, TO, true);
        assertEquals(watermarks, result.watermarkPath());
    }
}
