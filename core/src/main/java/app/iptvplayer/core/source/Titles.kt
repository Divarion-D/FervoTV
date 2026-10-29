package app.iptvplayer.core.source

import app.iptvplayer.core.model.VodKind

/*
 * Library titles (VOD-007, SRC-010): pure text rules, shared by every source type. Provider captions stay as they are
 * on posters; these give the clean title of a detail page, its year and the quality / language labels.
 */

/** [title] without provider labels and year; [tags] = the labels taken off, upper case, in order ("EN", "4K"). */
data class CleanTitle(val title: String, val year: Int?, val tags: List<String>)

/** Labels recognised at the end of a title, bare or bracketed (case doesn't matter). */
private val TAIL_TAGS = setOf(
    "4K", "UHD", "FHD", "HD", "SD", "HQ", "HDR", "HDR10", "DV", "HEVC", "H264", "H265", "X264", "X265", "AVC",
    "2160P", "1080P", "720P", "480P", "3D", "CAM", "WEB", "WEBRIP", "WEB-DL", "BDRIP", "BLURAY",
    "MULTI", "MULTISUB", "MULTI-SUB", "SUB", "SUBS", "DUB", "VOSTFR", "VOSE", "VO",
)

/** `|EN|`, `[4K]`, `(EN)` / `EN: `, `EN| `, `FR-CA - ` at the start; `[…]`, `(…)` with a label at the end. */
private val HEAD_BRACKET = Regex("""^[|\[(]\s*([\p{Lu}\d][\p{Lu}\d +\-]{0,11}?)\s*[|\])]\s*[-:|–—]?\s*""")
private val HEAD_CODE = Regex("""^(\p{Lu}{2,3}(?:-\p{Lu}{2,3})?)\s*(?:[:|]\s*|\s[-–—]\s+)""")
private val TAIL_BRACKET = Regex("""\s*[\[(]\s*([\p{L}\d +\-]{1,12}?)\s*[])]$""")
private val TAIL_WORD = Regex("""[\s\-|_.]+([\p{L}\d\-]{1,9})$""")
/** A year in brackets or after a dash: a bare one is often part of the title ("Blade Runner 2049"). */
private val TAIL_YEAR = Regex("""(?:\s*[(\[]\s*((?:19|20)\d\d)\s*[)\]]|\s*[-–—|,]\s*((?:19|20)\d\d))$""")
private val EDGE = Regex("""^[\s\-|:–—_.,]+|[\s\-|:–—_.,]+$""")
// ponytail: any 2–3 capitals in brackets is a label, so "[REC] 2" loses "[REC]"; a list of language codes if that bites.
private val LANGUAGE = Regex("""\p{Lu}{2,3}(?:-\p{Lu}{2,3})?""")

/**
 * VOD-007: "|EN| Фильм (2019) [4K]" → ("Фильм", 2019, [EN, 4K]). Labels go only while something is left, so
 * "1917" or "[REC]" keep their name; one year is taken, the last one ("2012 (2009)" → "2012", 2009).
 */
fun cleanTitle(raw: String): CleanTitle {
    var t = raw.trim()
    val head = ArrayList<String>()
    val tail = ArrayList<String>()
    var year: Int? = null
    // Leading labels: in brackets or pipes (short and in capitals: "|EN|", "[4K]", "[TOP 250]"), or a language code.
    while (true) {
        val m = HEAD_BRACKET.find(t)?.takeIf { isLabel(it.groupValues[1], bracketed = true) }
            ?: HEAD_CODE.find(t)
            ?: break
        val rest = t.substring(m.range.last + 1)
        if (rest.isBlank()) break
        head += m.groupValues[1].trim().uppercase()
        t = rest
    }
    // Trailing labels and the year, in any order ("(2019) 4K", "4K (2019)").
    while (true) {
        val bracket = TAIL_BRACKET.find(t)?.takeIf { isLabel(it.groupValues[1], bracketed = true) }
        val word = TAIL_WORD.find(t)?.takeIf { it.groupValues[1].uppercase() in TAIL_TAGS }
        val y = if (year == null) TAIL_YEAR.find(t) else null
        val m = bracket ?: word ?: y ?: break
        val rest = t.substring(0, m.range.first)
        if (rest.isBlank() || EDGE.replace(rest, "").isEmpty()) break
        if (m === y) year = m.groupValues[1].ifEmpty { m.groupValues[2] }.toInt() else tail.add(0, m.groupValues[1].trim().uppercase())
        t = rest
    }
    return CleanTitle(EDGE.replace(t, "").ifEmpty { raw.trim() }, year, head + tail)
}

/** A quality / language label, not a word of the title: known tags; in brackets also capitals ("EN", "TOP 250"). */
private fun isLabel(s: String, bracketed: Boolean): Boolean {
    val u = s.trim().uppercase()
    if (u in TAIL_TAGS) return true
    if (!bracketed) return false
    if (s.matches(LANGUAGE)) return true
    // "[REC]" is a film, "[MULTI-SUB]" a label: capitals with letters and digits ("4K", "TOP 250"), or known words.
    return s == u && ((s.any(Char::isDigit) && s.any(Char::isLetter)) || s.split(' ', '-', '+').all { it.uppercase() in TAIL_TAGS || it.matches(LANGUAGE) })
}

/** Where an episode marker was found in a name: the show before it, numbers, the episode's own title after it. */
data class EpisodeMark(val show: String, val season: Int, val episode: Int, val title: String)

/** Season + episode markers (SRC-010), the most specific first. Group 1 = season, 2 = episode. */
private val MARKS = listOf(
    Regex("""(?iu)\bS(\d{1,4})\s*[.\-_ ]?\s*E(?:P|PISODE)?\s*\.?\s*(\d{1,4})\b"""),
    Regex("""(?iu)\bSeason\s*(\d{1,4})\s*[,.\-:/]?\s*Ep(?:isode)?\.?\s*(\d{1,4})\b"""),
    Regex("""(?iu)\bСезон\s*(\d{1,4})\s*[,.\-:/]?\s*(?:Серия|Эпизод)\s*(\d{1,4})\b"""),
    Regex("""(?iu)\b(\d{1,4})\s*сезон\s*[,.\-:/]?\s*(\d{1,4})\s*(?:серия|эпизод)\b"""),
    Regex("""(?iu)\b(\d{1,2})x(\d{1,3})\b"""),
)

/** Episode only (short forms: "E05", "Ep. 5", "Episode 5", "Серия 5"), season 1. */
private val EPISODE_ONLY = Regex("""(?iu)\bEP?(\d{1,4})\b|\b(?:Ep\.|Episode|Серия|Эпизод)\s*(\d{1,4})\b""")

/**
 * SRC-010: "Шоу S01E05 Название" → ("Шоу", 1, 5, "Название"). Markers: `S1E5` (zeros, space, dot or dash between),
 * `1x05`, "Season N Episode M" / «Сезон N Серия M» / «N сезон M серия», short "E05" / «Серия 5» (season 1).
 * Season 0 = specials. null = no marker.
 */
fun episodeMark(name: String): EpisodeMark? {
    for (r in MARKS) {
        val m = r.find(name) ?: continue
        return mark(name, m.range, m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }
    val m = EPISODE_ONLY.find(name) ?: return null
    return mark(name, m.range, 1, m.groupValues[1].ifEmpty { m.groupValues[2] }.toInt())
}

private fun mark(name: String, at: IntRange, season: Int, episode: Int) = EpisodeMark(
    show = EDGE.replace(name.substring(0, at.first), ""),
    season = season,
    episode = episode,
    title = EDGE.replace(name.substring(at.last + 1), ""),
)

/**
 * VOD-007: an episode's own title: "Сериал – S01E03 – Название" → "Название"; the series name repeated in front
 * goes too. null when nothing is left (the app shows "Episode 3", or TMDB's title later).
 */
fun episodeTitle(raw: String, series: String): String? {
    val t = episodeMark(raw)?.let { m -> m.title.ifEmpty { if (sameName(m.show, series) || m.show.isEmpty()) "" else m.show } } ?: raw
    val withoutSeries = if (series.isNotBlank() && t.startsWith(series, ignoreCase = true)) t.substring(series.length) else t
    return EDGE.replace(withoutSeries, "").takeIf { it.isNotEmpty() && !sameName(it, series) }
}

private fun sameName(a: String, b: String) = showKey(a) == showKey(b)

/** Episodes of one show group by this (SRC-010): case, ё/е, punctuation and spacing don't matter. */
fun showKey(show: String): String = buildString(show.length) {
    for (c in show.lowercase()) {
        val ch = if (c == 'ё') 'е' else if (c.isLetterOrDigit()) c else ' '
        if (ch != ' ' || (isNotEmpty() && last() != ' ')) append(ch)
    }
}.trim()

/** Containers of films and episodes, not of live streams (SRC-010 "признаки VOD-адреса"). */
private val VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "webm", "mpg", "mpeg", "divx", "flv", "3gp")

/**
 * SRC-010: which section an M3U entry belongs to; null = live. The `type` attribute decides first; then the address:
 * an Xtream-style `/series/` or `/movie/` path, or a film container extension (an episode if the name has a marker).
 */
fun m3uSection(type: String?, url: String, name: String): VodKind? {
    when (type?.trim()?.lowercase()) {
        "movie", "movies", "film", "films", "vod" -> return VodKind.MOVIE
        "series", "serie", "show", "tvshow", "tv_show", "episode" -> return VodKind.SERIES
        "live", "channel", "tv", "stream" -> return null
    }
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    if ("/series/" in path) return VodKind.SERIES
    if ("/movie/" in path || "/movies/" in path) return VodKind.MOVIE
    val ext = path.substringAfterLast('/').substringAfterLast('.', "")
    if (ext in VOD_EXTENSIONS) return if (episodeMark(name) != null) VodKind.SERIES else VodKind.MOVIE
    return null
}

/** A year from a provider's date ("2019-05-01", "2019", "01.05.2019"), null if there's none. */
fun yearOf(date: String?): Int? = date?.let { Regex("""(?<!\d)((?:19|20)\d\d)(?!\d)""").find(it)?.groupValues?.get(1)?.toInt() }
