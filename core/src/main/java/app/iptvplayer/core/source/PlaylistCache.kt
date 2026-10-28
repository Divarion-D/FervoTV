package app.iptvplayer.core.source

import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.Source
import java.io.BufferedReader

/**
 * Reader of the pre-database cache `filesDir/playlist.tsv` (v0.1), kept only to move it into the database
 * on the first start of a newer version.
 *
 * Plain text, one channel per line: name, url, group, logo, tvgId separated by tabs.
 * First line: "#<source key>\t<saved at, ms>". Tabs/newlines inside values were replaced with spaces.
 */
class CacheHeader(val sourceKey: String, val savedAt: Long)

/** null when the file is damaged. */
fun readCacheHeader(r: BufferedReader): CacheHeader? {
    val header = r.readLine() ?: return null
    if (!header.startsWith("#")) return null
    val (key, savedAtText) = header.removePrefix("#").split('\t').takeIf { it.size == 2 } ?: return null
    return CacheHeader(key, savedAtText.toLongOrNull() ?: return null)
}

/** The lines after the header; damaged ones are skipped. */
fun readCacheChannels(r: BufferedReader, onChannel: (Channel) -> Unit) {
    for (line in r.lineSequence()) {
        if (line.isEmpty()) continue
        val f = line.split('\t').takeIf { it.size == 5 } ?: continue
        onChannel(Channel(f[0], f[1], f[2], f[3].ifEmpty { null }, f[4].ifEmpty { null }))
    }
}

/** Identity of the playlist source: another URL or Xtream account doesn't reuse its saved channels. */
fun Source.cacheKey(): String = hashCode().toUInt().toString()

/**
 * Identity of the source for user data (favorites, edits, EPG bindings): unlike [cacheKey] it doesn't change with
 * the Xtream password or the TS/HLS choice, so those edits survive such a change.
 */
fun Source.userKey(): String = when (this) {
    is Source.M3u -> url
    is Source.Xtream -> "${server.trimEnd('/')}|$user"
}
