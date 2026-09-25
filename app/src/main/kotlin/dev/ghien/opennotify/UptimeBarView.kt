package dev.ghien.opennotify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Thanh trạng thái kiểu uptime-monitor: mỗi ô là một khoảng thời gian, màu theo tỉ lệ "sống"
 * (xanh: đủ, vàng: gián đoạn một phần, đỏ: mất hẳn, xám: chưa có dữ liệu).
 */
class UptimeBarView(context: Context) : View(context) {

    private var slots = DoubleArray(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val up = ContextCompat.getColor(context, R.color.uptime_up)
    private val partial = ContextCompat.getColor(context, R.color.uptime_partial)
    private val down = ContextCompat.getColor(context, R.color.uptime_down)
    private val noData = ContextCompat.getColor(context, R.color.uptime_nodata)

    fun setSlots(values: DoubleArray) {
        slots = values
        invalidate()
    }

    private fun colorFor(v: Double): Int = when {
        v < 0 -> noData
        v >= 0.999 -> up
        v <= 0.001 -> down
        else -> partial
    }

    override fun onDraw(canvas: Canvas) {
        val n = slots.size
        if (n == 0) return
        val cell = width.toFloat() / n
        val gap = resources.displayMetrics.density * 0.5f
        for (i in 0 until n) {
            paint.color = colorFor(slots[i])
            canvas.drawRect(i * cell + gap, 0f, (i + 1) * cell - gap, height.toFloat(), paint)
        }
    }
}
