package dev.myotv

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Setup and status screen, navigable with the TV remote. */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var startStop: Button
    private val toggles = mutableListOf<Pair<Button, () -> String>>()
    private val refresh: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            setBackgroundColor(Color.rgb(18, 20, 24))
        }
        column.addView(text("Myo TV Remote", 32f))
        statusView = text("", 18f).also(column::addView)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startStop = button("Start") {
            if (Status.running) {
                MyoService.settings(this).edit().putBoolean(MyoService.PREF_AUTOSTART, false).apply()
                MyoService.stop(this)
            } else if (ensurePermissions()) {
                MyoService.settings(this).edit().putBoolean(MyoService.PREF_AUTOSTART, true).apply()
                MyoService.start(this)
            }
        }.also(buttons::addView)
        button("Test: press Down") { if (ensurePermissions()) MyoService.start(this, MyoService.ACTION_TEST_KEY) }.also(buttons::addView)
        button("Use a different Myo") { if (ensurePermissions()) MyoService.start(this, MyoService.ACTION_FORGET) }.also(buttons::addView)
        column.addView(buttons)

        val settingsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggle(MyoService.PREF_MOUSE_MODE) { if (it) "Mode: mouse pointer" else "Mode: D-pad" }.also(settingsRow::addView)
        toggle(MyoService.PREF_INVERT_TILT) { if (it) "Up/down: inverted" else "Up/down: normal" }.also(settingsRow::addView)
        toggle(MyoService.PREF_INVERT_POINTER_X) { if (it) "Pointer left/right: inverted" else "Pointer left/right: normal" }
            .also(settingsRow::addView)
        column.addView(settingsRow)
        column.addView(text(HELP, 16f))

        setContentView(ScrollView(this).apply { addView(column) })
        startStop.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        Status.addListener(refresh)
        render()
    }

    override fun onStop() {
        Status.removeListener(refresh)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            MyoService.settings(this).edit().putBoolean(MyoService.PREF_AUTOSTART, true).apply()
            MyoService.start(this)
        }
    }

    private fun render() {
        val battery = Status.battery?.let { "\nBattery: $it%" } ?: ""
        statusView.text = "Armband: ${Status.myo}\nTV control: ${Status.tv}\nGestures: ${Status.gestures.ifEmpty { "-" }}$battery\n"
        startStop.text = if (Status.running) "Stop" else "Start"
        toggles.forEach { (button, label) -> button.text = label() }
    }

    private fun ensurePermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), 1)
        return false
    }

    /** A button that flips a boolean setting and restarts the service so it takes effect. */
    private fun toggle(pref: String, label: (Boolean) -> String): Button {
        val prefs = MyoService.settings(this)
        val b = button("") {
            prefs.edit().putBoolean(pref, !prefs.getBoolean(pref, false)).apply()
            if (Status.running) {
                MyoService.stop(this)
                MyoService.start(this)
            }
            render()
        }
        toggles += b to { label(prefs.getBoolean(pref, false)) }
        return b
    }

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(230, 232, 236))
        setPadding(0, 12, 0, 12)
    }

    private fun button(label: String, onClick: (View) -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener(onClick)
    }

    companion object {
        private val HELP = """
            One-time setup
            1. Settings > Device Preferences > About: select Build 7 times to enable Developer options.
            2. Settings > Device Preferences > Developer options: turn on Network debugging.
            3. Press Start. When the TV asks "Allow network debugging?", tick Always allow and press OK.
            4. Put the armband on, wait for it to warm up, then do the sync gesture (wave out and hold) until it vibrates.

            Gestures in D-pad mode
            Double tap: unlock or lock (it relocks after 10 seconds of no gestures)
            Wave left / wave right: left / right
            Raise / lower your forearm: up / down (hold to repeat)
            Fist: select
            Fingers spread: back. Hold it for home.

            Gestures in mouse pointer mode
            Double tap: unlock, then click (it relocks after 10 seconds without movement)
            Move your arm: move the pointer
            Fist: press and hold where the pointer is. Hold still for a long-press (right click), or move to drag and scroll.
            Wave left / wave right: left / right
            Fingers spread: back. Hold it for home.

            If raising your arm moves down instead of up, switch "Up/down" to inverted.
            If the pointer moves the wrong way sideways, switch "Pointer left/right" to inverted.
        """.trimIndent()
    }
}
