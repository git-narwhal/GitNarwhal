package com.gitnarwhal.views

import com.gitnarwhal.utils.NativeFileChooser
import com.gitnarwhal.utils.RecentReposService
import com.gitnarwhal.utils.Settings
import com.gitnarwhal.utils.save
import com.gitnarwhal.utils.toPath
import org.json.JSONObject
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import java.nio.file.Files
import javax.swing.*

class MainView : JPanel(BorderLayout()) {

    val tabPane = JTabbedPane()

    /**
     * Guard against the ChangeListener firing re-entrantly.
     *
     * When we insert a new tab at the position of the selected "+" tab,
     * JTabbedPane auto-increments selectedIndex back to "+", which would fire
     * the listener again and spawn a second AddCloneTab.
     */
    @Volatile private var suppressTabChange = false
    /** True while restoring saved tabs at startup — prevents triggering refresh on every tab as it's added. */
    private var loadingTabs = false
    private var dragSourceIndex = -1

    init {
        add(tabPane, BorderLayout.CENTER)

        //region restore last-open tabs
        loadingTabs = true
        try {
            val toRemove = arrayListOf<JSONObject>()
            for (tab in if (Settings.reopenTabs) Settings.openTabs.map { it as JSONObject } else emptyList()) {
                val path = tab.getString("path").toPath()
                val name = tab.getString("name")
                if (Files.isDirectory(path)) {
                    addTab(RepoTab(path.toAbsolutePath().toString(), name), save = false)
                } else {
                    toRemove.add(tab)
                }
            }
            Settings.openTabs.removeAll { toRemove.contains(it as JSONObject) }
        } catch (ignored: Exception) {
            println("Open tabs loading error: ${ignored.message}")
            Settings.openTabs.removeAll { true }
        }
        Settings.save()
        loadingTabs = false
        //endregion

        addPlusTab()

        if (tabPane.tabCount == 1) {
            // Only the "+" — open a fresh Add/Clone/Create tab on first run
            addNewCloneTab()
        }

        tabPane.selectedIndex = 0

        val tabMouseHandler = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.button == java.awt.event.MouseEvent.BUTTON2) {
                    val idx = tabPane.indexAtLocation(e.x, e.y)
                    if (idx >= 0 && tabPane.getTitleAt(idx) != PLUS_TITLE)
                        closeTab(tabPane.getComponentAt(idx) as JPanel)
                }
            }
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.button == java.awt.event.MouseEvent.BUTTON1) {
                    val idx = tabPane.indexAtLocation(e.x, e.y)
                    dragSourceIndex = if (idx >= 0 && tabPane.getTitleAt(idx) != PLUS_TITLE) idx else -1
                }
            }
            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                if (dragSourceIndex >= 0)
                    tabPane.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR)
            }
            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                tabPane.cursor = java.awt.Cursor.getDefaultCursor()
                val src = dragSourceIndex.also { dragSourceIndex = -1 }
                if (src < 0) return
                val dst = tabPane.indexAtLocation(e.x, e.y)
                if (dst >= 0 && dst != src && tabPane.getTitleAt(dst) != PLUS_TITLE)
                    moveTab(src, dst)
            }
        }
        tabPane.addMouseListener(tabMouseHandler)
        tabPane.addMouseMotionListener(tabMouseHandler)

        tabPane.addChangeListener {
            if (suppressTabChange) return@addChangeListener
            val idx = tabPane.selectedIndex
            if (idx >= 0 && tabPane.getTitleAt(idx) == PLUS_TITLE) {
                suppressTabChange = true
                SwingUtilities.invokeLater {
                    try { addNewCloneTab() }
                    finally { suppressTabChange = false }
                }
            } else if (idx >= 0) {
                // Force a clean repaint to prevent ghost-rendering artifacts
                val selected = tabPane.getComponentAt(idx)
                selected?.revalidate()
                selected?.repaint()
                tabPane.revalidate()
                tabPane.repaint()
                // Trigger data load for the newly selected tab.
                // Skipped while loadingTabs (startup) — only the final selectedIndex=0 fires it.
                if (!loadingTabs) (selected as? RepoTab)?.onTabSelected()
            }
        }
    }

    private fun addPlusTab() {
        tabPane.addTab(PLUS_TITLE, JPanel())
    }

    fun addTab(panel: JPanel, title: String) {
        val plusIdx = (0 until tabPane.tabCount).indexOfFirst { tabPane.getTitleAt(it) == PLUS_TITLE }
        val insertIdx = if (plusIdx < 0) tabPane.tabCount else plusIdx
        tabPane.insertTab(title, null, panel, null, insertIdx)
        tabPane.setTabComponentAt(insertIdx, CloseableTabHeader(title, panel))
        tabPane.selectedComponent = panel
    }

    /** Custom tab header with an X close button. */
    private inner class CloseableTabHeader(title: String, panel: JPanel) :
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {

        init {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)

            add(JLabel(title).apply { border = BorderFactory.createEmptyBorder(0, 0, 0, 2) })

            add(JButton("×").apply {
                preferredSize     = Dimension(16, 16)
                isBorderPainted   = false
                isContentAreaFilled = false
                isFocusPainted    = false
                toolTipText       = "Close tab"
                font              = font.deriveFont(12f)
                foreground        = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                addActionListener { closeTab(panel) }
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseEntered(e: java.awt.event.MouseEvent) {
                        foreground = UIManager.getColor("Label.foreground") ?: Color.WHITE
                    }
                    override fun mouseExited(e: java.awt.event.MouseEvent) {
                        foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                    }
                })
            })
        }
    }

    fun addTab(repo: RepoTab, save: Boolean = true) {
        addTab(repo, repo.tabTitle)
        RecentReposService.record(repo.tabTitle, repo.path)
        if (save) {
            Settings.openTabs.put(JSONObject().apply {
                put("name", repo.tabTitle)
                put("path", java.io.File(repo.path).absolutePath)
            })
            Settings.save()
        }
    }

    fun closeTab(component: JPanel) {
        val idx = tabPane.indexOfComponent(component)
        if (idx < 0) return
        val wasSelected = tabPane.selectedIndex == idx

        // Suppress the ChangeListener: removing the tab transiently lands selection on the
        // "+" sentinel, which would otherwise auto-spawn a new Add/Clone tab.
        suppressTabChange = true
        try {
            tabPane.removeTabAt(idx)
            if (component is RepoTab) {
                val closed = java.io.File(component.path).absoluteFile.toPath().normalize()
                Settings.openTabs.removeAll { java.io.File((it as JSONObject).getString("path")).absoluteFile.toPath().normalize() == closed }
                Settings.save()
            }

            val realIdxs = (0 until tabPane.tabCount).filter { tabPane.getTitleAt(it) != PLUS_TITLE }
            if (realIdxs.isEmpty()) {
                // Nothing left but "+": open a fresh Add/Clone tab.
                addNewCloneTab()
            } else if (wasSelected && tabPane.getTitleAt(tabPane.selectedIndex) == PLUS_TITLE) {
                // We closed the rightmost real tab; selection fell onto "+". Land on the
                // nearest remaining real tab instead.
                tabPane.selectedIndex = realIdxs.last()
            }
        } finally {
            suppressTabChange = false
        }

        // Listener was suppressed during the swap — load data for the now-current tab.
        (tabPane.selectedComponent as? RepoTab)?.onTabSelected()
    }

    private fun moveTab(fromIndex: Int, toIndex: Int) {
        val component = tabPane.getComponentAt(fromIndex)
        val title = tabPane.getTitleAt(fromIndex)
        val tabComponent = tabPane.getTabComponentAt(fromIndex)
        val wasSelected = tabPane.selectedIndex == fromIndex
        suppressTabChange = true
        try {
            tabPane.removeTabAt(fromIndex)
            tabPane.insertTab(title, null, component, null, toIndex)
            tabPane.setTabComponentAt(toIndex, tabComponent)
            if (wasSelected) tabPane.selectedIndex = toIndex
        } finally {
            suppressTabChange = false
        }
    }

    fun selectTab(component: JPanel) {
        val idx = tabPane.indexOfComponent(component)
        if (idx >= 0) tabPane.selectedIndex = idx
    }

    /** Opens an Add/Clone/Create tab (triggered by the "+" sentinel or New Tab shortcut). */
    fun addNewCloneTab(): AddCloneTab {
        val newTab = AddCloneTab(this)
        addTab(newTab, newTab.tabTitle)
        return newTab
    }

    /**
     * Shows a native directory picker and opens the chosen repo as a RepoTab.
     * Called from File > Open Repository…
     */
    fun openRepositoryFromPicker() {
        val win = SwingUtilities.getWindowAncestor(this)
        val dir = NativeFileChooser.chooseDirectory(win, "Open Repository") ?: return
        val repo = RepoTab(dir.absolutePath, dir.name)
        addTab(repo)
        selectTab(repo)
    }

    companion object {
        const val PLUS_TITLE = "+"
    }
}
