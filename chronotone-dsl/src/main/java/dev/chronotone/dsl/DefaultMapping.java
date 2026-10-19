package dev.chronotone.dsl;

/**
 * The mapping that ships with Chronotone, written as a constant rather than a resource so that the
 * engine, the tests and the CLI all agree on one definition without classpath plumbing.
 *
 * <p>Read it top to bottom: coding intensity drives tempo, lines changed drive how hard it hits,
 * weather opens or dulls the pad, your listening fills the high end, and movement adds space.
 */
public final class DefaultMapping {

    public static final String YAML = """
            # Chronotone default mapping — v1.0
            mapping: v1.0
            grid: 1m              # one slot == one bar of music
            seed_salt: chronotone  # key + mode for a day are derived from this and the date

            params:

              # Coding intensity drives the pulse.
              tempo:
                events: { source: git, type: commit }
                window: 10m
                aggregate: { kind: count }
                in: [0, 8]
                out: [72, 132]
                whenEmpty: 0.0

              # Lines changed => how hard the track hits.
              energy:
                events: { source: git, type: commit }
                window: 10m
                aggregate: { kind: sum, field: lines_changed }
                in: [0, 600]
                out: [0.15, 1.0]
                whenEmpty: 0.1

              # Overcast dulls the pad; clear skies open it up.
              brightness:
                events: { source: weather, type: sample }
                window: 30m
                aggregate: { kind: mean, field: cloud_cover }
                in: [0.0, 1.0]
                out: [1.0, 0.25]
                whenEmpty: 0.6

              # What you are listening to fills the high end.
              hatDensity:
                events: { source: music, type: scrobble }
                window: 10m
                aggregate: { kind: count }
                in: [0, 4]
                out: [0.0, 0.9]
                whenEmpty: 0.15

              # Movement adds space: a walk makes the room bigger.
              reverbWet:
                events: { source: health, type: steps }
                window: 60m
                aggregate: { kind: mean, field: steps }
                in: [0, 300]
                out: [0.1, 0.6]
                whenEmpty: 0.2
            """;

    private DefaultMapping() {
    }
}
