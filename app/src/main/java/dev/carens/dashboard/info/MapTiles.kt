package dev.carens.dashboard.info

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.SystemClock
import android.util.LruCache
import dev.carens.dashboard.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Small OpenStreetMap tile renderer for the car screen's mini-map (the phone
 * uses osmdroid). Tiles are cached in memory and on disk so a drive mostly
 * reuses them, in line with the OSM tile usage policy.
 *
 * Built to keep going on patchy mobile data:
 *  - a whole render is time-boxed, so one slow tile can't freeze the map;
 *  - tiles download two at a time (OSM's limit) instead of one by one;
 *  - an old cached tile is used when a fresh one can't be fetched;
 *  - failed tiles and server push-back (429/403) back off instead of retrying every second.
 */
class MapTiles(context: Context) {
    private val diskDir = File(context.cacheDir, "car-tiles").apply { mkdirs() }
    private val memory = LruCache<String, Bitmap>(48)
    private val downloads = Semaphore(2)

    /** Tile key → when it may be tried again after a failed download. */
    private val failedUntil = ConcurrentHashMap<String, Long>()

    /** Server asked us to slow down; don't hit it at all until then. */
    @Volatile private var serverBackoffUntil = 0L

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

            val firstTx = floor(left / TILE).toInt()
            val firstTy = floor(top / TILE).toInt()
            val lastTx = floor((left + size) / TILE).toInt()
            val lastTy = floor((top + size) / TILE).toInt()
            val wanted = buildList {
                for (tx in firstTx..lastTx) for (ty in firstTy..lastTy) {
                    if (ty in 0 until n) add(Triple(tx, ty, ((tx % n) + n) % n))
                }
            }

            // Fetch missing tiles in parallel, but give up on stragglers after a few seconds.
            val tiles = ConcurrentHashMap<Pair<Int, Int>, Bitmap>()
            withTimeoutOrNull(RENDER_BUDGET_MS) {
                coroutineScope {
                    wanted.map { (tx, ty, wx) ->
                        async { tile(zoom, wx, ty)?.let { tiles[tx to ty] = it } }
                    }.awaitAll()
                }
            }
            // Anything still missing: fall back to an expired copy on disk, if there is one.
            for ((tx, ty, wx) in wanted) {
                if (tiles[tx to ty] == null) staleTile(zoom, wx, ty)?.let { tiles[tx to ty] = it }
            }
            if (tiles.isEmpty()) return@withContext null

            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(if (dark) 0xFF10151C.toInt() else 0xFFE8E4DC.toInt())
            for ((key, bmp) in tiles) {
                canvas.drawBitmap(
                    bmp,
                    (key.first * TILE - left).toFloat(),
                    (key.second * TILE - top).toFloat(),
                    if (dark) darkPaint else dayPaint,
                )
            }
            out
        }

    private suspend fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        memory.get(key)?.let { return it }
        val file = file(z, x, y)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < FRESH_MS) {
            BitmapFactory.decodeFile(file.path)?.let { memory.put(key, it); return it }
        }
        val now = SystemClock.elapsedRealtime()
        if (now < serverBackoffUntil || now < (failedUntil[key] ?: 0L)) return null

        return downloads.withPermit {
            memory.get(key) ?: runInterruptible { download(z, x, y, key, file) }
        }
    }

    private fun download(z: Int, x: Int, y: Int, key: String, file: File): Bitmap? {
        val result = runCatching {
            val conn = (URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 5_000
                setRequestProperty(
                    "User-Agent",
                    "CarensDashboard/${BuildConfig.VERSION_NAME} (+https://github.com/vishnuszipstack/carens-dashboard)",
                )
            }
            try {
                when (val code = conn.responseCode) {
                    200 -> {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
                            // Write via a temp file so a cut-off download never leaves a broken tile.
                            val tmp = File(file.path + ".part")
                            tmp.writeBytes(bytes)
                            tmp.renameTo(file)
                        }
                    }
                    429, 403, 418 -> {
                        val retry = conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
                        serverBackoffUntil = SystemClock.elapsedRealtime() + (retry ?: SERVER_BACKOFF_MS)
                        android.util.Log.w(TAG, "Tile server refused ($code); backing off")
                        null
                    }
                    else -> null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
        if (result != null) {
            failedUntil.remove(key)
            memory.put(key, result)
        } else {
            failedUntil[key] = SystemClock.elapsedRealtime() + TILE_RETRY_MS
        }
        return result
    }

    private fun staleTile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        memory.get(key)?.let { return it }
        val file = file(z, x, y)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.path)
    }

    private fun file(z: Int, x: Int, y: Int) = File(diskDir, "$z-$x-$y.png")

    private companion object {
        const val TAG = "MapTiles"
        const val TILE = 256
        const val FRESH_MS = 30L * 24 * 3600_000
        const val RENDER_BUDGET_MS = 8_000L
        const val TILE_RETRY_MS = 20_000L
        const val SERVER_BACKOFF_MS = 120_000L
    }
}
