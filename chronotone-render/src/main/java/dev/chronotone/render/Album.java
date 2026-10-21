package dev.chronotone.render;

import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.LoggedEvent;
import dev.chronotone.core.merkle.Commitment;
import dev.chronotone.dsl.MappingSpec;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A month as an album: one track per day, all sharing a single gain, with stems and a day-closed
 * manifest.
 *
 * <p>This is where the mastering that {@code station} could not do happens. A live frame normalises
 * itself because the peak of a session is not knowable while it is still running; an album does know
 * its material, so it renders every day <em>before</em> deciding how loud the album is, then scales
 * every track by the same factor. A quiet Tuesday stays quieter than a loud Saturday — which is the
 * difference between an album and a folder of separately mastered clips.
 *
 * <p>Nothing here holds a month in memory. Each day is synthesised block by block
 * ({@link StreamingSynth}), once to find the album's peak and once to write. The cost is two passes
 * over the material; the benefit is that a 30-day album needs the memory of one bar, not 12 GB.
 *
 * <p>Each track carries the Merkle root of the log entries it was made from, so publishing an album
 * publishes a commitment to the month without publishing a single event.
 */
public final class Album {

    public static final String FORMAT = "chronotone.album/1";

    /** What each stem is called on disk, in score order. */
    public static final Map<String, WavRenderer.Timbre> STEMS;

    static {
        Map<String, WavRenderer.Timbre> stems = new LinkedHashMap<>();
        stems.put("pad", WavRenderer.Timbre.PAD);
        stems.put("bass", WavRenderer.Timbre.BASS);
        stems.put("lead", WavRenderer.Timbre.LEAD);
        stems.put("kick", WavRenderer.Timbre.KICK);
        stems.put("hat", WavRenderer.Timbre.HAT);
        STEMS = java.util.Collections.unmodifiableMap(stems);
    }

    private Album() {
    }

    /** One day of the album. Hashes are the publishable identity of its audio. */
    public record Track(LocalDate day, Instant from, Instant to, int events, int slots, int notes,
                        double seconds, String timelineFingerprint, String wavHash,
                        String merkleRoot, String chainHead, Map<String, String> stems) {
    }

    public record Result(List<Track> tracks, List<LocalDate> emptyDays, double seconds, boolean stems) {

        public int days() {
            return tracks.size();
        }

        public Map<String, Object> toManifest(MappingSpec mapping, long seed, LocalDate from, int requestedDays,
                                              LocalTime dayStart, Duration window) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("tool", "chronotone");
            root.put("format", FORMAT);
            root.put("mapping", mapping.version());
            root.put("grid", mapping.grid().toString());
            root.put("seed", seed);
            root.put("from", from.toString());
            root.put("to", from.plusDays(requestedDays).toString());
            root.put("requested_days", requestedDays);
            root.put("day_start", dayStart.toString());
            root.put("window", window.toString());
            root.put("mastered", "album");
            root.put("peak", WavRenderer.PEAK);
            root.put("seconds", roundMillis(seconds));

            List<Object> tracks = new ArrayList<>(this.tracks.size());
            for (Track track : this.tracks) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("day", track.day().toString());
                entry.put("from", Event.formatTimestamp(track.from()));
                entry.put("to", Event.formatTimestamp(track.to()));
                entry.put("events", track.events());
                entry.put("slots", track.slots());
                entry.put("notes", track.notes());
                entry.put("seconds", roundMillis(track.seconds()));
                entry.put("timeline_sha256", track.timelineFingerprint());
                entry.put("wav_sha256", track.wavHash());
                entry.put("merkle_root", track.merkleRoot());
                entry.put("chain_head", track.chainHead());
                if (!track.stems().isEmpty()) {
                    entry.put("stems", track.stems());
                }
                tracks.add(entry);
            }
            root.put("tracks", tracks);
            root.put("empty_days", this.emptyDays.stream().map(LocalDate::toString).toList());
            return root;
        }

        private static double roundMillis(double seconds) {
            return StrictMath.round(seconds * 1000.0) / 1000.0;
        }
    }

    /** What pass one learned about a day, without keeping its audio. */
    private record DayPlan(LocalDate day, Instant from, Instant to, int events, int slots, int notes,
                           int samples, String timelineFingerprint, String merkleRoot, String chainHead) {
    }

    /**
     * Renders {@code days} consecutive days from {@code from}, each covering {@code window} from
     * {@code dayStart} (UTC), and writes {@code tracks/} (and, with {@code stems},
     * {@code stems/<day>/}) under {@code out}.
     */
    public static Result render(MappingSpec mapping, List<LoggedEvent> ledger, LocalDate from, int days,
                                LocalTime dayStart, Duration window, long seed, Path out, boolean stems)
            throws IOException {
        if (days <= 0) {
            throw new IllegalArgumentException("an album needs at least one day");
        }
        if (window.compareTo(mapping.grid()) < 0) {
            throw new IllegalArgumentException("--window (" + window + ") must be at least one grid step ("
                    + mapping.grid() + ")");
        }

        // Pass one: how loud is the album? Nothing is written yet.
        List<DayPlan> plans = new ArrayList<>();
        List<LocalDate> empty = new ArrayList<>();
        double peak = 0.0;

        for (int i = 0; i < days; i++) {
            LocalDate day = from.plusDays(i);
            Instant start = day.atTime(dayStart).toInstant(ZoneOffset.UTC);
            Instant end = start.plus(window);
            List<LoggedEvent> entries = entriesIn(ledger, start, end);
            if (entries.isEmpty()) {
                empty.add(day);
                continue;
            }

            List<Event> events = entries.stream().map(LoggedEvent::event).toList();
            Timeline timeline = Timeline.of(mapping, events, start, end);
            List<NoteEvent> notes = new ScoreBuilder(timeline, seed).build();

            StreamingSynth synth = new StreamingSynth(timeline, notes);
            while (synth.hasNext()) {
                for (double sample : synth.nextBlock()) {
                    peak = Math.max(peak, Math.abs(sample));
                }
            }

            int samples = WavRenderer.geometry(timeline).totalSamples();
            if (samples > Integer.MAX_VALUE / 2) {
                throw new IllegalStateException("the day " + day + " renders to " + samples
                        + " samples, which does not fit in a 32-bit WAV; use a shorter --window");
            }

            Commitment commitment = Commitment.of(entries);
            plans.add(new DayPlan(day, start, end, entries.size(), timeline.size(), notes.size(),
                    samples, timeline.fingerprint(), commitment.merkleRoot(), commitment.chainHead()));
        }

        if (plans.isEmpty()) {
            throw new IllegalStateException("no day between " + from + " and " + from.plusDays(days - 1)
                    + " has any events" + (empty.isEmpty() ? "" : " (empty: " + empty + ")"));
        }

        double gain = peak < 1e-9 ? 1.0 : WavRenderer.PEAK / peak;

        // Pass two: write every track with that one gain.
        Path tracksDir = out.resolve("tracks");
        Files.createDirectories(tracksDir);
        Path stemsDir = out.resolve("stems");

        List<Track> tracks = new ArrayList<>(plans.size());
        for (DayPlan plan : plans) {
            List<Event> events = entriesIn(ledger, plan.from(), plan.to()).stream()
                    .map(LoggedEvent::event).toList();
            Timeline timeline = Timeline.of(mapping, events, plan.from(), plan.to());
            List<NoteEvent> notes = new ScoreBuilder(timeline, seed).build();
            StreamingSynth full = new StreamingSynth(timeline, notes);

            Map<String, StreamingSynth> stemSynths = new LinkedHashMap<>();
            Map<String, WavSink> stemSinks = new LinkedHashMap<>();
            if (stems) {
                Path dayDir = stemsDir.resolve(plan.day().toString());
                Files.createDirectories(dayDir);
                for (Map.Entry<String, WavRenderer.Timbre> stem : STEMS.entrySet()) {
                    stemSynths.put(stem.getKey(), new StreamingSynth(timeline, notes, stem.getValue()));
                    stemSinks.put(stem.getKey(), new WavSink(dayDir.resolve(stem.getKey() + ".wav"), plan.samples()));
                }
            }

            WavSink track = new WavSink(tracksDir.resolve(plan.day() + ".wav"), plan.samples());
            try {
                while (full.hasNext()) {
                    double[] block = full.nextBlock();
                    track.write(block, gain);
                    for (Map.Entry<String, StreamingSynth> stem : stemSynths.entrySet()) {
                        stemSinks.get(stem.getKey()).write(stem.getValue().nextBlock(), gain);
                    }
                }
            } finally {
                track.close();
                for (WavSink sink : stemSinks.values()) {
                    sink.close();
                }
            }

            Map<String, String> stemHashes = new LinkedHashMap<>();
            for (Map.Entry<String, WavSink> sink : stemSinks.entrySet()) {
                stemHashes.put(sink.getKey(), sink.getValue().hash());
            }

            tracks.add(new Track(plan.day(), plan.from(), plan.to(), plan.events(), plan.slots(),
                    plan.notes(), plan.samples() / (double) WavRenderer.SAMPLE_RATE,
                    plan.timelineFingerprint(), track.hash(), plan.merkleRoot(), plan.chainHead(),
                    Map.copyOf(stemHashes)));
        }

        double seconds = tracks.stream().mapToDouble(Track::seconds).sum();
        return new Result(List.copyOf(tracks), List.copyOf(empty), seconds, stems);
    }

    /** The log entries whose event timestamp falls in {@code [from, to)}. */
    private static List<LoggedEvent> entriesIn(List<LoggedEvent> ledger, Instant from, Instant to) {
        List<LoggedEvent> slice = new ArrayList<>();
        for (LoggedEvent entry : ledger) {
            Instant at = entry.event().timestamp();
            if (!at.isBefore(from) && at.isBefore(to)) {
                slice.add(entry);
            }
        }
        return slice;
    }

    /**
     * Writes 16-bit PCM and hashes the bytes as they go, so a 300 MB track never has to exist in
     * memory to be identified.
     */
    private static final class WavSink implements Closeable {

        private final Path path;
        private final MessageDigest digest;
        private final OutputStream out;
        private String hash;

        WavSink(Path path, int samples) throws IOException {
            this.path = path;
            this.digest = sha256();
            this.out = new DigestOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(path), 1 << 16), digest);
            out.write(WavRenderer.header(samples * (WavRenderer.BITS_PER_SAMPLE / 8)));
        }

        void write(double[] block, double gain) throws IOException {
            byte[] bytes = new byte[block.length * 2];
            for (int i = 0; i < block.length; i++) {
                short value = WavRenderer.toPcm(block[i] * gain);
                bytes[2 * i] = (byte) value;
                bytes[2 * i + 1] = (byte) (value >>> 8);
            }
            out.write(bytes);
        }

        String hash() {
            if (hash == null) {
                throw new IllegalStateException("hash(" + path + ") before the file was closed");
            }
            return hash;
        }

        @Override
        public void close() throws IOException {
            if (hash != null) {
                return;
            }
            try {
                out.flush();
                hash = HexFormat.of().formatHex(digest.digest());
            } finally {
                out.close();
            }
        }

        @Override
        public String toString() {
            return path.toString();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required in every JDK", e);
        }
    }
}
