package dev.carens.dashboard.trips

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes trip history to CSV and opens the share sheet (Drive, WhatsApp, email…). */
object TripExport {
    fun csv(trips: List<TripEntity>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("start,end,distance_km,duration_min,moving_min,avg_moving_kmh,max_kmh,gps_gap_min,auto_started\n")
        trips.sortedBy { it.startedAtWallMs }.forEach { t ->
            sb.append(iso.format(Date(t.startedAtWallMs))).append(',')
                .append(t.endedAtWallMs?.let { iso.format(Date(it)) } ?: "").append(',')
                .append(String.format(Locale.US, "%.2f", t.distanceM / 1000)).append(',')
                .append(String.format(Locale.US, "%.1f", t.durationMs / 60000.0)).append(',')
                .append(String.format(Locale.US, "%.1f", t.movingMs / 60000.0)).append(',')
                .append(t.avgMovingKmh()?.let { String.format(Locale.US, "%.1f", it) } ?: "").append(',')
                .append(String.format(Locale.US, "%.1f", t.maxSpeedKmh)).append(',')
                .append(String.format(Locale.US, "%.1f", t.gpsGapMs / 60000.0)).append(',')
                .append(t.autoStarted).append('\n')
        }
        return sb.toString()
    }

    fun share(context: Context, trips: List<TripEntity>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val file = File(dir, "carens-trips-$stamp.csv")
        file.writeText(csv(trips))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Kia Carens trips")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export trips").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
