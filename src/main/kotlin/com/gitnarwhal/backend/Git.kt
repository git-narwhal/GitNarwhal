package com.gitnarwhal.backend

import com.gitnarwhal.utils.Command
import com.gitnarwhal.utils.GitDownloader
import com.gitnarwhal.utils.OS
import com.gitnarwhal.utils.Settings

class Git(val repo: String) {
    companion object {
        private const val INTERNAL_GIT = "./git/bin/git"

        private val WHERE = Command.find("git")

        val GIT: String = run {
            // User-defined path takes priority (requires restart to take effect)
            val custom = Settings.gitPath.trim()
            if (custom.isNotBlank() && java.io.File(custom).exists()) return@run custom
            when {
                WHERE != null -> WHERE.toString()
                java.io.File(INTERNAL_GIT + OS.EXE).exists() -> INTERNAL_GIT + OS.EXE
                else -> when (OS.CURRENT) {
                    OS.WINDOWS -> { GitDownloader.downloadWindowsGit(); INTERNAL_GIT + OS.EXE }
                    OS.LINUX   -> GitDownloader.downloadLinuxGit()
                    OS.MAC     -> GitDownloader.downloadMacGit()
                }
            }
        }

        /** Read/write git global config without a repo context. */
        fun globalGet(key: String) =
            Command(GIT, "config", "--global", "--get", key).execute().output.trim()
        fun globalSet(key: String, value: String) {
            Command(GIT, "config", "--global", key, value).execute()
        }
        fun globalUnset(key: String) {
            Command(GIT, "config", "--global", "--unset", key).execute()
        }
    }

    private fun git(vararg command: String, prependGit: Boolean = true): Command {
        var realCommand = command
        if (prependGit) {
            val list = realCommand.toMutableList()
            list.add(0, GIT)
            realCommand = list.toTypedArray()
        }
        return Command(*realCommand, path = repo).execute()
    }

    /** Like [git] but streams each output line to [onLine] as it arrives. */
    private fun gitStream(vararg command: String, onLine: (String) -> Unit): Command {
        val list = command.toMutableList().also { it.add(0, GIT) }
        return Command(*list.toTypedArray(), path = repo).execute(onLine = onLine)
    }

    fun commitStream(message: String, onLine: (String) -> Unit) =
        gitStream("commit", "-m", message, onLine = onLine)

    fun commitAmendStream(message: String? = null, onLine: (String) -> Unit) =
        if (message != null) gitStream("commit", "--amend", "-m", message, onLine = onLine)
        else                 gitStream("commit", "--amend", "--no-edit",   onLine = onLine)

    fun pullStream(
        remote: String = "origin", branch: String? = null,
        rebase: Boolean = false, noCommit: Boolean = false,
        noFf: Boolean = false, log: Boolean = false,
        onLine: (String) -> Unit
    ): Command {
        val parts = mutableListOf("pull")
        if (rebase)   parts += "--rebase"
        if (noCommit) parts += "--no-commit"
        if (noFf)     parts += "--no-ff"
        if (log)      parts += "--log"
        parts += remote
        if (branch != null) parts += branch
        return gitStream(*parts.toTypedArray(), onLine = onLine)
    }

    fun pushRefspecStream(
        remote: String, localBranch: String, remoteBranch: String,
        force: Boolean = false, setUpstream: Boolean = false,
        onLine: (String) -> Unit
    ): Command {
        val parts = mutableListOf("push")
        if (setUpstream) parts += "--set-upstream"
        if (force)       parts += "--force-with-lease"
        parts += remote
        parts += "$localBranch:$remoteBranch"
        return gitStream(*parts.toTypedArray(), onLine = onLine)
    }

    fun pushTagsStream(remote: String = "origin", onLine: (String) -> Unit) =
        gitStream("push", remote, "--tags", onLine = onLine)

    //region read
    fun currentBranch() = git("branch", "--show-current")
    fun revParse(ref: String) = git("rev-parse", ref)
    fun status()   = git("status", "--porcelain=v1", "-b", "--untracked-files=all")
    fun statusReadable() = git("status")
    fun fetch()              = git("fetch", "--all", "--prune")
    fun fetchRemote(remote: String) = git("fetch", remote, "--prune")
    fun branches() = git("branch", "--all", "-vv")

    fun localBranchNames(): List<String> {
        val out = git("branch", "--format=%(refname:short)")
        return if (out.success) out.output.lines().map { it.trim() }.filter { it.isNotBlank() } else emptyList()
    }

    fun remoteBranchNames(remote: String): List<String> {
        val out = git("branch", "-r", "--format=%(refname:short)")
        return if (out.success) out.output.lines()
            .map { it.trim() }
            .filter { it.startsWith("$remote/") && !it.contains("->") }
            .map { it.removePrefix("$remote/") }
            .filter { it.isNotBlank() }
        else emptyList()
    }

    /** Returns (remote, branchName) of the tracking branch for [branch], or null if none. */
    fun trackingBranch(branch: String): Pair<String, String>? {
        val remote = git("config", "branch.$branch.remote").takeIf { it.success }?.output?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val merge  = git("config", "branch.$branch.merge" ).takeIf { it.success }?.output?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val trackBranch = merge.removePrefix("refs/heads/")
        return if (trackBranch.isNotBlank()) remote to trackBranch else null
    }

    fun remoteNames(): List<String> {
        val out = remoteList()
        return if (out.success) out.output.lines().map { it.trim() }.filter { it.isNotBlank() } else emptyList()
    }
    fun tags()     = git("tag", "--list")
    /**
     * Returns one record per commit.
     * Format (fields separated by , records separated by ):
     *   hash | parentHashes | shortHash | author | authorDateUnix
     *       | committer | committerDateUnix | subject
     *
     * Using ASCII control chars as delimiters avoids conflicts with content.
     * Parsed by RepoTab.applyCommits() to pre-populate Commit metadata
     * without per-commit git-show calls on the EDT.
     */
    fun log() = git(
        "--no-pager", "log", "--all", "--date=unix",
        "--pretty=format:%x1E%H%x1F%P%x1F%h%x1F%aN <%aE>%x1F%ad%x1F%cN <%cE>%x1F%cd%x1F%s%x1F%D"
    )
    fun logFile(path: String) = git(
        "--no-pager", "log", "--all", "--follow", "--date=unix",
        "--pretty=format:%x1E%H%x1F%P%x1F%h%x1F%aN <%aE>%x1F%ad%x1F%cN <%cE>%x1F%cd%x1F%s%x1F%D",
        "--", path
    )
    /** Files changed in a commit: output is "STATUS\tpath" lines (M/A/D/R/C…). */
    fun commitFiles(hash: String) = git("diff-tree", "--no-commit-id", "-r", "--name-status", hash)
    /** Diff of a single file as it appears in a commit. */
    fun showFileDiff(hash: String, path: String) = git("--no-pager", "show", "--format=", hash, "--", path)
    /** Reverse-apply a patch (undo a commit's change to a file in working copy). */
    fun applyPatchReverse(patch: String) = applyPatch(patch, cached = false, reverse = true)

    fun diff(path: String? = null): Command {
        val args = mutableListOf("--no-pager", "diff")
        if (Settings.diffIgnoreWhitespace) args += "-w"
        if (path != null) { args += "--"; args += path }
        return git(*args.toTypedArray())
    }

    fun diffStaged(path: String? = null): Command {
        val args = mutableListOf("--no-pager", "diff", "--cached")
        if (Settings.diffIgnoreWhitespace) args += "-w"
        if (path != null) { args += "--"; args += path }
        return git(*args.toTypedArray())
    }

    /** Show an untracked file as a full-addition diff (git diff --no-index -- /dev/null <file>).
     *  Exit code 1 is normal when differences exist; always use .output. */
    fun diffUntracked(path: String): Command {
        val args = mutableListOf("--no-pager", "diff", "--no-index", "--")
        if (Settings.diffIgnoreWhitespace) args += "-w"
        args += "/dev/null"
        args += path
        return git(*args.toTypedArray())
    }
    fun remoteUrl(remote: String = "origin") = git("config", "--get", "remote.$remote.url")
    fun remoteList()                         = git("remote")
    fun remoteAdd(name: String, url: String) = git("remote", "add", name, url)
    fun remoteSetUrl(name: String, url: String) = git("remote", "set-url", name, url)
    fun remoteRemove(name: String)           = git("remote", "remove", name)
    fun configGet(key: String)               = git("config", "--get", key)
    fun configGetGlobal(key: String)         = git("config", "--global", "--get", key)
    fun configSet(key: String, value: String) = git("config", key, value)
    fun configSetGlobal(key: String, value: String) = git("config", "--global", key, value)
    fun configUnset(key: String)             = git("config", "--unset", key)

    /** Number of local commits not yet pushed to upstream (returns "0" on failure). */
    fun unpushedCount() = git("rev-list", "--count", "@{u}..HEAD")
    /** Number of upstream commits not yet pulled (returns "0" on failure). */
    fun unpulledCount() = git("rev-list", "--count", "HEAD..@{u}")

    fun show(commit: Commit) = show(commit.hash)
    fun show(commitHash: String) =
        git("--no-pager", "show", commitHash, "-s",
            "--pretty=format:" + arrayOf("%h", "%aN <%aE>", "%ad", "%cN <%cE>", "%cd", "%s", "%b").joinToString("%n"),
            "--date=unix")
    //endregion

    //region branch / checkout
    fun selectBranch(branch: String)        = git("checkout", branch)
    fun createBranch(branch: String)        = git("checkout", "-b", branch)
    fun createBranchFrom(branch: String, startPoint: String) = git("checkout", "-b", branch, startPoint)
    fun deleteBranch(branch: String, force: Boolean = false) =
        if (force) git("branch", "-D", branch) else git("branch", "-d", branch)
    fun renameBranch(old: String, new: String)       = git("branch", "-m", old, new)
    fun moveBranchToRef(branch: String, ref: String) = git("branch", "-f", branch, ref)
    //endregion

    //region staging
    fun add(fileName: String)     = git("add", "--", fileName)
    fun addAll()                  = git("add", "-A")
    fun restore(fileName: String)      = git("restore", "--", fileName)
    fun unstage(fileName: String)      = git("restore", "--staged", "--", fileName)
    fun checkoutOurs(fileName: String) = git("checkout", "--ours",   "--", fileName)
    fun checkoutTheirs(fileName: String) = git("checkout", "--theirs", "--", fileName)
    fun stopTracking(fileName: String) = git("rm", "--cached", "--", fileName)
    fun unstageAll()              = git("restore", "--staged", ".")

    /** Apply a unified diff patch. Pass [cached]=true to stage, [reverse]=true to undo. */
    fun applyPatch(patch: String, cached: Boolean = false, reverse: Boolean = false): Command {
        val tmp = java.io.File.createTempFile("gitnarwhal_", ".patch")
        return try {
            tmp.writeText(patch)
            val args = mutableListOf("apply", "--whitespace=nowarn")
            if (cached)  args += "--cached"
            if (reverse) args += "--reverse"
            args += tmp.absolutePath
            git(*args.toTypedArray())
        } finally {
            tmp.delete()
        }
    }
    //endregion

    //region commit / sync
    fun commit(message: String)   = git("commit", "-m", message)
    fun lastCommitMessage(): String = git("log", "-1", "--format=%B").output.trim()
    fun commitAmend(message: String? = null) =
        if (message != null) git("commit", "--amend", "-m", message) else git("commit", "--amend", "--no-edit")

    fun push(remote: String = "origin", branch: String? = null, force: Boolean = false): Command {
        val parts = mutableListOf("push", remote)
        if (branch != null) parts += branch
        if (force) parts += "--force-with-lease"
        return git(*parts.toTypedArray())
    }

    fun pushRefspec(
        remote: String, localBranch: String, remoteBranch: String,
        force: Boolean = false, setUpstream: Boolean = false
    ): Command {
        val parts = mutableListOf("push")
        if (setUpstream) parts += "--set-upstream"
        if (force)       parts += "--force-with-lease"
        parts += remote
        parts += "$localBranch:$remoteBranch"
        return git(*parts.toTypedArray())
    }

    fun pushTags(remote: String = "origin") = git("push", remote, "--tags")

    fun pull(
        remote: String = "origin", branch: String? = null,
        rebase: Boolean = false, noCommit: Boolean = false,
        noFf: Boolean = false, log: Boolean = false
    ): Command {
        val parts = mutableListOf("pull")
        if (rebase)   parts += "--rebase"
        if (noCommit) parts += "--no-commit"
        if (noFf)     parts += "--no-ff"
        if (log)      parts += "--log"
        parts += remote
        if (branch != null) parts += branch
        return git(*parts.toTypedArray())
    }

    fun merge(branch: String)  = git("merge", branch)
    fun mergeAbort()           = git("merge", "--abort")
    fun rebase(onto: String)   = git("rebase", onto)
    fun rebaseAbort()          = git("rebase", "--abort")
    fun rebaseContinue()       = git("rebase", "--continue")
    fun blame(path: String, rev: String? = null): Command {
        val args = mutableListOf("--no-pager", "blame", "--date=short")
        if (!rev.isNullOrBlank()) args += rev
        args += "--"; args += path
        return git(*args.toTypedArray())
    }
    fun cherryPick(hash: String)       = git("cherry-pick", hash)
    fun cherryPickContinue()           = git("cherry-pick", "--continue", "--no-edit")
    fun cherryPickAbort()              = git("cherry-pick", "--abort")
    fun revert(hash: String)           = git("revert", "--no-edit", hash)
    fun reset(target: String, mode: String = "mixed") = git("reset", "--$mode", target)
    //endregion

    //region stash / tag
    fun submoduleStatus()                 = git("submodule", "status")

    /**
     * True if the submodule at [subPath] (relative to this repo) has uncommitted
     * changes in its own working tree — i.e. the user should commit inside it.
     *
     * `git submodule status` only flags a *different commit* (gitlink mismatch); it does
     * NOT see a dirty working tree, so we inspect the submodule's own porcelain status.
     */
    fun submoduleDirty(subPath: String): Boolean {
        val dir = java.io.File(repo, subPath)
        if (!java.io.File(dir, ".git").exists()) return false   // uninitialized → nothing to commit
        return com.gitnarwhal.utils.Command(GIT, "status", "--porcelain", path = dir.absolutePath)
            .execute().output.isNotBlank()
    }
    fun stashList()                       = git("stash", "list")
    fun stashPush(message: String? = null) =
        if (message != null) git("stash", "push", "-m", message) else git("stash", "push")
    fun stashPop(index: Int = 0)          = git("stash", "pop", "stash@{$index}")
    fun stashDrop(index: Int = 0)         = git("stash", "drop", "stash@{$index}")
    fun stashApply(index: Int = 0)        = git("stash", "apply", "stash@{$index}")
    fun stashDiff(index: Int = 0)         = git("--no-pager", "stash", "show", "-p", "stash@{$index}")

    fun tagCreate(name: String, message: String? = null) =
        if (message != null) git("tag", "-a", name, "-m", message) else git("tag", name)

    fun tagCreateAt(
        name: String, hash: String,
        message: String? = null,
        force: Boolean = false,
        lightweight: Boolean = false
    ): Command {
        val args = mutableListOf("tag")
        if (force) args += "-f"
        if (!lightweight) {
            args += "-a"
            args += "-m"; args += (message?.ifBlank { null } ?: name)
        }
        args += name; args += hash
        return git(*args.toTypedArray())
    }

    fun tagDelete(name: String)                          = git("tag", "-d", name)
    fun pushTag(remote: String = "origin", tag: String)  = git("push", remote, tag)
    fun pushDeleteTag(remote: String, tag: String)       = git("push", remote, "--delete", tag)
    //endregion

    //region worktree
    data class WorktreeEntry(
        val path: String,
        val head: String,
        val branch: String?,   // null if bare or detached HEAD
        val isMain: Boolean
    )

    fun worktreeList(): List<WorktreeEntry> {
        val out = git("worktree", "list", "--porcelain")
        if (!out.success) return emptyList()
        val entries = mutableListOf<WorktreeEntry>()
        var path: String? = null
        var head: String? = null
        var branch: String? = null
        var isFirst = true
        for (raw in out.output.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("worktree ") -> {
                    // flush previous block
                    if (path != null && head != null) {
                        entries += WorktreeEntry(path, head, branch, isFirst)
                        isFirst = false
                    }
                    path = line.removePrefix("worktree "); head = null; branch = null
                }
                line.startsWith("HEAD ")     -> head   = line.removePrefix("HEAD ")
                line.startsWith("branch ")   -> branch = line.removePrefix("branch ")
            }
        }
        // flush last block
        if (path != null && head != null) entries += WorktreeEntry(path, head, branch, isFirst)
        return entries
    }

    fun worktreeAdd(path: String, branchOrCommit: String): Command =
        git("worktree", "add", path, branchOrCommit)

    fun worktreeRemove(path: String, force: Boolean = false): Command =
        if (force) git("worktree", "remove", "--force", path)
        else       git("worktree", "remove", path)
    //endregion

    //region init / clone (static-style helpers — no repo context required)
    object Static {
        fun init(path: String) = Command(GIT, "init", path = path).execute()
        fun clone(url: String, dest: String) = Command(GIT, "clone", url, dest).execute()
    }
    //endregion
}
