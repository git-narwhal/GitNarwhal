package com.gitnarwhal.backend

import java.io.File

/**
 * Helpers that keep working-tree status cheap in repos with many submodules.
 *
 * Plain `git status` descends into every submodule to see whether its working tree is
 * dirty (one stat pass per submodule), and `git submodule status` spawns several git
 * processes per submodule. With ~100 submodules that's seconds to tens of seconds on
 * Windows. So status is split in two phases:
 *  1. fast: `--ignore-submodules=dirty` (lists files + submodules with new commits);
 *  2. full: porcelain v2, which reports each submodule's dirty state in the same pass,
 *     converted back to v1 lines for the UI.
 */
object WorkingTreeStatus {

    /** Dirty state of a submodule as reported by porcelain v2's `S<c><m><u>` field. */
    data class SubmoduleState(val commitChanged: Boolean, val modified: Boolean, val untracked: Boolean) {
        /** Uncommitted content in the submodule's own working tree. */
        val dirty get() = modified || untracked
    }

    /** Porcelain v2 output converted to v1 lines, plus per-submodule state keyed by path. */
    data class FullStatus(val v1: String, val submodules: Map<String, SubmoduleState>)

    /**
     * Converts `git status --porcelain=v2 -b` output into porcelain v1 lines
     * (`XY path`, `XY orig -> path`, `?? path`, and a `## branch` header) and
     * collects the submodule states.
     */
    fun parseV2(out: String): FullStatus {
        val lines = mutableListOf<String>()
        val subs  = LinkedHashMap<String, SubmoduleState>()
        var head: String? = null
        fun xy(s: String) = s.replace('.', ' ')
        fun sub(field: String, path: String) {
            if (field.length == 4 && field[0] == 'S')
                subs[unquote(path)] = SubmoduleState(field[1] == 'C', field[2] == 'M', field[3] == 'U')
        }
        for (line in out.lines()) {
            when {
                line.startsWith("# branch.head ") -> head = line.removePrefix("# branch.head ")
                line.startsWith("1 ") -> {
                    val p = line.split(' ', limit = 9); if (p.size < 9) continue
                    sub(p[2], p[8]); lines += "${xy(p[1])} ${p[8]}"
                }
                line.startsWith("2 ") -> {
                    val p = line.split(' ', limit = 10); if (p.size < 10) continue
                    val parts = p[9].split('\t', limit = 2)
                    val path = parts[0]; val orig = parts.getOrElse(1) { path }
                    sub(p[2], path); lines += "${xy(p[1])} $orig -> $path"
                }
                line.startsWith("u ") -> {
                    val p = line.split(' ', limit = 11); if (p.size < 11) continue
                    lines += "${p[1]} ${p[10]}"
                }
                line.startsWith("? ") -> lines += "?? ${line.substring(2)}"
            }
        }
        if (head != null) lines.add(0, "## $head")
        return FullStatus(lines.joinToString("\n"), subs)
    }

    /**
     * Adds ` M path` lines for submodules known (from the last full status) to be dirty
     * but absent from a fast (`--ignore-submodules=dirty`) status, so they don't vanish
     * from the list between the fast and the full pass.
     */
    fun mergeDirtySubmodules(fastOut: String, dirtySubmodules: Collection<String>): String {
        if (dirtySubmodules.isEmpty()) return fastOut
        val listed = fastOut.lines()
            .filter { it.length > 3 && !it.startsWith("##") }
            .map { unquote(it.substring(3).substringAfter(" -> ")) }
            .toSet()
        val extra = dirtySubmodules.filter { it !in listed }.map { " M $it" }
        return if (extra.isEmpty()) fastOut else (fastOut.lines() + extra).joinToString("\n")
    }

    /** Strips C-style quoting git applies to unusual paths (`"a \"b\""` → `a "b"`). */
    fun unquote(path: String): String {
        if (path.length < 2 || !path.startsWith('"') || !path.endsWith('"')) return path
        val s = path.substring(1, path.length - 1)
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { bytes.write(c.toString().toByteArray()); i++; continue }
            val oct = s.substring(i + 1, minOf(i + 4, s.length))
            if (oct.length == 3 && oct.all { it in '0'..'7' }) {
                bytes.write(oct.toInt(8)); i += 4            // \303\271 → UTF-8 bytes
            } else {
                val n = s[i + 1]
                bytes.write(when (n) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> n }.toString().toByteArray()); i += 2
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }

    // ── Submodules without `git submodule status` ─────────────────────────────

    /** A submodule as recorded in the superproject index, plus its checked-out HEAD. */
    data class SubmoduleEntry(
        val path: String,
        val recordedHash: String,
        val headHash: String?,       // null when uninitialized or HEAD can't be resolved
        val initialized: Boolean,
        val conflicted: Boolean,
    ) {
        /** Checked-out commit differs from the recorded one (or the gitlink is conflicted). */
        val differentCommit get() = conflicted || (headHash != null && headHash != recordedHash)
    }

    /**
     * Parses `git ls-files -s` output and keeps gitlink (mode 160000) entries,
     * resolving each submodule's HEAD straight from disk via [readHead].
     */
    fun parseSubmodules(lsFilesOut: String, repo: File, readHead: (File) -> String? = ::readHead): List<SubmoduleEntry> {
        data class Raw(val hash: String, val stage: String, val path: String)
        val raws = lsFilesOut.lines().mapNotNull { line ->
            if (!line.startsWith("160000 ")) return@mapNotNull null
            val tab = line.indexOf('\t'); if (tab < 0) return@mapNotNull null
            val meta = line.substring(0, tab).split(' ')
            if (meta.size < 3) null else Raw(meta[1], meta[2], unquote(line.substring(tab + 1)))
        }
        return raws.groupBy { it.path }.map { (path, entries) ->
            val recorded = (entries.firstOrNull { it.stage == "0" } ?: entries.first()).hash
            val dir = File(repo, path)
            val initialized = File(dir, ".git").exists()
            SubmoduleEntry(
                path         = path,
                recordedHash = recorded,
                headHash     = if (initialized) readHead(dir) else null,
                initialized  = initialized,
                conflicted   = entries.any { it.stage != "0" },
            )
        }
    }

    /** Resolves a working tree's `.git` (directory, or `gitdir:` file) to its git dir. */
    fun gitDirOf(workTree: File): File? {
        val dotGit = File(workTree, ".git")
        if (dotGit.isDirectory) return dotGit
        if (!dotGit.isFile) return null
        val line = dotGit.readText().lineSequence().firstOrNull()?.trim() ?: return null
        if (!line.startsWith("gitdir:")) return null
        val target = File(line.removePrefix("gitdir:").trim())
        return (if (target.isAbsolute) target else File(workTree, target.path)).canonicalFile
    }

    private val SHA = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")

    /**
     * Reads the commit HEAD points to in [workTree] without spawning git: detached
     * HEAD, loose ref, or packed-refs (also through a linked worktree's `commondir`).
     * Returns null for an unborn branch or anything it can't resolve.
     */
    fun readHead(workTree: File): String? = runCatching {
        val gitDir = gitDirOf(workTree) ?: return null
        val head = File(gitDir, "HEAD").readText().trim()
        if (!head.startsWith("ref:")) return head.takeIf { SHA.matches(it) }
        val ref = head.removePrefix("ref:").trim()
        val common = File(gitDir, "commondir").takeIf { it.isFile }
            ?.let { File(gitDir, it.readText().trim()).canonicalFile } ?: gitDir
        for (d in listOf(gitDir, common)) {
            val f = File(d, ref)
            if (f.isFile) return f.readText().trim().takeIf { SHA.matches(it) }
        }
        File(common, "packed-refs").takeIf { it.isFile }?.useLines { seq ->
            seq.map { it.trim() }
                .firstOrNull { !it.startsWith("#") && !it.startsWith("^") && it.endsWith(" $ref") }
                ?.substringBefore(' ')
        }
    }.getOrNull()
}
