package com.chu.foldanim

import android.content.Context
import android.graphics.Canvas
import android.view.View

class FoldOverlayView(context: Context) : View(context) {
    private val renderer = FoldMaskRenderer()
    private val density = resources.displayMetrics.density

    var style: AnimStyle = AnimStyle.CURTAIN
    var cornerDp: Int = 28
    var hingeVertical: Boolean = true

    var progress: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(
            canvas = canvas,
            width = width.toFloat(),
            height = height.toFloat(),
            progress = progress,
            style = style,
            cornerPx = cornerDp * density,
            edgePx = 2f * density,
            hingeVertical = hingeVertical,
        )
    }
}
