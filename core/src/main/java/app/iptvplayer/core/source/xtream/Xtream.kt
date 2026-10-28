package app.iptvplayer.core.source.xtream

import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.NO_GROUP
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.net.httpGet
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

fun loadXtream(s: Source.Xtream): List<Channel> {
    val base = s.server.trimEnd('/')
    val api = "$base/player_api.php?username=${enc(s.user)}&password=${enc(s.pass)}"

    // optInt also accepts "1" as a string, which some panels return.
    if (JSONObject(httpGet(api)).optJSONObject("user_info")?.optInt("auth") != 1) {
        throw IOException("Xtream: неверный логин или пароль")
    }

    val cats = JSONArray(httpGet("$api&action=get_live_categories")).objects()
        .associate { it.optString("category_id") to it.optString("category_name") }
    val ext = if (s.hls) "m3u8" else "ts"
    val path = "$base/live/${enc(s.user)}/${enc(s.pass)}"

    return JSONArray(httpGet("$api&action=get_live_streams")).objects().map {
        Channel(
            name = it.optString("name"),
            url = "$path/${it.optString("stream_id")}.$ext",
            group = cats[it.optString("category_id")]?.ifBlank { null } ?: NO_GROUP,
            logo = it.optString("stream_icon").ifBlank { null },
            tvgId = it.optString("epg_channel_id").ifBlank { null },
        )
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
