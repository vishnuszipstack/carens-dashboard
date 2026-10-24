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
)

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
            val actions = c.playbackState?.actions ?: 0L
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

    fun play() = active?.transportControls?.play()
    fun pause() = active?.transportControls?.pause()
    fun next() = active?.transportControls?.skipToNext()
    fun previous() = active?.transportControls?.skipToPrevious()
}
