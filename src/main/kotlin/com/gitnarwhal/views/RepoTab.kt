package com.gitnarwhal.views

import com.gitnarwhal.backend.Commit
import com.gitnarwhal.backend.Git
import com.gitnarwhal.backend.RefInfo
import com.gitnarwhal.backend.RefType
import com.gitnarwhal.components.CommitDataPanel
import com.gitnarwhal.components.CommitDescriptionCell
import com.gitnarwhal.components.CommitGraphCell
import com.gitnarwhal.components.ProgressOverlay
import com.gitnarwhal.components.PullOverlay
import com.gitnarwhal.components.PushOverlay
import com.gitnarwhal.utils.Command
import com.gitnarwhal.utils.NativeFileChooser
import com.gitnarwhal.utils.OS
import com.gitnarwhal.utils.Settings
import com.gitnarwhal.utils.save
import org.json.JSONObject
import org.kordamp.ikonli.Ikon
import org.kordamp.ikonli.materialdesign.MaterialDesign
import org.kordamp.ikonli.swing.FontIcon
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class RepoTab(var path: String, val tabTitle: String) : JPanel(BorderLayout()) {

    val git: Git = Git(path)

    // ── Commit table ──────────────────────────────────────────────────────────
    private val commitList:     MutableList<Commit> = mutableListOf()
    private val filteredCommits: MutableList<Commit> = mutableListOf()

    private val commitTableModel = object : AbstractTableModel() {
        private val cols = arrayOf("Graph", "Description", "Date", "Committer", "Commit")
        override fun getRowCount()                    = filteredCommits.size
        override fun getColumnCount()                 = cols.size
        override fun getColumnName(col: Int)          = cols[col]
        override fun getColumnClass(col: Int)         = if (col <= 1) Commit::class.java else String::class.java
        override fun isCellEditable(row: Int, col: Int) = false
        override fun getValueAt(row: Int, col: Int): Any {
            val c = filteredCommits[row]
            return when (col) {
                0 -> c; 1 -> c; 2 -> c.committerDate; 3 -> c.committer
                4 -> if (c.hash == UNCOMMITTED_HASH) "" else c.hash
                else -> ""
            }
        }
    }

    val commitTable: JTable = JTable(commitTableModel).apply {
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        rowHeight = 22
        columnModel.getColumn(0).cellRenderer = CommitGraphCell()
        columnModel.getColumn(1).cellRenderer = CommitDescriptionCell()
    }
    private val commitScrollPane = JScrollPane(commitTable)

    private val commitDataPanel = CommitDataPanel(this)

    // ── Commit file list + diff ───────────────────────────────────────────────
    private val commitFileModel  = DefaultListModel<String>()
    private val commitFileList   = JList(commitFileModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val commitDiffScroll = JScrollPane()
    private val commitFileLabel  = JLabel("0 files")
    private var currentCommit: Commit? = null

    // ── Branch tree (unified sidebar) ──────────────────────────────────────────
    private data class BranchInfo(
        val fullName: String, val leafName: String,
        val isActive: Boolean, val tracking: String? = null,
        val ahead: Int = 0, val behind: Int = 0,
        val isInWorktree: Boolean = false
    )
    private data class TagInfo(val name: String)
    private data class StashInfo(val index: Int, val description: String)
    private data class WorktreeInfo(
        val path: String,
        val branch: String?,   // null if bare / detached
        val head: String,
        val isMain: Boolean
    )

    // ── Submodules ────────────────────────────────────────────────────────────
    internal data class SubmoduleInfo(
        val path: String, val name: String, val hash: String,
        // isDirty       = uncommitted changes in the submodule's working tree (user should commit)
        // differentCommit = checked-out commit differs from the one the superproject records
        val isDirty: Boolean, val differentCommit: Boolean, val isUninitialized: Boolean
    ) {
        /** Submodule needs the user's attention. */
        val needsAttention get() = isDirty || differentCommit
    }
    /** Marker for intermediate folder nodes in the submodule subtree. */
    private data class SubmoduleFolderNode(val name: String)

    private val branchRoot         = DefaultMutableTreeNode("root")
    private val localBranchesNode  = DefaultMutableTreeNode("BRANCHES")
    private val tagsNode           = DefaultMutableTreeNode("TAGS")
    private val remoteBranchesNode = DefaultMutableTreeNode("REMOTES")
    private val stashesNode        = DefaultMutableTreeNode("STASHES")
    private val submodulesNode     = DefaultMutableTreeNode("SUBMODULES")
    private val worktreesNode      = DefaultMutableTreeNode("WORKTREES")
    private var isHeadDetached     = false
    private var restoringExpansion = false
    // Expanded sub-folder paths (below section level) kept in memory across refreshes
    private val expandedFolderPaths = mutableSetOf<List<Any>>()

    private val allSubmodules      = mutableListOf<SubmoduleInfo>()
    private val branchTreeModel    = DefaultTreeModel(branchRoot)
    private val branchTree: JTree

    // ── File Status models + widgets — MUST be declared before init ──────────
    private val stagedModel   = DefaultListModel<String>()
    private val unstagedModel = DefaultListModel<String>()

    private val stagedList   = JList(stagedModel).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    }
    private val unstagedList = JList(unstagedModel).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    }
    private val stagedHeaderLabel   = JLabel("Staged files (0 files)")
    private val unstagedHeaderLabel = JLabel("Unstaged files (0 files)")

    private val diffScrollPane   = JScrollPane()
    private val diffFileNameLabel = JLabel(" ", SwingConstants.LEFT)

    private val authorLabel = JLabel(" ").apply {
        border     = BorderFactory.createEmptyBorder(0, 0, 4, 0)
        font       = font.deriveFont(Font.PLAIN, 11f)
        foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
    }

    private val commitMsgField          = JTextArea(4, 40).apply { lineWrap = true; wrapStyleWord = true }
    private val amendCheckBox           = JCheckBox("Amend latest commit")
    private val pushImmediatelyCheckBox = JCheckBox("Push changes immediately")

    // Tracking branch of the current local branch — populated by refresh()
    private var currentBranchName : String = ""
    private var trackingRemote    : String? = null
    private var trackingBranchRef : String? = null

    // ── Badge toolbar buttons ─────────────────────────────────────────────────
    private val commitBtn = BadgeButton(MaterialDesign.MDI_CHECK_CIRCLE, "Commit") { showFileStatus() }
    private val pullBtn   = BadgeButton(MaterialDesign.MDI_ARROW_DOWN_BOLD, "Pull") { pull() }
    private val pushBtn   = BadgeButton(MaterialDesign.MDI_ARROW_UP_BOLD, "Push")  { push() }

    // ── Commit search ─────────────────────────────────────────────────────────
    private val searchField = JTextField().apply {
        putClientProperty("JTextField.placeholderText", "Search commits…")
    }
    private val searchFilterCombo = JComboBox(arrayOf("All", "Message", "Author", "Hash")).apply {
        toolTipText = "Search field to filter on"
    }

    // ── Workspace nav buttons (File Status / History) ──────────────────────────
    private lateinit var fileStatusItem: JButton
    private lateinit var historyItem:    JButton

    // ── Progress bars ─────────────────────────────────────────────────────────
    // Always visible at 0% (so they never shift the layout); turn indeterminate while busy.
    private fun miniBar() = JProgressBar().apply {
        isIndeterminate = false
        value           = 0
        isStringPainted = false
        border          = null
        preferredSize   = Dimension(0, 3)
        maximumSize     = Dimension(Int.MAX_VALUE, 3)
    }

    private fun JProgressBar.setBusy(busy: Boolean) {
        isIndeterminate = busy
        if (!busy) value = 0
    }

    private val graphBar      = miniBar()   // under the commit search bar
    private val sidebarBar    = miniBar()   // bottom of the unified sidebar tree

    // ── Blame button (in diff top bar) ────────────────────────────────────────
    private val blameBtn      = JButton("Blame").apply { isVisible = false }
    private var currentDiffFile   = ""
    private var currentDiffStaged = false
    private var showingBlame      = false

    // ── Conflict banner ───────────────────────────────────────────────────────
    private val conflictBannerLabel = JLabel()
    private val conflictContinueBtn = JButton("Continue")
    private val conflictAbortBtn    = JButton("Abort")
    private val conflictBanner      = JPanel()

    // ── Main content (History / File Status) ──────────────────────────────────
    // Plain BorderLayout swap instead of CardLayout: CardLayout left the heavy,
    // non-opaque history view painting through the file-status card. We mount
    // exactly one panel at a time so bleed-through is structurally impossible.
    private val mainContainer = JPanel(BorderLayout())
    private lateinit var historyView:     JComponent
    private lateinit var fileStatusPanel: JComponent

    private val sideBarSplit: JSplitPane
    private var sideBarOpen            = true
    private var previousSideBarDivider = 240

    // ── Init ──────────────────────────────────────────────────────────────────
    init {
        branchRoot.add(localBranchesNode)
        branchRoot.add(tagsNode)
        branchRoot.add(remoteBranchesNode)
        branchRoot.add(stashesNode)
        branchRoot.add(submodulesNode)
        branchRoot.add(worktreesNode)

        branchTree = JTree(branchTreeModel).apply {
            isRootVisible    = false
            showsRootHandles = true
            toggleClickCount = -1   // disable click-count toggle; expand/collapse only via arrow icon
            cellRenderer     = BranchCellRenderer()
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            addMouseListener(buildBranchMouseAdapter())
            addTreeSelectionListener { e ->
                if (!e.isAddedPath) return@addTreeSelectionListener
                when (val obj = (e.path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
                    is BranchInfo -> scrollCommitTableToBranch(obj.fullName)
                    is StashInfo  -> previewStash(obj.index)
                }
            }
            addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
                override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent)  = onExpansionChanged(event, true)
                override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) = onExpansionChanged(event, false)
                private fun onExpansionChanged(event: javax.swing.event.TreeExpansionEvent, expanded: Boolean) {
                    if (restoringExpansion) return
                    val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    // Persist section-level state to disk
                    if (node.parent == branchRoot) {
                        val label = node.userObject as? String ?: return
                        val tabEntry = Settings.openTabs.filterIsInstance<JSONObject>().find { it.getString("path") == path } ?: return
                        val sections = tabEntry.optJSONObject("sidebarSections") ?: JSONObject().also { tabEntry.put("sidebarSections", it) }
                        sections.put(label, expanded)
                        Settings.save()
                        return
                    }
                    // Track sub-folder expansion state in memory (survives refreshes)
                    val userObj = node.userObject
                    if (userObj !is String && userObj !is SubmoduleFolderNode) return
                    val pathArray = event.path.path
                    val segments = mutableListOf<Any>()
                    for (i in 1 until pathArray.size) {
                        segments.add((pathArray[i] as? DefaultMutableTreeNode)?.userObject ?: return)
                    }
                    if (expanded) expandedFolderPaths.add(segments)
                    else expandedFolderPaths.remove(segments)
                }
            })
        }

        // History view: commit table above, detail panel below
        // Detail panel = [metadata+filelist | diff]
        val leftDetailSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JScrollPane(commitDataPanel),
            buildCommitFileListPanel()
        ).apply { resizeWeight = 0.4; dividerLocation = 180 }

        val commitDetailSplit = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            leftDetailSplit,
            commitDiffScroll
        ).apply { resizeWeight = 0.35 }

        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = filterCommits(searchField.text)
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = filterCommits(searchField.text)
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) {}
        })
        searchFilterCombo.addActionListener { filterCommits(searchField.text) }
        val searchBar = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(searchFilterCombo, BorderLayout.WEST)
            add(searchField,       BorderLayout.CENTER)
        }
        val searchAndBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(searchBar, BorderLayout.NORTH)
            add(graphBar,  BorderLayout.SOUTH)
        }
        val tableWithSearch = JPanel(BorderLayout()).apply {
            add(searchAndBar,     BorderLayout.NORTH)
            add(commitScrollPane, BorderLayout.CENTER)
        }
        historyView = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            tableWithSearch,
            commitDetailSplit
        ).apply { resizeWeight = 0.7; dividerLocation = 400 }
        fileStatusPanel = buildFileStatusPanel()

        mainContainer.add(historyView, BorderLayout.CENTER)

        sideBarSplit = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            buildSidebar(),
            mainContainer
        ).apply { dividerLocation = previousSideBarDivider; resizeWeight = 0.0 }

        add(buildToolbar(), BorderLayout.NORTH)
        add(sideBarSplit,   BorderLayout.CENTER)

        loadColumnWidths()
        // BLIT_SCROLL_MODE (the default) keeps a stale backing buffer after the card is
        // remounted, so the table only repaints once a scroll invalidates it. SIMPLE mode
        // always repaints the visible area — fixes "needs resize+scroll to redraw".
        commitScrollPane.viewport.scrollMode = javax.swing.JViewport.SIMPLE_SCROLL_MODE
        commitDiffScroll.viewport.scrollMode = javax.swing.JViewport.SIMPLE_SCROLL_MODE
        diffScrollPane.viewport.scrollMode   = javax.swing.JViewport.SIMPLE_SCROLL_MODE
        commitScrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = updateDescriptionColumnWidth()
        })
        // Load more commits as the user scrolls near the bottom (windowed history)
        commitScrollPane.verticalScrollBar.addAdjustmentListener { maybeLoadMoreCommits() }
        commitTable.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) = saveColumnWidths()
        })

        commitTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val row = commitTable.selectedRow
                if (row in filteredCommits.indices) {
                    val c = filteredCommits[row]
                    if (c.hash == UNCOMMITTED_HASH) {
                        // Only a real user click on the uncommitted node jumps to File Status.
                        // Programmatic selection during refresh must NOT yank the view around.
                        if (!suppressCommitSelection) showFileStatus()
                    } else {
                        currentCommit = c
                        commitDataPanel.showCommit(c)
                        loadCommitFiles(c)
                    }
                }
            }
        }

        installCommitContextMenu()
        // Re-apply the nav highlight after the L&F has finished installing (it resets
        // button fonts during UI install, wiping the bold set during construction).
        SwingUtilities.invokeLater { updateWorkspaceSelection() }
        // NOTE: refresh() is NOT called here — MainView calls it when the tab is first shown.
    }

    /** Called by MainView the first time this tab is selected, and on subsequent focus. */
    private var everShown = false
    fun onTabSelected() {
        if (!everShown) { everShown = true; refresh() }
        else refresh()
    }

    // ── Card constants + graph palette ───────────────────────────────────────
    companion object {
        private const val CARD_HISTORY     = "history"
        private const val CARD_FILE_STATUS = "fileStatus"

        const val UNCOMMITTED_HASH = "0000000000000000000000000000000000000000"

        // History is loaded in windows of this many commits; more are appended on scroll.
        private const val COMMIT_PAGE = 300

        // Min gap between focus-triggered refreshes (ms).
        private const val FOCUS_REFRESH_MS = 3000L

        // Shared, bounded pool for git fan-out in refresh(). Caps total concurrent git
        // subprocesses across all tabs so refreshes can't spawn an unbounded process storm.
        // Sized to cover refresh()'s 7 parallel reads without serializing a single refresh.
        private val GIT_POOL = java.util.concurrent.Executors.newFixedThreadPool(8) { r ->
            Thread(r, "gitnarwhal-git").apply { isDaemon = true }
        }

        val GRAPH_PALETTE = listOf(
            Color(0x4F, 0xC3, 0xF7),  // sky blue
            Color(0x81, 0xC7, 0x84),  // green
            Color(0xFF, 0xB7, 0x4D),  // amber
            Color(0xF0, 0x62, 0x92),  // pink
            Color(0xCE, 0x93, 0xD8),  // lavender
            Color(0x80, 0xCB, 0xC4),  // teal
            Color(0xFF, 0xF1, 0x76),  // yellow
            Color(0xA5, 0xD6, 0xA7),  // light green
            Color(0xFF, 0xCC, 0xBC),  // peach
            Color(0xB0, 0xBE, 0xC5),  // blue-grey
        )
    }

    // ── File Status panel ─────────────────────────────────────────────────────

    // ── Commit file list panel ────────────────────────────────────────────────

    private fun buildCommitFileListPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        // toolbar: file count label
        val toolbar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
            isOpaque = false
        }
        commitFileLabel.font = commitFileLabel.font.deriveFont(Font.PLAIN, 11f)
        commitFileLabel.foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
        toolbar.add(commitFileLabel, BorderLayout.WEST)
        panel.add(toolbar, BorderLayout.NORTH)

        commitFileList.cellRenderer = FileStatusCellRenderer()
        commitFileList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val line   = commitFileList.selectedValue ?: return@addListSelectionListener
                val commit = currentCommit            ?: return@addListSelectionListener
                showCommitFileDiff(commit, line)
            }
        }
        commitFileList.componentPopupMenu = JPopupMenu().apply {
            add(JMenuItem("File History").apply {
                addActionListener {
                    val line = commitFileList.selectedValue ?: return@addActionListener
                    showFileHistory(line.substring(2).trim())
                }
            })
            add(JMenuItem("Copy Path to Clipboard").apply {
                addActionListener {
                    val line = commitFileList.selectedValue ?: return@addActionListener
                    val fullPath = java.io.File(git.repo, line.substring(2).trim()).absolutePath
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(fullPath), null)
                }
            })
        }
        panel.add(JScrollPane(commitFileList), BorderLayout.CENTER)
        return panel
    }

    private fun loadCommitFiles(commit: Commit) {
        commitFileModel.clear()
        commitDiffScroll.setViewportView(null)
        commitFileLabel.text = "loading…"
        object : SwingWorker<List<String>, Void>() {
            override fun doInBackground(): List<String> {
                val r = git.commitFiles(commit.hash)
                if (!r.success) return emptyList()
                return r.output.lines().filter { it.isNotBlank() }.map { line ->
                    val tab = line.indexOf('\t')
                    if (tab < 0) line
                    else "${line[0]} ${line.substringAfterLast('\t').trim()}"
                }
            }
            override fun done() {
                val files = try { get() } catch (_: Exception) { return }
                files.forEach { commitFileModel.addElement(it) }
                commitFileLabel.text = "${files.size} file${if (files.size != 1) "s" else ""}"
                if (files.isNotEmpty()) commitFileList.selectedIndex = 0
            }
        }.execute()
    }

    private fun showCommitFileDiff(commit: Commit, fileLine: String) {
        val path = fileLine.substring(2).trim()
        object : SwingWorker<String, Void>() {
            override fun doInBackground() = git.showFileDiff(commit.hash, path).output
            override fun done() {
                val diff = try { get() } catch (_: Exception) { return }
                commitDiffScroll.setViewportView(
                    buildDiffView(diff, staged = false, file = path, commitHash = commit.hash))
                commitDiffScroll.revalidate()
            }
        }.execute()
    }

    private fun buildFileStatusPanel(): JComponent {
        stagedList.cellRenderer   = FileStatusCellRenderer()
        unstagedList.cellRenderer = FileStatusCellRenderer()

        // ── File selection → show diff ────────────────────────────────────────
        stagedList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && stagedList.selectedValue != null) {
                unstagedList.clearSelection()
                showFileDiff(stagedList.selectedValue.substring(2), staged = true)
            }
        }
        unstagedList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && unstagedList.selectedValue != null) {
                stagedList.clearSelection()
                val entry = unstagedList.selectedValue
                showFileDiff(entry.substring(2), staged = false, isUntracked = entry.startsWith("?"))
            }
        }

        // ── Staged panel ──────────────────────────────────────────────────────
        val unstageAllBtn      = JButton("Unstage All")
        val unstageSelectedBtn = JButton("Unstage Selected")
        unstageAllBtn.addActionListener {
            val r = git.unstageAll()
            if (!r.success) showError("Unstage failed", r.output)
            refreshFileStatus()
        }
        unstageSelectedBtn.addActionListener {
            stagedList.selectedValuesList.forEach { git.unstage(it.substring(2)) }
            refreshFileStatus()
        }
        val stagedBtnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            isOpaque = false; add(unstageAllBtn); add(unstageSelectedBtn)
        }
        val stagedHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(4, 6, 2, 4)
            add(stagedHeaderLabel, BorderLayout.WEST)
            add(stagedBtnRow,      BorderLayout.EAST)
        }
        val stagedPanel = JPanel(BorderLayout()).apply {
            add(stagedHeader,            BorderLayout.NORTH)
            add(JScrollPane(stagedList), BorderLayout.CENTER)
        }

        // ── Unstaged panel ────────────────────────────────────────────────────
        val stageAllBtn      = JButton("Stage All")
        val stageSelectedBtn = JButton("Stage Selected")
        stageAllBtn.addActionListener {
            val r = git.addAll()
            if (!r.success) showError("Stage failed", r.output)
            refreshFileStatus()
        }
        stageSelectedBtn.addActionListener {
            unstagedList.selectedValuesList.forEach { git.add(it.substring(2)) }
            refreshFileStatus()
        }
        val unstagedBtnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            isOpaque = false; add(stageAllBtn); add(stageSelectedBtn)
        }
        val unstagedHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(4, 6, 2, 4)
            add(unstagedHeaderLabel, BorderLayout.WEST)
            add(unstagedBtnRow,      BorderLayout.EAST)
        }
        val unstagedPanel = JPanel(BorderLayout()).apply {
            add(unstagedHeader,            BorderLayout.NORTH)
            add(JScrollPane(unstagedList), BorderLayout.CENTER)
        }

        // Right-click context menus
        unstagedList.componentPopupMenu = JPopupMenu().apply {
            // ── File navigation ───────────────────────────────────────────────
            add(JMenuItem("Open").apply {
                addActionListener {
                    val v = unstagedList.selectedValue ?: return@addActionListener
                    val f = java.io.File(git.repo, v.substring(2))
                    try { Desktop.getDesktop().open(f) } catch (_: Exception) {}
                }
            })
            add(JMenuItem("Show in Explorer").apply {
                addActionListener {
                    val v = unstagedList.selectedValue ?: return@addActionListener
                    val f = java.io.File(git.repo, v.substring(2))
                    try { Desktop.getDesktop().open(f.parentFile ?: f) } catch (_: Exception) {}
                }
            })
            add(JMenuItem("Copy Path to Clipboard").apply {
                addActionListener {
                    val v = unstagedList.selectedValue ?: return@addActionListener
                    val fullPath = java.io.File(git.repo, v.substring(2)).absolutePath
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(fullPath), null)
                }
            })
            addSeparator()

            // ── Staging ───────────────────────────────────────────────────────
            add(JMenuItem("Stage selected").apply {
                addActionListener {
                    unstagedList.selectedValuesList
                        .filter { !it.startsWith("! ") }
                        .forEach { git.add(it.substring(2)) }
                    refreshFileStatus()
                }
            })
            add(JMenuItem("Mark as resolved (git add)").apply {
                addActionListener {
                    unstagedList.selectedValuesList.forEach { git.add(it.substring(2)) }
                    refreshFileStatus()
                }
            })
            // Accept ours/theirs — only shown for conflicted files (UU/AA/DD prefix)
            addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
                private var conflictItems = listOf<JMenuItem>()
                override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {
                    conflictItems.forEach { it.isVisible = false }
                    val v = unstagedList.selectedValue ?: return
                    val isConflict = v.length >= 2 && v[0] in "UAD" && v[1] in "UAD"
                    conflictItems.forEach { it.isVisible = isConflict }
                }
                override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) {}
                override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) {}
            })
            val acceptOursItem = JMenuItem("Accept Ours (keep local)").apply {
                addActionListener {
                    val v = unstagedList.selectedValue ?: return@addActionListener
                    val file = v.substring(2)
                    git.checkoutOurs(file); git.add(file); refreshFileStatus()
                }
            }
            val acceptTheirsItem = JMenuItem("Accept Theirs (keep incoming)").apply {
                addActionListener {
                    val v = unstagedList.selectedValue ?: return@addActionListener
                    val file = v.substring(2)
                    git.checkoutTheirs(file); git.add(file); refreshFileStatus()
                }
            }
            add(acceptOursItem)
            add(acceptTheirsItem)
            addSeparator()

            add(JMenuItem("Discard changes").apply {
                addActionListener {
                    val files = unstagedList.selectedValuesList
                    if (files.isEmpty()) return@addActionListener
                    val names = files.joinToString("\n") { it.substring(2) }
                    if (!confirm("Discard changes to:\n$names\n\nThis cannot be undone.")) return@addActionListener
                    files.forEach { git.restore(it.substring(2)) }
                    refreshFileStatus()
                }
            })
            addSeparator()

            // ── Tracking ──────────────────────────────────────────────────────
            add(JMenuItem("Stop Tracking (git rm --cached)").apply {
                addActionListener {
                    val v = unstagedList.selectedValue ?: return@addActionListener
                    val file = v.substring(2)
                    if (!confirm("Stop tracking '$file'?\n\nThe file will be kept locally but removed from git index.")) return@addActionListener
                    val r = git.stopTracking(file)
                    if (!r.success) showError("Stop tracking failed", r.output)
                    refreshFileStatus()
                }
            })
            add(JMenuItem("Ignore…").apply {
                addActionListener {
                    val v = unstagedList.selectedValue ?: return@addActionListener
                    showIgnoreDialog(v.substring(2))
                }
            })
            addSeparator()

            // ── Inspection ────────────────────────────────────────────────────
            add(JMenuItem("File History").apply {
                addActionListener {
                    val v = unstagedList.selectedValue ?: return@addActionListener
                    showFileHistory(v.substring(2))
                }
            })
            add(JMenuItem("Blame").apply {
                addActionListener {
                    val v = unstagedList.selectedValue ?: return@addActionListener
                    val file = v.substring(2)
                    blameBtn.isVisible = true
                    showBlame(file, null)
                }
            })
        }
        stagedList.componentPopupMenu = JPopupMenu().apply {
            add(JMenuItem("Open").apply {
                addActionListener {
                    val v = stagedList.selectedValue ?: return@addActionListener
                    val f = java.io.File(git.repo, v.substring(2))
                    try { Desktop.getDesktop().open(f) } catch (_: Exception) {}
                }
            })
            add(JMenuItem("Show in Explorer").apply {
                addActionListener {
                    val v = stagedList.selectedValue ?: return@addActionListener
                    val f = java.io.File(git.repo, v.substring(2))
                    try { Desktop.getDesktop().open(f.parentFile ?: f) } catch (_: Exception) {}
                }
            })
            add(JMenuItem("Copy Path to Clipboard").apply {
                addActionListener {
                    val v = stagedList.selectedValue ?: return@addActionListener
                    val fullPath = java.io.File(git.repo, v.substring(2)).absolutePath
                    Toolkit.getDefaultToolkit().systemClipboard
                        .setContents(StringSelection(fullPath), null)
                }
            })
            addSeparator()
            add(JMenuItem("Unstage selected").apply {
                addActionListener {
                    stagedList.selectedValuesList.forEach { git.unstage(it.substring(2)) }
                    refreshFileStatus()
                }
            })
            addSeparator()
            add(JMenuItem("File History").apply {
                addActionListener {
                    val v = stagedList.selectedValue ?: return@addActionListener
                    showFileHistory(v.substring(2))
                }
            })
            add(JMenuItem("Blame").apply {
                addActionListener {
                    val v = stagedList.selectedValue ?: return@addActionListener
                    blameBtn.isVisible = true
                    showBlame(v.substring(2), null)
                }
            })
        }

        // ── Diff panel (right) ────────────────────────────────────────────────
        diffFileNameLabel.apply {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            font   = font.deriveFont(Font.BOLD)
        }
        blameBtn.addActionListener {
            if (showingBlame) {
                showingBlame = false; blameBtn.text = "Blame"
                if (currentDiffFile.isNotBlank()) showFileDiff(currentDiffFile, currentDiffStaged)
            } else {
                showingBlame = true; blameBtn.text = "Diff"
                if (currentDiffFile.isNotBlank()) showBlame(currentDiffFile, null)
            }
        }
        val diffTopBar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0,
                UIManager.getColor("Separator.foreground") ?: Color(0x44_44_44))
            add(diffFileNameLabel, BorderLayout.WEST)
            add(blameBtn.apply { border = BorderFactory.createEmptyBorder(2, 8, 2, 8) }, BorderLayout.EAST)
        }
        val diffOuter = JPanel(BorderLayout()).apply {
            add(diffTopBar,    BorderLayout.NORTH)
            add(diffScrollPane, BorderLayout.CENTER)
        }

        // ── Conflict banner ───────────────────────────────────────────────────
        conflictContinueBtn.addActionListener {
            val isRebase     = java.io.File(git.repo, ".git/rebase-merge").exists() ||
                               java.io.File(git.repo, ".git/rebase-apply").exists()
            val isCherryPick = java.io.File(git.repo, ".git/CHERRY_PICK_HEAD").exists()
            when {
                isRebase     -> runWithProgress("Continuing rebase…")       { git.rebaseContinue()     }
                isCherryPick -> runWithProgress("Continuing cherry-pick…")  { git.cherryPickContinue() }
                else         -> commitMsgField.requestFocus()
            }
        }
        conflictAbortBtn.addActionListener {
            val isRebase     = java.io.File(git.repo, ".git/rebase-merge").exists() ||
                               java.io.File(git.repo, ".git/rebase-apply").exists()
            val isCherryPick = java.io.File(git.repo, ".git/CHERRY_PICK_HEAD").exists()
            when {
                isRebase     -> runWithProgress("Aborting rebase…")       { git.rebaseAbort()     }
                isCherryPick -> runWithProgress("Aborting cherry-pick…")  { git.cherryPickAbort() }
                else         -> runWithProgress("Aborting merge…")        { git.mergeAbort()      }
            }
            refresh()
        }
        conflictBanner.apply {
            isVisible  = false
            layout     = BorderLayout()
            background = Color(0x60, 0x38, 0x08)
            border     = BorderFactory.createEmptyBorder(5, 8, 5, 8)
            conflictBannerLabel.foreground = Color(0xFF, 0xCC, 0x80)
            add(conflictBannerLabel, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false; add(conflictContinueBtn); add(conflictAbortBtn)
            }, BorderLayout.EAST)
        }

        // ── File list vertical split ──────────────────────────────────────────
        val fileListSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, stagedPanel, unstagedPanel).apply {
            resizeWeight     = 0.4
            dividerLocation  = 160
            minimumSize      = Dimension(320, 0)
        }

        // ── Top half: files | diff ────────────────────────────────────────────
        val topHalf = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fileListSplit, diffOuter).apply {
            resizeWeight    = 0.0
            dividerLocation = 340
        }

        // ── Commit area (bottom) ──────────────────────────────────────────────

        commitMsgField.putClientProperty("JTextField.placeholderText", "Commit message")
        val commitBtnBottom = JButton("Commit").apply {
            putClientProperty("FlatLaf.style",
                "background: #1A6FBF; foreground: #FFFFFF; hoverBackground: #2078CC; pressedBackground: #155DA0")
            addActionListener { doCommit() }
        }
        val checkboxPanel = JPanel().apply {
            layout   = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(pushImmediatelyCheckBox)
            add(amendCheckBox)
        }
        val bottomBar = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(4, 0, 0, 0)
            add(checkboxPanel,  BorderLayout.CENTER)
            add(commitBtnBottom, BorderLayout.EAST)
        }
        val commitArea = JPanel(BorderLayout(0, 4)).apply {
            border        = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            preferredSize = Dimension(0, 160)
            minimumSize   = Dimension(0, 120)
            add(authorLabel,                 BorderLayout.NORTH)
            add(JScrollPane(commitMsgField), BorderLayout.CENTER)
            add(bottomBar,                   BorderLayout.SOUTH)
        }

        val mainSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, topHalf, commitArea).apply {
            resizeWeight = 0.75
        }
        return JPanel(BorderLayout()).apply {
            add(conflictBanner, BorderLayout.NORTH)
            add(mainSplit,      BorderLayout.CENTER)
        }
    }

    private fun refreshFileStatus() {
        object : SwingWorker<String, Void>() {
            override fun doInBackground() = git.status().output
            override fun done() {
                val out = try { get() } catch (e: Exception) { return }
                applyFileStatus(out)
            }
        }.execute()
    }

    private fun applyFileStatus(out: String) {
        val prevFile   = currentDiffFile
        val prevStaged = currentDiffStaged

        stagedModel.clear(); unstagedModel.clear()
        val conflictCodes = setOf("UU","AA","DD","AU","UA","DU","UD")
        val conflictFiles = mutableListOf<String>()
        for (line in out.lines()) {
            if (line.length < 3 || line.startsWith("##")) continue
            val x    = line[0]; val y = line[1]; val file = line.substring(3)
            if (matchesIgnorePattern(file)) continue
            val code = "$x$y"
            if (code in conflictCodes) {
                conflictFiles.add(file)
                unstagedModel.addElement("! $file")
            } else {
                if (x != ' ' && x != '?') stagedModel.addElement("$x $file")
                if (y == 'M' || y == 'D' || y == '?') unstagedModel.addElement("$y $file")
            }
        }
        stagedHeaderLabel.text   = "Staged files (${stagedModel.size()} files)"
        unstagedHeaderLabel.text = "Unstaged files (${unstagedModel.size()} files)"

        // Restore previous selection if the file is still in any list; otherwise clear diff
        if (prevFile.isNotBlank()) {
            val sIdx = (0 until stagedModel.size())  .firstOrNull { stagedModel[it].substring(2)   == prevFile }
            val uIdx = (0 until unstagedModel.size()).firstOrNull { unstagedModel[it].substring(2) == prevFile }
            when {
                prevStaged  && sIdx != null -> stagedList.selectedIndex   = sIdx
                !prevStaged && uIdx != null -> unstagedList.selectedIndex = uIdx
                sIdx != null                -> stagedList.selectedIndex   = sIdx
                uIdx != null                -> unstagedList.selectedIndex = uIdx
                else -> { diffScrollPane.setViewportView(null); diffFileNameLabel.text = " "; currentDiffFile = "" }
            }
        } else {
            diffScrollPane.setViewportView(null); diffFileNameLabel.text = " "; currentDiffFile = ""
        }

        // Update conflict banner
        if (conflictFiles.isNotEmpty()) {
            val isRebase      = java.io.File(git.repo, ".git/rebase-merge").exists() ||
                                java.io.File(git.repo, ".git/rebase-apply").exists()
            val isCherryPick  = java.io.File(git.repo, ".git/CHERRY_PICK_HEAD").exists()
            val op = when { isRebase -> "Rebase"; isCherryPick -> "Cherry-pick"; else -> "Merge" }
            conflictBannerLabel.text = "⚠  $op in progress — ${conflictFiles.size} conflict(s)"
            conflictContinueBtn.text = when { isRebase -> "Continue Rebase"; isCherryPick -> "Continue Cherry-pick"; else -> "Commit Merge" }
            conflictBanner.isVisible = true
            if (commitMsgField.text.isBlank() && !isCherryPick) {
                val mergeMsg = java.io.File(git.repo, ".git/MERGE_MSG")
                if (mergeMsg.exists()) commitMsgField.text = mergeMsg.readText()
            }
        } else {
            conflictBanner.isVisible = false
        }
    }

    // ── Diff display ──────────────────────────────────────────────────────────

    private var currentCard = CARD_HISTORY
    /** True while applyCommitList sets the selection programmatically, so the
     *  selection listener doesn't auto-switch the view to File Status. */
    private var suppressCommitSelection = false

    // ── Windowed history (avoids huge JTable that breaks Java2D rendering) ─────
    private var fullLogOutput   = ""    // raw git-log output (all commits)
    private var displayedCommits = COMMIT_PAGE
    private var hasUncommittedNow = false
    @Volatile private var loadingMoreCommits = false
    // Guards against overlapping refreshes. refresh() is triggered from several places
    // (tab select, F5, programmatic). Without this, focus/tab churn stacks multiple
    // 7-process git fan-outs + heavy EDT rebuilds, thrashing the table redraw.
    @Volatile private var refreshing = false
    @Volatile private var lastRefreshAt = 0L

    /** Refresh on regaining window focus, but at most once per [FOCUS_REFRESH_MS] —
     *  prevents every alt-tab / dialog-close from firing a full git fan-out. */
    fun refreshOnFocus() {
        if (refreshing) return
        if (System.currentTimeMillis() - lastRefreshAt < FOCUS_REFRESH_MS) return
        refresh()
    }

    /**
     * Mounts exactly one of [historyView] / [fileStatusPanel] into [mainContainer].
     *
     * Replaces CardLayout, which left the heavy non-opaque history view painting
     * through the file-status card. Physically removing the inactive panel makes
     * bleed-through impossible.
     */
    private fun switchCard(card: String) {
        currentCard = card
        val target = if (card == CARD_HISTORY) historyView else fileStatusPanel
        mainContainer.removeAll()
        mainContainer.add(target, BorderLayout.CENTER)
        updateWorkspaceSelection()
        mainContainer.revalidate()
        mainContainer.repaint()
        // The freshly-mounted nested JSplitPanes don't fully re-layout/paint until the
        // window is resized. Replicate a resize in software: validate the whole root
        // pane and mark it completely dirty so the OS does a full repaint.
        SwingUtilities.invokeLater {
            val root = SwingUtilities.getRootPane(this) ?: return@invokeLater
            root.validate()
            javax.swing.RepaintManager.currentManager(root).markCompletelyDirty(root)
            root.repaint()
        }
    }

    private fun updateWorkspaceSelection() {
        if (!::fileStatusItem.isInitialized) return
        val accent = UIManager.getColor("Component.accentColor") ?: Color(0x3E_C4_BD)
        val normal = UIManager.getColor("Label.foreground") ?: Color.LIGHT_GRAY
        fileStatusItem.font = fileStatusItem.font.deriveFont(
            if (currentCard == CARD_FILE_STATUS) Font.BOLD else Font.PLAIN)
        historyItem.font = historyItem.font.deriveFont(
            if (currentCard == CARD_HISTORY) Font.BOLD else Font.PLAIN)
        fileStatusItem.foreground = if (currentCard == CARD_FILE_STATUS) accent else normal
        historyItem.foreground    = if (currentCard == CARD_HISTORY)     accent else normal
        fileStatusItem.repaint(); historyItem.repaint()
    }

    private fun showFileStatus() {
        switchCard(CARD_FILE_STATUS)
        refreshAuthorLabel()
        refreshFileStatus()
    }

    private fun scrollCommitTableToBranch(branchFullName: String) {
        object : SwingWorker<String, Void>() {
            override fun doInBackground() = git.revParse(branchFullName).output.trim()
            override fun done() {
                val hash = try { get() } catch (_: Exception) { return }
                if (hash.isBlank()) return
                val row = commitList.indexOfFirst { it.hash.startsWith(hash) || hash.startsWith(it.hash) }
                if (row < 0) return
                switchCard(CARD_HISTORY)
                commitTable.selectionModel.setSelectionInterval(row, row)
                commitTable.scrollRectToVisible(commitTable.getCellRect(row, 0, true))
            }
        }.execute()
    }

    private fun refreshAuthorLabel() {
        val name  = git.configGet("user.name").output.trim()
        val email = git.configGet("user.email").output.trim()
        authorLabel.text = when {
            name.isNotBlank()  -> "$name <$email>"
            email.isNotBlank() -> email
            else               -> "Unknown author"
        }
    }

    private fun showFileDiff(file: String, staged: Boolean, isUntracked: Boolean = false) {
        currentDiffFile   = file
        currentDiffStaged = staged
        showingBlame      = false
        blameBtn.text      = "Blame"
        blameBtn.isVisible = !isUntracked
        diffFileNameLabel.text = file
        object : SwingWorker<String, Void>() {
            override fun doInBackground(): String = when {
                staged      -> git.diffStaged(file).output
                isUntracked -> git.diffUntracked(file).output   // exit 1 is normal; always use .output
                else        -> git.diff(file).output
            }
            override fun done() {
                val diffText = try { get() } catch (e: Exception) { return }
                diffScrollPane.setViewportView(buildDiffView(diffText, staged, file))
                diffScrollPane.revalidate()
            }
        }.execute()
    }

    private data class DiffHunk(val header: String, val lines: List<String>)
    private data class ParsedDiff(val fileHeader: List<String>, val hunks: List<DiffHunk>)

    private fun parseDiff(diffText: String): ParsedDiff {
        val allLines   = diffText.lines()
        val fileHeader = allLines.takeWhile { !it.startsWith("@@") }
        val hunks      = mutableListOf<DiffHunk>()
        var hunkHeader = ""
        var hunkLines  = mutableListOf<String>()
        for (line in allLines.drop(fileHeader.size)) {
            if (line.startsWith("@@")) {
                if (hunkHeader.isNotEmpty()) hunks.add(DiffHunk(hunkHeader, hunkLines.toList()))
                hunkHeader = line
                hunkLines  = mutableListOf(line)
            } else if (hunkHeader.isNotEmpty()) {
                hunkLines.add(line)
            }
        }
        if (hunkHeader.isNotEmpty()) hunks.add(DiffHunk(hunkHeader, hunkLines.toList()))
        return ParsedDiff(fileHeader, hunks)
    }

    private fun buildPatch(fileHeader: List<String>, hunk: DiffHunk) = buildString {
        fileHeader.forEach { appendLine(it) }
        hunk.lines.forEach { appendLine(it) }
    }

    private fun buildDiffView(
        diffText: String, staged: Boolean, file: String,
        commitHash: String? = null
    ): JPanel {
        val bgColor   = UIManager.getColor("EditorPane.background") ?: Color(0x2B, 0x2B, 0x2B)
        val container = object : JPanel(), Scrollable {
            override fun getPreferredScrollableViewportSize() = preferredSize
            override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16
            override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height
            override fun getScrollableTracksViewportWidth()  = true
            override fun getScrollableTracksViewportHeight() = false
        }.apply {
            layout     = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bgColor
        }
        if (diffText.isBlank()) {
            val msg = when {
                commitHash != null -> "No changes in this file have been detected, or it is a binary file."
                staged             -> "No staged changes in this file."
                else               -> "No changes in this file have been detected, or it is a binary file\nor it is configured to be ignored by the file patterns."
            }
            container.add(object : JPanel(GridBagLayout()), Scrollable {
                override fun getPreferredScrollableViewportSize() = preferredSize
                override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 16
                override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height
                override fun getScrollableTracksViewportWidth()  = true
                override fun getScrollableTracksViewportHeight() = true
            }.apply {
                background = bgColor
                maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                alignmentX = Component.LEFT_ALIGNMENT
                add(JLabel("<html><div style='text-align:center'>${ msg.replace("\n", "<br>") }</div></html>").apply {
                    foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                    font       = font.deriveFont(Font.PLAIN, 12f)
                })
            })
            return container
        }
        val parsed = parseDiff(diffText)
        val actionVerb = if (staged) "Unstage" else "Stage"

        parsed.hunks.forEachIndexed { idx, hunk ->
            val headerInfo = hunk.header.substringAfter("@@").substringBefore("@@").trim()
            val hunkLabel  = JLabel("Hunk ${idx + 1} : $headerInfo").apply {
                border     = BorderFactory.createEmptyBorder(6, 8, 4, 8)
                font       = font.deriveFont(Font.BOLD, 11f)
                foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            }

            val lineList = buildHunkLineList(hunk.lines, bgColor)

            val hunkBtnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply { isOpaque = false }

            if (commitHash != null) {
                // ── Commit view: Reverse hunk only ────────────────────────────
                val reverseBtn = JButton("Reverse hunk")
                reverseBtn.addActionListener {
                    val selIdx = lineList.selectedIndices.toSet()
                    val patch  = if (hasActionableSelection(lineList)) buildLinePatch(parsed.fileHeader, hunk, selIdx)
                                 else buildPatch(parsed.fileHeader, hunk)
                    val r = git.applyPatch(patch, cached = false, reverse = true)
                    if (!r.success) showError("Reverse failed", r.output)
                    else refreshFileStatus()
                }
                hunkBtnRow.add(reverseBtn)
            } else {
                // ── Staging view: Stage / Discard ─────────────────────────────
                val stageBtn   = JButton("$actionVerb hunk")
                val discardBtn = JButton("Discard hunk")

                lineList.selectionModel.addListSelectionListener {
                    val hasLines = hasActionableSelection(lineList)
                    stageBtn.text   = if (hasLines) "$actionVerb lines" else "$actionVerb hunk"
                    discardBtn.text = if (hasLines) "Discard lines"     else "Discard hunk"
                }
                stageBtn.addActionListener {
                    val selIdx = lineList.selectedIndices.toSet()
                    val patch  = if (hasActionableSelection(lineList)) buildLinePatch(parsed.fileHeader, hunk, selIdx)
                                 else buildPatch(parsed.fileHeader, hunk)
                    val r = if (staged) git.applyPatch(patch, cached = true, reverse = true)
                            else        git.applyPatch(patch, cached = true)
                    if (!r.success) showError("$actionVerb failed", r.output)
                    else { showFileDiff(file, staged); refreshFileStatus() }
                }
                discardBtn.addActionListener {
                    val selIdx = lineList.selectedIndices.toSet()
                    val patch  = if (hasActionableSelection(lineList)) buildLinePatch(parsed.fileHeader, hunk, selIdx)
                                 else buildPatch(parsed.fileHeader, hunk)
                    val r = git.applyPatch(patch, cached = false, reverse = true)
                    if (!r.success) showError("Discard failed", r.output)
                    else { showFileDiff(file, staged); refreshFileStatus() }
                }
                if (!staged) hunkBtnRow.add(discardBtn)
                hunkBtnRow.add(stageBtn)
            }
            val hunkHeaderRow = JPanel(BorderLayout()).apply {
                isOpaque    = false
                alignmentX  = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 36)
                add(hunkLabel,  BorderLayout.WEST)
                add(hunkBtnRow, BorderLayout.EAST)
            }
            val lineScroll = JScrollPane(lineList).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                border     = BorderFactory.createEmptyBorder()
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBarPolicy   = JScrollPane.VERTICAL_SCROLLBAR_NEVER
                viewport.background = bgColor
                maximumSize = Dimension(Int.MAX_VALUE, lineList.preferredSize.height + horizontalScrollBar.preferredSize.height)
            }
            container.add(hunkHeaderRow)
            container.add(lineScroll)
            container.add(Box.createVerticalStrut(4))
        }
        container.add(Box.createVerticalGlue())
        return container
    }

    /** JList with per-line coloring and click-to-toggle selection. */
    private fun buildHunkLineList(lines: List<String>, bgColor: Color): JList<String> {
        val fgColor = UIManager.getColor("Label.foreground") ?: Color.WHITE
        val addBg   = Color(0x1B, 0x3A, 0x27)
        val remBg   = Color(0x3A, 0x1B, 0x1B)
        val hunkFg  = Color(0x4F, 0xC3, 0xF7)
        val monoFont = Font(com.gitnarwhal.utils.Settings.diffFontFamily, Font.PLAIN, com.gitnarwhal.utils.Settings.diffFontSize)

        val model = DefaultListModel<String>().apply { lines.forEach { addElement(it) } }

        val list = object : JList<String>(model) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

            private var wasSelected = false
            private var clickedRow  = -1

            override fun processMouseEvent(e: MouseEvent) {
                // Capture pre-click state before super dispatches to listeners
                if (e.id == MouseEvent.MOUSE_PRESSED && !e.isShiftDown && !e.isControlDown) {
                    val idx = locationToIndex(e.point)
                    clickedRow  = idx
                    wasSelected = idx >= 0 && isSelectedIndex(idx)
                }
                super.processMouseEvent(e)
                // After default processing: if row was already selected, toggle it off
                if (e.id == MouseEvent.MOUSE_RELEASED && !e.isShiftDown && !e.isControlDown) {
                    if (wasSelected && clickedRow >= 0) removeSelectionInterval(clickedRow, clickedRow)
                    wasSelected = false; clickedRow = -1
                }
            }
        }
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        list.background    = bgColor
        list.font          = monoFont

        list.setCellRenderer(object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val line  = value as? String ?: ""
                label.font = monoFont
                if (!isSelected) {
                    when {
                        line.startsWith("+") && !line.startsWith("+++") ->
                            { label.background = addBg; label.foreground = fgColor }
                        line.startsWith("-") && !line.startsWith("---") ->
                            { label.background = remBg; label.foreground = fgColor }
                        line.startsWith("@@") ->
                            { label.background = bgColor; label.foreground = hunkFg }
                        else ->
                            { label.background = bgColor; label.foreground = fgColor }
                    }
                }
                return label
            }
        })
        return list
    }

    /** True if any selected row in [list] is a diff +/- line (not context/header). */
    private fun hasActionableSelection(list: JList<String>): Boolean =
        list.selectedIndices.any { i ->
            val line = list.model.getElementAt(i)
            (line.startsWith("+") && !line.startsWith("+++")) ||
            (line.startsWith("-") && !line.startsWith("---"))
        }

    /**
     * Build a minimal valid unified diff from only the selected lines.
     * Unselected `-` lines become context (don't stage the removal).
     * Unselected `+` lines are dropped (don't stage the addition).
     */
    private fun buildLinePatch(fileHeader: List<String>, hunk: DiffHunk, selectedIndices: Set<Int>): String {
        val headerMatch = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""").find(hunk.header)
        val oldStart    = headerMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val newStart    = headerMatch?.groupValues?.get(2)?.toIntOrNull() ?: 1

        val patchLines  = mutableListOf<String>()
        var newOldCount = 0
        var newNewCount = 0

        for ((i, line) in hunk.lines.withIndex()) {
            if (i == 0) continue  // skip original @@ header; rebuild below
            val selected = i in selectedIndices
            when {
                line.startsWith("+") && !line.startsWith("+++") -> {
                    if (selected) { patchLines += line; newNewCount++ }
                    // unselected + → omit
                }
                line.startsWith("-") && !line.startsWith("---") -> {
                    if (selected) { patchLines += line; newOldCount++ }
                    else          { patchLines += " ${line.drop(1)}"; newOldCount++; newNewCount++ }
                }
                else -> { patchLines += line; newOldCount++; newNewCount++ }
            }
        }

        return buildString {
            fileHeader.forEach { appendLine(it) }
            appendLine("@@ -$oldStart,$newOldCount +$newStart,$newNewCount @@")
            patchLines.forEach { appendLine(it) }
        }
    }

    // ── Inline commit ─────────────────────────────────────────────────────────

    private fun doCommit() {
        val msg = commitMsgField.text.trim()
        if (msg.isEmpty() && !amendCheckBox.isSelected) {
            showError("Commit", "Commit message is required"); return
        }
        val shouldPush = pushImmediatelyCheckBox.isSelected
        val progress = ProgressOverlay()
        progress.show(SwingUtilities.getRootPane(this), "Committing…")
        object : SwingWorker<Boolean, String>() {
            override fun doInBackground(): Boolean {
                val result = when {
                    amendCheckBox.isSelected && msg.isEmpty() -> git.commitAmendStream(onLine = { publish(it) })
                    amendCheckBox.isSelected                  -> git.commitAmendStream(msg,    onLine = { publish(it) })
                    else                                      -> git.commitStream(msg)               { publish(it) }
                }
                return result.success
            }
            override fun process(chunks: List<String>) { chunks.forEach { progress.appendOutput(it) } }
            override fun done() {
                val ok = try { get() } catch (e: Exception) { false }
                progress.finishStreaming(ok)
                if (ok) {
                    commitMsgField.text                = ""
                    amendCheckBox.isSelected           = false
                    pushImmediatelyCheckBox.isSelected = false
                    refresh()
                    refreshFileStatus()
                    if (shouldPush) pushToTrackingBranch()
                }
            }
        }.execute()
    }

    // ── BadgeButton ───────────────────────────────────────────────────────────

    private inner class BadgeButton(ikon: Ikon, label: String, action: () -> Unit) : JButton() {
        var badge = 0
            set(v) { field = v; repaint() }

        init {
            icon                   = FontIcon.of(ikon, 18, UIManager.getColor("Label.foreground") ?: Color.LIGHT_GRAY)
            text                   = label
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition   = SwingConstants.BOTTOM
            isBorderPainted        = false
            isContentAreaFilled    = false
            isFocusPainted         = false
            toolTipText            = label
            addActionListener { action() }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (badge <= 0) return
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val txt = if (badge > 99) "99+" else badge.toString()
            g2.font = g2.font.deriveFont(Font.BOLD, 9f)
            val fm  = g2.fontMetrics
            val tw  = fm.stringWidth(txt)
            val bw  = maxOf(tw + 6, 14)
            val bh  = 12
            val bx  = width - bw - 1
            val by  = 3
            g2.color = UIManager.getColor("Component.accentColor") ?: Color(0x1A_6F_BF)
            g2.fillRoundRect(bx, by, bw, bh, bh, bh)
            g2.color = Color.WHITE
            g2.drawString(txt, bx + (bw - tw) / 2, by + fm.ascent - 1)
            g2.dispose()
        }
    }

    // ── Branch tree renderer ──────────────────────────────────────────────────

    private inner class BranchCellRenderer : DefaultTreeCellRenderer() {
        init { setLeafIcon(null); setOpenIcon(null); setClosedIcon(null) }

        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any?, sel: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode ?: return this
            val fg = UIManager.getColor("Label.foreground") ?: Color.LIGHT_GRAY
            when (val obj = node.userObject) {
                is BranchInfo -> {
                    val aheadBehind = buildString {
                        if (obj.ahead  > 0) append(" ↑${obj.ahead}")
                        if (obj.behind > 0) append(" ↓${obj.behind}")
                    }
                    text = obj.leafName + aheadBehind
                    font = font.deriveFont(when {
                        obj.isActive     -> Font.BOLD
                        obj.isInWorktree -> Font.ITALIC
                        else             -> Font.PLAIN
                    })
                    icon = null
                }
                is TagInfo -> {
                    text = obj.name
                    font = font.deriveFont(Font.PLAIN)
                    icon = null
                }
                is StashInfo -> {
                    text = obj.description
                    font = font.deriveFont(Font.PLAIN)
                    icon = null
                }
                is WorktreeInfo -> {
                    val isCurrent = runCatching {
                        java.io.File(obj.path).canonicalPath == java.io.File(path).canonicalPath
                    }.getOrDefault(obj.path == path)
                    text        = obj.branch?.removePrefix("refs/heads/") ?: "(bare)"
                    toolTipText = obj.path
                    font        = font.deriveFont(if (isCurrent) Font.BOLD else Font.PLAIN)
                    icon        = FontIcon.of(MaterialDesign.MDI_FOLDER_OUTLINE, 14, fg)
                }
                is SubmoduleInfo -> {
                    val dirtyColor  = Color(0xFF, 0xB3, 0x00)
                    val commitColor = Color(0x4F, 0xC3, 0xF7)
                    val mutedColor  = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
                    text = obj.name
                    when {
                        obj.isDirty -> {
                            icon        = FontIcon.of(MaterialDesign.MDI_PENCIL, 14, dirtyColor)
                            foreground  = dirtyColor
                            font        = font.deriveFont(Font.BOLD)
                            toolTipText = "${obj.path} — uncommitted changes (commit needed)"
                        }
                        obj.differentCommit -> {
                            icon        = FontIcon.of(MaterialDesign.MDI_SOURCE_FORK, 14, commitColor)
                            foreground  = commitColor
                            font        = font.deriveFont(Font.PLAIN)
                            toolTipText = "${obj.path} — checked-out commit differs from the recorded one"
                        }
                        obj.isUninitialized -> {
                            icon        = FontIcon.of(MaterialDesign.MDI_FOLDER_REMOVE, 14, mutedColor)
                            foreground  = mutedColor
                            font        = font.deriveFont(Font.ITALIC)
                            toolTipText = "${obj.path} — not initialized"
                        }
                        else -> {
                            icon        = FontIcon.of(MaterialDesign.MDI_FOLDER_OUTLINE, 14, mutedColor)
                            foreground  = mutedColor
                            font        = font.deriveFont(Font.PLAIN)
                            toolTipText = "${obj.path} — up to date"
                        }
                    }
                }
                is SubmoduleFolderNode -> {
                    val dirtyColor  = Color(0xFF, 0xB3, 0x00)
                    val commitColor = Color(0x4F, 0xC3, 0xF7)
                    text = obj.name
                    font = font.deriveFont(Font.PLAIN)
                    // breadthFirstEnumeration is consumed-once — collect to List first
                    val descendants = node.breadthFirstEnumeration().asSequence()
                        .mapNotNull { (it as? DefaultMutableTreeNode)?.userObject as? SubmoduleInfo }
                        .toList()
                    val hasDirty   = descendants.any { it.isDirty }
                    val hasCommit  = descendants.any { it.differentCommit }
                    val tint = when { hasDirty -> dirtyColor; hasCommit -> commitColor; else -> fg }
                    icon = FontIcon.of(MaterialDesign.MDI_FOLDER, 14, tint)
                    if (hasDirty)       { foreground = dirtyColor;  font = font.deriveFont(Font.BOLD) }
                    else if (hasCommit) { foreground = commitColor }
                }
                is String -> {
                    text = obj
                    icon = when (obj) {
                        "BRANCHES"   -> FontIcon.of(MaterialDesign.MDI_SOURCE_BRANCH,   14, fg)
                        "TAGS"       -> FontIcon.of(MaterialDesign.MDI_TAG_OUTLINE,     14, fg)
                        "REMOTES"    -> FontIcon.of(MaterialDesign.MDI_CLOUD_OUTLINE,   14, fg)
                        "STASHES"    -> FontIcon.of(MaterialDesign.MDI_PACKAGE_VARIANT, 14, fg)
                        "SUBMODULES" -> FontIcon.of(MaterialDesign.MDI_CUBE_OUTLINE,    14, fg)
                        "WORKTREES"  -> FontIcon.of(MaterialDesign.MDI_FOLDER_MULTIPLE, 14, fg)
                        else         -> null
                    }
                    font = if (node.parent == branchRoot) font.deriveFont(Font.BOLD)
                           else font.deriveFont(Font.PLAIN)
                }
            }
            return this
        }
    }

    // ── Branch tree mouse handling ────────────────────────────────────────────

    private fun buildBranchMouseAdapter() = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent)  = checkPopup(e)
        override fun mouseReleased(e: MouseEvent) = checkPopup(e)
        private fun checkPopup(e: MouseEvent) {
            if (!e.isPopupTrigger) return
            val tp = branchTree.getPathForLocation(e.x, e.y) ?: return
            branchTree.selectionPath = tp
            val node = tp.lastPathComponent as? DefaultMutableTreeNode ?: return
            when (val obj = node.userObject) {
                is BranchInfo -> {
                    val isLocal = tp.path.any { it === localBranchesNode }
                    buildBranchPopup(obj.fullName, isLocal, obj.tracking).show(branchTree, e.x, e.y)
                }
                is TagInfo       -> buildTagPopup(obj.name).show(branchTree, e.x, e.y)
                is StashInfo     -> buildStashTreePopup(obj.index).show(branchTree, e.x, e.y)
                is SubmoduleInfo -> buildSubmodulePopup(obj).show(branchTree, e.x, e.y)
                is WorktreeInfo  -> buildWorktreePopup(obj).show(branchTree, e.x, e.y)
                is String -> when (node) {
                    worktreesNode  -> buildAddWorktreeMenu().show(branchTree, e.x, e.y)
                    submodulesNode -> buildSubmoduleHeaderMenu().show(branchTree, e.x, e.y)
                }
            }
        }
        override fun mouseClicked(e: MouseEvent) {
            if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                val tp   = branchTree.getPathForLocation(e.x, e.y) ?: return
                val node = tp.lastPathComponent as? DefaultMutableTreeNode ?: return
                when (val obj = node.userObject) {
                    is BranchInfo    -> checkoutBranch(obj.fullName)
                    is SubmoduleInfo -> openSubmoduleTab(obj)
                    is WorktreeInfo  -> openWorktreeAsTab(obj)
                }
            }
        }
    }

    private fun buildBranchPopup(branchFullName: String, isLocal: Boolean = false, tracking: String? = null): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(menuItem("Checkout") { checkoutBranch(branchFullName) })
        if (isLocal && isHeadDetached) {
            menu.add(menuItem("Link Branch to HEAD") {
                if (confirm("Link '$branchFullName' to current HEAD?\n\ngit branch -f $branchFullName HEAD")) {
                    val r = git.moveBranchToRef(branchFullName, "HEAD")
                    if (!r.success) showError("Link branch failed", r.output)
                    else { git.selectBranch(branchFullName); refresh() }
                }
            })
        }
        menu.add(menuItem("Merge into current") {
            if (confirm("Merge '$branchFullName' into current branch?")) {
                val r = git.merge(branchFullName)
                if (!r.success) showError("Merge failed", r.output); refresh()
            }
        })
        menu.add(menuItem("Rebase current onto '$branchFullName'") {
            if (confirm("Rebase current branch onto '$branchFullName'?")) {
                val r = git.rebase(branchFullName)
                if (!r.success) showError("Rebase failed", r.output); refresh()
            }
        })
        menu.addSeparator()
        if (isLocal) {
            if (tracking != null) {
                val trackParts   = tracking.split("/", limit = 2)
                val remote       = trackParts[0]
                val remoteBranch = if (trackParts.size > 1) trackParts[1] else branchFullName
                menu.add(menuItem("Push to $tracking") {
                    val rp = SwingUtilities.getRootPane(this) ?: return@menuItem
                    val overlay = ProgressOverlay()
                    object : SwingWorker<Boolean, String>() {
                        override fun doInBackground(): Boolean {
                            val r = git.pushRefspecStream(remote, branchFullName, remoteBranch, force = false, setUpstream = false) { publish(it) }
                            return r.success
                        }
                        override fun process(chunks: List<String>) { chunks.forEach { overlay.appendOutput(it) } }
                        override fun done() {
                            val ok = try { get() } catch (_: Exception) { false }
                            overlay.finishStreaming(ok); refresh()
                        }
                    }.execute()
                    overlay.show(rp, "Pushing to $tracking…")
                })
                menu.add(menuItem("Pull from $tracking") {
                    val rp = SwingUtilities.getRootPane(this) ?: return@menuItem
                    val overlay = ProgressOverlay()
                    object : SwingWorker<Boolean, String>() {
                        override fun doInBackground(): Boolean {
                            val r = git.pullStream(remote, remoteBranch) { publish(it) }
                            return r.success
                        }
                        override fun process(chunks: List<String>) { chunks.forEach { overlay.appendOutput(it) } }
                        override fun done() {
                            val ok = try { get() } catch (_: Exception) { false }
                            overlay.finishStreaming(ok); refresh()
                        }
                    }.execute()
                    overlay.show(rp, "Pulling from $tracking…")
                })
            } else {
                menu.add(menuItem("Push…") { push() })
            }
            menu.addSeparator()
        }
        menu.add(menuItem("Rename…") {
            val newName = JOptionPane.showInputDialog(this, "New name:", branchFullName)
                ?.takeIf { it.isNotBlank() } ?: return@menuItem
            val r = git.renameBranch(branchFullName, newName)
            if (!r.success) showError("Rename failed", r.output); refresh()
        })
        menu.add(menuItem("Delete") {
            if (!isLocal) {
                val parts  = branchFullName.split("/", limit = 2)
                val remote = parts[0]
                val branch = if (parts.size > 1) parts[1] else branchFullName
                if (confirm("Delete remote branch '$branchFullName'?")) {
                    val r = git.pushDeleteTag(remote, branch)
                    if (!r.success) showError("Delete remote branch failed", r.output)
                    refresh()
                }
            } else {
                if (confirm("Delete branch '$branchFullName'? (refuses if not merged)")) {
                    val r = git.deleteBranch(branchFullName, force = false)
                    if (!r.success) {
                        if (confirm("Branch not merged. Force delete '$branchFullName'?")) {
                            val rf = git.deleteBranch(branchFullName, force = true)
                            if (!rf.success) showError("Force delete failed", rf.output)
                        }
                    }
                    refresh()
                }
            }
        })
        return menu
    }

    private fun checkoutBranch(branchName: String) {
        val r = git.selectBranch(branchName)
        if (r.success) refreshBranches() else showError("Checkout failed", r.output)
    }

    // ── Sidebar ───────────────────────────────────────────────────────────────

    private fun restoreSidebarExpansion() {
        val tabEntry = Settings.openTabs.filterIsInstance<JSONObject>().find { it.getString("path") == path }
        val saved = tabEntry?.optJSONObject("sidebarSections") ?: JSONObject()
        val sections = mapOf(
            "BRANCHES"   to localBranchesNode,
            "TAGS"       to tagsNode,
            "REMOTES"    to remoteBranchesNode,
            "STASHES"    to stashesNode,
            "SUBMODULES" to submodulesNode,
            "WORKTREES"  to worktreesNode
        )
        restoringExpansion = true
        branchTree.expandPath(TreePath(branchRoot.path))
        for ((name, node) in sections) {
            if (saved.optBoolean(name, true)) branchTree.expandPath(TreePath(node.path))
            else branchTree.collapsePath(TreePath(node.path))
        }
        // Restore sub-folder expansion state
        outer@ for (segments in expandedFolderPaths) {
            var node: DefaultMutableTreeNode = branchRoot
            for (seg in segments) {
                node = node.children().asSequence()
                    .filterIsInstance<DefaultMutableTreeNode>()
                    .firstOrNull { it.userObject == seg } ?: continue@outer
            }
            branchTree.expandPath(TreePath(node.path))
        }
        restoringExpansion = false
    }

    private fun buildSidebar(): JPanel {
        val panel = JPanel(BorderLayout())

        // WORKSPACE section (fixed height at top — not resizable)
        val workspacePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
        }
        workspacePanel.add(sidebarSectionHeader("WORKSPACE"))
        fileStatusItem = workspaceItem("File Status", MaterialDesign.MDI_FILE_DOCUMENT) {
            showFileStatus()
        }
        historyItem = workspaceItem("History", MaterialDesign.MDI_HISTORY) {
            showHistoryView()
        }
        workspacePanel.add(fileStatusItem)
        workspacePanel.add(historyItem)
        updateWorkspaceSelection()

        // Unified sidebar tree: BRANCHES / TAGS / REMOTES / STASHES / SUBMODULES / WORKTREES
        val treePanel = JPanel(BorderLayout())
        treePanel.add(JScrollPane(branchTree), BorderLayout.CENTER)
        treePanel.add(sidebarBar, BorderLayout.SOUTH)

        panel.add(workspacePanel, BorderLayout.NORTH)
        panel.add(treePanel,      BorderLayout.CENTER)
        return panel
    }

    private fun sidebarSectionHeader(title: String): JLabel =
        JLabel(title).apply {
            font   = font.deriveFont(Font.BOLD, 10f)
            border = BorderFactory.createEmptyBorder(4, 8, 2, 4)
            foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun workspaceItem(label: String, ikon: Ikon, onClick: () -> Unit): JButton =
        JButton(label, FontIcon.of(ikon, 14, UIManager.getColor("Label.foreground") ?: Color.LIGHT_GRAY)).apply {
            horizontalAlignment = SwingConstants.LEFT
            isBorderPainted     = false
            isContentAreaFilled = false
            isFocusPainted      = false
            border              = BorderFactory.createEmptyBorder(3, 12, 3, 4)
            alignmentX          = Component.LEFT_ALIGNMENT
            maximumSize         = Dimension(Int.MAX_VALUE, preferredSize.height + 4)
            addActionListener { onClick() }
        }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    private fun buildToolbar(): JToolBar {
        val bar = JToolBar().apply { isFloatable = false }

        // Toggle sidebar (far left, separate)
        bar.add(iconBtn(MaterialDesign.MDI_MENU, "Toggle Sidebar") { toggleSideBar() })
        bar.addSeparator()

        // Primary actions — match SourceTree order
        bar.add(commitBtn)
        bar.add(pullBtn)
        bar.add(pushBtn)
        bar.add(iconBtn(MaterialDesign.MDI_CLOUD_DOWNLOAD, "Fetch") { fetch() }.also { btn ->
            btn.componentPopupMenu = JPopupMenu().apply {
                add(JMenuItem("Fetch All Remotes").apply { addActionListener { fetch() } })
                addSeparator()
                // Remote-specific entries populated on first show
                addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
                    override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {
                        // Remove old per-remote items (keep first 2: "Fetch All" + separator)
                        while (componentCount > 2) remove(2)
                        git.remoteNames().forEach { remote ->
                            add(JMenuItem("Fetch $remote").apply {
                                addActionListener { runWithProgress("Fetching $remote…") { git.fetchRemote(remote) } }
                            })
                        }
                    }
                    override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) {}
                    override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) {}
                })
            }
            btn.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    if (e.isPopupTrigger || javax.swing.SwingUtilities.isRightMouseButton(e))
                        btn.componentPopupMenu.show(btn, e.x, e.y)
                }
            })
        })
        bar.addSeparator()
        bar.add(iconBtn(MaterialDesign.MDI_SOURCE_BRANCH,  "Branch")  { newBranch() })
        bar.add(iconBtn(MaterialDesign.MDI_SOURCE_MERGE,    "Merge")   { mergeBranch() })
        bar.add(iconBtn(MaterialDesign.MDI_ARCHIVE,        "Stash")   { stashCurrentChanges() })
        bar.add(iconBtn(MaterialDesign.MDI_UNDO,           "Discard") { discardAll() })
        bar.add(iconBtn(MaterialDesign.MDI_REFRESH,        "Refresh") { refresh() })

        // Push remaining to right
        bar.add(Box.createHorizontalGlue())

        bar.add(iconBtn(MaterialDesign.MDI_EARTH,    "Remote")   { openRemote() })
        bar.add(iconBtn(MaterialDesign.MDI_CODE_BRACES, "Open in IDE") { openInIDE() })
        bar.add(iconBtn(MaterialDesign.MDI_CONSOLE,  "Terminal") { openTerminal() })
        bar.add(iconBtn(MaterialDesign.MDI_FOLDER,   "Explorer") { openExplorer() })
        bar.addSeparator()
        bar.add(iconBtn(MaterialDesign.MDI_SETTINGS, "Settings") {
            val win = SwingUtilities.getWindowAncestor(this)
            RepoSettingsDialog(git, win).isVisible = true
        })
        return bar
    }

    private fun iconBtn(icon: Ikon, label: String, action: () -> Unit): JButton =
        JButton(label, FontIcon.of(icon, 18, UIManager.getColor("Label.foreground") ?: Color.DARK_GRAY)).apply {
            horizontalTextPosition = SwingConstants.CENTER
            verticalTextPosition   = SwingConstants.BOTTOM
            toolTipText            = label
            isBorderPainted        = false
            isContentAreaFilled    = false
            isFocusPainted         = false
            addActionListener { action() }
        }

    private fun toggleSideBar() {
        if (sideBarOpen) {
            previousSideBarDivider = sideBarSplit.dividerLocation
            sideBarSplit.dividerLocation = 0
        } else {
            sideBarSplit.dividerLocation = previousSideBarDivider
        }
        sideBarOpen = !sideBarOpen
    }

    // ── Commit context menu ───────────────────────────────────────────────────

    private fun installCommitContextMenu() {
        commitTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent)  = maybeShow(e)
            override fun mouseReleased(e: MouseEvent) = maybeShow(e)
            private fun maybeShow(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = commitTable.rowAtPoint(e.point)
                if (row < 0) return
                commitTable.selectionModel.setSelectionInterval(row, row)
                buildCommitPopup(commitList[row]).show(e.component, e.x, e.y)
            }
        })
    }

    private fun buildCommitPopup(commit: Commit): JPopupMenu {
        val isVirtual = commit.hash == UNCOMMITTED_HASH
        val menu = JPopupMenu()

        // ── Checkout ─────────────────────────────────────────────────────────
        menu.add(menuItem("Checkout…") {
            if (isVirtual) return@menuItem
            if (confirm("Checkout commit ${commit.shortHash}?\n\nThis puts the repo in detached HEAD state.")) {
                val r = git.selectBranch(commit.hash)
                if (!r.success) showError("Checkout failed", r.output) else refresh()
            }
        })

        // ── Merge ────────────────────────────────────────────────────────────
        menu.add(menuItem("Merge…") {
            if (isVirtual) return@menuItem
            if (confirm("Merge commit ${commit.shortHash} into the current branch?")) {
                val r = git.merge(commit.hash)
                if (!r.success) showError("Merge failed", r.output) else refresh()
            }
        })

        // ── Rebase ───────────────────────────────────────────────────────────
        menu.add(menuItem("Rebase…") {
            if (isVirtual) return@menuItem
            if (confirm("Rebase current branch onto ${commit.shortHash}?")) {
                val r = git.rebase(commit.hash)
                if (!r.success) showError("Rebase failed", r.output) else refresh()
            }
        })

        // ── Tag ──────────────────────────────────────────────────────────────
        menu.add(menuItem("Tag…") {
            val win = SwingUtilities.getWindowAncestor(this)
            val hash = if (isVirtual) "HEAD" else commit.hash
            TagDialog(git, hash, win).apply { isVisible = true }
            refresh()
        })

        menu.addSeparator()

        // ── Branch ───────────────────────────────────────────────────────────
        menu.add(menuItem("Create Branch…") {
            val name = JOptionPane.showInputDialog(this, "New branch name:", "Create Branch",
                JOptionPane.QUESTION_MESSAGE)?.trim()?.ifBlank { null } ?: return@menuItem
            val from = if (isVirtual) "HEAD" else commit.hash
            val r = git.createBranchFrom(name, from)
            if (!r.success) showError("Create branch failed", r.output) else refresh()
        })

        menu.addSeparator()

        // ── Cherry-pick ───────────────────────────────────────────────────────
        menu.add(menuItem("Cherry Pick") {
            if (isVirtual) return@menuItem
            if (confirm("Cherry-pick ${commit.shortHash}?")) {
                val r = git.cherryPick(commit.hash)
                if (!r.success) showError("Cherry-pick failed", r.output) else refresh()
            }
        }.also { it.isEnabled = !isVirtual })

        // ── Revert ───────────────────────────────────────────────────────────
        menu.add(menuItem("Reverse Commit…") {
            if (isVirtual) return@menuItem
            if (confirm("Revert ${commit.shortHash}? (creates a new revert commit)")) {
                val r = git.revert(commit.hash)
                if (!r.success) showError("Revert failed", r.output) else refresh()
            }
        }.also { it.isEnabled = !isVirtual })

        menu.addSeparator()

        // ── Reset ────────────────────────────────────────────────────────────
        val resetMenu = JMenu("Reset current branch to this commit")
        resetMenu.isEnabled = !isVirtual
        resetMenu.add(menuItem("Soft  — keep index and working tree") {
            if (confirm("git reset --soft ${commit.shortHash}?")) {
                val r = git.reset(commit.hash, "soft")
                if (!r.success) showError("Reset failed", r.output) else refresh()
            }
        })
        resetMenu.add(menuItem("Mixed  — unstage changes") {
            if (confirm("git reset --mixed ${commit.shortHash}?")) {
                val r = git.reset(commit.hash, "mixed")
                if (!r.success) showError("Reset failed", r.output) else refresh()
            }
        })
        resetMenu.add(menuItem("Hard  — DISCARD all changes (destructive!)") {
            if (confirm("DESTRUCTIVE: git reset --hard ${commit.shortHash}\n\nAll uncommitted changes will be lost.")) {
                val r = git.reset(commit.hash, "hard")
                if (!r.success) showError("Reset failed", r.output) else refresh()
            }
        })
        menu.add(resetMenu)

        menu.addSeparator()

        // ── Copy ─────────────────────────────────────────────────────────────
        menu.add(menuItem("Copy SHA to Clipboard")       { if (!isVirtual) copyToClipboard(commit.hash) })
        menu.add(menuItem("Copy short SHA to Clipboard") { if (!isVirtual) copyToClipboard(commit.shortHash) })

        return menu
    }

    // ── Stash ─────────────────────────────────────────────────────────────────

    /** Stash node selected → preview its diff in the commit detail panel. */
    private fun previewStash(idx: Int) {
        switchCard(CARD_HISTORY)
        object : SwingWorker<String, Void>() {
            override fun doInBackground() = git.stashDiff(idx).output
            override fun done() {
                val diff = try { get() } catch (_: Exception) { return }
                commitDiffScroll.setViewportView(buildDiffView(diff, false, "stash@{$idx}"))
                commitDiffScroll.revalidate()
            }
        }.execute()
    }

    private fun buildStashTreePopup(idx: Int): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(menuItem("Apply") {
            val r = git.stashApply(idx)
            if (!r.success) showError("Stash op failed", r.output); refresh()
        })
        menu.add(menuItem("Pop") {
            val r = git.stashPop(idx)
            if (!r.success) showError("Stash op failed", r.output); refresh()
        })
        menu.add(menuItem("Drop") {
            if (confirm("Drop stash@{$idx}? (irreversible)")) {
                val r = git.stashDrop(idx)
                if (!r.success) showError("Drop failed", r.output); refreshStashes()
            }
        })
        return menu
    }

    private fun stashCurrentChanges() {
        val msg = JOptionPane.showInputDialog(this, "Stash message (optional):", "Stash",
            JOptionPane.QUESTION_MESSAGE) ?: return
        val r = if (msg.isBlank()) git.stashPush() else git.stashPush(msg)
        if (!r.success) showError("Stash failed", r.output); refresh()
    }

    // ── Toolbar branch/merge/discard ──────────────────────────────────────────

    private fun newBranch() {
        val name = JOptionPane.showInputDialog(this, "New branch name:", "Create Branch",
            JOptionPane.QUESTION_MESSAGE)?.takeIf { it.isNotBlank() } ?: return
        val r = git.createBranch(name)
        if (!r.success) showError("Create branch failed", r.output); refreshBranches()
    }

    private fun mergeBranch() {
        val name = JOptionPane.showInputDialog(this, "Branch to merge into current branch:",
            "Merge", JOptionPane.QUESTION_MESSAGE)?.takeIf { it.isNotBlank() } ?: return
        if (confirm("Merge '$name' into current branch?")) {
            val r = git.merge(name)
            if (!r.success) showError("Merge failed", r.output); refresh()
        }
    }

    private fun discardAll() {
        if (!confirm("Discard ALL local changes?\n\nThis will restore every modified file to its last committed state. Untracked files are NOT deleted."))
            return
        val r = git.restore(".")
        if (!r.success) showError("Discard failed", r.output); refresh()
    }

    // ── Async refresh ─────────────────────────────────────────────────────────

    private data class RefreshSnapshot(
        val branchesOut: String, val branchOk: Boolean,
        val logOut: String,      val logOk: Boolean,
        val stashOut: String,
        val tagsOut: String,
        val statusOut: String,
        val modifiedCount: Int,
        val unpulledCount: Int,
        val unpushedCount: Int,
        val currentBranch: String,
        val tracking: Pair<String, String>?,  // remote to trackingBranch
        val commits: List<Commit>,             // graph pre-computed off the EDT
        val worktreeEntries: List<Git.WorktreeEntry>
        // submoduleStatus is intentionally NOT here — it runs in a separate lazy task
    )

    fun refresh() {
        // Skip overlapping refreshes: a refresh already in flight will publish fresh data.
        // Coalescing here is what keeps focus/tab/F5 churn from thrashing the graph redraw.
        if (refreshing) return
        refreshing = true
        lastRefreshAt = System.currentTimeMillis()
        showLoading()
        graphBar.setBusy(true); sidebarBar.setBusy(true)
        val window = displayedCommits.coerceAtLeast(COMMIT_PAGE)
        object : SwingWorker<RefreshSnapshot, Void>() {
            override fun doInBackground(): RefreshSnapshot {
                // Shared bounded pool (see GIT_POOL) instead of a fresh cached pool per
                // refresh — caps total concurrent git subprocesses so overlapping work
                // can't spawn an unbounded process storm.
                val pool = GIT_POOL
                val fBranches    = pool.submit<com.gitnarwhal.utils.Command> { git.branches() }
                val fLog         = pool.submit<com.gitnarwhal.utils.Command> { git.log() }
                val fStash       = pool.submit<com.gitnarwhal.utils.Command> { git.stashList() }
                val fTags        = pool.submit<com.gitnarwhal.utils.Command> { git.tags() }
                val fStatus      = pool.submit<com.gitnarwhal.utils.Command> { git.status() }
                val fUnpulled    = pool.submit<Int> { git.unpulledCount().output.trim().toIntOrNull() ?: 0 }
                val fUnpushed    = pool.submit<Int> { git.unpushedCount().output.trim().toIntOrNull() ?: 0 }
                val fWorktrees   = pool.submit<List<Git.WorktreeEntry>> { git.worktreeList() }
                val fBranchTrack = pool.submit<Pair<String, Pair<String,String>?>> {
                    val b = git.currentBranch().output.trim()
                    b to if (b.isNotBlank()) git.trackingBranch(b) else null
                }

                val branches   = fBranches.get()
                val log_       = fLog.get()
                val stash      = fStash.get()
                val tags       = fTags.get()
                val status     = fStatus.get()
                val unpulled   = fUnpulled.get()
                val unpushed   = fUnpushed.get()
                val worktrees  = fWorktrees.get()
                val (branch, tracking) = fBranchTrack.get()
                val modified   = status.output.lines().count { it.length > 2 && !it.startsWith("##") }
                // Heavy graph layout computed HERE (background), not on the EDT.
                // Only the first [window] commits are laid out — keeps the JTable small
                // enough that Java2D renders it reliably; more load on scroll.
                val commits = if (log_.success) layoutCommitGraph(log_.output, modified > 0, window)
                              else emptyList()

                return RefreshSnapshot(
                    branches.output, branches.success, log_.output, log_.success,
                    if (stash.success) stash.output else "",
                    if (tags.success) tags.output else "",
                    status.output,
                    modified, unpulled, unpushed,
                    branch, tracking, commits, worktrees
                )
            }
            override fun done() {
                refreshing = false
                val snap = try { get() } catch (e: Exception) {
                    hideLoading()
                    graphBar.setBusy(false); sidebarBar.setBusy(false)
                    return
                }
                if (snap.branchOk) applyBranches(snap.branchesOut)
                applyTags(snap.tagsOut)
                if (snap.logOk) {
                    fullLogOutput     = snap.logOut
                    hasUncommittedNow = snap.modifiedCount > 0
                    applyCommitList(snap.commits)
                }
                graphBar.setBusy(false)
                applyStashes(snap.stashOut)
                applyWorktrees(snap.worktreeEntries)
                sidebarBar.setBusy(false)
                applyFileStatus(snap.statusOut)
                commitBtn.badge = snap.modifiedCount
                pullBtn.badge   = snap.unpulledCount
                pushBtn.badge   = snap.unpushedCount
                currentBranchName = snap.currentBranch
                trackingRemote    = snap.tracking?.first
                trackingBranchRef = snap.tracking?.second
                updatePushCheckboxLabel()
                hideLoading()
                // Submodule status runs separately so it never blocks the main UI
                refreshSubmodulesAsync()
            }
        }.execute()
    }

    // ── Submodule support ─────────────────────────────────────────────────────

    /**
     * Parses `git submodule status` and, per submodule, probes its working tree for
     * uncommitted changes. Runs on a background thread (spawns one git-status per
     * submodule), so the result is handed to [applySubmodules] on the EDT.
     *
     * Flag meanings: ' ' clean · '+' checked-out commit differs from recorded · '-'
     * uninitialized · 'U' merge conflicts.
     */
    private fun parseSubmodules(output: String): List<SubmoduleInfo> {
        val subs = mutableListOf<SubmoduleInfo>()
        for (line in output.lines()) {
            if (line.isBlank()) continue
            val flag = line[0]
            val rest = line.drop(1).trim()
            val spaceIdx = rest.indexOf(' ')
            if (spaceIdx < 0) continue
            val hash      = rest.substring(0, spaceIdx)
            val remainder = rest.substring(spaceIdx + 1).trim()
            val subPath   = remainder.substringBefore(" (").substringBefore("\t").trim()
            if (subPath.isBlank()) continue
            val name = subPath.substringAfterLast("/").ifBlank { subPath }
            val uninitialized = flag == '-'
            subs += SubmoduleInfo(
                path            = subPath,
                name            = name,
                hash            = hash,
                isDirty         = if (uninitialized) false else git.submoduleDirty(subPath),
                differentCommit = flag == '+' || flag == 'U',
                isUninitialized = uninitialized
            )
        }
        return subs
    }

    private fun applySubmodules(subs: List<SubmoduleInfo>) {
        allSubmodules.clear()
        allSubmodules.addAll(subs)
        submodulesNode.removeAllChildren()
        for (info in allSubmodules) insertSubmoduleNode(info)
        branchTreeModel.nodeStructureChanged(submodulesNode)
        restoreSidebarExpansion()
    }

    /**
     * Inserts a submodule leaf into the unified tree under SUBMODULES, creating
     * intermediate folder nodes (SubmoduleFolderNode) for each path segment.
     */
    private fun insertSubmoduleNode(info: SubmoduleInfo) {
        val segs    = info.path.split("/")
        var current = submodulesNode
        for (i in 0 until segs.size - 1) {
            val seg = segs[i]
            current = current.children().asSequence()
                .filterIsInstance<DefaultMutableTreeNode>()
                .firstOrNull { it.userObject == SubmoduleFolderNode(seg) }
                ?: DefaultMutableTreeNode(SubmoduleFolderNode(seg)).also { current.add(it) }
        }
        current.add(DefaultMutableTreeNode(info))
    }

    // Not `inner`: uses no RepoTab state, so it can be built & exercised in a headless test.
    internal class SubmoduleTreeRenderer : DefaultTreeCellRenderer() {
        init { setLeafIcon(null); setOpenIcon(null); setClosedIcon(null) }

        // Dirty (uncommitted content → user should commit) is the loud state: amber + bold.
        private val dirtyColor  get() = Color(0xFF, 0xB3, 0x00)
        // Different commit (gitlink mismatch) is informational: blue, normal weight.
        private val commitColor get() = Color(0x4F, 0xC3, 0xF7)
        private val mutedColor  get() = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY

        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any?, sel: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ): java.awt.Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode ?: return this
            when (val obj = node.userObject) {
                is SubmoduleInfo -> {
                    text        = obj.name
                    when {
                        // Dirty wins: it's the actionable state (commit inside the submodule).
                        obj.isDirty -> {
                            icon        = FontIcon.of(MaterialDesign.MDI_PENCIL, 14, dirtyColor)
                            foreground  = dirtyColor
                            font        = font.deriveFont(Font.BOLD)
                            toolTipText = "${obj.path} — uncommitted changes (commit needed)"
                        }
                        obj.differentCommit -> {
                            icon        = FontIcon.of(MaterialDesign.MDI_SOURCE_FORK, 14, commitColor)
                            foreground  = commitColor
                            toolTipText = "${obj.path} — checked-out commit differs from the recorded one"
                        }
                        obj.isUninitialized -> {
                            icon        = FontIcon.of(MaterialDesign.MDI_FOLDER_REMOVE, 14, mutedColor)
                            foreground  = mutedColor
                            font        = font.deriveFont(Font.ITALIC)
                            toolTipText = "${obj.path} — not initialized"
                        }
                        else -> {
                            icon        = FontIcon.of(MaterialDesign.MDI_FOLDER, 14, mutedColor)
                            foreground  = mutedColor
                            toolTipText = "${obj.path} — up to date"
                        }
                    }
                }
                is String -> {
                    text        = obj
                    toolTipText = null
                    // Materialize: breadthFirstEnumeration().asSequence() is consume-once,
                    // and we iterate it twice below.
                    val descendants = node.breadthFirstEnumeration().asSequence()
                        .mapNotNull { (it as? DefaultMutableTreeNode)?.userObject as? SubmoduleInfo }
                        .toList()
                    val hasDirtyChild  = descendants.any { it.isDirty }
                    val hasCommitChild = descendants.any { it.differentCommit }
                    val tint = when {
                        hasDirtyChild  -> dirtyColor
                        hasCommitChild -> commitColor
                        else           -> foreground
                    }
                    icon = FontIcon.of(MaterialDesign.MDI_FOLDER, 14, tint)
                    if (hasDirtyChild) {
                        foreground = dirtyColor
                        font       = font.deriveFont(Font.BOLD)
                    } else if (hasCommitChild) {
                        foreground = commitColor
                    }
                }
            }
            return this
        }
    }

    private fun openSubmoduleTab(info: SubmoduleInfo) {
        val subPath  = java.io.File(git.repo, info.path).canonicalPath
        val mainView = javax.swing.SwingUtilities.getAncestorOfClass(
            MainView::class.java, this) as? MainView ?: return
        val existing = (0 until mainView.tabPane.tabCount)
            .mapNotNull { mainView.tabPane.getComponentAt(it) as? RepoTab }
            .firstOrNull { java.io.File(it.path).canonicalPath == subPath }
        if (existing != null) mainView.selectTab(existing)
        else mainView.addTab(RepoTab(subPath, info.name))
    }

    /** Fetches submodule status in background WITHOUT blocking or showing the loading bar. */
    private fun refreshSubmodulesAsync() {
        sidebarBar.setBusy(true)
        object : SwingWorker<List<SubmoduleInfo>, Void>() {
            override fun doInBackground() = parseSubmodules(git.submoduleStatus().output)
            override fun done() {
                sidebarBar.setBusy(false)
                val subs = try { get() } catch (_: Exception) { return }
                applySubmodules(subs)
            }
        }.execute()
    }

    fun refreshSubmodules() = refreshSubmodulesAsync()

    fun refreshWorktrees() {
        sidebarBar.setBusy(true)
        object : SwingWorker<List<Git.WorktreeEntry>, Void>() {
            override fun doInBackground() = git.worktreeList()
            override fun done() {
                sidebarBar.setBusy(false)
                val list = try { get() } catch (e: Exception) { emptyList() }
                applyWorktrees(list)
            }
        }.execute()
    }

    // ── Sidebar context menus (tags / stashes already above; submodule / worktree) ──

    private fun buildTagPopup(tagName: String): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(menuItem("Checkout") {
            val r = git.selectBranch(tagName)
            if (!r.success) showError("Checkout failed", r.output) else refresh()
        })
        menu.addSeparator()
        menu.add(menuItem("Delete") {
            if (confirm("Delete tag '$tagName'?")) {
                val r = git.tagDelete(tagName)
                if (!r.success) showError("Delete tag failed", r.output) else refreshBranches()
            }
        })
        return menu
    }

    private fun buildSubmodulePopup(info: SubmoduleInfo): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(menuItem("Open as Tab") { openSubmoduleTab(info) })
        menu.addSeparator()
        menu.add(menuItem("Update Submodule") {
            val r = com.gitnarwhal.utils.Command(
                com.gitnarwhal.backend.Git.GIT,
                "submodule", "update", "--init", "--recursive", info.path,
                path = git.repo
            ).execute()
            if (!r.success) showError("Update failed", r.output) else refreshSubmodulesAsync()
        })
        return menu
    }

    private fun buildSubmoduleHeaderMenu(): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(menuItem("Refresh Submodules") { refreshSubmodulesAsync() })
        return menu
    }

    private fun buildWorktreePopup(wt: WorktreeInfo): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(menuItem("Open as Tab") { openWorktreeAsTab(wt) })
        if (!wt.isMain) {
            menu.addSeparator()
            menu.add(menuItem("Remove Worktree…") {
                if (confirm("Remove worktree at '${wt.path}'?")) {
                    val r = git.worktreeRemove(wt.path)
                    if (!r.success) showError("Remove failed", r.output)
                    refreshWorktrees()
                }
            })
        }
        return menu
    }

    private fun buildAddWorktreeMenu(): JPopupMenu {
        val menu = JPopupMenu()
        menu.add(menuItem("Add Worktree…") { showAddWorktreeDialog() })
        return menu
    }

    private fun showAddWorktreeDialog() {
        val pathField   = JTextField(30)
        val browseBtn   = JButton("Browse…")
        val branchField = JTextField(20)

        browseBtn.addActionListener {
            val dir = NativeFileChooser.chooseDirectory(
                SwingUtilities.getWindowAncestor(this), "Worktree directory"
            ) ?: return@addActionListener
            pathField.text = dir.absolutePath
        }

        val panel = JPanel(GridBagLayout())
        val gbc   = GridBagConstraints().apply { insets = Insets(4, 4, 4, 4); anchor = GridBagConstraints.WEST }

        gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.NONE
        panel.add(JLabel("Path:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        panel.add(pathField, gbc)
        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        panel.add(browseBtn, gbc)

        gbc.gridx = 0; gbc.gridy = 1
        panel.add(JLabel("Branch / commit:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(branchField, gbc)

        val ok = JOptionPane.showConfirmDialog(
            this, panel, "Add Worktree",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (ok != JOptionPane.OK_OPTION) return
        val p = pathField.text.trim(); val b = branchField.text.trim()
        if (p.isBlank() || b.isBlank()) return
        val r = git.worktreeAdd(p, b)
        if (!r.success) showError("Add worktree failed", r.output)
        refreshWorktrees()
    }

    private fun openWorktreeAsTab(wt: WorktreeInfo) {
        val mv = javax.swing.SwingUtilities.getAncestorOfClass(
            MainView::class.java, this) as? MainView ?: return
        val branchName = wt.branch?.removePrefix("refs/heads/") ?: wt.head.take(7)
        val canon = java.io.File(wt.path).canonicalPath
        val existing = (0 until mv.tabPane.tabCount)
            .mapNotNull { mv.tabPane.getComponentAt(it) as? RepoTab }
            .firstOrNull { java.io.File(it.path).canonicalPath == canon }
        if (existing != null) mv.selectTab(existing)
        else mv.addTab(RepoTab(wt.path, branchName))
    }

    // The global loading bar was removed in favour of per-section bars; these remain
    // as no-ops so the (many) call sites stay valid without churn.
    private fun showLoading() {}
    private fun hideLoading() {}

    private fun updatePushCheckboxLabel() {
        val r = trackingRemote; val b = trackingBranchRef
        pushImmediatelyCheckBox.text      = if (r != null && b != null)
            "Push changes immediately to $r/$b" else "Push changes immediately (no upstream)"
        pushImmediatelyCheckBox.isEnabled = r != null && b != null
    }

    fun refreshBranches() {
        showLoading()
        sidebarBar.setBusy(true)
        object : SwingWorker<Pair<String?, String>, Void>() {
            override fun doInBackground(): Pair<String?, String> {
                val b = git.branches()
                val t = git.tags()
                return (b.output.takeIf { b.success }) to (if (t.success) t.output else "")
            }
            override fun done() {
                sidebarBar.setBusy(false)
                val (branchOut, tagsOut) = try { get() } catch (e: Exception) { hideLoading(); null to "" }
                if (branchOut != null) applyBranches(branchOut)
                applyTags(tagsOut)
                hideLoading()
            }
        }.execute()
    }

    fun refreshStashes() {
        showLoading()
        sidebarBar.setBusy(true)
        object : SwingWorker<String, Void>() {
            override fun doInBackground() = git.stashList().output
            override fun done() {
                sidebarBar.setBusy(false)
                val out = try { get() } catch (e: Exception) { hideLoading(); "" }
                applyStashes(out)
                hideLoading()
            }
        }.execute()
    }

    // ── Data application (EDT) ────────────────────────────────────────────────

    private fun applyBranches(output: String) {
        isHeadDetached = output.lines().any { it.trimStart().startsWith("* (HEAD detached") }
        localBranchesNode.removeAllChildren()
        remoteBranchesNode.removeAllChildren()
        for (line in output.lines()) {
            if (line.isBlank()) continue
            val isActive   = line.startsWith("*")
            val inWorktree = !isActive && line.startsWith("+")
            val cleaned    = line.removePrefix("*").removePrefix("+").trim()
            val parts    = cleaned.replace("\\s+".toRegex(), " ").split(" ")
            val fullName = parts[0]
            if (cleaned.contains(" -> ")) continue
            if (fullName.startsWith("remotes/")) {
                insertBranch(remoteBranchesNode, fullName.removePrefix("remotes/"), false, null, 0, 0)
            } else {
                val rest     = if (parts.size > 2) parts.drop(2).joinToString(" ") else ""
                val tracking = "^\\[([^\\]:]+)".toRegex().find(rest)?.groups?.get(1)?.value
                val ahead    = "ahead (\\d+)".toRegex().find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val behind   = "behind (\\d+)".toRegex().find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                insertBranch(localBranchesNode, fullName, isActive, tracking, ahead, behind, inWorktree)
            }
        }
        branchTreeModel.reload()
        restoreSidebarExpansion()
    }

    private fun insertBranch(parent: DefaultMutableTreeNode, path: String, active: Boolean,
                             tracking: String?, ahead: Int = 0, behind: Int = 0, inWorktree: Boolean = false) {
        val segs    = path.split("/")
        var current = parent
        for (i in 0 until segs.size - 1) {
            val seg = segs[i]
            current = current.children().asSequence()
                .filterIsInstance<DefaultMutableTreeNode>()
                .firstOrNull { it.userObject == seg }
                ?: DefaultMutableTreeNode(seg).also { current.add(it) }
        }
        current.add(DefaultMutableTreeNode(BranchInfo(path, segs.last(), active, tracking, ahead, behind, inWorktree)))
    }

    /**
     * Parses git-log output and computes the full commit graph (topological order,
     * lane assignment, line geometry). Pure data work — NO Swing access — so it runs
     * on a background thread. Hand the result to [applyCommitList] on the EDT.
     */
    private fun layoutCommitGraph(logOutput: String, hasUncommitted: Boolean = false, limit: Int = 0): List<Commit> {
        val localMap      = LinkedHashMap<String, Commit>()
        val localList     = mutableListOf<Commit>()
        val parentHashMap = mutableMapOf<String, List<String>>()  // hash → parent hashes (for pass 2)
        var parsed = 0   // number of commits accepted so far (for windowed loading)
        for (record in logOutput.split('')) {
            val f = record.split('')
            if (f.size < 8) continue
            val hash = f[0].trim(); if (hash.isBlank()) continue
            if (limit in 1..parsed) break   // window full — stop parsing
            // Pass 1: populate commit data; parent linking deferred to pass 2
            val commit = localMap.getOrPut(hash) { Commit(hash, this) }
            commit.prePopulate(listOf(f[2], f[3], f[4], f[5], f[6], f[7]))
            val decoration = if (f.size > 8) f[8] else ""
            commit.refs = parseRefs(decoration)
            commit.isCurrentHead = decoration.split(", ").any { p ->
                val t = p.trim(); t.startsWith("HEAD -> ") || t == "HEAD"
            }
            parentHashMap[hash] = f[1].split(" ").filter { it.isNotBlank() }
            parsed++
        }
        // Pass 2: link parents/children — ALL commits now in localMap, lookups succeed
        for ((h, parentHashes) in parentHashMap) {
            val commit = localMap[h] ?: continue
            for (ph in parentHashes) {
                val p = localMap[ph] ?: continue
                if (p !in commit.parents) { commit.parents.add(p); p.childs.add(commit) }
            }
        }
        // ── Topological sort (post-order DFS, newest first → y=0 = top row) ──
        // Iterative (explicit stack) to avoid StackOverflowError on deep histories.
        var y = 0
        val visiting = HashSet<String>()
        val stack    = ArrayDeque<Pair<Commit, Boolean>>()
        localMap.values.sortedBy { runCatching { -it.committerTimeStamp.toLong() }.getOrDefault(0L) }
            .forEach { root ->
                if (root.explored) return@forEach
                stack.addLast(root to false)
                while (stack.isNotEmpty()) {
                    val (c, processed) = stack.removeLast()
                    if (processed) {
                        if (!c.explored) { c.explored = true; c.y = y++ }
                        continue
                    }
                    if (c.explored || c.hash in visiting) continue
                    visiting.add(c.hash)
                    stack.addLast(c to true)
                    for (i in c.childs.indices.reversed()) {
                        val child = c.childs[i]
                        if (!child.explored && child.hash !in visiting) stack.addLast(child to false)
                    }
                }
            }
        localMap.values.sortedBy { it.y }.forEach { localList.add(it) }

        // ── Lane (x) assignment ───────────────────────────────────────────────
        // laneHashes[i] = hash of next commit expected in lane i (null = free)
        val laneHashes = mutableListOf<String?>()
        val laneColors = mutableListOf<Color>()
        var colorIdx = 0

        fun nextColor() = GRAPH_PALETTE[colorIdx++ % GRAPH_PALETTE.size]

        fun allocateLane(hash: String, color: Color): Int {
            val free = laneHashes.indexOfFirst { it == null }
            return if (free >= 0) {
                laneHashes[free] = hash; laneColors[free] = color; free
            } else {
                laneHashes.add(hash); laneColors.add(color); laneHashes.size - 1
            }
        }

        for (commit in localList) {
            val topLines = laneHashes.indices
                .filter { laneHashes[it] != null }
                .map { it to laneColors[it] }
            val topSet = topLines.map { it.first }.toSet()  // lanes active before this row

            val laneIdx = laneHashes.indexOf(commit.hash)
            val col: Int
            if (laneIdx >= 0) {
                col = laneIdx; commit.color = laneColors[col]
            } else {
                val c = nextColor(); col = allocateLane(commit.hash, c); commit.color = c
            }
            commit.x = col

            val forkLines = mutableListOf<Pair<Int, Color>>()
            if (commit.parents.isEmpty()) {
                laneHashes[col] = null
            } else {
                val fp     = commit.parents[0]
                val fpLane = laneHashes.indexOf(fp.hash)
                when {
                    fpLane >= 0 && fpLane != col -> {
                        laneHashes[col] = null
                        forkLines.add(fpLane to commit.color)  // use the branch's own color, not the ancestor's
                    }
                    fpLane < 0 -> laneHashes[col] = fp.hash
                    // fpLane == col: already correct
                }
                for (parent in commit.parents.drop(1)) {
                    val pLane = laneHashes.indexOf(parent.hash)
                    if (pLane >= 0) {
                        forkLines.add(pLane to laneColors[pLane])
                    } else {
                        val c = nextColor(); val nl = allocateLane(parent.hash, c)
                        forkLines.add(nl to c)
                    }
                }
            }

            // Exclude newly-created fork target lanes: their vertical starts in the NEXT row.
            // The fork diagonal already connects the commit dot to them; a mid-starting
            // vertical in this row would create the phantom "stub" line.
            val newForkLanes = forkLines.map { it.first }.filter { it !in topSet }.toSet()
            val bottomLines = laneHashes.indices
                .filter { laneHashes[it] != null && it !in newForkLanes }
                .map { it to laneColors[it] }

            commit.graphTopLines    = topLines
            commit.graphBottomLines = bottomLines
            commit.graphForkLines   = forkLines
        }

        // ── Uncommitted-changes virtual node ─────────────────────────────────
        if (hasUncommitted && localList.isNotEmpty()) {
            val grey     = Color.GRAY
            val head     = localList.firstOrNull { it.isCurrentHead } ?: localList.first()
            val headIdx  = localList.indexOf(head).coerceAtLeast(0)
            val headLane = head.x

            val virtual = Commit(UNCOMMITTED_HASH, this).apply {
                prePopulate(listOf("", "", "", "", "", "Uncommitted changes"))
                x     = headLane
                y     = -1
                color = grey
                refs  = emptyList()                                  // no branch pills on the working-copy node
                graphTopLines    = emptyList()
                graphBottomLines = listOf(headLane to grey)
                graphForkLines   = emptyList()
            }
            // branch pills stay on HEAD (head.refs left untouched)

            if (headIdx == 0) {
                // HEAD is already the top commit — just sit directly above it.
                head.graphTopLines = head.graphTopLines + (headLane to grey)
            } else {
                // HEAD is behind origin: shift the commits above HEAD one lane to the
                // right, freeing headLane for a grey connector that runs from the
                // (top-of-list) uncommitted node straight down to HEAD. The origin
                // chain then merges into HEAD from its shifted lane.
                fun shift(lines: List<Pair<Int, Color>>) =
                    lines.map { (l, c) -> (if (l >= headLane) l + 1 else l) to c }
                for (i in 0 until headIdx) {
                    val c = localList[i]
                    if (c.x >= headLane) c.x += 1
                    c.graphTopLines    = shift(c.graphTopLines)
                    c.graphBottomLines = shift(c.graphBottomLines)
                    c.graphForkLines   = shift(c.graphForkLines)
                    // Direct children of HEAD now fork down-left into headLane;
                    // everyone else just lets the grey connector pass straight through.
                    if (head in c.parents) {
                        c.graphForkLines   = c.graphForkLines + (headLane to grey)
                        c.graphBottomLines = c.graphBottomLines.filterNot { it.first == c.x }
                    }
                    c.graphTopLines    = c.graphTopLines    + (headLane to grey)
                    c.graphBottomLines = c.graphBottomLines + (headLane to grey)
                }
                head.graphTopLines = head.graphTopLines + (headLane to grey)
            }
            localList.add(0, virtual)
        }

        return localList
    }

    /**
     * EDT: pushes a pre-computed commit list (from [layoutCommitGraph]) into the table.
     * [keepView] = true when appending more commits on scroll — keep selection/scroll put.
     */
    private fun applyCommitList(localList: List<Commit>, keepView: Boolean = false) {
        // ── Apply to table ────────────────────────────────────────────────────
        val previousHash = filteredCommits.getOrNull(commitTable.selectedRow)?.hash
        commitList.clear(); commitList.addAll(localList)
        filterCommits(searchField.text)   // repopulates filteredCommits + fires tableDataChanged

        val maxLanes = (localList.maxOfOrNull { it.x } ?: 0) + 1
        val colW = (maxLanes * CommitGraphCell.LANE_W + CommitGraphCell.H_OFFSET * 2).coerceIn(60, 300)
        commitTable.columnModel.getColumn(0).apply { preferredWidth = colW; width = colW }
        updateDescriptionColumnWidth()

        // Restore previous selection; fall back to row 0 (HEAD / uncommitted)
        val targetRow = if (previousHash != null)
            filteredCommits.indexOfFirst { it.hash == previousHash }.takeIf { it >= 0 } ?: 0
        else 0
        if (filteredCommits.isNotEmpty()) {
            // Suppress the listener's File Status auto-switch for this programmatic selection
            suppressCommitSelection = true
            try {
                commitTable.selectionModel.setSelectionInterval(targetRow, targetRow)
                if (!keepView) commitTable.scrollRectToVisible(commitTable.getCellRect(targetRow, 0, true))
            } finally {
                suppressCommitSelection = false
            }
        }

        // Force a layout + repaint after the model swap — otherwise the freshly
        // populated table sometimes doesn't draw until the window is resized.
        SwingUtilities.invokeLater {
            commitTable.revalidate()
            commitScrollPane.revalidate()
            commitScrollPane.repaint()
        }
    }

    /** Total commits available in the loaded log (each record starts with RS = ). */
    private fun totalCommitRecords() = fullLogOutput.count { it == '' }

    /** Called on scroll: if near the bottom and more commits exist, append the next page. */
    private fun maybeLoadMoreCommits() {
        if (loadingMoreCommits || fullLogOutput.isEmpty() || currentCard != CARD_HISTORY) return
        val sb = commitScrollPane.verticalScrollBar
        val nearBottom = sb.value + sb.visibleAmount >= sb.maximum - 50 * commitTable.rowHeight
        if (!nearBottom) return
        if (displayedCommits >= totalCommitRecords()) return  // everything loaded

        loadingMoreCommits = true
        showLoading()
        graphBar.setBusy(true)
        val newWindow = displayedCommits + COMMIT_PAGE
        val raw = fullLogOutput
        val unc = hasUncommittedNow
        object : SwingWorker<List<Commit>, Void>() {
            override fun doInBackground() = layoutCommitGraph(raw, unc, newWindow)
            override fun done() {
                hideLoading(); graphBar.setBusy(false); loadingMoreCommits = false
                val commits = try { get() } catch (_: Exception) { return }
                displayedCommits = newWindow
                val scrollPos = commitScrollPane.verticalScrollBar.value
                applyCommitList(commits, keepView = true)
                SwingUtilities.invokeLater { commitScrollPane.verticalScrollBar.value = scrollPos }
            }
        }.execute()
    }

    // ── Column width persistence ──────────────────────────────────────────────

    private fun loadColumnWidths() {
        val w = Settings.columnWidths
        listOf(0 to "graph", 2 to "date", 3 to "committer", 4 to "commit").forEach { (col, key) ->
            val saved = w.optInt(key, 0)
            if (saved > 0) commitTable.columnModel.getColumn(col).apply {
                preferredWidth = saved; width = saved
            }
        }
    }

    private fun saveColumnWidths() {
        val w = JSONObject()
        w.put("graph",     commitTable.columnModel.getColumn(0).width)
        w.put("date",      commitTable.columnModel.getColumn(2).width)
        w.put("committer", commitTable.columnModel.getColumn(3).width)
        w.put("commit",    commitTable.columnModel.getColumn(4).width)
        Settings.columnWidths = w
    }

    private fun updateDescriptionColumnWidth() {
        val viewW = commitScrollPane.viewport.width.takeIf { it > 0 } ?: return
        val fixedW = listOf(0, 2, 3, 4).sumOf { commitTable.columnModel.getColumn(it).width }
        val descW = (viewW - fixedW).coerceAtLeast(50)
        commitTable.columnModel.getColumn(1).apply { preferredWidth = descW; width = descW }
    }

    private fun parseRefs(decoration: String): List<RefInfo> {
        if (decoration.isBlank()) return emptyList()
        val result = mutableListOf<RefInfo>()
        for (rawPart in decoration.split(", ")) {
            val part = rawPart.trim()
            when {
                part.startsWith("HEAD -> ") -> {
                    val branch = part.removePrefix("HEAD -> ")
                    result.add(RefInfo(branch, if (branch.contains("/")) RefType.REMOTE_BRANCH else RefType.LOCAL_BRANCH))
                }
                part == "HEAD"           -> result.add(RefInfo("HEAD", RefType.HEAD))
                part.startsWith("tag: ") -> result.add(RefInfo(part.removePrefix("tag: "), RefType.TAG))
                part.contains(" -> ")    -> { /* remote HEAD alias e.g. origin/HEAD -> origin/main */ }
                part.contains("/")       -> result.add(RefInfo(part, RefType.REMOTE_BRANCH))
                part.isNotBlank()        -> result.add(RefInfo(part, RefType.LOCAL_BRANCH))
            }
        }
        return result
    }

    private fun applyStashes(output: String) {
        stashesNode.removeAllChildren()
        var idx = 0
        for (line in output.lines()) {
            if (line.isBlank()) continue
            stashesNode.add(DefaultMutableTreeNode(StashInfo(idx, line)))
            idx++
        }
        branchTreeModel.nodeStructureChanged(stashesNode)
        restoreSidebarExpansion()
    }

    private fun applyTags(output: String) {
        tagsNode.removeAllChildren()
        for (line in output.lines()) {
            val name = line.trim()
            if (name.isNotBlank()) tagsNode.add(DefaultMutableTreeNode(TagInfo(name)))
        }
        branchTreeModel.nodeStructureChanged(tagsNode)
        restoreSidebarExpansion()
    }

    private fun applyWorktrees(entries: List<Git.WorktreeEntry>) {
        worktreesNode.removeAllChildren()
        for (e in entries) {
            worktreesNode.add(DefaultMutableTreeNode(
                WorktreeInfo(e.path, e.branch, e.head, e.isMain)
            ))
        }
        branchTreeModel.nodeStructureChanged(worktreesNode)
        restoreSidebarExpansion()
    }

    // ── Toolbar git actions ───────────────────────────────────────────────────

    fun commit() {
        CommitDialog(SwingUtilities.getWindowAncestor(this), git, onSuccess = { refresh() }).isVisible = true
    }

    fun fetch() = runWithProgress("Fetching…") { git.fetch() }
    fun pull()  = PullOverlay(git).show(SwingUtilities.getRootPane(this)) { refresh() }
    fun push()  = PushOverlay(git, tabTitle).show(SwingUtilities.getRootPane(this)) { refresh() }

    private fun runWithProgress(title: String, op: () -> Command) {
        val overlay = ProgressOverlay()
        val rp      = SwingUtilities.getRootPane(this)
        object : SwingWorker<Command, Void>() {
            override fun doInBackground() = op()
            override fun done() {
                val cmd = try { get() } catch (e: Exception) {
                    overlay.finish("Error: ${e.message}", false); return
                }
                overlay.finish(cmd.output, cmd.success)
                refresh()
            }
        }.execute()
        overlay.show(rp, title)
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    fun showHistoryView() = switchCard(CARD_HISTORY)
    fun showFileStatusView() = showFileStatus()
    fun openSettings()       { RepoSettingsDialog(git, SwingUtilities.getWindowAncestor(this)).isVisible = true }

    private fun showFileHistory(filePath: String) {
        switchCard(CARD_HISTORY)
        showLoading()
        graphBar.setBusy(true)
        object : SwingWorker<List<Commit>?, Void>() {
            override fun doInBackground(): List<Commit>? {
                val logOut = git.logFile(filePath).output
                if (logOut.isBlank()) return null
                return layoutCommitGraph(logOut, false)   // heavy work off the EDT
            }
            override fun done() {
                hideLoading(); graphBar.setBusy(false)
                val commits = try { get() } catch (_: Exception) { return }
                if (commits == null) { showError("File History", "No history found for: $filePath"); return }
                applyCommitList(commits)
                searchField.text = ""
            }
        }.execute()
    }

    private fun pushToTrackingBranch() {
        val remote = trackingRemote ?: return push()   // fallback to full dialog if no upstream
        val branch = trackingBranchRef ?: return push()
        val local  = currentBranchName
        val overlay = ProgressOverlay()
        object : SwingWorker<Boolean, String>() {
            override fun doInBackground(): Boolean {
                val r = git.pushRefspecStream(remote, local, branch,
                    force = false, setUpstream = false) { publish(it) }
                return r.success
            }
            override fun process(chunks: List<String>) { chunks.forEach { overlay.appendOutput(it) } }
            override fun done() {
                val ok = try { get() } catch (_: Exception) { false }
                overlay.finishStreaming(ok)
                refresh()
            }
        }.execute()
        overlay.show(SwingUtilities.getRootPane(this), "Pushing to $remote/$branch…")
    }

    fun openTerminal() = Thread {
        when (Settings.terminalPreset) {
            "gitbash" -> {
                val gitBash = com.gitnarwhal.backend.Git.GIT.removeSuffix("cmd\\git.exe") + "git-bash.exe"
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
    fun openInIDE() = Thread {
        val repoCmd   = git.configGet("gitnarwhal.ideCommand").output.trim()
        val globalCmd = Settings.ideCommand.trim()
        val template  = repoCmd.ifBlank { globalCmd }
        if (template.isNotBlank()) launchIde(template) else ideAutoDetect()
    }.start()

    /**
     * Runs a user-configured IDE command. Tokenized (not a raw string) so the repo
     * path stays a single argument even with spaces. If the template contains `$REPO`
     * it's substituted; otherwise the repo path is appended so the IDE opens the folder
     * (e.g. `code "C:\full\path"`) instead of just launching empty.
     * Routed through the shell so a bare `code` (= code.cmd on Windows) resolves.
     */
    private fun launchIde(template: String) {
        val tokens = template.split(" ").filter { it.isNotBlank() }
        val args = if (template.contains("\$REPO")) tokens.map { it.replace("\$REPO", path) }
                   else tokens + path
        Command.shell(*args.toTypedArray()).execute(path)
    }

    private fun ideAutoDetect() {
        // Try `code` on the PATH. Must go through the shell on Windows: `code` is
        // code.cmd, which ProcessBuilder can't launch directly (PATHEXT not applied).
        if (Command.find("code") != null) { Command.shell("code", path).execute(); return }
        // Windows: check common install locations (Code.exe is directly launchable)
        if (OS.CURRENT == OS.WINDOWS) {
            val candidates = listOfNotNull(
                System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\Microsoft VS Code\\Code.exe" },
                System.getenv("PROGRAMFILES")?.let  { "$it\\Microsoft VS Code\\Code.exe" },
                System.getenv("PROGRAMFILES(X86)")?.let { "$it\\Microsoft VS Code\\Code.exe" }
            ).filter { java.io.File(it).exists() }
            val exe = candidates.firstOrNull()
            if (exe != null) { Command(exe, path).execute(); return }
        }
        // macOS: open via Finder
        if (OS.CURRENT == OS.MAC) { Command("open", "-a", "Visual Studio Code", path).execute(); return }
        // Linux/fallback
        Command("code", path).execute()
    }
    fun openExplorer()  = Thread { (OS.EXPLORER + path).execute() }.start()
    fun openRemote()   = Thread { (OS.BROWSER  + git.remoteUrl().output).execute() }.start()

    fun selectCommit(hash: String) {
        val idx = filteredCommits.indexOfFirst { it.hash == hash || it.hash.startsWith(hash) || hash.startsWith(it.hash) }
        if (idx >= 0) {
            switchCard(CARD_HISTORY)
            commitTable.selectionModel.setSelectionInterval(idx, idx)
            commitTable.scrollRectToVisible(commitTable.getCellRect(idx, 0, true))
        }
    }

    private fun matchesIgnorePattern(filename: String): Boolean {
        val patterns = com.gitnarwhal.utils.Settings.diffIgnorePatterns
            .split(",", ";").map { it.trim() }.filter { it.isNotBlank() }
        if (patterns.isEmpty()) return false
        val leaf = java.nio.file.Path.of(filename.substringAfterLast("/"))
        return patterns.any { pat ->
            try { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$pat").matches(leaf) }
            catch (_: Exception) { false }
        }
    }

    // ── Commit search ─────────────────────────────────────────────────────────

    private fun filterCommits(query: String) {
        val q = query.trim().lowercase()
        val field = searchFilterCombo.selectedItem as? String ?: "All"
        filteredCommits.clear()
        filteredCommits.addAll(
            if (q.isEmpty()) commitList
            else commitList.filter { c ->
                runCatching {
                    when (field) {
                        "Message" -> c.title.lowercase().contains(q)
                        "Author"  -> c.author.lowercase().contains(q) || c.committer.lowercase().contains(q)
                        "Hash"    -> c.hash.startsWith(q, ignoreCase = true) || c.shortHash.startsWith(q, ignoreCase = true)
                        else      -> c.title.lowercase().contains(q) ||
                                     c.author.lowercase().contains(q) ||
                                     c.committer.lowercase().contains(q) ||
                                     c.hash.startsWith(q, ignoreCase = true) ||
                                     c.shortHash.startsWith(q, ignoreCase = true)
                    }
                }.getOrDefault(false)
            }
        )
        commitTableModel.fireTableDataChanged()
    }

    // ── .gitignore quick-add ──────────────────────────────────────────────────

    private fun showIgnoreDialog(filePath: String) {
        val name   = filePath.substringAfterLast("/")
        val ext    = if ('.' in name) "*.${name.substringAfterLast('.')}" else null
        val folder = if ('/' in filePath) "${filePath.substringBeforeLast('/')}/" else null
        val opts   = listOfNotNull(name, ext, folder).toTypedArray()
        val combo  = JComboBox(opts)
        val result = JOptionPane.showConfirmDialog(this, combo, "Add to .gitignore",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (result != JOptionPane.OK_OPTION) return
        java.io.File(git.repo, ".gitignore").appendText("\n${combo.selectedItem}\n")
        refreshFileStatus()
    }

    // ── File blame ────────────────────────────────────────────────────────────

    private data class BlameLine(
        val hash: String,
        val author: String,
        val date: String,
        val lineNum: Int,
        val code: String
    )

    private fun showBlame(file: String, rev: String?) {
        diffFileNameLabel.text = "$file  (blame)"
        object : SwingWorker<String, Void>() {
            override fun doInBackground() = git.blame(file, rev).output
            override fun done() {
                val out = try { get() } catch (e: Exception) { return }
                diffScrollPane.setViewportView(buildBlameView(out))
                diffScrollPane.revalidate()
            }
        }.execute()
    }

    private fun buildBlameView(blameOutput: String): JComponent {
        val regex = Regex("""^(\^?[0-9a-f]+)\s+\((.+?)\s+(\d{4}-\d{2}-\d{2})\s+(\d+)\)\s?(.*)$""")
        val lines = blameOutput.lines().mapNotNull { line ->
            val m = regex.find(line) ?: return@mapNotNull null
            BlameLine(
                hash    = m.groupValues[1].trimStart('^'),
                author  = m.groupValues[2].trim(),
                date    = m.groupValues[3],
                lineNum = m.groupValues[4].toIntOrNull() ?: 0,
                code    = m.groupValues[5]
            )
        }
        if (lines.isEmpty()) return JTextArea(blameOutput).apply {
            isEditable = false
            font       = Font(com.gitnarwhal.utils.Settings.diffFontFamily, Font.PLAIN, com.gitnarwhal.utils.Settings.diffFontSize)
            background = UIManager.getColor("EditorPane.background") ?: Color(0x2B, 0x2B, 0x2B)
            foreground = UIManager.getColor("Label.foreground") ?: Color.WHITE
        }

        val bgColor   = UIManager.getColor("EditorPane.background")     ?: Color(0x2B, 0x2B, 0x2B)
        val fgColor   = UIManager.getColor("Label.foreground")           ?: Color.WHITE
        val mutedFg   = UIManager.getColor("Label.disabledForeground")   ?: Color.GRAY
        val selBg     = UIManager.getColor("List.selectionBackground")   ?: Color(0x26, 0x4F, 0x78)
        val monoFont  = Font(com.gitnarwhal.utils.Settings.diffFontFamily, Font.PLAIN, com.gitnarwhal.utils.Settings.diffFontSize)

        val hashColors = LinkedHashMap<String, Color>()
        var ci = 0
        lines.forEach { hashColors.getOrPut(it.hash) { GRAPH_PALETTE[ci++ % GRAPH_PALETTE.size] } }

        val maxLn  = lines.maxOf { it.lineNum }.toString().length

        val model = DefaultListModel<BlameLine>().apply { lines.forEach { addElement(it) } }

        val cellH = monoFont.size + 6

        val list = object : JList<BlameLine>(model), Scrollable {
            override fun getPreferredScrollableViewportSize() = preferredSize
            override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = cellH
            override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height
            override fun getScrollableTracksViewportWidth()  = true
            override fun getScrollableTracksViewportHeight() = false
        }
        list.selectionMode    = ListSelectionModel.SINGLE_SELECTION
        list.background       = bgColor
        list.fixedCellHeight  = cellH

        list.setCellRenderer(object : ListCellRenderer<BlameLine> {
            private val stamp = object : JComponent() {
                var entry: BlameLine? = null
                var selected = false

                override fun paintComponent(g: Graphics) {
                    val bl = entry ?: return
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.font = monoFont
                    val fm = g2.fontMetrics

                    g2.color = if (selected) selBg else bgColor
                    g2.fillRect(0, 0, width, height)

                    val y = fm.ascent + (height - fm.height) / 2
                    var x = 4

                    g2.color = hashColors[bl.hash] ?: Color.GRAY
                    val hashStr = bl.hash.take(8)
                    g2.drawString(hashStr, x, y)
                    x += fm.stringWidth(hashStr) + fm.charWidth(' ')

                    g2.color = mutedFg
                    val authorStr = bl.author.take(14).padEnd(15)
                    g2.drawString(authorStr, x, y)
                    x += fm.stringWidth(authorStr)

                    val dateStr = "${bl.date}  "
                    g2.drawString(dateStr, x, y)
                    x += fm.stringWidth(dateStr)

                    val lnStr = bl.lineNum.toString().padStart(maxLn)
                    g2.drawString(lnStr, x, y)
                    x += fm.stringWidth(lnStr)

                    g2.color = mutedFg
                    val sep = " │ "
                    g2.drawString(sep, x, y)
                    x += fm.stringWidth(sep)

                    g2.color = fgColor
                    g2.drawString(bl.code, x, y)

                    g2.dispose()
                }
            }

            override fun getListCellRendererComponent(
                list: JList<out BlameLine>, value: BlameLine,
                index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                stamp.entry    = value
                stamp.selected = isSelected
                stamp.preferredSize = Dimension(list.width.coerceAtLeast(1), cellH)
                return stamp
            }
        })

        list.toolTipText = "Click a line to navigate to its commit in History"
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = list.locationToIndex(e.point)
                if (idx >= 0) selectCommit(model.getElementAt(idx).hash)
            }
        })

        return list
    }

    private fun menuItem(text: String, action: () -> Unit) =
        JMenuItem(text).apply { addActionListener { action() } }

    private fun confirm(message: String) =
        JOptionPane.showConfirmDialog(this, message, "Confirm",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION

    private fun showError(title: String, body: String) =
        JOptionPane.showMessageDialog(this, body.ifBlank { "(no output)" }, title, JOptionPane.ERROR_MESSAGE)

    private fun copyToClipboard(text: String) =
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
            java.awt.datatransfer.StringSelection(text), null)
}
