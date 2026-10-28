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
 * Sends key presses to whatever app is in the foreground, via the TV's own ADB daemon
 * ("Network debugging" in Developer options, port 5555 on localhost).
 *
 * A normal app cannot inject input into other apps. Over ADB we start [dev.myotv.inject.InjectServer]
 * as the shell user, which can, and stream key codes to its stdin. If that helper cannot
 * start, each key falls back to a slower `input keyevent` command.
 */
class KeyInjector(private val context: Context, private val onStatus: (String) -> Unit) {

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "key-injector") }
    private var dadb: Dadb? = null
    private var helper: AdbShellStream? = null

    fun press(keyCode: Int) = executor.execute { send(keyCode) }

    /** Connect ahead of the first gesture so the TV's "Allow debugging?" prompt appears up front. */
    fun warmUp() = executor.execute { ensureConnected() }

    fun close() = executor.execute {
        closeQuietly()
        executor.shutdown()
    }

    private fun send(keyCode: Int) {
        if (!ensureConnected()) return
        try {
            val h = helper
            if (h != null) {
                h.write("$keyCode\n")
            } else {
                dadb!!.shell("input keyevent $keyCode")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Key $keyCode failed; reconnecting next time", e)
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
            helper = startHelper(d)
            onStatus(if (helper != null) "TV control ready" else "TV control ready (slow fallback mode)")
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
        private const val TAG = "KeyInjector"
        private const val HOST = "127.0.0.1"
        private const val PORT = 5555
    }
}
