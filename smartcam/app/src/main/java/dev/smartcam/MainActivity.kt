package dev.smartcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Range
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.util.Size
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The camera screen. Each analysed frame updates the chosen subject, the scene type, the framing
 * advice and the exposure: focus and metering follow the subject, exposure compensation nudges it to
 * a good brightness for the scene, and the shutter is forced faster for moving subjects or allowed
 * slower for steady low-light shots.
 */
@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: GuideOverlay
    private lateinit var shutter: View
    private lateinit var thumbnail: ImageView
    private lateinit var enhanceToggle: TextView
    private lateinit var autoToggle: TextView
    private val rotatingViews = mutableListOf<View>()

    private val motion by lazy { DeviceMotion(this) }
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("smartcam", MODE_PRIVATE) }

    private var analyzer: SceneAnalyzer? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val tracker = SubjectTracker()
    private val composer = Composer()
    private val stabilizer = SceneStabilizer()
    private var lastFrame: FrameResult? = null
    private var lastGrid: LumaGrid? = null
    private var sceneChange = 0f
    private var guidance: Guidance? = null

    // What the camera can do.
    private var fpsRanges = emptyList<Pair<Int, Int>>()
    private var isoRange: IntRange? = null
    private var exposureRange: LongRange? = null

    // Latest exposure reported by the camera; "auto" values are from frames where auto exposure ran.
    @Volatile private var shownExposureNs = 0L
    @Volatile private var shownIso = 0
    @Volatile private var autoExposureNs = 0L
    @Volatile private var autoIso = 0

    private var manual: ManualExposure? = null
    private var manualEv = 0f
    private var fastShutterNs: Long? = null
    private var fastShutterUntil = 0L
    private var fpsRange: Pair<Int, Int>? = null
    private var appliedOptions: Pair<Pair<Int, Int>?, ManualExposure?>? = null
    private var lastExposureUpdate = 0L

    private var meteredSubject: Detection? = null
    private var meteredAt = 0L
    private var spotFocusUntil = 0L
    private var shownRotation = 0
    private var shownAligned = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var alignedSince = 0L
    private var lastShot = 0L
    private var lastPhoto: Uri? = null

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "SmartCam needs the camera", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        motion.start()
    }

    override fun onPause() {
        motion.stop()
        super.onPause()
    }

    override fun onDestroy() {
        imageAnalysis?.clearAnalyzer()
        analyzer?.close()
        analysisExecutor.shutdown()
        saveExecutor.shutdown()
        super.onDestroy()
    }

    // ---- UI ----

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        overlay = GuideOverlay(this).apply { onTap = ::onTap }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        enhanceToggle = chip { prefs.getBoolean(PREF_ENHANCE, true).let { if (it) "✨ Enhance on" else "Enhance off" } }
        enhanceToggle.setOnClickListener { flip(PREF_ENHANCE, true); refreshChips() }
        autoToggle = chip { if (prefs.getBoolean(PREF_AUTO_SHOT, false)) "Auto-shot on" else "Auto-shot off" }
        autoToggle.setOnClickListener { flip(PREF_AUTO_SHOT, false); refreshChips() }
        top.addView(enhanceToggle)
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(autoToggle)
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((28 * dp).toInt(), 0, (28 * dp).toInt(), (28 * dp).toInt())
        }
        thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(Color.DKGRAY); cornerRadius = 10 * dp }
            clipToOutline = true
            setOnClickListener { lastPhoto?.let(::openPhoto) }
        }
        shutter = View(this).apply {
            background = shutterDrawable(Color.WHITE)
            setOnClickListener { takePhoto() }
            contentDescription = "Take photo"
        }
        val size = (56 * dp).toInt()
        bottom.addView(thumbnail, LinearLayout.LayoutParams(size, size))
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(shutter, LinearLayout.LayoutParams((76 * dp).toInt(), (76 * dp).toInt()))
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(View(this), LinearLayout.LayoutParams(size, size))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        rotatingViews += listOf(thumbnail, enhanceToggle, autoToggle)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (top.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top
            (bottom.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom
            top.requestLayout()
            bottom.requestLayout()
            insets
        }
        setContentView(root)
    }

    private fun chip(label: () -> String) = TextView(this).apply {
        val dp = resources.displayMetrics.density
        setTextColor(Color.WHITE)
        textSize = 14f
        setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
        background = GradientDrawable().apply { setColor(Color.argb(140, 40, 40, 40)); cornerRadius = 20 * dp }
        text = label()
        tag = label
    }

    @Suppress("UNCHECKED_CAST")
    private fun refreshChips() {
        for (chip in listOf(enhanceToggle, autoToggle)) chip.text = (chip.tag as () -> String)()
    }

    private fun flip(key: String, default: Boolean) = prefs.edit().putBoolean(key, !prefs.getBoolean(key, default)).apply()

    private fun shutterDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((5 * resources.displayMetrics.density).toInt(), Color.argb(120, 255, 255, 255))
    }

    // ---- Camera ----

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ bind(future.get()) }, mainExecutor)
    }

    private fun bind(provider: ProcessCameraProvider) {
        // Preview, analysis and the photo all use 4:3 so what you frame is what you get.
        val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val previewBuilder = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
                shownExposureNs = exposure
                shownIso = iso
                if (result.get(CaptureResult.CONTROL_AE_MODE) != CameraMetadata.CONTROL_AE_MODE_OFF) {
                    autoExposureNs = exposure
                    autoIso = iso
                }
            }
        })
        val preview = previewBuilder.build().also { it.surfaceProvider = previewView.surfaceProvider }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            .build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
            )
            .build()
        val sceneAnalyzer = SceneAnalyzer({ motion.rotation }, mainExecutor, ::onFrame)
        analysis.setAnalyzer(analysisExecutor, sceneAnalyzer)

        provider.unbindAll()
        val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
        camera = cam
        imageCapture = capture
        imageAnalysis = analysis
        analyzer?.close()
        analyzer = sceneAnalyzer

        val info = Camera2CameraInfo.from(cam.cameraInfo)
        fpsRanges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { Pair(it.lower, it.upper) } ?: emptyList()
        val manualSensor = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        if (manualSensor) {
            isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper }
            exposureRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper }
        }
    }

    // ---- Per frame ----

    private fun onFrame(frame: FrameResult) {
        val now = SystemClock.elapsedRealtime()
        lastFrame = frame
        val detections = frame.faces + frame.objects
        if (tracker.update(detections, now)) toast("Lost the subject, tap it again")

        val grid = frame.grid
        sceneChange = lastGrid?.let { sceneChange * 0.7f + grid.difference(it) * 0.3f } ?: 0f
        lastGrid = grid
        val scene = stabilizer.update(SceneClassifier.classify(frame.labels, frame.faces, grid.mean()))
        val horizon = if (scene == SceneType.LANDSCAPE) grid.horizon() else null
        val steady = motion.steady && sceneChange < 0.04f
        // While the phone itself is turning, the subject sliding across the frame isn't the subject moving.
        val subjectVx = if (motion.steady) tracker.vx else 0f
        val subjectSpeed = if (motion.steady) tracker.speed else 0f
        val g = composer.guide(scene, tracker.subject, frame.faces, horizon, motion.rollError, motion.flat, subjectVx, steady)
        guidance = g

        meterOnSubject(now)
        updateExposure(now, frame, scene, steady, subjectSpeed)
        val flash = if (ExposureAdvisor.useFlash(scene, grid.mean())) ImageCapture.FLASH_MODE_AUTO else ImageCapture.FLASH_MODE_OFF
        if (flash != flashMode) {
            flashMode = flash
            imageCapture?.flashMode = flash
        }

        overlay.state = OverlayState(detections, tracker.subject, tracker.locked, g, hud(scene, g), frame.rotation,
            motion.rollError, motion.flat, frame.displayAspect)
        if (g.aligned != shownAligned) {
            shownAligned = g.aligned
            shutter.background = shutterDrawable(if (g.aligned) GuideOverlay.GREEN else Color.WHITE)
        }
        if (frame.rotation != shownRotation) {
            // Turn the shortest way, e.g. 270 to 0 goes through 360 rather than back through 180.
            val delta = DeviceMotion.wrap((frame.rotation - shownRotation).toFloat())
            shownRotation = frame.rotation
            for (v in rotatingViews) v.animate().rotation(v.rotation - delta)
            imageCapture?.targetRotation = surfaceRotation(frame.rotation)
        }

        if (g.aligned && steady) {
            if (alignedSince == 0L) alignedSince = now
        } else {
            alignedSince = 0L
        }
        if (prefs.getBoolean(PREF_AUTO_SHOT, false) && alignedSince != 0L && now - alignedSince > 700 && now - lastShot > 3000) {
            takePhoto()
        }
    }

    private fun hud(scene: SceneType, g: Guidance): String {
        val parts = mutableListOf(scene.label)
        if (shownExposureNs > 0) {
            val seconds = shownExposureNs / 1e9
            parts += if (seconds >= 0.5) "%.1fs".format(seconds) else "1/${(1 / seconds).roundToInt()}s"
            parts += "ISO $shownIso"
        }
        val cam = camera
        if (manual == null && cam != null) {
            val state = cam.cameraInfo.exposureState
            val ev = state.exposureCompensationIndex * state.exposureCompensationStep.toFloat()
            if (abs(ev) > 0.01f) parts += "%+.1f EV".format(ev)
        }
        if (manual != null) parts += "fast shutter"
        parts += "framing ${g.score}"
        return parts.joinToString(" · ")
    }

    /** Points focus, exposure and white balance at the subject when it changes or moves. */
    private fun meterOnSubject(now: Long) {
        if (now < spotFocusUntil) return
        val subject = tracker.subject ?: return
        val previous = meteredSubject
        val changed = previous == null || !SubjectTracker.matches(previous, subject)
        val moved = previous != null && hypot(subject.box.cx - previous.box.cx, subject.box.cy - previous.box.cy) > 0.12f
        if ((changed || moved) && now - meteredAt > 1000) meter(subject.box, now, subject)
    }

    private fun meter(box: Box, now: Long, subject: Detection?) {
        val cam = camera ?: return
        val (x, y) = overlay.toView(box.cx, box.cy)
        val size = (max(box.width, box.height) * overlay.imageWidthFraction()).coerceIn(0.06f, 0.4f)
        val point = previewView.meteringPointFactory.createPoint(x, y, size)
        val flags = FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AWB or
            if (manual == null) FocusMeteringAction.FLAG_AE else 0
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point, flags).disableAutoCancel().build())
        meteredSubject = subject
        meteredAt = now
    }

    private fun updateExposure(now: Long, frame: FrameResult, scene: SceneType, steady: Boolean, subjectSpeed: Float) {
        val cam = camera ?: return
        val grid = frame.grid
        val subject = tracker.subject

        // Shutter: freeze motion. Keep the fast shutter a moment after motion stops so it doesn't flap.
        val needed = if (subject != null) ExposureAdvisor.shutterFor(subjectSpeed) else null
        if (needed != null) {
            fastShutterNs = minOf(needed, if (now < fastShutterUntil) fastShutterNs ?: needed else needed)
            fastShutterUntil = now + 1500
        } else if (now >= fastShutterUntil) {
            fastShutterNs = null
        }
        val isos = isoRange
        val times = exposureRange
        val wanted = fastShutterNs
        manual = if (wanted != null && isos != null && times != null && autoExposureNs > 0) {
            ExposureAdvisor.manual(wanted, autoExposureNs, autoIso, isos, times, manualEv)
        } else null
        if (manual == null) manualEv = 0f
        fpsRange = ExposureAdvisor.fpsRange(fpsRanges, grid.mean() < 0.2f, steady, subjectSpeed > 0.1f)
        applyCaptureOptions(cam)

        // Brightness: nudge the subject toward a good level for the scene, twice a second.
        if (now - lastExposureUpdate < 500) return
        lastExposureUpdate = now
        val bright = SceneClassifier.isBrightScene(frame.labels)
        val target = ExposureAdvisor.targetLuma(scene, bright)
        val measured = when {
            subject != null -> grid.average(subject.box)
            bright || scene == SceneType.NIGHT -> grid.mean()
            else -> null
        }
        if (manual != null) {
            if (measured != null) manualEv = ExposureAdvisor.nextManualEv(manualEv, measured, target, grid.highlights)
            return
        }
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange.lower..state.exposureCompensationRange.upper
        val next = if (measured != null) {
            ExposureAdvisor.nextEvIndex(state.exposureCompensationIndex, state.exposureCompensationStep.toFloat(),
                range, measured, target, grid.highlights)
        } else {
            // Nothing to judge by: ease back to what the camera chose.
            state.exposureCompensationIndex.let { it - it.coerceIn(-1, 1) }
        }
        if (next != state.exposureCompensationIndex) cam.cameraControl.setExposureCompensationIndex(next)
    }

    private fun applyCaptureOptions(cam: Camera) {
        val m = manual
        val applied = appliedOptions
        // Small ISO and shutter changes aren't worth a new request.
        val same = applied != null && applied.first == fpsRange && run {
            val old = applied.second
            (old == null && m == null) || (old != null && m != null &&
                abs(old.iso - m.iso) < old.iso * 0.1 && abs(old.exposureNs - m.exposureNs) < old.exposureNs * 0.1)
        }
        if (same) return
        appliedOptions = Pair(fpsRange, m)
        val options = CaptureRequestOptions.Builder()
        fpsRange?.let { options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.second)) }
        if (m != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, m.exposureNs)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, m.iso)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(m.exposureNs, ExposureAdvisor.SECOND / 30))
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options.build())
    }

    // ---- Taps and capture ----

    private fun onTap(worldX: Float, worldY: Float) {
        val now = SystemClock.elapsedRealtime()
        val frame = lastFrame
        val hit = frame?.let { (it.faces + it.objects).filter { d -> d.box.contains(worldX, worldY) }.minByOrNull { d -> d.box.area } }
        if (hit != null) {
            tracker.lock(hit, now)
            spotFocusUntil = 0L
            meter(hit.box, now, hit)
            return
        }
        // Nothing recognised there: focus on that spot for a while, and choose the subject automatically again.
        tracker.unlock()
        spotFocusUntil = now + 5000
        meter(Box.around(worldX, worldY, 0.05f), now, null)
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastShot < 600) return
        lastShot = now
        alignedSince = 0L
        overlay.flash()
        val scene = stabilizer.scene
        val enhance = prefs.getBoolean(PREF_ENHANCE, true)
        capture.takePicture(saveExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bitmap = try {
                    image.toBitmap()
                } finally {
                    image.close()
                }
                val photo = PhotoSaver.process(bitmap, rotation, scene, enhance)
                val uri = PhotoSaver.save(contentResolver, photo)
                val thumb = Bitmap.createScaledBitmap(photo, 160, (160f * photo.height / photo.width).toInt().coerceAtLeast(1), true)
                photo.recycle()
                runOnUiThread {
                    if (uri == null) {
                        toast("Couldn't save the photo")
                    } else {
                        lastPhoto = uri
                        thumbnail.setImageBitmap(thumb)
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread { toast("Photo failed: ${exception.message}") }
            }
        })
    }

    private fun openPhoto(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("No app to show photos")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun surfaceRotation(degrees: Int) = when (degrees) {
        90 -> Surface.ROTATION_270
        180 -> Surface.ROTATION_180
        270 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val PREF_ENHANCE = "enhance"
        private const val PREF_AUTO_SHOT = "auto_shot"
    }
}
