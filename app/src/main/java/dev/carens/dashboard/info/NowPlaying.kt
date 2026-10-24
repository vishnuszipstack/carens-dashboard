package dev.carens.dashboard.info

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exists only so the user can grant "Notification access", which is what
 * Android requires before an app may see other apps' media sessions.
 * It ignores notifications themselves.
 */
class MusicListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        (application as? dev.carens.dashboard.DashApp)?.container?.music?.refresh()
    }
}

data class NowPlaying(
    val title: String,
    val artist: String?,
    val appName: String,
    val packageName: String,
    val isPlaying: Boolean,
    val art: Bitmap?,
    val canSkip: Boolean,
    val album: String? = null,
    val durationMs: Long = 0,
    /** Position at [positionUpdatedAt] (elapsedRealtime); extrapolate with [speed] while playing. */
    val positionMs: Long = 0,
    val positionUpdatedAt: Long = 0,
    val speed: Float = 1f,
    val canSeek: Boolean = false,
    val queue: List<QueueEntry> = emptyList(),
    val activeQueueId: Long = -1,
) {
    fun currentPositionMs(nowElapsed: Long): Long {
        if (!isPlaying || positionUpdatedAt == 0L) return positionMs
        val p = positionMs + ((nowElapsed - positionUpdatedAt) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

data class QueueEntry(val id: Long, val title: String, val subtitle: String?)

/** An installed app that can play media in the car (it exposes a media browser service). */
data class MediaApp(val packageName: String, val label: String, val icon: android.graphics.drawable.Drawable?)

/** What's playing in other apps (Spotify, YouTube Music, …), with transport controls. */
class NowPlayingRepository(private val context: Context) {

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _accessGranted = MutableStateFlow(false)
    val accessGranted: StateFlow<Boolean> = _accessGranted.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val component = ComponentName(context, MusicListenerService::class.java)
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private var listening = false
    private var controllers: List<MediaController> = emptyList()
    private var active: MediaController? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = choose()
        override fun onMetadataChanged(metadata: MediaMetadata?) = choose()
        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) = choose()
        override fun onSessionDestroyed() = refresh()
    }

    fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun accessSettingsIntent(): Intent {
        val intent = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Safe to call repeatedly (e.g. on every resume) to pick up a fresh grant. */
    fun refresh() {
        main.post {
            val granted = hasAccess()
            _accessGranted.value = granted
            if (!granted) {
                detach()
                return@post
            }
            try {
                if (!listening) {
                    manager.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                    listening = true
                }
                attach(manager.getActiveSessions(component))
            } catch (_: SecurityException) {
                _accessGranted.value = false
                detach()
            }
        }
    }

    private fun attach(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = list.filter { it.packageName != context.packageName }
        controllers.forEach { it.registerCallback(controllerCallback, main) }
        choose()
    }

    private fun detach() {
        controllers.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers = emptyList()
        active = null
        _nowPlaying.value = null
    }

    private fun choose() {
        val withMeta = controllers.filter { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
        val pick = withMeta.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: active?.takeIf { it in withMeta }
            ?: withMeta.firstOrNull()
        active = pick
        _nowPlaying.value = pick?.let { c ->
            val md = c.metadata!!
            val ps = c.playbackState
            val actions = ps?.actions ?: 0L
            NowPlaying(
                title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
                artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                appName = appLabel(c.packageName),
                packageName = c.packageName,
                isPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                canSkip = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
                album = md.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                positionMs = ps?.position ?: 0L,
                positionUpdatedAt = ps?.lastPositionUpdateTime ?: 0L,
                speed = ps?.playbackSpeed ?: 1f,
                canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
                queue = c.queue.orEmpty().take(50).map { q ->
                    QueueEntry(q.queueId, q.description.title?.toString() ?: "", q.description.subtitle?.toString())
                },
                activeQueueId = ps?.activeQueueItemId ?: -1L,
            )
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
        pm.getApplicationLabel(info).toString()
    }.getOrDefault(pkg)

    fun playPause() {
        val c = active ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun seekTo(ms: Long) = active?.transportControls?.seekTo(ms)
    fun playQueueItem(id: Long) = active?.transportControls?.skipToQueueItem(id)

    /** Opens the music app itself on the phone. */
    fun openApp(packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { context.startActivity(it) } }
    }

    /** Installed apps that offer car/media playback, for quick switching. */
    fun mediaApps(): List<MediaApp> {
        val pm = context.packageManager
        val intent = Intent("android.media.browse.MediaBrowserService")
        val services = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(intent, 0)
        }
        return services.map { it.serviceInfo.packageName }
            .distinct()
            .filter { it != context.packageName && pm.getLaunchIntentForPackage(it) != null }
            .map { pkg -> MediaApp(pkg, appLabel(pkg), runCatching { pm.getApplicationIcon(pkg) }.getOrNull()) }
            .sortedBy { it.label.lowercase() }
    }

    private val audio = context.getSystemService(android.media.AudioManager::class.java)

    fun volume(): Pair<Int, Int> =
        audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) to audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)

    fun setVolume(level: Int) {
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, level, 0)
    }

    fun play() = active?.transportControls?.play()
    fun pause() = active?.transportControls?.pause()
    fun next() = active?.transportControls?.skipToNext()
    fun previous() = active?.transportControls?.skipToPrevious()
}
