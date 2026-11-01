package com.lyricwidget.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val background = Executors.newSingleThreadExecutor()
    private val onChange: () -> Unit = { refreshNow() }
    /** Canción con la que se prellenó la búsqueda, para no pisar lo que el usuario escribió. */
    private var prefilledKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NowPlaying.init(this)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.access_app_info).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.access_grant).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.offset_minus).setOnClickListener { NowPlaying.adjustOffset(-NowPlaying.OFFSET_STEP_MS) }
        findViewById<Button>(R.id.offset_plus).setOnClickListener { NowPlaying.adjustOffset(NowPlaying.OFFSET_STEP_MS) }
        findViewById<Button>(R.id.offset_reset).setOnClickListener { NowPlaying.resetOffset() }
        findViewById<Button>(R.id.now_reload).setOnClickListener { NowPlaying.load(force = true) }
        findViewById<Button>(R.id.now_full).setOnClickListener { startActivity(Intent(this, LyricsActivity::class.java)) }
        findViewById<Button>(R.id.search_go).setOnClickListener { search() }
        findViewById<EditText>(R.id.search_title).setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) search()
            action == EditorInfo.IME_ACTION_SEARCH
        }
        findViewById<Button>(R.id.cache_clear).setOnClickListener {
            LyricsRepository.clearCache(this)
            refreshCache()
        }
    }

    override fun onResume() {
        super.onResume()
        NowPlaying.addListener(onChange)
        refreshAccess()
        refreshNow()
        refreshPlayers()
        refreshAppearance()
        refreshCache()
    }

    override fun onPause() {
        super.onPause()
        NowPlaying.removeListener(onChange)
    }

    override fun onDestroy() {
        super.onDestroy()
        background.shutdownNow()
    }

    private fun refreshAccess() {
        val granted = NowPlaying.hasAccess(this)
        findViewById<TextView>(R.id.access_status).setText(if (granted) R.string.access_ok else R.string.access_missing)
        visible(R.id.access_grant, !granted)
        visible(R.id.access_restricted, !granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    }

    private fun refreshNow() {
        val track = NowPlaying.track
        val lyrics = NowPlaying.lyrics
        val title = findViewById<TextView>(R.id.now_title)
        val meta = findViewById<TextView>(R.id.now_meta)
        val line = findViewById<TextView>(R.id.now_line)
        val status = findViewById<TextView>(R.id.now_lyrics)

        visible(R.id.now_meta, track != null)
        visible(R.id.now_line, track != null && lyrics != null && !lyrics.instrumental)
        visible(R.id.now_lyrics, track != null)
        visible(R.id.now_reload, track != null)
        visible(R.id.now_full, track != null && lyrics != null && !lyrics.instrumental)
        visible(R.id.offset_box, track != null && lyrics?.synced == true)
        if (track == null) {
            title.setText(R.string.now_idle)
            return
        }

        title.text = track.label
        val player = getString(R.string.now_player, appName(track.player))
        meta.text = if (NowPlaying.isPlaying) player else "$player · ${getString(R.string.now_paused)}"
        status.text = when {
            NowPlaying.status == NowPlaying.Status.SEARCHING -> getString(R.string.lyrics_searching)
            NowPlaying.status == NowPlaying.Status.UNAVAILABLE -> getString(R.string.lyrics_unavailable)
            lyrics == null -> getString(R.string.lyrics_not_found)
            lyrics.instrumental -> getString(R.string.lyrics_instrumental, lyrics.source)
            lyrics.synced -> getString(R.string.lyrics_synced, lyrics.source)
            else -> getString(R.string.lyrics_plain, lyrics.source)
        }
        if (lyrics != null) {
            val index = NowPlaying.currentIndex()
            line.text = lyrics.lines.getOrNull(index)?.text?.ifBlank { Lyrics.PAUSE } ?: Lyrics.PAUSE
        }
        findViewById<TextView>(R.id.offset_value).text = LyricsWidget.formatOffset(NowPlaying.offsetMs(), NowPlaying.offsetIsAuto())

        // Prellenar la búsqueda con la canción nueva (ya limpia de "Remastered", "Official Video"...).
        if (prefilledKey != track.key) {
            prefilledKey = track.key
            findViewById<EditText>(R.id.search_artist).setText(track.cleanArtist)
            findViewById<EditText>(R.id.search_title).setText(track.cleanTitle)
        }
    }

    private fun search() {
        val artist = findViewById<EditText>(R.id.search_artist).text.toString().trim()
        val title = findViewById<EditText>(R.id.search_title).text.toString().trim()
        if (title.isEmpty()) return
        val status = findViewById<TextView>(R.id.search_status)
        val results = findViewById<LinearLayout>(R.id.search_results)
        results.removeAllViews()
        status.visibility = View.VISIBLE
        status.setText(R.string.search_running)
        val duration = NowPlaying.track?.durationMs ?: 0
        background.execute {
            val found = try {
                LyricsRepository.search(artist, title, duration)
            } catch (e: LyricsRepository.Unavailable) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                showResults(found)
            }
        }
    }

    /** `found = null` significa que el servidor no respondió (distinto de "sin resultados"). */
    private fun showResults(found: List<LyricsRepository.Result>?) {
        val status = findViewById<TextView>(R.id.search_status)
        val results = findViewById<LinearLayout>(R.id.search_results)
        results.removeAllViews()
        when {
            found == null -> status.setText(R.string.search_unavailable)
            found.isEmpty() -> status.setText(R.string.search_empty)
            NowPlaying.track == null -> status.setText(R.string.search_pick_disabled)
            else -> status.visibility = View.GONE
        }
        for (result in found.orEmpty().take(15)) {
            val tag = getString(
                when {
                    result.instrumental -> R.string.search_tag_instrumental
                    result.synced != null -> R.string.search_tag_synced
                    else -> R.string.search_tag_plain
                },
            )
            val duration = String.format(Locale.ROOT, "%d:%02d", result.durationSec / 60, result.durationSec % 60)
            val details = listOf(result.artist, result.album, duration, tag).filter { it.isNotBlank() }.joinToString(" · ")
            results.addView(TextView(this).apply {
                text = "${result.title}\n$details"
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                background = getDrawable(R.drawable.metric_bg)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
                isEnabled = NowPlaying.track != null
                setOnClickListener {
                    NowPlaying.choose(result)
                    results.removeAllViews()
                    Toast.makeText(this@MainActivity, R.string.search_chosen, Toast.LENGTH_SHORT).show()
                    refreshCache()
                }
            })
        }
    }

    /** Una casilla por reproductor visto; marcada = se muestran sus canciones. */
    private fun refreshPlayers() {
        val container = findViewById<LinearLayout>(R.id.players)
        container.removeAllViews()
        val seen = Players.seen(this).map { it to appName(it) }.sortedBy { it.second.lowercase(Locale.ROOT) }
        if (seen.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.players_empty)
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, 0)
            })
            return
        }
        val ignored = Players.ignored(this)
        for ((packageName, name) in seen) {
            container.addView(CheckBox(this).apply {
                text = name
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                minHeight = dp(44)
                isChecked = packageName !in ignored
                setOnCheckedChangeListener { _, checked -> Players.setIgnored(this@MainActivity, packageName, !checked) }
            })
        }
    }

    private fun refreshAppearance() {
        options(
            R.id.bg_options,
            WidgetTheme.BACKGROUNDS.map { it.label }.toIntArray(),
            WidgetTheme.backgroundIndex(this),
        ) {
            WidgetTheme.setBackgroundIndex(this, it)
            refreshAppearance()
        }
        val accents = WidgetTheme.ACCENTS
        swatches(R.id.accent, accents.map { intArrayOf(it) }, accents.indexOf(WidgetTheme.accent(this))) {
            WidgetTheme.setAccent(this, accents[it])
            refreshAppearance()
        }
        options(R.id.size_options, WidgetTheme.TEXT_SIZES, WidgetTheme.textSizeIndex(this)) {
            WidgetTheme.setTextSizeIndex(this, it)
            refreshAppearance()
        }
        options(
            R.id.context_options,
            intArrayOf(R.string.context_0, R.string.context_1, R.string.context_2, R.string.context_3),
            WidgetTheme.contextLines(this),
        ) {
            WidgetTheme.setContextLines(this, it)
            refreshAppearance()
        }
        appearanceToggle(R.id.show_offset, WidgetTheme.showOffset(this), WidgetTheme::setShowOffset)
        appearanceToggle(R.id.show_title, WidgetTheme.showTitle(this), WidgetTheme::setShowTitle)
        appearanceToggle(R.id.show_progress, WidgetTheme.showProgress(this), WidgetTheme::setShowProgress)
    }

    private fun appearanceToggle(id: Int, checked: Boolean, save: (android.content.Context, Boolean) -> Unit) {
        findViewById<CheckBox>(id).apply {
            setOnCheckedChangeListener(null)
            isChecked = checked
            setOnCheckedChangeListener { _, value -> save(this@MainActivity, value) }
        }
    }

    private fun refreshCache() {
        // Contar archivos es rápido, pero se hace fuera del hilo principal por si la caché crece mucho.
        background.execute {
            val count = LyricsRepository.cacheCount(this)
            val size = Formatter.formatShortFileSize(this, LyricsRepository.cacheBytes(this))
            runOnUiThread {
                if (!isDestroyed) findViewById<TextView>(R.id.cache_info).text = getString(R.string.cache_info, count, size)
            }
        }
    }

    private fun appName(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun visible(id: Int, show: Boolean) {
        findViewById<View>(id).visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Llena una fila con píldoras excluyentes; `selected` = -1 si el valor actual no está en la lista. */
    private fun options(id: Int, labels: IntArray, selected: Int, onPick: (Int) -> Unit) {
        val row = findViewById<LinearLayout>(id)
        row.removeAllViews()
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            row.addView(TextView(this).apply {
                setText(label)
                textSize = 14f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isSelected) getColor(R.color.brand_dark) else getColor(R.color.text_primary))
                background = getDrawable(if (isSelected) R.drawable.chip_on else R.drawable.chip_off)
                gravity = Gravity.CENTER
                minHeight = dp(40)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
                setOnClickListener { onPick(index) }
            })
        }
    }

    /** Llena el contenedor con círculos de color repartidos en filas. El seleccionado lleva un borde. */
    private fun swatches(id: Int, options: List<IntArray>, selected: Int, onPick: (Int) -> Unit) {
        val container = findViewById<LinearLayout>(id)
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        val size = dp(40)
        val gap = dp(10)
        // 16dp de margen exterior + 20dp de padding interno a cada lado = 72dp
        val perRow = ((resources.displayMetrics.widthPixels - dp(72)) / (size + gap)).coerceAtLeast(1)

        options.chunked(perRow).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { if (rowIndex > 0) topMargin = gap }
            }
            chunk.forEachIndexed { i, colors ->
                val index = rowIndex * perRow + i
                val isSelected = index == selected
                row.addView(View(this).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, swatchColors(colors)).apply {
                        shape = GradientDrawable.OVAL
                        setStroke(
                            if (isSelected) dp(3) else dp(1),
                            if (isSelected) getColor(R.color.text_primary) else getColor(R.color.border_strong),
                        )
                    }
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
                    setOnClickListener { onPick(index) }
                })
            }
            container.addView(row)
        }
    }

    /** GradientDrawable necesita al menos dos colores; un sólido se repite. */
    private fun swatchColors(colors: IntArray) = if (colors.size == 1) intArrayOf(colors[0], colors[0]) else colors

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
