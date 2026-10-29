package dev.zunehd

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Plain logic with no Android types, so it can be unit tested.

object Format {
    /** 0:07, 3:41, 1:02:03. */
    fun time(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }
}

object SessionPicker {
    data class Candidate(val pkg: String, val playing: Boolean)

    /**
     * Which media session to show, as an index into [c], or -1. The preferred app (Tidal) wins
     * unless it is paused while another app is actually playing and [followOthers] is on.
     */
    fun pick(c: List<Candidate>, preferred: String, followOthers: Boolean): Int {
        val mine = c.indexOfFirst { it.pkg == preferred }
        if (!followOthers) return mine
        val otherPlaying = c.indexOfFirst { it.playing && it.pkg != preferred }
        if (mine >= 0 && (c[mine].playing || otherPlaying < 0)) return mine
        if (otherPlaying >= 0) return otherPlaying
        return if (c.isEmpty()) -1 else 0
    }
}

data class HistoryEntry(
    val title: String,
    val artist: String,
    val album: String,
    val mediaId: String?,
    val pkg: String,
    val playedAt: Long,
)

object HistoryRules {
    const val LIMIT = 60

    fun key(e: HistoryEntry) = (e.title.trim() + "\u0000" + e.artist.trim()).lowercase(Locale.ROOT)

    /** Newest first; replaying a song moves it to the front instead of adding a duplicate. */
    fun add(list: List<HistoryEntry>, e: HistoryEntry, limit: Int = LIMIT): List<HistoryEntry> =
        (listOf(e) + list.filter { key(it) != key(e) }).take(limit)
}

/** Motion of the Zune pivot: big headers that slide along with the pages under them. */
object PivotMath {
    /** How far the header strip is shifted left at a fractional page [pos]. */
    fun stripOffset(starts: FloatArray, pos: Float): Float {
        if (starts.isEmpty()) return 0f
        val p = pos.coerceIn(0f, (starts.size - 1).toFloat())
        val i = floor(p).toInt()
        if (i + 1 >= starts.size) return starts[i]
        return starts[i] + (starts[i + 1] - starts[i]) * (p - i)
    }

    /** Page position while dragging, with rubber banding past the first and last page. */
    fun drag(start: Float, deltaPages: Float, count: Int): Float {
        val raw = start + deltaPages
        val last = max(0, count - 1).toFloat()
        return when {
            raw < 0f -> raw * 0.3f
            raw > last -> last + (raw - last) * 0.3f
            else -> raw
        }
    }

    /** Page to settle on after a drag that started on page [start], moving at [velocity] pages/s. */
    fun snapTarget(pos: Float, start: Float, velocity: Float, count: Int): Int {
        if (count <= 0) return 0
        val t = when {
            velocity > 0.5f -> floor(pos).toInt() + 1
            velocity < -0.5f -> ceil(pos).toInt() - 1
            else -> pos.roundToInt()
        }
        val base = start.roundToInt()
        return t.coerceIn(max(0, base - 1), min(count - 1, base + 1)).coerceIn(0, count - 1)
    }

    /** Current header white, the rest dimmed like on the device. */
    fun headerAlpha(i: Int, pos: Float) = 1f - 0.6f * min(1f, abs(i - pos))
}
