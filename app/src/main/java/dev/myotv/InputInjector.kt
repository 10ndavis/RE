package dev.myotv

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors

/**
 * Sends key presses and touches to whatever app is in the foreground, via the TV's own ADB
 * daemon ("Network debugging" in Developer options, port 5555 on localhost).
 *
 * A normal app cannot inject input into other apps. Over ADB we start [dev.myotv.inject.InjectServer]
 * as the shell user, which can, and stream commands to its stdin. If that helper cannot
 * start, input falls back to slower `input` shell commands, without live dragging.
 */
class InputInjector(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onReady: () -> Unit,
) {

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "input-injector") }
    private var dadb: Dadb? = null
    private var helper: AdbShellStream? = null
    private var fallbackTouchStart: Triple<Int, Int, Long>? = null

    fun press(keyCode: Int) = executor.execute { send("k $keyCode") { "input keyevent $keyCode" } }

    fun tap(x: Int, y: Int) = executor.execute { send("t $x $y") { "input tap $x $y" } }

    fun touchDown(x: Int, y: Int) = executor.execute {
        fallbackTouchStart = Triple(x, y, System.currentTimeMillis())
        send("d $x $y") { null }
    }

    fun touchMove(x: Int, y: Int) = executor.execute { send("m $x $y") { null } }

    fun touchUp(x: Int, y: Int) = executor.execute {
        val start = fallbackTouchStart
        fallbackTouchStart = null
        send("u $x $y") {
            // Without the helper, replay the whole press as one swipe (a long-press if it didn't move).
            start?.let { (x0, y0, t0) -> "input swipe $x0 $y0 $x $y ${(System.currentTimeMillis() - t0).coerceIn(50, 5_000)}" }
        }
    }

    /** Connect ahead of the first gesture so the TV's "Allow debugging?" prompt appears up front. */
    fun warmUp() = executor.execute { ensureConnected() }

    fun close() = executor.execute {
        closeQuietly()
        executor.shutdown()
    }

    /** Sends [command] to the helper, or runs [fallback]'s shell command (if any) without it. */
    private fun send(command: String, fallback: () -> String?) {
        if (!ensureConnected()) return
        try {
            val h = helper
            if (h != null) {
                h.write("$command\n")
            } else {
                fallback()?.let { dadb!!.shell(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "'$command' failed; reconnecting next time", e)
            onStatus("TV connection lost; will reconnect")
            closeQuietly()
        }
    }

    private fun ensureConnected(): Boolean {
        if (dadb != null) return true
        return try {
            onStatus("Connecting to this TV's ADB (approve the prompt on screen the first time)")
            val d = Dadb.create(HOST, PORT, keyPair(), connectTimeout = 3_000)
            // Forces the connection and authorization handshake.
            d.shell("echo ok")
            dadb = d
            // Lets the service draw the mouse pointer over other apps; Android TV has no settings screen for it.
            d.shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            helper = startHelper(d)
            onStatus(if (helper != null) "TV control ready" else "TV control ready (slow fallback mode)")
            onReady()
            true
        } catch (e: Exception) {
            Log.w(TAG, "ADB connect failed", e)
            onStatus("Can't reach this TV's ADB. Turn on Developer options > Network debugging. (${e.message})")
            closeQuietly()
            false
        }
    }

    private fun startHelper(d: Dadb): AdbShellStream? {
        val apk = context.applicationInfo.sourceDir
        val stream = d.openShell("CLASSPATH=$apk app_process / dev.myotv.inject.InjectServer")
        return try {
            val first = stream.read()
            if (first is AdbShellPacket.StdOut && String(first.payload).startsWith("READY")) {
                // Drain the helper's acknowledgements so its output pipe never fills up.
                Thread({
                    try {
                        while (true) if (stream.read() is AdbShellPacket.Exit) break
                    } catch (_: Exception) {
                    }
                }, "inject-helper-reader").apply { isDaemon = true }.start()
                stream
            } else {
                Log.w(TAG, "Injection helper did not start: $first")
                stream.close()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Injection helper failed", e)
            null
        }
    }

    private fun keyPair(): AdbKeyPair {
        val dir = File(context.filesDir, "adb")
        val private = File(dir, "adbkey")
        val public = File(dir, "adbkey.pub")
        if (!private.exists() || !public.exists()) AdbKeyPair.generate(private, public)
        return AdbKeyPair.read(private, public)
    }

    private fun closeQuietly() {
        runCatching { helper?.close() }
        runCatching { dadb?.close() }
        helper = null
        dadb = null
    }

    companion object {
        private const val TAG = "InputInjector"
        private const val HOST = "127.0.0.1"
        private const val PORT = 5555
    }
}
