package com.chu.foldanim

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * 힌지 각도 센서와 화면 전환(커버 ↔ 메인)을 감시하다가, 접거나 펼칠 때 오버레이 마스크로 애니메이션을 그립니다.
 *
 * 동작 원리
 * - 메인(안쪽) 화면이 켜진 상태에서 각도가 바뀌면, 설정한 "각도별 표시 %" 곡선을 목표값으로 삼아 마스크를 엽니다/닫습니다.
 * - 커버 → 메인으로 전환되는 순간(펼침)에는 닫힌 상태에서부터 열리는 애니메이션을 시작합니다.
 * - 메인 → 커버로 전환되는 순간(접힘)에는 커버 화면에서 짧게 열리는 효과를 보여줍니다.
 * - 각도가 잠시 멈추면(손을 멈추면) 끝까지 열고 오버레이를 제거해 터치를 막지 않습니다.
 */
class FoldAnimationService : Service(), SensorEventListener, DisplayManager.DisplayListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var displayManager: DisplayManager
    private lateinit var windowManager: WindowManager
    private var hingeSensor: Sensor? = null

    private var config = FoldConfig()

    private var overlay: FoldOverlayView? = null
    private var current = 0f
    private var target = 1f
    private var settled = false
    private var followAngle = false
    private var overlayStartedAt = 0L
    private var lastFrameNanos = 0L

    private var angle = -1f
    private var anchorAngle = -1f
    private var lastMotionAngle = -1f
    private var lastMotionAt = 0L

    private var isMainDisplay = true
    private val knownAreas = mutableSetOf<Long>()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) = onFrame(frameTimeNanos)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)
        windowManager = getSystemService(WindowManager::class.java)
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

        config = FoldSettings.load(this)
        FoldSettings.prefs(this).registerOnSharedPreferenceChangeListener(this)

        isMainDisplay = detectMainDisplay()
        hingeSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        displayManager.registerDisplayListener(this, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                FoldSettings.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST -> playTimed()
        }
        if (!config.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        FoldSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        sensorManager.unregisterListener(this)
        displayManager.unregisterDisplayListener(this)
        removeOverlay()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        config = FoldSettings.load(this)
        if (!config.enabled) stopSelf()
    }

    // ───────────────────────── 힌지 각도 ─────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        val a = event.values[0]
        angle = a
        if (anchorAngle < 0f) anchorAngle = a
        if (!isMainDisplay) {
            anchorAngle = a
            return
        }

        if (overlay == null) {
            val delta = a - anchorAngle
            if (abs(delta) < START_THRESHOLD_DEG) return
            anchorAngle = a
            if (a >= OPEN_ANGLE_DEG) return
            val opening = delta > 0f
            if ((opening && config.onUnfold) || (!opening && config.onFold)) {
                val p = config.revealAt(a)
                showOverlay(startProgress = p, followAngle = true)
                target = p
            }
            return
        }

        if (abs(a - lastMotionAngle) >= MOTION_EPS_DEG) {
            lastMotionAngle = a
            lastMotionAt = SystemClock.uptimeMillis()
        }
        if (followAngle && !settled) {
            if (a >= OPEN_ANGLE_DEG) settle() else target = config.revealAt(a)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ───────────────────────── 화면 전환 ─────────────────────────

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit

    override fun onDisplayChanged(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY) return
        val wasMain = isMainDisplay
        isMainDisplay = detectMainDisplay()
        if (wasMain == isMainDisplay) {
            overlay?.hingeVertical = hingeVertical()
            return
        }

        removeOverlay()
        if (!config.enabled) return

        if (isMainDisplay) {
            // 커버 → 메인: 펼치는 중
            if (!config.onUnfold) return
            val p = if (angle >= 0f) config.revealAt(angle) else 1f
            val start = if (config.alwaysFromClosed) 0f else p
            if (start >= 1f) return
            showOverlay(startProgress = start, followAngle = true)
            target = p
            if (angle >= OPEN_ANGLE_DEG || angle < 0f) settle()
        } else {
            // 메인 → 커버: 접힘 완료
            if (config.onFold && config.coverReveal) playTimed()
        }
        anchorAngle = angle
    }

    /**
     * 기본 디스플레이가 메인(안쪽) 화면인지 판단합니다.
     * 두 가지 화면 크기를 모두 본 뒤에는 더 큰 쪽을 메인으로, 그 전에는 smallestWidth ≥ 600dp 로 추정합니다.
     */
    @Suppress("DEPRECATION")
    private fun detectMainDisplay(): Boolean {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return true
        val m = DisplayMetrics()
        display.getRealMetrics(m)
        val area = m.widthPixels.toLong() * m.heightPixels
        knownAreas += area
        if (knownAreas.size >= 2) return area >= knownAreas.max() * 9 / 10
        val swDp = min(m.widthPixels, m.heightPixels) / m.density
        return swDp >= 600f
    }

    private fun hingeVertical(): Boolean {
        val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        return rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
    }

    // ───────────────────────── 오버레이 ─────────────────────────

    /** 각도와 무관하게 시간 기반으로 0 → 100% 열리는 애니메이션 (커버 화면 효과, 테스트 재생). */
    private fun playTimed() {
        removeOverlay()
        showOverlay(startProgress = 0f, followAngle = false)
        settle()
    }

    private fun settle() {
        settled = true
        target = 1f
    }

    private fun showOverlay(startProgress: Float, followAngle: Boolean) {
        if (!Settings.canDrawOverlays(this)) return
        val view = overlay ?: FoldOverlayView(this).also { v ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
                title = "FoldAnimationOverlay"
            }
            try {
                windowManager.addView(v, params)
            } catch (e: RuntimeException) {
                return
            }
            overlay = v
        }
        view.style = config.style
        view.cornerDp = config.cornerDp
        view.hingeVertical = hingeVertical()

        current = startProgress
        target = startProgress
        view.progress = startProgress
        settled = false
        this.followAngle = followAngle
        overlayStartedAt = SystemClock.uptimeMillis()
        lastMotionAt = overlayStartedAt
        lastMotionAngle = angle
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(frameTimeNanos: Long) {
        val view = overlay ?: return
        val now = SystemClock.uptimeMillis()

        // 손을 멈췄거나 너무 오래 떠 있으면 끝까지 열고 정리합니다.
        if (!settled && (now - lastMotionAt > SETTLE_MS || now - overlayStartedAt > MAX_OVERLAY_MS)) {
            settle()
        }

        val dtMs = if (lastFrameNanos == 0L) 16f else (frameTimeNanos - lastFrameNanos) / 1_000_000f
        lastFrameNanos = frameTimeNanos
        // 지수 감쇠로 목표값을 따라가 센서 값이 튀어도 부드럽게 움직입니다.
        val tau = config.durationMs.coerceAtLeast(60) / 5f
        current += (target - current) * (1f - exp(-dtMs / tau))
        if (abs(target - current) < 0.002f) current = target
        view.progress = current

        if (settled && current >= 0.998f) {
            removeOverlay()
            anchorAngle = angle
            return
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun removeOverlay() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        val v = overlay ?: return
        overlay = null
        try {
            windowManager.removeViewImmediate(v)
        } catch (e: RuntimeException) {
            // 이미 제거된 경우 무시
        }
    }

    // ───────────────────────── 알림 ─────────────────────────

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FoldAnimationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_action_off), stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "fold_animation"
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "com.chu.foldanim.STOP"
        const val ACTION_TEST = "com.chu.foldanim.TEST"

        /** 이 이상 각도가 바뀌어야 접기/펼치기 동작으로 인식 (손떨림 무시) */
        private const val START_THRESHOLD_DEG = 4f
        /** 이 각도 이상이면 완전히 펼친 것으로 봄 */
        private const val OPEN_ANGLE_DEG = 172f
        private const val MOTION_EPS_DEG = 0.5f
        /** 각도 변화가 이 시간 동안 없으면 멈춘 것으로 보고 끝까지 엽니다 */
        private const val SETTLE_MS = 350L
        /** 안전장치: 오버레이가 이 시간 이상 남아있지 않도록 */
        private const val MAX_OVERLAY_MS = 4000L

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, FoldAnimationService::class.java).setAction(action)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FoldAnimationService::class.java))
        }
    }
}
