package com.gitnarwhal.backend

import com.gitnarwhal.views.RepoTab
import java.awt.Color

/**
 * Commit-graph layout: parses `git log` output (see [Git.log] format) and computes
 * topological order, lane assignment and per-row line geometry. Pure data work —
 * no Swing access — so it can run off the EDT and be unit-tested.
 */
object CommitGraphLayout {

    const val UNCOMMITTED_HASH = "0000000000000000000000000000000000000000"

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

    fun layout(logOutput: String, hasUncommitted: Boolean = false, limit: Int = 0, repoTab: RepoTab? = null): List<Commit> {
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
            val commit = localMap.getOrPut(hash) { Commit(hash, repoTab) }
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

        // ── Uncommitted-changes virtual node ─────────────────────────────────
        // Laid out as a real (grey) child of HEAD sitting in the top row: its lane is a
        // grey connector running straight down to HEAD. Branches whose parent is HEAD
        // must NOT join that connector (they'd look like children of the working copy):
        // they keep their own lane down to HEAD's row and converge into HEAD's dot there.
        val virtualParents = HashMap<Commit, List<Commit>>()
        if (hasUncommitted && localList.isNotEmpty()) {
            val head = localList.firstOrNull { it.isCurrentHead } ?: localList.first()
            val virtual = Commit(UNCOMMITTED_HASH, repoTab).apply {
                prePopulate(listOf("", "", "", "", "", "Uncommitted changes"))
                y    = -1
                refs = emptyList()   // branch pills stay on HEAD
            }
            virtualParents[virtual] = listOf(head)
            localList.add(0, virtual)
        }

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

        val virtualHead = virtualParents.values.firstOrNull()?.first()
        var virtualLane = -1
        for (commit in localList) {
            val parents = virtualParents[commit] ?: commit.parents
            var topLines = laneHashes.indices
                .filter { laneHashes[it] != null }
                .map { it to laneColors[it] }
            val topSet = topLines.map { it.first }.toSet()  // lanes active before this row
            var mergeLines = emptyList<Pair<Int, Color>>()

            val laneIdx = laneHashes.indexOf(commit.hash)
            val col: Int
            if (commit === virtualHead && virtualLane >= 0) {
                // HEAD under the uncommitted node: sits on the grey connector's lane.
                // Every other lane waiting for HEAD (branch tips above it) converges
                // into the dot from above; HEAD's history continues in the colour of
                // the first of them (as it would without uncommitted changes).
                col = virtualLane
                val incoming = laneHashes.indices.filter { it != col && laneHashes[it] == commit.hash }
                mergeLines = incoming.map { it to laneColors[it] }
                topLines   = topLines.filterNot { it.first in incoming }
                incoming.forEach { laneHashes[it] = null }
                laneColors[col] = mergeLines.firstOrNull()?.second ?: nextColor()
                commit.color = laneColors[col]
            } else if (laneIdx >= 0) {
                col = laneIdx; commit.color = laneColors[col]
            } else {
                val c = if (commit in virtualParents) Color.GRAY else nextColor()
                col = allocateLane(commit.hash, c); commit.color = c
            }
            commit.x = col
            if (commit in virtualParents) virtualLane = col

            val forkLines = mutableListOf<Pair<Int, Color>>()
            if (parents.isEmpty()) {
                laneHashes[col] = null
            } else {
                val fp     = parents[0]
                val fpLane = laneHashes.indexOf(fp.hash)
                when {
                    // child of HEAD (not the virtual node): keep own lane down to HEAD's row
                    fp === virtualHead && commit !in virtualParents -> laneHashes[col] = fp.hash
                    fpLane >= 0 && fpLane != col -> {
                        laneHashes[col] = null
                        forkLines.add(fpLane to commit.color)  // use the branch's own color, not the ancestor's
                    }
                    fpLane < 0 -> laneHashes[col] = fp.hash
                    // fpLane == col: already correct
                }
                for (parent in parents.drop(1)) {
                    val pLane = laneHashes.indexOf(parent.hash)
                    if (pLane >= 0 && parent !== virtualHead) {
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
            commit.graphMergeLines  = mergeLines
        }

        return localList
    }

    fun parseRefs(decoration: String): List<RefInfo> {
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
}
