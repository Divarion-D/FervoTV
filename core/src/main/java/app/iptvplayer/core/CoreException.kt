package app.iptvplayer.core

import java.io.IOException

/**
 * A failure core reports as a code, not as text (UI-022): `core` has no user-visible words, the apps say it in the
 * interface language. [detail] is technical and not translated (an HTTP code, a format version), may be null.
 */
class CoreException(val code: ErrorCode, val detail: String? = null) :
    IOException(if (detail == null) code.name else "${code.name}: $detail")

enum class ErrorCode {
    /** The local playlist file can't be opened any more: moved, deleted, permission lost, drive removed. */
    FILE_LOST,

    /** The playlist has no channels (almost always a broken or blocked answer). */
    PLAYLIST_EMPTY,

    /** A guide source was removed while it was being refreshed. */
    EPG_SOURCE_GONE,

    /** A redirect without an address; [CoreException.detail] = the HTTP code. */
    BAD_REDIRECT,
    TOO_MANY_REDIRECTS,

    /** Xtream: wrong login or password (or the account is off). */
    XTREAM_REJECTED,

    /** Xtream: the server doesn't answer like Xtream Codes. */
    XTREAM_NOT_XTREAM,

    /** Xtream: the answer isn't the expected guide / channel list. */
    XTREAM_BAD_EPG,
    XTREAM_BAD_CHANNELS,

    /** BAK-011: not a Fervo TV settings file. */
    BACKUP_FOREIGN,

    /** BAK-011: a Fervo TV file that is damaged or truncated. */
    BACKUP_CORRUPT,

    /** BAK-011: made by a newer version with a format this one can't read; [CoreException.detail] = its version. */
    BACKUP_NEWER,
}
