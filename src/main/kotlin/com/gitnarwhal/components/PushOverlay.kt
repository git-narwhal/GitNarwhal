package com.gitnarwhal.components

import com.gitnarwhal.backend.Git
import com.gitnarwhal.utils.Settings
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseMotionAdapter
import javax.swing.*
import javax.swing.table.AbstractTableModel

class PushOverlay(private val git: Git, private val repoName: String) : JPanel(null) {

    // ── Table model ───────────────────────────────────────────────────────────

    private data class Row(val local: String, var remote: String, var push: Boolean, var track: Boolean)

    private inner class PushTableModel : AbstractTableModel() {
        val rows = mutableListOf<Row>()
        private val cols = arrayOf("Push?", "Local branch", "Remote branch", "Track?")
        override fun getRowCount()            = rows.size
        override fun getColumnCount()         = 4
        override fun getColumnName(col: Int)  = cols[col]
        override fun getColumnClass(col: Int) = if (col == 0 || col == 3) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(r: Int, c: Int) = c != 1
        override fun getValueAt(r: Int, c: Int): Any = when (c) {
            0 -> rows[r].push; 1 -> rows[r].local; 2 -> rows[r].remote; else -> rows[r].track
        }
        override fun setValueAt(v: Any?, r: Int, c: Int) {
            when (c) {
                0 -> rows[r].push   = v as Boolean
                2 -> rows[r].remote = v as? String ?: ""
                3 -> rows[r].track  = v as Boolean
            }
            fireTableCellUpdated(r, c)
        }
    }

    // ── UI components ─────────────────────────────────────────────────────────

    private val tableModel    = PushTableModel()
    private val remoteCombo   = JComboBox<String>()
    private val urlField      = JTextField().apply { isEditable = false }
    private val branchEditor  = JComboBox<String>().apply { isEditable = true }
    private val table         = JTable(tableModel).apply {
        rowHeight = 24
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    }
    private val selectAllCk  = JCheckBox("Select All")
    private val pushTagsCk   = JCheckBox("Push all tags")
    private val forcePushCk  = JCheckBox("Force Push")
    private val pushBtn      = JButton("Push")
    private val cancelBtn    = JButton("Cancel")

    // ── Glass-pane state ──────────────────────────────────────────────────────

    private var host: JComponent?     = null
    private var onDone: (() -> Unit)? = null

    private val card: JPanel

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        isOpaque = false
        addMouseListener(object : MouseAdapter() {})
        addMouseMotionListener(object : MouseMotionAdapter() {})

        with(table.columnModel) {
            getColumn(0).apply { preferredWidth = 52; maxWidth = 60 }
            getColumn(1).preferredWidth = 180
            getColumn(2).preferredWidth = 220
            getColumn(3).apply { preferredWidth = 55; maxWidth = 65 }
            getColumn(2).cellEditor = DefaultCellEditor(branchEditor)
        }

        remoteCombo.addActionListener { onRemoteChanged() }

        selectAllCk.addActionListener {
            val v = selectAllCk.isSelected
            tableModel.rows.forEach { it.push = v }
            tableModel.fireTableDataChanged()
        }

        cancelBtn.addActionListener { dismiss() }
        pushBtn.addActionListener   { doPush()  }

        card = buildCard()
        add(card)
    }

    // ── Card layout ───────────────────────────────────────────────────────────

    private fun buildCard(): JPanel {
        val bg  = UIManager.getColor("Panel.background") ?: Color(0x2B, 0x2B, 0x2B)
        val sep = UIManager.getColor("Separator.foreground") ?: Color(0x55, 0x55, 0x55)

        val titleBar = JPanel(BorderLayout()).apply {
            background = bg
            border     = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, sep),
                BorderFactory.createEmptyBorder(7, 12, 7, 12)
            )
            add(JLabel("Push : $repoName").apply {
                font = font.deriveFont(Font.BOLD, 12f)
                horizontalAlignment = SwingConstants.CENTER
            })
        }

        val remoteRow = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(8, 12, 4, 12)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(JLabel("Push to repository:"))
                add(remoteCombo.apply { preferredSize = Dimension(100, 26) })
            }, BorderLayout.WEST)
            add(urlField, BorderLayout.CENTER)
        }

        val tablePanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border   = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 12, 4, 12),
                BorderFactory.createTitledBorder("Branches to push")
            )
            add(JScrollPane(table), BorderLayout.CENTER)
        }

        val selectRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(0, 8, 0, 8)
            add(selectAllCk)
        }

        val bottomRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(4, 12, 12, 12)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
                isOpaque = false; add(pushTagsCk); add(forcePushCk)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false; add(pushBtn); add(cancelBtn)
            }, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            background = bg
            border     = BorderFactory.createLineBorder(sep)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
                add(titleBar); add(remoteRow)
            }, BorderLayout.NORTH)
            add(tablePanel, BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
                add(selectRow); add(bottomRow)
            }, BorderLayout.SOUTH)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun show(host: JComponent, onDone: (() -> Unit)? = null) {
        this.host   = host
        this.onDone = onDone
        isVisible   = true
        OverlayHost.attach(host, this)
        repositionCard()
        loadData()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private data class LoadResult(
        val remotes: List<String>, val defaultRemote: String, val url: String,
        val currentBranch: String, val localBranches: List<String>, val remoteBranches: List<String>
    )

    private fun loadData() {
        object : SwingWorker<LoadResult, Void>() {
            override fun doInBackground(): LoadResult {
                val remotes  = git.remoteNames().ifEmpty { listOf("origin") }
                val remote   = remotes.first()
                return LoadResult(
                    remotes, remote,
                    git.remoteUrl(remote).output.trim(),
                    git.currentBranch().output.trim(),
                    git.localBranchNames(),
                    git.remoteBranchNames(remote)
                )
            }
            override fun done() {
                val d = try { get() } catch (e: Exception) { return }
                remoteCombo.removeAllItems(); d.remotes.forEach { remoteCombo.addItem(it) }
                urlField.text = d.url
                branchEditor.removeAllItems(); d.remoteBranches.forEach { branchEditor.addItem(it) }
                tableModel.rows.clear()
                d.localBranches.forEach { branch ->
                    val remBranch = if (d.remoteBranches.contains(branch)) branch else ""
                    tableModel.rows.add(Row(branch, remBranch, branch == d.currentBranch, false))
                }
                tableModel.fireTableDataChanged()
            }
        }.execute()
    }

    private fun onRemoteChanged() {
        val remote = remoteCombo.selectedItem as? String ?: return
        object : SwingWorker<Pair<String, List<String>>, Void>() {
            override fun doInBackground() =
                git.remoteUrl(remote).output.trim() to git.remoteBranchNames(remote)
            override fun done() {
                val (url, branches) = try { get() } catch (e: Exception) { return }
                urlField.text = url
                branchEditor.removeAllItems(); branches.forEach { branchEditor.addItem(it) }
                tableModel.rows.forEach { row ->
                    if (!branches.contains(row.remote)) row.remote = if (branches.contains(row.local)) row.local else ""
                }
                tableModel.fireTableDataChanged()
            }
        }.execute()
    }

    // ── Push action ───────────────────────────────────────────────────────────

    private fun doPush() {
        val remote   = remoteCombo.selectedItem as? String ?: return
        val force    = forcePushCk.isSelected
        val withTags = pushTagsCk.isSelected
        val toPush   = tableModel.rows.filter { it.push }
        if (toPush.isEmpty()) return
        val h = host ?: return
        dismiss()
        val progress = ProgressOverlay()
        progress.show(h, "Pushing…", Settings::showOutputPush)
        object : SwingWorker<Boolean, String>() {
            override fun doInBackground(): Boolean {
                var ok = true
                for (row in toPush) {
                    val target = row.remote.ifBlank { row.local }
                    val r = git.pushRefspecStream(remote, row.local, target, force, row.track) { publish(it) }
                    if (!r.success) ok = false
                }
                if (ok && withTags) {
                    val r = git.pushTagsStream(remote) { publish(it) }
                    if (!r.success) ok = false
                }
                return ok
            }
            override fun process(chunks: List<String>) { chunks.forEach { progress.appendOutput(it) } }
            override fun done() {
                val ok = try { get() } catch (e: Exception) { false }
                progress.finishStreaming(ok)
                if (ok) onDone?.invoke()
            }
        }.execute()
    }

    // ── Layout / paint ────────────────────────────────────────────────────────

    override fun doLayout() { super.doLayout(); repositionCard() }

    override fun paintComponent(g: Graphics) {
        (g as Graphics2D).color = Color(0, 0, 0, 160)
        g.fillRect(0, 0, width, height)
    }

    private fun repositionCard() {
        val w = width.coerceAtLeast(1); val h = height.coerceAtLeast(1)
        val cw = 720.coerceAtMost(w - 40)
        val ch = 420.coerceAtMost(h - 40)
        card.setBounds((w - cw) / 2, (h - ch) / 2, cw, ch)
        revalidate(); repaint()
    }

    private fun dismiss() {
        host ?: return
        isVisible = false
        OverlayHost.detach(this)
        host = null
    }
}
