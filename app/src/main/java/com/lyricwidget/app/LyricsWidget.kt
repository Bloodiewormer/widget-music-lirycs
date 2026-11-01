package com.lyricwidget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

/**
 * Widget con la línea actual de la letra, la anterior y las siguientes. No se actualiza por su
 * cuenta (`updatePeriodMillis = 0`): lo redibuja [NowPlaying] solo cuando cambia de línea.
 */
class LyricsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        NowPlaying.init(context)
        updateAll(context, forceFull = true)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        NowPlaying.init(context)
        manager.updateAppWidget(id, build(context, options))
    }

    override fun onEnabled(context: Context) {
        NowPlaying.init(context)
        // El reloj se detiene si no hay widgets; al agregar el primero hay que reactivarlo.
        NowPlaying.changed()
    }

    override fun onReceive(context: Context, intent: Intent) {
        NowPlaying.init(context)
        when (intent.action) {
            ACTION_OFFSET_MINUS -> NowPlaying.adjustOffset(-NowPlaying.OFFSET_STEP_MS)
            ACTION_OFFSET_PLUS -> NowPlaying.adjustOffset(NowPlaying.OFFSET_STEP_MS)
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        private const val ACTION_OFFSET_MINUS = "com.lyricwidget.app.OFFSET_MINUS"
        private const val ACTION_OFFSET_PLUS = "com.lyricwidget.app.OFFSET_PLUS"

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, LyricsWidget::class.java))

        fun hasWidgets(context: Context) = ids(context).isNotEmpty()

        /** Ids de las líneas (anterior, actual, siguiente, después) de cada una de las dos caras del flipper. */
        private val FACES = arrayOf(
            intArrayOf(R.id.prev_a, R.id.current_a, R.id.next_a, R.id.next2_a),
            intArrayOf(R.id.prev_b, R.id.current_b, R.id.next_b, R.id.next2_b),
        )

        /**
         * Cara visible del flipper y lo que muestra. Cuando cambia la línea se escribe en la cara
         * oculta y se pasa a ella, así el lanzador anima la transición. Si solo avanza la barra,
         * se reescribe la misma cara y no hay animación.
         */
        private var face = 0
        private var faceContent: String? = null
        private var lineContent: String? = null
        private var lastFlipAt = 0L
        private var lastProgress = -1

        fun updateAll(context: Context, forceFull: Boolean = false) {
            val currentLine = "${NowPlaying.track?.key}|${NowPlaying.status}|" +
                "${System.identityHashCode(NowPlaying.lyrics)}|${NowPlaying.currentIndex()}"
            val content = "$currentLine|${NowPlaying.track?.label}|" +
                "${NowPlaying.showClock}|${NowPlaying.albumColor}|${NowPlaying.offsetMs()}|${NowPlaying.offsetIsAuto()}"
            val manager = AppWidgetManager.getInstance(context)
            val widgetIds = ids(context)
            if (widgetIds.isEmpty()) return
            val progress = NowPlaying.progress()
            if (!forceFull && content == faceContent) {
                // La barra cambia cada pocos segundos. Actualizar solo esa vista evita reiniciar el
                // ViewFlipper y recalcular el texto en launchers lentos (especialmente Android 8).
                if (progress != lastProgress && progress >= 0 && WidgetTheme.showProgress(context) && !NowPlaying.showClock) {
                    val light = WidgetTheme.background(context).foreground == Color.WHITE
                    val views = RemoteViews(context.packageName, R.layout.widget_lyrics)
                    views.setProgressBar(if (light) R.id.progress_light else R.id.progress_dark, 1000, progress, false)
                    manager.partiallyUpdateAppWidget(widgetIds, views)
                }
                lastProgress = progress
                return
            }
            lastProgress = progress
            if (content != faceContent) {
                faceContent = content
            }
            if (currentLine != lineContent) {
                lineContent = currentLine
                // Android 8 conserva el cambio de cara de v1.2. En equipos nuevos se omite la
                // animación cuando una frase es demasiado corta para terminarla sin corte.
                val now = SystemClock.elapsedRealtime()
                val animation = Device.widgetAnimationMs
                val room = Device.lite(context) ||
                    (NowPlaying.currentLineDurationMs() >= animation * 2 && now - lastFlipAt >= animation + 60)
                if (Device.animationsEnabled && room) {
                    face = 1 - face
                    lastFlipAt = now
                }
            }
            for (id in widgetIds) manager.updateAppWidget(id, build(context, manager.getAppWidgetOptions(id)))
        }

        private fun build(context: Context, options: Bundle?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_lyrics)
            val (prevId, currentId, nextId, next2Id) = FACES[face].toList()
            val style = WidgetTheme.background(context)
            val fg = style.foreground
            views.setInt(R.id.bg, "setColorFilter", WidgetTheme.backgroundColor(context))
            views.setInt(R.id.bg, "setImageAlpha", style.alpha)
            views.setTextColor(R.id.track, withAlpha(fg, 0xB3))
            views.setTextColor(R.id.offset, withAlpha(fg, 0xB3))
            views.setInt(R.id.offset_minus, "setColorFilter", withAlpha(fg, 0xB3))
            views.setInt(R.id.offset_plus, "setColorFilter", withAlpha(fg, 0xB3))
            views.setTextColor(prevId, withAlpha(fg, 0x66))
            views.setTextColor(currentId, WidgetTheme.currentLineColor(context))
            views.setTextColor(nextId, withAlpha(fg, 0x99))
            views.setTextColor(next2Id, withAlpha(fg, 0x66))

            val openApp = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val openLyrics = PendingIntent.getActivity(
                context, 3, Intent(context, LyricsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // Con letra: verla completa. Sin ella: a la app, donde está la búsqueda manual.
            views.setOnClickPendingIntent(R.id.lines, if (NowPlaying.status == NowPlaying.Status.FOUND) openLyrics else openApp)
            views.setOnClickPendingIntent(R.id.track, NowPlaying.playerIntent ?: openApp)
            views.setOnClickPendingIntent(R.id.offset_minus, broadcast(context, ACTION_OFFSET_MINUS, 1))
            views.setOnClickPendingIntent(R.id.offset_plus, broadcast(context, ACTION_OFFSET_PLUS, 2))

            val clock = NowPlaying.showClock && NowPlaying.status != NowPlaying.Status.NO_ACCESS
            views.setViewVisibility(R.id.clock_box, if (clock) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.lyrics_box, if (clock) View.GONE else View.VISIBLE)
            if (clock) {
                views.setTextColor(R.id.clock_time, fg)
                views.setTextColor(R.id.clock_date, withAlpha(fg, 0xB3))
                views.setOnClickPendingIntent(R.id.clock_box, openApp)
                return views
            }

            val progress = NowPlaying.progress()
            val lightBar = fg == Color.WHITE
            views.setProgressBar(if (lightBar) R.id.progress_light else R.id.progress_dark, 1000, progress.coerceAtLeast(0), false)
            if (Device.modern) {
                // Android 12+: la barra toma el color de acento en vez de blanco o gris fijo.
                views.setColorStateList(
                    if (lightBar) R.id.progress_light else R.id.progress_dark, "setProgressTintList",
                    ColorStateList.valueOf(WidgetTheme.currentLineColor(context)),
                )
            }

            val track = NowPlaying.track
            val lyrics = NowPlaying.lyrics
            views.setTextViewText(R.id.track, track?.label ?: context.getString(R.string.app_name))

            val synced = lyrics?.synced == true && NowPlaying.status == NowPlaying.Status.FOUND
            val showOffset = synced && WidgetTheme.showOffset(context)
            val offsetVisibility = if (showOffset) View.VISIBLE else View.GONE
            views.setViewVisibility(R.id.offset_minus, offsetVisibility)
            views.setViewVisibility(R.id.offset, offsetVisibility)
            views.setViewVisibility(R.id.offset_plus, offsetVisibility)
            views.setTextViewText(R.id.offset, formatOffset(NowPlaying.offsetMs(), NowPlaying.offsetIsAuto()))

            val message = when (NowPlaying.status) {
                NowPlaying.Status.NO_ACCESS -> R.string.widget_no_access
                NowPlaying.Status.IDLE -> R.string.widget_idle
                NowPlaying.Status.SEARCHING -> R.string.widget_searching
                NowPlaying.Status.NOT_FOUND -> R.string.widget_not_found
                NowPlaying.Status.UNAVAILABLE -> R.string.widget_unavailable
                NowPlaying.Status.FOUND -> if (lyrics?.instrumental == true) R.string.widget_instrumental else 0
            }
            val index = NowPlaying.currentIndex()
            fun line(i: Int): String = lyrics?.lines?.getOrNull(i)?.text?.ifBlank { Lyrics.PAUSE } ?: ""
            val currentText = if (message != 0 || lyrics == null) {
                context.getString(if (message != 0) message else R.string.widget_not_found)
            } else if (index < 0) Lyrics.PAUSE else line(index)
            val fit = fitLines(context, options, WidgetTheme.textSp(context), currentText,
                WidgetTheme.showTitle(context) || showOffset, WidgetTheme.showProgress(context) && progress >= 0)
            views.setViewVisibility(R.id.header, if (fit.header) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.track, if (WidgetTheme.showTitle(context)) View.VISIBLE else View.INVISIBLE)
            views.setViewVisibility(R.id.progress_light, if (fit.progress && lightBar) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.progress_dark, if (fit.progress && !lightBar) View.VISIBLE else View.GONE)
            val sp = WidgetTheme.textSp(context)
            views.setTextViewTextSize(prevId, TypedValue.COMPLEX_UNIT_SP, sp)
            views.setTextViewTextSize(currentId, TypedValue.COMPLEX_UNIT_SP, fit.currentSp)
            views.setTextViewTextSize(nextId, TypedValue.COMPLEX_UNIT_SP, sp)
            views.setTextViewTextSize(next2Id, TypedValue.COMPLEX_UNIT_SP, sp)
            views.setInt(currentId, "setMaxLines", fit.currentLines)
            views.setTextViewText(currentId, currentText)
            if (message != 0 || lyrics == null) {
                views.setViewVisibility(prevId, View.GONE)
                views.setViewVisibility(nextId, View.GONE)
                views.setViewVisibility(next2Id, View.GONE)
                views.setViewVisibility(currentId, View.VISIBLE)
            } else {
                views.setTextViewText(prevId, line(index - 1))
                views.setTextViewText(nextId, line(index + 1))
                views.setTextViewText(next2Id, line(index + 2))
                views.setViewVisibility(currentId, View.VISIBLE)
                views.setViewVisibility(prevId, if (fit.prev) View.VISIBLE else View.GONE)
                views.setViewVisibility(nextId, if (fit.next) View.VISIBLE else View.GONE)
                views.setViewVisibility(next2Id, if (fit.next2) View.VISIBLE else View.GONE)
                if (!lyrics.synced) {
                    views.setTextViewText(R.id.track, context.getString(R.string.widget_unsynced, track?.label.orEmpty()))
                }
            }
            // Al final: primero se escribe la cara oculta y luego se pasa a ella, animando el cambio.
            views.setDisplayedChild(R.id.lines, face)
            return views
        }


        private class Fit(
            val currentSp: Float, val currentLines: Int,
            val prev: Boolean, val next: Boolean, val next2: Boolean,
            val header: Boolean, val progress: Boolean,
        )

        /** La línea actual ocupa primero las filas que necesita; las vecinas usan el espacio restante. */
        private fun fitLines(
            context: Context, options: Bundle?, sp: Float, text: String,
            wantHeader: Boolean, wantProgress: Boolean,
        ): Fit {
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val legacyHeight = options?.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            ) ?: 0
            val legacyWidth = options?.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            ) ?: 0
            // El menor tamaño que reporta el launcher evita desbordes al rotar o redimensionar.
            @Suppress("DEPRECATION")
            val sizes = if (Device.modern) {
                options?.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            } else null
            val height = sizes?.minOfOrNull { it.height } ?: legacyHeight.takeIf { it > 0 }?.toFloat() ?: 110f
            val width = sizes?.minOfOrNull { it.width } ?: legacyWidth.takeIf { it > 0 }?.toFloat() ?: 250f
            // En widgets muy bajos, cabecera y barra ceden el sitio a la letra.
            val header = wantHeader && height >= 100f
            val progress = wantProgress && height >= 90f
            if (Device.lite(context)) {
                // La v1.2 mantenía el mismo tamaño y distribución al cambiar de línea.
                // Evitar medir cada frase conserva esa transición en Android 8 y poca RAM.
                val scale = context.resources.configuration.fontScale * 1.25f
                val lineHeight = sp * scale
                val currentHeight = sp * 1.3f * scale
                var room = height - 20f - (if (header) 36f else 0f) -
                    (if (progress) 11f else 0f) - currentHeight - 8f
                fun take(cost: Float): Boolean = (room >= cost).also { if (it) room -= cost }
                val contextLines = WidgetTheme.contextLines(context)
                val next = contextLines >= 1 && take(lineHeight)
                val prev = contextLines >= 2 && take(lineHeight)
                val next2 = contextLines >= 3 && take(lineHeight + 2f)
                val currentLines = if (take(currentHeight)) 2 else 1
                return Fit(sp * 1.3f, currentLines, prev, next, next2, header, progress)
            }
            val density = context.resources.displayMetrics.density
            val textWidth = ((width - 32f) * density).toInt().coerceAtLeast(1)
            val available = (height - 20f - (if (header) 36f else 0f) -
                (if (progress) 11f else 0f) - 8f - 4f).coerceAtLeast(0f)

            fun paint(size: Float) = TextPaint().apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, context.resources.displayMetrics)
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            fun rowHeight(size: Float): Float {
                val metrics = paint(size).fontMetrics
                return (metrics.descent - metrics.ascent) / density + 2f
            }
            fun wrappedLines(size: Float): Int {
                val value = text.ifEmpty { " " }
                return StaticLayout.Builder.obtain(value, 0, value.length, paint(size), textWidth)
                    .setIncludePad(false).build().lineCount.coerceAtLeast(1)
            }
            // Reducir un poco el tamaño solo si la frase no cabe completa; nunca reducir las demás.
            val sizesToTry = floatArrayOf(sp * 1.3f, sp * 1.2f, sp * 1.1f, sp, sp * 0.9f)
            val chosen = sizesToTry.firstOrNull { wrappedLines(it) * rowHeight(it) <= available }
                ?: sizesToTry.last()
            val currentRows = wrappedLines(chosen).coerceAtMost(
                (available / rowHeight(chosen)).toInt().coerceAtLeast(1),
            )
            var remaining = available - currentRows * rowHeight(chosen)
            fun take(extra: Float = 0f): Boolean {
                val cost = rowHeight(sp) + extra
                if (remaining < cost) return false
                remaining -= cost
                return true
            }
            val contextLines = WidgetTheme.contextLines(context)
            val next = contextLines >= 1 && take()
            val prev = contextLines >= 2 && take()
            val next2 = contextLines >= 3 && take(2f)
            return Fit(chosen, currentRows, prev, next, next2, header, progress)
        }

        /** "auto" marca un desfase aprendido de tus ajustes anteriores, no uno elegido para esta canción. */
        fun formatOffset(ms: Long, auto: Boolean = false): String {
            val value = if (ms == 0L) "±0 s" else String.format(Locale.ROOT, "%+.1f s", ms / 1000.0)
            return if (auto && ms != 0L) "auto $value" else value
        }

        private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

        private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode, Intent(context, LyricsWidget::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
