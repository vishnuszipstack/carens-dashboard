package dev.carens.dashboard.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Draws the speedometer used as the "album art" on the car's now-playing
 * screen. Kept small (~400 KB) because it crosses a binder call on each update.
 */
object GaugeArt {
    private const val SIZE = 320

    private val bg = Color.parseColor("#05070A")
    private val track = Color.parseColor("#1E252F")
    private val accent = Color.parseColor("#3DDC97")
    private val danger = Color.parseColor("#FF4D4F")
    private val text = Color.parseColor("#E8EDF2")
    private val muted = Color.parseColor("#8B96A5")
    private val warn = Color.parseColor("#FFB020")

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = SIZE * 0.06f
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = SIZE * 0.30f
        fontFeatureSettings = "tnum"
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = SIZE * 0.075f
    }

    /**
     * @param speed display speed in the user's unit, or null when there's no reliable GPS.
     * @param fraction 0..1 position on the arc.
     */
    fun render(speed: Int?, unitLabel: String, fraction: Float, over: Boolean, status: String?): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)

        val pad = SIZE * 0.12f
        val oval = RectF(pad, pad, SIZE - pad, SIZE - pad)
        arcPaint.color = track
        c.drawArc(oval, 150f, 240f, false, arcPaint)
        if (speed != null && fraction > 0f) {
            arcPaint.color = if (over) danger else accent
            c.drawArc(oval, 150f, 240f * fraction.coerceIn(0f, 1f), false, arcPaint)
        }

        val cx = SIZE / 2f
        numberPaint.color = when {
            speed == null -> muted
            over -> danger
            else -> text
        }
        c.drawText(speed?.toString() ?: "--", cx, SIZE * 0.56f, numberPaint)

        smallPaint.color = muted
        c.drawText(unitLabel, cx, SIZE * 0.68f, smallPaint)

        if (status != null) {
            smallPaint.color = warn
            c.drawText(status, cx, SIZE * 0.86f, smallPaint)
        }
        return bmp
    }
}
