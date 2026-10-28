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
 * with the playlist. The caller closes [reader].
 */
fun parseM3u(reader: Reader, baseUrl: String? = null, onChannel: (Channel) -> Unit) {
    var info: String? = null
    var extGroup: String? = null
    var first = true
    for (raw in BufferedReader(reader).lineSequence()) {
        val line = (if (first) raw.removePrefix("\uFEFF") else raw).trim()
        first = false
        when {
            line.isEmpty() -> {}
            line.startsWith("#EXTINF", ignoreCase = true) -> { info = line; extGroup = null }
            line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substring(8).trim()
            line.startsWith("#") -> {}
            else -> {
                // A broken entry skips only itself.
                runCatching { toChannel(info, extGroup, line, baseUrl) }.onSuccess(onChannel)
                info = null
                extGroup = null
            }
        }
    }
}

private fun toChannel(info: String?, extGroup: String?, url: String, baseUrl: String?): Channel {
    val attrs = info?.let { ATTR.findAll(it).associate { m -> m.groupValues[1].lowercase() to m.groupValues[2] } }.orEmpty()
    val name = info?.let(::displayName).orEmpty()
        .ifBlank { attrs["tvg-name"].orEmpty() }
        .ifBlank { url.substringBefore('?').substringAfterLast('/') }
    val group = attrs["group-title"]?.takeIf { it.isNotBlank() }
        ?: extGroup?.takeIf { it.isNotBlank() }
        ?: NO_GROUP
    // Some providers use a non-standard `logo` attribute, often with a relative path.
    val logo = (attrs["tvg-logo"] ?: attrs["logo"])?.ifBlank { null }?.let { resolve(baseUrl, it) }
    return Channel(name, url, group, logo, attrs["tvg-id"]?.ifBlank { null })
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
