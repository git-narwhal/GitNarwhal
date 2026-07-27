package com.gitnarwhal.components

import com.gitnarwhal.backend.Git
import com.gitnarwhal.utils.Settings
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseMotionAdapter
import javax.swing.*

class PullOverlay(private val git: Git) : JPanel(null) {

    // ── UI components ─────────────────────────────────────────────────────────

    private val remoteCombo       = JComboBox<String>()
    private val urlField          = JTextField().apply { isEditable = false }
    private val remoteBranchCombo = JComboBox<String>()
    private val localBranchLabel  = JLabel("—")
    private val refreshBtn        = JButton("Refresh")

    private val commitNowCk      = JCheckBox("Commit merged changes immediately").apply { isSelected = true }
    private val includeLogCk     = JCheckBox("Include messages from commits being merged in merge commit")
    private val noFfCk           = JCheckBox("Create a new commit even if fast-forward is possible")
    private val rebaseCk         = JCheckBox("Rebase instead of merge  (WARNING: make sure you haven't pushed your changes)")

    private val pullBtn   = JButton("Pull")
    private val cancelBtn = JButton("Cancel")

    // ── Glass-pane state ──────────────────────────────────────────────────────

    private var host: JComponent?     = null
    private var onDone: (() -> Unit)? = null

    private val remoteListener = java.awt.event.ActionListener { onRemoteChanged() }

    private val card: JPanel

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        isOpaque = false
        addMouseListener(object : MouseAdapter() {})
        addMouseMotionListener(object : MouseMotionAdapter() {})

        remoteCombo.addActionListener(remoteListener)

        rebaseCk.addActionListener {
            val rebase = rebaseCk.isSelected
            commitNowCk.isEnabled   = !rebase
            includeLogCk.isEnabled  = !rebase
            noFfCk.isEnabled        = !rebase
        }

        refreshBtn.addActionListener { fetchAndRefresh() }
        cancelBtn.addActionListener  { dismiss()        }
        pullBtn.addActionListener    { doPull()         }

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
            add(JLabel("Pull").apply {
                font = font.deriveFont(Font.BOLD, 12f)
                horizontalAlignment = SwingConstants.CENTER
            })
        }

        fun labelW() = Dimension(170, 24)

        fun formRow(labelText: String, comp: JComponent, extra: JComponent? = null) =
            JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                border   = BorderFactory.createEmptyBorder(3, 12, 3, 12)
                add(JLabel(labelText).apply { preferredSize = labelW() }, BorderLayout.WEST)
                if (extra != null) {
                    add(JPanel(BorderLayout(4, 0)).apply {
                        isOpaque = false
                        add(comp,  BorderLayout.CENTER)
                        add(extra, BorderLayout.EAST)
                    }, BorderLayout.CENTER)
                } else {
                    add(comp, BorderLayout.CENTER)
                }
            }

        val topSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(6, 0, 6, 0)
            add(formRow("Pull from remote:", remoteCombo))
            add(formRow("",                  urlField))
            add(formRow("Remote branch to pull:", remoteBranchCombo, refreshBtn))
            add(formRow("Pull into local branch:", localBranchLabel))
        }

        val optPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border   = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 12, 4, 12),
                BorderFactory.createTitledBorder("Options")
            )
            listOf(commitNowCk, includeLogCk, noFfCk, rebaseCk).forEach {
                it.isOpaque = false
                it.border   = BorderFactory.createEmptyBorder(2, 4, 2, 4)
                add(it)
            }
        }

        val bottomRow = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(0, 12, 4, 12)
            add(pullBtn); add(cancelBtn)
        }

        return JPanel(BorderLayout()).apply {
            background = bg
            border     = BorderFactory.createLineBorder(sep)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
                add(titleBar); add(topSection)
            }, BorderLayout.NORTH)
            add(optPanel,   BorderLayout.CENTER)
            add(bottomRow,  BorderLayout.SOUTH)
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
        val remotes: List<String>, val url: String,
        val remoteBranches: List<String>, val currentBranch: String,
        val trackingRemote: String?, val trackingBranch: String?
    )

    private fun loadData() {
        object : SwingWorker<LoadResult, Void>() {
            override fun doInBackground(): LoadResult {
                val current = git.currentBranch().output.trim()
                val tracking = git.trackingBranch(current)
                val remotes  = git.remoteNames().ifEmpty { listOf("origin") }
                val remote   = tracking?.first ?: remotes.first()
                return LoadResult(
                    remotes,
                    git.remoteUrl(remote).output.trim(),
                    git.remoteBranchNames(remote),
                    current,
                    tracking?.first,
                    tracking?.second
                )
            }
            override fun done() {
                val d = try { get() } catch (e: Exception) { return }
                remoteCombo.removeActionListener(remoteListener)
                remoteCombo.removeAllItems(); d.remotes.forEach { remoteCombo.addItem(it) }
                if (d.trackingRemote != null) remoteCombo.selectedItem = d.trackingRemote
                remoteCombo.addActionListener(remoteListener)
                remoteBranchCombo.removeAllItems(); d.remoteBranches.forEach { remoteBranchCombo.addItem(it) }
                if (d.trackingBranch != null) remoteBranchCombo.selectedItem = d.trackingBranch
                else remoteBranchCombo.selectedIndex = -1
                urlField.text         = d.url
                localBranchLabel.text = d.currentBranch.ifBlank { "—" }
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
                remoteBranchCombo.removeAllItems(); branches.forEach { remoteBranchCombo.addItem(it) }
            }
        }.execute()
    }

    private fun fetchAndRefresh() {
        val remote = remoteCombo.selectedItem as? String ?: return
        refreshBtn.isEnabled = false
        object : SwingWorker<List<String>, Void>() {
            override fun doInBackground(): List<String> {
                git.fetchRemote(remote)
                return git.remoteBranchNames(remote)
            }
            override fun done() {
                refreshBtn.isEnabled = true
                val branches = try { get() } catch (e: Exception) { return }
                remoteBranchCombo.removeAllItems(); branches.forEach { remoteBranchCombo.addItem(it) }
            }
        }.execute()
    }

    // ── Pull action ───────────────────────────────────────────────────────────

    private fun doPull() {
        val remote  = remoteCombo.selectedItem as? String ?: return
        val branch  = remoteBranchCombo.selectedItem as? String
        val rebase  = rebaseCk.isSelected
        val noCommit = !rebase && !commitNowCk.isSelected
        val noFf    = !rebase && noFfCk.isSelected
        val log     = !rebase && includeLogCk.isSelected
        val h       = host ?: return
        dismiss()
        val progress = ProgressOverlay()
        progress.show(h, "Pulling…", Settings::showOutputPull)
        object : SwingWorker<Boolean, String>() {
            override fun doInBackground(): Boolean =
                git.pullStream(remote, branch, rebase, noCommit, noFf, log) { publish(it) }.success
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
        val cw = 560.coerceAtMost(w - 40)
        val ch = 380.coerceAtMost(h - 40)
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
