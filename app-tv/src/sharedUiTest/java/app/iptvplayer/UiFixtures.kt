package app.iptvplayer

import android.content.Context
import app.iptvplayer.core.Repository
import app.iptvplayer.core.db.AppDb
import app.iptvplayer.core.db.LibraryStore
import app.iptvplayer.core.db.LibraryUser
import app.iptvplayer.core.db.PlaylistStore
import app.iptvplayer.core.db.Watch
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.EpisodeEntry
import app.iptvplayer.core.model.PlaylistRefresh
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.model.VodEntry
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.source.cacheKey
import app.iptvplayer.core.source.userKey
import java.net.URI

internal object UiFixtures {
    private const val SAVED_AT = 1_700_000_000_000L

    fun seed(context: Context, baseUrl: String) {
        requireUiTestPackage(context.packageName)
        listOf("iptv", "settings", "series", "player_memory", "broken_logos").forEach { name ->
            check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        }
        val db = AppDb.get(context)
        val source = seed(context.packageName, db, baseUrl)
        Repository(context, db).apply {
            this.source = source
            settings = settings.copy(
                autoLogos = false,
                playlistRefresh = PlaylistRefresh.MANUAL,
                preview = false,
                homeVisible = false,
                homePreview = false,
            )
        }
    }

    internal fun seed(packageName: String, db: AppDb, baseUrl: String): Source.M3u {
        requireUiTestPackage(packageName)
        val root = normalizedBaseUrl(baseUrl)
        val source = Source.M3u("$root/playlist-http.m3u")
        val sourceKey = source.cacheKey()
        val userKey = source.userKey()

        db.clearAllTables()
        PlaylistStore(db).import(sourceKey, savedAt = SAVED_AT, userKey = userKey) { fixtureLive(root).forEach(it::channel) }

        val movieKey = "$userKey|movie|multi"
        val seriesKey = "$userKey|series|test-series"
        val library = LibraryStore(db)
        library.import(sourceKey, userKey, savedAt = SAVED_AT) {
            it.item(
                VodEntry(
                    VodKind.MOVIE, movieKey, "Test multi audio subtitles (2026) [4K]", "Movies",
                    url = "$root/multi.mp4", plot = "Synthetic movie for UI tests", genre = "Test",
                )
            )
            it.item(
                VodEntry(
                    VodKind.SERIES, seriesKey, "Test Series", "Series",
                    plot = "Two deterministic seasons", genre = "Test",
                )
            )
        }
        val episodes = listOf(
            EpisodeEntry("$seriesKey|1|1", seriesKey, 1, 1, "Test Series S01E01", "Pilot", "$root/episode1.mp4"),
            EpisodeEntry("$seriesKey|1|2", seriesKey, 1, 2, "Test Series S01E02", "Second", "$root/episode2.mp4"),
            EpisodeEntry("$seriesKey|2|1", seriesKey, 2, 1, "Test Series S02E01", "Return", "$root/episode1.mp4"),
            EpisodeEntry("$seriesKey|2|2", seriesKey, 2, 2, "Test Series S02E02", "Finale", "$root/episode2.mp4"),
        )
        library.storeEpisodes(sourceKey, seriesKey, episodes, at = SAVED_AT)

        val user = LibraryUser(db, clock = { SAVED_AT + 1 })
        user.savePosition(Watch(movieKey, VodKind.MOVIE, title = "Test multi audio subtitles", watchedAt = 0), 30_000, 120_000)
        user.savePosition(
            Watch(episodes.first().key, VodKind.SERIES, seriesKey, 1, 1, "Test Series", "Pilot", watchedAt = 0),
            15_000,
            120_000,
        )
        return source
    }

    private fun fixtureLive(root: String): List<Channel> {
        val formats = listOf(
            Channel("Test MP4", "$root/live/mp4", "Formats", tvgId = "media-test-mp4", number = 1),
            Channel("Test MPEG-TS", "$root/live/mpeg2.ts", "Formats", tvgId = "media-test-ts", number = 2),
            Channel("Test HLS", "$root/live/hls.m3u8", "Formats", tvgId = "media-test-hls", number = 3),
            Channel("Test DASH", "$root/live/dash.mpd", "Formats", tvgId = "media-test-dash", number = 4),
            Channel("Missing logo", "$root/live/extensionless", "Edge cases", tvgId = "media-test-no-logo", number = 5),
            Channel(
                "Required headers", "$root/live/headers", "Edge cases", tvgId = "media-test-headers", number = 6,
                userAgent = "FervoTV-Media-Test", referer = "https://media-test.invalid/",
            ),
        )
        val endpoints = listOf("live/mp4", "live/mpeg2.ts", "live/hls.m3u8", "live/dash.mpd")
        return formats + (7..24).map { number ->
            val name = when (number) {
                7, 8 -> "Duplicate channel"
                9 -> "A deliberately very long channel name for layout and truncation checks"
                else -> "Channel $number"
            }
            Channel(
                name = name,
                url = "$root/${endpoints[number % endpoints.size]}?fixture=$number",
                group = if (number % 2 == 0) "News" else "Sports",
                tvgId = "media-test-$number",
                number = number,
            )
        }
    }

    private fun normalizedBaseUrl(value: String): String {
        val base = value.trim().trimEnd('/')
        val uri = runCatching { URI(base) }.getOrNull()
        require(uri != null && uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "UI fixture base URL must be an HTTP(S) origin without credentials, query or fragment"
        }
        return base
    }
}
