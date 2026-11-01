package com.lyricwidget.app

import android.content.Context
import android.graphics.Color
import android.os.Build

/** Apariencia del widget. El fondo es una forma blanca que se tiñe al dibujarla. */
object WidgetTheme {
    private const val KEY_ACCENT = "accent"
    private const val KEY_BACKGROUND = "background"
    private const val KEY_TEXT_SIZE = "text_size"
    private const val KEY_SHOW_OFFSET = "show_offset"
    private const val KEY_SHOW_TITLE = "show_title"
    private const val KEY_SHOW_PROGRESS = "show_progress"
    private const val KEY_CONTEXT = "context_lines"

    val ACCENTS = intArrayOf(
        Color.WHITE,
        0xFFDB2777.toInt(), // rosa
        0xFFEC4899.toInt(), // chicle
        0xFFEF4444.toInt(), // rojo
        0xFFF97316.toInt(), // naranja
        0xFFF59E0B.toInt(), // ámbar
        0xFFEAB308.toInt(), // amarillo
        0xFF84CC16.toInt(), // lima
        0xFF22C55E.toInt(), // verde
        0xFF10B981.toInt(), // esmeralda
        0xFF14B8A6.toInt(), // turquesa
        0xFF06B6D4.toInt(), // cian
        0xFF0EA5E9.toInt(), // celeste
        0xFF3B82F6.toInt(), // azul
        0xFF6366F1.toInt(), // índigo
        0xFF8B5CF6.toInt(), // violeta
        0xFFA855F7.toInt(), // morado
        0xFFD946EF.toInt(), // fucsia
    )

    /**
     * `color = null` significa "tomar el color de la portada del álbum".
     * `system = true`: colores Material You del fondo de pantalla (solo Android 12+).
     */
    class Background(val label: Int, val color: Int?, val alpha: Int, val foreground: Int, val system: Boolean = false)

    // "Sistema" va al final para que los índices guardados de las demás opciones no cambien.
    val BACKGROUNDS: Array<Background> = listOfNotNull(
        Background(R.string.bg_album, null, 235, Color.WHITE),
        Background(R.string.bg_dark, 0xFF202124.toInt(), 215, Color.WHITE),
        Background(R.string.bg_black, Color.BLACK, 240, Color.WHITE),
        Background(R.string.bg_glass, Color.BLACK, 80, Color.WHITE),
        Background(R.string.bg_light, Color.WHITE, 240, 0xFF202124.toInt()),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Background(R.string.bg_system, null, 240, Color.WHITE, system = true) else null,
    ).toTypedArray()

    val TEXT_SIZES = intArrayOf(R.string.size_s, R.string.size_m, R.string.size_l, R.string.size_xl)
    private val TEXT_SP = floatArrayOf(13f, 15f, 18f, 22f)

    fun accent(context: Context): Int = prefs(context).getInt(KEY_ACCENT, ACCENTS[0])

    fun setAccent(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_ACCENT, color).apply()
        LyricsWidget.updateAll(context, forceFull = true)
    }

    fun backgroundIndex(context: Context): Int =
        prefs(context).getInt(KEY_BACKGROUND, 0).coerceIn(0, BACKGROUNDS.lastIndex)

    fun setBackgroundIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_BACKGROUND, index).apply()
        LyricsWidget.updateAll(context, forceFull = true)
    }

    fun textSizeIndex(context: Context): Int =
        prefs(context).getInt(KEY_TEXT_SIZE, 1).coerceIn(0, TEXT_SP.lastIndex)

    fun setTextSizeIndex(context: Context, index: Int) {
        prefs(context).edit().putInt(KEY_TEXT_SIZE, index).apply()
        LyricsWidget.updateAll(context, forceFull = true)
    }

    fun textSp(context: Context): Float = TEXT_SP[textSizeIndex(context)]

    fun showOffset(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_OFFSET, true)
    fun showTitle(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_TITLE, true)
    fun showProgress(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_PROGRESS, true)
    fun contextLines(context: Context): Int = prefs(context).getInt(KEY_CONTEXT, 3).coerceIn(0, 3)

    fun setShowOffset(context: Context, show: Boolean) = setBoolean(context, KEY_SHOW_OFFSET, show)
    fun setShowTitle(context: Context, show: Boolean) = setBoolean(context, KEY_SHOW_TITLE, show)
    fun setShowProgress(context: Context, show: Boolean) = setBoolean(context, KEY_SHOW_PROGRESS, show)

    fun setContextLines(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_CONTEXT, count.coerceIn(0, 3)).apply()
        LyricsWidget.updateAll(context, forceFull = true)
    }

    private fun setBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
        LyricsWidget.updateAll(context, forceFull = true)
    }

    /** Color de fondo efectivo: con "Portada" y sin carátula disponible se usa el oscuro. */
    fun backgroundColor(context: Context): Int {
        val style = BACKGROUNDS[backgroundIndex(context)]
        if (style.system && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getColor(android.R.color.system_accent1_800)
        }
        return style.color ?: NowPlaying.albumColor ?: 0xFF202124.toInt()
    }

    fun background(context: Context): Background = BACKGROUNDS[backgroundIndex(context)]

    /** La línea actual usa el acento salvo que no se lea sobre el fondo (p. ej. blanco sobre claro). */
    fun currentLineColor(context: Context): Int {
        val accent = accent(context)
        val style = background(context)
        val lightBg = style.foreground != Color.WHITE
        return if (lightBg && luminance(accent) > 0.6f) style.foreground else accent
    }

    private fun luminance(color: Int): Float =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f
}
