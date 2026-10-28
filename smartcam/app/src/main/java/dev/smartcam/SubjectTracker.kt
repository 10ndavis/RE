package dev.smartcam

import kotlin.math.hypot

/**
 * Chooses the subject from each frame's detections and follows it from frame to frame.
 * Normally picks the most prominent face or object; after a tap it sticks to that one until it has
 * been out of view for a while.
 */
class SubjectTracker(private val lostAfterMs: Long = 1500) {
    var subject: Detection? = null
        private set
    var locked = false
        private set
    /** Subject velocity in frame widths (x) and heights (y) per second, smoothed. */
    var vx = 0f
        private set
    var vy = 0f
        private set

    private var lastSeenMs = 0L

    fun lock(detection: Detection, nowMs: Long) {
        if (subject?.let { matches(it, detection) } != true) {
            vx = 0f
            vy = 0f
        }
        subject = detection
        locked = true
        lastSeenMs = nowMs
    }

    fun unlock() {
        locked = false
    }

    /** Feeds one frame. Returns true if a locked subject was just lost. */
    fun update(detections: List<Detection>, nowMs: Long): Boolean {
        val previous = subject
        val followed = previous?.let { prev -> detections.filter { matches(prev, it) }.maxByOrNull { it.box.iou(prev.box) } }

        val next = if (locked) {
            followed
        } else {
            val best = detections.maxByOrNull(::prominence)
            // Stay with the current subject unless something clearly more prominent appears.
            if (followed != null && best != null && prominence(followed) >= prominence(best) * 0.7f) followed else best
        }

        if (next != null) {
            if (previous != null && followed === next) {
                val dt = (nowMs - lastSeenMs).coerceAtLeast(1) / 1000f
                vx = vx * 0.6f + (next.box.cx - previous.box.cx) / dt * 0.4f
                vy = vy * 0.6f + (next.box.cy - previous.box.cy) / dt * 0.4f
            } else {
                vx = 0f
                vy = 0f
            }
            subject = next
            lastSeenMs = nowMs
            return false
        }
        if (locked && nowMs - lastSeenMs < lostAfterMs) return false
        val wasLocked = locked
        subject = null
        locked = false
        vx = 0f
        vy = 0f
        return wasLocked
    }

    val speed get() = hypot(vx, vy)

    companion object {
        fun matches(a: Detection, b: Detection): Boolean {
            if (a.kind != b.kind) return false
            if (a.trackingId != null && a.trackingId == b.trackingId) return true
            return a.box.iou(b.box) > 0.3f || hypot(a.box.cx - b.box.cx, a.box.cy - b.box.cy) < 0.08f
        }

        /** Faces win unless tiny (someone in the background); otherwise bigger, more central and more confident objects. */
        fun prominence(d: Detection): Float {
            val centrality = 1f - hypot(d.box.cx - 0.5f, d.box.cy - 0.5f) / 0.71f
            val base = d.box.area.coerceAtMost(0.5f) * (0.4f + centrality) * (0.5f + d.confidence)
            return if (d.kind == DetectionKind.FACE && d.box.area >= 0.004f) base + 0.3f else base
        }
    }
}
