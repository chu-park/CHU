package com.chu.foldanim

import android.content.Context
import android.content.SharedPreferences

/**
 * 앱 설정 값.
 *
 * [curve] 는 [FoldSettings.CURVE_ANGLES] 각 지점(0°, 30° … 180°)에서 메인 화면을 몇 % 선명하게 보여줄지(0~100) 입니다.
 * 그 사이 각도는 선형 보간합니다.
 */
data class FoldConfig(
    val enabled: Boolean = true,
    val curve: List<Int> = FoldSettings.DEFAULT_CURVE,
    /** 커버 화면이 이 각도까지 펼쳐지면 완전히 흐려져 사라짐 */
    val coverAngle: Int = 50,
    val blurDp: Int = 40,
    val zoomPct: Int = 25,
    val dimPct: Int = 85,
    val smoothMs: Int = 180,
    val imageVersion: Long = 0L,
) {
    /** 메인(안쪽) 화면: 힌지 각도에 따른 화면 표시 비율 (0.0 ~ 1.0). */
    fun innerRevealAt(angle: Float): Float {
        val angles = FoldSettings.CURVE_ANGLES
        val a = angle.coerceIn(angles.first().toFloat(), angles.last().toFloat())
        for (i in 0 until angles.size - 1) {
            val a0 = angles[i]
            val a1 = angles[i + 1]
            if (a <= a1) {
                val t = (a - a0) / (a1 - a0).toFloat()
                val p = curve[i] + (curve[i + 1] - curve[i]) * t
                return (p / 100f).coerceIn(0f, 1f)
            }
        }
        return curve.last() / 100f
    }

    /** 커버 화면: 접혀 있을 때 100%, [coverAngle] 까지 펼치면 0%. */
    fun coverRevealAt(angle: Float): Float {
        val t = (angle / coverAngle.coerceAtLeast(1)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    fun revealAt(angle: Float, inner: Boolean): Float =
        if (inner) innerRevealAt(angle) else coverRevealAt(angle)
}

object FoldSettings {
    private const val PREFS = "fold_settings"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_CURVE = "curve"
    private const val KEY_COVER_ANGLE = "cover_angle"
    private const val KEY_BLUR = "blur_dp"
    private const val KEY_ZOOM = "zoom_pct"
    private const val KEY_DIM = "dim_pct"
    private const val KEY_SMOOTH = "smooth_ms"
    private const val KEY_IMAGE_VERSION = "image_version"

    val CURVE_ANGLES = listOf(0, 30, 60, 90, 120, 150, 180)
    val DEFAULT_CURVE = listOf(0, 0, 15, 45, 75, 95, 100)

    val PRESETS: List<Pair<String, List<Int>>> = listOf(
        "기본" to DEFAULT_CURVE,
        "선형" to listOf(0, 17, 33, 50, 67, 83, 100),
        "빠르게 선명" to listOf(0, 20, 55, 85, 100, 100, 100),
        "끝까지 흐리게" to listOf(0, 0, 5, 15, 35, 70, 100),
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): FoldConfig {
        val p = prefs(context)
        val d = FoldConfig()
        return FoldConfig(
            enabled = p.getBoolean(KEY_ENABLED, d.enabled),
            curve = parseCurve(p.getString(KEY_CURVE, null)) ?: d.curve,
            coverAngle = p.getInt(KEY_COVER_ANGLE, d.coverAngle),
            blurDp = p.getInt(KEY_BLUR, d.blurDp),
            zoomPct = p.getInt(KEY_ZOOM, d.zoomPct),
            dimPct = p.getInt(KEY_DIM, d.dimPct),
            smoothMs = p.getInt(KEY_SMOOTH, d.smoothMs),
            imageVersion = p.getLong(KEY_IMAGE_VERSION, d.imageVersion),
        )
    }

    fun save(context: Context, c: FoldConfig) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, c.enabled)
            .putString(KEY_CURVE, c.curve.joinToString(","))
            .putInt(KEY_COVER_ANGLE, c.coverAngle)
            .putInt(KEY_BLUR, c.blurDp)
            .putInt(KEY_ZOOM, c.zoomPct)
            .putInt(KEY_DIM, c.dimPct)
            .putInt(KEY_SMOOTH, c.smoothMs)
            .putLong(KEY_IMAGE_VERSION, c.imageVersion)
            .apply()
    }

    private fun parseCurve(s: String?): List<Int>? {
        val values = s?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        if (values.size != CURVE_ANGLES.size) return null
        return values.map { it.coerceIn(0, 100) }
    }
}
