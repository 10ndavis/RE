package dev.smartcam

import android.graphics.Rect
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.io.Closeable
import java.util.concurrent.Executor

/** One analysed frame. Boxes are world upright; [displayAspect] is the frame's width/height as shown on screen. */
class FrameResult(
    val faces: List<Detection>,
    val objects: List<Detection>,
    val labels: Map<String, Float>,
    val grid: LumaGrid,
    val displayAspect: Float,
    val rotation: Int,
)

/**
 * Runs the on-device models on camera frames: faces, prominent objects and (every few frames) scene
 * labels, plus a brightness grid. Frames are turned world upright first, using [deviceRotation], so
 * the models see people the right way up however the phone is held. Results arrive on [callbackExecutor].
 */
class SceneAnalyzer(
    private val deviceRotation: () -> Int,
    private val callbackExecutor: Executor,
    private val onFrame: (FrameResult) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {

    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.05f)
            .enableTracking()
            .build()
    )
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build())

    private var frameCount = 0
    private var labels = emptyMap<String, Float>()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        val device = deviceRotation()
        val displayRotation = image.imageInfo.rotationDegrees
        val world = (displayRotation + device) % 360

        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val grid = LumaGrid.build(image.width, image.height, world) { x, y ->
            buffer.get(y * rowStride + x * pixelStride).toInt() and 0xFF
        }

        val width = if (world % 180 == 0) image.width else image.height
        val height = if (world % 180 == 0) image.height else image.width
        val displayAspect = if (displayRotation % 180 == 0) image.width.toFloat() / image.height
        else image.height.toFloat() / image.width

        val input = InputImage.fromMediaImage(media, world)
        val objectsTask = objectDetector.process(input)
        val facesTask = faceDetector.process(input)
        // Scene labels change slowly and are the slowest model, so run them less often.
        val labelsTask = if (frameCount++ % 3 == 0) labeler.process(input) else null
        val tasks: List<Task<*>> = listOfNotNull(objectsTask, facesTask, labelsTask)

        Tasks.whenAllComplete(tasks).addOnCompleteListener(callbackExecutor) {
            image.close()
            val faces = if (facesTask.isSuccessful) facesTask.result.map { face ->
                Detection(normalize(face.boundingBox, width, height), DetectionKind.FACE, face.trackingId, "Face", 1f,
                    face.headEulerAngleY)
            } else emptyList()
            val objects = if (objectsTask.isSuccessful) objectsTask.result.mapNotNull { obj ->
                val label = obj.labels.maxByOrNull { it.confidence }
                val box = normalize(obj.boundingBox, width, height)
                if (box.area < 0.004f) null
                else Detection(box, DetectionKind.OBJECT, obj.trackingId, label?.text ?: "Subject", label?.confidence ?: 0.5f)
            } else emptyList()
            if (labelsTask != null && labelsTask.isSuccessful) {
                labels = labelsTask.result.associate { it.text to it.confidence }
            }
            onFrame(FrameResult(faces, objects, labels, grid, displayAspect, device))
        }
    }

    private fun normalize(r: Rect, width: Int, height: Int) = Box(
        (r.left.toFloat() / width).coerceIn(0f, 1f),
        (r.top.toFloat() / height).coerceIn(0f, 1f),
        (r.right.toFloat() / width).coerceIn(0f, 1f),
        (r.bottom.toFloat() / height).coerceIn(0f, 1f),
    )

    override fun close() {
        objectDetector.close()
        faceDetector.close()
        labeler.close()
    }
}
