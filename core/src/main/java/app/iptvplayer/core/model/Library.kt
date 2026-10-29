package app.iptvplayer.core.model

/** The two halves of the library (VOD-001). Stored by name. */
enum class VodKind { MOVIE, SERIES }

/**
 * Catalogue order (VOD-003). [PROVIDER] = the provider's with the user's manual order on top; [ADDED] = the
 * provider's date, else when Fervo TV first saw the title, newest first; [YEAR] and [RATING] highest first.
 */
enum class VodSort { PROVIDER, NAME, ADDED, YEAR, RATING }

/** How far a film or episode was watched (VOD-008, VOD-010). [position] is 0 once [watched]; [at] = last watched. */
data class Progress(val position: Long, val duration: Long, val watched: Boolean, val at: Long)

/**
 * A film or a series as the library screens show it. [name] is the poster caption: the provider's (VOD-007) or the
 * user's name; [title] the clean title for the detail page, [tags] the quality / language labels taken off it.
 * [key] is stable across refreshes (user data hangs on it): M3U film = stream URL, M3U series = playlist + show name,
 * Xtream = server|user|movie|stream_id or server|user|series|series_id.
 */
data class VodItem(
    val key: String,
    val kind: VodKind,
    val name: String,
    val title: String,
    val year: Int?,
    val tags: List<String>,
    /** The provider's category name (filters use it; the user's name for it comes with the categories). */
    val category: String,
    val poster: String?,
    val backdrop: String?,
    val rating: Double?,
    /** The provider's date added, else when the title first appeared in Fervo TV (epoch ms). */
    val added: Long,
    val plot: String?,
    val genre: String?,
    /** Film: its stream. Series: null, episodes have their own. */
    val url: String?,
    val favorite: Boolean,
    /** Hidden itself or by its category (listed only when the filter asks for hidden ones). */
    val hidden: Boolean,
    /** Film: its own; series: the last episode started (VOD-008). */
    val progress: Progress?,
    /** Database row id; changes with every refresh, use [key] to refer to the item. */
    val id: Long = 0,
)

/**
 * An episode of a series. [title] is clean (VOD-007), null when nothing is left after cleaning (the app shows
 * "Episode 3"). [key]: M3U = stream URL, Xtream = series key|season|episode.
 */
data class Episode(
    val key: String,
    val seriesKey: String,
    /** 0 = specials. */
    val season: Int,
    val episode: Int,
    val title: String?,
    /** The provider's name of the entry. */
    val name: String,
    val url: String,
    val image: String?,
    val plot: String?,
    /** ms, null = unknown. */
    val duration: Long?,
    val released: String?,
    val progress: Progress?,
)

/** A film or series as a source gives it (SRC-010, SRC-011); the import cleans its title and stores it. */
data class VodEntry(
    val kind: VodKind,
    val key: String,
    val name: String,
    val category: String,
    val url: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: Double? = null,
    /** The provider's date added, epoch ms. */
    val added: Long? = null,
    val plot: String? = null,
    val genre: String? = null,
    /** The provider's year; null = taken from the name. */
    val year: Int? = null,
)

/** An episode as a source gives it; [title] already clean (null = none). */
data class EpisodeEntry(
    val key: String,
    val seriesKey: String,
    val season: Int,
    val episode: Int,
    val name: String,
    val title: String?,
    val url: String,
    val image: String? = null,
    val plot: String? = null,
    val duration: Long? = null,
    val released: String? = null,
)

/** VOD-010: continue at the saved position at once, ask, or always start over. */
enum class ResumeMode { CONTINUE, ASK, START }

/** VOD-006: how a series lists its episodes; kept per series. */
data class SeriesView(val newestFirst: Boolean = false, val hideWatched: Boolean = false)

/** Details of a film the list doesn't carry (Xtream get_vod_info, VOD-004); null fields = not given. */
data class VodInfo(
    /** ms. */
    val duration: Long? = null,
    val plot: String? = null,
    val genre: String? = null,
    val backdrop: String? = null,
    val director: String? = null,
    val cast: String? = null,
    val released: String? = null,
)
