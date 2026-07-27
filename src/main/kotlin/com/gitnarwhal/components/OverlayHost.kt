package com.gitnarwhal.components

import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * Attaches a blocking overlay panel above a single host component (e.g. a
 * RepoTab) instead of the window's glass pane, so it only blocks input over
 * that host's screen area — other tabs (and the tab strip) stay usable.
 *
 * The overlay is added to the window's JLayeredPane (free-form positioning,
 * no layout manager to fight with) and its bounds are kept in sync with the
 * host's on-screen position/size.
 */
object OverlayHost {

    private data class Attachment(val host: JComponent, val layeredPane: JLayeredPane, val listener: ComponentAdapter)

    private val attachments = mutableMapOf<JComponent, Attachment>()

    fun attach(host: JComponent, overlay: JComponent) {
        val window = SwingUtilities.getWindowAncestor(host) as? RootPaneContainer

        if (window == null) {
            // Fallback: no window yet, just cover the host directly.
            overlay.setBounds(0, 0, host.width, host.height)
            host.add(overlay)
            host.setComponentZOrder(overlay, 0)
            return
        }

        val layeredPane = window.layeredPane

        fun reposition() {
            val pos = SwingUtilities.convertPoint(host, 0, 0, layeredPane)
            overlay.setBounds(pos.x, pos.y, host.width, host.height)
        }

        reposition()
        layeredPane.add(overlay, JLayeredPane.PALETTE_LAYER as Any)
        overlay.revalidate()
        overlay.repaint()

        val listener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = reposition()
            override fun componentMoved(e: ComponentEvent)   = reposition()
        }
        host.addComponentListener(listener)
        attachments[overlay] = Attachment(host, layeredPane, listener)
    }

    fun detach(overlay: JComponent) {
        val (host, layeredPane, listener) = attachments.remove(overlay) ?: run {
            (overlay.parent as? JComponent)?.remove(overlay)
            return
        }
        host.removeComponentListener(listener)
        layeredPane.remove(overlay)
        layeredPane.revalidate()
        layeredPane.repaint()
    }
}
