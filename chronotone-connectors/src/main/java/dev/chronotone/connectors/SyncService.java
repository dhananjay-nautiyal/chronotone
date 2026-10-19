package dev.chronotone.connectors;

import dev.chronotone.core.connector.Connector;
import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.EventLog;
import dev.chronotone.core.log.Hashes;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pulls events from every configured connector into the log.
 *
 * <p>Two rules keep this safe to re-run:
 * <ol>
 *   <li>a per-connector watermark means the next sync only asks for new time;</li>
 *   <li>an event's identity is {@code sha256(connectorId + canonical event)}, so overlapping windows
 *       can never append the same event twice.</li>
 * </ol>
 * A dry run reports what would be written and touches nothing.
 */
public final class SyncService {

    private final Path logPath;
    private final Path watermarkPath;

    public SyncService(Path logPath) {
        this(logPath, watermarksPathFor(logPath));
    }

    public SyncService(Path logPath, Path watermarkPath) {
        this.logPath = logPath;
        this.watermarkPath = watermarkPath;
    }

    public static Path watermarksPathFor(Path logPath) {
        return logPath.resolveSibling(logPath.getFileName() + ".watermarks.json");
    }

    public SyncResult sync(List<ConnectorConfig> configs, Instant from, Instant to, boolean dryRun)
            throws IOException {
        Watermarks watermarks = Watermarks.load(watermarkPath);
        List<Event> existing = EventLog.readEvents(logPath);
        List<ConnectorReport> reports = new ArrayList<>(configs.size());
        List<Event> pending = new ArrayList<>();
        Map<String, Instant> advanced = new LinkedHashMap<>();

        for (ConnectorConfig config : configs) {
            Instant start = watermarks.get(config.id())
                    .map(mark -> mark.plusMillis(1))
                    .orElse(from);
            if (start.isBefore(from)) {
                start = from;
            }

            int fetched = 0;
            int appended = 0;
            int duplicates = 0;
            if (to.isAfter(start)) {
                Set<String> known = identities(existing, config.id());
                List<Event> events;
                try (Connector connector = ConnectorFactory.create(config)) {
                    events = connector.fetch(start, to);
                }
                fetched = events.size();
                for (Event event : events) {
                    if (known.add(identity(config.id(), event))) {
                        pending.add(event);
                        appended++;
                    } else {
                        duplicates++;
                    }
                }
                advanced.put(config.id(), to);
            } else {
                watermarks.get(config.id()).ifPresent(mark -> advanced.put(config.id(), mark));
            }
            reports.add(new ConnectorReport(config.id(), config.type(), start, to, fetched, appended, duplicates));
        }

        pending.sort(Comparator.comparing(Event::timestamp));
        long logSize = existing.size();
        String commitment = null;
        if (!dryRun) {
            // Open (creating if needed) so that even an empty sync commits a genesis state.
            try (EventLog log = EventLog.open(logPath)) {
                for (Event event : pending) {
                    log.append(event);
                }
                logSize = log.size();
                commitment = log.commitment();
            }
            watermarks.putAll(advanced);
            watermarks.save();
        }
        return new SyncResult(dryRun, logSize, pending.size(), commitment,
                List.copyOf(reports), watermarkPath);
    }

    private static Set<String> identities(List<Event> events, String connectorId) {
        Set<String> identities = new HashSet<>(Math.max(16, events.size() * 2));
        for (Event event : events) {
            identities.add(identity(connectorId, event));
        }
        return identities;
    }

    /** Content identity of one event for one connector: the deduplication key. */
    static String identity(String connectorId, Event event) {
        return Hashes.sha256Hex(connectorId + "\n" + event.canonical());
    }
}
