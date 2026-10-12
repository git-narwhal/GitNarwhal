package com.gitnarwhal.utils

import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/** Pure helpers for the image diff view (no Swing components). */
object ImageDiff {
    private val EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp")

    fun isImagePath(path: String) = path.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** Decodes [bytes] with ImageIO; null if absent or not a decodable image. */
    fun decode(bytes: ByteArray?): BufferedImage? {
        if (bytes == null || bytes.isEmpty()) return null
        return try { ImageIO.read(bytes.inputStream()) } catch (_: Exception) { null }
    }

    /** True when at least one side decodes, i.e. the image view can be shown. */
    fun canShow(old: ByteArray?, new: ByteArray?) = decode(old) != null || decode(new) != null

    /** Short human readable state: "added", "deleted" or "modified". */
    fun changeKind(old: Any?, new: Any?) = when {
        old == null && new != null -> "added"
        old != null && new == null -> "deleted"
        else                       -> "modified"
    }

    fun formatSize(bytes: Int) = when {
        bytes < 1024        -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else                -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    /** Pixel difference: unchanged pixels become dark, changed ones red scaled by intensity.
     *  A missing side counts as fully transparent, so added/deleted images light up entirely. */
    fun difference(a: BufferedImage?, b: BufferedImage?): BufferedImage {
        val w = max(a?.width ?: 0, b?.width ?: 0).coerceAtLeast(1)
        val h = max(a?.height ?: 0, b?.height ?: 0).coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) for (x in 0 until w) {
            val pa = pixel(a, x, y)
            val pb = pixel(b, x, y)
            val d = max(
                max(abs(channel(pa, 24) - channel(pb, 24)), abs(channel(pa, 16) - channel(pb, 16))),
                max(abs(channel(pa, 8) - channel(pb, 8)), abs(channel(pa, 0) - channel(pb, 0)))
            )
            out.setRGB(x, y, if (d == 0) 0xFF202020.toInt() else {
                val v = (96 + d * 159 / 255).coerceAtMost(255)
                (0xFF shl 24) or (v shl 16) or (0x30 shl 8) or 0x30
            })
        }
        return out
    }

    /** Overlay of [b] at [alpha] (0..1) on top of [a]. */
    fun blend(a: BufferedImage?, b: BufferedImage?, alpha: Float): BufferedImage {
        val w = max(a?.width ?: 0, b?.width ?: 0).coerceAtLeast(1)
        val h = max(a?.height ?: 0, b?.height ?: 0).coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        try {
            if (a != null) {
                g.composite = AlphaComposite.SrcOver.derive(1f - alpha)
                g.drawImage(a, 0, 0, null)
            }
            if (b != null) {
                g.composite = AlphaComposite.SrcOver.derive(alpha)
                g.drawImage(b, 0, 0, null)
            }
        } finally { g.dispose() }
        return out
    }

    private fun pixel(img: BufferedImage?, x: Int, y: Int) =
        if (img != null && x < img.width && y < img.height) img.getRGB(x, y) else 0

    private fun channel(argb: Int, shift: Int) = (argb ushr shift) and 0xFF
}
