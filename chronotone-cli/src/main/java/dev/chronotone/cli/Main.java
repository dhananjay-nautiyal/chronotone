package dev.chronotone.cli;

import dev.chronotone.connectors.ConnectorConfig;
import dev.chronotone.connectors.ConnectorConfigFile;
import dev.chronotone.connectors.SyncResult;
import dev.chronotone.connectors.SyncService;
import dev.chronotone.core.connector.SyntheticConnector;
import dev.chronotone.core.log.CanonicalJson;
import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.EventLog;
import dev.chronotone.core.log.Hashes;
import dev.chronotone.core.log.LoggedEvent;
import dev.chronotone.core.log.VerifyResult;
import dev.chronotone.core.merkle.Commitment;
import dev.chronotone.core.merkle.MerkleProof;
import dev.chronotone.core.merkle.MerkleTree;
import dev.chronotone.dsl.DefaultMapping;
import dev.chronotone.dsl.MappingParser;
import dev.chronotone.dsl.MappingSpec;
import dev.chronotone.dsl.ParamSpec;
import dev.chronotone.render.Album;
import dev.chronotone.render.Chronotone;
import dev.chronotone.render.RenderResult;
import dev.chronotone.render.Station;
import dev.chronotone.render.WavRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * {@code chronotone init|demo|sync|render|station|commit|prove|check|verify}.
 *
 * <p>No wall-clock values are written into any artifact, so two renders of the same inputs can be
 * compared byte for byte — and so can two commitments of the same slice. That is exactly what CI does.
 *
 * <p>{@code station} is the exception that proves it: it reads the clock, but only to decide <em>which
 * window</em> to render. Pin {@code --now} and a station frame is as deterministic as any other render.
 */
public final class Main {

    private static final String VERSION = "0.1.0";
    private static final String DEFAULT_LOG = ".chronotone/demo.jsonl";

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        try {
            Map<String, String> options = options(args, 1);
            switch (args[0]) {
                case "init" -> init(options);
                case "demo" -> demo(options);
                case "sync" -> sync(options);
                case "render" -> render(options);
                case "album" -> album(options);
                case "station" -> station(options);
                case "commit" -> commit(options);
                case "prove" -> prove(options);
                case "check" -> check(options);
                case "verify" -> verify(options);
                case "help", "--help", "-h" -> usage();
                default -> {
                    System.err.println("chronotone: unknown command '" + args[0] + "'");
                    usage();
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            System.err.println("chronotone: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Creates an empty log and prints its commitment (the genesis hash). */
    private static void init(Map<String, String> options) throws IOException {
        Path log = Path.of(options.getOrDefault("log", DEFAULT_LOG));
        try (EventLog eventLog = EventLog.open(log)) {
            System.out.println("log      " + log.toAbsolutePath());
            System.out.println("entries  " + eventLog.size());
            System.out.println("commit   " + eventLog.commitment());
        }
    }

    /** Writes a deterministic synthetic day. Starts clean unless {@code --append}. */
    private static void demo(Map<String, String> options) throws IOException {
        Path log = Path.of(options.getOrDefault("log", DEFAULT_LOG));
        LocalDate day = LocalDate.parse(options.getOrDefault("day", LocalDate.now(ZoneOffset.UTC).toString()));
        long seed = Long.parseLong(options.getOrDefault("seed", "42"));
        if (!Boolean.parseBoolean(options.getOrDefault("append", "false"))) {
            Files.deleteIfExists(log);
        }

        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Event> events = new SyntheticConnector(seed).fetch(from, to);

        try (EventLog eventLog = EventLog.open(log)) {
            for (Event event : events) {
                eventLog.append(event);
            }
            System.out.println("day        " + day + " (seed " + seed + ")");
            System.out.println("wrote      " + eventLog.size() + " events -> " + log);
            System.out.println("commitment " + eventLog.commitment());
        }
        Map<String, Long> perSource = events.stream()
                .collect(Collectors.groupingBy(Event::source, TreeMap::new, Collectors.counting()));
        System.out.println("sources    " + perSource);
    }

    /**
     * Pulls events from every configured connector into the log. Incremental via per-connector
     * watermarks and idempotent via content identity, so re-running never duplicates history.
     */
    private static void sync(Map<String, String> options) throws IOException {
        Path log = Path.of(options.getOrDefault("log", DEFAULT_LOG));
        Path config = Path.of(options.getOrDefault("config", ".chronotone/connectors.yaml"));
        boolean dryRun = Boolean.parseBoolean(options.getOrDefault("dry-run", "false"));
        Instant to = options.containsKey("to") ? Instant.parse(options.get("to")) : Instant.now();
        Instant from = options.containsKey("from") ? Instant.parse(options.get("from")) : to.minus(Duration.ofDays(7));
        if (from.isAfter(to)) {
            throw new IllegalStateException("--from must be before --to");
        }
        if (!Files.exists(config)) {
            throw new IllegalStateException("no connector config at " + config
                    + " — copy examples/connectors.yaml and point it at your own exports");
        }

        List<ConnectorConfig> configs = ConnectorConfigFile.read(config);
        if (configs.isEmpty()) {
            throw new IllegalStateException("no connectors configured in " + config);
        }
        System.out.println("config     " + config);
        for (ConnectorConfig connector : configs) {
            System.out.println("  " + connector.id() + " (" + connector.type() + ")");
        }
        System.out.println("window     " + Event.formatTimestamp(from) + " -> " + Event.formatTimestamp(to));
        if (dryRun) {
            System.out.println("mode       dry run");
        }

        SyncResult result = new SyncService(log).sync(configs, from, to, dryRun);
        System.out.println(result.summary());
        System.out.println("watermarks " + result.watermarkPath());
    }

    /** Renders a window of the log to MIDI + WAV + a hash manifest. */
    private static void render(Map<String, String> options) throws IOException {
        Path log = Path.of(options.getOrDefault("log", DEFAULT_LOG));
        Path out = Path.of(options.getOrDefault("out", "out"));
        long seed = Long.parseLong(options.getOrDefault("seed", "42"));
        LocalTime startTime = LocalTime.parse(options.getOrDefault("from", "07:00"));
        int minutes = Integer.parseInt(options.getOrDefault("minutes", "30"));

        List<Event> events = EventLog.readEvents(log);
        if (events.isEmpty()) {
            throw new IllegalStateException("no events in " + log + " — run `chronotone demo` first");
        }
        LocalDate day = options.containsKey("day")
                ? LocalDate.parse(options.get("day"))
                : events.getFirst().timestamp().atZone(ZoneOffset.UTC).toLocalDate();
        Instant from = day.atTime(startTime).toInstant(ZoneOffset.UTC);
        Instant to = from.plus(minutes, ChronoUnit.MINUTES);

        MappingSpec mapping = new MappingParser().parse(mappingYaml(options));
        RenderResult result = Chronotone.render(mapping, events, from, to, seed);

        Files.createDirectories(out);
        Files.write(out.resolve("chronotone.mid"), result.midi());
        Files.write(out.resolve("chronotone.wav"), result.wav());
        Files.writeString(out.resolve("chronotone.json"),
                manifest(result, mapping, day, from, to, seed, events.size()));

        System.out.println("window     " + Event.formatTimestamp(from) + " -> " + Event.formatTimestamp(to));
        System.out.println("mapping    " + mapping.version() + " (" + mapping.paramNames() + ")");
        for (ParamSpec param : mapping.params().values()) {
            System.out.println("  " + param.describe());
        }
        System.out.println("timeline   " + result.description());
        System.out.println("render     " + result.summary());
        System.out.println("fingerprint " + result.timelineFingerprint());
        System.out.println("midi       " + result.midiHash() + "  (" + result.midiBytes() + " bytes)");
        System.out.println("wav        " + result.wavHash() + "  (" + result.wavBytes() + " bytes)");
        System.out.println("wrote      " + out.resolve("chronotone.mid") + ", "
                + out.resolve("chronotone.wav") + ", " + out.resolve("chronotone.json"));
        System.out.println();
        System.out.println("render again with the same arguments: the hashes must not change.");
    }

    /**
     * A month as an album: one track per day, one gain for the whole thing.
     *
     * <p>{@code render} masters a single window; {@code station} masters a rolling one. Neither can
     * master a month, because both have to decide a gain before the material exists. This command
     * renders every day first, finds the album's peak, and only then scales — so the quiet days stay
     * quiet and the loud ones stay loud, which is the whole point of mastering an album instead of a
     * folder of clips.
     *
     * <p>{@code --stems} writes each day's five voices separately (pad, bass, lead, kick, hat), at the
     * same gain, so a stem set sums back to its track.
     */
    private static void album(Map<String, String> options) throws IOException {
        Path log = Path.of(options.getOrDefault("log", DEFAULT_LOG));
        Path out = Path.of(options.getOrDefault("out", "out/album"));
        long seed = Long.parseLong(options.getOrDefault("seed", "42"));
        LocalTime dayStart = LocalTime.parse(options.getOrDefault("start", "00:00"));
        Duration window = parseDuration(options.getOrDefault("window", "24h"));
        boolean stems = options.containsKey("stems");

        if (!Files.exists(log)) {
            throw new IllegalStateException("no log at " + log
                    + " — run `chronotone demo` or `chronotone sync` first");
        }
        List<LoggedEvent> ledger = EventLog.readEntries(log);
        if (ledger.isEmpty()) {
            throw new IllegalStateException("no entries in " + log + " — run `chronotone demo` first");
        }

        LocalDate from;
        int days;
        if (options.containsKey("month")) {
            YearMonth month = YearMonth.parse(options.get("month"));
            from = month.atDay(1);
            days = month.lengthOfMonth();
        } else if (options.containsKey("from")) {
            from = LocalDate.parse(options.get("from"));
            days = Integer.parseInt(options.getOrDefault("days", "1"));
        } else {
            LocalDate first = ledger.getFirst().event().timestamp()
                    .atZone(ZoneOffset.UTC).toLocalDate();
            YearMonth month = YearMonth.from(first);
            from = month.atDay(1);
            days = month.lengthOfMonth();
        }

        MappingSpec mapping = new MappingParser().parse(mappingYaml(options));
        Album.Result result = Album.render(mapping, ledger, from, days, dayStart, window, seed, out, stems);

        Files.createDirectories(out);
        Files.writeString(out.resolve("album.json"),
                CanonicalJson.write(result.toManifest(mapping, seed, from, days, dayStart, window)));

        System.out.println("album      " + Album.FORMAT);
        System.out.println("range      " + from + " .. " + from.plusDays(days - 1)
                + "  (" + days + " days requested)");
        System.out.println("window     " + dayStart + " + " + window + " per day");
        System.out.println("mapping    " + mapping.version() + " (" + mapping.paramNames() + ")");
        System.out.println("tracks     " + result.days() + " with sound"
                + (result.emptyDays().isEmpty() ? "" : ", " + result.emptyDays().size()
                + " empty and skipped"));
        System.out.println("mastering  one gain for the album, peak " + WavRenderer.PEAK);
        System.out.println("audio      " + clock(result.seconds()) + " at 44.1 kHz / 16-bit / mono");
        System.out.println();
        for (Album.Track track : result.tracks()) {
            System.out.printf("%s  %5d events  %5d bars  %6d notes  %s  %s  root %s%n",
                    track.day(), track.events(), track.slots(), track.notes(),
                    clock(track.seconds()), shortHash(track.wavHash()), shortHash(track.merkleRoot()));
        }
        System.out.println();
        if (stems) {
            System.out.println("stems      " + String.join(", ", Album.STEMS.keySet())
                    + " per track, same gain as the album");
        }
        System.out.println("wrote      " + out.resolve("album.json") + ", "
                + out.resolve("tracks") + " (" + result.days() + " files)"
                + (stems ? ", " + out.resolve("stems") : ""));
        System.out.println();
        System.out.println("render again with the same arguments: every track hash must not change.");
    }

    private static String clock(double seconds) {
        long millis = StrictMath.round(seconds * 1000.0);
        long h = millis / 3_600_000;
        long m = (millis / 60_000) % 60;
        long s = (millis / 1000) % 60;
        long ms = millis % 1000;
        return String.format(Locale.ROOT, "%d:%02d:%02d.%03d", h, m, s, ms);
    }

    private static String shortHash(String hash) {
        return hash.substring(0, 12);
    }

    /**
     * Live station: the trailing {@code --window} of the log, re-rendered as the clock moves.
     *
     * <p>A frame is the same bytes {@code render} would have produced for the same window, so going
     * live costs no fidelity. With {@code --follow} the window advances a slot at a time and only a
     * changed window is re-rendered; with {@code --stream} the blocks that closed since the last tick
     * go to stdout as raw 16-bit PCM, so a player can be piped to directly. Status lines go to stderr,
     * keeping stdout clean for that pipe.
     *
     * <p>Gain is normalised per window — the peak of the whole session is not knowable while it is
     * still running. Each frame is an honest master of its own window; the master of a whole day is
     * still {@code chronotone render}.
     */
    private static void station(Map<String, String> options) throws IOException, InterruptedException {
        Path log = Path.of(options.getOrDefault("log", DEFAULT_LOG));
        Path out = Path.of(options.getOrDefault("out", "out/station"));
        long seed = Long.parseLong(options.getOrDefault("seed", "42"));
        Duration window = parseDuration(options.getOrDefault("window", "30m"));
        Duration every = parseDuration(options.getOrDefault("every", "15s"));
        boolean follow = Boolean.parseBoolean(options.getOrDefault("follow", "false"));
        boolean stream = Boolean.parseBoolean(options.getOrDefault("stream", "false"));
        Instant pinned = options.containsKey("now") ? Instant.parse(options.get("now")) : null;
        int ticks = Integer.parseInt(options.getOrDefault("ticks", follow ? "-1" : "1"));

        MappingSpec mapping = new MappingParser().parse(mappingYaml(options));
        if (window.compareTo(mapping.grid()) < 0) {
            throw new IllegalStateException("--window (" + window + ") must be at least one grid step ("
                    + mapping.grid() + ")");
        }
        if (every.isZero() || every.isNegative()) {
            throw new IllegalStateException("--every must be positive");
        }
        if (!Files.exists(log)) {
            throw new IllegalStateException("no log at " + log
                    + " — run `chronotone demo` or `chronotone sync` first");
        }
        Files.createDirectories(out);

        System.err.println("station    window " + window + " | every " + every + " | seed " + seed
                + (follow ? " | following" : "") + (stream ? " | streaming raw PCM to stdout" : ""));
        System.err.println("log        " + log + "   out " + out.toAbsolutePath());

        Instant cursor = null;
        Instant lastTo = null;
        for (int tick = 0; ticks < 0 || tick < ticks; tick++) {
            Instant now = pinned == null ? Instant.now() : pinned.plusSeconds(tick * every.toSeconds());
            Instant to = Station.align(now, mapping.grid());

            if (!to.equals(lastTo)) {
                List<Event> events = EventLog.readEvents(log);
                if (events.isEmpty() && lastTo == null) {
                    System.err.println("warn       no events in " + log
                            + " — the station will play its quiet floor until you sync");
                }
                Station.Frame frame = Station.frame(mapping, events, now, window, seed);
                Files.write(out.resolve("chronotone.wav"), frame.wav());
                Files.writeString(out.resolve("station.json"),
                        stationManifest(frame, mapping, window, seed, events.size()));

                if (cursor == null) {
                    // Start listening from the slot that has just closed, not from the whole window.
                    cursor = Station.listenFrom(frame);
                }
                int streamed = 0;
                if (stream) {
                    for (int block : Station.blocksAfter(frame, cursor)) {
                        System.out.write(frame.pcmOfBlock(block));
                        cursor = frame.timeline().slot(block);
                        streamed++;
                    }
                    System.out.flush();
                }

                lastTo = to;
                System.err.println("tick       " + Event.formatTimestamp(to) + "  " + frame.summary()
                        + "  wav " + frame.wavHash().substring(0, 12)
                        + (stream ? "  streamed " + streamed + " slot(s)" : ""));
            }

            boolean more = ticks < 0 || tick + 1 < ticks;
            if (pinned == null && more) {
                Thread.sleep(every.toMillis());
            }
        }
        System.err.println("wrote      " + out.resolve("chronotone.wav") + ", " + out.resolve("station.json"));
    }

    private static String stationManifest(Station.Frame frame, MappingSpec mapping, Duration window,
                                          long seed, int eventsInLog) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("tool", "chronotone");
        manifest.put("version", VERSION);
        manifest.put("format", Station.FORMAT);
        manifest.put("mapping", mapping.version());
        manifest.put("grid", mapping.grid().toString());
        manifest.put("window", window.toString());
        manifest.put("from", Event.formatTimestamp(frame.from()));
        manifest.put("to", Event.formatTimestamp(frame.to()));
        manifest.put("seed", seed);
        manifest.put("events_in_log", eventsInLog);
        manifest.put("slots", frame.slots());
        manifest.put("notes", frame.notes());
        manifest.put("seconds", StrictMath.round(frame.seconds() * 1000.0) / 1000.0);
        List<Integer> bounds = new ArrayList<>(frame.bounds().length);
        for (int offset : frame.bounds()) {
            bounds.add(offset);
        }
        manifest.put("slot_samples", bounds);
        manifest.put("timeline_sha256", frame.timelineFingerprint());
        manifest.put("wav_sha256", frame.wavHash());
        manifest.put("timeline", frame.timeline().describe());
        manifest.put("note", "gain is normalised per window; the master of a whole day is `render`");
        return CanonicalJson.write(manifest) + "\n";
    }

    /** Accepts {@code 30m}, {@code 90s}, {@code 6h}, {@code 2d}, or a bare number of minutes. */
    private static Duration parseDuration(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            throw new IllegalStateException("empty duration");
        }
        char unit = text.charAt(text.length() - 1);
        if (Character.isDigit(unit)) {
            return Duration.ofMinutes(Long.parseLong(text));
        }
        long amount = Long.parseLong(text.substring(0, text.length() - 1));
        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw new IllegalStateException("unknown duration unit '" + unit + "' in " + value);
        };
    }

    /**
     * Publishes a Merkle root over a slice of the log — a whole day with {@code --day}, an explicit
     * {@code --from}/{@code --to} window, or everything. The root is the only thing you have to make
     * public; the events stay on your disk.
     */
    private static void commit(Map<String, String> options) throws IOException {
        Path log = Path.of(options.getOrDefault("log", DEFAULT_LOG));
        Path out = Path.of(options.getOrDefault("out", "out/commitment.json"));
        if (!Files.exists(log)) {
            throw new IllegalStateException("no log at " + log
                    + " — run `chronotone demo` or `chronotone sync` first");
        }
        List<LoggedEvent> slice = sliceOf(options, log);
        Commitment commitment = Commitment.of(slice);

        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, commitment.toJson());

        System.out.println("log        " + log);
        System.out.println("commitment " + commitment.describe());
        System.out.println("merkleRoot " + commitment.merkleRoot());
        System.out.println("chainHead  " + commitment.chainHead());
        System.out.println("priorHead  " + commitment.priorHead());
        System.out.println("rule       " + Commitment.RULE);
        System.out.println("wrote      " + out);
        System.out.println();
        System.out.println("publish " + out.getFileName() + " (or just the merkleRoot) and keep the log private.");
    }

    /**
     * Generates an inclusion proof for one entry of a committed slice: the audit path from that
     * entry's hash up to the published root, and nothing else.
     */
    private static void prove(Map<String, String> options) throws IOException {
        Path log = Path.of(options.getOrDefault("log", DEFAULT_LOG));
        Path commitmentPath = Path.of(required(options, "commitment"));
        Path out = Path.of(options.getOrDefault("out", "out/proof.json"));
        int index = Integer.parseInt(required(options, "index"));

        Commitment commitment = Commitment.fromJson(Files.readString(commitmentPath));
        List<LoggedEvent> slice = sliceIn(log, commitment);
        MerkleProof proof = commitment.treeOver(slice).proof(index);
        if (!proof.verify() || !commitment.merkleRoot().equals(proof.root())) {
            throw new IllegalStateException("internal error: the generated proof does not match the commitment");
        }

        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, proof.toJson());

        LoggedEvent entry = slice.get(index);
        System.out.println("commitment " + commitmentPath);
        System.out.println("entry      #" + index + "  " + entry.event().source() + "/" + entry.event().type()
                + "  " + Event.formatTimestamp(entry.event().timestamp()));
        System.out.println("proof      " + proof.describe());
        System.out.println("entryHash  " + proof.entryHash());
        System.out.println("wrote      " + out);
        System.out.println();
        System.out.println("this proof names no other entry and carries no payload.");
    }

    /**
     * Verifies a proof against a commitment using only those two files. The log is deliberately not
     * read: a verifier should not need it, which is the entire point of a commitment.
     */
    private static void check(Map<String, String> options) throws IOException {
        Path commitmentPath = Path.of(required(options, "commitment"));
        Path proofPath = Path.of(required(options, "proof"));

        Commitment commitment = Commitment.fromJson(Files.readString(commitmentPath));
        MerkleProof proof = MerkleProof.fromJson(Files.readString(proofPath));

        List<String> problems = new ArrayList<>();
        if (!proof.root().equals(commitment.merkleRoot())) {
            problems.add("the proof is against root " + proof.root()
                    + " but the commitment says " + commitment.merkleRoot());
        }
        if (proof.size() != commitment.entries()) {
            problems.add("the proof covers " + proof.size() + " entries but the commitment says "
                    + commitment.entries());
        }
        if (!proof.verify()) {
            problems.add(proof.failureReason() == null
                    ? "the audit path does not verify"
                    : proof.failureReason());
        }

        System.out.println("commitment " + commitmentPath + "  (" + commitment.describe() + ")");
        System.out.println("proof      " + proofPath + "  (" + proof.describe() + ")");
        if (problems.isEmpty()) {
            System.out.println("OK  entry #" + proof.index() + " is included in the committed slice");
            System.out.println("    checked without reading the log, the other entries or any payload");
        } else {
            for (String problem : problems) {
                System.out.println("FAILED  " + problem);
            }
            System.exit(1);
        }
    }

    /** Verifies the log's hash chain, and optionally that it still matches a published commitment. */
    private static void verify(Map<String, String> options) throws IOException {
        Path log = Path.of(options.getOrDefault("log", DEFAULT_LOG));
        VerifyResult result = EventLog.verify(log);
        System.out.println(result);
        boolean ok = result.ok();

        if (options.containsKey("commitment")) {
            Path commitmentPath = Path.of(options.get("commitment"));
            System.out.println("commitment " + commitmentPath);
            try {
                Commitment commitment = Commitment.fromJson(Files.readString(commitmentPath));
                List<LoggedEvent> slice = sliceIn(log, commitment);
                System.out.println("           " + commitment.describe());
                if (commitment.covers(slice)) {
                    System.out.println("OK  the log matches the published commitment " + commitment.merkleRoot());
                } else {
                    System.out.println("FAILED  the log does not match the published commitment");
                    ok = false;
                }
            } catch (IOException | RuntimeException e) {
                System.out.println("FAILED  " + e.getMessage());
                ok = false;
            }
        }
        if (!ok) {
            System.exit(1);
        }
    }

    /** The log entries a command covers: a whole {@code --day}, an explicit window, or everything. */
    private static List<LoggedEvent> sliceOf(Map<String, String> options, Path log) throws IOException {
        List<LoggedEvent> entries = EventLog.readEntries(log);
        Instant from = null;
        Instant to = null;
        if (options.containsKey("day")) {
            LocalDate day = LocalDate.parse(options.get("day"));
            from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
            to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        } else {
            if (options.containsKey("from")) {
                from = Instant.parse(options.get("from"));
            }
            if (options.containsKey("to")) {
                to = Instant.parse(options.get("to"));
            }
        }
        if (from == null && to == null) {
            return entries;
        }
        List<LoggedEvent> slice = new ArrayList<>();
        for (LoggedEvent entry : entries) {
            Instant at = entry.event().timestamp();
            if ((from == null || !at.isBefore(from)) && (to == null || at.isBefore(to))) {
                slice.add(entry);
            }
        }
        return slice;
    }

    /**
     * Rebuilds the exact slice a commitment describes. The chain links it records — the hash before
     * the slice and the hash at its end — locate the slice in the log exactly, so no timestamp
     * guessing is involved.
     */
    private static List<LoggedEvent> sliceIn(Path log, Commitment commitment) throws IOException {
        if (commitment.entries() == 0) {
            return List.of();
        }
        List<LoggedEvent> slice = new ArrayList<>();
        boolean collecting = Hashes.GENESIS.equals(commitment.priorHead());
        for (LoggedEvent entry : EventLog.readEntries(log)) {
            if (!collecting) {
                if (entry.hash().equals(commitment.priorHead())) {
                    collecting = true;
                }
                continue;
            }
            slice.add(entry);
            if (entry.hash().equals(commitment.chainHead())) {
                break;
            }
        }
        if (!commitment.covers(slice)) {
            throw new IllegalStateException("the log at " + log + " does not match this commitment — "
                    + "the log has changed since it was published, or the commitment describes other entries");
        }
        return slice;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("--" + key + " is required for this command");
        }
        return value;
    }

    private static String manifest(RenderResult result, MappingSpec mapping, LocalDate day,
                                   Instant from, Instant to, long seed, int eventsInLog) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("tool", "chronotone");
        manifest.put("version", VERSION);
        manifest.put("mapping", mapping.version());
        manifest.put("grid", mapping.grid().toString());
        manifest.put("day", day.toString());
        manifest.put("from", Event.formatTimestamp(from));
        manifest.put("to", Event.formatTimestamp(to));
        manifest.put("seed", seed);
        manifest.put("events_in_log", eventsInLog);
        manifest.put("slots", result.slots());
        manifest.put("notes", result.notes());
        manifest.put("seconds", StrictMath.round(result.seconds() * 1000.0) / 1000.0);
        manifest.put("timeline_sha256", result.timelineFingerprint());
        manifest.put("midi_sha256", result.midiHash());
        manifest.put("wav_sha256", result.wavHash());
        manifest.put("timeline", result.description());
        return CanonicalJson.write(manifest) + "\n";
    }

    private static String mappingYaml(Map<String, String> options) throws IOException {
        String path = options.get("mapping");
        return path == null ? DefaultMapping.YAML : Files.readString(Path.of(path));
    }

    private static Map<String, String> options(String[] args, int from) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalStateException("unexpected argument '" + arg + "'");
            }
            String key = arg.substring(2);
            int equals = key.indexOf('=');
            if (equals >= 0) {
                options.put(key.substring(0, equals), key.substring(equals + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(key, args[++i]);
            } else {
                options.put(key, "true");
            }
        }
        return options;
    }

    private static void usage() {
        System.out.print("""
                chronotone %s — your life, rendered as sound

                usage:
                  chronotone init    [--log <file>]
                  chronotone demo    [--log <file>] [--day <yyyy-MM-dd>] [--seed <n>] [--append]
                  chronotone sync    [--config <file>] [--log <file>] [--from <iso>] [--to <iso>] [--dry-run]
                  chronotone render  [--log <file>] [--out <dir>] [--day <yyyy-MM-dd>]
                                     [--from <HH:mm>] [--minutes <n>] [--seed <n>] [--mapping <file>]
                  chronotone album   [--log <file>] [--out <dir>] [--month <yyyy-MM>]
                                     [--from <yyyy-MM-dd> --days <n>] [--start <HH:mm>]
                                     [--window <dur>] [--stems] [--seed <n>] [--mapping <file>]
                  chronotone station [--log <file>] [--out <dir>] [--mapping <file>] [--seed <n>]
                                     [--window <dur>] [--now <iso>] [--follow] [--every <dur>]
                                     [--ticks <n>] [--stream]
                  chronotone commit  [--log <file>] [--day <yyyy-MM-dd>] [--from <iso>] [--to <iso>]
                                     [--out <file>]
                  chronotone prove   [--log <file>] --commitment <file> --index <n> [--out <file>]
                  chronotone check   --commitment <file> --proof <file>
                  chronotone verify  [--log <file>] [--commitment <file>]

                a first run, with no personal data at all:
                  chronotone demo --seed 42
                  chronotone render --from 07:00 --minutes 30 --seed 42 --out out
                  chronotone verify

                listening to the day as it happens:
                  chronotone station --window 30m
                  chronotone station --follow --every 60s --stream | ffplay -f s16le -ar 44100 -ac 1 -

                with your own data:
                  cp examples/connectors.yaml .chronotone/connectors.yaml   # then edit it
                  chronotone sync --config .chronotone/connectors.yaml --dry-run
                  chronotone sync --config .chronotone/connectors.yaml
                  chronotone render --from 21:00 --minutes 24 --out out

                publishing a month as an album:
                  chronotone album --month 2026-10 --seed 42
                  chronotone album --month 2026-10 --stems            # + pad/bass/lead/kick/hat

                publishing a hash without publishing your day:
                  chronotone commit --day 2026-10-19 --out out/commitment.json
                  chronotone prove  --commitment out/commitment.json --index 4 --out out/proof.json
                  chronotone check  --commitment out/commitment.json --proof out/proof.json

                defaults:
                  --log %s    --out out    --seed 42    --from 07:00    --minutes 30
                """.formatted(VERSION, DEFAULT_LOG));
    }
}
