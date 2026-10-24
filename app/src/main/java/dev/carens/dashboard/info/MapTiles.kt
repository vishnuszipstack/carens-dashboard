package dev.carens.dashboard.info

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.LruCache
import dev.carens.dashboard.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Small OpenStreetMap tile renderer for the car screen's mini-map (the phone
 * uses osmdroid). Tiles are cached in memory and on disk so a drive mostly
 * reuses them, in line with the OSM tile usage policy.
 */
class MapTiles(context: Context) {
    private val diskDir = File(context.cacheDir, "car-tiles").apply { mkdirs() }
    private val memory = LruCache<String, Bitmap>(48)
    private val fetchLock = Mutex()

    private val darkPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Invert, then rotate hue back so water stays blue-ish: a cheap night map.
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val dim = ColorMatrix().apply { setScale(0.85f, 0.88f, 0.95f, 1f) }
        invert.postConcat(dim)
        colorFilter = ColorMatrixColorFilter(invert)
    }
    private val dayPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Renders a [size]×[size] map centred on the position, or null if no tiles could be loaded. */
    suspend fun render(lat: Double, lon: Double, zoom: Int, size: Int, dark: Boolean): Bitmap? =
        withContext(Dispatchers.IO) {
            val n = 1 shl zoom
            val worldX = (lon + 180.0) / 360.0 * n * TILE
            val latRad = Math.toRadians(lat)
            val worldY = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n * TILE
            val left = worldX - size / 2.0
            val top = worldY - size / 2.0

            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(if (dark) 0xFF10151C.toInt() else 0xFFE8E4DC.toInt())
            var drawn = 0
            val firstTx = floor(left / TILE).toInt()
            val firstTy = floor(top / TILE).toInt()
            val lastTx = floor((left + size) / TILE).toInt()
            val lastTy = floor((top + size) / TILE).toInt()
            for (tx in firstTx..lastTx) {
                for (ty in firstTy..lastTy) {
                    if (ty < 0 || ty >= n) continue
                    val wrappedX = ((tx % n) + n) % n
                    val tile = tile(zoom, wrappedX, ty) ?: continue
                    canvas.drawBitmap(
                        tile,
                        (tx * TILE - left).toFloat(),
                        (ty * TILE - top).toFloat(),
                        if (dark) darkPaint else dayPaint,
                    )
                    drawn++
                }
            }
            if (drawn == 0) null else out
        }

    private suspend fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        memory.get(key)?.let { return it }
        val file = File(diskDir, "$z-$x-$y.png")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 30L * 24 * 3600_000) {
            BitmapFactory.decodeFile(file.path)?.let { memory.put(key, it); return it }
        }
        return fetchLock.withLock {
            memory.get(key) ?: runCatching {
                val conn = (URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", "CarensDashboard/${BuildConfig.VERSION_NAME} (personal use)")
                }
                try {
                    if (conn.responseCode != 200) return@runCatching null
                    val bytes = conn.inputStream.use { it.readBytes() }
                    file.writeBytes(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()?.also { memory.put(key, it) }
        }
    }

    private companion object {
        const val TILE = 256
    }
}
