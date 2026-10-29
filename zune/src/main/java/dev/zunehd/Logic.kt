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

/** One word on the home menu. Built-in items have plain ids; added apps are "app:<package>". */
data class MenuItem(val id: String, val label: String, val pkg: String? = null, val visible: Boolean = true) {
    val custom: Boolean get() = id.startsWith("app:")
}

/** The home menu's contents and order, as the user arranged them. */
object HomeMenu {
    val DEFAULT_IDS = listOf(
        "music", "videos", "pictures", "radio", "podcasts",
        "marketplace", "social", "news", "internet", "apps", "settings",
    )

    /** Items the launcher handles itself; the rest open an app. */
    val INTERNAL = setOf("music", "news", "apps", "settings")

    fun defaults() = DEFAULT_IDS.map { MenuItem(it, it) }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()

    fun encode(list: List<MenuItem>): String =
        list.joinToString("\n") { listOf(it.id, clean(it.label), it.pkg ?: "", if (it.visible) "1" else "0").joinToString("\t") }

    /** Parses a saved menu. Built-ins missing from it (say, added in a newer version) go at the end. */
    fun decode(s: String?): List<MenuItem> {
        if (s.isNullOrBlank()) return defaults()
        val items = s.split('\n').mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 4 || f[0].isEmpty()) return@mapNotNull null
            if (f[0] !in DEFAULT_IDS && !f[0].startsWith("app:")) return@mapNotNull null
            MenuItem(f[0], f[1].ifEmpty { f[0] }, f[2].ifEmpty { null }, f[3] != "0")
        }.distinctBy { it.id }
        val missing = DEFAULT_IDS.filter { id -> items.none { it.id == id } }.map { MenuItem(it, it) }
        return items + missing
    }

    fun move(list: List<MenuItem>, from: Int, delta: Int): List<MenuItem> {
        val to = from + delta
        if (from !in list.indices || to !in list.indices) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }
}

/** The fast-scroll letter strip: "#" then a to z. */
object Letters {
    val ALL = listOf("#") + ('a'..'z').map { it.toString() }

    fun of(label: String): String = label.firstOrNull()?.lowercaseChar()?.takeIf { it in 'a'..'z' }?.toString() ?: "#"

    /** The letter under the finger, moved to the nearest letter that has entries. */
    fun pick(fraction: Float, present: Set<String>): String? {
        if (present.isEmpty()) return null
        val i = (fraction * ALL.size).toInt().coerceIn(0, ALL.size - 1)
        for (d in 0 until ALL.size) {
            ALL.getOrNull(i + d)?.let { if (it in present) return it }
            ALL.getOrNull(i - d)?.let { if (it in present) return it }
        }
        return null
    }
}
