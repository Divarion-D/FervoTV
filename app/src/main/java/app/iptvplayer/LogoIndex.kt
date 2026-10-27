package app.iptvplayer

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Channel logos found in the iptv-org database, keyed "id:<lowercase iptv-org id>" or [normName] of a channel name.
 * Only entries that match the current playlist are kept: the full database (~30k logos) is used once to match and dropped.
 */
class LogoIndex(private val map: Map<String, String>) {
    fun find(ch: Channel): String? = matchKey(map, ch)?.let(map::get)

    companion object {
        private const val PREFIX = "logos-"

        /**
         * Cached on disk per logo source. Rebuilt (re-downloaded) when older than [Defaults.LOGO_INDEX_MAX_AGE_MS]
         * or when the playlist's channels changed. A stale cache beats none.
         */
        suspend fun load(ctx: Context, source: String, channels: List<Channel>): LogoIndex = withContext(Dispatchers.IO) {
            val base = source.trim().trimEnd('/')
            val file = File(ctx.cacheDir, "$PREFIX${base.hashCode().toUInt()}.tsv")
            val stamp = "#" + channels.map { "${it.tvgId}|${it.name}" }.sorted().hashCode()
            val cached = if (file.exists()) read(file) else null
            val fresh = cached != null && cached.first == stamp &&
                System.currentTimeMillis() - file.lastModified() < Defaults.LOGO_INDEX_MAX_AGE_MS

            if (!fresh) {
                try {
                    val full = build(base)
                    val used = HashMap<String, String>()
                    for (ch in channels) matchKey(full, ch)?.let { used[it] = full.getValue(it) }
                    val tmp = File(file.path + ".tmp")
                    tmp.bufferedWriter().use { w ->
                        w.write("$stamp\n")
                        used.forEach { (k, v) -> w.write("$k\t$v\n") }
                    }
                    tmp.renameTo(file)
                    // Indexes of other (old) logo sources are unused now.
                    ctx.cacheDir.listFiles { f -> f.name.startsWith(PREFIX) && f != file }?.forEach { it.delete() }
                    return@withContext LogoIndex(used)
                } catch (e: Exception) {
                    if (cached == null) throw e
                }
            }
            LogoIndex(cached!!.second)
        }

        /** First line is the playlist stamp, then "key\turl" lines. */
        private fun read(file: File): Pair<String, Map<String, String>> = file.useLines { lines ->
            val it = lines.iterator()
            val stamp = if (it.hasNext()) it.next() else ""
            stamp to it.asSequence()
                .mapNotNull { l -> l.indexOf('\t').takeIf { i -> i > 0 }?.let { i -> l.substring(0, i) to l.substring(i + 1) } }
                .toMap()
        }

        /** Streams the JSON (channels.json is ~8 MB) instead of loading it whole. */
        private fun build(base: String): Map<String, String> {
            val logoByChannel = HashMap<String, String>()
            httpStream("$base/logos.json") { input ->
                JsonReader(input.reader()).use { r ->
                    r.beginArray()
                    while (r.hasNext()) {
                        var channel: String? = null
                        var feed: String? = null
                        var url: String? = null
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "channel" -> channel = r.stringOrNull()
                            "feed" -> feed = r.stringOrNull()
                            "url" -> url = r.stringOrNull()
                            else -> r.skipValue()
                        }
                        r.endObject()
                        // The channel's main logo wins over per-feed variants.
                        if (channel != null && url != null && (feed == null || channel !in logoByChannel)) {
                            logoByChannel[channel] = url
                        }
                    }
                }
            }

            val index = HashMap<String, String>()
            httpStream("$base/channels.json") { input ->
                JsonReader(input.reader()).use { r ->
                    r.beginArray()
                    while (r.hasNext()) {
                        var id: String? = null
                        val names = ArrayList<String>()
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "id" -> id = r.stringOrNull()
                            "name" -> r.stringOrNull()?.let(names::add)
                            "alt_names" -> if (r.peek() == JsonToken.BEGIN_ARRAY) {
                                r.beginArray()
                                while (r.hasNext()) r.stringOrNull()?.let(names::add)
                                r.endArray()
                            } else r.skipValue()
                            else -> r.skipValue()
                        }
                        r.endObject()
                        val url = id?.let(logoByChannel::get) ?: continue
                        index["id:" + id.lowercase()] = url
                        // ponytail: first channel with a given name wins regardless of country; prefer by country if collisions matter.
                        for (n in names) normName(n).takeIf { it.isNotEmpty() }?.let { index.getOrPut(it) { url } }
                    }
                }
            }
            return index
        }

        private fun JsonReader.stringOrNull(): String? =
            if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()
    }
}

/** tvg-id first, then the normalized name. */
private fun matchKey(map: Map<String, String>, ch: Channel): String? =
    ch.tvgId?.let { "id:" + it.lowercase() }?.takeIf { it in map }
        ?: normName(ch.name).takeIf { it.isNotEmpty() && it in map }

private val QUALITY = Regex("""(?<![\p{L}\p{N}])(hd|fhd|uhd|4k|sd|hevc|orig)(?![\p{L}\p{N}])""")

/** "Звезда HD" → "звезда", "viju+ History" → "vijuhistory": case, quality tags and punctuation don't matter. */
fun normName(name: String): String =
    name.lowercase().replace('ё', 'е').replace(QUALITY, "").filter { it.isLetterOrDigit() }
