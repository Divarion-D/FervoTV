package app.iptvplayer.core.source

import app.iptvplayer.core.model.VodKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** VOD-007 clean titles and SRC-010 episode markers / sections: tables of provider names and what they become. */
class TitlesTest {
    @Test
    fun cleanTitles() {
        val cases = listOf(
            "|EN| The Matrix (1999)" to CleanTitle("The Matrix", 1999, listOf("EN")),
            "[4K] Дюна (2021)" to CleanTitle("Дюна", 2021, listOf("4K")),
            "[4K] Дюна 2021" to CleanTitle("Дюна 2021", null, listOf("4K")),
            "EN: Heat (1995) [MULTI-SUB]" to CleanTitle("Heat", 1995, listOf("EN", "MULTI-SUB")),
            "|NF| |RU| Брат 2 (2000) FHD" to CleanTitle("Брат 2", 2000, listOf("NF", "RU", "FHD")),
            "FR-CA - Amélie (2001)" to CleanTitle("Amélie", 2001, listOf("FR-CA")),
            "Avatar 4K (2009)" to CleanTitle("Avatar", 2009, listOf("4K")),
            "Avatar (2009) 4K HEVC" to CleanTitle("Avatar", 2009, listOf("4K", "HEVC")),
            "Inception - 2010" to CleanTitle("Inception", 2010, emptyList()),
            // Names that look like labels or years but are the whole title stay.
            "1917" to CleanTitle("1917", null, emptyList()),
            "2012 (2009)" to CleanTitle("2012", 2009, emptyList()),
            "[REC]" to CleanTitle("[REC]", null, emptyList()),
            "(500) Days of Summer" to CleanTitle("(500) Days of Summer", null, emptyList()),
            "Blade Runner 2049" to CleanTitle("Blade Runner 2049", null, emptyList()),
            "Человек-паук: Через вселенные" to CleanTitle("Человек-паук: Через вселенные", null, emptyList()),
            "  Пробелы  " to CleanTitle("Пробелы", null, emptyList()),
        )
        for ((raw, want) in cases) assertEquals(raw, want, cleanTitle(raw))
    }

    @Test
    fun episodeMarkers() {
        val cases = listOf(
            "Шоу S01E05 Название" to EpisodeMark("Шоу", 1, 5, "Название"),
            "Show S1E5" to EpisodeMark("Show", 1, 5, ""),
            "Show s01 e05 - Pilot" to EpisodeMark("Show", 1, 5, "Pilot"),
            "Show S01.E05" to EpisodeMark("Show", 1, 5, ""),
            "Show - S01-E05 - Title" to EpisodeMark("Show", 1, 5, "Title"),
            "Show S01EP05" to EpisodeMark("Show", 1, 5, ""),
            "Show 1x05 Title" to EpisodeMark("Show", 1, 5, "Title"),
            "Show Season 2 Episode 10" to EpisodeMark("Show", 2, 10, ""),
            "Сериал Сезон 3 Серия 4" to EpisodeMark("Сериал", 3, 4, ""),
            "Сериал 3 сезон 4 серия" to EpisodeMark("Сериал", 3, 4, ""),
            "Show S00E01 Special" to EpisodeMark("Show", 0, 1, "Special"),
            "Show E07" to EpisodeMark("Show", 1, 7, ""),
            "Show Ep. 7" to EpisodeMark("Show", 1, 7, ""),
            "Сериал Серия 12" to EpisodeMark("Сериал", 1, 12, ""),
            "S02E03 Pilot" to EpisodeMark("", 2, 3, "Pilot"),
        )
        for ((raw, want) in cases) assertEquals(raw, want, episodeMark(raw))
        for (none in listOf("Rocky 2", "Blade Runner 2049", "1920x1080 test", "Ocean's Eleven", "Seven")) {
            assertEquals(none, null, episodeMark(none))
        }
    }

    @Test
    fun episodeTitles() {
        assertEquals("Название", episodeTitle("Сериал – S01E03 – Название", "Сериал"))
        assertEquals("Pilot", episodeTitle("Pilot", "Show"))
        assertEquals("Pilot", episodeTitle("Show - Pilot", "Show"))
        assertEquals(null, episodeTitle("Show - S01E03", "Show")) // the app says "Episode 3"
        assertEquals(null, episodeTitle("Show", "Show"))
        assertEquals(null, episodeTitle("", "Show"))
    }

    @Test
    fun sections() {
        val cases = listOf(
            Triple("movie", "http://h/a.ts", "A") to VodKind.MOVIE,
            Triple("series", "http://h/a.ts", "A") to VodKind.SERIES,
            Triple("live", "http://h/a.mkv", "A") to null,
            Triple(null, "http://h/movie/u/p/1.mkv", "A") to VodKind.MOVIE,
            Triple(null, "http://h/series/u/p/1.mp4", "A") to VodKind.SERIES,
            Triple(null, "http://h/films/a.mkv?token=1", "A") to VodKind.MOVIE,
            Triple(null, "http://h/a.mp4", "Show S01E02") to VodKind.SERIES,
            Triple(null, "http://h/live/1.ts", "Канал") to null,
            Triple(null, "http://h/index.m3u8", "Канал") to null,
            Triple(null, "http://h/stream", "Канал") to null,
        )
        for ((args, want) in cases) assertEquals(args.toString(), want, m3uSection(args.first, args.second, args.third))
    }

    @Test
    fun showKeysAndYears() {
        assertEquals(showKey("Ёлки  Палки!"), showKey("елки палки"))
        assertEquals(2019, yearOf("2019-05-01"))
        assertEquals(2019, yearOf("01.05.2019"))
        assertEquals(null, yearOf("N/A"))
        assertEquals(null, yearOf(null))
    }
}
