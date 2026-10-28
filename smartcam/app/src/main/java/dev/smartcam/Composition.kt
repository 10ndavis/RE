package dev.smartcam

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

data class Point(val x: Float, val y: Float)

/** What to tell the photographer about the current frame. All points are world-upright, normalized. */
data class Guidance(
    /** The part of the subject that should sit on [target] (eyes for a face, centre for an object). */
    val subjectPoint: Point?,
    /** Where that point should be. A null x or y in the target means "anywhere on that axis". */
    val target: Point?,
    val horizonOnly: Boolean,
    val message: String,
    val tip: String?,
    val level: Boolean,
    /** Framed, level and not too close or far: ready to shoot. */
    val aligned: Boolean,
    val score: Int,
)

/**
 * Works out where the subject should go in the frame and which way to move the phone to put it there.
 * Uses the rule of thirds, with headroom and looking room for faces and leading room for moving
 * subjects. Keeps its earlier choice of third when the subject sits near the middle, so the advice
 * doesn't flip between left and right.
 */
class Composer {
    private var lastTx: Float? = null
    private var lastTy: Float? = null

    fun guide(
        scene: SceneType,
        subject: Detection?,
        faces: List<Detection>,
        horizon: Float?,
        rollError: Float,
        flat: Boolean,
        subjectVx: Float,
        steady: Boolean,
    ): Guidance {
        val level = flat || abs(rollError) <= LEVEL_TOLERANCE
        var point: Point? = null
        var target: Point? = null
        var horizonOnly = false
        var size: String? = null

        if (scene == SceneType.GROUP && faces.size >= 2) {
            val group = Box(faces.minOf { it.box.left }, faces.minOf { it.box.top },
                faces.maxOf { it.box.right }, faces.maxOf { it.box.bottom })
            point = Point(group.cx, group.top + group.height * 0.4f)
            target = Point(0.5f, 0.4f)
            if (faces.any { it.box.touchesEdge(0.03f) }) size = "Step back to fit everyone"
            else if (group.width < 0.25f) size = "Move closer"
        } else if (subject != null && subject.kind == DetectionKind.FACE) {
            val face = subject.box
            point = Point(face.cx, face.top + face.height * 0.4f)
            val tx = when {
                face.width > 0.28f -> 0.5f
                // Leave room in front of the face: looking right means sit on the left third.
                subject.yaw > 15f -> THIRD
                subject.yaw < -15f -> 1 - THIRD
                else -> third(face.cx, lastTx)
            }
            val ty = if (face.height > 0.45f) 0.4f else THIRD
            target = Point(tx, ty)
            if (face.height > 0.5f && face.touchesEdge()) size = "Step back"
            else if (face.height < 0.06f) size = "Move closer"
        } else if (subject != null) {
            val box = subject.box
            point = Point(box.cx, box.cy)
            val big = box.area > 0.4f || box.width > 0.75f || box.height > 0.75f
            target = if (big || (scene == SceneType.FOOD && box.area > 0.25f)) {
                Point(0.5f, 0.5f)
            } else {
                // A moving subject goes on the third behind it so it moves into the frame.
                val tx = when {
                    subjectVx > 0.08f -> THIRD
                    subjectVx < -0.08f -> 1 - THIRD
                    else -> third(box.cx, lastTx)
                }
                Point(tx, third(box.cy, lastTy))
            }
            val minArea = if (scene == SceneType.MACRO) 0.12f else 0.015f
            if (box.touchesEdge() && box.area > 0.5f) size = "Step back"
            else if (box.area < minArea && scene != SceneType.LANDSCAPE) size = "Move closer"
        } else if (scene == SceneType.LANDSCAPE && horizon != null) {
            point = Point(0.5f, horizon)
            target = Point(0.5f, third(horizon, lastTy))
            horizonOnly = true
        }

        if (target != null) {
            if (!horizonOnly) lastTx = target.x
            lastTy = target.y
        }

        val dx = if (point != null && target != null && !horizonOnly) point.x - target.x else 0f
        val dy = if (point != null && target != null) point.y - target.y else 0f
        val framed = target != null && abs(dx) <= TOLERANCE && abs(dy) <= TOLERANCE
        val aligned = framed && level && size == null

        val message = when {
            !level -> if (rollError > 0) "Level the phone ↺" else "Level the phone ↻"
            size != null -> size
            target == null -> "Tap what you want to photograph"
            !framed -> moveMessage(dx, dy, horizonOnly)
            !steady -> "Hold steady…"
            else -> "Perfect, take the shot"
        }

        var score = 100f - hypot(dx, dy) * 220f - max(0f, abs(rollError) - 1f) * 5f
        if (size != null) score -= 15f
        if (target == null) score = if (level) 60f else 40f

        return Guidance(point, target, horizonOnly, message, tip(scene, flat, horizon, subject), level, aligned,
            score.toInt().coerceIn(0, 100))
    }

    private fun moveMessage(dx: Float, dy: Float, horizonOnly: Boolean): String {
        val parts = mutableListOf<String>()
        if (abs(dx) > TOLERANCE) parts += if (dx > 0) "pan right →" else "pan left ←"
        if (abs(dy) > TOLERANCE) {
            // For a horizon, tilting moves the line: tilt down to bring it up.
            parts += if (dy > 0) "tilt down ↓" else "tilt up ↑"
        }
        // Biggest correction first.
        if (parts.size == 2 && abs(dy) > abs(dx)) parts.reverse()
        val text = parts.joinToString(", ")
        val sentence = text.replaceFirstChar { it.uppercase() }
        return if (horizonOnly) "$sentence to put the horizon on a third" else sentence
    }

    private fun tip(scene: SceneType, flat: Boolean, horizon: Float?, subject: Detection?): String? = when (scene) {
        SceneType.FOOD -> if (flat) "Top-down: great for flat dishes" else "Tip: try shooting straight down"
        SceneType.NIGHT -> "Low light: brace the phone, it will use a slower shutter"
        SceneType.LANDSCAPE -> if (horizon == null) "Keep the horizon off the centre line" else null
        SceneType.PORTRAIT -> if (subject?.kind == DetectionKind.FACE && abs(subject.yaw) > 15f)
            "Leaving space where they're looking" else "Eyes on the top third line"
        SceneType.PET -> "Get down to their eye level"
        SceneType.MACRO -> "Tap the subject to lock focus"
        SceneType.GROUP -> "Faces near the top third"
        SceneType.GENERAL -> null
    }

    /** Nearest third line to [value], sticking with [previous] unless the other is clearly closer. */
    private fun third(value: Float, previous: Float?): Float {
        val nearest = if (value < 0.5f) THIRD else 1 - THIRD
        if (previous == null || previous == nearest || (previous != THIRD && previous != 1 - THIRD)) return nearest
        return if (abs(value - previous) < abs(value - nearest) + HYSTERESIS) previous else nearest
    }

    companion object {
        const val THIRD = 1f / 3f
        const val TOLERANCE = 0.045f
        const val LEVEL_TOLERANCE = 2.5f
        private const val HYSTERESIS = 0.08f
    }
}
