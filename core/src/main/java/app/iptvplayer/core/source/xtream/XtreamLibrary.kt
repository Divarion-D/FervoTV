package app.iptvplayer.core.source.xtream

import android.util.JsonReader
import android.util.JsonToken
import app.iptvplayer.core.CoreException
import app.iptvplayer.core.ErrorCode
import app.iptvplayer.core.model.EpisodeEntry
import app.iptvplayer.core.model.NO_GROUP
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.model.VodEntry
import app.iptvplayer.core.model.VodKind
import app.iptvplayer.core.net.httpGet
import app.iptvplayer.core.net.httpStream
import app.iptvplayer.core.source.episodeTitle
import app.iptvplayer.core.source.userKey
import app.iptvplayer.core.source.yearOf
import org.json.JSONArray
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/*
 * Films and series of an Xtream account (SRC-011): categories and lists come with the sync, a series' episodes
 * when it is opened (get_series_info, one request per series). Lists are read element by element like live streams.
 */

/** Keys of library items: stable across refreshes, password and container changes (like [xtreamKey]). */
internal fun movieKey(userKey: String, id: String) = "$userKey|movie|$id"
internal fun seriesKey(userKey: String, id: String) = "$userKey|series|$id"
internal fun episodeKey(seriesKey: String, season: Int, episode: Int) = "$seriesKey|$season|$episode"

/** series_id of a series keyed by [seriesKey]. */
internal fun xtreamSeriesId(key: String) = key.substringAfterLast('|')

/** Film categories, films, then series categories and series, in the provider's order. */
fun loadXtreamLibrary(s: Source.Xtream, onCategory: (VodKind, String) -> Unit, onItem: (VodEntry) -> Unit) {
    val api = api(s)
    val userKey = s.userKey()
    val movies = categories("$api&action=get_vod_categories")
    movies.values.distinct().forEach { onCategory(VodKind.MOVIE, it) }
    httpStream("$api&action=get_vod_streams") { input ->
        JsonReader(input.reader()).use { readVodStreams(it, movies, path(s, "movie"), userKey, onItem) }
    }
    val series = categories("$api&action=get_series_categories")
    series.values.distinct().forEach { onCategory(VodKind.SERIES, it) }
    httpStream("$api&action=get_series") { input ->
        JsonReader(input.reader()).use { readSeries(it, series, userKey, onItem) }
    }
}

/** A series' episodes (get_series_info); [name] = the series' name, to clean episode titles. */
fun loadXtreamEpisodes(s: Source.Xtream, seriesKey: String, name: String): List<EpisodeEntry> =
    httpStream("${api(s)}&action=get_series_info&series_id=${enc(xtreamSeriesId(seriesKey))}") { input ->
        JsonReader(input.reader()).use { readSeriesInfo(it, path(s, "series"), seriesKey, name) }
    }

private fun path(s: Source.Xtream, kind: String) = "${s.server.trimEnd('/')}/$kind/${enc(s.user)}/${enc(s.pass)}"

/** id → name; an answer that isn't a list (some panels send `{}` or `null` when there are none) = no categories. */
private fun categories(url: String): Map<String, String> {
    val list = runCatching { JSONArray(httpGet(url).trim()) }.getOrNull() ?: return emptyMap()
    return list.objects().associate { it.optString("category_id") to it.optString("category_name").trim().ifEmpty { NO_GROUP } }
}

/**
 * get_vod_streams: `[{stream_id, name, stream_icon, rating, added, category_id, container_extension, year?, plot?,
 * genre?}, …]`. URL = [path]/{id}.{container}. An entry without id or name is skipped.
 */
internal fun readVodStreams(r: JsonReader, cats: Map<String, String>, path: String, userKey: String, onItem: (VodEntry) -> Unit) {
    readArray(r) { f ->
        val id = f["stream_id"] ?: return@readArray
        val name = f["name"]?.trim()?.ifEmpty { null } ?: return@readArray
        val ext = f["container_extension"]?.takeIf { it.matches(EXTENSION) } ?: "mp4"
        onItem(
            VodEntry(
                VodKind.MOVIE, movieKey(userKey, id), name, cats[f["category_id"]] ?: NO_GROUP,
                url = "$path/$id.$ext", poster = f["stream_icon"], rating = rating(f["rating"]),
                added = seconds(f["added"]), plot = f["plot"], genre = f["genre"], year = yearOf(f["year"]),
            )
        )
    }
}

/** get_series: `[{series_id, name, cover, plot, genre, releaseDate, rating, backdrop_path, last_modified, category_id}, …]`. */
internal fun readSeries(r: JsonReader, cats: Map<String, String>, userKey: String, onItem: (VodEntry) -> Unit) {
    readArray(r) { f ->
        val id = f["series_id"] ?: return@readArray
        val name = f["name"]?.trim()?.ifEmpty { null } ?: return@readArray
        onItem(
            VodEntry(
                VodKind.SERIES, seriesKey(userKey, id), name, cats[f["category_id"]] ?: NO_GROUP,
                poster = f["cover"], backdrop = f["backdrop_path"], rating = rating(f["rating"]),
                added = seconds(f["last_modified"]), plot = f["plot"], genre = f["genre"],
                year = yearOf(f["year"]) ?: yearOf(f["releaseDate"] ?: f["release_date"]),
            )
        )
    }
}

/**
 * get_series_info: `{"seasons": […], "info": {…}, "episodes": {"1": [{id, episode_num, title, container_extension,
 * season, info: {movie_image, plot, duration_secs, duration, releasedate}}, …], …}}`. Some panels send "episodes" as
 * a list of lists. Season = the entry's, else the group's, else 1; number = episode_num, else its place in the group.
 */
internal fun readSeriesInfo(r: JsonReader, path: String, seriesKey: String, name: String): List<EpisodeEntry> {
    if (r.peek() != JsonToken.BEGIN_OBJECT) throw CoreException(ErrorCode.XTREAM_BAD_LIBRARY)
    val out = ArrayList<EpisodeEntry>()
    fun episode(season: Int?, n: Int) {
        val f = readFields(r) ?: return
        val id = f["id"] ?: return
        val s = f["season"]?.toIntOrNull() ?: season ?: 1
        val e = f["episode_num"]?.toIntOrNull() ?: n
        val ext = f["container_extension"]?.takeIf { it.matches(EXTENSION) } ?: "mp4"
        val title = f["title"].orEmpty()
        out += EpisodeEntry(
            key = episodeKey(seriesKey, s, e), seriesKey = seriesKey, season = s, episode = e,
            name = title, title = episodeTitle(title, name), url = "$path/$id.$ext",
            image = f["info.movie_image"], plot = f["info.plot"],
            duration = f["info.duration_secs"]?.toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: hms(f["info.duration"]),
            released = f["info.releasedate"] ?: f["info.air_date"],
        )
    }
    /** A list of episodes; [season] = the group's key. Lists nested in it are groups too. */
    fun group(season: Int?) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) return r.skipValue()
        var n = 0
        r.beginArray()
        while (r.hasNext()) if (r.peek() == JsonToken.BEGIN_ARRAY) group(season) else episode(season, ++n)
        r.endArray()
    }
    r.beginObject()
    while (r.hasNext()) {
        if (r.nextName() != "episodes") { r.skipValue(); continue }
        when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) group(r.nextName().toIntOrNull())
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> group(null)
            else -> r.skipValue()
        }
    }
    r.endObject()
    return out
}

/** Elements of a top-level list as flat fields; broken elements skip themselves. Not a list: [ErrorCode.XTREAM_BAD_LIBRARY]. */
private fun readArray(r: JsonReader, onFields: (Map<String, String>) -> Unit) {
    if (r.peek() != JsonToken.BEGIN_ARRAY) throw CoreException(ErrorCode.XTREAM_BAD_LIBRARY)
    r.beginArray()
    while (r.hasNext()) readFields(r)?.let(onFields)
    r.endArray()
}

/**
 * One object as name → text: scalars as text ("1" and 1 alike), blank and null left out, a list as its first scalar
 * (backdrop_path), a nested object one level deep as "name.field" (an episode's info). null for a non-object.
 */
private fun readFields(r: JsonReader): Map<String, String>? {
    if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
    val f = HashMap<String, String>()
    fun value(name: String, nested: Boolean) {
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) {
                    val v = r.scalar()?.trim()
                    if (!v.isNullOrEmpty() && name !in f) f[name] = v
                }
                r.endArray()
            }
            JsonToken.BEGIN_OBJECT -> if (nested) r.skipValue() else {
                r.beginObject()
                while (r.hasNext()) value("$name.${r.nextName()}", nested = true)
                r.endObject()
            }
            else -> r.scalar()?.trim()?.takeIf { it.isNotEmpty() }?.let { f[name] = it }
        }
    }
    r.beginObject()
    while (r.hasNext()) value(r.nextName(), nested = false)
    r.endObject()
    return f
}

private val EXTENSION = Regex("[A-Za-z0-9]{1,5}")

/** "7.5", 7.5, "N/A", "" → 7.5 / null; 0 means "no rating" with panels. */
private fun rating(s: String?): Double? = s?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }

/** Unix seconds → ms; 0, blank and garbage → null. */
private fun seconds(s: String?): Long? = s?.toLongOrNull()?.takeIf { it > 0 }?.times(1000)

/** "01:23:45" / "45:10" → ms. */
private fun hms(s: String?): Long? {
    val parts = s?.split(':')?.map { it.trim().toLongOrNull() ?: return null } ?: return null
    if (parts.isEmpty() || parts.size > 3) return null
    return parts.fold(0L) { acc, p -> acc * 60 + p }.takeIf { it > 0 }?.times(1000)
}

/**
 * SRC-012: the account an Xtream panel's M3U link names (`http://host:port/get.php?username=…&password=…&type=…
 * &output=ts|m3u8`), null for any other link. `output=m3u8` keeps HLS.
 */
fun xtreamOfM3uUrl(url: String): Source.Xtream? {
    val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (u.scheme?.lowercase() !in setOf("http", "https") || u.rawAuthority == null) return null
    val path = u.rawPath.orEmpty()
    if (!path.endsWith("/get.php")) return null
    val q = u.rawQuery.orEmpty().split('&').mapNotNull { p ->
        val i = p.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
        runCatching { p.substring(0, i).lowercase() to URLDecoder.decode(p.substring(i + 1), "UTF-8") }.getOrNull()
    }.toMap()
    val user = q["username"]?.takeIf { it.isNotEmpty() } ?: return null
    val pass = q["password"] ?: return null
    return Source.Xtream("${u.scheme}://${u.rawAuthority}${path.removeSuffix("/get.php")}", user, pass, hls = q["output"]?.lowercase() in setOf("m3u8", "hls"))
}

/** SRC-012: does the server accept [s] as an Xtream account? Any failure (network, not Xtream, rejected) = no. */
fun xtreamAccepted(s: Source.Xtream): Boolean = try {
    checkXtream(s)
    true
} catch (e: IOException) {
    false
} catch (e: RuntimeException) {
    false
}
