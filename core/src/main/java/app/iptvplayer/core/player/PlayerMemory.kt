package app.iptvplayer.core.player

import android.content.Context
import java.security.MessageDigest

/** Per-item player choices. Item identities are hashed so credentials never reach SharedPreferences. */
class PlayerMemory(context: Context) {
    enum class Kind { ENGINE, FORMAT, SCALE, VOLUME, AUDIO_DELAY, AUDIO_TRACK, SUBTITLE_TRACK }

    data class Counts(
        val engine: Int = 0,
        val liveEngine: Int = 0,
        val format: Int = 0,
        val scale: Int = 0,
        val volume: Int = 0,
        val audioDelay: Int = 0,
        val audioTrack: Int = 0,
        val subtitleTrack: Int = 0,
    )

    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun get(itemKey: String, live: Boolean, kind: Kind): String? = prefs.getString(key(itemKey, live, kind), null)

    fun put(itemKey: String, live: Boolean, kind: Kind, value: String?) {
        prefs.edit().apply { if (value == null) remove(key(itemKey, live, kind)) else putString(key(itemKey, live, kind), value) }.apply()
    }

    fun counts(): Counts = Counts(
        engine = count(Kind.ENGINE),
        liveEngine = prefs.all.keys.count { it.startsWith("${Kind.ENGINE.name}:L:") },
        format = count(Kind.FORMAT),
        scale = count(Kind.SCALE),
        volume = count(Kind.VOLUME),
        audioDelay = count(Kind.AUDIO_DELAY),
        audioTrack = count(Kind.AUDIO_TRACK),
        subtitleTrack = count(Kind.SUBTITLE_TRACK),
    )

    fun reset(kind: Kind, liveOnly: Boolean = false) {
        val prefix = "${kind.name}:" + if (liveOnly) "L:" else ""
        prefs.edit().apply { prefs.all.keys.filter { it.startsWith(prefix) }.forEach(::remove) }.apply()
    }

    private fun count(kind: Kind) = prefs.all.keys.count { it.startsWith("${kind.name}:") }

    private fun key(itemKey: String, live: Boolean, kind: Kind) =
        "${kind.name}:${if (live) "L" else "V"}:${itemKey.sha256()}"

    private fun String.sha256() = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object { const val NAME = "player_memory" }
}
