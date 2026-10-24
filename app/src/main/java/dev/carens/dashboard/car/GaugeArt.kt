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
 * Draws the square picture Android Auto shows on its now-playing screen.
 * That picture is the only free-form area a sideloaded media app gets, so it
 * packs a speed gauge, mini-map, clock, weather, trip and music.
 *
 * Layout (512 px):
 *   ┌───────────────┬──────────┐
 *   │  speed gauge  │ mini-map │
 *   │               ├──────────┤
 *   │               │ clock/wx │
 *   ├───────────────┴──────────┤
 *   │ trip · place · music     │
 *   └──────────────────────────┘
 */
object GaugeArt {
    const val SIZE = 512

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

        drawGauge(c, m, RectF(0f, 0f, 300f, 340f))
        drawMap(c, m, RectF(312f, 14f, 498f, 200f))
        drawClockWeather(c, m, RectF(312f, 212f, 498f, 340f))
        drawBottom(c, m, RectF(14f, 352f, 498f, 498f))
        return bmp
    }

    private fun drawGauge(c: Canvas, m: DashArtModel, r: RectF) {
        val cx = r.centerX()
        val d = 250f
        val oval = RectF(cx - d / 2, 36f, cx + d / 2, 36f + d)
        arcPaint.color = track
        c.drawArc(oval, 150f, 240f, false, arcPaint)
        if (m.speed != null && m.fraction > 0f) {
            arcPaint.color = if (m.over) danger else accent
            c.drawArc(oval, 150f, 240f * m.fraction.coerceIn(0f, 1f), false, arcPaint)
        }
        tp.textAlign = Paint.Align.CENTER
        tp.typeface = bold
        tp.textSize = 112f
        tp.color = when {
            m.speed == null -> muted
            m.over -> danger
            else -> text
        }
        c.drawText(m.speed?.toString() ?: "--", cx, 200f, tp)
        tp.typeface = Typeface.DEFAULT
        tp.textSize = 30f
        tp.color = muted
        c.drawText(m.unitLabel, cx, 240f, tp)
        if (m.status != null) {
            tp.color = warn
            tp.textSize = 28f
            c.drawText(m.status, cx, 322f, tp)
        }
    }

    private fun drawMap(c: Canvas, m: DashArtModel, r: RectF) {
        val radius = 22f
        fill.shader = null
        fill.color = panel
        c.drawRoundRect(r, radius, radius, fill)
        val map = m.map
        if (map != null) {
            val shader = BitmapShader(map, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val matrix = Matrix().apply {
                setScale(r.width() / map.width, r.height() / map.height)
                postTranslate(r.left, r.top)
            }
            shader.setLocalMatrix(matrix)
            fill.shader = shader
            c.drawRoundRect(r, radius, radius, fill)
            fill.shader = null
        } else {
            tp.textAlign = Paint.Align.CENTER
            tp.typeface = Typeface.DEFAULT
            tp.textSize = 22f
            tp.color = muted
            c.drawText("Map loading", r.centerX(), r.centerY() + 8f, tp)
        }
        // Heading arrow at the centre (the map is north-up).
        c.save()
        c.translate(r.centerX(), r.centerY())
        c.rotate(m.bearing ?: 0f)
        val arrow = Path().apply {
            moveTo(0f, -20f)
            lineTo(14f, 16f)
            lineTo(0f, 8f)
            lineTo(-14f, 16f)
            close()
        }
        fill.color = accent
        fill.style = Paint.Style.FILL
        c.drawPath(arrow, fill)
        fill.color = Color.WHITE
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 3f
        c.drawPath(arrow, fill)
        fill.style = Paint.Style.FILL
        c.restore()
    }

    private fun drawClockWeather(c: Canvas, m: DashArtModel, r: RectF) {
        fill.shader = null
        fill.color = panel
        c.drawRoundRect(r, 22f, 22f, fill)
        tp.textAlign = Paint.Align.CENTER
        tp.typeface = bold
        tp.textSize = 50f
        tp.color = text
        c.drawText(m.clock, r.centerX(), r.top + 58f, tp)
        tp.typeface = Typeface.DEFAULT
        tp.textSize = 38f
        val weather = listOfNotNull(m.weatherEmoji, m.weatherTemp).joinToString(" ")
        c.drawText(weather.ifBlank { "—" }, r.centerX(), r.top + 110f, tp)
    }

    private fun drawBottom(c: Canvas, m: DashArtModel, r: RectF) {
        fill.shader = null
        fill.color = panel
        c.drawRoundRect(r, 22f, 22f, fill)
        tp.textAlign = Paint.Align.LEFT
        val x = r.left + 20f
        val w = r.width() - 40f
        val lines = listOfNotNull(
            m.navLine?.let { Triple(it, accent, true) },
            Triple(m.tripLine, text, m.navLine == null),
            m.placeLine?.let { Triple("📍 $it", muted, false) },
            m.musicLine?.let { Triple("♪ $it", accent, false) },
        ).take(3)
        var y = r.top + 44f
        for ((s, color, isBold) in lines) {
            tp.typeface = if (isBold) bold else Typeface.DEFAULT
            tp.textSize = if (isBold) 34f else 30f
            tp.color = color
            val fitted = TextUtils.ellipsize(s, tp, w, TextUtils.TruncateAt.END).toString()
            c.drawText(fitted, x, y, tp)
            y += 46f
        }
    }
}
