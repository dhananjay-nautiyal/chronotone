package dev.chronotone.player.android

import dev.chronotone.player.StationFrame
import java.io.File

/**
 * Where a rendered frame lives on the device.
 *
 * The phone is not asked to render anything, so it does not need the event log, the mapping, or the
 * engine. It is given two files — the audio and the manifest that says where every bar begins — and
 * that is the whole privacy story: the phone never sees an event.
 *
 * The files are read from the app's external files directory because `adb push` can write there:
 *
 * ```
 * adb push out/station/station.json   /sdcard/Android/data/dev.chronotone.player/files/frame/
 * adb push out/station/chronotone.wav /sdcard/Android/data/dev.chronotone.player/files/frame/
 * ```
 */
object FrameStore {

    const val DIRECTORY = "frame"

    fun load(externalFiles: File): StationFrame =
        StationFrame.open(File(externalFiles, DIRECTORY).toPath())

    fun hint(): String = """
        No frame found. Push one from the machine that rendered it:

        adb push out/station/station.json \\
          /sdcard/Android/data/dev.chronotone.player/files/frame/
        adb push out/station/chronotone.wav \\
          /sdcard/Android/data/dev.chronotone.player/files/frame/
    """.trimIndent()
}
