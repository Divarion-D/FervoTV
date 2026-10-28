package app.iptvplayer.core.source.xtream

import android.util.JsonReader
import android.util.JsonToken
import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.NO_GROUP
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.net.httpGet
import app.iptvplayer.core.net.httpStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Live categories (in provider order, via [onCategory]) and channels (via [onChannel], one at a time).
 * The account check and the category list are small; the stream list can be huge and is read element by element.
 */
fun loadXtream(s: Source.Xtream, onCategory: (String) -> Unit, onChannel: (Channel) -> Unit) {
    val base = s.server.trimEnd('/')
    val api = "$base/player_api.php?username=${enc(s.user)}&password=${enc(s.pass)}"

    // optInt also accepts "1" as a string, which some panels return.
    if (JSONObject(httpGet(api)).optJSONObject("user_info")?.optInt("auth") != 1) {
        throw IOException("Xtream: неверный логин или пароль")
    }

    val cats = JSONArray(httpGet("$api&action=get_live_categories")).objects()
        .associate { it.optString("category_id") to it.optString("category_name") }
    cats.values.forEach { name -> onCategory(name.ifBlank { NO_GROUP }) }

    val ext = if (s.hls) "m3u8" else "ts"
    val path = "$base/live/${enc(s.user)}/${enc(s.pass)}"
    httpStream("$api&action=get_live_streams") { input ->
        JsonReader(input.reader()).use { readLiveStreams(it, cats, path, ext, onChannel) }
    }
}

/** get_live_streams: `[{stream_id, name, category_id, stream_icon, epg_channel_id}, …]`. An entry without id is skipped. */
internal fun readLiveStreams(r: JsonReader, cats: Map<String, String>, path: String, ext: String, onChannel: (Channel) -> Unit) {
    if (r.peek() != JsonToken.BEGIN_ARRAY) throw IOException("Xtream: сервер вернул не список каналов")
    r.beginArray()
    while (r.hasNext()) {
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
        var id: String? = null
        var name: String? = null
        var category: String? = null
        var icon: String? = null
        var epg: String? = null
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "stream_id" -> id = r.scalar()
            "name" -> name = r.scalar()
            "category_id" -> category = r.scalar()
            "stream_icon" -> icon = r.scalar()
            "epg_channel_id" -> epg = r.scalar()
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
