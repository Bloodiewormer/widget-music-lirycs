package com.lyricwidget.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Busca letras en APIs públicas y gratuitas, sin clave y sin backend propio:
 *  1. LRCLIB (lrclib.net): letras sincronizadas en formato LRC.
 *  2. lyrics.ovh: solo texto, como respaldo.
 * Lo encontrado se guarda en disco, así cada canción se descarga una sola vez y queda offline.
 * Todas las funciones de red bloquean: llamarlas fuera del hilo principal.
 */
object LyricsRepository {
    private const val USER_AGENT = "LyricWidget/1.0 (https://github.com/Bloodiewormer/widget-music-lirycs)"
    private const val LRCLIB = "https://lrclib.net/api"
    private const val LYRICS_OVH = "https://api.lyrics.ovh/v1"

    /** Resultado de LRCLIB; también se usa para la búsqueda manual. */
    class Result(
        val title: String,
        val artist: String,
        val album: String,
        val durationSec: Int,
        val synced: String?,
        val plain: String?,
        val instrumental: Boolean,
        val source: String = "LRCLIB",
    ) {
        fun toLyrics(): Lyrics? = when {
            instrumental -> Lyrics.instrumental(source)
            synced != null -> Lyrics.fromLrc(synced, source)
            plain != null -> Lyrics.fromPlain(plain, source)
            else -> null
        }

        fun toJson(): JSONObject = JSONObject()
            .put("title", title).put("artist", artist).put("album", album).put("duration", durationSec)
            .put("synced", synced).put("plain", plain).put("instrumental", instrumental).put("source", source)
    }

    /** El servidor no respondió (caído, saturado o sin internet). Distinto de "esa letra no existe". */
    class Unavailable : IOException()

    /** @throws Unavailable si no se encontró nada y además algún servidor falló: vale la pena reintentar. */
    fun load(context: Context, track: Track): Lyrics? {
        cached(context, track)?.let { return it }
        var failure: Unavailable? = null
        fun attempt(block: () -> Result?): Result? = try {
            block()
        } catch (e: Unavailable) {
            failure = e
            null
        }
        // /get también responde 503 cuando la canción no existe, así que su fallo no cuenta como caída.
        val result = runCatching { lrclibExact(track) }.getOrNull()
            ?: attempt { search(track.cleanArtist, track.cleanTitle, track.durationMs).firstOrNull() }
            ?: attempt { lyricsOvh(track) }
        if (result == null) {
            failure?.let { throw it }
            return null
        }
        save(context, track, result)
        return result.toLyrics()
    }

    /**
     * Busca en LRCLIB y ordena: primero sincronizadas, luego por cercanía a la duración real.
     * Cuando LRCLIB está saturado responde 503 a las consultas compuestas pero no a las simples,
     * así que se prueban de más precisa a más simple y se filtra por artista aquí.
     * @throws Unavailable si ninguna consulta obtuvo respuesta del servidor.
     */
    fun search(artist: String, title: String, durationMs: Long): List<Result> {
        val queries = buildList {
            if (artist.isNotBlank()) add("track_name=${encode(title)}&artist_name=${encode(artist)}")
            add("q=${encode("$artist $title".trim())}")
            add("track_name=${encode(title)}")
        }.distinct()
        var failure: Unavailable? = null
        for (query in queries) {
            val body = try {
                get("$LRCLIB/search?$query")
            } catch (e: Unavailable) {
                failure = e
                continue
            } ?: continue
            val results = rank(body, artist, durationMs)
            if (results.isNotEmpty()) return results
        }
        failure?.let { throw it }
        return emptyList()
    }

    private fun rank(body: String, artist: String, durationMs: Long): List<Result> {
        val array = try { JSONArray(body) } catch (e: Exception) { return emptyList() }
        val wanted = Track.normalize(artist)
        val target = (durationMs / 1000).toInt()
        val results = (0 until array.length()).mapNotNull { parse(array.optJSONObject(it)) }
            .filter { it.synced != null || it.plain != null || it.instrumental }
            .filter { wanted.isEmpty() || Track.normalize(it.artist).let { a -> a.contains(wanted) || wanted.contains(a) } }
        return results.sortedBy { (if (it.synced != null) 0 else 1_000) + if (target > 0) abs(it.durationSec - target) else 0 }
            .filter { target <= 0 || abs(it.durationSec - target) <= 15 }
            .ifEmpty { results }
    }

    /** Coincidencia exacta con los datos originales del reproductor (lo más fiable cuando existe). */
    private fun lrclibExact(track: Track): Result? {
        if (track.durationMs <= 0) return null
        val url = "$LRCLIB/get?artist_name=${encode(track.artist)}&track_name=${encode(track.title)}" +
            "&album_name=${encode(track.album)}&duration=${track.durationMs / 1000}"
        return parse(get(url, attempts = 1)?.let { runCatching { JSONObject(it) }.getOrNull() })
    }

    private fun lyricsOvh(track: Track): Result? {
        if (track.cleanArtist.isBlank()) return null
        val body = get("$LYRICS_OVH/${encodePath(track.cleanArtist)}/${encodePath(track.cleanTitle)}") ?: return null
        val text = runCatching { JSONObject(body).optString("lyrics") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return Result(track.cleanTitle, track.cleanArtist, track.album, (track.durationMs / 1000).toInt(), null, text, false, "lyrics.ovh")
    }

    private fun parse(json: JSONObject?): Result? {
        json ?: return null
        return Result(
            title = json.optString("trackName", json.optString("title")),
            artist = json.optString("artistName", json.optString("artist")),
            album = json.optString("albumName", json.optString("album")),
            durationSec = json.optDouble("duration", 0.0).toInt(),
            synced = json.optStringOrNull("syncedLyrics") ?: json.optStringOrNull("synced"),
            plain = json.optStringOrNull("plainLyrics") ?: json.optStringOrNull("plain"),
            instrumental = json.optBoolean("instrumental", false),
            source = json.optString("source", "LRCLIB"),
        )
    }

    // ---- Caché en disco ----

    private fun dir(context: Context) = File(context.filesDir, "lyrics").apply { mkdirs() }

    private fun file(context: Context, track: Track): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(track.key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir(context), "$hash.json")
    }

    private fun cached(context: Context, track: Track): Lyrics? {
        val file = file(context, track)
        if (!file.exists()) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        return parse(json)?.toLyrics()
    }

    /** Guarda un resultado (automático o elegido a mano) como la letra de `track`. */
    fun save(context: Context, track: Track, result: Result) {
        runCatching { file(context, track).writeText(result.toJson().toString()) }
    }

    fun forget(context: Context, track: Track) {
        file(context, track).delete()
    }

    fun cacheCount(context: Context): Int = dir(context).listFiles()?.size ?: 0

    fun cacheBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0

    fun clearCache(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    // ---- HTTP mínimo, sin librerías ----

    /**
     * Devuelve el cuerpo si la respuesta es 200 y `null` si no existe (404 u otro 4xx).
     * Los errores del servidor (5xx) y de red se reintentan con espera creciente.
     * @throws Unavailable si tras los reintentos el servidor sigue sin responder.
     */
    private fun get(url: String, attempts: Int = 3): String? {
        repeat(attempts) { attempt ->
            if (attempt > 0) Thread.sleep(700L * attempt)
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                when {
                    code == HttpURLConnection.HTTP_OK -> return connection.inputStream.bufferedReader().use { it.readText() }
                    code < 500 -> return null
                }
            } catch (e: IOException) {
                // Sin internet o conexión cortada: se reintenta igual que un 5xx.
            } finally {
                connection?.disconnect()
            }
        }
        throw Unavailable()
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    /** Para segmentos de ruta: URLEncoder usa '+' en los espacios, que en una ruta no significa espacio. */
    private fun encodePath(text: String) = encode(text).replace("+", "%20")

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
