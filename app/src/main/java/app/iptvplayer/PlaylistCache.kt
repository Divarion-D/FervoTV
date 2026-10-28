package app.iptvplayer

/** Last successfully loaded playlist: the app opens it instantly and works without internet. */
class CachedPlaylist(val channels: List<Channel>, val savedAt: Long)

/**
 * Plain text, one channel per line: name, url, group, logo, tvgId separated by tabs.
 * First line: "#<source key>\t<saved at, ms>". Tabs/newlines inside values become spaces.
 */
fun encodePlaylist(sourceKey: String, savedAt: Long, channels: List<Channel>): String = buildString {
    append('#').append(sourceKey).append('\t').append(savedAt).append('\n')
    for (c in channels) {
        listOf(c.name, c.url, c.group, c.logo.orEmpty(), c.tvgId.orEmpty()).joinTo(this, "\t") { clean(it) }
        append('\n')
    }
}

/** null when the cache belongs to another source or is damaged. */
fun decodePlaylist(sourceKey: String, text: String): CachedPlaylist? {
    val lines = text.lineSequence().iterator()
    if (!lines.hasNext()) return null
    val header = lines.next()
    if (!header.startsWith("#")) return null
    val (key, savedAtText) = header.removePrefix("#").split('\t').takeIf { it.size == 2 } ?: return null
    if (key != sourceKey) return null
    val savedAt = savedAtText.toLongOrNull() ?: return null
    val channels = lines.asSequence()
        .filter { it.isNotEmpty() }
        .map { it.split('\t') }
        .filter { it.size == 5 }
        .map { f -> Channel(f[0], f[1], f[2], f[3].ifEmpty { null }, f[4].ifEmpty { null }) }
        .toList()
    return CachedPlaylist(channels, savedAt)
}

/** Identity of the playlist source: another URL or Xtream account doesn't reuse this cache. */
fun Source.cacheKey(): String = hashCode().toUInt().toString()

private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
