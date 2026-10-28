package dev.myotv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import dev.myotv.MyoProtocol.ClassifierEvent

/** Foreground service that keeps the armband connected and turns its gestures into key presses. */
class MyoService : Service(), MyoConnection.Listener {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var myo: MyoConnection
    private lateinit var injector: InputInjector
    private lateinit var mapper: GestureMapper
    private lateinit var pointer: PointerOverlay
    private var pointerShown = false
    private var pointerDimmed = false
    private var wasLocked = true

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        thread = HandlerThread("myo").apply { start() }
        handler = Handler(thread.looper)
        val prefs = settings(this)
        mapper = GestureMapper(
            GestureMapper.Config(
                mouseMode = prefs.getBoolean(PREF_MOUSE_MODE, false),
                invertTilt = prefs.getBoolean(PREF_INVERT_TILT, false),
                invertPointerX = prefs.getBoolean(PREF_INVERT_POINTER_X, false),
            )
        )
        myo = MyoConnection(this, handler, this)
        pointer = PointerOverlay(this)
        injector = InputInjector(this, onStatus = { message -> Status.update { tv = message } }, onReady = pointer::retry)
        Status.update { running = true; gestures = "" }
        handler.post { myo.start() }
        handler.post(ticker)
        injector.warmUp()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FORGET -> handler.post { myo.forget() }
            ACTION_TEST_KEY -> injector.press(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.post { myo.stop() }
        // Give the armband's "sleep normally" command time to go out before the thread ends.
        handler.postDelayed({ thread.quitSafely() }, 1_000)
        injector.close()
        pointer.show(false)
        Status.update { running = false; myo = "Stopped"; tv = "Not connected"; gestures = ""; battery = null }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val ticker = object : Runnable {
        override fun run() {
            handle(mapper.onTick(SystemClock.uptimeMillis()))
            handler.postDelayed(this, 100)
        }
    }

    override fun onStatus(message: String) = Status.update { myo = message }

    override fun onConnected(address: String) {
        Status.update { myo = "Connected to Myo $address" }
        publishGestureState()
    }

    override fun onDisconnected() {
        handle(mapper.onArmUnsynced())
        Status.update { battery = null }
    }

    override fun onClassifierEvent(event: ClassifierEvent) {
        val now = SystemClock.uptimeMillis()
        handle(
            when (event) {
                is ClassifierEvent.ArmSynced -> mapper.onArmSynced(event.arm, event.xTowardWrist)
                ClassifierEvent.ArmUnsynced -> mapper.onArmUnsynced()
                is ClassifierEvent.PoseChanged -> mapper.onPose(event.pose, now)
                ClassifierEvent.SyncFailed -> {
                    Status.update { gestures = "Sync failed: do the sync gesture more gently" }
                    return
                }
                // The firmware is held unlocked; GestureMapper does its own locking.
                ClassifierEvent.Locked, ClassifierEvent.Unlocked -> emptyList()
            }
        )
    }

    override fun onImu(sample: MyoProtocol.ImuSample) = handle(mapper.onImu(sample, SystemClock.uptimeMillis()))

    override fun onBattery(percent: Int) = Status.update { battery = percent }

    private fun handle(outputs: List<GestureMapper.Output>) {
        if (wasLocked && !mapper.locked) pointer.center()
        wasLocked = mapper.locked
        for (output in outputs) when (output) {
            is GestureMapper.Output.Key -> injector.press(output.keyCode)
            is GestureMapper.Output.Vibrate -> myo.vibrate(output.kind)
            is GestureMapper.Output.PointerMove -> {
                pointer.moveBy(output.dx, output.dy)
                if (mapper.touching) injector.touchMove(pointer.x, pointer.y)
            }
            GestureMapper.Output.Click -> injector.tap(pointer.x, pointer.y)
            GestureMapper.Output.TouchDown -> injector.touchDown(pointer.x, pointer.y)
            GestureMapper.Output.TouchUp -> injector.touchUp(pointer.x, pointer.y)
        }
        updatePointer()
        publishGestureState()
    }

    /** In mouse mode the pointer shows while the armband is synced, dimmed while locked. */
    private fun updatePointer() {
        val shown = mapper.mouseMode && mapper.synced
        val dimmed = mapper.locked
        if (shown == pointerShown && dimmed == pointerDimmed) return
        pointerShown = shown
        pointerDimmed = dimmed
        pointer.show(shown, dimmed)
    }

    private fun publishGestureState() {
        val text = when {
            !mapper.synced -> "Put the armband on and do the sync gesture (wave out and hold)"
            mapper.locked -> "Locked (${mapper.arm.name.lowercase()} arm). Double tap to unlock."
            mapper.touching -> "Unlocked: holding (move to drag)"
            else -> "Unlocked${if (mapper.mouseMode) " (mouse)" else ""}: ${mapper.pose.name.lowercase().replace('_', ' ')}"
        }
        if (text != Status.gestures) Status.update { gestures = text }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Myo connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Myo TV Remote is running")
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    companion object {
        private const val CHANNEL = "myo"
        const val ACTION_FORGET = "dev.myotv.FORGET"
        const val ACTION_TEST_KEY = "dev.myotv.TEST_KEY"
        const val PREF_AUTOSTART = "autostart"
        const val PREF_INVERT_TILT = "invert_tilt"
        const val PREF_MOUSE_MODE = "mouse_mode"
        const val PREF_INVERT_POINTER_X = "invert_pointer_x"

        fun settings(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun start(context: Context, action: String? = null) {
            context.startForegroundService(Intent(context, MyoService::class.java).setAction(action))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MyoService::class.java))
        }
    }
}
