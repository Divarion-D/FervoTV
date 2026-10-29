package app.iptvplayer.ui

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.iptvplayer.R
import app.iptvplayer.core.CoreException
import app.iptvplayer.core.ErrorCode
import app.iptvplayer.core.db.ChannelGroup
import app.iptvplayer.core.db.GroupCount
import app.iptvplayer.core.model.ChannelSort
import app.iptvplayer.core.model.NO_GROUP
import app.iptvplayer.core.model.PlaylistRefresh
import app.iptvplayer.core.model.Source
import app.iptvplayer.core.net.HttpException
import app.iptvplayer.core.player.PlaybackError
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * UI-022: core gives codes and values, these word them in the interface language. All text is in res/values
 * (English, the fallback) and res/values-ru.
 */

/** A text to show later, worded when shown (so a language switch rewords it). */
class Txt(@StringRes val id: Int, vararg val args: Any) {
    @Composable
    fun text(): String = stringResource(id, *args)
}

@StringRes
fun ChannelSort.title(): Int = when (this) {
    ChannelSort.PROVIDER -> R.string.sort_provider
    ChannelSort.NUMBER -> R.string.sort_number
    ChannelSort.NAME -> R.string.sort_name
    ChannelSort.ARCHIVE_FIRST -> R.string.sort_archive
    ChannelSort.FAVORITES_FIRST -> R.string.sort_favorites
}

/** "Every 3 days" for [PlaylistRefresh.EVERY_N_DAYS], the plain name otherwise. */
fun PlaylistRefresh.label(ctx: Context, days: Int): String = when (this) {
    PlaylistRefresh.ON_START -> ctx.getString(R.string.refresh_on_start)
    PlaylistRefresh.HOURS_6 -> ctx.getString(R.string.refresh_6h)
    PlaylistRefresh.DAILY -> ctx.getString(R.string.refresh_daily)
    PlaylistRefresh.WEEKLY -> ctx.getString(R.string.refresh_weekly)
    PlaylistRefresh.EVERY_N_DAYS -> ctx.resources.getQuantityString(R.plurals.refresh_every_days, days, days)
    PlaylistRefresh.MANUAL -> ctx.getString(R.string.refresh_manual)
}

fun daysText(ctx: Context, n: Int): String = ctx.resources.getQuantityString(R.plurals.days, n, n)

/** The service lists and the provider's nameless category get the interface's names. */
fun List<GroupCount>.named(ctx: Context): List<GroupCount> = map { g ->
    val id = when (g.group) {
        ChannelGroup.Favorites -> R.string.group_favorites
        ChannelGroup.Recent -> R.string.group_recent
        ChannelGroup.Archive -> R.string.group_archive
        ChannelGroup.All -> R.string.group_all
        is ChannelGroup.Category -> if (g.title == NO_GROUP) R.string.group_none else null
        is ChannelGroup.Collection -> null
    }
    if (id == null) g else g.copy(title = ctx.getString(id))
}

/** A category name as shown: the nameless one gets the interface's name. */
fun groupName(ctx: Context, name: String): String = if (name == NO_GROUP) ctx.getString(R.string.group_none) else name

/** A human reason instead of an exception's text. */
fun errorText(ctx: Context, e: Throwable): String = when (e) {
    is CoreException -> when (e.code) {
        ErrorCode.FILE_LOST -> ctx.getString(R.string.err_file_lost)
        ErrorCode.PLAYLIST_EMPTY -> ctx.getString(R.string.err_playlist_empty)
        ErrorCode.EPG_SOURCE_GONE -> ctx.getString(R.string.err_epg_source_gone)
        ErrorCode.BAD_REDIRECT -> ctx.getString(R.string.err_bad_redirect, e.detail.orEmpty())
        ErrorCode.TOO_MANY_REDIRECTS -> ctx.getString(R.string.err_too_many_redirects)
        ErrorCode.XTREAM_REJECTED -> ctx.getString(R.string.err_xtream_rejected)
        ErrorCode.XTREAM_NOT_XTREAM -> ctx.getString(R.string.err_xtream_not_xtream)
        ErrorCode.XTREAM_BAD_EPG -> ctx.getString(R.string.err_xtream_bad_epg)
        ErrorCode.XTREAM_BAD_CHANNELS -> ctx.getString(R.string.err_xtream_bad_channels)
        ErrorCode.BACKUP_FOREIGN -> ctx.getString(R.string.err_backup_foreign)
        ErrorCode.BACKUP_CORRUPT -> ctx.getString(R.string.err_backup_corrupt)
        ErrorCode.BACKUP_NEWER -> ctx.getString(R.string.err_backup_newer, e.detail.orEmpty())
    }
    is HttpException -> ctx.getString(R.string.err_http, e.code)
    is UnknownHostException -> ctx.getString(R.string.err_no_internet)
    is SocketTimeoutException -> ctx.getString(R.string.err_timeout)
    is ConnectException -> ctx.getString(R.string.err_connect)
    else -> e.message ?: e.javaClass.simpleName
}

fun playbackErrorText(ctx: Context, e: PlaybackError): String = ctx.getString(
    when (e.kind) {
        PlaybackError.Kind.Network -> R.string.play_err_network
        PlaybackError.Kind.Format -> R.string.play_err_format
        PlaybackError.Kind.Decoder -> R.string.play_err_decoder
        PlaybackError.Kind.Drm -> R.string.play_err_drm
        PlaybackError.Kind.Other -> R.string.play_err_other
        PlaybackError.Kind.Stalled -> R.string.play_err_stalled
    },
    e.detail,
)

fun Source.label(ctx: Context): String = when (this) {
    is Source.M3u -> if (url.startsWith("content://")) ctx.getString(R.string.source_local_file) else url
    is Source.Xtream -> ctx.getString(R.string.source_xtream, server)
}

/** Day and month in the language's own order and words ("Thu, Oct 29" / "чт, 29 окт"); [weekday] with the day name. */
fun dayText(ms: Long, weekday: Boolean = true, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, if (weekday) "EEEdMMM" else "dMMM"), locale)
        .format(Date(ms)).replace(".", "")

/** Date and time, short and in the language's order ("10/29/26, 9:40 PM" / "29.10.2026, 21:40"). */
fun dateTimeText(ctx: Context, ms: Long): String {
    val locale = if (Build.VERSION.SDK_INT >= 24) ctx.resources.configuration.locales[0] else @Suppress("DEPRECATION") ctx.resources.configuration.locale
    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, locale).format(Date(ms))
    return date + ", " + android.text.format.DateFormat.getTimeFormat(ctx).format(Date(ms))
}
