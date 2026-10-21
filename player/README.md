# chronotone-player

A navigator for a rendered Chronotone frame.

It plays a `chronotone.wav` and seeks with the sample offsets the engine published in
`station.json`. It does not render audio, does not read the event log, and does not know what a
tempo is.

## Why not render on the phone

The determinism contract is the project's one irreplaceable asset: the same events, seed, mapping
and grid must produce the same samples on every machine. A Kotlin port of the synth would be a
second implementation that has to agree with the first forever — the most expensive way to protect a
guarantee.

So the split is:

```
engine (Java)  ──renders──▶  chronotone.wav + station.json  ──plays──▶  phone
```

The phone holds audio and a manifest. It never holds an event, a payload, or a mapping. That is a
privacy improvement as well as an architectural one.

## The contract

`station.json` is written by `chronotone station` (format `chronotone.station/1`). The field that
matters is:

```
slot_samples[0 .. slots+1]
```

`slots + 2` sample offsets: the start of every bar, then the end of the last bar, then the end of the
release tail. Seeking to a wall-clock instant means finding the bar that contains it and starting at
`slot_samples[bar]` — never re-deriving a bar length from a tempo.

`shared/src/jvmTest/resources/station.json` is a real manifest produced by the engine. CI regenerates
that exact frame with the Java CLI and `diff`s the file before running the Kotlin tests, so the two
languages cannot drift apart quietly.

## Layout

| Module | What it is |
| --- | --- |
| `shared` | Kotlin Multiplatform (`jvm` + `androidTarget`): JSON reader, ISO-8601, manifest validation, seek math. No dependencies. |
| `shared/src/jvmSharedMain` | File and PCM plumbing shared by desktop and Android (`java.io`/`java.nio`). |
| `desktopApp` | A Swing reference player. Custom-painted, so the same code draws the window and the documentation screenshot. |
| `androidApp` | A Compose screen with the same waveform, scrubber and readout; `AudioTrack` streams PCM from the exact sample. |

## Desktop

```bash
cd player
./gradlew :desktopApp:run --args="--station ../out/station"
```

Controls: click anywhere on the waveform or scrubber to seek to that bar, space to play/pause.

Render an image without a display (this is how `docs/player.png` is made):

```bash
./gradlew :desktopApp:run \
  --args="--station ../out/station --slot 23 --screenshot player.png"
```

## Android

```bash
./gradlew :androidApp:assembleDebug
adb install androidApp/build/outputs/apk/debug/androidApp-debug.apk

# The app reads its frame from its external files directory.
adb push ../out/station/station.json   /sdcard/Android/data/dev.chronotone.player/files/frame/
adb push ../out/station/chronotone.wav /sdcard/Android/data/dev.chronotone.player/files/frame/
```

The Android module is included only when an SDK is found (`local.properties`, `ANDROID_HOME` or
`ANDROID_SDK_ROOT`). Without one, `shared` and `desktopApp` still build and test.

## Tests

```bash
./gradlew :shared:jvmTest :desktopApp:test
```

- `shared` — JSON reader, ISO-8601 instants and durations, manifest validation (rejecting incoherent
  bounds), and the seek math: every slot round-trips through its own samples, instants map to the bar
  that contains them, and the release tail belongs to the last bar.
- `shared` fixture test — asserts the player agrees with the engine's own numbers in a real manifest.
- `desktopApp` — builds a frame on disk, verifies the manifest/WAV consistency check, and paints the
  panel off-screen (a blank or broken UI fails the test).

## Limits

- A frame is rendered elsewhere and copied to the device. The player does not synthesize.
- The Android app is compiled in CI but not run there: there is no emulator in the pipeline.
- `chronotone.wav` is uncompressed, so a 30-minute frame is about 7 MB and a whole day about 380 MB.
  The player streams from disk, so it does not need that in memory, but it is a lot of storage.
