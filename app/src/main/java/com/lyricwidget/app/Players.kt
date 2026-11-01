package com.lyricwidget.app

import android.content.Context

/**
 * Reproductores vistos alguna vez y cuáles ignorar (p. ej. YouTube para videos o una app de
 * podcasts). Se recuerdan los vistos para poder listarlos en la app aunque no estén sonando.
 */
object Players {
    private const val KEY_SEEN = "players_seen"
    private const val KEY_IGNORED = "players_ignored"

    /** Lo pone [NowPlayingService] para volver a elegir sesión cuando cambia la lista. */
    var onChanged: (() -> Unit)? = null

    fun seen(context: Context): Set<String> = prefs(context).getStringSet(KEY_SEEN, emptySet()).orEmpty()

    fun remember(context: Context, packageName: String) {
        val current = seen(context)
        if (packageName in current) return
        prefs(context).edit().putStringSet(KEY_SEEN, current + packageName).apply()
    }

    fun ignored(context: Context): Set<String> = prefs(context).getStringSet(KEY_IGNORED, emptySet()).orEmpty()

    fun isIgnored(context: Context, packageName: String) = packageName in ignored(context)

    fun setIgnored(context: Context, packageName: String, ignore: Boolean) {
        val current = ignored(context)
        // getStringSet devuelve una instancia que no se debe modificar: siempre se guarda una copia.
        prefs(context).edit().putStringSet(KEY_IGNORED, if (ignore) current + packageName else current - packageName).apply()
        onChanged?.invoke()
    }
}
