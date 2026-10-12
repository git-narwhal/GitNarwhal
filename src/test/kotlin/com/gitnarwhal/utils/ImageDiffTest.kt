package com.gitnarwhal.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageDiffTest {
    private fun img(w: Int, h: Int, argb: Int) =
        BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { i ->
            for (y in 0 until h) for (x in 0 until w) i.setRGB(x, y, argb)
        }

    private fun png(i: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(i, "png", it) }.toByteArray()

    @Test fun `isImagePath matches supported extensions case-insensitively`() {
        assertTrue(ImageDiff.isImagePath("a/b/logo.PNG"))
        assertTrue(ImageDiff.isImagePath("x.jpeg"))
        assertFalse(ImageDiff.isImagePath("readme.md"))
        assertFalse(ImageDiff.isImagePath("noext"))
    }

    @Test fun `decode handles null empty garbage and valid png`() {
        assertNull(ImageDiff.decode(null))
        assertNull(ImageDiff.decode(ByteArray(0)))
        assertNull(ImageDiff.decode(byteArrayOf(1, 2, 3)))
        assertEquals(3, ImageDiff.decode(png(img(3, 2, 0xFFFF0000.toInt())))!!.width)
    }

    @Test fun `canShow true when only one side exists`() {
        val p = png(img(2, 2, 0xFF00FF00.toInt()))
        assertTrue(ImageDiff.canShow(null, p))
        assertTrue(ImageDiff.canShow(p, null))
        assertFalse(ImageDiff.canShow(null, null))
        assertFalse(ImageDiff.canShow(byteArrayOf(1), byteArrayOf(2)))
    }

    @Test fun `changeKind covers added deleted modified`() {
        assertEquals("added", ImageDiff.changeKind(null, 1))
        assertEquals("deleted", ImageDiff.changeKind(1, null))
        assertEquals("modified", ImageDiff.changeKind(1, 2))
    }

    @Test fun `formatSize units`() {
        assertEquals("10 B", ImageDiff.formatSize(10))
        assertEquals("1.5 KB", ImageDiff.formatSize(1536).replace(',', '.'))
        assertTrue(ImageDiff.formatSize(3 * 1024 * 1024).endsWith("MB"))
    }

    @Test fun `difference marks equal pixels dark and changed pixels red`() {
        val a = img(2, 1, 0xFF000000.toInt())
        val b = img(2, 1, 0xFF000000.toInt()).also { it.setRGB(1, 0, 0xFFFFFFFF.toInt()) }
        val d = ImageDiff.difference(a, b)
        assertEquals(0xFF202020.toInt(), d.getRGB(0, 0))
        assertEquals(255, (d.getRGB(1, 0) shr 16) and 0xFF)
    }

    @Test fun `difference of added image lights up whole canvas and uses larger size`() {
        val d = ImageDiff.difference(null, img(3, 2, 0xFFFFFFFF.toInt()))
        assertEquals(3, d.width); assertEquals(2, d.height)
        assertNotEquals(0xFF202020.toInt(), d.getRGB(2, 1))
        val e = ImageDiff.difference(null, null)
        assertEquals(1, e.width)
    }

    @Test fun `blend mixes at alpha and tolerates missing side`() {
        val a = img(1, 1, 0xFFFF0000.toInt())
        val b = img(1, 1, 0xFF0000FF.toInt())
        assertEquals(0xFF, ImageDiff.blend(a, b, 0f).getRGB(0, 0) shr 16 and 0xFF)
        assertEquals(0xFF, ImageDiff.blend(a, b, 1f).getRGB(0, 0) and 0xFF)
        assertEquals(1, ImageDiff.blend(null, b, 0.5f).width)
        assertEquals(1, ImageDiff.blend(a, null, 0.5f).width)
    }
}
