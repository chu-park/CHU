package com.chu.foldanim

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 아이폰 듀오 스타일 배경화면.
 *
 * 힌지 각도 센서 값으로
 * - 커버 화면: 펼치기 시작하면 배경이 흐려지고 커지며 검게 가라앉아 "화면을 통과해 사라지는" 느낌
 * - 메인 화면: 켜지는 순간 흐리고 늘어난 상태에서 각도에 따라 점점 선명하게 제자리로
 * 를 그립니다. 접을 때는 같은 과정이 거꾸로 진행됩니다.
 * 각도에 직접 묶여 있어서 손으로 여는 속도를 그대로 따라갑니다.
 */
class DuoWallpaperService : WallpaperService() {

    companion object {
        /**
         * 마지막 힌지 각도. 화면이 커버 ↔ 메인으로 바뀌면 배경화면 엔진이 새로 만들어질 수 있어서,
         * 새 엔진이 첫 센서 값을 받기 전에도 올바른 상태(흐린 상태)로 시작하도록 공유합니다.
         */
        @Volatile
        private var lastAngle = 180f
    }

    override fun onCreateEngine(): Engine = DuoEngine()

    private inner class DuoEngine : Engine(), SensorEventListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val sensorManager = getSystemService(SensorManager::class.java)
        private val hingeSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        private val density = resources.displayMetrics.density

        private var config = FoldSettings.load(this@DuoWallpaperService)
        private var bitmap: Bitmap? = null
        private var loadedImageVersion = -1L

        private val node = RenderNode("duo-wallpaper")
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dimPaint = Paint()
        private val vignettePaint = Paint()
        private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        private val srcRect = Rect()
        private val dstRect = RectF()

        private var width = 0
        private var height = 0
        private var inner = true
        private val knownAreas = mutableSetOf<Long>()

        private var angle = lastAngle
        private var sensorEvents = 0L
        private var current = 1f
        private var target = 1f
        private var lastFrameNanos = 0L
        private var animating = false

        private val frameCallback = Choreographer.FrameCallback { onFrame(it) }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            FoldSettings.prefs(this@DuoWallpaperService).registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            FoldSettings.prefs(this@DuoWallpaperService).unregisterOnSharedPreferenceChangeListener(this)
            sensorManager.unregisterListener(this)
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            node.discardDisplayList()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                hingeSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
                }
                // 화면이 막 켜졌을 때는 현재 각도 상태로 바로 그립니다 (부드럽게 따라가기 X).
                current = targetFor(angle)
                target = current
                draw()
            } else {
                sensorManager.unregisterListener(this)
                Choreographer.getInstance().removeFrameCallback(frameCallback)
                animating = false
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.width = width
            this.height = height
            inner = detectInner(width, height)
            ensureBitmap()
            // 커버 ↔ 메인 화면이 바뀐 순간: 해당 화면의 현재 각도 상태로 즉시 맞춰 깜빡임을 줄입니다.
            current = targetFor(angle)
            target = current
            draw()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            draw()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            config = FoldSettings.load(this@DuoWallpaperService)
            ensureBitmap()
            current = targetFor(angle)
            target = current
            draw()
        }

        override fun onSensorChanged(event: SensorEvent) {
            angle = event.values[0]
            lastAngle = angle
            sensorEvents++
            if (config.debugHud) draw()
            target = targetFor(angle)
            if (!animating && abs(target - current) > 0.001f) {
                animating = true
                lastFrameNanos = 0L
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun targetFor(a: Float): Float =
            if (!config.enabled || hingeSensor == null) 1f else config.revealAt(a, inner)

        private fun onFrame(frameTimeNanos: Long) {
            val dtMs = if (lastFrameNanos == 0L) 8f else (frameTimeNanos - lastFrameNanos) / 1_000_000f
            lastFrameNanos = frameTimeNanos
            // 센서 값의 계단 현상을 지수 감쇠로 부드럽게 (작을수록 손에 더 딱 붙음)
            val tau = max(config.smoothMs, 1) / 4f
            current += (target - current) * (1f - exp(-dtMs / tau))
            if (abs(target - current) < 0.001f) current = target
            draw()
            if (current != target && isVisible) {
                Choreographer.getInstance().postFrameCallback(frameCallback)
            } else {
                animating = false
            }
        }

        /** 두 가지 화면 크기를 모두 본 뒤에는 넓은 쪽을, 그 전에는 smallestWidth ≥ 600dp 를 메인으로 판단 */
        private fun detectInner(w: Int, h: Int): Boolean {
            if (w <= 0 || h <= 0) return inner
            val area = w.toLong() * h
            knownAreas += area
            if (knownAreas.size >= 2) return area >= knownAreas.max() * 9 / 10
            return min(w, h) / density >= 600f
        }

        private fun ensureBitmap() {
            if (bitmap != null && loadedImageVersion == config.imageVersion) return
            val maxDim = max(max(width, height), 1080)
            bitmap = WallpaperImage.load(this@DuoWallpaperService, maxDim)
            loadedImageVersion = config.imageVersion
        }

        private fun draw() {
            val bmp = bitmap ?: return
            if (width <= 0 || height <= 0) return
            val holder = surfaceHolder
            val canvas = try {
                holder.lockHardwareCanvas()
            } catch (e: Exception) {
                null
            } ?: return

            try {
                val fx = EffectFrame.of(current, config, inner)
                val w = width.toFloat()
                val h = height.toFloat()

                // centerCrop
                val scale = max(w / bmp.width, h / bmp.height)
                val sw = (w / scale).toInt()
                val sh = (h / scale).toInt()
                val sx = (bmp.width - sw) / 2
                val sy = (bmp.height - sh) / 2
                srcRect.set(sx, sy, sx + sw, sy + sh)
                dstRect.set(0f, 0f, w, h)

                node.setPosition(0, 0, width, height)
                val rc = node.beginRecording()
                rc.save()
                rc.scale(fx.scaleX, fx.scaleY, w / 2f, h / 2f)
                rc.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
                rc.restore()
                node.endRecording()
                val blurPx = fx.blurDp * density
                node.setRenderEffect(
                    if (blurPx >= 0.5f) RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP) else null
                )

                canvas.drawColor(Color.BLACK)
                canvas.drawRenderNode(node)

                if (fx.dim > 0.002f) {
                    // 전체를 가라앉히고, 가장자리는 더 어둡게 (심도 느낌)
                    dimPaint.color = Color.argb((fx.dim * 0.65f * 255).toInt(), 0, 0, 0)
                    canvas.drawRect(0f, 0f, w, h, dimPaint)
                    vignettePaint.shader = RadialGradient(
                        w / 2f, h / 2f, hypot(w, h) / 2f,
                        intArrayOf(Color.TRANSPARENT, Color.argb((fx.dim * 255).toInt(), 0, 0, 0)),
                        floatArrayOf(0.35f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                    canvas.drawRect(0f, 0f, w, h, vignettePaint)
                }

                if (config.debugHud) {
                    hudPaint.textSize = 18f * density
                    val lines = listOf(
                        "각도 ${angle.toInt()}° · ${if (inner) "메인" else "커버"} 화면 · 표시 ${(current * 100).toInt()}%",
                        "화면 ${width}×${height} · 센서 ${if (hingeSensor == null) "없음" else "이벤트 $sensorEvents"}",
                    )
                    lines.forEachIndexed { i, line ->
                        canvas.drawText(line, w / 2f, h * 0.18f + i * 26f * density, hudPaint)
                    }
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}
