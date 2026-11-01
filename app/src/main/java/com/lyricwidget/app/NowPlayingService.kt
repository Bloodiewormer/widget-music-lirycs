package com.lyricwidget.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.PowerManager
import android.service.notification.NotificationListenerService

/**
 * Android lo mantiene enlazado mientras el acceso a notificaciones esté concedido. No hace trabajo
 * propio: solo se suscribe a las sesiones multimedia (Spotify, YouTube Music, Deezer...) y pasa los
 * cambios a [NowPlaying]. Sin música no hay reloj corriendo ni red.
 */
class NowPlayingService : NotificationListenerService() {

    private var sessions: MediaSessionManager? = null
    private val controllers = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var active: MediaController? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        watch(list.orEmpty())
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            NowPlaying.screenOn = intent.action != Intent.ACTION_SCREEN_OFF
        }
    }

    override fun onListenerConnected() {
        NowPlaying.init(this)
        NowPlaying.connected = true
        NowPlaying.screenOn = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        val manager = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, NowPlayingService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, me)
            sessions = manager
            watch(manager.getActiveSessions(me))
        } catch (e: SecurityException) {
            // El acceso se revocó justo al conectar; Android volverá a llamar cuando se conceda.
            NowPlaying.connected = false
        }
        NowPlaying.changed()
    }

    override fun onListenerDisconnected() {
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        sessions = null
        runCatching { unregisterReceiver(screen) }
        controllers.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        controllers.clear()
        active = null
        NowPlaying.connected = false
        NowPlaying.clear()
        NowPlaying.changed()
    }

    /** Se suscribe a las sesiones nuevas, suelta las que desaparecieron y elige cuál mostrar. */
    private fun watch(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in tokens }.forEach { token ->
            controllers.remove(token)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
        }
        for (controller in list) {
            if (controller.sessionToken in controllers) continue
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = pick()
                override fun onPlaybackStateChanged(state: PlaybackState?) = pick()
                override fun onSessionDestroyed() = pick()
            }
            controller.registerCallback(callback)
            controllers[controller.sessionToken] = controller to callback
        }
        pick()
    }

    /**
     * Prioridad: la sesión que esté sonando; si ninguna suena, la que se mostraba (pausada);
     * si ya no existe, la más reciente según Android.
     */
    private fun pick() {
        val all = controllers.values.map { it.first }
        val playing = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val current = active?.takeIf { a -> all.any { it.sessionToken == a.sessionToken } }
        active = playing ?: current ?: all.firstOrNull()
        NowPlaying.update(active)
    }
}
