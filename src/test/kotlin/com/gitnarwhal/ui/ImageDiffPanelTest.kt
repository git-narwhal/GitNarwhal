package com.gitnarwhal.ui

import com.gitnarwhal.components.ImageDiffPanel
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Headless-safe: builds and lays out the panel for modified / added / deleted images. */
class ImageDiffPanelTest {
    private fun png(argb: Int) = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, argb) }, "png", it)
    }.toByteArray()

    @Test fun `panel builds for modified added and deleted`() {
        for ((o, n) in listOf(png(-1) to png(-16777216), null to png(-1), png(-1) to null)) {
            val p = ImageDiffPanel(o, n)
            p.setSize(600, 400)
            p.doLayout()
            assertNotNull(p.preferredSize)
        }
    }
}
