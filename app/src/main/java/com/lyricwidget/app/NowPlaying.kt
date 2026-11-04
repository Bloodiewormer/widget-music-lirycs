package com.lyricwidget.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
    private const val LEARN_PREFIX = "learn:"
    const val OFFSET_STEP_MS = 500L
    private const val RETRY_DELAY_MS = 30_000L
    private const val MAX_RETRIES = 3
    private const val PROGRESS_STEP_MS = 5_000L
    private const val PAUSE_TO_CLOCK_MS = 2 * 60_000L

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
    /** Portada reducida, solo en Android 12+. */
    var albumArt: Bitmap? = null
        private set
    var playerIntent: PendingIntent? = null
        private set
    private var controls: MediaController.TransportControls? = null

    var screenOn = true
        set(value) {
            if (field == value) return
            field = value
            // Al encender la pantalla el widget puede estar varias líneas atrasado: redibujar ya.
            if (value) changed() else schedule()
        }

    /** Aumenta con cada canción para descartar descargas que terminan tarde. */
    private var generation = 0
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

    /** Cuánto dura en total la línea actual; muy grande si no hay letra sincronizada. */
    fun currentLineDurationMs(): Long {
        val words = lyrics?.takeIf { it.synced } ?: return Long.MAX_VALUE
        val index = currentIndex()
        val start = words.lines.getOrNull(index)?.timeMs ?: 0L
        val end = words.lines.getOrNull(index + 1)?.timeMs ?: return Long.MAX_VALUE
        return ((end - start) / (state?.playbackSpeed?.takeIf { it > 0f } ?: 1f)).toLong()
    }

    /** Salta en el reproductor al inicio de la línea `index` (solo letras sincronizadas). */
    fun seekToLine(index: Int) {
        val line = lyrics?.takeIf { it.synced }?.lines?.getOrNull(index) ?: return
        controls?.seekTo((line.timeMs - offsetMs()).coerceAtLeast(0))
    }

    // ---- Entradas desde el servicio ----

    fun update(controller: MediaController?) {
        val metadata = controller?.metadata
        if (controller == null || metadata == null) {
            clear()
            return
        }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
        if (title.isNullOrBlank()) {
            clear()
            return
        }
        val next = Track(
            title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION).orEmpty(),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            player = controller.packageName,
        )
        state = controller.playbackState
        playerIntent = controller.sessionActivity
        controls = controller.transportControls
        if (next.key != track?.key) {
            track = next
            val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            albumColor = dominantColor(art)
            // Solo Android 12+ la usa (fondo difuminado); en equipos viejos no se guarda para ahorrar memoria.
            albumArt = if (Device.modern) art?.let { runCatching { Bitmap.createScaledBitmap(it, 160, 160, true) }.getOrNull() } else null
            retries = 0
            refreshAudioContext()
            load(force = false)
        } else {
            // Algunos reproductores publican la duración y la portada un instante después del título.
            if (next.durationMs > 0 && track?.durationMs != next.durationMs) track = next
            if (albumColor == null) {
                val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                albumColor = dominantColor(art)
                if (Device.modern) albumArt = art?.let { runCatching { Bitmap.createScaledBitmap(it, 160, 160, true) }.getOrNull() }
            }
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
        controls = null
        albumColor = null
        albumArt = null
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

    // ---- Desfase: por canción, o aprendido automáticamente ----
    //
    // No se puede "escuchar" la canción para saber si la letra va desfasada: Android no deja
    // capturar el audio de Spotify y similares. Pero el desfase suele ser sistemático (latencia
    // de los audífonos Bluetooth, un reproductor que informa la posición tarde...). Así que cada
    // vez que corriges una canción se guarda como muestra para ese reproductor + salida de audio,
    // y las canciones que nunca ajustaste usan la mediana de esas muestras.

    /** Reproductor + salida de audio actuales, p. ej. "com.spotify.music@bt". */
    private var audioContext = ""
    private var learnedMs: Long? = null

    private fun refreshAudioContext() {
        val audio = app.getSystemService(AudioManager::class.java)
        val bluetooth = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        audioContext = "${track?.player.orEmpty()}@${if (bluetooth) "bt" else "local"}"
        learnedMs = learned()
    }

    /** Mediana de lo que corregiste en este contexto; `null` con menos de 2 canciones ajustadas. */
    private fun learned(): Long? {
        val prefix = "$LEARN_PREFIX$audioContext:"
        val samples = prefs(app).all.filterKeys { it.startsWith(prefix) }.values.mapNotNull { it as? Long }.sorted()
        if (samples.size < 2) return null
        val median = samples[samples.size / 2]
        return (median / 100) * 100 // redondeado a décimas de segundo
    }

    /** Desfase elegido para esta canción si lo hay; si no, el aprendido. */
    fun offsetMs(): Long {
        val current = track ?: return 0
        val own = prefs(app).getLong(OFFSET_PREFIX + current.key, Long.MIN_VALUE)
        return if (own != Long.MIN_VALUE) own else learnedMs ?: 0
    }

    fun offsetIsAuto(): Boolean = track?.let { !prefs(app).contains(OFFSET_PREFIX + it.key) && learnedMs != null } ?: false

    fun adjustOffset(deltaMs: Long) = setOffset(offsetMs() + deltaMs)

    fun setOffset(valueMs: Long) {
        val current = track ?: return
        prefs(app).edit()
            .putLong(OFFSET_PREFIX + current.key, valueMs)
            .putLong("$LEARN_PREFIX$audioContext:${current.key}", valueMs)
            .apply()
        learnedMs = learned()
        changed()
    }

    /** Olvida el ajuste de esta canción: vuelve al desfase aprendido (o a cero). */
    fun resetOffset() {
        val current = track ?: return
        prefs(app).edit()
            .remove(OFFSET_PREFIX + current.key)
            .remove("$LEARN_PREFIX$audioContext:${current.key}")
            .apply()
        learnedMs = learned()
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
        render()
        schedule()
    }

    private fun render() {
        LyricsWidget.updateAll(app)
        listeners.toList().forEach { it() }
    }

    private val tick = Runnable { changed() }

    /**
     * En vez de consultar la posición cada X ms, duerme justo hasta que toque la siguiente línea
     * (o hasta el próximo paso de la barra de avance, cada 5 s como mucho).
     * Sin música, con la pantalla apagada o sin nadie mirando, no programa nada.
     */
    private fun schedule() {
        main.removeCallbacks(tick)
        val current = track ?: return
        if (!screenOn) return
        if (listeners.isEmpty() && !LyricsWidget.hasWidgets(app)) return
        if (!isPlaying) {
            // En pausa: un único aviso para pasar al reloj, no un bucle.
            val wait = PAUSE_TO_CLOCK_MS - pausedForMs()
            if (wait > 0) main.postDelayed(tick, wait)
            return
        }
        val words = lyrics
        val duration = current.durationMs
        if (words == null && duration <= 0) return
        val position = positionMs()
        val speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        val untilLine = words?.msUntilNext(words.indexAt(position, duration), position, duration)
        val wait = minOf(untilLine?.let { (it / speed).toLong() } ?: PROGRESS_STEP_MS, PROGRESS_STEP_MS)
        main.postDelayed(tick, wait.coerceAtLeast(30L))
    }

    private fun pausedForMs(): Long {
        val since = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - since
    }

    /** Sin música, o en pausa desde hace un rato: el widget muestra el reloj. */
    val showClock: Boolean
        get() = track == null || (!isPlaying && pausedForMs() >= PAUSE_TO_CLOCK_MS)

    /** Avance de la canción de 0 a 1000, o -1 si el reproductor no informa la duración. */
    fun progress(): Int {
        val duration = track?.durationMs?.takeIf { it > 0 } ?: return -1
        return ((positionMs() - offsetMs()) * 1000 / duration).toInt().coerceIn(0, 1000)
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
