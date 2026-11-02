package com.lyricwidget.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Letra completa de la canción actual, con la línea que suena resaltada y centrada.
 * Tocar una línea sincronizada salta a ese momento en el reproductor.
 */
class LyricsActivity : Activity() {

    private lateinit var scroll: ScrollView
    private lateinit var container: LinearLayout
    private val onChange: () -> Unit = { refresh() }

    /** Letra dibujada ahora; si cambia (otra canción o búsqueda manual) se reconstruye la lista. */
    private var shown: Lyrics? = null
    private var shownMessage = 0
    private var highlighted = -1
    /** Tras desplazar a mano se deja de seguir la canción unos segundos. */
    private var userScrolledAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NowPlaying.init(this)
        setContentView(R.layout.activity_lyrics)
        scroll = findViewById(R.id.lyrics_scroll)
        container = findViewById(R.id.lyrics_lines)
        findViewById<Button>(R.id.lyrics_settings).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.lyrics_offset_minus).setOnClickListener { NowPlaying.adjustOffset(-NowPlaying.OFFSET_STEP_MS) }
        findViewById<Button>(R.id.lyrics_offset_plus).setOnClickListener { NowPlaying.adjustOffset(NowPlaying.OFFSET_STEP_MS) }
        findViewById<Button>(R.id.lyrics_smaller).setOnClickListener { resize(-TEXT_STEP_SP) }
        findViewById<Button>(R.id.lyrics_bigger).setOnClickListener { resize(TEXT_STEP_SP) }
        scroll.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE) userScrolledAt = SystemClock.elapsedRealtime()
            false
        }
        // Margen arriba y abajo de media pantalla para poder centrar la primera y la última línea.
        scroll.post {
            val half = scroll.height / 2
            container.setPadding(container.paddingLeft, half, container.paddingRight, half)
            highlighted = -1
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        NowPlaying.addListener(onChange)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        NowPlaying.removeListener(onChange)
    }

    private fun refresh() {
        val track = NowPlaying.track
        val lyrics = NowPlaying.lyrics
        applyBackdrop()
        findViewById<TextView>(R.id.lyrics_title).text = track?.title ?: getString(R.string.app_name)
        findViewById<TextView>(R.id.lyrics_artist).text = track?.artist.orEmpty()
        findViewById<TextView>(R.id.lyrics_source).text = when {
            lyrics == null -> ""
            lyrics.synced -> getString(R.string.lyrics_synced, lyrics.source)
            else -> getString(R.string.lyrics_plain, lyrics.source)
        }
        findViewById<View>(R.id.lyrics_offset_box).visibility =
            if (lyrics?.synced == true && NowPlaying.status == NowPlaying.Status.FOUND) View.VISIBLE else View.INVISIBLE
        findViewById<TextView>(R.id.lyrics_offset).text = LyricsWidget.formatOffset(NowPlaying.offsetMs(), NowPlaying.offsetIsAuto())

        // Modo karaoke: la pantalla no se apaga mientras suena música con esta vista abierta.
        if (NowPlaying.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val message = when (NowPlaying.status) {
            NowPlaying.Status.NO_ACCESS -> R.string.widget_no_access
            NowPlaying.Status.IDLE -> R.string.now_idle
            NowPlaying.Status.SEARCHING -> R.string.lyrics_searching
            NowPlaying.Status.NOT_FOUND -> R.string.lyrics_not_found
            NowPlaying.Status.UNAVAILABLE -> R.string.lyrics_unavailable
            NowPlaying.Status.FOUND -> if (lyrics?.instrumental == true) R.string.widget_instrumental else 0
        }
        if (message != 0 || lyrics == null) {
            if (shownMessage != message) showMessage(message)
            return
        }
        if (shown !== lyrics) build(lyrics)
        highlight(NowPlaying.currentIndex())
    }

    private var backdropArt: Bitmap? = null

    /**
     * Android 12+: la portada difuminada con el blur nativo del sistema (RenderEffect).
     * En versiones anteriores, el color promedio de la portada, que no cuesta nada dibujar.
     */
    private fun applyBackdrop() {
        findViewById<View>(R.id.root).setBackgroundColor(NowPlaying.albumColor ?: DEFAULT_BG)
        val image = findViewById<ImageView>(R.id.lyrics_backdrop)
        val scrim = findViewById<View>(R.id.lyrics_scrim)
        val art = NowPlaying.albumArt
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || art == null) {
            image.visibility = View.GONE
            scrim.visibility = View.GONE
            return
        }
        image.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        if (art === backdropArt) return
        backdropArt = art
        image.setImageBitmap(art)
        image.setRenderEffect(RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP))
    }

    private fun showMessage(message: Int) {
        colorAnimators.values.forEach { it.cancel() }
        colorAnimators.clear()
        shown = null
        shownMessage = message
        highlighted = -1
        container.removeAllViews()
        container.addView(TextView(this).apply {
            setText(message)
            textSize = 18f
            setTextColor(LINE_COLOR)
            // Los mensajes de "no encontrada" llevan a la búsqueda manual.
            setOnClickListener { startActivity(Intent(this@LyricsActivity, MainActivity::class.java)) }
        })
    }

    private fun build(lyrics: Lyrics) {
        colorAnimators.values.forEach { it.cancel() }
        colorAnimators.clear()
        shown = lyrics
        shownMessage = 0
        highlighted = -1
        container.removeAllViews()
        lyrics.lines.forEachIndexed { index, line ->
            container.addView(TextView(this).apply {
                text = line.text.ifBlank { Lyrics.PAUSE }
                textSize = textSp()
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(LINE_COLOR)
                setPadding(0, dp(8), 0, dp(8))
                if (lyrics.synced) {
                    setOnClickListener {
                        userScrolledAt = 0
                        NowPlaying.seekToLine(index)
                    }
                }
            })
        }
    }

    private fun highlight(index: Int) {
        if (index == highlighted) return
        // La animación nunca dura más que la mitad de la línea: en las partes rápidas se acorta
        // y, si no hay tiempo, el cambio es instantáneo en vez de quedar cortado a la mitad.
        val budget = NowPlaying.currentLineDurationMs() / 2
        val duration = if (!Device.animationsEnabled || budget < MIN_ANIMATION_MS) 0L
        else minOf(Device.lineAnimationMs(this), budget)

        (container.getChildAt(highlighted) as? TextView)?.let { animateLine(it, current = false, duration) }
        highlighted = index
        val view = container.getChildAt(index) as? TextView ?: return
        animateLine(view, current = true, duration)
        if (SystemClock.elapsedRealtime() - userScrolledAt > FOLLOW_PAUSE_MS) {
            view.post {
                val target = (view.top + view.height / 2 - scroll.height / 2).coerceAtLeast(0)
                scrollAnimator?.cancel()
                if (duration == 0L) {
                    scroll.scrollTo(0, target)
                    return@post
                }
                scrollAnimator = ObjectAnimator.ofInt(scroll, "scrollY", scroll.scrollY, target).apply {
                    this.duration = duration
                    interpolator = DecelerateInterpolator(1.6f)
                    start()
                }
            }
        }
    }

    private var scrollAnimator: ObjectAnimator? = null
    private val colorAnimators = HashMap<View, ValueAnimator>()

    /**
     * La línea que entra se ilumina de forma nítida; la que sale vuelve a su estado atenuado.
     * Al mantener la escala uniforme (1.0x) el texto se lee perfectamente sin saltos ni pixelado.
     */
    private fun animateLine(view: TextView, current: Boolean, duration: Long) {
        val to = if (current) CURRENT_COLOR else LINE_COLOR
        colorAnimators.remove(view)?.cancel()
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        if (duration == 0L) {
            view.setTextColor(to)
            return
        }
        colorAnimators[view] = ValueAnimator.ofArgb(view.currentTextColor, to).apply {
            this.duration = duration
            addUpdateListener { view.setTextColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun textSp(): Float = prefs(this).getFloat(KEY_TEXT_SP, DEFAULT_TEXT_SP)

    /** Cambia el tamaño de todas las líneas y vuelve a centrar la actual. */
    private fun resize(deltaSp: Float) {
        val size = (textSp() + deltaSp).coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
        prefs(this).edit().putFloat(KEY_TEXT_SP, size).apply()
        if (shown == null) return
        for (i in 0 until container.childCount) (container.getChildAt(i) as TextView).textSize = size
        val current = highlighted
        highlighted = -1
        userScrolledAt = 0
        // Esperar a que se vuelva a medir el texto antes de centrar la línea.
        container.postDelayed({ highlight(current) }, 100)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_TEXT_SP = "lyrics_text_sp"
        private const val DEFAULT_TEXT_SP = 22f
        private const val MIN_TEXT_SP = 14f
        private const val MAX_TEXT_SP = 40f
        private const val TEXT_STEP_SP = 2f
        private const val DEFAULT_BG = 0xFF111827.toInt()
        private const val LINE_COLOR = 0x73FFFFFF
        private const val CURRENT_COLOR = 0xFFFFFFFF.toInt()
        private const val FOLLOW_PAUSE_MS = 4_000L
        private const val MIN_ANIMATION_MS = 90L
        private const val BLUR_RADIUS = 90f
    }
}
