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
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/**
 * Android lo mantiene enlazado mientras el acceso a notificaciones esté concedido. No hace trabajo
 * propio: solo se suscribe a las sesiones multimedia (Spotify, YouTube Music, Deezer...) y pasa los
 * cambios a [NowPlaying]. Sin música no hay reloj corriendo ni red.
 */
class NowPlayingService : NotificationListenerService() {

    private var sessions: MediaSessionManager? = null
    private val controllers = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val lastEventTime = mutableMapOf<MediaSession.Token, Long>()
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
        Players.onChanged = { pick() }
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
        Players.onChanged = null
        controllers.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        controllers.clear()
        lastEventTime.clear()
        active = null
        NowPlaying.connected = false
        NowPlaying.clear()
        NowPlaying.changed()
    }

    /** Se suscribe a las sesiones nuevas, suelta las que desaparecieron y elige cuál mostrar. */
    private fun watch(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in tokens }.toList().forEach { token ->
            controllers.remove(token)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
            lastEventTime.remove(token)
        }
        val now = SystemClock.elapsedRealtime()
        for (controller in list) {
            Players.remember(this, controller.packageName)
            if (controller.sessionToken in controllers) continue
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    lastEventTime[controller.sessionToken] = SystemClock.elapsedRealtime()
                    pick()
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    lastEventTime[controller.sessionToken] = SystemClock.elapsedRealtime()
                    pick()
                }

                override fun onSessionDestroyed() {
                    controllers.remove(controller.sessionToken)
                    lastEventTime.remove(controller.sessionToken)
                    pick()
                }
            }
            controller.registerCallback(callback)
            controllers[controller.sessionToken] = controller to callback
            if (playbackScore(controller) > 1) {
                lastEventTime[controller.sessionToken] = now
            }
        }
        pick()
    }

    private fun playbackScore(controller: MediaController?): Int {
        return when (controller?.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> 3
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING -> 2
            PlaybackState.STATE_PAUSED -> 1
            else -> 0
        }
    }

    private fun hasTitle(controller: MediaController?): Boolean {
        val meta = controller?.metadata ?: return false
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        return !title.isNullOrBlank()
    }

    private fun eventTime(controller: MediaController): Long {
        val now = SystemClock.elapsedRealtime()
        val posTime = controller.playbackState?.lastPositionUpdateTime ?: 0L
        val validPosTime = if (posTime in 1..now + 10_000L) posTime else 0L
        val recordedTime = lastEventTime[controller.sessionToken] ?: 0L
        return maxOf(validPosTime, recordedTime)
    }

    /**
     * Prioriza estrictamente el reproductor que esté sonando en este momento.
     * Si varios o ninguno suenan, elige según título válido, recencia de actividad
     * y el orden de prioridad del sistema Android. Los reproductores ignorados no cuentan.
     */
    private fun pick() {
        val ignored = Players.ignored(this)
        val manager = sessions
        val me = ComponentName(this, NowPlayingService::class.java)
        val systemList = if (manager != null) {
            runCatching { manager.getActiveSessions(me) }.getOrNull()
        } else null

        // Si Android nos da una lista actualizada, nos aseguramos de tener registrados
        // los controladores que aparezcan en ella.
        if (systemList != null) {
            val known = controllers.keys
            if (systemList.any { it.sessionToken !in known }) {
                watch(systemList)
                return
            }
        }

        val all = (systemList ?: controllers.values.map { it.first })
            .filter { it.packageName !in ignored }

        if (all.isEmpty()) {
            active = null
            NowPlaying.update(null)
            return
        }

        // Criterios de selección ordenados por importancia:
        // 1. Está sonando activamente (PLAYING o BUFFERING/CONNECTING > PAUSED > STOPPED).
        // 2. Tiene metadatos con título de canción válido (descarta sesiones mudas de sistema).
        // 3. Nivel de reproducción detallado (PLAYING > BUFFERING > PAUSED > NINGUNO).
        // 4. Marca de tiempo del evento más reciente (el que empezó a sonar o actualizó su estado último).
        // Nota: maxWithOrNull mantiene el primer elemento de 'all' (orden de Android) ante empate total.
        val best = all.maxWithOrNull(
            compareBy<MediaController> { playbackScore(it) > 1 }
                .thenBy { hasTitle(it) }
                .thenBy { playbackScore(it) }
                .thenBy { eventTime(it) }
        )

        active = best ?: all.firstOrNull()
        NowPlaying.update(active)
    }
}
