package com.chu.foldanim

import android.content.Context
import android.content.SharedPreferences

enum class AnimStyle(val label: String) {
    CURTAIN("힌지 커튼"),
    IRIS("원형 아이리스"),
    FADE("페이드"),
}

/**
 * 앱 설정 값.
 *
 * [curve] 는 [FoldSettings.CURVE_ANGLES] 각 지점(0°, 30° … 180°)에서 화면을 몇 % 보여줄지(0~100) 입니다.
 * 그 사이 각도는 선형 보간합니다.
 */
data class FoldConfig(
    val enabled: Boolean = false,
    val style: AnimStyle = AnimStyle.CURTAIN,
    val onUnfold: Boolean = true,
    val onFold: Boolean = true,
    val coverReveal: Boolean = true,
    val alwaysFromClosed: Boolean = true,
    val durationMs: Int = 400,
    val cornerDp: Int = 28,
    val curve: List<Int> = FoldSettings.DEFAULT_CURVE,
) {
    /** 힌지 각도에 따른 화면 표시 비율 (0.0 ~ 1.0). */
    fun revealAt(angle: Float): Float {
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
}

object FoldSettings {
    private const val PREFS = "fold_settings"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_STYLE = "style"
    private const val KEY_ON_UNFOLD = "on_unfold"
    private const val KEY_ON_FOLD = "on_fold"
    private const val KEY_COVER_REVEAL = "cover_reveal"
    private const val KEY_ALWAYS_FROM_CLOSED = "always_from_closed"
    private const val KEY_DURATION = "duration_ms"
    private const val KEY_CORNER = "corner_dp"
    private const val KEY_CURVE = "curve"

    val CURVE_ANGLES = listOf(0, 30, 60, 90, 120, 150, 180)
    val DEFAULT_CURVE = listOf(0, 5, 20, 45, 70, 90, 100)

    val PRESETS: List<Pair<String, List<Int>>> = listOf(
        "기본" to DEFAULT_CURVE,
        "선형" to listOf(0, 17, 33, 50, 67, 83, 100),
        "빠르게 열림" to listOf(0, 30, 60, 85, 100, 100, 100),
        "늦게 열림" to listOf(0, 0, 5, 15, 35, 70, 100),
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): FoldConfig {
        val p = prefs(context)
        val d = FoldConfig()
        return FoldConfig(
            enabled = p.getBoolean(KEY_ENABLED, d.enabled),
            style = runCatching { AnimStyle.valueOf(p.getString(KEY_STYLE, d.style.name)!!) }
                .getOrDefault(d.style),
            onUnfold = p.getBoolean(KEY_ON_UNFOLD, d.onUnfold),
            onFold = p.getBoolean(KEY_ON_FOLD, d.onFold),
            coverReveal = p.getBoolean(KEY_COVER_REVEAL, d.coverReveal),
            alwaysFromClosed = p.getBoolean(KEY_ALWAYS_FROM_CLOSED, d.alwaysFromClosed),
            durationMs = p.getInt(KEY_DURATION, d.durationMs),
            cornerDp = p.getInt(KEY_CORNER, d.cornerDp),
            curve = parseCurve(p.getString(KEY_CURVE, null)) ?: d.curve,
        )
    }

    fun save(context: Context, c: FoldConfig) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, c.enabled)
            .putString(KEY_STYLE, c.style.name)
            .putBoolean(KEY_ON_UNFOLD, c.onUnfold)
            .putBoolean(KEY_ON_FOLD, c.onFold)
            .putBoolean(KEY_COVER_REVEAL, c.coverReveal)
            .putBoolean(KEY_ALWAYS_FROM_CLOSED, c.alwaysFromClosed)
            .putInt(KEY_DURATION, c.durationMs)
            .putInt(KEY_CORNER, c.cornerDp)
            .putString(KEY_CURVE, c.curve.joinToString(","))
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun parseCurve(s: String?): List<Int>? {
        val values = s?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        if (values.size != CURVE_ANGLES.size) return null
        return values.map { it.coerceIn(0, 100) }
    }
}
