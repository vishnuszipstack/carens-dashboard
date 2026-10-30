package dev.carens.dashboard.car

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint

/** What the car dashboard picture shows. Strings are pre-formatted by the caller. */
data class DashArtModel(
    val speed: Int?,
    val unitLabel: String,
    val fraction: Float,
    val over: Boolean,
    val status: String?,
    val clock: String,
    val weatherEmoji: String?,
    val weatherTemp: String?,
    val tripLine: String,
    val musicLine: String?,
    val placeLine: String?,
    /** e.g. "↰ 300 m · MG Road" while navigating. */
    val navLine: String? = null,
    val bearing: Float?,
    val map: Bitmap?,
)

/**
 * Draws the square picture Android Auto shows on its now-playing screen —
 * the only free-form area a sideloaded media app gets. Map-first so it reads
 * at a glance:
 *
 *   ┌──────────────────────────┐
 *   │ 7:11 PM          ☁ 26°   │   (next-turn banner while navigating)
 *   │          ▲ you           │
 *   │   map fills the square   │
 *   │ (42)         Vattamkulam │
 *   └──────────────────────────┘
 * Trip and song stay in the text lines next to the picture.
 */
object GaugeArt {
    const val SIZE = 512

    /** Speed bubble size relative to the original 96 px radius design. */
    private const val SPEED_RADIUS = 96f
    private const val SPEED_SCALE = 0.7f

    private val bg = Color.parseColor("#05070A")
    private val panel = Color.parseColor("#11161D")
    private val track = Color.parseColor("#232B36")
    private val accent = Color.parseColor("#3DDC97")
    private val danger = Color.parseColor("#FF4D4F")
    private val text = Color.parseColor("#F2F5F8")
    private val muted = Color.parseColor("#97A3B3")
    private val warn = Color.parseColor("#FFB020")

    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 22f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun render(m: DashArtModel): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val full = RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat())

        // 1. Map fills the whole picture.
        val map = m.map
        if (map != null) {
            c.drawBitmap(map, null, full, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            text(c, "Map loading…", SIZE / 2f, SIZE / 2f, 30f, muted, bold = false, align = Paint.Align.CENTER)
        }
        // Darken top and bottom edges so overlays stay readable on any map.
        fill.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, SIZE.toFloat(),
            intArrayOf(0xB0000000.toInt(), 0x00000000, 0x00000000, 0xC0000000.toInt()),
            floatArrayOf(0f, 0.28f, 0.6f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        c.drawRect(full, fill)
        fill.shader = null

        // 2. Your position and heading, in the centre.
        drawArrow(c, SIZE / 2f, SIZE / 2f + 10f, m.bearing)

        // 3. Top: next turn while navigating, otherwise clock + weather.
        val nav = m.navLine
        if (nav != null) {
            val r = RectF(14f, 14f, SIZE - 14f, 118f)
            fill.color = Color.parseColor("#0E7A4E")
            c.drawRoundRect(r, 26f, 26f, fill)
            text(c, nav, r.left + 24f, r.centerY() + 16f, 44f, Color.WHITE, bold = true, maxWidth = r.width() - 48f)
        } else {
            text(c, m.clock, 24f, 72f, 56f, text, bold = true)
            val wx = listOfNotNull(m.weatherEmoji, m.weatherTemp).joinToString(" ")
            if (wx.isNotBlank()) text(c, wx, SIZE - 24f, 72f, 44f, text, bold = true, align = Paint.Align.RIGHT)
        }

        // 4. Bottom-left: speed bubble, kept compact so the map stays visible.
        val r0 = SPEED_RADIUS * SPEED_SCALE
        val margin = 16f
        drawSpeed(c, m, cx = margin + r0, cy = SIZE - margin - r0, radius = r0, k = SPEED_SCALE)

        // 5. Bottom-right: clock while navigating, else where you are.
        val right = if (nav != null) {
            listOfNotNull(m.clock, m.weatherTemp).joinToString("  ")
        } else {
            m.placeLine
        }
        if (!right.isNullOrBlank()) {
            val maxW = SIZE - (margin + 2 * r0 + 24f) - 24f - 28f
            tp.textSize = 30f
            tp.typeface = bold
            val fitted = TextUtils.ellipsize(right, tp, maxW, TextUtils.TruncateAt.END).toString()
            val w = tp.measureText(fitted)
            val r = RectF(SIZE - 24f - w - 28f, SIZE - 84f, SIZE - 24f, SIZE - 30f)
            fill.color = 0xCC0B1017.toInt()
            c.drawRoundRect(r, 18f, 18f, fill)
            text(c, fitted, r.left + 14f, r.bottom - 17f, 30f, text, bold = true)
        }
        return bmp
    }

    /** [k] scales everything inside the bubble together with its [radius]. */
    private fun drawSpeed(c: Canvas, m: DashArtModel, cx: Float, cy: Float, radius: Float, k: Float) {
        fill.color = 0xEE0B1017.toInt()
        c.drawCircle(cx, cy, radius, fill)
        val inset = 12f * k
        val ring = RectF(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset)
        arcPaint.strokeWidth = 12f * k
        arcPaint.color = track
        c.drawArc(ring, 135f, 270f, false, arcPaint)
        if (m.speed != null && m.fraction > 0f) {
            arcPaint.color = if (m.over) danger else accent
            c.drawArc(ring, 135f, 270f * m.fraction.coerceIn(0f, 1f), false, arcPaint)
        }
        val numColor = when {
            m.speed == null -> muted
            m.over -> danger
            else -> text
        }
        text(c, m.speed?.toString() ?: "--", cx, cy + 22f * k, 72f * k, numColor, bold = true, align = Paint.Align.CENTER)
        text(c, m.unitLabel, cx, cy + 58f * k, 22f * k, muted, bold = false, align = Paint.Align.CENTER)
        m.status?.let {
            // Warnings stay readable: shrink less than the bubble, and anchor to the left edge.
            val size = 22f
            tp.textSize = size
            tp.typeface = bold
            val w = tp.measureText(it)
            val left = (cx - w / 2 - 12f).coerceAtLeast(8f)
            val r = RectF(left, cy - radius - 40f, left + w + 24f, cy - radius - 8f)
            fill.color = 0xE0000000.toInt()
            c.drawRoundRect(r, 12f, 12f, fill)
            text(c, it, r.centerX(), r.bottom - 9f, size, warn, bold = true, align = Paint.Align.CENTER)
        }
    }

    private fun drawArrow(c: Canvas, x: Float, y: Float, bearing: Float?) {
        c.save()
        c.translate(x, y)
        c.rotate(bearing ?: 0f)
        val arrow = Path().apply {
            moveTo(0f, -30f)
            lineTo(22f, 24f)
            lineTo(0f, 12f)
            lineTo(-22f, 24f)
            close()
        }
        fill.style = Paint.Style.FILL
        fill.color = accent
        c.drawPath(arrow, fill)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 4f
        fill.color = Color.WHITE
        c.drawPath(arrow, fill)
        fill.style = Paint.Style.FILL
        c.restore()
    }

    private fun text(
        c: Canvas,
        s: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean,
        align: Paint.Align = Paint.Align.LEFT,
        maxWidth: Float? = null,
    ) {
        tp.textAlign = align
        tp.typeface = if (bold) this.bold else Typeface.DEFAULT
        tp.textSize = size
        tp.color = color
        tp.setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
        val out = maxWidth?.let { TextUtils.ellipsize(s, tp, it, TextUtils.TruncateAt.END).toString() } ?: s
        c.drawText(out, x, y, tp)
        tp.clearShadowLayer()
    }
}
