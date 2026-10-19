# Chronotone

**Your life, rendered as sound.**

Chronotone turns a window of your digital life — commits, scrobbles, weather, steps — into an
original, continuous piece of music. Scrub back to any moment and hear that exact moment again.
At the end of a day (or a month, or a year) you get a finished track.

This is not a generative-music toy. The core idea is the **determinism contract**:

> A render is a pure function of `(events, seed, mapping, grid)`. Same inputs, same audio, same hash.

That one constraint changes what the project can be:

| Consequence | What it enables |
| --- | --- |
| Reproducible audio | The timeline becomes *navigable*, not random. A moment always sounds the same. |
| Content addressing | Every render has a `sha256`. Share the hash, keep the events private. |
| Tamper-evident history | The event log is a hash chain; the head hash is the log's public commitment. |
| CI-verifiable art | Two renders of the same day must be byte-identical, and CI enforces it. |

## Status

Phase 1 of the roadmap is complete and working:

- Append-only, tamper-evident **event log** (canonical JSON + hash chain, zero dependencies)
- **Connector SPI** plus a deterministic synthetic connector for demos and CI
- **Mapping DSL** (YAML): window an event source, aggregate it, map the aggregate into a musical dimension
- **Deterministic renderer**: timeline → note score → Standard MIDI File *and* a self-contained
  44.1 kHz PCM WAV written by a small built-in synth (no audio libraries, no soundfont downloads)
- **Timeline fingerprint**, `sha256` of the MIDI and WAV, and a chain `verify` command
- CLI: `init`, `demo`, `render`, `verify`

## Quickstart

Requires JDK 21 and Maven.

```bash
mvn -B verify                                   # build + tests

mvn -B -ntp package -DskipTests
JAR=$(ls chronotone-cli/target/chronotone-cli-*.jar | grep -v original | head -n1)

# 1. Write a deterministic synthetic day (git commits, scrobbles, weather, steps)
java -jar "$JAR" demo --log .chronotone/demo.jsonl --seed 42

# 2. Render 30 minutes of it, starting at 07:00
java -jar "$JAR" render --log .chronotone/demo.jsonl \
  --from 07:00 --minutes 30 --seed 42 --out out

# 3. Listen
ffplay -autoexit out/chronotone.wav

# 4. Prove the history was not edited
java -jar "$JAR" verify --log .chronotone/demo.jsonl
```

Listen to the same minute twice and compare the hashes: they will be identical.

## How a day becomes music

```
connectors (git, scrobble, weather, health)
      └─► event log        append-only, hash-chained, canonical JSON
              └─► mapping DSL    window + aggregate + range  ──►  musical params per slot
                      └─► score builder    params ──► notes (PPQ 480)
                              ├─► SMF writer     ──► chronotone.mid
                              └─► built-in synth ──► chronotone.wav
```

A mapping is data, not code:

```yaml
mapping: v1.0
grid: 1m                 # one slot == one bar

params:
  tempo:
    events: { source: git, type: commit }
    window: 10m
    aggregate: { kind: count }
    in: [0, 8]           # raw count  ->  normalised 0..1
    out: [72, 132]       # normalised ->  BPM

  brightness:
    events: { source: weather, type: sample }
    window: 30m
    aggregate: { kind: mean, field: cloud_cover }
    in: [0.0, 1.0]
    out: [1.0, 0.25]     # overcast dulls the pad, clear sky opens it up
```

Musical parameters: `tempo`, `energy`, `brightness`, `hatDensity`, `reverbWet`.
Key and mode are derived deterministically from the mapping's `seed_salt` and the day,
so a day has its own tonal identity. See `chronotone-dsl/src/main/java/dev/chronotone/dsl/DefaultMapping.java`.

## Modules

| Module | Responsibility | Dependencies |
| --- | --- | --- |
| `chronotone-core` | `Event`, canonical JSON, JSON reader, hash chain, `EventLog`, connector SPI | **none** |
| `chronotone-dsl` | Mapping DSL: parse, validate, evaluate | core, SnakeYAML |
| `chronotone-render` | Timeline, score builder, MIDI writer, PCM/WAV synth | core, dsl |
| `chronotone-cli` | `init` / `demo` / `render` / `verify` | all |

## Determinism rules

The renderer only uses `StrictMath` (portable IEEE-754 results), `java.util.Random` (a specified
algorithm) seeded from a mixing function of `(seed, slot)`, and integer ticks — no wall-clock time,
no hash-map iteration order, no float accumulation across threads. The render manifest deliberately
contains no timestamp so that two renders can be `diff`ed in CI.

## Roadmap

| Phase | Scope | State |
| --- | --- | --- |
| **1** | Event log, connector SPI, mapping DSL, deterministic MIDI + WAV render, CLI, CI | ✅ |
| 2 | Real connectors (git, Last.fm/Subsonic, weather, health) | next |
| 3 | Merkle commitments so a hash can be published while the payloads stay private | planned |
| 4 | Live station mode: render the last N minutes continuously, then commit the day | planned |
| 5 | Android/KMP player with timeline scrubbing | planned |
| 6 | Monthly album export: stems, mastering, generative cover art, share card | planned |

## Honest limitations

The hash chain proves nothing was edited *if you trust the head hash*. It does not yet prove
anything about events you have not disclosed — that needs Merkle proofs (Phase 3). Treat the
current commitment as tamper-evidence within a log, not as a zero-knowledge claim.

## License

MIT — see [LICENSE](LICENSE).
