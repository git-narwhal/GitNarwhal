package com.gitnarwhal.backend

import com.gitnarwhal.backend.WorkingTreeStatus.SubmoduleState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkingTreeStatusTest {

    private val tmp: File = Files.createTempDirectory("gitnarwhal-wts").toFile()
    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private val sha1 = "a".repeat(40)
    private val sha2 = "b".repeat(40)

    // ── parseV2 ───────────────────────────────────────────────────────────

    @Test
    fun `parseV2 converts entries to v1 and collects submodule states`() {
        val out = """
            # branch.oid $sha1
            # branch.head main
            # branch.upstream origin/main
            # branch.ab +0 -0
            1 .M S.M. 160000 160000 160000 $sha1 $sha1 [SPOONI]/[spooni-scripts]
            1 .M S..U 160000 160000 160000 $sha1 $sha1 [VORP]/vorp_weaponsv2
            1 M. SC.. 160000 160000 160000 $sha1 $sha2 libs/bumped
            1 M. N... 100644 100644 100644 $sha1 $sha2 src/with space.txt
            2 R. N... 100644 100644 100644 $sha1 $sha1 R100 new name.txt${'\t'}old.txt
            u UU N... 100644 100644 100644 100644 $sha1 $sha1 $sha1 conflict.txt
            ? untracked.txt
            ! ignored.txt
        """.trimIndent()
        val full = WorkingTreeStatus.parseV2(out)
        assertEquals(
            listOf(
                "## main",
                " M [SPOONI]/[spooni-scripts]",
                " M [VORP]/vorp_weaponsv2",
                "M  libs/bumped",
                "M  src/with space.txt",
                "R  old.txt -> new name.txt",
                "UU conflict.txt",
                "?? untracked.txt",
            ),
            full.v1.lines()
        )
        assertEquals(
            mapOf(
                "[SPOONI]/[spooni-scripts]" to SubmoduleState(false, true, false),
                "[VORP]/vorp_weaponsv2"     to SubmoduleState(false, false, true),
                "libs/bumped"               to SubmoduleState(true, false, false),
            ),
            full.submodules
        )
        assertEquals(setOf("[SPOONI]/[spooni-scripts]", "[VORP]/vorp_weaponsv2"),
            full.submodules.filterValues { it.dirty }.keys)
    }

    @Test
    fun `parseV2 tolerates truncated lines and missing header`() {
        val full = WorkingTreeStatus.parseV2("1 .M\n2 R.\nu UU\n? a.txt")
        assertEquals("?? a.txt", full.v1)
        assertTrue(full.submodules.isEmpty())
    }

    // ── mergeDirtySubmodules ──────────────────────────────────────────────

    @Test
    fun `mergeDirtySubmodules adds only submodules missing from the fast output`() {
        val fast = "## main\n M libs/bumped\n?? new.txt"
        assertEquals(fast, WorkingTreeStatus.mergeDirtySubmodules(fast, emptySet()))
        assertEquals(fast, WorkingTreeStatus.mergeDirtySubmodules(fast, setOf("libs/bumped")))
        assertEquals("$fast\n M sub/dirty",
            WorkingTreeStatus.mergeDirtySubmodules(fast, setOf("libs/bumped", "sub/dirty")))
    }

    // ── unquote ───────────────────────────────────────────────────────────

    @Test
    fun `unquote decodes git C-style quoting`() {
        assertEquals("plain.txt", WorkingTreeStatus.unquote("plain.txt"))
        assertEquals("\"", WorkingTreeStatus.unquote("\""))
        assertEquals("a \"b\"\\c", WorkingTreeStatus.unquote("\"a \\\"b\\\"\\\\c\""))
        assertEquals("tab\there\nnl\r", WorkingTreeStatus.unquote("\"tab\\there\\nnl\\r\""))
        assertEquals("più.txt", WorkingTreeStatus.unquote("\"pi\\303\\271.txt\""))
        assertEquals("end\\", WorkingTreeStatus.unquote("\"end\\\""))
    }

    // ── parseSubmodules ───────────────────────────────────────────────────

    @Test
    fun `parseSubmodules keeps gitlinks and flags different and conflicted ones`() {
        listOf("same", "moved", "conf").forEach { File(tmp, "$it/.git").apply { parentFile.mkdirs(); writeText("gitdir: x") } }
        File(tmp, "uninit").mkdirs()
        val ls = """
            100644 $sha1 0${'\t'}README.md
            160000 $sha1 0${'\t'}same
            160000 $sha1 0${'\t'}moved
            160000 $sha1 0${'\t'}uninit
            160000 $sha1 1${'\t'}conf
            160000 $sha2 2${'\t'}conf
            160000 broken-no-tab
        """.trimIndent()
        val heads = mapOf("same" to sha1, "moved" to sha2, "conf" to sha2)
        val subs = WorkingTreeStatus.parseSubmodules(ls, tmp) { heads[it.name] }.associateBy { it.path }

        assertEquals(setOf("same", "moved", "uninit", "conf"), subs.keys)
        assertFalse(subs.getValue("same").differentCommit)
        assertTrue(subs.getValue("moved").differentCommit)
        assertEquals(sha2, subs.getValue("moved").headHash)
        with(subs.getValue("uninit")) { assertFalse(initialized); assertNull(headHash); assertFalse(differentCommit) }
        with(subs.getValue("conf")) { assertTrue(conflicted); assertTrue(differentCommit); assertEquals(sha1, recordedHash) }
    }

    // ── gitDirOf / readHead ───────────────────────────────────────────────

    private fun gitDir(name: String, head: String): File =
        File(tmp, name).apply { mkdirs(); File(this, "HEAD").writeText("$head\n") }

    @Test
    fun `readHead resolves loose ref through a gitdir file`() {
        val gd = gitDir("modules/sub", "ref: refs/heads/main")
        File(gd, "refs/heads").mkdirs(); File(gd, "refs/heads/main").writeText("$sha1\n")
        val wt = File(tmp, "wt/sub").apply { mkdirs(); File(this, ".git").writeText("gitdir: ../../modules/sub\n") }
        assertEquals(gd.canonicalFile, WorkingTreeStatus.gitDirOf(wt))
        assertEquals(sha1, WorkingTreeStatus.readHead(wt))
    }

    @Test
    fun `readHead handles absolute gitdir, detached HEAD and packed-refs`() {
        val detached = gitDir("detached", sha2)
        val wt1 = File(tmp, "wt1").apply { mkdirs(); File(this, ".git").writeText("gitdir: ${detached.absolutePath}") }
        assertEquals(sha2, WorkingTreeStatus.readHead(wt1))

        val wt2 = File(tmp, "wt2").apply { mkdirs() }
        val dotGit = File(wt2, ".git").apply { mkdirs(); File(this, "HEAD").writeText("ref: refs/heads/dev") }
        File(dotGit, "packed-refs").writeText("# pack-refs with: peeled\n$sha1 refs/heads/main\n^$sha2\n$sha2 refs/heads/dev\n")
        assertEquals(dotGit, WorkingTreeStatus.gitDirOf(wt2))
        assertEquals(sha2, WorkingTreeStatus.readHead(wt2))
    }

    @Test
    fun `readHead follows commondir of a linked worktree`() {
        val common = gitDir("common", "ref: refs/heads/main")
        File(common, "refs/heads").mkdirs(); File(common, "refs/heads/feature").writeText(sha2)
        val linked = gitDir("common/worktrees/wt", "ref: refs/heads/feature")
        File(linked, "commondir").writeText("../..\n")
        val wt = File(tmp, "linked-wt").apply { mkdirs(); File(this, ".git").writeText("gitdir: ${linked.absolutePath}") }
        assertEquals(sha2, WorkingTreeStatus.readHead(wt))
    }

    @Test
    fun `readHead returns null when unresolvable`() {
        assertNull(WorkingTreeStatus.gitDirOf(File(tmp, "nothing")))
        assertNull(WorkingTreeStatus.readHead(File(tmp, "nothing")))
        val bad = File(tmp, "bad").apply { mkdirs(); File(this, ".git").writeText("not a gitdir line") }
        assertNull(WorkingTreeStatus.gitDirOf(bad))
        val empty = File(tmp, "empty").apply { mkdirs(); File(this, ".git").writeText("") }
        assertNull(WorkingTreeStatus.gitDirOf(empty))
        val unborn = File(tmp, "unborn").apply { mkdirs(); File(this, ".git").mkdirs(); File(this, ".git/HEAD").writeText("ref: refs/heads/main") }
        assertNull(WorkingTreeStatus.readHead(unborn))
        val garbage = File(tmp, "garbage").apply { mkdirs(); File(this, ".git").mkdirs(); File(this, ".git/HEAD").writeText("zzz") }
        assertNull(WorkingTreeStatus.readHead(garbage))
    }
}
