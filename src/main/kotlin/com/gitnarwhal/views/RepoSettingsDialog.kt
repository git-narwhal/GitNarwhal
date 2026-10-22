package com.gitnarwhal.views

import com.gitnarwhal.backend.Git
import com.gitnarwhal.utils.Command
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import java.io.File
import javax.swing.*
import javax.swing.table.AbstractTableModel

class RepoSettingsDialog(private val git: Git, owner: Window?) :
    JDialog(owner, "Repository Settings", ModalityType.APPLICATION_MODAL) {

    // ── Remote data ───────────────────────────────────────────────────────────
    // originalName/originalUrl are null for remotes added in this dialog
    private data class RemoteEntry(
        var name: String,
        var url: String,
        val originalName: String? = null,
        val originalUrl: String? = null,
    )

    private val remotes = mutableListOf<RemoteEntry>()
    private val removedRemoteNames = mutableSetOf<String>()

    private val remotesModel = object : AbstractTableModel() {
        private val cols = arrayOf("Name", "Path")
        override fun getRowCount()                      = remotes.size
        override fun getColumnCount()                   = 2
        override fun getColumnName(col: Int)            = cols[col]
        override fun isCellEditable(r: Int, c: Int)    = false
        override fun getValueAt(row: Int, col: Int): Any =
            if (col == 0) remotes[row].name else remotes[row].url
    }

    private val remotesTable = JTable(remotesModel).apply {
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        tableHeader.reorderingAllowed = false
        rowHeight = 22
    }

    // ── Advanced — IDE ────────────────────────────────────────────────────────
    private val ideField = JTextField(30)

    // ── Advanced — user info ──────────────────────────────────────────────────
    private val useGlobalCheckBox = JCheckBox("Use global user settings")
    private val fullNameField     = JTextField(30)
    private val emailField        = JTextField(30)

    // ── Init ──────────────────────────────────────────────────────────────────
    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        layout = BorderLayout()

        val tabs = JTabbedPane()
        tabs.addTab("Remotes",  buildRemotesTab())
        tabs.addTab("Advanced", buildAdvancedTab())

        add(tabs, BorderLayout.CENTER)
        add(buildBottomBar(), BorderLayout.SOUTH)

        loadRemotes()
        loadUserInfo()
        ideField.text = git.configGet("gitnarwhal.ideCommand").output.trim()

        pack()
        minimumSize = Dimension(600, 460)
        setLocationRelativeTo(owner)
    }

    // ── Remotes tab ───────────────────────────────────────────────────────────
    private fun buildRemotesTab(): JComponent {
        val panel = JPanel(BorderLayout(0, 6))
        panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        panel.add(JLabel("Remote repository paths"), BorderLayout.NORTH)
        panel.add(JScrollPane(remotesTable), BorderLayout.CENTER)

        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val addBtn    = JButton("Add")
        val editBtn   = JButton("Edit")
        val removeBtn = JButton("Remove")

        addBtn.addActionListener    { onAddRemote() }
        editBtn.addActionListener   { onEditRemote() }
        removeBtn.addActionListener { onRemoveRemote() }

        // Enable Edit/Remove only when row selected
        editBtn.isEnabled   = false
        removeBtn.isEnabled = false
        remotesTable.selectionModel.addListSelectionListener {
            val sel = remotesTable.selectedRow >= 0
            editBtn.isEnabled   = sel
            removeBtn.isEnabled = sel
        }

        btnPanel.add(addBtn); btnPanel.add(editBtn); btnPanel.add(removeBtn)
        panel.add(btnPanel, BorderLayout.SOUTH)
        return panel
    }

    private fun onAddRemote() {
        showRemoteEditor(null) { name, url ->
            remotes.add(RemoteEntry(name, url))
            remotesModel.fireTableRowsInserted(remotes.size - 1, remotes.size - 1)
        }
    }

    private fun onEditRemote() {
        val row = remotesTable.selectedRow
        if (row < 0) return
        val entry = remotes[row]
        showRemoteEditor(entry) { name, url ->
            entry.name = name
            entry.url  = url
            remotesModel.fireTableRowsUpdated(row, row)
        }
    }

    private fun onRemoveRemote() {
        val row = remotesTable.selectedRow
        if (row < 0) return
        val confirm = JOptionPane.showConfirmDialog(
            this,
            "Remove remote '${remotes[row].name}'?",
            "Remove Remote",
            JOptionPane.YES_NO_OPTION
        )
        if (confirm == JOptionPane.YES_OPTION) {
            remotes.removeAt(row).originalName?.let { removedRemoteNames += it }
            remotesModel.fireTableRowsDeleted(row, row)
        }
    }

    private fun showRemoteEditor(existing: RemoteEntry?, onOk: (name: String, url: String) -> Unit) {
        val nameField = JTextField(existing?.name ?: "", 20)
        val urlField  = JTextField(existing?.url  ?: "", 40)

        val form = JPanel(GridBagLayout())
        val gbc  = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            fill   = GridBagConstraints.HORIZONTAL
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0; form.add(JLabel("Name:"), gbc)
        gbc.gridx = 1;                gbc.weightx = 1.0; form.add(nameField, gbc)
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; form.add(JLabel("URL:"), gbc)
        gbc.gridx = 1;                gbc.weightx = 1.0; form.add(urlField, gbc)

        val title  = if (existing == null) "Add Remote" else "Edit Remote"
        val result = JOptionPane.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (result == JOptionPane.OK_OPTION) {
            val name = nameField.text.trim()
            val url  = urlField.text.trim()
            if (name.isNotBlank() && url.isNotBlank()) onOk(name, url)
        }
    }

    // ── Advanced tab ──────────────────────────────────────────────────────────
    private fun buildAdvancedTab(): JComponent {
        val outer = JPanel()
        outer.layout = BoxLayout(outer, BoxLayout.Y_AXIS)
        outer.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // Repo-specific ignore list
        val excludeFile = File(git.repo, ".git/info/exclude")
        val ignorePanel = JPanel(BorderLayout(6, 0))
        ignorePanel.border = BorderFactory.createTitledBorder("Repository-specific ignore list")
        val ignorePath = JTextField(excludeFile.absolutePath).apply { isEditable = false }
        val editIgnore = JButton("Edit")
        editIgnore.addActionListener {
            if (!excludeFile.exists()) excludeFile.writeText("# git ls-files --others --exclude-from=.git/info/exclude\n")
            try { Desktop.getDesktop().edit(excludeFile) } catch (_: Exception) {
                JOptionPane.showMessageDialog(this, "Could not open editor for: ${excludeFile.absolutePath}")
            }
        }
        ignorePanel.add(ignorePath, BorderLayout.CENTER)
        ignorePanel.add(editIgnore, BorderLayout.EAST)
        ignorePanel.maximumSize = Dimension(Int.MAX_VALUE, ignorePanel.preferredSize.height + 16)
        outer.add(ignorePanel)
        outer.add(Box.createVerticalStrut(8))

        // IDE command
        val idePanel = JPanel(BorderLayout(6, 0))
        idePanel.border = BorderFactory.createTitledBorder("IDE command (overrides global setting)")
        ideField.toolTipText = "Command used by 'Open in IDE'. Use \$REPO as placeholder. Leave blank to use global setting."
        idePanel.add(ideField, BorderLayout.CENTER)
        idePanel.maximumSize = Dimension(Int.MAX_VALUE, idePanel.preferredSize.height + 16)
        outer.add(idePanel)
        outer.add(Box.createVerticalStrut(8))

        // User info
        val userPanel = JPanel(GridBagLayout())
        userPanel.border = BorderFactory.createTitledBorder("User information")
        val gbc = GridBagConstraints().apply {
            insets  = Insets(3, 6, 3, 6)
            fill    = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
        userPanel.add(useGlobalCheckBox, gbc)

        val nameLabel  = JLabel("Full Name:")
        val emailLabel = JLabel("Email address:")
        nameLabel.horizontalAlignment  = SwingConstants.RIGHT
        emailLabel.horizontalAlignment = SwingConstants.RIGHT

        gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.25
        userPanel.add(nameLabel, gbc)
        gbc.gridx = 1; gbc.weightx = 0.75
        userPanel.add(fullNameField, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.25
        userPanel.add(emailLabel, gbc)
        gbc.gridx = 1; gbc.weightx = 0.75
        userPanel.add(emailField, gbc)

        useGlobalCheckBox.addActionListener { updateUserFieldsEnabled() }

        userPanel.maximumSize = Dimension(Int.MAX_VALUE, userPanel.preferredSize.height + 24)
        outer.add(userPanel)
        outer.add(Box.createVerticalGlue())
        return JScrollPane(outer).apply { border = null }
    }

    private fun updateUserFieldsEnabled() {
        val useGlobal = useGlobalCheckBox.isSelected
        fullNameField.isEnabled = !useGlobal
        emailField.isEnabled    = !useGlobal
    }

    // ── Load / save ───────────────────────────────────────────────────────────
    private fun loadRemotes() {
        val names = git.remoteList().output.lines().filter { it.isNotBlank() }
        for (name in names) {
            val url = git.remoteUrl(name).output.trim()
            remotes.add(RemoteEntry(name, url, originalName = name, originalUrl = url))
        }
        remotesModel.fireTableDataChanged()
    }

    private fun loadUserInfo() {
        val localName  = git.configGet("user.name").output.trim()
        val localEmail = git.configGet("user.email").output.trim()
        val globalName  = git.configGetGlobal("user.name").output.trim()
        val globalEmail = git.configGetGlobal("user.email").output.trim()

        val hasLocal = localName.isNotBlank() || localEmail.isNotBlank()
        useGlobalCheckBox.isSelected = !hasLocal

        fullNameField.text = if (hasLocal) localName  else globalName
        emailField.text    = if (hasLocal) localEmail else globalEmail
        updateUserFieldsEnabled()
    }

    private fun saveAndClose() {
        // Apply remote diffs (entries track their original name/url, so edits and renames are detected)
        val errors = mutableListOf<String>()
        fun run(c: Command) { if (!c.success) errors += c.output.trim() }

        // Removed
        for (name in removedRemoteNames) run(git.remoteRemove(name))

        // Renamed / URL changed
        for (r in remotes) {
            val origName = r.originalName ?: continue
            if (r.name != origName) run(git.remoteRename(origName, r.name))
            if (r.url != r.originalUrl) run(git.remoteSetUrl(r.name, r.url))
        }

        // Added
        for (r in remotes.filter { it.originalName == null }) run(git.remoteAdd(r.name, r.url))

        if (errors.isNotEmpty()) {
            JOptionPane.showMessageDialog(this, errors.joinToString("\n"), "Remote update failed", JOptionPane.ERROR_MESSAGE)
        }

        // IDE command (per-repo)
        val ideCmd = ideField.text.trim()
        if (ideCmd.isNotBlank()) git.configSet("gitnarwhal.ideCommand", ideCmd)
        else git.configUnset("gitnarwhal.ideCommand")

        // User info
        if (useGlobalCheckBox.isSelected) {
            git.configUnset("user.name")
            git.configUnset("user.email")
        } else {
            val name  = fullNameField.text.trim()
            val email = emailField.text.trim()
            if (name.isNotBlank())  git.configSet("user.name",  name)
            if (email.isNotBlank()) git.configSet("user.email", email)
        }
        dispose()
    }

    // ── Bottom bar ────────────────────────────────────────────────────────────
    private fun buildBottomBar(): JComponent {
        val bar = JPanel(BorderLayout())
        bar.border = BorderFactory.createEmptyBorder(6, 10, 10, 10)

        val editConfigBtn = JButton("Edit Config File...")
        editConfigBtn.addActionListener {
            val configFile = File(git.repo, ".git/config")
            try { Desktop.getDesktop().edit(configFile) } catch (_: Exception) {
                JOptionPane.showMessageDialog(this, "Could not open: ${configFile.absolutePath}")
            }
        }

        val okBtn     = JButton("OK")
        val cancelBtn = JButton("Cancel")
        okBtn.addActionListener     { saveAndClose() }
        cancelBtn.addActionListener { dispose() }

        val rightBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        rightBtns.add(okBtn)
        rightBtns.add(cancelBtn)

        bar.add(editConfigBtn, BorderLayout.WEST)
        bar.add(rightBtns,     BorderLayout.EAST)
        return bar
    }
}
