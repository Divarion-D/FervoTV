package app.iptvplayer.core.source.m3u

import app.iptvplayer.core.model.Channel
import app.iptvplayer.core.model.NO_GROUP
import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader
import java.net.URI

private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

/** [baseUrl] = playlist URL; relative logo paths are resolved against it. */
fun parseM3u(text: String, baseUrl: String? = null): List<Channel> =
    ArrayList<Channel>().also { out -> parseM3u(StringReader(text), baseUrl) { out += it } }

/**
 * Streaming version: reads [reader] line by line and hands out channels one at a time, so memory doesn't grow
 * with the playlist. The caller closes [reader]. [onEpgUrls] gets the XMLTV addresses of each `#EXTM3U` header
 * (`url-tvg` / `x-tvg-url`, comma-separated lists split).
 */
fun parseM3u(reader: Reader, baseUrl: String? = null, onEpgUrls: (List<String>) -> Unit = {}, onChannel: (Channel) -> Unit) =
    parseM3uEntries(reader, baseUrl, onEpgUrls) { onChannel(it.channel) }

/** An entry of the playlist: its fields as a [Channel] and its `type` / `tvg-type` attribute (SRC-010: film, series). */
class M3uEntry(val channel: Channel, val type: String?)

/** [parseM3u] with each entry's type, to sort live channels from films and episodes. */
fun parseM3uEntries(reader: Reader, baseUrl: String? = null, onEpgUrls: (List<String>) -> Unit = {}, onEntry: (M3uEntry) -> Unit) {
    var info: String? = null
    var extGroup: String? = null
    var first = true
    for (raw in BufferedReader(reader).lineSequence()) {
        val line = (if (first) raw.removePrefix("\uFEFF") else raw).trim()
        first = false
        when {
            line.isEmpty() -> {}
            line.startsWith("#EXTM3U", ignoreCase = true) -> epgUrls(line).takeIf { it.isNotEmpty() }?.let(onEpgUrls)
            line.startsWith("#EXTINF", ignoreCase = true) -> { info = line; extGroup = null }
            line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substring(8).trim()
            line.startsWith("#") -> {}
            else -> {
                // A broken entry skips only itself.
                runCatching { toEntry(info, extGroup, line, baseUrl) }.onSuccess(onEntry)
                info = null
                extGroup = null
            }
        }
    }
}

private fun toEntry(info: String?, extGroup: String?, url: String, baseUrl: String?): M3uEntry {
    val attrs = info?.let { ATTR.findAll(it).associate { m -> m.groupValues[1].lowercase() to m.groupValues[2] } }.orEmpty()
    val name = info?.let(::displayName).orEmpty()
        .ifBlank { attrs["tvg-name"].orEmpty() }
        .ifBlank { url.substringBefore('?').substringAfterLast('/') }
    val group = attrs["group-title"]?.takeIf { it.isNotBlank() }
        ?: extGroup?.takeIf { it.isNotBlank() }
        ?: NO_GROUP
    // Some providers use a non-standard `logo` attribute, often with a relative path.
    val logo = (attrs["tvg-logo"] ?: attrs["logo"])?.ifBlank { null }?.let { resolve(baseUrl, it) }
    val channel = Channel(
        name, url, group, logo, attrs["tvg-id"]?.ifBlank { null },
        number = attrs["tvg-chno"]?.trim()?.toIntOrNull()?.takeIf { it > 0 },
        // Archive depth in days: catchup-days is the common one, tvg-rec and timeshift are older spellings.
        archiveDays = listOf("catchup-days", "tvg-rec", "timeshift").firstNotNullOfOrNull { attrs[it]?.trim()?.toIntOrNull() }
            ?.coerceAtLeast(0) ?: 0,
    )
    return M3uEntry(channel, (attrs["type"] ?: attrs["tvg-type"])?.ifBlank { null })
}

private fun epgUrls(header: String): List<String> {
    val attrs = ATTR.findAll(header).associate { m -> m.groupValues[1].lowercase() to m.groupValues[2] }
    return listOf("url-tvg", "x-tvg-url").flatMap { attrs[it].orEmpty().split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

private fun resolve(base: String?, ref: String): String =
    if (base == null || !base.startsWith("http") || "://" in ref) ref
    else runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)

/** Channel name = everything after the first comma outside quoted attribute values. */
private fun displayName(info: String): String {
    var quoted = false
    for (i in info.indices) {
        when (info[i]) {
            '"' -> quoted = !quoted
            ',' -> if (!quoted) return info.substring(i + 1).trim()
        }
    }
    return ""
}
