package com.gitnarwhal.components

import com.gitnarwhal.backend.Commit
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

class CommitGraphCell : DefaultTableCellRenderer() {

    companion object {
        const val LANE_W   = 14   // pixels per lane
        const val DOT_R    = 4    // commit dot radius
        const val H_OFFSET = 8    // left margin
    }

    private var commit: Commit? = null

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
    ): Component {
        super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column)
        commit = value as? Commit
        return this
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val c = commit ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val mid  = height / 2
            val dotX = H_OFFSET + c.x * LANE_W

            // Top half — vertical lines for lanes active above this row
            for ((lane, color) in c.graphTopLines) {
                g2.color = color
                g2.drawLine(H_OFFSET + lane * LANE_W, 0, H_OFFSET + lane * LANE_W, mid)
            }

            // Bottom half — vertical lines for lanes active below this row
            for ((lane, color) in c.graphBottomLines) {
                g2.color = color
                g2.drawLine(H_OFFSET + lane * LANE_W, mid, H_OFFSET + lane * LANE_W, height)
            }

            // Fork/merge lines — diagonals from the commit dot to parent lanes (bottom half)
            for ((parentLane, color) in c.graphForkLines) {
                g2.color = color
                g2.drawLine(dotX, mid, H_OFFSET + parentLane * LANE_W, height)
            }

            // Merge-in lines — diagonals from lanes above converging into the dot (top half)
            for ((childLane, color) in c.graphMergeLines) {
                g2.color = color
                g2.drawLine(H_OFFSET + childLane * LANE_W, 0, dotX, mid)
            }

            // Commit dot — draw on top of lines
            val isHead = c.isCurrentHead
            if (isHead) {
                // Hollow ring: fill with background first to erase lines inside, then draw ring
                g2.color  = background
                g2.fillOval(dotX - DOT_R, mid - DOT_R, DOT_R * 2, DOT_R * 2)
                g2.color  = c.color
                g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawOval(dotX - DOT_R, mid - DOT_R, DOT_R * 2, DOT_R * 2)
            } else {
                g2.color  = c.color
                g2.fillOval(dotX - DOT_R, mid - DOT_R, DOT_R * 2, DOT_R * 2)
                g2.color  = c.color.darker()
                g2.stroke = BasicStroke(1f)
                g2.drawOval(dotX - DOT_R, mid - DOT_R, DOT_R * 2, DOT_R * 2)
            }
        } finally {
            g2.dispose()
        }
    }
}
