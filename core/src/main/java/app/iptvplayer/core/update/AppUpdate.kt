package app.iptvplayer.core.update

import app.iptvplayer.core.Defaults
import app.iptvplayer.core.net.httpGet
import org.json.JSONObject

/** A downloadable APK from a GitHub Release. */
data class ApkAsset(val name: String, val url: String, val size: Long)

data class AppRelease(
    val version: String,
    val title: String,
    val notes: String,
    val releaseUrl: String,
    val apk: ApkAsset,
)

/** BAK-009: compare stable three-component release versions without a third-party updater library. */
fun checkLatestRelease(
    currentVersion: String,
    supportedAbis: List<String>,
    endpoint: String = Defaults.RELEASES_API,
): AppRelease? = parseLatestRelease(httpGet(endpoint), currentVersion, supportedAbis)

internal fun parseLatestRelease(
    response: String,
    currentVersion: String,
    supportedAbis: List<String>,
): AppRelease? {
    val json = JSONObject(response)
    val version = json.optString("tag_name").removePrefix("v")
    val current = SemVersion.parse(currentVersion) ?: return null
    val latest = SemVersion.parse(version) ?: return null
    if (latest <= current || json.optBoolean("draft") || json.optBoolean("prerelease")) return null

    val assets = json.optJSONArray("assets") ?: return null
    val apk = (0 until assets.length())
        .mapNotNull { assets.optJSONObject(it)?.toAsset() }
        .filter { it.name.endsWith(".apk", ignoreCase = true) }
        .map { it to abiRank(it.name, supportedAbis) }
        .filter { (_, rank) -> rank < 100 }
        .minByOrNull { (_, rank) -> rank }
        ?.first
        ?: return null
    return AppRelease(
        version = version,
        title = json.optString("name").ifBlank { "Fervo TV $version" },
        notes = json.optString("body").trim(),
        releaseUrl = json.optString("html_url"),
        apk = apk,
    )
}

private fun JSONObject.toAsset() = ApkAsset(
    name = optString("name"),
    url = optString("browser_download_url"),
    size = optLong("size", -1),
).takeIf { it.name.isNotBlank() && it.url.isNotBlank() }

/** Prefer a universal APK, then an APK containing the device's first supported ABI. */
private fun abiRank(name: String, supportedAbis: List<String>): Int {
    val lower = name.lowercase()
    if ("universal" in lower || "all" in lower) return 0
    supportedAbis.indexOfFirst { abi -> abi.lowercase() in lower }.takeIf { it >= 0 }?.let { return it + 1 }
    // A plain APK name (the current release format) is universal even without saying so explicitly.
    val knownAbi = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").any { it in lower }
    return if (knownAbi) 100 else 50
}

private data class SemVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVersion> {
    override fun compareTo(other: SemVersion): Int = compareValuesBy(this, other, SemVersion::major, SemVersion::minor, SemVersion::patch)

    companion object {
        fun parse(value: String): SemVersion? {
            val m = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value.trim()) ?: return null
            return SemVersion(m.groupValues[1].toIntOrNull() ?: return null, m.groupValues[2].toIntOrNull() ?: return null, m.groupValues[3].toIntOrNull() ?: return null)
        }
    }
}
