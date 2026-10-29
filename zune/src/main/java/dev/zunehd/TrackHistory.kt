package dev.zunehd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Songs played in the music app, for quickplay "history" like the Zune HD kept. */
class TrackHistory(ctx: Context) {
    private val file = File(ctx.filesDir, "history.json")
    private val artDir = File(ctx.filesDir, "art").apply { mkdirs() }
    private val cache = LruCache<String, Bitmap>(40)

    var entries: List<HistoryEntry> = load()
        private set

    fun add(e: HistoryEntry) {
        entries = HistoryRules.add(entries, e)
        save()
        val keep = entries.map { artFile(it).name }.toSet()
        artDir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    fun saveArt(e: HistoryEntry, art: Bitmap) {
        val f = artFile(e)
        if (f.exists()) return
        runCatching {
            f.outputStream().use { ArtLoader.thumbnail(art, 240).compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }
    }

    fun art(e: HistoryEntry): Bitmap? {
        val f = artFile(e)
        cache.get(f.name)?.let { return it }
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.also { cache.put(f.name, it) }
    }

    private fun artFile(e: HistoryEntry) =
        File(artDir, Integer.toHexString(HistoryRules.key(e).hashCode()) + ".jpg")

    private fun load(): List<HistoryEntry> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            HistoryEntry(
                o.getString("title"), o.optString("artist"), o.optString("album"),
                o.optString("mediaId").ifEmpty { null }, o.optString("pkg", Zune.TIDAL), o.optLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val a = JSONArray()
        for (e in entries) {
            a.put(
                JSONObject().put("title", e.title).put("artist", e.artist).put("album", e.album)
                    .put("mediaId", e.mediaId ?: "").put("pkg", e.pkg).put("at", e.playedAt),
            )
        }
        runCatching { file.writeText(a.toString()) }
    }
}
