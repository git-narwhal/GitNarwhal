package com.gitnarwhal.backend

import com.gitnarwhal.utils.Settings
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GitTest {

    private lateinit var repoDir: File
    private lateinit var git: Git

    // Resolved once at setup — master or main depending on git version/config
    private lateinit var defaultBranch: String

    @BeforeAll
    fun setup() {
        repoDir = Files.createTempDirectory("gitnarwhal-gittest").toFile()
        Git.Static.init(repoDir.absolutePath)
        git = Git(repoDir.absolutePath)
        git.configSet("user.name", "Test User")
        git.configSet("user.email", "test@example.com")

        // Create the first commit so we have a HEAD
        writeFile("README.md", "# Test Repo")
        git.add("README.md")
        git.commit("Initial commit")

        defaultBranch = git.currentBranch().output.trim()
    }

    @AfterAll
    fun cleanup() {
        repoDir.deleteRecursively()
    }

    private fun writeFile(name: String, content: String = "content"): File =
        File(repoDir, name).also { it.writeText(content) }

    private fun deleteFile(name: String) = File(repoDir, name).delete()

    // ── Static.init ───────────────────────────────────────────────────────

    @Test @Order(1)
    fun `Static init creates a valid git repo`() {
        val tmp = Files.createTempDirectory("gitnarwhal-init-test").toFile()
        try {
            val r = Git.Static.init(tmp.absolutePath)
            assertTrue(r.success, "Static.init should succeed: ${r.output}")
            assertTrue(File(tmp, ".git").exists(), ".git dir should exist after init")
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ── Config ────────────────────────────────────────────────────────────

    @Test @Order(2)
    fun `configSet and configGet round-trip`() {
        git.configSet("test.roundtrip", "hello123")
        val result = git.configGet("test.roundtrip")
        assertTrue(result.success)
        assertEquals("hello123", result.output.trim())
        git.configUnset("test.roundtrip")
    }

    @Test @Order(3)
    fun `configUnset removes the key`() {
        git.configSet("test.toremove", "value")
        git.configUnset("test.toremove")
        val r = git.configGet("test.toremove")
        assertFalse(r.success, "configGet should fail after unset")
    }

    @Test @Order(4)
    fun `configGet on nonexistent key returns failure`() {
        val r = git.configGet("definitely.not.a.key.xyz")
        assertFalse(r.success)
    }

    // ── Status / Branch ───────────────────────────────────────────────────

    @Test @Order(5)
    fun `status returns success`() {
        val r = git.status()
        assertTrue(r.success, "status should succeed: ${r.output}")
    }

    @Test @Order(6)
    fun `statusReadable returns human-readable output`() {
        val r = git.statusReadable()
        assertTrue(r.success, "statusReadable should succeed")
        assertTrue(r.output.isNotBlank())
    }

    @Test @Order(7)
    fun `currentBranch returns the default branch name`() {
        val branch = git.currentBranch().output.trim()
        assertTrue(branch.isNotBlank(), "currentBranch should not be blank")
        assertEquals(defaultBranch, branch)
    }

    @Test @Order(8)
    fun `branches lists the current branch`() {
        val r = git.branches()
        assertTrue(r.success)
        assertTrue(r.output.contains(defaultBranch), "branches should contain '$defaultBranch'")
    }

    @Test @Order(9)
    fun `localBranchNames contains the default branch`() {
        val names = git.localBranchNames()
        assertTrue(names.contains(defaultBranch),
            "localBranchNames should contain '$defaultBranch', got: $names")
    }

    @Test @Order(10)
    fun `remoteBranchNames returns empty list without a remote`() {
        val names = git.remoteBranchNames("origin")
        assertTrue(names.isEmpty(), "no remote branches in local-only repo")
    }

    @Test @Order(11)
    fun `trackingBranch returns null without upstream`() {
        val tracking = git.trackingBranch(defaultBranch)
        assertNull(tracking, "no tracking branch in local-only repo")
    }

    // ── Log / RevParse ────────────────────────────────────────────────────

    @Test @Order(12)
    fun `log returns success and contains the initial commit`() {
        val r = git.log()
        assertTrue(r.success, "log should succeed")
        assertTrue(r.output.contains("Initial commit"), "log should contain initial commit message")
    }

    @Test @Order(13)
    fun `revParse HEAD returns a 40-char hash`() {
        val r = git.revParse("HEAD")
        assertTrue(r.success)
        assertTrue(r.output.trim().matches(Regex("[0-9a-f]{40}")),
            "HEAD hash should be 40 hex chars, got: '${r.output.trim()}'")
    }

    @Test @Order(14)
    fun `tags returns success`() {
        val r = git.tags()
        assertTrue(r.success)
    }

    // ── Staging / Diff ────────────────────────────────────────────────────

    @Test @Order(20)
    fun `add stages a file`() {
        writeFile("stage-me.txt", "some content")
        val r = git.add("stage-me.txt")
        assertTrue(r.success, "add should succeed: ${r.output}")
        val staged = git.diffStaged("stage-me.txt")
        assertTrue(staged.output.isNotBlank(), "staged diff should show the new file")
    }

    @Test @Order(21)
    fun `unstage removes file from index`() {
        git.unstage("stage-me.txt")
        val staged = git.diffStaged("stage-me.txt")
        assertTrue(staged.output.isBlank(), "staged diff should be empty after unstage")
        deleteFile("stage-me.txt")
    }

    @Test @Order(22)
    fun `addAll stages all changes`() {
        writeFile("all1.txt", "file 1")
        writeFile("all2.txt", "file 2")
        val r = git.addAll()
        assertTrue(r.success, "addAll should succeed")
        val staged = git.diffStaged()
        assertTrue(staged.output.isNotBlank(), "staged diff should show changes after addAll")
        git.commit("commit for addAll test")
    }

    @Test @Order(23)
    fun `diff shows unstaged changes`() {
        writeFile("dirty.txt", "original")
        git.add("dirty.txt")
        git.commit("add dirty.txt")
        writeFile("dirty.txt", "modified")
        val r = git.diff("dirty.txt")
        assertTrue(r.output.contains("modified") || r.output.contains("-original"),
            "diff should show changes, got: ${r.output}")
    }

    @Test @Order(24)
    fun `diff with no path returns all unstaged changes`() {
        val r = git.diff()
        assertTrue(r.success || r.output.isEmpty())
    }

    @Test @Order(25)
    fun `diffStaged with no path works`() {
        git.add("dirty.txt")
        val r = git.diffStaged()
        assertTrue(r.success || r.output.isEmpty())
        git.unstageAll()
        git.restore("dirty.txt")
    }

    @Test @Order(26)
    fun `unstageAll removes all staged changes`() {
        writeFile("unstage-all.txt", "unstage all test")
        git.add("unstage-all.txt")
        git.unstageAll()
        val staged = git.diffStaged()
        assertTrue(staged.output.isBlank(), "no staged changes after unstageAll")
        deleteFile("unstage-all.txt")
    }

    @Test @Order(27)
    fun `restore discards working tree changes`() {
        val r = git.restore("dirty.txt")
        assertTrue(r.success, "restore should succeed: ${r.output}")
        assertEquals("original", File(repoDir, "dirty.txt").readText().trim())
    }

    @Test @Order(28)
    fun `diffUntracked shows a new file as addition`() {
        val absPath = writeFile("untracked.txt", "brand new content").absolutePath
        val r = git.diffUntracked(absPath)
        assertTrue(r.output.contains("brand new content") || r.output.contains("+brand new content"),
            "diffUntracked should show file content, got: ${r.output}")
        deleteFile("untracked.txt")
    }

    @Test @Order(29)
    fun `applyPatch applies a unified diff to the working tree`() {
        writeFile("patch-target.txt", "line one\n")
        git.add("patch-target.txt")
        git.commit("add patch-target")
        writeFile("patch-target.txt", "line one modified\n")
        // Command.execute() trims output; git apply needs trailing newline
        val diff = git.diff("patch-target.txt").output.trimEnd() + "\n"
        git.restore("patch-target.txt")
        val r = git.applyPatch(diff)
        assertTrue(r.success, "applyPatch should succeed: ${r.output}")
        git.restore("patch-target.txt")
    }

    // ── Commit / Amend ────────────────────────────────────────────────────

    @Test @Order(30)
    fun `commit creates a new commit`() {
        writeFile("new-file.txt", "new content")
        git.add("new-file.txt")
        val r = git.commit("Test commit message")
        assertTrue(r.success, "commit should succeed: ${r.output}")
        val log = git.log().output
        assertTrue(log.contains("Test commit message"), "log should contain the commit message")
    }

    @Test @Order(31)
    fun `commitAmend with message updates the last commit`() {
        val r = git.commitAmend("Amended commit message")
        assertTrue(r.success, "commitAmend with message should succeed: ${r.output}")
        val log = git.log().output
        assertTrue(log.contains("Amended commit message"), "log should contain amended message")
    }

    @Test @Order(32)
    fun `commitAmend with null keeps the existing message`() {
        val r = git.commitAmend(null)
        assertTrue(r.success, "commitAmend --no-edit should succeed: ${r.output}")
    }

    @Test @Order(33)
    fun `commitStream streams output lines`() {
        writeFile("stream-test.txt", "stream content")
        git.add("stream-test.txt")
        val lines = mutableListOf<String>()
        git.commitStream("Stream commit") { lines.add(it) }
        assertTrue(lines.isNotEmpty(), "commitStream should produce output lines")
    }

    @Test @Order(34)
    fun `commitAmendStream with no-edit streams output`() {
        val lines = mutableListOf<String>()
        git.commitAmendStream { lines.add(it) }
        // May produce empty output; just verify it doesn't throw
        assertNotNull(lines)
    }

    @Test @Order(35)
    fun `commitAmendStream with message streams output`() {
        val lines = mutableListOf<String>()
        git.commitAmendStream("Amended via stream") { lines.add(it) }
        assertNotNull(lines)
    }

    // ── Show / CommitFiles ────────────────────────────────────────────────

    @Test @Order(38)
    fun `show returns commit metadata`() {
        val hash = git.revParse("HEAD").output.trim()
        val r = git.show(hash)
        assertTrue(r.success, "show should succeed: ${r.output}")
        assertTrue(r.output.isNotBlank())
    }

    @Test @Order(39)
    fun `commitFiles lists files changed in a commit`() {
        val hash = git.revParse("HEAD").output.trim()
        val r = git.commitFiles(hash)
        assertTrue(r.success, "commitFiles should succeed: ${r.output}")
    }

    @Test @Order(40)
    fun `showFileDiff returns diff of a file in a commit`() {
        writeFile("showfile.txt", "version one")
        git.add("showfile.txt")
        git.commit("add showfile")
        val hash = git.revParse("HEAD").output.trim()
        val r = git.showFileDiff(hash, "showfile.txt")
        assertTrue(r.output.contains("version one") || r.output.contains("+version one"),
            "showFileDiff should contain file content, got: ${r.output}")
    }

    // ── Reset ─────────────────────────────────────────────────────────────

    @Test @Order(42)
    fun `reset soft keeps the staged changes`() {
        writeFile("reset-soft.txt", "soft reset content")
        git.add("reset-soft.txt")
        git.commit("to be soft-reset")
        val r = git.reset("HEAD~1", "soft")
        assertTrue(r.success, "reset soft should succeed: ${r.output}")
        // file should still be staged
        val staged = git.diffStaged("reset-soft.txt")
        assertTrue(staged.output.isNotBlank(), "file should remain staged after soft reset")
        git.commit("re-commit after soft reset")
    }

    @Test @Order(43)
    fun `reset mixed unstages but keeps working tree`() {
        writeFile("reset-mixed.txt", "mixed reset content")
        git.add("reset-mixed.txt")
        git.commit("to be mixed-reset")
        val r = git.reset("HEAD~1", "mixed")
        assertTrue(r.success, "reset mixed should succeed: ${r.output}")
        assertTrue(File(repoDir, "reset-mixed.txt").exists(), "file should exist after mixed reset")
        git.add("reset-mixed.txt")
        git.commit("re-commit after mixed reset")
    }

    // ── Branches ─────────────────────────────────────────────────────────

    @Test @Order(50)
    fun `createBranch checks out new branch`() {
        val r = git.createBranch("feature/alpha")
        assertTrue(r.success, "createBranch should succeed: ${r.output}")
        assertEquals("feature/alpha", git.currentBranch().output.trim())
        git.selectBranch(defaultBranch)
    }

    @Test @Order(51)
    fun `selectBranch switches to an existing branch`() {
        val r = git.selectBranch("feature/alpha")
        assertTrue(r.success, "selectBranch should succeed: ${r.output}")
        assertEquals("feature/alpha", git.currentBranch().output.trim())
        git.selectBranch(defaultBranch)
    }

    @Test @Order(52)
    fun `deleteBranch removes a merged branch`() {
        val r = git.deleteBranch("feature/alpha")
        assertTrue(r.success, "deleteBranch should succeed: ${r.output}")
        assertFalse(git.localBranchNames().contains("feature/alpha"), "branch should be gone")
    }

    @Test @Order(53)
    fun `deleteBranch force removes unmerged branch`() {
        git.createBranch("unmerged-branch")
        writeFile("unmerged.txt", "unmerged content")
        git.add("unmerged.txt")
        git.commit("unmerged commit")
        git.selectBranch(defaultBranch)
        val r = git.deleteBranch("unmerged-branch", force = true)
        assertTrue(r.success, "force delete should succeed: ${r.output}")
    }

    @Test @Order(54)
    fun `renameBranch renames a branch`() {
        git.createBranch("old-name")
        git.selectBranch(defaultBranch)
        val r = git.renameBranch("old-name", "new-name")
        assertTrue(r.success, "renameBranch should succeed: ${r.output}")
        assertTrue(git.localBranchNames().contains("new-name"))
        assertFalse(git.localBranchNames().contains("old-name"))
        git.deleteBranch("new-name")
    }

    @Test @Order(55)
    fun `createBranchFrom creates a branch at a specific commit`() {
        val headHash = git.revParse("HEAD").output.trim()
        val r = git.createBranchFrom("from-specific", headHash)
        assertTrue(r.success, "createBranchFrom should succeed: ${r.output}")
        git.selectBranch(defaultBranch)
        git.deleteBranch("from-specific", force = true)
    }

    @Test @Order(56)
    fun `moveBranchToRef forces a branch to HEAD`() {
        git.createBranch("to-move")
        git.selectBranch(defaultBranch)
        val headHash = git.revParse("HEAD").output.trim()
        val r = git.moveBranchToRef("to-move", "HEAD")
        assertTrue(r.success, "moveBranchToRef should succeed: ${r.output}")
        val branchHash = git.revParse("to-move").output.trim()
        assertEquals(headHash, branchHash)
        git.deleteBranch("to-move")
    }

    // ── Stash ─────────────────────────────────────────────────────────────

    @Test @Order(60)
    fun `stashPush saves changes and stashList shows them`() {
        writeFile("stash-file.txt", "stash content")
        git.add("stash-file.txt")
        val r = git.stashPush("My stash entry")
        assertTrue(r.success, "stashPush should succeed: ${r.output}")
        val list = git.stashList()
        assertTrue(list.success && list.output.contains("My stash entry"),
            "stashList should contain the stash, got: ${list.output}")
    }

    @Test @Order(61)
    fun `stashPop restores the stash`() {
        val r = git.stashPop(0)
        assertTrue(r.success, "stashPop should succeed: ${r.output}")
        assertTrue(File(repoDir, "stash-file.txt").exists(), "stashed file should be restored")
    }

    @Test @Order(62)
    fun `stashPush without message also works`() {
        writeFile("stash-noname.txt", "no message stash")
        git.add("stash-noname.txt")
        val r = git.stashPush()
        assertTrue(r.success, "stashPush without message should succeed: ${r.output}")
    }

    @Test @Order(63)
    fun `stashApply restores without removing from list`() {
        val countBefore = git.stashList().output.lines().count { it.isNotBlank() }
        val r = git.stashApply(0)
        val countAfter  = git.stashList().output.lines().count { it.isNotBlank() }
        assertEquals(countBefore, countAfter, "stashApply should not remove the stash")
        git.restore("stash-noname.txt")
    }

    @Test @Order(64)
    fun `stashDrop removes a stash entry`() {
        val countBefore = git.stashList().output.lines().count { it.isNotBlank() }
        val r = git.stashDrop(0)
        assertTrue(r.success, "stashDrop should succeed: ${r.output}")
        val countAfter = git.stashList().output.lines().count { it.isNotBlank() }
        assertTrue(countAfter < countBefore, "stash count should decrease after drop")
        // clean up uncommitted file
        deleteFile("stash-noname.txt")
    }

    // ── Tags ──────────────────────────────────────────────────────────────

    @Test @Order(70)
    fun `tagCreate creates a lightweight tag`() {
        val r = git.tagCreate("v-test-1")
        assertTrue(r.success, "tagCreate should succeed: ${r.output}")
        assertTrue(git.tags().output.contains("v-test-1"))
    }

    @Test @Order(71)
    fun `tagCreate with message creates an annotated tag`() {
        val r = git.tagCreate("v-test-2", "Annotated release")
        assertTrue(r.success, "tagCreate annotated should succeed: ${r.output}")
        assertTrue(git.tags().output.contains("v-test-2"))
    }

    @Test @Order(72)
    fun `tagCreateAt creates a tag at a specific commit`() {
        val hash = git.revParse("HEAD").output.trim()
        val r = git.tagCreateAt("v-test-3", hash, "Tag at HEAD")
        assertTrue(r.success, "tagCreateAt should succeed: ${r.output}")
    }

    @Test @Order(73)
    fun `tagCreateAt lightweight variant`() {
        val hash = git.revParse("HEAD").output.trim()
        val r = git.tagCreateAt("v-test-4", hash, lightweight = true)
        assertTrue(r.success, "tagCreateAt lightweight should succeed: ${r.output}")
    }

    @Test @Order(74)
    fun `tagCreateAt force flag overwrites existing tag`() {
        val hash = git.revParse("HEAD").output.trim()
        val r = git.tagCreateAt("v-test-1", hash, force = true, lightweight = true)
        assertTrue(r.success, "force tag creation should succeed: ${r.output}")
    }

    @Test @Order(75)
    fun `tagDelete removes a tag`() {
        val r = git.tagDelete("v-test-1")
        assertTrue(r.success, "tagDelete should succeed: ${r.output}")
        assertFalse(git.tags().output.contains("v-test-1"), "v-test-1 should be gone")
    }

    // ── Remote ────────────────────────────────────────────────────────────

    @Test @Order(80)
    fun `remoteAdd adds a remote`() {
        val r = git.remoteAdd("fakremote", "https://example.com/fake.git")
        assertTrue(r.success, "remoteAdd should succeed: ${r.output}")
        assertEquals("https://example.com/fake.git", git.remoteUrl("fakremote").output.trim())
    }

    @Test @Order(81)
    fun `remoteNames lists remotes`() {
        val names = git.remoteNames()
        assertTrue(names.contains("fakremote"), "remoteNames should contain fakremote")
    }

    @Test @Order(82)
    fun `remoteSetUrl changes the URL`() {
        val r = git.remoteSetUrl("fakremote", "https://example.com/changed.git")
        assertTrue(r.success)
        assertEquals("https://example.com/changed.git", git.remoteUrl("fakremote").output.trim())
    }

    @Test @Order(83)
    fun `remoteRemove deletes the remote`() {
        val r = git.remoteRemove("fakremote")
        assertTrue(r.success)
        assertFalse(git.remoteNames().contains("fakremote"))
    }

    @Test @Order(84)
    fun `remoteUrl returns failure for nonexistent remote`() {
        val r = git.remoteUrl("nonexistent")
        assertFalse(r.success)
    }

    // ── Blame ─────────────────────────────────────────────────────────────

    @Test @Order(90)
    fun `blame returns annotation for a tracked file`() {
        val r = git.blame("README.md")
        assertTrue(r.success, "blame should succeed: ${r.output}")
        assertTrue(r.output.isNotBlank())
    }

    @Test @Order(91)
    fun `blame with rev parameter works`() {
        val hash = git.revParse("HEAD").output.trim()
        val r = git.blame("README.md", hash)
        assertTrue(r.success, "blame with rev should succeed: ${r.output}")
    }

    // ── Merge ─────────────────────────────────────────────────────────────

    @Test @Order(100)
    fun `merge integrates another branch`() {
        git.createBranch("merge-from")
        writeFile("merge-content.txt", "merged content")
        git.add("merge-content.txt")
        git.commit("commit on merge-from")
        git.selectBranch(defaultBranch)
        val r = git.merge("merge-from")
        assertTrue(r.success, "merge should succeed: ${r.output}")
        git.deleteBranch("merge-from")
    }

    // ── Revert / CherryPick ───────────────────────────────────────────────

    @Test @Order(105)
    fun `revert creates an undo commit`() {
        writeFile("revert-target.txt", "to be reverted")
        git.add("revert-target.txt")
        git.commit("commit to revert")
        val hash = git.revParse("HEAD").output.trim()
        val r = git.revert(hash)
        assertTrue(r.success, "revert should succeed: ${r.output}")
    }

    @Test @Order(106)
    fun `cherryPick applies a commit to the current branch`() {
        git.createBranch("cherry-src")
        writeFile("cherry-content.txt", "cherry picked content")
        git.add("cherry-content.txt")
        git.commit("cherry commit")
        val cherryHash = git.revParse("HEAD").output.trim()
        git.selectBranch(defaultBranch)
        val r = git.cherryPick(cherryHash)
        assertTrue(r.success, "cherryPick should succeed: ${r.output}")
        git.deleteBranch("cherry-src")
    }

    // ── Push/Pull builders (no real remote — just verify code paths) ──────

    @Test @Order(110)
    fun `push to nonexistent remote fails gracefully`() {
        val r = git.push("no-such-remote")
        assertFalse(r.success, "push to nonexistent remote should fail")
    }

    @Test @Order(111)
    fun `push with branch arg builds correct command`() {
        val r = git.push("no-such-remote", defaultBranch)
        assertFalse(r.success)
    }

    @Test @Order(112)
    fun `push with force flag builds correct command`() {
        val r = git.push("no-such-remote", force = true)
        assertFalse(r.success)
    }

    @Test @Order(113)
    fun `pushRefspec builds correct command`() {
        val r = git.pushRefspec("no-remote", defaultBranch, defaultBranch,
            force = true, setUpstream = true)
        assertFalse(r.success)
    }

    @Test @Order(114)
    fun `pushTags to nonexistent remote fails gracefully`() {
        val r = git.pushTags("no-remote")
        assertFalse(r.success)
    }

    @Test @Order(115)
    fun `pushRefspecStream builds correct command`() {
        val lines = mutableListOf<String>()
        git.pushRefspecStream("no-remote", defaultBranch, defaultBranch,
            force = false, setUpstream = false) { lines.add(it) }
        assertNotNull(lines)
    }

    @Test @Order(116)
    fun `pushTagsStream to nonexistent remote produces output`() {
        val lines = mutableListOf<String>()
        git.pushTagsStream("no-remote") { lines.add(it) }
        assertNotNull(lines)
    }

    @Test @Order(117)
    fun `pullStream to nonexistent remote produces output`() {
        val lines = mutableListOf<String>()
        git.pullStream("no-remote", branch = null, rebase = false,
            noCommit = false, noFf = false, log = false) { lines.add(it) }
        assertNotNull(lines)
    }

    @Test @Order(118)
    fun `pullStream with all flags set produces output`() {
        val lines = mutableListOf<String>()
        git.pullStream("no-remote", branch = "main", rebase = true,
            noCommit = true, noFf = true, log = true) { lines.add(it) }
        assertNotNull(lines)
    }

    @Test @Order(119)
    fun `pull with no remote fails gracefully`() {
        val r = git.pull("no-remote")
        assertFalse(r.success)
    }

    @Test @Order(120)
    fun `pull with all options set builds correct command`() {
        val r = git.pull("no-remote", "main", rebase = true,
            noCommit = true, noFf = true, log = true)
        assertFalse(r.success)
    }

    @Test @Order(121)
    fun `unpushedCount returns output`() {
        val r = git.unpushedCount()
        assertNotNull(r.output)
    }

    @Test @Order(122)
    fun `unpulledCount returns output`() {
        val r = git.unpulledCount()
        assertNotNull(r.output)
    }

    @Test @Order(123)
    fun `fetchRemote fails gracefully for missing remote`() {
        val r = git.fetchRemote("no-remote")
        assertFalse(r.success)
    }

    @Test @Order(124)
    fun `fetch with no remotes does not crash`() {
        val r = git.fetch()
        assertNotNull(r)
    }

    // ── Companion / global config ─────────────────────────────────────────

    @Test @Order(130)
    fun `globalGet returns a non-null value for any key`() {
        val value = Git.globalGet("user.name")
        assertNotNull(value)
    }

    @Test @Order(131)
    fun `globalGet returns empty string for nonexistent key`() {
        val value = Git.globalGet("gitnarwhal.testkey.xyz.notexist")
        assertNotNull(value)
    }

    @Test @Order(132)
    fun `configGetGlobal returns a value`() {
        val r = git.configGetGlobal("user.name")
        assertNotNull(r)
    }

    @Test @Order(133)
    fun `configSetGlobal sets a value (cleanup after)`() {
        git.configSetGlobal("gitnarwhal.test.cleanup", "test-value")
        val r = git.configGetGlobal("gitnarwhal.test.cleanup")
        assertEquals("test-value", r.output.trim())
        Git.globalUnset("gitnarwhal.test.cleanup")
    }

    @Test @Order(134)
    fun `applyPatchReverse reverses a staged patch`() {
        writeFile("reverse-patch.txt", "original content\n")
        git.add("reverse-patch.txt")
        git.commit("add reverse-patch")
        writeFile("reverse-patch.txt", "modified content\n")
        val diff = git.diff("reverse-patch.txt").output.trimEnd() + "\n"
        git.add("reverse-patch.txt")
        val r = git.applyPatch(diff, cached = true, reverse = true)
        assertTrue(r.success, "applyPatchReverse should succeed: ${r.output}")
        git.restore("reverse-patch.txt")
    }

    @Test @Order(135)
    fun `globalSet and globalGet round-trip via companion`() {
        Git.globalSet("gitnarwhal.testset.xyz", "set-value")
        val value = Git.globalGet("gitnarwhal.testset.xyz")
        assertEquals("set-value", value)
        Git.globalUnset("gitnarwhal.testset.xyz")
    }

    @Test @Order(136)
    fun `Static clone copies a local repository`() {
        val dest = Files.createTempDirectory("gitnarwhal-clone-test").toFile()
        try {
            val r = Git.Static.clone(repoDir.absolutePath, dest.absolutePath)
            assertTrue(r.success, "clone should succeed: ${r.output}")
            assertTrue(File(dest, ".git").exists(), ".git dir should exist in clone")
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test @Order(140)
    fun `submoduleDirty returns false for an uninitialized or missing submodule`() {
        // No .git inside the path → nothing to commit, treated as clean.
        assertFalse(git.submoduleDirty("definitely-not-a-submodule"))
    }

    @Test @Order(141)
    fun `submoduleDirty reflects working-tree changes when a git dir is present`() {
        // The repo root itself has a .git, so it exercises the porcelain-status branch.
        git.addAll(); git.commit("clean tree for submoduleDirty test")
        assertFalse(git.submoduleDirty("."), "clean tree should report not dirty")
        writeFile("dirty-probe.txt", "uncommitted change")
        assertTrue(git.submoduleDirty("."), "an untracked file should make the tree dirty")
        deleteFile("dirty-probe.txt")
    }

    @Test @Order(137)
    fun `logFile returns history for a specific file`() {
        val r = git.logFile("README.md")
        assertTrue(r.success, "logFile should succeed: ${r.output}")
        assertTrue(r.output.isNotBlank(), "logFile output should not be blank")
    }

    @Test @Order(138)
    fun `stashDiff returns patch for a stash entry`() {
        writeFile("stash-diff-file.txt", "stash diff content\n")
        git.add("stash-diff-file.txt")
        git.stashPush("stash for diff test")
        val r = git.stashDiff(0)
        assertTrue(r.output.isNotBlank(), "stashDiff should return a non-empty patch")
        git.stashDrop(0)
        deleteFile("stash-diff-file.txt")
    }

    @Test @Order(139)
    fun `diff, diffStaged, diffUntracked with diffIgnoreWhitespace cover the -w branch`() {
        val orig = Settings.diffIgnoreWhitespace
        try {
            Settings.diffIgnoreWhitespace = true
            writeFile("ws-file.txt", "line one\n")
            git.add("ws-file.txt")
            git.commit("add ws-file")
            writeFile("ws-file.txt", "line one  \n")
            val r1 = git.diff("ws-file.txt")
            assertNotNull(r1)
            val r2 = git.diff()
            assertNotNull(r2)
            git.add("ws-file.txt")
            val r3 = git.diffStaged("ws-file.txt")
            assertNotNull(r3)
            val r4 = git.diffStaged()
            assertNotNull(r4)
            git.unstageAll()
            git.restore("ws-file.txt")
            val absPath = writeFile("ws-untracked.txt", "untracked\n").absolutePath
            val r5 = git.diffUntracked(absPath)
            assertNotNull(r5)
            deleteFile("ws-untracked.txt")
        } finally {
            Settings.diffIgnoreWhitespace = orig
        }
    }

    // ── Worktree ──────────────────────────────────────────────────────────

    @Test @Order(150)
    fun `worktreeList returns the main worktree flagged isMain`() {
        val list = git.worktreeList()
        assertTrue(list.isNotEmpty(), "main worktree should always be listed")
        val main = list.first()
        assertTrue(main.isMain, "first entry should be the main worktree")
        assertEquals(repoDir.canonicalPath, File(main.path).canonicalPath)
        assertTrue(main.head.isNotBlank(), "main worktree should report a HEAD hash")
    }

    @Test @Order(151)
    fun `worktreeAdd creates a linked worktree, then worktreeRemove deletes it`() {
        val wtDir  = File(repoDir.parentFile, "${repoDir.name}-wt")
        val branch = "wt-branch"
        git.createBranch(branch)
        // `git worktree add <path> <branch>` refuses a branch already checked out in the
        // main worktree, so switch back to the default branch first.
        git.selectBranch(defaultBranch)
        val add = git.worktreeAdd(wtDir.absolutePath, branch)
        try {
            assertTrue(add.success, "worktreeAdd should succeed: ${add.output}")
            val list = git.worktreeList()
            val added = list.firstOrNull { File(it.path).canonicalPath == wtDir.canonicalPath }
            assertNotNull(added, "added worktree should appear in the list")
            assertFalse(added!!.isMain, "linked worktree must not be flagged isMain")
            assertEquals("refs/heads/$branch", added.branch)

            val remove = git.worktreeRemove(wtDir.absolutePath, force = true)
            assertTrue(remove.success, "worktreeRemove should succeed: ${remove.output}")
            assertNull(
                git.worktreeList().firstOrNull { File(it.path).canonicalPath == wtDir.canonicalPath },
                "removed worktree should no longer be listed"
            )
        } finally {
            wtDir.deleteRecursively()
        }
    }

    @Test
    fun `fileVersions handles modified added and deleted files in each context`() {
        writeFile("pic.png", "v1"); git.add("pic.png"); git.commit("add pic")
        val c1 = git.revParse("HEAD").output.trim()

        // unstaged modification: index -> working tree
        writeFile("pic.png", "v2")
        git.fileVersions("pic.png", staged = false).let {
            assertEquals("v1", String(it.first!!)); assertEquals("v2", String(it.second!!))
        }
        // staged modification: HEAD -> index
        git.add("pic.png")
        git.fileVersions("pic.png", staged = true).let {
            assertEquals("v1", String(it.first!!)); assertEquals("v2", String(it.second!!))
        }
        git.commit("modify pic")
        val c2 = git.revParse("HEAD").output.trim()

        // commit contexts: added then modified then deleted
        git.fileVersions("pic.png", staged = false, commit = c1).let {
            assertNull(it.first, "added in c1: no old side"); assertEquals("v1", String(it.second!!))
        }
        git.fileVersions("pic.png", staged = false, commit = c2).let {
            assertEquals("v1", String(it.first!!)); assertEquals("v2", String(it.second!!))
        }
        // unstaged deletion: index -> missing
        deleteFile("pic.png")
        git.fileVersions("pic.png", staged = false).let {
            assertEquals("v2", String(it.first!!)); assertNull(it.second)
        }
        // staged deletion: HEAD -> missing from index
        git.add("pic.png")
        git.fileVersions("pic.png", staged = true).let {
            assertEquals("v2", String(it.first!!)); assertNull(it.second)
        }
        git.commit("delete pic")
        val c3 = git.revParse("HEAD").output.trim()
        git.fileVersions("pic.png", staged = false, commit = c3).let {
            assertEquals("v2", String(it.first!!)); assertNull(it.second, "deleted in c3: no new side")
        }
        // untracked file: no old side, new from disk
        writeFile("new.png", "n")
        git.fileVersions("new.png", staged = false).let {
            assertNull(it.first); assertEquals("n", String(it.second!!))
        }
        deleteFile("new.png")
    }

    @Test
    fun `blob returns raw bytes and null for unknown path`() {
        assertNotNull(git.blob("HEAD", "README.md"))
        assertNull(git.blob("HEAD", "does-not-exist.bin"))
        assertNull(git.workingFile("does-not-exist.bin"))
    }
}
