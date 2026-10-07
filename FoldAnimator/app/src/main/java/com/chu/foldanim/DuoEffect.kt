package com.chu.foldanim

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.pow

/**
 * 표시 비율 p(0 = 완전히 사라짐, 1 = 선명)에 대한 한 프레임의 효과 값.
 * 배경화면과 앱 미리보기가 같은 계산을 씁니다.
 */
data class EffectFrame(
    val blurDp: Float,
    val scaleX: Float,
    val scaleY: Float,
    /** 0~1, 검게 가라앉는 정도 */
    val dim: Float,
) {
    companion object {
        /**
         * @param inner 메인(안쪽) 화면이면 true. 메인 화면은 힌지를 가로지르는 방향으로 더 늘어납니다.
         */
        fun of(p: Float, config: FoldConfig, inner: Boolean): EffectFrame {
            val e = 1f - p.coerceIn(0f, 1f)
            val zoom = config.zoomPct / 100f * e
            return EffectFrame(
                blurDp = config.blurDp * e,
                scaleX = 1f + zoom * (if (inner) 1.4f else 0.7f),
                scaleY = 1f + zoom * (if (inner) 0.6f else 1.0f),
                dim = config.dimPct / 100f * e.pow(1.4f),
            )
        }
    }
}

object WallpaperImage {
    private const val FILE_NAME = "wallpaper_image"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** 사용자가 고른 이미지를 앱 내부로 복사합니다. */
    fun import(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)!!.use { input ->
            file(context).outputStream().use { input.copyTo(it) }
        }
        true
    }.getOrDefault(false)

    fun reset(context: Context) {
        file(context).delete()
    }

    /** 긴 변이 [maxDim] 정도가 되도록 줄여서 읽습니다. 없으면 기본 그라데이션 이미지. */
    fun load(context: Context, maxDim: Int): Bitmap {
        val f = file(context)
        if (f.exists()) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { return it }
        }
        return defaultImage(minOf(maxDim, 1440))
    }

    private fun defaultImage(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, s, s,
            intArrayOf(Color.rgb(20, 24, 70), Color.rgb(90, 40, 140), Color.rgb(230, 110, 90)),
            null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, s, s, paint)
        val blobs = listOf(
            Triple(0.25f, 0.3f, Color.argb(200, 80, 160, 255)),
            Triple(0.75f, 0.45f, Color.argb(180, 255, 120, 200)),
            Triple(0.45f, 0.8f, Color.argb(170, 255, 200, 90)),
        )
        for ((x, y, color) in blobs) {
            paint.shader = RadialGradient(
                x * s, y * s, s * 0.35f,
                intArrayOf(color, Color.TRANSPARENT), null, Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, s, s, paint)
        }
        return bmp
    }
}
