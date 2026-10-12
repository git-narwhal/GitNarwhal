package com.gitnarwhal.components

import com.gitnarwhal.utils.ImageDiff
import java.awt.*
import java.awt.image.BufferedImage
import javax.swing.*

/**
 * Image diff viewer. Either side may be missing: old == null means the image was added,
 * new == null means it was deleted. Modes: side by side, onion skin, pixel difference.
 */
class ImageDiffPanel(oldBytes: ByteArray?, newBytes: ByteArray?) : JPanel(BorderLayout()), Scrollable {

    private val oldImg = ImageDiff.decode(oldBytes)
    private val newImg = ImageDiff.decode(newBytes)
    private val kind   = ImageDiff.changeKind(oldBytes, newBytes)

    private val oldPane = ImagePane(oldImg, if (oldBytes == null) "(added — no previous version)" else "(cannot decode image)")
    private val newPane = ImagePane(newImg, if (newBytes == null) "(deleted)" else "(cannot decode image)")
    private val overlayPane = ImagePane(null, "")

    private val modeBox = JComboBox(arrayOf("Side by side", "Onion skin", "Difference"))
    private val zoomBox = JComboBox(arrayOf("Fit", "50%", "100%", "200%", "400%"))
    private val onionSlider = JSlider(0, 100, 50)
    private val cards = CardLayout()
    private val center = JPanel(cards)

    init {
        val bg = UIManager.getColor("EditorPane.background") ?: Color(0x2B, 0x2B, 0x2B)
        background = bg

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            isOpaque = false
            add(JLabel("Image $kind"))
            add(modeBox)
            add(JLabel("Zoom"))
            add(zoomBox)
            add(onionSlider.apply { isVisible = false; toolTipText = "Old ◄──► New" })
        }
        add(top, BorderLayout.NORTH)

        // Side by side with shared scrolling so both images pan together
        val oldScroll = JScrollPane(oldPane)
        val newScroll = JScrollPane(newPane)
        newScroll.horizontalScrollBar.model = oldScroll.horizontalScrollBar.model
        newScroll.verticalScrollBar.model   = oldScroll.verticalScrollBar.model
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, labelled("Old", oldBytes, oldImg, oldScroll),
            labelled("New", newBytes, newImg, newScroll)).apply {
            resizeWeight = 0.5
            border = null
        }
        center.add(split, "side")
        center.add(JScrollPane(overlayPane), "overlay")
        add(center, BorderLayout.CENTER)
        preferredSize = Dimension(600, 380)

        modeBox.addActionListener { applyMode() }
        zoomBox.addActionListener { applyZoom() }
        onionSlider.addChangeListener { if (!onionSlider.valueIsAdjusting) applyMode() }
        applyMode()
    }

    override fun getPreferredScrollableViewportSize() = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16
    override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height
    override fun getScrollableTracksViewportWidth()  = true
    override fun getScrollableTracksViewportHeight() = true

    private fun labelled(title: String, bytes: ByteArray?, img: BufferedImage?, body: JComponent) =
        JPanel(BorderLayout()).apply {
            val info = when {
                bytes == null -> "none"
                img == null   -> ImageDiff.formatSize(bytes.size)
                else          -> "${img.width}×${img.height} · ${ImageDiff.formatSize(bytes.size)}"
            }
            add(JLabel("$title: $info").apply {
                border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
                font = font.deriveFont(Font.BOLD, 11f)
            }, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }

    private fun applyMode() {
        onionSlider.isVisible = modeBox.selectedIndex == 1
        when (modeBox.selectedIndex) {
            0 -> cards.show(center, "side")
            1 -> { overlayPane.image = ImageDiff.blend(oldImg, newImg, onionSlider.value / 100f); cards.show(center, "overlay") }
            else -> { overlayPane.image = ImageDiff.difference(oldImg, newImg); cards.show(center, "overlay") }
        }
        applyZoom()
    }

    private fun applyZoom() {
        val z = when (zoomBox.selectedIndex) {
            1 -> 0.5; 2 -> 1.0; 3 -> 2.0; 4 -> 4.0
            else -> 0.0     // fit
        }
        listOf(oldPane, newPane, overlayPane).forEach { it.zoom = z }
    }

    /** Draws an image on a checkerboard at a fixed zoom, or scaled down to fit (zoom == 0). */
    private class ImagePane(img: BufferedImage?, private val placeholder: String) : JPanel(), Scrollable {
        var image: BufferedImage? = img
            set(v) { field = v; revalidate(); repaint() }
        var zoom: Double = 0.0
            set(v) { field = v; revalidate(); repaint() }

        override fun getPreferredSize(): Dimension {
            val img = image
            if (img == null || zoom == 0.0) return Dimension(1, 1)
            return Dimension((img.width * zoom).toInt().coerceAtLeast(1), (img.height * zoom).toInt().coerceAtLeast(1))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val img = image
            if (img == null) {
                g.color = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                val fm = g.fontMetrics
                g.drawString(placeholder, (width - fm.stringWidth(placeholder)) / 2, height / 2)
                return
            }
            val scale = if (zoom == 0.0) minOf(width.toDouble() / img.width, height.toDouble() / img.height, 1.0) else zoom
            val w = (img.width * scale).toInt().coerceAtLeast(1)
            val h = (img.height * scale).toInt().coerceAtLeast(1)
            val x = if (zoom == 0.0) (width - w) / 2 else 0
            val y = if (zoom == 0.0) (height - h) / 2 else 0
            val cell = 8
            for (cy in 0 until (h + cell - 1) / cell) for (cx in 0 until (w + cell - 1) / cell) {
                g.color = if ((cx + cy) % 2 == 0) Color(0x80, 0x80, 0x80) else Color(0xA0, 0xA0, 0xA0)
                g.fillRect(x + cx * cell, y + cy * cell, minOf(cell, w - cx * cell), minOf(cell, h - cy * cell))
            }
            (g as Graphics2D).setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                if (scale >= 1.0) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR else RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(img, x, y, w, h, null)
        }

        override fun getPreferredScrollableViewportSize() = Dimension(200, 200)
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = if (o == SwingConstants.VERTICAL) r.height else r.width
        override fun getScrollableTracksViewportWidth()  = zoom == 0.0 || image == null
        override fun getScrollableTracksViewportHeight() = zoom == 0.0 || image == null
    }
}
