package com.gitnarwhal.backend

import com.gitnarwhal.backend.CommitGraphLayout.UNCOMMITTED_HASH
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitGraphLayoutTest {

    /** One git-log record in the [Git.log] format; [time] orders commits newest-first. */
    private fun rec(hash: String, parents: String, time: Int, title: String = hash, refs: String = "") =
        "\u001E$hash\u001F$parents\u001F${hash.take(7)}\u001FA <a@x>\u001F$time\u001FC <c@x>\u001F$time\u001F$title\u001F$refs"

    private fun lanes(lines: List<Pair<Int, Color>>) = lines.map { it.first }

    @Test
    fun `two branch tips above HEAD with uncommitted changes connect cleanly`() {
        // main(HEAD) <- origin/main, main(HEAD) <- origin/release, both ahead of HEAD
        val log = rec("a1", "m1", 40, refs = "origin/main") +
                  rec("r1", "m1", 39, refs = "origin/release") +
                  rec("m1", "p1", 30, refs = "HEAD -> main") +
                  rec("p1", "", 20)
        val c = CommitGraphLayout.layout(log, hasUncommitted = true)

        assertEquals(listOf(UNCOMMITTED_HASH, "a1", "r1", "m1", "p1"), c.map { it.hash })
        assertEquals(listOf(0, 1, 2, 0, 0), c.map { it.x })

        val (virt, a, r, head) = c
        assertEquals(Color.GRAY, virt.color)
        assertEquals(listOf(0), lanes(virt.graphBottomLines))
        // Tips never join the grey connector: they keep their own lane down to HEAD's row.
        assertTrue(a.graphForkLines.isEmpty() && r.graphForkLines.isEmpty())
        assertEquals(listOf(0), lanes(a.graphTopLines))
        assertEquals(listOf(0, 1), lanes(a.graphBottomLines))
        assertEquals(listOf(0, 1), lanes(r.graphTopLines))
        assertEquals(listOf(0, 1, 2), lanes(r.graphBottomLines))
        // HEAD: grey connector from above, both tips converge into its dot.
        assertEquals(listOf(0 to Color.GRAY), head.graphTopLines)
        assertEquals(listOf(1 to a.color, 2 to r.color), head.graphMergeLines)
        assertEquals(a.color, head.color)  // main keeps the colour of the first tip merging into it
        assertEquals(listOf(0 to head.color), head.graphBottomLines)
        assertTrue(head.isCurrentHead)
        assertEquals(listOf(RefInfo("main", RefType.LOCAL_BRANCH)), head.refs)
        assertTrue(virt.refs.isEmpty())
    }

    @Test
    fun `merge above HEAD with HEAD as second parent converges into HEAD, not the connector`() {
        val log = rec("mg", "o1 m1", 40) + rec("o1", "p1", 35) +
                  rec("m1", "p1", 30, refs = "HEAD -> main") + rec("p1", "", 20)
        val c = CommitGraphLayout.layout(log, hasUncommitted = true).associateBy { it.hash }
        val mg = c.getValue("mg"); val m1 = c.getValue("m1")
        assertEquals(1, mg.x)
        assertEquals(listOf(2), lanes(mg.graphForkLines))
        assertEquals(0, m1.x)
        assertEquals(listOf(2), lanes(m1.graphMergeLines))
        assertEquals(listOf(1), lanes(m1.graphForkLines))  // first parent p1 continues in o1's lane
    }

    @Test
    fun `uncommitted node sits directly above HEAD when HEAD is newest`() {
        val log = rec("m2", "m1", 30, refs = "HEAD -> main") + rec("m1", "", 20)
        val c = CommitGraphLayout.layout(log, hasUncommitted = true)
        assertEquals(listOf(0, 0, 0), c.map { it.x })
        assertEquals(listOf(0 to Color.GRAY), c[1].graphTopLines)
        assertTrue(c.all { it.graphForkLines.isEmpty() })
    }

    @Test
    fun `without uncommitted changes no virtual node is added`() {
        val log = rec("m2", "m1", 30, refs = "HEAD -> main") + rec("m1", "", 20)
        val c = CommitGraphLayout.layout(log)
        assertEquals(listOf("m2", "m1"), c.map { it.hash })
        assertTrue(c[0].graphTopLines.isEmpty())
        assertTrue(c[1].graphBottomLines.isEmpty())  // root closes its lane
    }

    @Test
    fun `merge commit opens a second lane for the merged parent`() {
        val log = rec("mg", "m1 f1", 40) + rec("f1", "b", 30) + rec("m1", "b", 25) + rec("b", "", 10)
        val c = CommitGraphLayout.layout(log).associateBy { it.hash }
        val mg = c.getValue("mg")
        assertEquals(0, mg.x)
        assertEquals(listOf(1), lanes(mg.graphForkLines))
        assertEquals(listOf(0), lanes(mg.graphBottomLines))  // new fork lane starts next row
        assertEquals(1, c.getValue("b").x)                  // m1 forks into f1's lane
        assertEquals(listOf(1), lanes(c.getValue("m1").graphForkLines))
    }

    @Test
    fun `limit stops parsing after n commits`() {
        val log = rec("c3", "c2", 30) + rec("c2", "c1", 20) + rec("c1", "", 10)
        assertEquals(listOf("c3", "c2"), CommitGraphLayout.layout(log, limit = 2).map { it.hash })
    }

    @Test
    fun `parseRefs classifies decorations`() {
        assertEquals(emptyList(), CommitGraphLayout.parseRefs(" "))
        assertEquals(
            listOf(
                RefInfo("main", RefType.LOCAL_BRANCH),
                RefInfo("origin/x", RefType.REMOTE_BRANCH),
                RefInfo("HEAD", RefType.HEAD),
                RefInfo("v1", RefType.TAG),
                RefInfo("origin/main", RefType.REMOTE_BRANCH),
                RefInfo("dev", RefType.LOCAL_BRANCH),
            ),
            CommitGraphLayout.parseRefs("HEAD -> main, HEAD -> origin/x, HEAD, tag: v1, origin/HEAD -> origin/main, origin/main, dev")
        )
    }
}
