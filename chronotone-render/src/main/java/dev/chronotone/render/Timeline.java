package dev.chronotone.render;

import dev.chronotone.core.log.CanonicalJson;
import dev.chronotone.core.log.Event;
import dev.chronotone.core.log.Hashes;
import dev.chronotone.core.music.Mode;
import dev.chronotone.core.music.MusicalParams;
import dev.chronotone.dsl.MappingSpec;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The musical spine of a render: one {@link MusicalParams} per slot, computed from the mapping and
 * the events. Two timelines built from the same inputs produce the same {@link #fingerprint()},
 * which is the value worth publishing — it captures "this day, as heard through this mapping"
 * without revealing a single event.
 */
public final class Timeline {

    private static final int MAX_SLOTS = 20_000;
    private static final Mode[] MODES = Mode.values();

    private final MappingSpec mapping;
    private final List<Event> events;
    private final List<Instant> slots;
    private final List<MusicalParams> params;
    private final int rootPitchClass;
    private final Mode mode;

    private Timeline(MappingSpec mapping, List<Event> events, List<Instant> slots,
                     List<MusicalParams> params, int rootPitchClass, Mode mode) {
        this.mapping = mapping;
        this.events = events;
        this.slots = slots;
        this.params = params;
        this.rootPitchClass = rootPitchClass;
        this.mode = mode;
    }

    public static Timeline of(MappingSpec mapping, List<Event> events, Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("'to' must be after 'from'");
        }
        List<Event> sorted = events.stream()
                .filter(event -> !event.timestamp().isAfter(to))
                .sorted((a, b) -> a.timestamp().compareTo(b.timestamp()))
                .toList();

        Duration grid = mapping.grid();
        List<Instant> slots = new ArrayList<>();
        for (Instant slot = from; slot.isBefore(to); slot = slot.plus(grid)) {
            slots.add(slot);
            if (slots.size() > MAX_SLOTS) {
                throw new IllegalArgumentException("window holds more than " + MAX_SLOTS
                        + " slots; use a coarser grid or a shorter span");
            }
        }

        // A day gets its own tonal identity: same salt + same date => same key, forever.
        LocalDate day = from.atZone(ZoneOffset.UTC).toLocalDate();
        String dayKey = mapping.seedSalt() + "|" + day;
        int root = (int) (Long.parseLong(Hashes.sha256Hex(dayKey).substring(0, 8), 16) % 12);
        Mode dayMode = MODES[(int) (Long.parseLong(Hashes.sha256Hex(dayKey + "|mode").substring(0, 8), 16) % MODES.length)];

        List<MusicalParams> params = new ArrayList<>(slots.size());
        for (Instant slot : slots) {
            MusicalParams evaluated = MusicalParams.from(mapping.evaluate(slot, sorted), MusicalParams.defaults());
            params.add(evaluated.withKey(root, dayMode));
        }
        return new Timeline(mapping, sorted, List.copyOf(slots), List.copyOf(params), root, dayMode);
    }

    public int size() {
        return slots.size();
    }

    public Instant slot(int index) {
        return slots.get(index);
    }

    public MusicalParams params(int index) {
        return params.get(index);
    }

    public List<MusicalParams> params() {
        return params;
    }

    public List<Event> events() {
        return events;
    }

    public MappingSpec mapping() {
        return mapping;
    }

    public int rootPitchClass() {
        return rootPitchClass;
    }

    public Mode mode() {
        return mode;
    }

    /** Content address of the timeline itself: the mapping and every parameter of every slot. */
    public String fingerprint() {
        List<Object> slotDigest = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            MusicalParams p = params.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("slot", Event.formatTimestamp(slots.get(i)));
            entry.put("tempo", p.tempoBpm());
            entry.put("energy", p.energy());
            entry.put("brightness", p.brightness());
            entry.put("hatDensity", p.hatDensity());
            entry.put("reverbWet", p.reverbWet());
            slotDigest.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("mapping", mapping.version());
        root.put("grid", mapping.grid().toString());
        root.put("salt", mapping.seedSalt());
        root.put("root", rootPitchClass);
        root.put("mode", mode.name());
        root.put("slots", slotDigest);
        return Hashes.sha256Hex(CanonicalJson.write(root));
    }

    /** Human-readable summary of the day's movement, used by the CLI. */
    public String describe() {
        if (params.isEmpty()) {
            return "empty timeline";
        }
        int minTempo = Integer.MAX_VALUE;
        int maxTempo = Integer.MIN_VALUE;
        double energy = 0.0;
        double hats = 0.0;
        for (MusicalParams p : params) {
            minTempo = Math.min(minTempo, p.tempoBpm());
            maxTempo = Math.max(maxTempo, p.tempoBpm());
            energy += p.energy();
            hats += p.hatDensity();
        }
        int n = params.size();
        return "%d slots | %s, root pitch class %d | tempo %d..%d bpm | mean energy %.2f | mean hats %.2f".formatted(
                n, mode.name().toLowerCase(), rootPitchClass, minTempo, maxTempo, energy / n, hats / n);
    }
}
