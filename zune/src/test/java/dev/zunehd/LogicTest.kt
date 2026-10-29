package dev.zunehd

import org.junit.Assert.assertEquals
import org.junit.Test

class LogicTest {
    @Test fun formatsTimes() {
        assertEquals("0:00", Format.time(0))
        assertEquals("0:07", Format.time(7_400))
        assertEquals("3:41", Format.time(221_000))
        assertEquals("1:02:03", Format.time(3_723_000))
        assertEquals("0:00", Format.time(-5))
    }

    private fun c(pkg: String, playing: Boolean) = SessionPicker.Candidate(pkg, playing)

    @Test fun prefersTidal() {
        val list = listOf(c("spotify", false), c(Zune.TIDAL, false))
        assertEquals(1, SessionPicker.pick(list, Zune.TIDAL, followOthers = true))
    }

    @Test fun followsAnotherPlayerOnlyWhileItPlaysAndTidalDoesNot() {
        val list = listOf(c(Zune.TIDAL, false), c("youtube", true))
        assertEquals(1, SessionPicker.pick(list, Zune.TIDAL, followOthers = true))
        assertEquals(0, SessionPicker.pick(list, Zune.TIDAL, followOthers = false))
        val both = listOf(c("youtube", true), c(Zune.TIDAL, true))
        assertEquals(1, SessionPicker.pick(both, Zune.TIDAL, followOthers = true))
    }

    @Test fun noTidalSession() {
        assertEquals(-1, SessionPicker.pick(emptyList(), Zune.TIDAL, true))
        assertEquals(-1, SessionPicker.pick(listOf(c("youtube", true)), Zune.TIDAL, followOthers = false))
        assertEquals(0, SessionPicker.pick(listOf(c("youtube", false)), Zune.TIDAL, followOthers = true))
    }

    private fun e(title: String, artist: String, at: Long = 0) = HistoryEntry(title, artist, "", null, Zune.TIDAL, at)

    @Test fun historyMovesReplaysToTheFront() {
        var h = HistoryRules.add(emptyList(), e("One", "A"))
        h = HistoryRules.add(h, e("Two", "B"))
        h = HistoryRules.add(h, e("one ", "a", at = 5))
        assertEquals(listOf("one ", "Two"), h.map { it.title })
        assertEquals(5L, h[0].playedAt)
    }

    @Test fun historyIsCapped() {
        var h = emptyList<HistoryEntry>()
        repeat(10) { h = HistoryRules.add(h, e("t$it", "a"), limit = 4) }
        assertEquals(listOf("t9", "t8", "t7", "t6"), h.map { it.title })
    }

    @Test fun headerStripFollowsThePages() {
        val starts = floatArrayOf(0f, 100f, 260f)
        assertEquals(0f, PivotMath.stripOffset(starts, 0f), 0.001f)
        assertEquals(50f, PivotMath.stripOffset(starts, 0.5f), 0.001f)
        assertEquals(180f, PivotMath.stripOffset(starts, 1.5f), 0.001f)
        assertEquals(260f, PivotMath.stripOffset(starts, 2f), 0.001f)
        assertEquals(260f, PivotMath.stripOffset(starts, 2.4f), 0.001f)
        assertEquals(0f, PivotMath.stripOffset(starts, -0.3f), 0.001f)
    }

    @Test fun dragRubberBandsAtTheEnds() {
        assertEquals(0.5f, PivotMath.drag(0f, 0.5f, 3), 0.001f)
        assertEquals(-0.15f, PivotMath.drag(0f, -0.5f, 3), 0.001f)
        assertEquals(2.15f, PivotMath.drag(2f, 0.5f, 3), 0.001f)
    }

    @Test fun snapsOnePageAtATime() {
        assertEquals(1, PivotMath.snapTarget(0.2f, 0f, 2f, 4)) // flick forward
        assertEquals(0, PivotMath.snapTarget(0.2f, 0f, 0f, 4)) // slow, not far enough
        assertEquals(1, PivotMath.snapTarget(0.6f, 0f, 0f, 4)) // slow, past half
        assertEquals(1, PivotMath.snapTarget(1.9f, 1f, -3f, 4)) // flick back
        assertEquals(2, PivotMath.snapTarget(2.9f, 1f, 9f, 4)) // never skips two pages
        assertEquals(0, PivotMath.snapTarget(-0.1f, 0f, -5f, 4)) // stays in range
    }

    @Test fun dimsOtherHeaders() {
        assertEquals(1f, PivotMath.headerAlpha(1, 1f), 0.001f)
        assertEquals(0.4f, PivotMath.headerAlpha(3, 1f), 0.001f)
        assertEquals(0.7f, PivotMath.headerAlpha(1, 1.5f), 0.001f)
    }
}
