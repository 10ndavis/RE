package dev.smartcam

enum class SceneType(val label: String) {
    PORTRAIT("Portrait"),
    GROUP("Group"),
    PET("Pet"),
    FOOD("Food"),
    MACRO("Close-up"),
    LANDSCAPE("Landscape"),
    NIGHT("Night"),
    GENERAL("Auto"),
}

enum class DetectionKind { FACE, OBJECT }

/** Something the models found in a frame, in world-upright normalized coordinates. */
data class Detection(
    val box: Box,
    val kind: DetectionKind,
    val trackingId: Int?,
    val label: String,
    val confidence: Float,
    /** Faces only: head turn in degrees, positive when the face turns toward the right of the photo. */
    val yaw: Float = 0f,
)

/** Picks the kind of shot from image labels, faces and brightness. */
object SceneClassifier {
    private val PET = setOf("Dog", "Cat", "Bird", "Pet", "Horse", "Rabbit", "Hamster", "Fish")
    private val FOOD = setOf("Food", "Cuisine", "Dish", "Dessert", "Cake", "Pizza", "Bread", "Fruit", "Vegetable",
        "Meal", "Sushi", "Cookie", "Coffee", "Drink", "Breakfast", "Lunch", "Dinner", "Baked goods", "Salad", "Pasta")
    private val MACRO = setOf("Flower", "Petal", "Insect", "Butterfly", "Bee", "Leaf", "Mushroom", "Plant")
    private val LANDSCAPE = setOf("Sky", "Mountain", "Beach", "Lake", "Sea", "Ocean", "Sunset", "Cloud", "Waterfall",
        "River", "Field", "Desert", "Forest", "Horizon", "Landscape", "Skyline", "Snow", "Glacier", "Canyon")
    private val BRIGHT = setOf("Snow", "Beach", "Glacier", "Ski", "Iceberg")

    const val DARK_LUMA = 0.13f

    fun classify(labels: Map<String, Float>, faces: List<Detection>, meanLuma: Float): SceneType {
        val bigFaces = faces.count { it.box.area > 0.003f }
        if (bigFaces >= 3) return SceneType.GROUP
        if (bigFaces >= 1) return SceneType.PORTRAIT
        if (meanLuma < DARK_LUMA) return SceneType.NIGHT
        fun score(set: Set<String>) = labels.filterKeys { it in set }.values.maxOrNull() ?: 0f
        val ranked = listOf(
            SceneType.PET to score(PET),
            SceneType.FOOD to score(FOOD),
            SceneType.MACRO to score(MACRO),
            SceneType.LANDSCAPE to score(LANDSCAPE),
        ).maxBy { it.second }
        return if (ranked.second >= 0.6f) ranked.first else SceneType.GENERAL
    }

    /** Snow and beach scenes fool the camera's meter into making them grey. */
    fun isBrightScene(labels: Map<String, Float>) = labels.any { (k, v) -> k in BRIGHT && v >= 0.6f }
}

/** Only switches scene after it has been seen several frames in a row, so the guidance doesn't flicker. */
class SceneStabilizer(private val framesNeeded: Int = 4) {
    var scene = SceneType.GENERAL
        private set
    private var candidate = SceneType.GENERAL
    private var count = 0

    fun update(next: SceneType): SceneType {
        if (next == scene) {
            count = 0
        } else if (next == candidate) {
            if (++count >= framesNeeded) {
                scene = next
                count = 0
            }
        } else {
            candidate = next
            count = 1
        }
        return scene
    }
}
