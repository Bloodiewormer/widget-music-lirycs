package com.lyricwidget.app

import java.util.Locale

/** Canción tal como la publica el reproductor en su MediaSession. */
class Track(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val player: String,
) {
    /** Identifica la canción para la caché y el desfase, sin importar mayúsculas ni el reproductor. */
    val key: String = "${normalize(artist)}|${normalize(title)}"

    /** Artista y título sin ruido ("Remastered 2011", "(Official Video)", "VEVO"...) para buscar. */
    val cleanArtist: String
    val cleanTitle: String

    init {
        var a = artist.replace(TOPIC, "").replace(VEVO, "").trim()
        var t = title
        // En YouTube el título suele ser "Artista - Canción" y el "artista" es el nombre del canal.
        val dash = t.indexOf(" - ")
        if (dash > 0 && (a.isEmpty() || artist != a || t.startsWith(a, ignoreCase = true))) {
            a = t.substring(0, dash)
            t = t.substring(dash + 3)
        }
        cleanArtist = a.split(FEATURING, limit = 2)[0].trim().ifEmpty { artist.trim() }
        cleanTitle = t.replace(NOISE_BRACKETS, "").replace(NOISE_DASH, "").replace(FEAT_TAIL, "").trim().ifEmpty { title.trim() }
    }

    val label: String get() = if (artist.isBlank()) title else "$title · $artist"

    companion object {
        private val TOPIC = Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE)
        private val VEVO = Regex("""VEVO$""")
        private val FEATURING = Regex("""\s*(,|;|\s(feat\.?|ft\.|featuring|con|with)\s)\s*""", RegexOption.IGNORE_CASE)
        private val NOISE_WORDS = "remaster|live|en vivo|version|versión|feat|ft\\.|official|oficial|video|audio|lyric|letra|explicit|mono|stereo|edit|visualizer|hd|4k|mv"
        private val NOISE_BRACKETS = Regex("""\s*[(\[][^)\]]*($NOISE_WORDS)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        private val NOISE_DASH = Regex("""\s+-\s+[^-]*($NOISE_WORDS).*$""", RegexOption.IGNORE_CASE)
        private val FEAT_TAIL = Regex("""\s+(feat\.?|ft\.)\s.*$""", RegexOption.IGNORE_CASE)

        fun normalize(text: String) = text.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()
    }
}
