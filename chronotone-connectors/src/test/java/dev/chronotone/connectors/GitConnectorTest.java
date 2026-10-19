package dev.chronotone.connectors;

import dev.chronotone.core.log.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitConnectorTest {

    @TempDir
    Path tempDir;

    @Test
    void readsTheHistoryOfALocalRepository() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        run(repo, "init", "-q");
        run(repo, "config", "user.email", "chronotone@example.com");
        run(repo, "config", "user.name", "Chronotone Test");

        Files.writeString(repo.resolve("note.txt"), "one\n");
        run(repo, "add", "-A");
        run(repo, "commit", "-q", "-m", "first commit");

        Files.writeString(repo.resolve("note.txt"), "one\ntwo\nthree\n");
        run(repo, "add", "-A");
        run(repo, "commit", "-q", "-m", "second commit");

        ConnectorConfig config = new ConnectorConfig("git", "git", Map.of("repo", repo.toString()));
        List<Event> events = new GitConnector(config).fetch(
                Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(1, ChronoUnit.HOURS));

        assertEquals(2, events.size());
        Event first = events.getFirst();
        assertEquals("git", first.source());
        assertEquals("commit", first.type());
        assertEquals(1L, first.payload().get("files"));
        assertTrue(((Number) first.payload().get("lines_changed")).longValue() > 0,
                "expected lines_changed > 0 but was " + first.payload().get("lines_changed"));
        assertTrue(events.stream().anyMatch(event -> "second commit".equals(event.payload().get("subject"))));
    }

    @Test
    void anEmptyRepositoryIsEmptyNotBroken() throws Exception {
        Path repo = tempDir.resolve("fresh");
        Files.createDirectories(repo);
        run(repo, "init", "-q");

        ConnectorConfig config = new ConnectorConfig("git", "git", Map.of("repo", repo.toString()));
        List<Event> events = new GitConnector(config).fetch(Instant.now().minus(1, ChronoUnit.HOURS), Instant.now());
        assertTrue(events.isEmpty());
    }

    @Test
    void theWindowExcludesOlderCommits() throws Exception {
        Path repo = tempDir.resolve("old");
        Files.createDirectories(repo);
        run(repo, "init", "-q");
        run(repo, "config", "user.email", "chronotone@example.com");
        run(repo, "config", "user.name", "Chronotone Test");
        Files.writeString(repo.resolve("note.txt"), "old\n");
        run(repo, "add", "-A");
        run(repo, "commit", "-q", "-m", "way back");

        ConnectorConfig config = new ConnectorConfig("git", "git", Map.of("repo", repo.toString()));
        // A window that ended before the commit was made.
        List<Event> events = new GitConnector(config).fetch(
                Instant.now().minus(30, ChronoUnit.DAYS), Instant.now().minus(20, ChronoUnit.DAYS));
        assertTrue(events.isEmpty());
    }

    @Test
    void aMissingRepositoryFailsWithAClearMessage() {
        ConnectorConfig config = new ConnectorConfig("git", "git", Map.of("repo", tempDir.resolve("nope").toString()));
        ConnectorException error = org.junit.jupiter.api.Assertions.assertThrows(ConnectorException.class,
                () -> new GitConnector(config).fetch(Instant.now().minus(1, ChronoUnit.HOURS), Instant.now()));
        assertTrue(error.getMessage().contains("nope"));
    }

    private static void run(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        assertEquals(0, exit, () -> "git " + String.join(" ", args) + " failed: " + output);
    }
}
