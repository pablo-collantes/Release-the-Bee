package io.github.pablocollantes.releasethebee.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A battery outline filled to [level]: green while charging, red at 15% and under. */
class BatteryView(ctx: Context, private val ui: Ui) : View(ctx) {
    private var level = 0
    private var charging = false
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dpf(1.5f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun set(level: Int, charging: Boolean) {
        this.level = level.coerceIn(0, 100)
        this.charging = charging
        contentDescription = "RTB battery $level%" + if (charging) ", charging" else ""
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(ui.dp(22), ui.dp(12))

    override fun onDraw(c: Canvas) {
        val sw = outline.strokeWidth
        val nub = ui.dpf(2f)
        val corner = ui.dpf(2.5f)
        val color = when {
            charging -> ui.green
            level <= 15 -> ui.red
            else -> ui.fg
        }
        outline.color = Ui.alpha(color, 0.6f)
        fill.color = color
        r.set(sw / 2, sw / 2, width - nub - sw / 2, height - sw / 2)
        c.drawRoundRect(r, corner, corner, outline)
        val nubTop = height * 0.3f
        r.set(width - nub, nubTop, width.toFloat(), height - nubTop)
        c.drawRoundRect(r, nub / 2, nub / 2, outline.also { it.style = Paint.Style.FILL })
        outline.style = Paint.Style.STROKE
        val inset = sw + ui.dpf(1f)
        val inner = width - nub - 2 * inset
        r.set(inset, inset, inset + inner * level / 100f, height - inset)
        if (level > 0) c.drawRoundRect(r, corner / 2, corner / 2, fill)
    }
}
