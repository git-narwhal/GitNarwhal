package com.gitnarwhal.components

import com.gitnarwhal.views.RepoTab
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPopupMenu
import javax.swing.SwingConstants

class BranchButton(
    val branchName: String,
    private val repo: RepoTab,
    selected: Boolean = false
) : JLabel(branchName, SwingConstants.LEFT) {

    var selected: Boolean = false
        set(value) {
            field = value
            putClientProperty("JComponent.outline", if (value) "focus" else null)
            font = font.deriveFont(if (value) java.awt.Font.BOLD else java.awt.Font.PLAIN)
            repaint()
        }

    var tracking: String? = null

    init {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        this.selected = selected
        componentPopupMenu = buildPopup()

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities_isLeft(e) && e.clickCount == 2) checkout()
            }
        })
    }

    private fun checkout() {
        val result = repo.git.checkoutSmart(branchName)
        if (result.success) {
            repo.refreshBranches()
        } else {
            showError("Checkout failed", result.output)
        }
    }

    private fun buildPopup(): JPopupMenu {
        val menu = JPopupMenu(branchName)
        menu.add(menuItem("Checkout")  { checkout() })
        menu.add(menuItem("Merge into current") {
            if (confirm("Merge '$branchName' into current branch?")) {
                val r = repo.git.merge(branchName)
                if (!r.success) showError("Merge failed", r.output)
                repo.refresh()
            }
        })
        menu.add(menuItem("Rebase current onto $branchName") {
            if (confirm("Rebase current branch onto '$branchName'?")) {
                val r = repo.git.rebase(branchName)
                if (!r.success) showError("Rebase failed", r.output)
                repo.refresh()
            }
        })
        menu.addSeparator()
        menu.add(menuItem("Rename…") {
            val newName = JOptionPane.showInputDialog(this, "New name for '$branchName':", branchName)
                ?.takeIf { it.isNotBlank() } ?: return@menuItem
            val r = repo.git.renameBranch(branchName, newName)
            if (!r.success) showError("Rename failed", r.output)
            repo.refreshBranches()
        })
        menu.add(menuItem("Delete") {
            if (confirm("Delete branch '$branchName'? (refuses if not merged)")) {
                val r = repo.git.deleteBranch(branchName, force = false)
                if (!r.success) {
                    if (confirm("Branch isn't merged. Force delete '$branchName'? (irreversible!)")) {
                        val rf = repo.git.deleteBranch(branchName, force = true)
                        if (!rf.success) showError("Force delete failed", rf.output)
                    }
                }
                repo.refreshBranches()
            }
        })
        return menu
    }

    private fun menuItem(text: String, action: () -> Unit) =
        javax.swing.JMenuItem(text).apply { addActionListener { action() } }

    private fun confirm(message: String): Boolean =
        JOptionPane.showConfirmDialog(this, message, "Confirm",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION

    private fun showError(title: String, body: String) {
        JOptionPane.showMessageDialog(this, body.ifBlank { "(no output)" }, title, JOptionPane.ERROR_MESSAGE)
    }

    @Suppress("FunctionName")
    private fun SwingUtilities_isLeft(e: MouseEvent) = javax.swing.SwingUtilities.isLeftMouseButton(e)
}
