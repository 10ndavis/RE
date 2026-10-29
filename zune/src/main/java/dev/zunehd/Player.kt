package dev.zunehd

import android.app.NotificationManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast

/**
 * The music app's media session, embedded. Tidal publishes a MediaSession like every Android
 * player; with notification access we can read its track, art, queue and custom actions
 * (favourite, shuffle, repeat) and drive it, so the launcher behaves like the Zune's own player.
 */
object Player {
    fun interface Listener {
        fun onPlayerChanged()
    }

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var manager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var sessions: List<MediaController> = emptyList()

    var controller: MediaController? = null
        private set
    var art: Bitmap? = null
        private set
    var blurredArt: Bitmap? = null
        private set
    private var artKey: String? = null
    private var lastRecorded: String? = null

    lateinit var history: TrackHistory
        private set

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        history = TrackHistory(app)
        manager = app.getSystemService(MediaSessionManager::class.java)
        app.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = LockScreenActivity.showIfPlaying(app)
        }, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF))
        connect()
    }

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    private fun changed() = listeners.toList().forEach { it.onPlayerChanged() }

    private fun component() = ComponentName(app, MediaListener::class.java)

    fun hasAccess(ctx: Context): Boolean {
        val cn = ComponentName(ctx, MediaListener::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            return ctx.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(cn)
        }
        val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == cn }
    }

    fun requestAccess(ctx: Context) {
        val music = appLabel(ctx, Prefs(ctx).musicPackage)
        if (android.os.Build.VERSION.SDK_INT >= 33 && ctx is android.app.Activity) {
            // Android 13+ blocks notification access for apps installed from a file until the
            // user unlocks "restricted settings" for the app, so walk them through it.
            android.app.AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("connect $music")
                .setMessage(
                    "android blocks music access for apps installed from a file.\n\n" +
                        "1. tap app info\n" +
                        "2. tap ⋮ in the top corner, then allow restricted settings\n" +
                        "3. come back and tap music access, then turn on Zune HD\n\n" +
                        "if there's no ⋮ menu, try music access once first. it only appears after a blocked attempt.",
                )
                .setPositiveButton("music access") { _, _ -> openAccessSettings(ctx) }
                .setNeutralButton("app info") { _, _ ->
                    start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }
                .show()
            return
        }
        openAccessSettings(ctx)
        Toast.makeText(ctx, "allow ${app.getString(R.string.app_name)} so it can show and control $music", Toast.LENGTH_LONG).show()
    }

    private fun openAccessSettings(ctx: Context) {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, MediaListener::class.java).flattenToString())
        val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        start(ctx, if (android.os.Build.VERSION.SDK_INT >= 30 && detail.resolveActivity(ctx.packageManager) != null) detail else list)
    }

    /** (Re)attach to the system's list of media sessions. Harmless to call often. */
    fun connect() {
        val m = manager ?: return
        try {
            if (sessionsListener == null) {
                val l = MediaSessionManager.OnActiveSessionsChangedListener { list -> choose(list ?: emptyList()) }
                m.addOnActiveSessionsChangedListener(l, component(), main)
                sessionsListener = l
            }
            choose(m.getActiveSessions(component()))
        } catch (_: SecurityException) {
            // No notification access yet.
        }
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Another app may have started or stopped playing, which changes who we follow.
            val m = manager
            if (m != null) {
                try {
                    val list = m.getActiveSessions(component())
                    if (choose(list)) return
                } catch (_: SecurityException) {
                }
            }
            changed()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            loadArt()
            record()
            changed()
        }

        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = changed()

        override fun onSessionDestroyed() {
            setController(null)
            connect()
        }
    }

    /** Returns true if the followed session changed (listeners were already told). */
    private fun choose(list: List<MediaController>): Boolean {
        sessions = list
        val prefs = Prefs(app)
        val i = SessionPicker.pick(
            list.map { SessionPicker.Candidate(it.packageName, it.playbackState?.state == PlaybackState.STATE_PLAYING) },
            prefs.musicPackage,
            prefs.followOthers,
        )
        return setController(if (i >= 0) list[i] else null)
    }

    private fun setController(c: MediaController?): Boolean {
        val old = controller
        if (c?.sessionToken == old?.sessionToken && (c == null) == (old == null)) return false
        old?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback, main)
        loadArt()
        record()
        changed()
        return true
    }

    // ---- what's playing ----

    val metadata: MediaMetadata? get() = controller?.metadata
    val state: PlaybackState? get() = controller?.playbackState

    val isPlaying: Boolean
        get() = state?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING }

    val hasTrack: Boolean get() = title().isNotEmpty()

    fun title(): String = text(MediaMetadata.METADATA_KEY_TITLE) ?: text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""

    fun artist(): String = text(MediaMetadata.METADATA_KEY_ARTIST)
        ?: text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        ?: text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""

    fun album(): String = text(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

    private fun text(key: String) = metadata?.getText(key)?.toString()?.takeIf { it.isNotBlank() }

    fun duration(): Long = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0

    fun position(): Long {
        val s = state ?: return 0
        var p = s.position
        if (s.state == PlaybackState.STATE_PLAYING && s.lastPositionUpdateTime > 0) {
            p += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        val d = duration()
        return if (d > 0) p.coerceIn(0, d) else p.coerceAtLeast(0)
    }

    fun playerPackage(): String = controller?.packageName ?: Prefs(app).musicPackage

    fun customActions(): List<PlaybackState.CustomAction> = state?.customActions ?: emptyList()

    /** The custom action's icon lives in the player app's own resources. */
    fun customIcon(ctx: Context, a: PlaybackState.CustomAction): Drawable? = runCatching {
        val pkg = controller?.packageName ?: return null
        ctx.packageManager.getResourcesForApplication(pkg).getDrawable(a.icon, null)
    }.getOrNull()

    fun queue(): List<MediaSession.QueueItem> = controller?.queue ?: emptyList()

    /** Titles of the next [n] songs in the queue after the current one. */
    fun upNext(n: Int): List<String> {
        val q = queue()
        val i = q.indexOfFirst { it.queueId == activeQueueId() }
        return q.drop(if (i >= 0) i + 1 else 0).take(n).mapNotNull { it.description.title?.toString() }
    }

    fun activeQueueId(): Long = state?.activeQueueItemId ?: -1

    // ---- commands ----

    fun toggle(ctx: Context) {
        val c = controller ?: return wake(ctx)
        if (isPlaying) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()

    fun previous() = controller?.transportControls?.skipToPrevious()

    fun seekTo(ms: Long) = controller?.transportControls?.seekTo(ms)

    fun skipTo(queueId: Long) = controller?.transportControls?.skipToQueueItem(queueId)

    fun custom(a: PlaybackState.CustomAction) = controller?.transportControls?.sendCustomAction(a, null)

    /**
     * Nothing to control yet: press play on the music app's media button receiver, which
     * resumes the last queue in the background. If no session shows up, open the app.
     */
    fun wake(ctx: Context) {
        val pkg = Prefs(ctx).musicPackage
        val receivers = ctx.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg), 0)
        val r = receivers.firstOrNull()?.activityInfo
        if (r == null) {
            launchApp(ctx, pkg)
            return
        }
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            ctx.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(ComponentName(r.packageName, r.name))
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)),
            )
        }
        main.postDelayed({
            connect()
            if (controller?.packageName != pkg) launchApp(ctx, pkg)
        }, 2500)
    }

    /**
     * The home screen's play circle: "play all my music". Resume what the music app has loaded;
     * with nothing loaded, an empty search asks it to just play something, as "play music" does.
     */
    fun playAll(ctx: Context) {
        val pkg = Prefs(ctx).musicPackage
        val session = sessions.firstOrNull { it.packageName == pkg }
        when {
            session == null -> wake(ctx)
            session.metadata != null -> session.transportControls.play()
            else -> session.transportControls.playFromSearch("", Bundle())
        }
    }

    /** Play the best match for [query] in the music app, the way a voice assistant asks it to. */
    fun search(ctx: Context, query: String, entry: HistoryEntry? = null) {
        val pkg = entry?.pkg ?: Prefs(ctx).musicPackage
        val extras = Bundle()
        if (entry != null) {
            extras.putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
            extras.putString(MediaStore.EXTRA_MEDIA_TITLE, entry.title)
            extras.putString(MediaStore.EXTRA_MEDIA_ARTIST, entry.artist)
            extras.putString(MediaStore.EXTRA_MEDIA_ALBUM, entry.album)
        }
        val session = sessions.firstOrNull { it.packageName == pkg }
        if (session != null) {
            session.transportControls.playFromSearch(query, extras)
            return
        }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(pkg)
            .putExtra(SearchManager.QUERY, query)
            .putExtras(extras)
        if (intent.resolveActivity(ctx.packageManager) != null) start(ctx, intent) else launchApp(ctx, pkg)
    }

    fun playHistory(ctx: Context, e: HistoryEntry) = search(ctx, "${e.title} ${e.artist}".trim(), e)

    fun launchApp(ctx: Context, pkg: String) {
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            start(ctx, launch)
        } else {
            Toast.makeText(ctx, "${appLabel(ctx, pkg)} isn't installed", Toast.LENGTH_SHORT).show()
            runCatching { start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))) }
        }
    }

    fun appLabel(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().lowercase()
    }.getOrElse { if (pkg == Zune.TIDAL) "tidal" else pkg }

    private fun start(ctx: Context, i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    // ---- art and history ----

    private fun loadArt() {
        val m = metadata
        if (m == null) {
            artKey = null
            setArt(null)
            return
        }
        val inline = ArtLoader.bitmap(m)
        val uri = ArtLoader.uri(m)
        val key = "${title()}|${artist()}|$uri|${inline?.generationId}"
        if (key == artKey) return
        artKey = key
        if (inline != null) {
            setArt(inline)
        } else if (uri != null) {
            ArtLoader.load(app, uri) { b ->
                if (artKey == key) {
                    setArt(b)
                    record()
                    changed()
                }
            }
        } else {
            setArt(null)
        }
    }

    private fun setArt(b: Bitmap?) {
        if (b === art) return
        art = b
        blurredArt = b?.let { runCatching { ArtLoader.blur(it) }.getOrNull() }
    }

    private fun record() {
        val c = controller ?: return
        val title = title()
        if (title.isEmpty()) return
        val e = HistoryEntry(title, artist(), album(), metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), c.packageName, System.currentTimeMillis())
        val key = HistoryRules.key(e)
        if (key != lastRecorded) {
            lastRecorded = key
            history.add(e)
        }
        art?.let { history.saveArt(e, it) }
    }
}
