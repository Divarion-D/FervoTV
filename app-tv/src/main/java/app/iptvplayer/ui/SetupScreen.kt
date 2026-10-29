package app.iptvplayer.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.iptvplayer.R
import app.iptvplayer.core.model.Source
import app.iptvplayer.initialFocus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    initial: Source?,
    onCancel: () -> Unit,
    onDone: (Source) -> Unit,
    onLoadFile: () -> Unit,
    /** BAK-011: the source came from a settings file without its password. */
    askPassword: Boolean = false,
) {
    val ctx = LocalContext.current
    val x = initial as? Source.Xtream
    var xtream by remember { mutableStateOf(x != null) }
    var url by remember { mutableStateOf((initial as? Source.M3u)?.url.orEmpty()) }
    var server by remember { mutableStateOf(x?.server.orEmpty()) }
    var user by remember { mutableStateOf(x?.user.orEmpty()) }
    var pass by remember { mutableStateOf(x?.pass.orEmpty()) }
    var hls by remember { mutableStateOf(x?.hls ?: false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep read access after restart; some providers don't support it, then it works until reboot.
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            url = uri.toString()
        }
    }

    BackHandler(initial != null, onCancel)

    val source: Source? = if (xtream) {
        if (server.isBlank() || user.isBlank()) null
        else Source.Xtream(withScheme(server.trim()), user.trim(), pass, hls)
    } else {
        url.trim().takeIf { it.isNotEmpty() }?.let { Source.M3u(withScheme(it)) }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (initial != null) {
            TopAppBar(
                title = { Text(stringResource(R.string.source_title)) },
                navigationIcon = { BackButton(onCancel) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        }
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (initial == null) {
                Icon(
                    painterResource(R.drawable.ic_live_tv), contentDescription = null,
                    modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.setup_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to "M3U / M3U8", true to "Xtream Codes").forEachIndexed { i, (isXtream, label) ->
                    val shape = SegmentedButtonDefaults.itemShape(i, 2)
                    SegmentedButton(
                        selected = xtream == isXtream,
                        onClick = { xtream = isXtream },
                        shape = shape,
                        icon = {
                            SegmentedButtonDefaults.Icon(xtream == isXtream, inactiveContent = {
                                Icon(
                                    painterResource(if (isXtream) R.drawable.ic_dns else R.drawable.ic_link),
                                    contentDescription = null,
                                    modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                                )
                            })
                        },
                        modifier = (if (i == 0 && !askPassword) Modifier.initialFocus() else Modifier).focusRing(shape),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primary,
                            activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) { Text(label) }
                }
            }

            if (askPassword) Text(stringResource(R.string.password_needed), color = MaterialTheme.colorScheme.primary)
            if (xtream) {
                Field(server, { server = it }, stringResource(R.string.field_server), R.drawable.ic_dns, placeholder = "http://host:port")
                Field(user, { user = it }, stringResource(R.string.field_login), R.drawable.ic_person)
                Field(
                    pass, { pass = it }, stringResource(R.string.field_password), R.drawable.ic_key, password = true,
                    modifier = if (askPassword) Modifier.initialFocus() else Modifier,
                )
                SettingItem(
                    R.drawable.ic_tv, stringResource(R.string.xtream_hls), stringResource(R.string.xtream_hls_summary),
                    trailing = { Switch(hls, onCheckedChange = null) },
                ) { hls = !hls }
            } else {
                Field(url, { url = it }, stringResource(R.string.field_playlist_url), R.drawable.ic_link, placeholder = "http://…/playlist.m3u")
                OutlinedButton(
                    onClick = { pickFile.launch(arrayOf("*/*")) },
                    modifier = Modifier.focusRing(CircleShape),
                ) { IconLabel(R.drawable.ic_folder_open, stringResource(R.string.choose_file)) }
            }

            Button(
                onClick = { source?.let(onDone) },
                enabled = source != null,
                modifier = Modifier.fillMaxWidth().height(52.dp).focusRing(CircleShape),
            ) { IconLabel(R.drawable.ic_playlist_play, stringResource(R.string.load)) }

            // BAK-011: a new install takes everything from a settings file instead.
            if (initial == null) OutlinedButton(
                onClick = onLoadFile,
                modifier = Modifier.fillMaxWidth().focusRing(CircleShape),
            ) { IconLabel(R.drawable.ic_restart_alt, stringResource(R.string.settings_file_load)) }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
) = OutlinedTextField(
    value, onChange, modifier.fillMaxWidth(),
    label = { Text(label) },
    placeholder = placeholder?.let { { Text(it) } },
    leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
    singleLine = true,
    shape = RoundedCornerShape(12.dp),
    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
)

private fun withScheme(s: String) = if ("://" in s) s else "http://$s"
