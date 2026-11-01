package com.lyricwidget.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
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
        findViewById<View>(R.id.root).setBackgroundColor(NowPlaying.albumColor ?: DEFAULT_BG)
        findViewById<TextView>(R.id.lyrics_title).text = track?.title ?: getString(R.string.app_name)
        findViewById<TextView>(R.id.lyrics_artist).text = track?.artist.orEmpty()
        findViewById<TextView>(R.id.lyrics_source).text = when {
            lyrics == null -> ""
            lyrics.synced -> getString(R.string.lyrics_synced, lyrics.source)
            else -> getString(R.string.lyrics_plain, lyrics.source)
        }

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

    private fun showMessage(message: Int) {
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
        shown = lyrics
        shownMessage = 0
        highlighted = -1
        container.removeAllViews()
        lyrics.lines.forEachIndexed { index, line ->
            container.addView(TextView(this).apply {
                text = line.text.ifBlank { "♪" }
                textSize = 22f
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
        (container.getChildAt(highlighted) as? TextView)?.apply {
            setTextColor(LINE_COLOR)
            typeface = Typeface.DEFAULT
        }
        highlighted = index
        val view = container.getChildAt(index) as? TextView ?: return
        view.setTextColor(CURRENT_COLOR)
        view.typeface = Typeface.DEFAULT_BOLD
        if (SystemClock.elapsedRealtime() - userScrolledAt > FOLLOW_PAUSE_MS) {
            view.post { scroll.smoothScrollTo(0, view.top + view.height / 2 - scroll.height / 2) }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val DEFAULT_BG = 0xFF111827.toInt()
        private const val LINE_COLOR = 0x73FFFFFF
        private const val CURRENT_COLOR = 0xFFFFFFFF.toInt()
        private const val FOLLOW_PAUSE_MS = 4_000L
    }
}
