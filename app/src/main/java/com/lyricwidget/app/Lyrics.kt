package com.lyricwidget.app

/**
 * Letra lista para mostrar. Si viene sincronizada (formato LRC) cada línea trae su tiempo;
 * si es solo texto, las líneas se reparten a lo largo de la canción como aproximación.
 */
class Lyrics(
    val lines: List<Line>,
    val synced: Boolean,
    val instrumental: Boolean,
    val source: String,
) {
    class Line(val timeMs: Long, val text: String)

    /** Línea que corresponde a `positionMs`; -1 si todavía no empieza la primera. */
    fun indexAt(positionMs: Long, durationMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (!synced) {
            if (durationMs <= 0) return 0
            return (positionMs * lines.size / durationMs).toInt().coerceIn(0, lines.lastIndex)
        }
        // Búsqueda binaria de la última línea que ya empezó.
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** Milisegundos hasta que cambie la línea mostrada, o `null` si ya no cambia más. */
    fun msUntilNext(index: Int, positionMs: Long, durationMs: Long): Long? {
        val next = index + 1
        if (next > lines.lastIndex) return null
        val start = if (synced) lines[next].timeMs else if (durationMs > 0) next * durationMs / lines.size else return null
        return start - positionMs
    }

    companion object {
        /** Texto para silencios instrumentales y para antes de la primera línea. */
        const val PAUSE = "· · ·"

        private val TAG = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")

        fun fromLrc(lrc: String, source: String): Lyrics? {
            val lines = mutableListOf<Line>()
            for (raw in lrc.lineSequence()) {
                val tags = TAG.findAll(raw).toList()
                if (tags.isEmpty()) continue
                val text = raw.substring(tags.last().range.last + 1).trim()
                for (tag in tags) {
                    val minutes = tag.groupValues[1].toLong()
                    val seconds = tag.groupValues[2].replace(':', '.').toDouble()
                    lines += Line(minutes * 60_000 + (seconds * 1000).toLong(), text)
                }
            }
            if (lines.isEmpty()) return null
            lines.sortBy { it.timeMs }
            return Lyrics(lines, synced = true, instrumental = false, source = source)
        }

        fun fromPlain(text: String, source: String): Lyrics? {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map { Line(0, it) }.toList()
            if (lines.isEmpty()) return null
            return Lyrics(lines, synced = false, instrumental = false, source = source)
        }

        fun instrumental(source: String) = Lyrics(emptyList(), synced = false, instrumental = true, source = source)
    }
}
