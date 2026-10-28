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
    private lateinit var invert: Button
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
        invert = button("") {
            val prefs = MyoService.settings(this)
            prefs.edit().putBoolean(MyoService.PREF_INVERT_TILT, !prefs.getBoolean(MyoService.PREF_INVERT_TILT, false)).apply()
            if (Status.running) {
                // The gesture mapper reads this setting when the service starts.
                MyoService.stop(this)
                MyoService.start(this)
            }
            render()
        }.also(buttons::addView)
        column.addView(buttons)
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
        val inverted = MyoService.settings(this).getBoolean(MyoService.PREF_INVERT_TILT, false)
        invert.text = if (inverted) "Up/down: inverted" else "Up/down: normal"
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

            Gestures
            Double tap: unlock or lock (it relocks after 10 seconds of no gestures)
            Wave left / wave right: left / right
            Raise / lower your forearm: up / down (hold to repeat)
            Fist: select
            Fingers spread: back. Hold it for home.

            If raising your arm moves down instead of up, switch "Up/down" to inverted.
        """.trimIndent()
    }
}
