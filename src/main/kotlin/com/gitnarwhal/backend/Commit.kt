package com.gitnarwhal.backend

import com.gitnarwhal.views.RepoTab
import java.awt.Color
import java.lang.Exception
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*
import kotlin.reflect.KProperty

// ── Ref decoration types ───────────────────────────────────────────────────────
enum class RefType { HEAD, LOCAL_BRANCH, REMOTE_BRANCH, TAG }
data class RefInfo(val name: String, val type: RefType)

class Commit(var hash: String, val repoTab: RepoTab? = null) {
    //data for drawing commit (consumed by Swing graph renderer)
    var explored: Boolean = false
    var y = -1
    var x = -1

    // Graph lane colors & line data (assigned by RepoTab.applyCommits)
    var color: Color                          = Color.GRAY
    var refs: List<RefInfo>                   = emptyList()
    var isCurrentHead: Boolean                = false
    var graphTopLines: List<Pair<Int, Color>> = emptyList()  // (lane, color) active above this row
    var graphBottomLines: List<Pair<Int, Color>> = emptyList()  // (lane, color) active below this row
    var graphForkLines: List<Pair<Int, Color>> = emptyList()  // (parentLane, color) diagonals from dot
    var graphMergeLines: List<Pair<Int, Color>> = emptyList()  // (childLane, color) diagonals into dot (top half)

    //region Git show parameters
    private val show = GitShow()

    val shortHash       by show

    var author          by show
    var authorDate      by show

    var committer       by show
    var committerDate   by show
    val committerTimeStamp by show

    var title           by show

    val message         by show
    //endregion

    val childs  = arrayListOf<Commit>()
    val parents = arrayListOf<Commit>()

    override fun toString(): String = hash

    /**
     * Pre-populates GitShow data from a richer git-log pass so that
     * [title], [committer], [committerDate], etc. can be read on the EDT
     * without triggering a blocking git-show subprocess per commit.
     *
     * @param data list matching GitShow.linePositions indices:
     *   [0]=shortHash, [1]=author, [2]=authorDateUnix,
     *   [3]=committer,  [4]=committerDateUnix, [5]=title
     */
    fun prePopulate(data: List<String>) {
        show.commitShowData = data
    }
}

open class GitShow {
    /** Volatile: written in a SwingWorker background thread, read on EDT. */
    @Volatile var commitShowData: List<String>? = null

    val linePositions = hashMapOf(
            "shortHash" to 0,
            "author" to 1,
            "authorDate" to 2,
            "committer" to 3,
            "committerDate" to 4, "committerTimeStamp" to 4,
            "title" to 5
    )

    operator fun getValue(commit: Commit, property: KProperty<*>): String {
        if (commitShowData == null) {
            // NEVER block the EDT on a git-show subprocess: doing so per-cell during a
            // table repaint is the root of the "graph freezes / stops redrawing" bug.
            // Pre-populated commits never hit this path; if an un-populated commit somehow
            // reaches a renderer, return a placeholder and let a later refresh fill it in.
            if (javax.swing.SwingUtilities.isEventDispatchThread()) return "…"
            val repoTab = commit.repoTab
                ?: throw Exception("no repo context to load commit: ${commit.hash}")
            with(repoTab.git.show(commit)) {
                if (!success)
                    throw Exception("can't get data for commit: ${commit.hash}")
                commitShowData = output.lines()
            }
        }
        if (property.name == "message") {
            return commitShowData!!
                    .filterIndexed { index, _ -> index >= linePositions.count() }
                    .joinToString("\n")
        } else {
            var output = commitShowData!![linePositions[property.name]!!]
            // Empty fields (e.g. the synthetic "Uncommitted changes" node) must not be
            // date-parsed: "".toLong() throws NumberFormatException during cell painting,
            // which aborts the whole table repaint and leaves the graph half-drawn.
            if (property.name.contains("Date") && property.name != "committerTimeStamp" && output.isNotBlank()) {
                val epoch = output.toLongOrNull()
                if (epoch != null) {
                    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    output = formatter.format(Date.from(Instant.ofEpochSecond(epoch)))
                }
            }
            return output
        }
    }

    operator fun setValue(commit: Commit, property: KProperty<*>, any: Any) {
        TODO("Not yet implemented")
    }
}
