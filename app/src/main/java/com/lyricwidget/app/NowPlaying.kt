package com.lyricwidget.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.Executors

fun prefs(context: Context) = context.getSharedPreferences("lyricwidget", Context.MODE_PRIVATE)

/**
 * Estado compartido de lo que suena: canción, letra y posición. Lo alimenta [NowPlayingService]
 * y lo leen el widget y la app. Todo ocurre en el hilo principal salvo la descarga de letras.
 */
object NowPlaying {
    enum class Status { NO_ACCESS, IDLE, SEARCHING, FOUND, NOT_FOUND, UNAVAILABLE }

    private const val OFFSET_PREFIX = "offset:"
    const val OFFSET_STEP_MS = 500L
    private const val RETRY_DELAY_MS = 30_000L
    private const val MAX_RETRIES = 3

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<() -> Unit>()

    var connected = false
    var track: Track? = null
        private set
    var lyrics: Lyrics? = null
        private set
    var state: PlaybackState? = null
        private set
    var albumColor: Int? = null
        private set
    var playerIntent: PendingIntent? = null
        private set
    var screenOn = true
        set(value) {
            field = value
            schedule()
        }

    /** Aumenta con cada canción para descartar descargas que terminan tarde. */
    private var generation = 0
    private var lastIndex = Int.MIN_VALUE
    private var searching = false
    private var unavailable = false
    private var retries = 0

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    val status: Status
        get() = when {
            !connected && !hasAccess(app) -> Status.NO_ACCESS
            track == null -> Status.IDLE
            searching -> Status.SEARCHING
            lyrics == null && unavailable -> Status.UNAVAILABLE
            lyrics == null -> Status.NOT_FOUND
            else -> Status.FOUND
        }

    val isPlaying: Boolean get() = state?.state == PlaybackState.STATE_PLAYING

    fun hasAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, NowPlayingService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Posición actual extrapolada desde el último reporte del reproductor, más el desfase elegido. */
    fun positionMs(): Long {
        val s = state ?: return 0
        var position = s.position
        if (s.state == PlaybackState.STATE_PLAYING) {
            position += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        return position + offsetMs()
    }

    fun currentIndex(): Int = lyrics?.indexAt(positionMs(), track?.durationMs ?: 0) ?: -1

    // ---- Entradas desde el servicio ----

    fun update(controller: MediaController?) {
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (controller == null || title.isNullOrBlank()) {
            clear()
            return
        }
        val next = Track(
            title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            player = controller.packageName,
        )
        state = controller.playbackState
        playerIntent = controller.sessionActivity
        if (next.key != track?.key) {
            track = next
            albumColor = dominantColor(
                metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART),
            )
            retries = 0
            load(force = false)
        } else if (next.durationMs > 0 && track?.durationMs != next.durationMs) {
            // Algunos reproductores publican la duración un instante después del título.
            track = next
        }
        changed()
    }

    fun clear() {
        if (track == null && state == null) return
        generation++
        track = null
        lyrics = null
        state = null
        playerIntent = null
        albumColor = null
        searching = false
        unavailable = false
        main.removeCallbacks(retry)
        changed()
    }

    // ---- Letras ----

    /** Descarga (o lee de la caché) la letra de la canción actual. `force` ignora la caché. */
    fun load(force: Boolean) {
        val current = track ?: return
        val id = ++generation
        main.removeCallbacks(retry)
        lyrics = null
        searching = true
        unavailable = false
        changed()
        network.execute {
            if (force) LyricsRepository.forget(app, current)
            var failed = false
            val found = try {
                LyricsRepository.load(app, current)
            } catch (e: LyricsRepository.Unavailable) {
                failed = true
                null
            }
            main.post {
                if (id != generation) return@post
                lyrics = found
                searching = false
                unavailable = failed
                // Servidor caído o sin internet: se reintenta solo, unas pocas veces por canción.
                if (failed && retries < MAX_RETRIES) {
                    retries++
                    main.postDelayed(retry, RETRY_DELAY_MS)
                }
                changed()
            }
        }
    }

    private val retry = Runnable { if (track != null && lyrics == null) load(force = false) }

    /** Usa un resultado elegido a mano en la búsqueda manual para la canción actual. */
    fun choose(result: LyricsRepository.Result) {
        val current = track ?: return
        generation++
        main.removeCallbacks(retry)
        searching = false
        unavailable = false
        lyrics = result.toLyrics()
        network.execute { LyricsRepository.save(app, current, result) }
        changed()
    }

    // ---- Desfase por canción ----

    fun offsetMs(): Long = track?.let { prefs(app).getLong(OFFSET_PREFIX + it.key, 0) } ?: 0

    fun adjustOffset(deltaMs: Long) = setOffset(offsetMs() + deltaMs)

    fun setOffset(valueMs: Long) {
        val current = track ?: return
        prefs(app).edit().apply {
            if (valueMs == 0L) remove(OFFSET_PREFIX + current.key) else putLong(OFFSET_PREFIX + current.key, valueMs)
        }.apply()
        changed()
    }

    // ---- Observadores y reloj ----

    fun addListener(listener: () -> Unit) {
        listeners += listener
        schedule()
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Redibuja todo y reprograma el reloj de líneas. */
    fun changed() {
        lastIndex = Int.MIN_VALUE
        render()
        schedule()
    }

    private fun render() {
        LyricsWidget.updateAll(app)
        listeners.toList().forEach { it() }
    }

    private val tick = Runnable { schedule() }

    /**
     * En vez de consultar la posición cada X ms, duerme justo hasta que toque la siguiente línea.
     * Sin música, con la pantalla apagada o sin nadie mirando, no programa nada.
     */
    private fun schedule() {
        main.removeCallbacks(tick)
        val words = lyrics ?: return
        if (!isPlaying || !screenOn) return
        if (listeners.isEmpty() && !LyricsWidget.hasWidgets(app)) return
        val duration = track?.durationMs ?: 0
        val position = positionMs()
        val index = words.indexAt(position, duration)
        if (index != lastIndex) {
            lastIndex = index
            render()
        }
        val wait = words.msUntilNext(index, position, duration) ?: return
        val speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        main.postDelayed(tick, (wait / speed).toLong().coerceIn(30L, 10_000L))
    }

    /** Color promedio de la portada, oscurecido para que el texto blanco se lea encima. */
    private fun dominantColor(art: Bitmap?): Int? {
        art ?: return null
        val pixel = runCatching { Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0) }.getOrNull() ?: return null
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel, hsv)
        hsv[1] = (hsv[1] * 1.2f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceIn(0.18f, 0.38f)
        return Color.HSVToColor(hsv)
    }
}
