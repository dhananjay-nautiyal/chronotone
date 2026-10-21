package dev.chronotone.player.desktop

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JComponent

/**
 * Paints a component to an image without a display.
 *
 * This is what makes the README screenshot trustworthy: the picture is produced by the same
 * `paintComponent` the app uses, so it cannot drift from the real UI.
 */
object Screenshot {

    fun render(component: JComponent, width: Int, height: Int): BufferedImage {
        component.setSize(width, height)
        component.doLayout()

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            component.printAll(g)
        } finally {
            g.dispose()
        }
        return image
    }
}
