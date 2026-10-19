package dev.chronotone.connectors;

import dev.chronotone.core.connector.Connector;
import dev.chronotone.core.log.Event;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reads a local git repository's history — the most honest signal of a day's work, and it never
 * leaves the machine.
 *
 * <p>Uses one {@code git log} call with {@code --numstat} so that lines added and removed per commit
 * become the payload. No JGit dependency: the git binary is already there.
 */
public final class GitConnector implements Connector {

    private static final String FIELD = "\u001f";
    private static final String COMMIT = "CT" + FIELD;

    private final String id;
    private final Path repo;
    private final String eventSource;
    private final String eventType;

    public GitConnector(ConnectorConfig config) {
        this.id = config.id();
        this.repo = config.path("repo", Path.of("."));
        this.eventSource = config.option("event_source", "git");
        this.eventType = config.option("event_type", "commit");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String describe() {
        return id + " (git history of " + repo + ")";
    }

    @Override
    public List<Event> fetch(Instant from, Instant to) {
        List<String> command = List.of("git", "-C", repo.toString(), "log",
                "--since=" + Event.formatTimestamp(from),
                "--until=" + Event.formatTimestamp(to),
                "--date=iso-strict",
                "--no-renames",
                "--pretty=format:" + COMMIT + "%H" + FIELD + "%cI" + FIELD + "%an" + FIELD + "%s",
                "--numstat");

        String output = run(command);
        return parse(output, eventSource, eventType);
    }

    private String run(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        try {
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                if (stderr.contains("does not have any commits")) {
                    return ""; // a brand new repository has nothing to say, and that is not an error
                }
                throw new ConnectorException("git failed for '" + repo + "': " + stderr.strip());
            }
            return stdout;
        } catch (IOException e) {
            throw new ConnectorException("cannot read git history of '" + repo
                    + "' (is it a repository? is git on PATH?)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("interrupted while reading git history", e);
        }
    }

    /** Parses the {@code --pretty}/{@code --numstat} output into one event per commit. */
    static List<Event> parse(String output, String eventSource, String eventType) {
        List<Event> events = new ArrayList<>();
        String hash = null;
        String committedAt = null;
        String author = null;
        String subject = null;
        long added = 0;
        long deleted = 0;
        long files = 0;

        for (String line : output.split("\n")) {
            if (line.startsWith(COMMIT)) {
                if (hash != null) {
                    events.add(toEvent(eventSource, eventType, hash, committedAt, author, subject, added, deleted, files));
                }
                String[] parts = line.substring(COMMIT.length()).split(FIELD, -1);
                hash = parts[0];
                committedAt = parts.length > 1 ? parts[1] : null;
                author = parts.length > 2 ? parts[2] : "";
                subject = parts.length > 3 ? parts[3] : "";
                added = 0;
                deleted = 0;
                files = 0;
            } else if (hash != null && line.indexOf('\t') > 0) {
                String[] parts = line.split("\t");
                added += parseCount(parts[0]);
                deleted += parseCount(parts.length > 1 ? parts[1] : "0");
                files++;
            }
        }
        if (hash != null) {
            events.add(toEvent(eventSource, eventType, hash, committedAt, author, subject, added, deleted, files));
        }
        events.sort(Comparator.comparing(Event::timestamp));
        return events;
    }

    private static Event toEvent(String source, String type, String hash, String committedAt,
                                 String author, String subject, long added, long deleted, long files) {
        return Event.of(source, type, java.time.Instant.parse(committedAt), Map.of(
                "commit", hash,
                "author", author == null ? "" : author,
                "subject", subject == null ? "" : subject,
                "lines_added", added,
                "lines_deleted", deleted,
                "lines_changed", added + deleted,
                "files", files));
    }

    /** {@code --numstat} prints {@code -} for binary files. */
    private static long parseCount(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
