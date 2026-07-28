package com.gitnarwhal.components

import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
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

    private data class Attachment(
        val host: JComponent,
        val layeredPane: JLayeredPane,
        val componentListener: ComponentAdapter,
        val hierarchyListener: HierarchyListener
    )

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

        // Only block input while the host's tab is actually the one on screen —
        // JTabbedPane keeps non-selected tabs' components alive but hidden, so
        // without this the overlay would keep covering the window after switching away.
        overlay.isVisible = host.isShowing

        reposition()
        layeredPane.add(overlay, JLayeredPane.PALETTE_LAYER as Any)
        overlay.revalidate()
        overlay.repaint()

        val componentListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = reposition()
            override fun componentMoved(e: ComponentEvent)   = reposition()
        }
        host.addComponentListener(componentListener)

        val hierarchyListener = HierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                overlay.isVisible = host.isShowing
                if (host.isShowing) reposition()
            }
        }
        host.addHierarchyListener(hierarchyListener)

        attachments[overlay] = Attachment(host, layeredPane, componentListener, hierarchyListener)
    }

    fun detach(overlay: JComponent) {
        val attachment = attachments.remove(overlay) ?: run {
            (overlay.parent as? JComponent)?.remove(overlay)
            return
        }
        attachment.host.removeComponentListener(attachment.componentListener)
        attachment.host.removeHierarchyListener(attachment.hierarchyListener)
        attachment.layeredPane.remove(overlay)
        attachment.layeredPane.revalidate()
        attachment.layeredPane.repaint()
    }
}
