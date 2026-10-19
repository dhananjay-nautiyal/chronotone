package dev.chronotone.connectors;

import java.nio.file.Path;
import java.util.List;

/** Outcome of a sync run: per-connector detail plus the log's new state. */
public record SyncResult(boolean dryRun, long logSize, int appended, String commitment,
                         List<ConnectorReport> connectors, Path watermarkPath) {

    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (ConnectorReport report : connectors) {
            sb.append(report.summary()).append('\n');
        }
        sb.append("log %d events | appended %d".formatted(logSize, appended));
        if (dryRun) {
            sb.append(" (dry run, nothing written)");
        } else if (commitment != null) {
            sb.append(" | commitment ").append(commitment);
        }
        return sb.toString();
    }
}
