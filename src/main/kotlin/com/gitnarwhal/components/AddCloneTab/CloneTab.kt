package com.gitnarwhal.components.AddCloneTab

import com.gitnarwhal.backend.Git
import com.gitnarwhal.components.ProgressOverlay
import com.gitnarwhal.utils.NativeFileChooser
import com.gitnarwhal.utils.Settings
import com.gitnarwhal.utils.toPath
import com.gitnarwhal.views.AddCloneTab
import com.gitnarwhal.views.RepoTab
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class CloneTab(private val addCloneTab: AddCloneTab) : JPanel(BorderLayout()) {

    val urlField  = JTextField()
    val destField = JTextField()
    val nameField = JTextField()

    private val cloneBtn = accentButton("Clone")

    init {
        isOpaque = false

        val form = JPanel().apply {
            isOpaque   = false
            layout     = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = 0f
        }

        form.add(title("Clone a repository"))
        form.add(Box.createVerticalStrut(6))
        form.add(subtitle("Clone a remote repository to a local folder"))
        form.add(Box.createVerticalStrut(28))

        // URL field
        urlField.putClientProperty("JTextField.placeholderText", "Repository URL:")
        urlField.alignmentX  = 0f
        urlField.maximumSize = Dimension(Int.MAX_VALUE, urlField.preferredSize.height)
        form.add(urlField)
        form.add(Box.createVerticalStrut(10))

        // Destination + Browse
        if (Settings.defaultCloneFolder.isNotBlank()) destField.text = Settings.defaultCloneFolder
        destField.putClientProperty("JTextField.placeholderText", "Destination Path:")
        val browseBtn = JButton("Browse")
        browseBtn.addActionListener {
            val win = SwingUtilities.getWindowAncestor(this)
            object : SwingWorker<java.io.File?, Void>() {
                override fun doInBackground() = NativeFileChooser.chooseDirectory(win, "Select Destination")
                override fun done() {
                    val dir = try { get() } catch (_: Exception) { return } ?: return
                    destField.text = dir.absolutePath
                }
            }.execute()
        }
        form.add(pathRow(destField, browseBtn))
        form.add(Box.createVerticalStrut(10))

        // Name field
        nameField.putClientProperty("JTextField.placeholderText", "Name:")
        nameField.alignmentX  = 0f
        nameField.maximumSize = Dimension(Int.MAX_VALUE, nameField.preferredSize.height)
        form.add(nameField)
        form.add(Box.createVerticalStrut(20))

        // Clone button
        cloneBtn.alignmentX = 0f
        cloneBtn.addActionListener { run() }
        form.add(cloneBtn)

        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border   = BorderFactory.createEmptyBorder(40, 48, 40, 48)
            add(form, BorderLayout.NORTH)
        }, BorderLayout.CENTER)

        // auto-fill name from URL
        urlField.document.addDocumentListener(SimpleDocListener {
            val derived = deriveNameFromUrl(urlField.text)
            if (derived.isNotEmpty() && nameField.text.isBlank()) nameField.text = derived
        })
    }

    private fun deriveNameFromUrl(url: String): String =
        url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git")

    fun run() {
        val url  = urlField.text.trim()
        val dest = destField.text.trim()
        val name = nameField.text.trim().ifBlank { deriveNameFromUrl(url) }

        if (url.isBlank())  { err("URL is required"); return }
        if (dest.isBlank()) { err("Destination is required"); return }
        if (name.isBlank()) { err("Could not derive a repo name — fill in Name manually"); return }

        val destPath = dest.toPath().resolve(name).toAbsolutePath().toString()
        if (File(destPath).exists()) { err("Destination already exists: $destPath"); return }

        cloneBtn.isEnabled = false
        val overlay = ProgressOverlay()
        object : SwingWorker<Pair<Boolean, String>, Void>() {
            override fun doInBackground(): Pair<Boolean, String> {
                val cmd = Git.Static.clone(url, destPath)
                return cmd.success to cmd.output
            }
            override fun done() {
                cloneBtn.isEnabled = true
                val (success, output) = try { get() }
                    catch (e: Exception) { false to (e.message ?: "unknown error") }
                overlay.finish(output, success)
                if (success) with(addCloneTab.mainView) {
                    val repo = RepoTab(destPath, name)
                    addTab(repo); selectTab(repo); closeTab(addCloneTab)
                }
            }
        }.execute()
        overlay.show(this, "Cloning $url")
    }

    private fun err(msg: String) =
        JOptionPane.showMessageDialog(this, msg, "Clone", JOptionPane.ERROR_MESSAGE)
}

internal class SimpleDocListener(private val onChange: () -> Unit) : DocumentListener {
    override fun insertUpdate(e: DocumentEvent?)  = onChange()
    override fun removeUpdate(e: DocumentEvent?)  = onChange()
    override fun changedUpdate(e: DocumentEvent?) = onChange()
}
