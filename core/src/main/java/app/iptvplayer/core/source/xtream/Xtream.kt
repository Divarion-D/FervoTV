package app.iptvplayer.core.source.xtream

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.NO_GROUP
import app.iptvplayer.core.model.Programme
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.source.userKey
import app.iptvplayer.core.net.HttpException
import app.iptvplayer.core.net.httpGet
import app.iptvplayer.core.net.httpStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

internal const val ACCOUNT_REJECTED = "Xtream: аккаунт не принят — проверьте логин и пароль"

/**
 * Live categories (in provider order, via [onCategory]) and channels (via [onChannel], one at a time).
 * The account check and the category list are small; the stream list can be huge and is read element by element.
 */
fun loadXtream(s: Source.Xtream, onCategory: (String) -> Unit, onChannel: (Channel) -> Unit) {
    val base = s.server.trimEnd('/')
    val api = api(s)

    // Some panels answer a wrong login with 401/403 or an HTML page instead of auth = 0.
    val auth = try {
        httpGet(api)
    } catch (e: HttpException) {
        if (e.code == 401 || e.code == 403) throw IOException(ACCOUNT_REJECTED) else throw e
    }
    val info = runCatching { JSONObject(auth) }.getOrNull() ?: throw IOException("Xtream: сервер ответил не как Xtream Codes")
    // optInt also accepts "1" as a string, which some panels return.
    if (info.optJSONObject("user_info")?.optInt("auth") != 1) throw IOException(ACCOUNT_REJECTED)

    val cats = JSONArray(httpGet("$api&action=get_live_categories")).objects()
        .associate { it.optString("category_id") to it.optString("category_name") }
    cats.values.forEach { name -> onCategory(name.ifBlank { NO_GROUP }) }

    val ext = if (s.hls) "m3u8" else "ts"
    val path = "$base/live/${enc(s.user)}/${enc(s.pass)}"
    httpStream("$api&action=get_live_streams") { input ->
        JsonReader(input.reader()).use { readLiveStreams(it, cats, path, ext, s.userKey(), onChannel) }
    }
}

private fun api(s: Source.Xtream) = "${s.server.trimEnd('/')}/player_api.php?username=${enc(s.user)}&password=${enc(s.pass)}"

/** The account's full guide (EPG-002): connected as an ordinary XMLTV source. */
fun xtreamXmltvUrl(s: Source.Xtream) = "${s.server.trimEnd('/')}/xmltv.php?username=${enc(s.user)}&password=${enc(s.pass)}"

/** [Channel.key] of an Xtream channel: [userKey] + stream_id, so neither the password nor TS/HLS changes it. */
internal fun xtreamKey(userKey: String, streamId: String) = "$userKey|$streamId"

/** stream_id of a channel keyed by [xtreamKey]. */
internal fun xtreamStreamId(key: String) = key.substringAfterLast('|')

/** get_short_epg: the current and next few programmes of one stream (EPG-002), titles and descriptions in base64. */
fun loadShortEpg(s: Source.Xtream, streamId: String, limit: Int): List<Programme> =
    httpStream("${api(s)}&action=get_short_epg&stream_id=${enc(streamId)}&limit=$limit") { input ->
        JsonReader(input.reader()).use(::readShortEpg)
    }

/** `{"epg_listings": [{title, description, start_timestamp, stop_timestamp, …}]}`; entries without times are skipped. */
internal fun readShortEpg(r: JsonReader): List<Programme> {
    val out = ArrayList<Programme>()
    if (r.peek() != JsonToken.BEGIN_OBJECT) throw IOException("Xtream: сервер вернул не телегид")
    r.beginObject()
    while (r.hasNext()) {
        if (r.nextName() != "epg_listings" || r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); continue }
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            var title: String? = null
            var description: String? = null
            var start: Long? = null
            var stop: Long? = null
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "title" -> title = r.scalar()
                "description" -> description = r.scalar()
                "start_timestamp" -> start = r.scalar()?.toLongOrNull()
                "stop_timestamp" -> stop = r.scalar()?.toLongOrNull()
                else -> r.skipValue()
            }
            r.endObject()
            if (start == null || stop == null || stop <= start) continue
            out += Programme(start * 1000, stop * 1000, unBase64(title).orEmpty(), unBase64(description)?.ifBlank { null })
        }
        r.endArray()
    }
    r.endObject()
    return out
}

/** Panels send base64 texts; one that isn't valid base64 is kept as is. */
private fun unBase64(s: String?): String? = s?.let {
    runCatching { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(it).trim()
}

/**
 * get_live_streams: `[{num, stream_id, name, category_id, stream_icon, epg_channel_id, tv_archive, tv_archive_duration}, …]`.
 * An entry without id is skipped.
 */
internal fun readLiveStreams(
    r: JsonReader, cats: Map<String, String>, path: String, ext: String, userKey: String, onChannel: (Channel) -> Unit,
) {
    if (r.peek() != JsonToken.BEGIN_ARRAY) throw IOException("Xtream: сервер вернул не список каналов")
    r.beginArray()
    while (r.hasNext()) {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
        var id: String? = null
        var name: String? = null
        var category: String? = null
        var icon: String? = null
        var epg: String? = null
        var num: String? = null
        var archive: String? = null
        var archiveDays: String? = null
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "stream_id" -> id = r.scalar()
            "name" -> name = r.scalar()
            "category_id" -> category = r.scalar()
            "stream_icon" -> icon = r.scalar()
            "epg_channel_id" -> epg = r.scalar()
            "num" -> num = r.scalar()
            "tv_archive" -> archive = r.scalar()
            "tv_archive_duration" -> archiveDays = r.scalar()
            else -> r.skipValue()
        }
        r.endObject()
        if (id.isNullOrBlank()) continue
        onChannel(
            Channel(
                name = name.orEmpty(),
                url = "$path/$id.$ext",
                group = cats[category]?.ifBlank { null } ?: NO_GROUP,
                logo = icon?.ifBlank { null },
                tvgId = epg?.ifBlank { null },
                key = xtreamKey(userKey, id),
                number = num?.toIntOrNull()?.takeIf { it > 0 },
                archiveDays = if (archive == "1") archiveDays?.toIntOrNull()?.coerceAtLeast(1) ?: 1 else 0,
            )
        )
    }
    r.endArray()
}

/** Strings, numbers and booleans as text (panels mix "1" and 1); null, objects and arrays as null. */
private fun JsonReader.scalar(): String? = when (peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    else -> { skipValue(); null }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
