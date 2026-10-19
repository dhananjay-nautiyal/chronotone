package dev.chronotone.core.log;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only, tamper-evident event log.
 *
 * <p>Each line is canonical JSON: {@code {"event":{...},"hash":"…","prev":"…"}} where
 * {@code hash = sha256(prev + "\n" + canonicalEvent)}. The head hash is the log's commitment:
 * publish it and anyone holding the log can prove it was not edited, because changing any earlier
 * byte breaks every hash after it.
 *
 * <p>The file is plain text on purpose — greppable, diffable, and easy to ship to a phone.
 */
public final class EventLog implements Closeable {

    private final Path path;
    private final List<LoggedEvent> entries = new ArrayList<>();
    private String head = Hashes.GENESIS;
    private BufferedWriter writer;
    private boolean closed;

    private EventLog(Path path) {
        this.path = path;
    }

    /** Opens (creating if needed) and verifies the log. Throws if the chain is broken. */
    public static EventLog open(Path path) throws IOException {
        EventLog log = new EventLog(path);
        log.load();
        return log;
    }

    /** Convenience for read-only callers such as the renderer. Returns an empty list if absent. */
    public static List<Event> readEvents(Path path) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        try (EventLog log = open(path)) {
            return log.events();
        }
    }

    /**
     * The verified entries, chain links included. Returns an empty list if the log is absent.
     * Commitment and proof generation need the entry hashes, not just the events.
     */
    public static List<LoggedEvent> readEntries(Path path) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        try (EventLog log = open(path)) {
            return log.entries();
        }
    }

    private void load() throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(path)) {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            long lineNumber = 0;
            for (String line : lines) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                LoggedEvent entry = parseEntry(line, lineNumber);
                if (!entry.previousHash().equals(head)) {
                    throw new IllegalStateException("event log line " + lineNumber
                            + " does not link to the previous entry");
                }
                String expected = Hashes.chain(entry.previousHash(), entry.event().canonical());
                if (!expected.equals(entry.hash())) {
                    throw new IllegalStateException("event log line " + lineNumber
                            + " has been modified since it was written");
                }
                entries.add(new LoggedEvent(entries.size(), entry.previousHash(), entry.hash(), entry.event()));
                head = entry.hash();
            }
        }
        writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Appends an event and returns its verified entry. Flushes on every call: a log must survive a crash. */
    public LoggedEvent append(Event event) throws IOException {
        ensureOpen();
        String canonical = event.canonical();
        String hash = Hashes.chain(head, canonical);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", event.canonicalMap());
        line.put("hash", hash);
        line.put("prev", head);
        writer.write(CanonicalJson.write(line));
        writer.write("\n"); // never platform line separators: the bytes are part of the hash chain
        writer.flush();

        LoggedEvent entry = new LoggedEvent(entries.size(), head, hash, event);
        entries.add(entry);
        head = hash;
        return entry;
    }

    /** Streaming verification: no state kept, safe to run against a large log. */
    public static VerifyResult verify(Path path) throws IOException {
        if (!Files.exists(path)) {
            return VerifyResult.failed(0, -1, "no log at " + path);
        }
        String head = Hashes.GENESIS;
        long count = 0;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                LoggedEvent entry;
                try {
                    entry = parseEntry(line, lineNumber);
                } catch (IllegalStateException e) {
                    return VerifyResult.failed(count, lineNumber - 1, e.getMessage());
                }
                if (!entry.previousHash().equals(head)) {
                    return VerifyResult.failed(count, lineNumber - 1,
                            "line " + lineNumber + " does not link to the previous entry");
                }
                String expected = Hashes.chain(entry.previousHash(), entry.event().canonical());
                if (!expected.equals(entry.hash())) {
                    return VerifyResult.failed(count, lineNumber - 1,
                            "line " + lineNumber + " has been modified since it was written");
                }
                head = entry.hash();
                count++;
            }
        }
        return VerifyResult.ok(count, head);
    }

    /** The current commitment to everything appended so far. */
    public String commitment() {
        return head;
    }

    public long size() {
        return entries.size();
    }

    public List<LoggedEvent> entries() {
        return Collections.unmodifiableList(entries);
    }

    public List<Event> events() {
        return entries.stream().map(LoggedEvent::event).toList();
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (writer != null) {
            writer.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("event log is closed: " + path);
        }
    }

    private static LoggedEvent parseEntry(String line, long lineNumber) {
        Map<String, Object> root;
        try {
            root = Json.parseObject(line);
        } catch (RuntimeException e) {
            throw new IllegalStateException("event log line " + lineNumber + " is not valid JSON: " + e.getMessage(), e);
        }
        Object hashNode = root.get("hash");
        Object prevNode = root.get("prev");
        Object eventNode = root.get("event");
        if (!(hashNode instanceof String hash)
                || !(prevNode instanceof String prev)
                || !(eventNode instanceof Map<?, ?> eventMap)) {
            throw new IllegalStateException("event log line " + lineNumber + " is missing hash/prev/event");
        }
        Event event;
        try {
            Map<String, Object> eventFields = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : eventMap.entrySet()) {
                eventFields.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            event = Event.fromMap(eventFields);
        } catch (RuntimeException e) {
            throw new IllegalStateException("event log line " + lineNumber + " has an unreadable event: " + e.getMessage(), e);
        }
        return new LoggedEvent(lineNumber - 1, prev, hash, event);
    }
}
