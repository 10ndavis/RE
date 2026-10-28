package dev.smartcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneAndEnhancerTest {
    private val face = Detection(Box.around(0.5f, 0.3f, 0.08f), DetectionKind.FACE, 1, "Face", 1f)

    @Test
    fun classifiesScenes() {
        assertEquals(SceneType.PORTRAIT, SceneClassifier.classify(emptyMap(), listOf(face), 0.5f))
        assertEquals(SceneType.GROUP, SceneClassifier.classify(emptyMap(), listOf(face, face, face), 0.5f))
        assertEquals(SceneType.NIGHT, SceneClassifier.classify(mapOf("Sky" to 0.9f), emptyList(), 0.05f))
        assertEquals(SceneType.FOOD, SceneClassifier.classify(mapOf("Food" to 0.8f, "Sky" to 0.55f), emptyList(), 0.5f))
        assertEquals(SceneType.LANDSCAPE, SceneClassifier.classify(mapOf("Mountain" to 0.7f), emptyList(), 0.5f))
        assertEquals(SceneType.GENERAL, SceneClassifier.classify(mapOf("Chair" to 0.9f), emptyList(), 0.5f))
    }

    @Test
    fun stabilizerWaitsForRepeats() {
        val s = SceneStabilizer(3)
        s.update(SceneType.FOOD)
        s.update(SceneType.FOOD)
        assertEquals(SceneType.GENERAL, s.scene)
        s.update(SceneType.FOOD)
        assertEquals(SceneType.FOOD, s.scene)
    }

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun stretchesFlatContrast() {
        // A hazy photo using only 60..180.
        val pixels = IntArray(10_000) { grey(60 + (it % 121)) }
        val plan = PhotoEnhancer.plan(pixels, SceneType.GENERAL)
        PhotoEnhancer.apply(pixels, plan)
        val values = pixels.map { it and 0xFF }
        assertTrue(values.min() < 45)
        assertTrue(values.max() > 225)
    }

    @Test
    fun greyStaysGreyAndAlphaIsKept() {
        val pixels = IntArray(1000) { grey(it % 256) }
        PhotoEnhancer.apply(pixels, PhotoEnhancer.plan(pixels, SceneType.LANDSCAPE))
        for (p in pixels) {
            assertEquals(0xFF, p ushr 24)
            assertEquals(p and 0xFF, (p shr 8) and 0xFF)
            assertEquals(p and 0xFF, (p shr 16) and 0xFF)
        }
    }

    @Test
    fun mutedColoursGetMoreVivid() {
        val muted = (0xFF shl 24) or (140 shl 16) or (120 shl 8) or 110
        val pixels = IntArray(1000) { if (it % 2 == 0) grey(0) else if (it % 3 == 0) grey(255) else muted }
        PhotoEnhancer.apply(pixels, PhotoEnhancer.plan(pixels, SceneType.LANDSCAPE))
        val p = pixels[1]
        val spread = ((p shr 16) and 0xFF) - (p and 0xFF)
        assertTrue("spread $spread", spread > 30)
    }

    @Test
    fun deviceAngles() {
        assertEquals(0f, DeviceMotion.angle(0f, 9.8f), 0.01f)
        assertEquals(90f, DeviceMotion.angle(-9.8f, 0f), 0.01f)
        assertEquals(270f, DeviceMotion.angle(9.8f, 0f), 0.01f)
        assertEquals(0, DeviceMotion.quantize(40f, 0))
        assertEquals(90, DeviceMotion.quantize(60f, 0))
        assertEquals(90, DeviceMotion.quantize(50f, 90))
        assertEquals(0, DeviceMotion.quantize(350f, 0))
        assertEquals(-10f, DeviceMotion.wrap(350f), 0.01f)
    }
}
