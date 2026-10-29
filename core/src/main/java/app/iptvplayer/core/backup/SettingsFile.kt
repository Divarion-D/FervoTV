package app.iptvplayer.core.backup

import app.iptvplayer.core.CoreException
import app.iptvplayer.core.Defaults
import app.iptvplayer.core.ErrorCode
import app.iptvplayer.core.db.CategoryState
import app.iptvplayer.core.db.ChannelState
import app.iptvplayer.core.db.EpgSource
import app.iptvplayer.core.db.SavedCollection
import app.iptvplayer.core.db.UserData
import app.iptvplayer.core.model.ChannelSort
import app.iptvplayer.core.model.PlaylistRefresh
import app.iptvplayer.core.model.Settings
import app.iptvplayer.core.model.Source
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * The Fervo TV backup format (BAK-001, BAK-011): a ZIP, extension `.fervo`, of JSON files.
 *
 *   manifest.json  {"format": "fervo", "formatVersion": 1, "appVersion": "0.2", "createdAt": "2026-10-29T10:00:00Z",
 *                   "parts": ["source", "settings", "guide", "channels"]}
 *   source.json    {"type": "m3u", "url": …} | {"type": "xtream", "server", "user", "hls", "password"?}
 *   settings.json  every field of Settings by name (missing = default, unknown = ignored)
 *   guide.json     {"sources": [{"url", "userAgent"?, "refresh", "refreshDays", "shift"?}]}  (priority order)
 *   channels.json  {"channels": [{"key", "name"?, "hidden"?, "favorite"?, "epg"?, "epgShift"?, "logo"?}],
 *                   "categories": [{"key", "name"?, "hidden"?, "position"?}],
 *                   "order": [{"list", "keys": […]}], "collections": [{"name", "keys": […]}]}
 *
 * Keys are the stable keys of channels and categories, so edits land on the channels of a fresh import.
 * Later versions (P5) add parts and fields; [FORMAT_VERSION] goes up only when old readers would misread a file.
 * A reader takes the parts it knows and ignores the rest.
 */

const val FORMAT = "fervo"
const val FORMAT_VERSION = 1
const val BACKUP_EXTENSION = "fervo"

/** What is read from one file at most: a settings file is kilobytes; this keeps a hostile one from filling memory. */
private const val MAX_BYTES = 16L shl 20

/**
 * The content of a settings file (BAK-011). A null part isn't in the file and isn't touched by a restore.
 * [passwordMissing] (read side): the Xtream account came without its password, the app asks for it.
 */
data class Backup(
    val source: Source? = null,
    val passwordMissing: Boolean = false,
    val settings: Settings? = null,
    /** Guide sources in priority order; only address, User-Agent, schedule and shift are kept. */
    val guides: List<EpgSource>? = null,
    val channels: UserData? = null,
)

/** Writes [b] to [out] (and closes it). The Xtream password goes in only [withPassword]: the file isn't encrypted. */
fun writeBackup(out: OutputStream, b: Backup, withPassword: Boolean, appVersion: String, now: Long) {
    val parts = LinkedHashMap<String, JSONObject>()
    b.source?.let { parts["source"] = sourceJson(it, withPassword) }
    b.settings?.let { parts["settings"] = settingsJson(it) }
    b.guides?.let { list -> parts["guide"] = JSONObject().put("sources", JSONArray(list.map(::guideJson))) }
    b.channels?.let { parts["channels"] = channelsJson(it) }
    val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val manifest = JSONObject()
        .put("format", FORMAT)
        .put("formatVersion", FORMAT_VERSION)
        .put("appVersion", appVersion)
        .put("createdAt", created.format(Date(now)))
        .put("parts", JSONArray(parts.keys.toList()))
    ZipOutputStream(out).use { zip ->
        fun put(name: String, json: JSONObject) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(json.toString(2).toByteArray())
            zip.closeEntry()
        }
        put("manifest.json", manifest)
        parts.forEach { (name, json) -> put("$name.json", json) }
    }
}

/**
 * Reads a settings file whatever its name. Everything is read and checked before anything is returned, so a restore
 * either gets the whole file or nothing. Throws [CoreException]: [ErrorCode.BACKUP_FOREIGN] (not a Fervo TV file),
 * [ErrorCode.BACKUP_CORRUPT] (damaged, truncated, nonsense inside), [ErrorCode.BACKUP_NEWER] (a newer format).
 */
fun readBackup(input: InputStream): Backup {
    val files = unzip(input)
    val manifest = files["manifest.json"]?.let(::json) ?: throw CoreException(ErrorCode.BACKUP_FOREIGN)
    if (manifest.optString("format") != FORMAT) throw CoreException(ErrorCode.BACKUP_FOREIGN)
    val version = manifest.optInt("formatVersion", 0)
    if (version < 1) throw CoreException(ErrorCode.BACKUP_CORRUPT)
    if (version > FORMAT_VERSION) throw CoreException(ErrorCode.BACKUP_NEWER, version.toString())
    return try {
        val parts = manifest.getJSONArray("parts").let { a -> List(a.length()) { a.getString(it) } }
        // A part the manifest lists but the archive lacks: the file was cut off.
        fun part(name: String): JSONObject? =
            if (name in parts) json(files["$name.json"] ?: throw CoreException(ErrorCode.BACKUP_CORRUPT)) else null
        val source = part("source")
        Backup(
            source = source?.let(::readSource),
            passwordMissing = source?.optString("type") == "xtream" && !source.has("password"),
            settings = part("settings")?.let(::readSettings),
            guides = part("guide")?.getJSONArray("sources")?.objects()?.map(::readGuide),
            channels = part("channels")?.let(::readChannels),
        )
    } catch (e: JSONException) {
        throw CoreException(ErrorCode.BACKUP_CORRUPT)
    } catch (e: IllegalArgumentException) {
        throw CoreException(ErrorCode.BACKUP_CORRUPT)
    }
}

/** Entry name → text. Not a ZIP at all reads as no entries; a broken one is [ErrorCode.BACKUP_CORRUPT]. */
private fun unzip(input: InputStream): Map<String, String> {
    val files = HashMap<String, String>()
    var total = 0L
    try {
        ZipInputStream(input).use { zip ->
            val buf = ByteArray(8192)
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || e.name in files) continue
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw CoreException(ErrorCode.BACKUP_CORRUPT)
                    bytes.write(buf, 0, n)
                }
                files[e.name] = bytes.toString("UTF-8")
            }
        }
    } catch (e: CoreException) {
        throw e
    } catch (e: IOException) {
        throw CoreException(ErrorCode.BACKUP_CORRUPT)
    } catch (e: IllegalArgumentException) { // a malformed entry name
        throw CoreException(ErrorCode.BACKUP_CORRUPT)
    }
    return files
}

private fun json(text: String): JSONObject = try {
    JSONObject(text)
} catch (e: JSONException) {
    throw CoreException(ErrorCode.BACKUP_CORRUPT)
}

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else getString(name)

private fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else getInt(name)

private inline fun <reified E : Enum<E>> JSONObject.enum(name: String, default: E): E =
    enumValues<E>().find { it.name == optString(name) } ?: default

private fun nonBlank(s: String): String = s.ifBlank { throw JSONException("blank") }

// --- source ---

private fun sourceJson(s: Source, withPassword: Boolean): JSONObject = when (s) {
    is Source.M3u -> JSONObject().put("type", "m3u").put("url", s.url)
    is Source.Xtream -> JSONObject().put("type", "xtream").put("server", s.server).put("user", s.user).put("hls", s.hls)
        .apply { if (withPassword) put("password", s.pass) }
}

private fun readSource(j: JSONObject): Source = when (j.getString("type")) {
    "m3u" -> Source.M3u(nonBlank(j.getString("url")))
    "xtream" -> Source.Xtream(nonBlank(j.getString("server")), nonBlank(j.getString("user")), j.optString("password", ""), j.optBoolean("hls"))
    else -> throw JSONException("source type") // ponytail: a newer source type (Stalker, P5) reads as damaged, not as "newer"
}

// --- settings ---

private fun settingsJson(s: Settings): JSONObject = JSONObject()
    .put("autoLogos", s.autoLogos)
    .put("logoSource", s.logoSource)
    .put("playlistRefresh", s.playlistRefresh.name)
    .put("refreshDays", s.refreshDays)
    .put("epgDays", s.epgDays)
    .put("epgShift", s.epgShift)
    .put("channelSort", s.channelSort.name)
    .put("preview", s.preview)
    .put("previewSound", s.previewSound)
    .put("rememberLive", s.rememberLive)
    .putOpt("liveTiles", s.liveTiles)
    .put("liveGuide", s.liveGuide)
    .put("guideEmpty", s.guideEmpty)
    .put("language", s.language)

private fun readSettings(j: JSONObject): Settings {
    val d = Settings()
    return Settings(
        autoLogos = j.optBoolean("autoLogos", d.autoLogos),
        logoSource = j.optString("logoSource", d.logoSource).ifBlank { d.logoSource },
        playlistRefresh = j.enum("playlistRefresh", d.playlistRefresh),
        refreshDays = j.optInt("refreshDays", d.refreshDays).coerceIn(1, 365),
        epgDays = j.optInt("epgDays", d.epgDays).coerceIn(Defaults.EPG_DAYS_RANGE),
        epgShift = j.optInt("epgShift", d.epgShift).coerceIn(-Defaults.EPG_SHIFT_MAX, Defaults.EPG_SHIFT_MAX),
        channelSort = j.enum("channelSort", d.channelSort),
        preview = j.optBoolean("preview", d.preview),
        previewSound = j.optBoolean("previewSound", d.previewSound),
        rememberLive = j.optBoolean("rememberLive", d.rememberLive),
        liveTiles = if (j.isNull("liveTiles")) null else j.getBoolean("liveTiles"),
        liveGuide = j.optBoolean("liveGuide", d.liveGuide),
        guideEmpty = j.optBoolean("guideEmpty", d.guideEmpty),
        language = j.optString("language", d.language),
    )
}

// --- guide ---

private fun guideJson(s: EpgSource): JSONObject = JSONObject()
    .put("url", s.url)
    .putOpt("userAgent", s.userAgent)
    .put("refresh", s.refresh.name)
    .put("refreshDays", s.refreshDays)
    .putOpt("shift", s.shift)

private fun readGuide(j: JSONObject) = EpgSource(
    url = nonBlank(j.getString("url")),
    userAgent = j.optStringOrNull("userAgent"),
    refresh = j.enum("refresh", Defaults.EPG_REFRESH),
    refreshDays = j.optInt("refreshDays", Defaults.PLAYLIST_REFRESH_DAYS).coerceIn(1, 365),
    shift = j.optIntOrNull("shift"),
)

// --- channel edits ---

private fun channelsJson(d: UserData): JSONObject = JSONObject()
    .put("channels", JSONArray(d.channels.map { s ->
        JSONObject().put("key", s.ckey).putOpt("name", s.name).put("hidden", s.hidden).putOpt("favorite", s.favorite)
            .putOpt("epg", s.epg).putOpt("epgShift", s.epgShift).putOpt("logo", s.logo)
    }))
    .put("categories", JSONArray(d.categories.map { s ->
        JSONObject().put("key", s.ckey).putOpt("name", s.name).put("hidden", s.hidden).putOpt("position", s.position)
    }))
    .put("order", JSONArray(d.order.map { (list, keys) -> JSONObject().put("list", list).put("keys", JSONArray(keys)) }))
    .put("collections", JSONArray(d.collections.map { c -> JSONObject().put("name", c.name).put("keys", JSONArray(c.channels)) }))

private fun readChannels(j: JSONObject) = UserData(
    channels = j.optJSONArray("channels")?.objects().orEmpty().map { c ->
        ChannelState(
            ckey = nonBlank(c.getString("key")), name = c.optStringOrNull("name"), hidden = c.optBoolean("hidden"),
            favorite = c.optIntOrNull("favorite"), epg = c.optStringOrNull("epg"), epgShift = c.optIntOrNull("epgShift"),
            logo = c.optStringOrNull("logo"),
        )
    },
    categories = j.optJSONArray("categories")?.objects().orEmpty().map { c ->
        CategoryState(nonBlank(c.getString("key")), c.optStringOrNull("name"), c.optBoolean("hidden"), c.optIntOrNull("position"))
    },
    order = j.optJSONArray("order")?.objects().orEmpty().associate { o -> nonBlank(o.getString("list")) to o.getJSONArray("keys").strings() },
    collections = j.optJSONArray("collections")?.objects().orEmpty().map { c ->
        SavedCollection(c.getString("name"), c.getJSONArray("keys").strings())
    },
)
