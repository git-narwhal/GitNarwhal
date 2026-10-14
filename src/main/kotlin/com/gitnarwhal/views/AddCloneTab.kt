package com.gitnarwhal.views

import com.gitnarwhal.backend.Git
import com.gitnarwhal.components.AddCloneTab.AddTab
import com.gitnarwhal.components.AddCloneTab.CloneTab
import com.gitnarwhal.components.AddCloneTab.CreateTab
import com.gitnarwhal.utils.Command
import com.gitnarwhal.utils.NativeFileChooser
import com.gitnarwhal.utils.OS
import com.gitnarwhal.utils.RecentReposService
import com.gitnarwhal.utils.RecentReposService.RecentFolder
import com.gitnarwhal.utils.RecentReposService.RecentRepo
import com.gitnarwhal.utils.Settings
import com.gitnarwhal.utils.save
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.materialdesign.MaterialDesign
import org.kordamp.ikonli.swing.FontIcon
import java.awt.*
import java.awt.event.*
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class AddCloneTab(val mainView: MainView) : JPanel(BorderLayout()) {

    val tabTitle: String = "New Tab"

    private val cards     = CardLayout()
    private val container = JPanel(cards)

    val cloneTab  = CloneTab(this)
    val addTab    = AddTab(this)
    val createTab = CreateTab(this)

    private val localBtn  = NavTabButton("Local",  MaterialDesign.MDI_LAPTOP)
    private val remoteBtn = NavTabButton("Remote", MaterialDesign.MDI_CLOUD)
    private val cloneBtn  = ActionNavButton("Clone",  MaterialDesign.MDI_DOWNLOAD)
    private val addBtn    = ActionNavButton("Add",    MaterialDesign.MDI_FOLDER_PLUS)
    private val createBtn = ActionNavButton("Create", MaterialDesign.MDI_PLUS)

    val activateCloneTab:  JButton get() = cloneBtn
    val activateAddTab:    JButton get() = addBtn
    val activateCreateTab: JButton get() = createBtn

    // ── Local repos list ──────────────────────────────────────────────────────

    interface LocalItem

    data class RepoEntry(
        val repo: RecentRepo,
        var branch: String? = null,
        var modifiedCount: Int? = null,
        var missing: Boolean = false,
        val folderId: String? = null
    ) : LocalItem

    data class FolderEntry(
        val id: String,
        var name: String,
        var expanded: Boolean = true
    ) : LocalItem

    private val repoListModel     = DefaultListModel<LocalItem>()
    private val repoList          = JList(repoListModel)
    private val searchField       = JTextField()
    private var allItems          = listOf<LocalItem>()
    private val folderExpandState = mutableMapOf<String, Boolean>()

    init {
        container.add(buildLocalPanel(), CARD_LOCAL)
        container.add(JPanel().apply { add(JLabel("Remote not yet implemented")) }, CARD_REMOTE)
        container.add(cloneTab,  CARD_CLONE)
        container.add(addTab,    CARD_ADD)
        container.add(createTab, CARD_CREATE)

        // ── Nav bar ───────────────────────────────────────────────────────────
        val navBar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createMatteBorder(0, 0, 1, 0,
                UIManager.getColor("Separator.foreground") ?: Color(0x44_44_44))
        }
        navBar.add(Box.createHorizontalStrut(8))
        navBar.add(localBtn)
        navBar.add(remoteBtn)
        navBar.add(Box.createHorizontalStrut(12))

        val sep = JSeparator(SwingConstants.VERTICAL).apply {
            maximumSize = Dimension(1, 40)
            preferredSize = Dimension(1, 40)
        }
        navBar.add(sep)
        navBar.add(Box.createHorizontalStrut(12))

        navBar.add(cloneBtn)
        navBar.add(addBtn)
        navBar.add(createBtn)
        navBar.add(Box.createHorizontalGlue())

        localBtn.addActionListener  { switchTo(CARD_LOCAL) }
        remoteBtn.addActionListener { switchTo(CARD_REMOTE) }
        cloneBtn.addActionListener  { switchTo(CARD_CLONE) }
        addBtn.addActionListener    { switchTo(CARD_ADD) }
        createBtn.addActionListener { switchTo(CARD_CREATE) }

        add(navBar,    BorderLayout.NORTH)
        add(container, BorderLayout.CENTER)

        switchTo(CARD_LOCAL)
        loadRecentRepos()
    }

    // ── Local panel ───────────────────────────────────────────────────────────

    private fun buildLocalPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 0))

        // Search bar
        val searchPanel = JPanel(BorderLayout(6, 0)).apply {
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        }
        searchField.toolTipText = "Search repositories"
        searchPanel.add(JLabel("Search:"), BorderLayout.WEST)
        searchPanel.add(searchField, BorderLayout.CENTER)
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterList()
            override fun removeUpdate(e: DocumentEvent) = filterList()
            override fun changedUpdate(e: DocumentEvent) = filterList()
        })

        // Repo list — variable cell height (folders ~32px, repos ~56px)
        repoList.cellRenderer = LocalListRenderer()
        repoList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        repoList.fixedCellHeight = -1
        repoList.border = BorderFactory.createEmptyBorder()

        repoList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                handleMouse(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                handleMouse(e)
            }
            override fun mouseClicked(e: MouseEvent) {
                val idx = repoList.locationToIndex(e.point)
                if (idx < 0) return
                val item = repoListModel.getElementAt(idx)
                if (item is FolderEntry) {
                    // toggle expand on single click
                    item.expanded = !item.expanded
                    folderExpandState[item.id] = item.expanded
                    rebuildDisplayList()
                } else if (e.clickCount == 2 && item is RepoEntry) {
                    openSelected()
                }
            }
            private fun handleMouse(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val idx = repoList.locationToIndex(e.point)
                if (idx >= 0) repoList.selectedIndex = idx
                val item = if (idx >= 0) repoListModel.getElementAt(idx) else null
                showContextMenu(e, item)
            }
        })

        // Keyboard shortcuts
        fun bind(ks: KeyStroke, id: String, action: () -> Unit) {
            repoList.getInputMap(JComponent.WHEN_FOCUSED).put(ks, id)
            repoList.actionMap.put(id, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = action()
            })
        }
        val CTRL  = InputEvent.CTRL_DOWN_MASK
        val ALT   = InputEvent.ALT_DOWN_MASK
        val SHIFT = InputEvent.SHIFT_DOWN_MASK
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,  0),          "open")     { (repoList.selectedValue as? RepoEntry)?.let { doOpen(it) } }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0),          "delete")   { repoList.selectedValue?.let { doDelete(it) } }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F2,     0),          "rename")   { repoList.selectedValue?.let { doRename(it) } }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_N,      CTRL),       "new-repo") { doNewRepository() }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_R,      ALT or SHIFT), "remote") { (repoList.selectedValue as? RepoEntry)?.let { doOpenRemote(it.repo.path) } }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_T,      ALT or SHIFT), "term")   { (repoList.selectedValue as? RepoEntry)?.let { doOpenTerminal(it.repo.path) } }

        val scroll = JScrollPane(repoList).apply {
            border = BorderFactory.createEmptyBorder()
        }

        // Bottom bar
        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            border = BorderFactory.createMatteBorder(1, 0, 0, 0,
                UIManager.getColor("Separator.foreground") ?: Color(0x44_44_44))
        }
        val refreshBtn = JButton("Refresh").apply {
            isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { loadRecentRepos() }
        }
        val terminalBtn = JButton("Open in Terminal").apply {
            isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener {
                (repoList.selectedValue as? RepoEntry)?.let { doOpenTerminal(it.repo.path) }
            }
        }
        bottomBar.add(refreshBtn)
        bottomBar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(1, 16) })
        bottomBar.add(terminalBtn)

        panel.add(searchPanel, BorderLayout.NORTH)
        panel.add(scroll,      BorderLayout.CENTER)
        panel.add(bottomBar,   BorderLayout.SOUTH)
        return panel
    }

    private fun loadRecentRepos() {
        val repos   = RecentReposService.getAll()
        val folders = RecentReposService.getFolders()

        val items = mutableListOf<LocalItem>()

        // Ungrouped repos first (no folderId), most-recent-first order preserved
        repos.filter { it.folderId == null }.forEach { repo ->
            items += RepoEntry(repo, missing = !File(repo.path).isDirectory)
        }

        // Folders with their repos
        folders.forEach { folder ->
            val expanded = folderExpandState.getOrDefault(folder.id, true)
            val folderEntry = FolderEntry(folder.id, folder.name, expanded)
            items += folderEntry
            if (expanded) {
                repos.filter { it.folderId == folder.id }.forEach { repo ->
                    items += RepoEntry(repo, missing = !File(repo.path).isDirectory, folderId = folder.id)
                }
            }
        }

        allItems = items
        filterList()

        // async: load branch + modified count for each visible repo entry
        items.filterIsInstance<RepoEntry>().forEach { if (!it.missing) loadRepoMeta(it) }
    }

    private fun rebuildDisplayList() {
        // Rebuilds the visible list from allItems respecting current expand states,
        // without reloading from service (used for fold/unfold toggle).
        val visible = mutableListOf<LocalItem>()
        var skipFolderId: String? = null
        for (item in allItems) {
            when {
                item is FolderEntry -> {
                    item.expanded = folderExpandState.getOrDefault(item.id, item.expanded)
                    visible += item
                    skipFolderId = if (item.expanded) null else item.id
                }
                item is RepoEntry && skipFolderId != null && item.folderId == skipFolderId -> { /* skip — folder collapsed */ }
                else -> visible += item
            }
        }
        repoListModel.clear()
        visible.forEach { repoListModel.addElement(it) }
    }

    private fun filterList() {
        val query = searchField.text.trim().lowercase()
        if (query.isEmpty()) {
            rebuildDisplayList()
            return
        }
        // When filtering, flatten: show matching repos regardless of folder structure
        repoListModel.clear()
        allItems.filterIsInstance<RepoEntry>()
            .filter { it.repo.name.lowercase().contains(query) || it.repo.path.lowercase().contains(query) }
            .forEach { repoListModel.addElement(it) }
    }

    private fun loadRepoMeta(entry: RepoEntry) {
        object : SwingWorker<Pair<String?, Int?>, Void>() {
            override fun doInBackground(): Pair<String?, Int?> {
                return try {
                    val git = Git(entry.repo.path)
                    val branchOut = git.currentBranch().output.trim().ifBlank { null }
                    val statusOut = git.status().output
                    val modified  = statusOut.lines()
                        .filter { it.length >= 3 && !it.startsWith("##") }
                        .count { it[0] != '?' || it[1] != '?' }
                    branchOut to if (modified > 0) modified else null
                } catch (_: Exception) { null to null }
            }
            override fun done() {
                val (branch, count) = try { get() } catch (_: Exception) { null to null }
                entry.branch = branch
                entry.modifiedCount = count
                val idx = repoListModel.indexOf(entry)
                if (idx >= 0) repoListModel.set(idx, entry)
            }
        }.execute()
    }

    // ── Context menu ──────────────────────────────────────────────────────────

    private fun showContextMenu(e: MouseEvent, item: LocalItem?) {
        val CTRL  = InputEvent.CTRL_DOWN_MASK
        val ALT   = InputEvent.ALT_DOWN_MASK
        val SHIFT = InputEvent.SHIFT_DOWN_MASK

        fun item(text: String, accel: KeyStroke? = null, enabled: Boolean = true, action: () -> Unit): JMenuItem {
            return JMenuItem(text).also {
                if (accel != null) it.accelerator = accel
                it.isEnabled = enabled
                it.addActionListener { action() }
            }
        }

        val menu = JPopupMenu()

        when {
            item is RepoEntry -> {
                menu.add(item("Open", enabled = !item.missing) { doOpen(item) })
                menu.addSeparator()
                menu.add(item("Show in Explorer", enabled = !item.missing) { doShowInExplorer(item.repo.path) })
                menu.add(item("Remote",
                    accel   = KeyStroke.getKeyStroke(KeyEvent.VK_R, ALT or SHIFT),
                    enabled = !item.missing
                ) { doOpenRemote(item.repo.path) })
                menu.add(item("Terminal",
                    accel   = KeyStroke.getKeyStroke(KeyEvent.VK_T, ALT or SHIFT),
                    enabled = !item.missing
                ) { doOpenTerminal(item.repo.path) })
                menu.addSeparator()

                // Move to folder
                val folders = RecentReposService.getFolders()
                if (folders.isNotEmpty()) {
                    val moveMenu = JMenu("Move to Folder")
                    if (item.folderId != null) {
                        moveMenu.add(JMenuItem("(No Folder)").also {
                            it.addActionListener { doMoveToFolder(item, null) }
                        })
                        moveMenu.addSeparator()
                    }
                    folders.filter { it.id != item.folderId }.forEach { folder ->
                        moveMenu.add(JMenuItem(folder.name).also {
                            it.addActionListener { doMoveToFolder(item, folder.id) }
                        })
                    }
                    menu.add(moveMenu)
                    menu.addSeparator()
                }

                menu.add(item("Delete",
                    accel = KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)
                ) { doDelete(item) })
                menu.add(item("Rename",
                    accel = KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0)
                ) { doRename(item) })
                menu.addSeparator()
                menu.add(item("New Repository",
                    accel = KeyStroke.getKeyStroke(KeyEvent.VK_N, CTRL)
                ) { doNewRepository() })
                menu.add(item("New Folder") { doNewFolder() })
            }

            item is FolderEntry -> {
                menu.add(item("Rename",
                    accel = KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0)
                ) { doRename(item) })
                menu.add(item("Delete",
                    accel = KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)
                ) { doDelete(item) })
                menu.addSeparator()
                menu.add(item("New Folder") { doNewFolder() })
                menu.add(item("New Repository",
                    accel = KeyStroke.getKeyStroke(KeyEvent.VK_N, CTRL)
                ) { doNewRepository() })
            }

            else -> {
                // Empty area
                menu.add(item("New Repository",
                    accel = KeyStroke.getKeyStroke(KeyEvent.VK_N, CTRL)
                ) { doNewRepository() })
                menu.add(item("New Folder") { doNewFolder() })
            }
        }

        menu.show(repoList, e.x, e.y)
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun openSelected() {
        val entry = repoList.selectedValue as? RepoEntry ?: return
        doOpen(entry)
    }

    private fun doOpen(entry: RepoEntry) {
        if (entry.missing) return
        val repo = RepoTab(entry.repo.path, entry.repo.name)
        mainView.addTab(repo)
        mainView.selectTab(repo)
    }

    private fun doShowInExplorer(path: String) =
        Thread { (OS.EXPLORER + path).execute() }.start()

    private fun doOpenRemote(path: String) = Thread {
        val url = Git(path).remoteUrl().output.trim()
        if (url.isNotBlank()) (OS.BROWSER + url).execute()
    }.start()

    private fun doOpenTerminal(path: String) = Thread {
        when (Settings.terminalPreset) {
            "gitbash" -> {
                val gitBash = Git.GIT.removeSuffix("cmd\\git.exe") + "git-bash.exe"
                if (java.nio.file.Files.exists(java.nio.file.Path.of(gitBash)))
                    Command(gitBash).execute(path)
                else
                    Command("git-bash").execute(path)
            }
            "pwsh"           -> Command("pwsh").execute(path)
            "powershell"     -> Command("powershell").execute(path)
            "wt"             -> Command("wt", "-d", path).execute()
            "cmd"            -> Command("cmd").execute(path)
            "terminal"       -> Command("open", "-a", "Terminal", path).execute()
            "iterm2"         -> Command("open", "-a", "iTerm", path).execute()
            "gnome-terminal" -> Command("gnome-terminal").execute(path)
            "konsole"        -> Command("konsole").execute(path)
            "xterm"          -> Command("xterm").execute(path)
            "custom" -> {
                val custom = Settings.terminalCommand.trim()
                if (custom.isNotBlank()) Command(custom.replace("\$REPO", path)).execute(path)
                else OS.TERMINAL.execute(path)
            }
            else -> OS.TERMINAL.execute(path)
        }
    }.start()

    private fun doDelete(item: LocalItem) {
        when (item) {
            is RepoEntry -> {
                RecentReposService.remove(item.repo.path)
                loadRecentRepos()
            }
            is FolderEntry -> {
                val confirm = JOptionPane.showConfirmDialog(
                    this,
                    "Delete folder \"${item.name}\"?\nContained repositories will be moved to the root.",
                    "Delete Folder",
                    JOptionPane.YES_NO_OPTION
                )
                if (confirm == JOptionPane.YES_OPTION) {
                    folderExpandState.remove(item.id)
                    RecentReposService.removeFolder(item.id)
                    loadRecentRepos()
                }
            }
        }
    }

    private fun doRename(item: LocalItem) {
        when (item) {
            is RepoEntry -> {
                val newName = JOptionPane.showInputDialog(
                    this, "New name:", "Rename Repository",
                    JOptionPane.PLAIN_MESSAGE, null, null, item.repo.name
                )?.toString()?.trim()?.ifBlank { null } ?: return
                RecentReposService.renameRepo(item.repo.path, newName)
                // Also update open tab header and saved open-tabs name if present
                updateOpenTabTitle(item.repo.path, newName)
                loadRecentRepos()
            }
            is FolderEntry -> {
                val newName = JOptionPane.showInputDialog(
                    this, "New name:", "Rename Folder",
                    JOptionPane.PLAIN_MESSAGE, null, null, item.name
                )?.toString()?.trim()?.ifBlank { null } ?: return
                RecentReposService.renameFolder(item.id, newName)
                loadRecentRepos()
            }
        }
    }

    private fun doNewRepository() = switchTo(CARD_CLONE)

    private fun doNewFolder() {
        val name = JOptionPane.showInputDialog(
            this, "Folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE
        )?.trim()?.ifBlank { null } ?: return
        RecentReposService.addFolder(name)
        loadRecentRepos()
    }

    private fun doMoveToFolder(entry: RepoEntry, folderId: String?) {
        RecentReposService.setRepoFolder(entry.repo.path, folderId)
        loadRecentRepos()
    }

    private fun updateOpenTabTitle(path: String, newName: String) {
        for (i in 0 until mainView.tabPane.tabCount) {
            val comp = mainView.tabPane.getComponentAt(i)
            if (comp is RepoTab && comp.path == path) {
                val header = mainView.tabPane.getTabComponentAt(i)
                if (header is JPanel) {
                    (header.getComponent(0) as? JLabel)?.also {
                        it.text = newName
                        header.revalidate()
                        header.repaint()
                    }
                }
                // Update saved open-tabs entry
                val openTabs = Settings.openTabs
                for (j in 0 until openTabs.length()) {
                    val obj = openTabs.optJSONObject(j) ?: continue
                    if (obj.optString("path") == path) { obj.put("name", newName); break }
                }
                Settings.save()
                break
            }
        }
    }

    // ── Custom list cell renderer ─────────────────────────────────────────────

    private inner class LocalListRenderer : ListCellRenderer<LocalItem> {

        // ── Folder cell ──────────────────────────────────────────────────────
        private val folderIcon      = FontIcon.of(MaterialDesign.MDI_FOLDER, 18, Color.GRAY)
        private val folderNameLabel = JLabel().apply { font = font.deriveFont(Font.BOLD, 12f) }
        private val arrowLabel      = JLabel().apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            preferredSize = Dimension(14, 14)
        }
        private val folderCell = JPanel(BorderLayout(6, 0)).apply {
            border = BorderFactory.createEmptyBorder(0, 10, 0, 12)
            preferredSize = Dimension(0, 32)
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).also { it.isOpaque = false }
            left.add(arrowLabel)
            left.add(JLabel(folderIcon))
            add(left,            BorderLayout.WEST)
            add(folderNameLabel, BorderLayout.CENTER)
        }

        // ── Repo cell ────────────────────────────────────────────────────────
        private val nameLabel     = JLabel()
        private val pathLabel     = JLabel()
        private val branchLabel   = JLabel()
        private val badgeLabel    = JLabel()
        private val warningLabel  = JLabel("Repository Moved or Deleted")
        private val repoIcon      = FontIcon.of(MaterialDesign.MDI_FOLDER, 28, Color.GRAY)

        private val repoCell = JPanel(BorderLayout(10, 0)).apply {
            preferredSize = Dimension(0, 56)
        }
        private val textPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
        }
        private val topRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }

        init {
            nameLabel.font    = nameLabel.font.deriveFont(Font.BOLD, 13f)
            pathLabel.font    = pathLabel.font.deriveFont(11f)
            branchLabel.font  = branchLabel.font.deriveFont(11f)
            badgeLabel.font   = badgeLabel.font.deriveFont(Font.BOLD, 10f)
            warningLabel.font = warningLabel.font.deriveFont(11f)
            warningLabel.foreground = Color(0xE5_73_73)

            topRow.add(nameLabel)
            topRow.add(branchLabel)
            topRow.add(badgeLabel)
            textPanel.add(topRow)
            textPanel.add(warningLabel)
            textPanel.add(pathLabel)

            repoCell.add(JLabel(repoIcon), BorderLayout.WEST)
            repoCell.add(textPanel,        BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out LocalItem>, value: LocalItem,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val bg     = if (isSelected) list.selectionBackground else list.background
            val accent = UIManager.getColor("Component.accentColor") ?: Color(0x4F_C3_F7)

            return when (value) {
                is FolderEntry -> {
                    val folderBg = if (isSelected) list.selectionBackground
                                   else (UIManager.getColor("Panel.background") ?: list.background)
                                       .let { Color(it.red, it.green, it.blue, 255) }
                    folderCell.background = folderBg
                    (folderCell.getComponent(0) as JPanel).background = folderBg  // left panel
                    folderNameLabel.text      = value.name
                    folderNameLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
                    arrowLabel.text           = if (value.expanded) "▼" else "▶"
                    arrowLabel.foreground     = if (isSelected) list.selectionForeground else Color.GRAY
                    folderIcon.iconColor      = if (isSelected) accent else Color(0x78_90_AB)
                    folderCell
                }
                is RepoEntry -> {
                    val indented = value.folderId != null
                    repoCell.border = BorderFactory.createEmptyBorder(6, if (indented) 28 else 12, 6, 12)
                    repoCell.background = bg
                    textPanel.background = bg
                    topRow.background    = bg

                    nameLabel.text       = value.repo.name
                    nameLabel.foreground = if (value.missing) Color.GRAY else list.foreground

                    pathLabel.text       = value.repo.path
                    pathLabel.foreground = Color.GRAY

                    if (value.branch != null) {
                        branchLabel.text       = "  ${value.branch}  "
                        branchLabel.foreground = accent
                        branchLabel.border     = BorderFactory.createLineBorder(accent, 1, true)
                        branchLabel.isVisible  = true
                    } else {
                        branchLabel.isVisible = false
                    }

                    if (value.modifiedCount != null && value.modifiedCount!! > 0) {
                        badgeLabel.text       = " ${value.modifiedCount} "
                        badgeLabel.foreground = Color.WHITE
                        badgeLabel.background = Color(0xE5_A0_00)
                        badgeLabel.isOpaque   = true
                        badgeLabel.border     = BorderFactory.createEmptyBorder(1, 4, 1, 4)
                        badgeLabel.isVisible  = true
                    } else {
                        badgeLabel.isVisible = false
                    }

                    warningLabel.isVisible = value.missing
                    repoIcon.iconColor     = if (value.missing) Color.GRAY
                                             else if (isSelected) accent else Color(0x78_90_AB)
                    repoCell
                }
                else -> JLabel("?")
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun switchTab(buttonOrCard: Any) {
        switchTo(when (buttonOrCard) {
            cloneBtn  -> CARD_CLONE
            addBtn    -> CARD_ADD
            createBtn -> CARD_CREATE
            is String -> buttonOrCard
            else      -> CARD_LOCAL
        })
    }

    fun browseForPath(owner: Component, onPicked: (File) -> Unit = {}) {
        val win = SwingUtilities.getWindowAncestor(owner)
        object : SwingWorker<File?, Void>() {
            override fun doInBackground() = NativeFileChooser.chooseDirectory(win, "Select Folder")
            override fun done() {
                val dir = try { get() } catch (_: Exception) { return } ?: return
                addTab.pathField.text    = dir.absolutePath
                createTab.pathField.text = dir.absolutePath
                onPicked(dir)
            }
        }.execute()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun switchTo(card: String) {
        cards.show(container, card)
        val isLocal  = card == CARD_LOCAL
        val isRemote = card == CARD_REMOTE
        localBtn.setNavSelected(isLocal)
        remoteBtn.setNavSelected(isRemote)
        listOf(cloneBtn, addBtn, createBtn).forEach { it.setNavSelected(false) }
        when (card) {
            CARD_CLONE  -> cloneBtn.setNavSelected(true)
            CARD_ADD    -> addBtn.setNavSelected(true)
            CARD_CREATE -> createBtn.setNavSelected(true)
        }
        if (card == CARD_LOCAL) loadRecentRepos()
    }

    // ── Nav buttons ───────────────────────────────────────────────────────────

    private inner class NavTabButton(label: String, ikon: Ikon) : JButton() {
        private val fontIcon = FontIcon.of(ikon, 22, unselectedColor())
        init {
            text = label; icon = fontIcon
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition   = SwingConstants.BOTTOM
            isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
            preferredSize = Dimension(80, 58); maximumSize = Dimension(80, 58)
            font = font.deriveFont(11f); foreground = unselectedColor()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        fun setNavSelected(sel: Boolean) {
            val color = if (sel) accentColor() else unselectedColor()
            fontIcon.iconColor = color; foreground = color
            putClientProperty("selected", sel); repaint()
        }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (getClientProperty("selected") == true) {
                g.color = accentColor()
                g.fillRect(0, height - 3, width, 3)
            }
        }
        private fun accentColor()     = UIManager.getColor("Component.accentColor") ?: Color(0x4F_C3_F7)
        private fun unselectedColor() = UIManager.getColor("Label.foreground") ?: Color.LIGHT_GRAY
    }

    private inner class ActionNavButton(label: String, ikon: Ikon) : JButton() {
        private val fontIcon = FontIcon.of(ikon, 20, unselectedColor())
        init {
            text = label; icon = fontIcon
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition   = SwingConstants.BOTTOM
            isBorderPainted = false; isContentAreaFilled = false; isFocusPainted = false
            preferredSize = Dimension(70, 58); maximumSize = Dimension(70, 58)
            font = font.deriveFont(11f); foreground = unselectedColor()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        fun setNavSelected(sel: Boolean) {
            val color = if (sel) accentColor() else unselectedColor()
            fontIcon.iconColor = color; foreground = color
            putClientProperty("selected", sel); repaint()
        }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (getClientProperty("selected") == true) {
                g.color = accentColor()
                g.fillRect(0, height - 3, width, 3)
            }
        }
        private fun accentColor()     = UIManager.getColor("Component.accentColor") ?: Color(0x4F_C3_F7)
        private fun unselectedColor() = UIManager.getColor("Label.foreground") ?: Color.LIGHT_GRAY
    }

    companion object {
        const val CARD_LOCAL  = "local"
        const val CARD_REMOTE = "remote"
        const val CARD_CLONE  = "clone"
        const val CARD_ADD    = "add"
        const val CARD_CREATE = "create"
    }
}
