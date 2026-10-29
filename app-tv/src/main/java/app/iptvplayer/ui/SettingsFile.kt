package app.iptvplayer.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.iptvplayer.inLanguage
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.iptvplayer.R
import app.iptvplayer.core.Repository
import app.iptvplayer.core.backup.BACKUP_EXTENSION
import app.iptvplayer.core.backup.Backup
import app.iptvplayer.core.model.Source
import app.iptvplayer.initialFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** BAK-011 «Сохранить настройки в файл» / «Загрузить настройки из файла», for Настройки and the first-run screen. */
class SettingsFileActions(val save: () -> Unit, val load: () -> Unit)

/**
 * The settings file: the system's document window to pick where; TV boxes without one save to and read from
 * Downloads. Reading checks the whole file first (a bad one changes nothing), then asks before replacing.
 * [onRestored] runs after the restore, to refresh the source and the guide.
 */
@Composable
fun settingsFile(repo: Repository, onRestored: (Backup) -> Unit): SettingsFileActions {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saveDialog by remember { mutableStateOf(false) }
    var withPassword by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Backup?>(null) } // read, waiting for "Заменить"
    var failure by remember { mutableStateOf<String?>(null) }
    var downloads by remember { mutableStateOf<List<Pair<String, Uri>>?>(null) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun toast(text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

    fun write(shown: String, open: () -> OutputStream?) {
        scope.launch {
            try {
                val out = withContext(Dispatchers.IO) { open() } ?: throw FileNotFoundException(shown)
                repo.saveSettingsFile(out, withPassword, appVersion(ctx))
                toast(ctx.getString(R.string.settings_file_saved, shown))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(ctx.getString(R.string.settings_file_save_failed, errorText(ctx, e)))
            }
        }
    }

    fun read(open: () -> InputStream?) {
        scope.launch {
            try {
                pending = repo.readSettingsFile(withContext(Dispatchers.IO) { open() } ?: throw FileNotFoundException())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = errorText(ctx, e)
            }
        }
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri ->
        if (uri != null) write(displayName(ctx, uri)) { ctx.contentResolver.openOutputStream(uri) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) read { ctx.contentResolver.openInputStream(uri) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterPermission?.invoke()
        afterPermission = null
    }

    // Android 6–9 need the storage permission for Downloads; 10+ write there without one.
    fun withStorage(action: () -> Unit) {
        if (Build.VERSION.SDK_INT in 23..28 && ctx.checkSelfPermission(STORAGE) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = action
            permission.launch(STORAGE)
        } else action()
    }

    fun startSave() {
        val name = "fervo-settings-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".$BACKUP_EXTENSION"
        try {
            create.launch(name)
        } catch (e: ActivityNotFoundException) {
            withStorage { write("${Environment.DIRECTORY_DOWNLOADS}/$name") { createInDownloads(ctx, name) } }
        }
    }

    fun startLoad() {
        try {
            open.launch(arrayOf("*/*")) // the format is told by the content, not by the name
        } catch (e: ActivityNotFoundException) {
            withStorage {
                scope.launch {
                    val list = withContext(Dispatchers.IO) { runCatching { listDownloads(ctx) }.getOrDefault(emptyList()) }
                    if (list.isEmpty()) toast(ctx.getString(R.string.settings_file_downloads_empty)) else downloads = list
                }
            }
        }
    }

    val shape = CircleShape
    if (saveDialog) {
        val xtream = repo.source is Source.Xtream
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text(stringResource(R.string.settings_file_save)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_file_save_text))
                    if (xtream) {
                        val row = RoundedCornerShape(12.dp)
                        Row(
                            Modifier.fillMaxWidth().focusRing(row).clip(row).clickable { withPassword = !withPassword }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(withPassword, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.settings_file_with_password))
                        }
                        if (withPassword) Text(stringResource(R.string.settings_file_password_warning), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { saveDialog = false; startSave() }, Modifier.initialFocus().focusRing(shape)) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }, Modifier.focusRing(shape)) { Text(stringResource(R.string.cancel)) } },
        )
    }
    pending?.let { b ->
        ConfirmDialog(
            stringResource(R.string.settings_file_replace_title), stringResource(R.string.settings_file_replace_text),
            stringResource(R.string.settings_file_replace),
            onConfirm = {
                scope.launch {
                    try {
                        repo.restore(b)
                        toast(ctx.inLanguage(repo.settings.language).getString(R.string.settings_file_loaded)) // already in the file's language
                        onRestored(b)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = errorText(ctx, e)
                    }
                }
            },
        ) { pending = null }
    }
    failure?.let { text ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(stringResource(R.string.settings_file_load_failed)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { failure = null }, Modifier.initialFocus().focusRing(shape)) { Text(stringResource(R.string.close)) }
            },
        )
    }
    downloads?.let { list ->
        ChoiceDialog<Pair<String, Uri>?>(
            title = stringResource(R.string.settings_file_downloads),
            options = list,
            selected = null,
            label = { it?.first.orEmpty() },
            onSelect = { f -> downloads = null; if (f != null) read { ctx.contentResolver.openInputStream(f.second) } },
            onDismiss = { downloads = null },
        )
    }
    return SettingsFileActions(save = { withPassword = false; saveDialog = true }, load = ::startLoad)
}

private const val MIME = "application/octet-stream"

/** The name the user gave the file in the document window. */
private fun displayName(ctx: Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

/** Only asked on Android 6–9 (maxSdkVersion 28 in the manifest). */
private const val STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE

private fun appVersion(ctx: Context): String =
    @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()

private fun downloadsDir(): File = @Suppress("DEPRECATION") Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

/** Without a document window: a new file in the public Downloads. */
private fun createInDownloads(ctx: Context, name: String): OutputStream? =
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
        }
        ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let(ctx.contentResolver::openOutputStream)
    } else File(downloadsDir().apply { mkdirs() }, name).outputStream()

/**
 * Settings files in Downloads, newest first. ponytail: Android 11+ lists only the files this installation wrote
 * (a reinstall can't see older ones without the document window); add a QR/network transfer (BAK-005) for those boxes.
 */
private fun listDownloads(ctx: Context): List<Pair<String, Uri>> =
    if (Build.VERSION.SDK_INT >= 29) {
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("%.$BACKUP_EXTENSION"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(1) to ContentUris.withAppendedId(uri, c.getLong(0))) }
        }.orEmpty()
    } else {
        downloadsDir().listFiles { f -> f.name.endsWith(".$BACKUP_EXTENSION") }.orEmpty()
            .sortedByDescending { it.lastModified() }.map { it.name to Uri.fromFile(it) }
    }
