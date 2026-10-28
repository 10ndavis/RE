package dev.myotv

import java.util.concurrent.CopyOnWriteArraySet

/** Live status shared between [MyoService] and the on-screen UI. */
object Status {
    @Volatile var running = false
    @Volatile var myo = "Stopped"
    @Volatile var tv = "Not connected"
    @Volatile var gestures = ""
    @Volatile var battery: Int? = null

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    fun update(change: Status.() -> Unit) {
        change()
        listeners.forEach { it() }
    }
}
