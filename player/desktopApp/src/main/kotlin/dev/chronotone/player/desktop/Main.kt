package dev.chronotone.player.desktop

import dev.chronotone.player.StationFrame
import java.awt.Dimension
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * A reference player for a rendered station frame.
 *
 * ```bash
 * java -jar chronotone-player.jar --station out/station
 * ```
 *
 * It deliberately does not render audio and does not read the event log. It plays the frame the
 * engine produced and seeks with the sample offsets the engine published.
 */
fun main(args: Array<String>) {
    val options = try {
        Options.parse(args)
    } catch (failure: IllegalArgumentException) {
        System.err.println("error: ${failure.message}")
        System.err.println(Options.USAGE)
        exitProcess(2)
    }

    val frame = try {
        StationFrame.open(options.station)
    } catch (failure: Throwable) {
        System.err.println("error: ${failure.message}")
        exitProcess(1)
    }

    if (options.screenshot != null) {
        val panel = PlayerPanel(frame, audio = null, startSlot = options.slot)
        val image = Screenshot.render(panel, panel.preferredSize.width, panel.preferredSize.height)
        ImageIO.write(image, "png", options.screenshot.toFile())
        println("wrote ${options.screenshot} (${image.width}x${image.height})")
        frame.close()
        return
    }

    val audio = AudioPlayer(frame.wav)
    SwingUtilities.invokeLater {
        val panel = PlayerPanel(frame, audio, options.slot)
        panel.preferredSize = Dimension(960, 540)

        val window = JFrame("chronotone — ${options.station.fileName}")
        window.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        window.contentPane = panel
        window.isResizable = false
        window.pack()
        window.setLocationRelativeTo(null)
        window.isVisible = true
        panel.start()

        Runtime.getRuntime().addShutdownHook(Thread { frame.close() })
    }
}

private class Options(
    val station: Path,
    val screenshot: Path?,
    val slot: Int,
) {
    companion object {
        val USAGE = """
            usage: chronotone-player [options]

              --station <dir>       a directory with station.json and chronotone.wav
                                    (default: out/station)
              --slot <n>            the bar to open on (default: 0)
              --screenshot <png>    paint the player to an image and exit, no display needed
        """.trimIndent()

        fun parse(args: Array<String>): Options {
            var station = Path.of("out", "station")
            var screenshot: Path? = null
            var slot = 0

            var at = 0
            while (at < args.size) {
                val flag = args[at]
                val value = args.getOrNull(at + 1)
                    ?: throw IllegalArgumentException("$flag needs a value")

                when (flag) {
                    "--station" -> { station = Path.of(value); at += 2 }
                    "--screenshot" -> { screenshot = Path.of(value); at += 2 }
                    "--slot" -> {
                        slot = value.toIntOrNull() ?: throw IllegalArgumentException("--slot needs a number")
                        at += 2
                    }
                    "--help", "-h" -> { println(USAGE); exitProcess(0) }
                    else -> throw IllegalArgumentException("unknown option '$flag'")
                }
            }
            return Options(station, screenshot, slot)
        }
    }
}
