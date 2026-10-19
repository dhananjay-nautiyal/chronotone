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
| Tamper-evident history | The log is a hash chain, and a **Merkle root** over any slice can be published while the events stay private. |
| CI-verifiable art | Two renders of the same day must be byte-identical, and CI enforces it. |

## Status

Phases 1–3 of the roadmap are complete and working:

- Append-only, tamper-evident **event log** (canonical JSON + hash chain, zero dependencies)
- **Connector SPI** plus a deterministic synthetic connector for demos and CI
- **Connectors that read real life**: your local git history, CSV/JSONL data exports you already
  own, and historical weather from Open-Meteo (no API key)
- **`sync`**: incremental via per-connector watermarks, idempotent via content identity, with a
  dry-run mode that touches nothing
- **Mapping DSL** (YAML): window an event source, aggregate it, map the aggregate into a musical dimension
- **Deterministic renderer**: timeline → note score → Standard MIDI File *and* a self-contained
  44.1 kHz PCM WAV written by a small built-in synth (no audio libraries, no soundfont downloads)
- **Merkle commitments**: publish a root over a day, prove one entry is inside it, and verify that
  proof from two small JSON files without the log
- **Timeline fingerprint**, `sha256` of the MIDI and WAV, and a chain `verify` command
- CLI: `init`, `demo`, `sync`, `render`, `commit`, `prove`, `check`, `verify`

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

### Commitments

The chain and the Merkle tree answer different questions. The chain proves *nothing in this log was
edited*, but checking it needs every byte. A commitment lets you publish a root and later prove one
event is inside it — without disclosing the rest.

```bash
java -jar "$JAR" commit --day 2026-10-19 --out out/commitment.json    # publish this file
java -jar "$JAR" prove  --commitment out/commitment.json --index 4 --out out/proof.json
java -jar "$JAR" check  --commitment out/commitment.json --proof out/proof.json
java -jar "$JAR" verify --commitment out/commitment.json              # log still matches?
```

`check` reads only those two files: no log, no other entries, no payloads. For 24 committed entries
the proof is 5 hashes.

Why both? Because a hash chain alone cannot detect a *re-hashed rewrite*. If someone edits an event
and recomputes every hash after it, the chain is internally consistent and `verify --log` says `OK`.
Sending that forged log against a commitment published earlier makes it fail:

```
$ java -jar "$JAR" verify --log forged.jsonl                     # chain recomputed by the forger
OK  24 entries  head=ad66d30742a8...

$ java -jar "$JAR" verify --log forged.jsonl --commitment out/commitment.json
FAILED  the log does not match this commitment
```

### With your own data

Nothing personal ever goes into this repository — connectors run locally and write to a log that
stays on your disk. `examples/connectors.yaml` is a working config you can copy and edit:

```bash
cp examples/connectors.yaml .chronotone/connectors.yaml     # then point it at your own exports

java -jar "$JAR" sync --config .chronotone/connectors.yaml --dry-run   # see what would be added
java -jar "$JAR" sync --config .chronotone/connectors.yaml             # append it
java -jar "$JAR" render --from 07:00 --minutes 100 --out out
java -jar "$JAR" verify
```

## Connectors

A connector turns one source of your life into events. Adding a source is a config entry, not code.

| Type | Reads | Options |
| --- | --- | --- |
| `git` | your local commit history via `git log --numstat` | `repo` |
| `file` | any CSV or JSONL export you already have (health, listening history, habits) | `path`, `format`, `time_column`, `time_format`, `payload` |
| `open-meteo` | historical hourly weather (keyless public API) | `latitude`, `longitude` |
| `synthetic` | a deterministic fake day, for tests and demos | `seed` |

```yaml
connectors:
  - id: git
    type: git
    repo: ~/projects/chronotone
  - id: scrobbles
    type: file
    path: ~/exports/scrobbles.csv
    format: csv
    time_column: played_at
    time_format: iso          # iso | epoch_s | epoch_ms | <pattern>
    event_source: music
    event_type: scrobble
    payload: artist,track,duration_s
```

**Privacy.** Events are produced on your machine and appended to a local log. The only network call
in the whole project is the weather connector, which sends a latitude and a longitude and nothing
else. The engine is public; your life is not.

**Sync is safe to re-run.** A watermark per connector means the next sync only asks for new time,
and an event's identity is `sha256(connectorId + canonical event)`, so overlapping windows cannot
duplicate history. Deleting the watermarks entirely still appends nothing twice.

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
| `chronotone-core` | `Event`, canonical JSON, JSON reader, hash chain, `EventLog`, Merkle tree/proofs/commitments, connector SPI | **none** |
| `chronotone-dsl` | Mapping DSL: parse, validate, evaluate | core, SnakeYAML |
| `chronotone-render` | Timeline, score builder, MIDI writer, PCM/WAV synth | core, dsl |
| `chronotone-connectors` | git history, CSV/JSONL exports, Open-Meteo weather, incremental sync | core, SnakeYAML |
| `chronotone-cli` | `init` / `demo` / `sync` / `render` / `commit` / `prove` / `check` / `verify` | all |

## Determinism rules

The renderer only uses `StrictMath` (portable IEEE-754 results), `java.util.Random` (a specified
algorithm) seeded from a mixing function of `(seed, slot)`, and integer ticks — no wall-clock time,
no hash-map iteration order, no float accumulation across threads. The render manifest deliberately
contains no timestamp so that two renders can be `diff`ed in CI.

## Roadmap

| Phase | Scope | State |
| --- | --- | --- |
| **1** | Event log, connector SPI, mapping DSL, deterministic MIDI + WAV render, CLI, CI | ✅ |
| **2** | Real connectors (git, CSV/JSONL exports, Open-Meteo weather), incremental idempotent sync | ✅ |
| **3** | Merkle commitments: publish a root, prove inclusion, verify without the log | ✅ |
| 4 | Live station mode: render the last N minutes continuously, then commit the day | next |
| 5 | Android/KMP player with timeline scrubbing | planned |
| 6 | Monthly album export: stems, mastering, generative cover art, share card | planned |

## Honest limitations

Merkle commitments close the biggest gap, but not every one. Still true today:

- **The commitment is checked against a log you hold.** A commitment binds you to a history; it does
  not prove the history is *complete*, or that events were not withheld.
- **Proofs are inclusion-only.** There is no non-inclusion proof, so "this event was in the day" is
  provable and "this event was not" is not.
- **Event timestamps and source/type are public** in a commitment's scope description. The payloads
  are what stays private.
- **A tree over 24 entries is small.** Anything short of a full privacy design (padding, hiding the
  slice size) leaks the number of events and their rough times.
- **The synth is deliberately plain.** The WAV is clean and listenable, not mastered.

## License

MIT — see [LICENSE](LICENSE).
