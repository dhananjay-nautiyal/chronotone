package dev.chronotone.core.connector;

import dev.chronotone.core.log.Event;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A deterministic fake day: focused coding bursts, music all day, weather and movement samples.
 * It exists so that the engine, the docs and CI can all produce the *same* day of life without
 * anybody handing over their real data.
 *
 * <p>Determinism comes from iterating fixed five-minute buckets and drawing from a single
 * {@link Random} seeded with {@code seed}; {@code StrictMath} is used so the curve is identical on
 * every JVM.
 */
public final class SyntheticConnector implements Connector {

    private static final String[] REPOS = {"chronotone", "music", "anime-watch", "astro-talk"};
    private static final String[] ARTISTS = {"Tycho", "Aphex Twin", "Nils Frahm", "Bonobo", "Four Tet"};
    private static final String[] TRACKS = {"Awake", "Xtal", "Says", "Kong", "Two Thousand and Seventeen"};

    private final long seed;

    public SyntheticConnector(long seed) {
        this.seed = seed;
    }

    @Override
    public String id() {
        return "synthetic";
    }

    @Override
    public String describe() {
        return "synthetic day (seed " + seed + ")";
    }

    @Override
    public List<Event> fetch(Instant from, Instant to) {
        Random rng = new Random(seed);
        List<Event> events = new ArrayList<>();
        Instant cursor = from.truncatedTo(ChronoUnit.MINUTES);
        while (cursor.isBefore(to)) {
            int hour = cursor.atZone(ZoneOffset.UTC).getHour();
            int minute = cursor.atZone(ZoneOffset.UTC).getMinute();
            double intensity = activityCurve(hour);

            if (rng.nextDouble() < intensity * 0.9) {
                events.add(Event.of("git", "commit", cursor, Map.of(
                        "repo", REPOS[rng.nextInt(REPOS.length)],
                        "lines_changed", 1 + rng.nextInt(240))));
                if (rng.nextDouble() < intensity * 0.4) {
                    events.add(Event.of("git", "commit", cursor.plusSeconds(1 + rng.nextInt(50)), Map.of(
                            "repo", REPOS[rng.nextInt(REPOS.length)],
                            "lines_changed", 1 + rng.nextInt(90))));
                }
            }

            if (rng.nextDouble() < 0.55) {
                events.add(Event.of("music", "scrobble", cursor, Map.of(
                        "artist", ARTISTS[rng.nextInt(ARTISTS.length)],
                        "track", TRACKS[rng.nextInt(TRACKS.length)],
                        "duration_s", 120 + rng.nextInt(200))));
            }

            if (minute % 30 == 0) {
                events.add(Event.of("weather", "sample", cursor, Map.of(
                        "cloud_cover", round2(rng.nextDouble()),
                        "temp_c", 12 + rng.nextInt(18))));
            }

            if (minute % 15 == 0) {
                events.add(Event.of("health", "steps", cursor, Map.of(
                        "steps", rng.nextInt(400))));
            }

            cursor = cursor.plus(5, ChronoUnit.MINUTES);
        }
        events.sort(Comparator.comparing(Event::timestamp));
        return events;
    }

    /** Late-morning and late-night focus peaks, with a quiet floor the rest of the day. */
    private static double activityCurve(int hour) {
        double morning = StrictMath.exp(-StrictMath.pow(hour - 11, 2) / 6.0);
        double evening = StrictMath.exp(-StrictMath.pow(hour - 22, 2) / 5.0);
        double floor = hour < 7 ? 0.02 : 0.06;
        return Math.min(1.0, floor + 0.9 * Math.max(morning, evening));
    }

    private static double round2(double value) {
        return StrictMath.rint(value * 100.0) / 100.0;
    }
}
