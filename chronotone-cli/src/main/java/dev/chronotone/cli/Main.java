package dev.chronotone.cli;

import dev.chronotone.core.connector.SyntheticConnector;
import dev.chronotone.core.log.CanonicalJson;
import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.EventLog;
import dev.chronotone.core.log.VerifyResult;
import dev.chronotone.dsl.DefaultMapping;
import dev.chronotone.dsl.MappingParser;
import dev.chronotone.dsl.MappingSpec;
import dev.chronotone.dsl.ParamSpec;
import dev.chronotone.render.Chronotone;
import dev.chronotone.render.RenderResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * {@code chronotone init|demo|render|verify}.
 *
 * <p>No wall-clock values are written into any artifact, so two renders of the same inputs can be
 * compared byte for byte — which is exactly what CI does.
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
                case "render" -> render(options);
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

    /** Verifies the log's hash chain without rendering anything. */
    private static void verify(Map<String, String> options) throws IOException {
        Path log = Path.of(options.getOrDefault("log", DEFAULT_LOG));
        VerifyResult result = EventLog.verify(log);
        System.out.println(result);
        if (!result.ok()) {
            System.exit(1);
        }
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
                  chronotone render  [--log <file>] [--out <dir>] [--day <yyyy-MM-dd>]
                                     [--from <HH:mm>] [--minutes <n>] [--seed <n>] [--mapping <file>]
                  chronotone verify  [--log <file>]

                a first run:
                  chronotone demo --seed 42
                  chronotone render --from 07:00 --minutes 30 --seed 42 --out out
                  chronotone verify

                defaults:
                  --log %s    --out out    --seed 42    --from 07:00    --minutes 30
                """.formatted(VERSION, DEFAULT_LOG));
    }
}
