package dev.chronotone.connectors;

import dev.chronotone.core.log.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenMeteoConnectorTest {

    private static final String FIXTURE = """
            {"latitude":30.3,"longitude":78.0,"timezone":"GMT","hourly_units":{"time":"iso8601","cloud_cover":"%"},
             "hourly":{
               "time":["2026-10-19T09:00","2026-10-19T10:00","2026-10-19T11:00","2026-10-19T12:00"],
               "cloud_cover":[20,80,100,5],
               "temperature_2m":[18.4,19.1,21.0,22.6],
               "precipitation":[0,0.2,1.5,0]}}
            """;

    @Test
    void turnsHourlyWeatherIntoNormalisedEvents() {
        Instant from = Instant.parse("2026-10-19T09:00:00Z");
        Instant to = Instant.parse("2026-10-19T12:00:00Z");

        List<Event> events = OpenMeteoConnector.parse(FIXTURE, "weather", "sample", from, to);

        assertEquals(3, events.size());
        Event first = events.getFirst();
        assertEquals("weather", first.source());
        assertEquals(Instant.parse("2026-10-19T09:00:00Z"), first.timestamp());
        assertEquals(0.2, first.payload().get("cloud_cover"));   // percent -> 0..1, what the mapping wants
        assertEquals(18.4, first.payload().get("temp_c"));
        assertEquals(1.5, events.get(2).payload().get("precipitation_mm"));
    }

    @Test
    void theWindowIsRespected() {
        List<Event> events = OpenMeteoConnector.parse(FIXTURE, "weather", "sample",
                Instant.parse("2026-10-19T09:00:00Z"), Instant.parse("2026-10-19T10:00:00Z"));
        assertEquals(1, events.size());
    }

    @Test
    void anApiErrorBecomesAReadableFailure() {
        ConnectorConfigException error = assertThrows(ConnectorConfigException.class,
                () -> OpenMeteoConnector.parse("{\"reason\":\"Latitude must be in range\"}", "weather", "sample",
                        Instant.parse("2026-10-19T09:00:00Z"), Instant.parse("2026-10-19T10:00:00Z")));
        assertTrue(error.getMessage().contains("Latitude must be in range"));
    }

    @Test
    void coordinatesAreRequired() {
        ConnectorConfigException error = assertThrows(ConnectorConfigException.class,
                () -> new OpenMeteoConnector(new ConnectorConfig("weather", "open-meteo", Map.of())));
        assertTrue(error.getMessage().contains("latitude"));
    }
}
