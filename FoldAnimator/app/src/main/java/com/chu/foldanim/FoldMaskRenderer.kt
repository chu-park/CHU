package com.chu.foldanim

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.hypot

/**
 * 표시 비율(progress)에 맞춰 "아직 가려진 부분"을 검게 칠합니다.
 * 실제 오버레이와 앱 안의 미리보기가 같은 코드를 사용합니다.
 */
class FoldMaskRenderer {
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val hole = RectF()

    /**
     * @param progress 0이면 완전히 가림, 1이면 완전히 보임
     * @param hingeVertical 힌지가 화면을 좌/우로 나누면 true
     */
    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        progress: Float,
        style: AnimStyle,
        cornerPx: Float,
        edgePx: Float,
        hingeVertical: Boolean,
    ) {
        val p = progress.coerceIn(0f, 1f)
        if (p >= 1f || width <= 0f || height <= 0f) return

        if (style == AnimStyle.FADE) {
            maskPaint.alpha = ((1f - p) * 255).toInt()
            canvas.drawRect(0f, 0f, width, height, maskPaint)
            maskPaint.alpha = 255
            return
        }

        val cx = width / 2f
        val cy = height / 2f
        path.reset()
        path.addRect(0f, 0f, width, height, Path.Direction.CW)

        when (style) {
            AnimStyle.CURTAIN -> {
                // 힌지(가운데)에서 양쪽으로 열리고, 동시에 위아래로 살짝 커지면서 카드처럼 펼쳐집니다.
                if (hingeVertical) {
                    val halfW = width * p / 2f
                    val inset = height * (1f - p) * 0.06f
                    hole.set(cx - halfW, inset, cx + halfW, height - inset)
                } else {
                    val halfH = height * p / 2f
                    val inset = width * (1f - p) * 0.06f
                    hole.set(inset, cy - halfH, width - inset, cy + halfH)
                }
                val r = minOf(cornerPx, hole.width() / 2f, hole.height() / 2f)
                if (hole.width() > 0f && hole.height() > 0f) {
                    path.addRoundRect(hole, r, r, Path.Direction.CW)
                }
                canvas.drawPath(path, maskPaint)
                drawEdge(canvas, p, edgePx) { canvas.drawRoundRect(hole, r, r, edgePaint) }
            }
            AnimStyle.IRIS -> {
                val radius = hypot(width, height) / 2f * p
                if (radius > 0f) path.addCircle(cx, cy, radius, Path.Direction.CW)
                canvas.drawPath(path, maskPaint)
                drawEdge(canvas, p, edgePx) { canvas.drawCircle(cx, cy, radius, edgePaint) }
            }
            AnimStyle.FADE -> Unit
        }
    }

    /** 열리는 경계에 은은한 빛 테두리. 다 열릴수록 사라집니다. */
    private inline fun drawEdge(canvas: Canvas, p: Float, edgePx: Float, block: () -> Unit) {
        if (edgePx <= 0f || p <= 0f) return
        edgePaint.strokeWidth = edgePx
        edgePaint.alpha = ((1f - p) * 90).toInt()
        block()
    }
}
