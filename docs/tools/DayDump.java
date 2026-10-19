package dev.chronotone.render;

import dev.chronotone.core.connector.SyntheticConnector;
import dev.chronotone.core.log.Event;
import dev.chronotone.dsl.DefaultMapping;
import dev.chronotone.dsl.MappingParser;
import dev.chronotone.dsl.MappingSpec;

import java.time.Instant;
import java.util.List;

/**
 * Prints the deterministic synthetic day as data, for {@code docs/generate_assets.py} to chart.
 *
 * <p>Two things are worth noting. It lives in the {@code dev.chronotone.render} package on purpose: the
 * sample range of a slot is package-private geometry, and reading the engine's own numbers beats
 * re-deriving bar lengths in Python and getting them subtly wrong. And the day it prints is the
 * synthetic one — the docs chart generated data and never anybody's life.
 *
 * <pre>{@code
 * # from the repository root, with the modules built
 * CP=chronotone-core/target/chronotone-core-0.1.0-SNAPSHOT.jar:\
 * chronotone-dsl/target/chronotone-dsl-0.1.0-SNAPSHOT.jar:\
 * chronotone-render/target/chronotone-render-0.1.0-SNAPSHOT.jar:\
 * $(ls ~/.m2/repository/org/yaml/snakeyaml/*/snakeyaml-*.jar | head -1)
 *
 * javac -cp "$CP" -d /tmp/daydump docs/tools/DayDump.java
 * java -cp "$CP:/tmp/daydump" dev.chronotone.render.DayDump 2026-10-19T00:00:00Z > out/day/day.csv
 * }</pre>
 */
public final class DayDump {

    public static void main(String[] args) {
        MappingSpec mapping = new MappingParser().parse(DefaultMapping.YAML);
        Instant from = Instant.parse(args.length > 0 ? args[0] : "2026-10-19T00:00:00Z");
        Instant to = from.plusSeconds(86_400);
        List<Event> events = new SyntheticConnector(42L).fetch(from, to);
        Timeline timeline = Timeline.of(mapping, events, from, to);
        WavRenderer.Geometry geometry = WavRenderer.geometry(timeline);

        System.out.println("slot,tempo,energy,brightness,hats,reverb,start,end,seconds");
        for (int i = 0; i < timeline.size(); i++) {
            var params = timeline.params(i);
            System.out.printf("%d,%d,%.5f,%.5f,%.5f,%.5f,%d,%d,%.4f%n", i,
                    params.tempoBpm(), params.energy(), params.brightness(),
                    params.hatDensity(), params.reverbWet(),
                    geometry.bounds()[i], geometry.bounds()[i + 1], WavRenderer.secondsPerBar(params));
        }
    }
}
