package com.lyricwidget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
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
        updateAll(context)
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

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            for (id in ids(context)) manager.updateAppWidget(id, build(context, manager.getAppWidgetOptions(id)))
        }

        private fun build(context: Context, options: Bundle?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_lyrics)
            val style = WidgetTheme.background(context)
            val fg = style.foreground
            views.setInt(R.id.bg, "setColorFilter", WidgetTheme.backgroundColor(context))
            views.setInt(R.id.bg, "setImageAlpha", style.alpha)
            views.setTextColor(R.id.track, withAlpha(fg, 0xB3))
            views.setTextColor(R.id.offset, withAlpha(fg, 0xB3))
            views.setInt(R.id.offset_minus, "setColorFilter", withAlpha(fg, 0xB3))
            views.setInt(R.id.offset_plus, "setColorFilter", withAlpha(fg, 0xB3))
            views.setTextColor(R.id.line_prev, withAlpha(fg, 0x66))
            views.setTextColor(R.id.line_current, WidgetTheme.currentLineColor(context))
            views.setTextColor(R.id.line_next, withAlpha(fg, 0x99))
            views.setTextColor(R.id.line_next2, withAlpha(fg, 0x66))

            val sp = WidgetTheme.textSp(context)
            views.setTextViewTextSize(R.id.line_prev, TypedValue.COMPLEX_UNIT_SP, sp)
            views.setTextViewTextSize(R.id.line_current, TypedValue.COMPLEX_UNIT_SP, sp * 1.3f)
            views.setTextViewTextSize(R.id.line_next, TypedValue.COMPLEX_UNIT_SP, sp)
            views.setTextViewTextSize(R.id.line_next2, TypedValue.COMPLEX_UNIT_SP, sp)

            val fit = fitLines(context, options, sp)

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
            views.setViewVisibility(R.id.progress_light, if (progress >= 0 && lightBar) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.progress_dark, if (progress >= 0 && !lightBar) View.VISIBLE else View.GONE)
            views.setProgressBar(if (lightBar) R.id.progress_light else R.id.progress_dark, 1000, progress.coerceAtLeast(0), false)

            val track = NowPlaying.track
            val lyrics = NowPlaying.lyrics
            views.setTextViewText(R.id.track, track?.label ?: context.getString(R.string.app_name))

            val synced = lyrics?.synced == true && NowPlaying.status == NowPlaying.Status.FOUND
            val offsetVisibility = if (synced) View.VISIBLE else View.GONE
            views.setViewVisibility(R.id.offset_minus, offsetVisibility)
            views.setViewVisibility(R.id.offset, offsetVisibility)
            views.setViewVisibility(R.id.offset_plus, offsetVisibility)
            views.setTextViewText(R.id.offset, formatOffset(NowPlaying.offsetMs()))

            val message = when (NowPlaying.status) {
                NowPlaying.Status.NO_ACCESS -> R.string.widget_no_access
                NowPlaying.Status.IDLE -> R.string.widget_idle
                NowPlaying.Status.SEARCHING -> R.string.widget_searching
                NowPlaying.Status.NOT_FOUND -> R.string.widget_not_found
                NowPlaying.Status.UNAVAILABLE -> R.string.widget_unavailable
                NowPlaying.Status.FOUND -> if (lyrics?.instrumental == true) R.string.widget_instrumental else 0
            }
            if (message != 0 || lyrics == null) {
                views.setViewVisibility(R.id.line_prev, View.GONE)
                views.setViewVisibility(R.id.line_next, View.GONE)
                views.setViewVisibility(R.id.line_next2, View.GONE)
                views.setViewVisibility(R.id.line_current, View.VISIBLE)
                views.setTextViewText(R.id.line_current, context.getString(message))
                return views
            }

            val index = NowPlaying.currentIndex()
            fun line(i: Int): String = lyrics.lines.getOrNull(i)?.text?.ifBlank { "♪" } ?: ""
            views.setTextViewText(R.id.line_prev, line(index - 1))
            views.setTextViewText(R.id.line_current, if (index < 0) "♪" else line(index))
            views.setTextViewText(R.id.line_next, line(index + 1))
            views.setTextViewText(R.id.line_next2, line(index + 2))
            views.setViewVisibility(R.id.line_current, View.VISIBLE)
            views.setInt(R.id.line_current, "setMaxLines", fit.currentLines)
            views.setViewVisibility(R.id.line_prev, if (fit.prev) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.line_next, if (fit.next) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.line_next2, if (fit.next2) View.VISIBLE else View.GONE)
            if (!lyrics.synced) {
                views.setTextViewText(R.id.track, context.getString(R.string.widget_unsynced, track?.label.orEmpty()))
            }
            return views
        }

        private class Fit(val currentLines: Int, val prev: Boolean, val next: Boolean, val next2: Boolean)

        /**
         * Decide qué líneas mostrar según la altura real del widget, para que ninguna quede cortada.
         * Prioridad: actual, siguiente, anterior, la de después, y por último una segunda fila para
         * la actual. En horizontal Android reporta la altura en MIN_HEIGHT; en vertical, en MAX_HEIGHT.
         */
        private fun fitLines(context: Context, options: Bundle?, sp: Float): Fit {
            val config = context.resources.configuration
            val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
            val height = options?.getInt(
                if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            ) ?: 0
            if (height <= 0) return Fit(2, prev = true, next = true, next2 = false)

            val scale = config.fontScale * 1.25f // alto de una fila de texto ≈ 1.25 × tamaño de letra
            val line = sp * scale
            val current = sp * 1.3f * scale
            var room = height - 67f - current - 8f // cabecera 36dp + padding 20dp + barra 11dp; márgenes de la actual 8dp
            fun take(size: Float): Boolean = (room >= size).also { if (it) room -= size }
            val next = take(line)
            val prev = take(line)
            val next2 = take(line + 2f)
            val twoRows = take(current)
            return Fit(if (twoRows) 2 else 1, prev, next, next2)
        }

        fun formatOffset(ms: Long): String =
            if (ms == 0L) "±0 s" else String.format(Locale.ROOT, "%+.1f s", ms / 1000.0)

        private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

        private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode, Intent(context, LyricsWidget::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
