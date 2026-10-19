package dev.chronotone.connectors;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorConfigTest {

    @Test
    void parsesTheExampleConfig() throws Exception {
        // Surefire runs with the module directory as the working directory.
        List<ConnectorConfig> configs = ConnectorConfigFile.read(java.nio.file.Path.of("..", "examples", "connectors.yaml"));

        assertEquals(3, configs.size());
        assertEquals("git", configs.getFirst().id());
        assertEquals("git", configs.getFirst().type());
        assertEquals(".", configs.getFirst().option("repo"));
        assertEquals("steps", configs.get(1).id());
        assertEquals(List.of("steps", "distance_m"), configs.get(1).list("payload"));
    }

    @Test
    void rejectsDuplicateIdsBecauseTheyKeyTheWatermarks() {
        ConnectorConfigException error = assertThrows(ConnectorConfigException.class, () -> new ConnectorConfigFile().parse("""
                connectors:
                  - id: git
                    type: git
                  - id: git
                    type: synthetic
                """));
        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test
    void rejectsAMissingType() {
        assertThrows(ConnectorConfigException.class, () -> new ConnectorConfigFile().parse("""
                connectors:
                  - id: git
                """));
    }

    @Test
    void rejectsUnknownTypesWhenCreatingTheConnector() {
        ConnectorConfig config = new ConnectorConfig("mystery", "telepathy", java.util.Map.of());
        ConnectorConfigException error = assertThrows(ConnectorConfigException.class, () -> ConnectorFactory.create(config));
        assertTrue(error.getMessage().contains("telepathy"));
    }

    @Test
    void expandsTildeInPaths() {
        String expanded = ConnectorConfig.expand("~/exports/steps.csv").toString();
        assertTrue(expanded.startsWith(System.getProperty("user.home")));
        assertTrue(expanded.endsWith("exports/steps.csv"));
    }

    @Test
    void typedOptionAccessorsFailClearly() {
        ConnectorConfig config = new ConnectorConfig("w", "open-meteo", java.util.Map.of("latitude", "north"));
        ConnectorConfigException error = assertThrows(ConnectorConfigException.class, () -> config.decimal("latitude", 0));
        assertTrue(error.getMessage().contains("latitude"));
    }
}
