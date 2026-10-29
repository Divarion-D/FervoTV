package app.iptvplayer

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.text.TextUtils
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/*
 * UI-022: the interface language, independent of the metadata language. Android 13+ uses the platform's per-app
 * language (it also shows in the system's app settings; the manifest takes it as a configuration change, so nothing
 * restarts). Older versions get the language over the composition by [Localized]: no extra library, no restart.
 */

/** What Настройки offer; "" = the system's language (English when the system's isn't translated). */
val LANGUAGES = listOf("", "ru", "en")

/** Android 13+: makes [tag] the app's language. Older versions: [Localized] does it. */
fun applyLanguage(ctx: Context, tag: String) {
    if (Build.VERSION.SDK_INT < 33) return
    val lm = ctx.getSystemService(LocaleManager::class.java)
    val want = LocaleList.forLanguageTags(tag)
    if (lm.applicationLocales != want) lm.applicationLocales = want
}

/** Android 13+: the language set for the app (maybe in the system's settings), "" = the system's; null before 13. */
fun platformLanguage(ctx: Context): String? =
    if (Build.VERSION.SDK_INT < 33) null
    else ctx.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language.orEmpty()

/**
 * The interface in [tag] ("" = as the configuration says). Before Android 13 it replaces the context, configuration
 * and layout direction for everything inside; on 13+ the configuration already has it. Sets the default locale too,
 * for date formats.
 */
@Composable
fun Localized(tag: String, content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    if (Build.VERSION.SDK_INT >= 33 || tag.isEmpty()) {
        Locale.setDefault(firstLocale(config))
        return content()
    }
    val base = LocalContext.current
    val locale = remember(tag) { Locale.forLanguageTag(tag) }
    val localized = remember(config, locale) { Configuration(config).apply { setLocale(locale); setLayoutDirection(locale) } }
    val ctx = remember(base, localized) { LocaleContext(base, base.createConfigurationContext(localized).resources) }
    Locale.setDefault(locale)
    val rtl = TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL
    CompositionLocalProvider(
        LocalContext provides ctx,
        LocalConfiguration provides localized,
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        content = content,
    )
}

/** The activity with other resources: everything else (system services, the activity itself) stays the base's. */
private class LocaleContext(base: Context, private val res: Resources) : ContextWrapper(base) {
    override fun getResources(): Resources = res
}

private fun firstLocale(c: Configuration): Locale =
    if (Build.VERSION.SDK_INT >= 24) c.locales[0] else @Suppress("DEPRECATION") c.locale

/** The activity under any wrappers ([Localized] wraps it before Android 13). */
fun Context.findActivity(): android.app.Activity? =
    this as? android.app.Activity ?: (this as? ContextWrapper)?.baseContext?.findActivity()

/** Resources in [tag] ("" = the system's), for a text shown just as the language changes. */
fun Context.inLanguage(tag: String): Context {
    val locale = if (tag.isEmpty()) firstLocale(Resources.getSystem().configuration) else Locale.forLanguageTag(tag)
    return createConfigurationContext(Configuration(resources.configuration).apply { setLocale(locale) })
}
