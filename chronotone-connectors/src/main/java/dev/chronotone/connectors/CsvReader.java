package dev.chronotone.connectors;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny RFC-4180-ish CSV line parser: quoted fields, doubled quotes inside them, and commas inside
 * quotes. Exports from health and music apps are messy, so this handles the common cases without a
 * CSV library.
 */
final class CsvReader {

    private CsvReader() {
    }

    static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }
}
