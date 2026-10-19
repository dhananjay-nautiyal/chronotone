package dev.chronotone.connectors;

import dev.chronotone.core.connector.Connector;
import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Historical weather from Open-Meteo's archive API. No API key, no account, hourly samples — so a
 * grey afternoon is literally duller in the render than a bright one.
 *
 * <p>Weather is the one connector that talks to the network. Nothing about you is sent beyond a
 * latitude and a longitude.
 *
 * <pre>{@code
 * - id: weather
 *   type: open-meteo
 *   latitude: 30.3165
 *   longitude: 78.0322
 * }</pre>
 */
public final class OpenMeteoConnector implements Connector {

    private static final String ENDPOINT = "https://archive-api.open-meteo.com/v1/archive";
    private static final String HOURLY = "cloud_cover,temperature_2m,precipitation";

    private final String id;
    private final double latitude;
    private final double longitude;
    private final String endpoint;
    private final int timeoutSeconds;
    private final String eventSource;
    private final String eventType;

    public OpenMeteoConnector(ConnectorConfig config) {
        this.id = config.id();
        this.latitude = config.decimal("latitude", Double.NaN);
        this.longitude = config.decimal("longitude", Double.NaN);
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            throw new ConnectorConfigException("connector '" + id
                    + "' (open-meteo) requires 'latitude' and 'longitude'");
        }
        this.endpoint = config.option("endpoint", ENDPOINT);
        this.timeoutSeconds = config.integer("timeout_seconds", 20);
        this.eventSource = config.option("event_source", "weather");
        this.eventType = config.option("event_type", "sample");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String describe() {
        return id + " (open-meteo at " + latitude + "," + longitude + ")";
    }

    @Override
    public List<Event> fetch(Instant from, Instant to) {
        LocalDate start = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = to.minusMillis(1).atZone(ZoneOffset.UTC).toLocalDate();
        String url = "%s?latitude=%s&longitude=%s&start_date=%s&end_date=%s&hourly=%s&timezone=UTC"
                .formatted(endpoint, latitude, longitude, start, end, HOURLY);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", "chronotone")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("open-meteo responded " + response.statusCode() + ": "
                        + response.body().strip().substring(0, Math.min(200, response.body().length())));
            }
            return parse(response.body(), eventSource, eventType, from, to);
        } catch (IOException e) {
            throw new ConnectorException("connector '" + id + "': " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("connector '" + id + "': interrupted while calling open-meteo", e);
        }
    }

    /** Extracted so that the response shape is tested without touching the network. */
    static List<Event> parse(String json, String eventSource, String eventType, Instant from, Instant to) {
        Map<String, Object> root = Json.parseObject(json);
        if (!(root.get("hourly") instanceof Map<?, ?> hourlyNode)) {
            String reason = root.get("reason") == null ? "no 'hourly' block in the response" : String.valueOf(root.get("reason"));
            throw new ConnectorConfigException("open-meteo: " + reason);
        }
        Map<String, Object> hourly = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : hourlyNode.entrySet()) {
            hourly.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        List<?> times = asList(hourly.get("time"));
        List<?> clouds = asList(hourly.get("cloud_cover"));
        List<?> temperatures = asList(hourly.get("temperature_2m"));
        List<?> precipitation = asList(hourly.get("precipitation"));

        List<Event> events = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            Instant timestamp = Instant.parse(String.valueOf(times.get(i)) + ":00Z");
            if (!TimeParser.inWindow(timestamp, from, to)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            Object cloud = at(clouds, i);
            if (cloud instanceof Number number) {
                payload.put("cloud_cover", number.doubleValue() / 100.0);
            }
            Object temperature = at(temperatures, i);
            if (temperature instanceof Number number) {
                payload.put("temp_c", number.doubleValue());
            }
            Object rain = at(precipitation, i);
            if (rain instanceof Number number) {
                payload.put("precipitation_mm", number.doubleValue());
            }
            events.add(Event.of(eventSource, eventType, timestamp, payload));
        }
        return events;
    }

    private static Object at(List<?> list, int index) {
        return index < list.size() ? list.get(index) : null;
    }

    private static List<?> asList(Object node) {
        return node instanceof List<?> list ? list : List.of();
    }
}
