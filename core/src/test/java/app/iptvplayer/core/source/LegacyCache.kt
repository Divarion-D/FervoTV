package app.iptvplayer.core.source

import app.iptvplayer.core.model.Channel
import java.io.BufferedReader
import java.io.StringReader

// The v0.1 writer and whole-file reader of playlist.tsv. The app only reads the format now (to migrate it),
// so these live in the tests: they build fixtures and keep PlaylistCacheTest checking the format.

class CachedPlaylist(val channels: List<Channel>, val savedAt: Long)

fun encodePlaylist(sourceKey: String, savedAt: Long, channels: List<Channel>): String = buildString {
    append('#').append(sourceKey).append('\t').append(savedAt).append('\n')
    for (c in channels) {
        listOf(c.name, c.url, c.group, c.logo.orEmpty(), c.tvgId.orEmpty()).joinTo(this, "\t") { clean(it) }
        append('\n')
    }
}

fun decodePlaylist(sourceKey: String, text: String): CachedPlaylist? {
    val r = BufferedReader(StringReader(text))
    val header = readCacheHeader(r)?.takeIf { it.sourceKey == sourceKey } ?: return null
    return CachedPlaylist(ArrayList<Channel>().also { list -> readCacheChannels(r) { list += it } }, header.savedAt)
}

private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
