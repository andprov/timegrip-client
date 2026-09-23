package ru.timegrip.app.ui.update

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.timegrip.app.BuildConfig
import ru.timegrip.app.R
import ru.timegrip.app.data.update.UpdateState
import ru.timegrip.app.ui.common.LocalAppContainer
import java.io.File

/** Offers the update found on GitHub, shows the download and opens the system installer. */
@Composable
fun UpdateDialog() {
    val updater = LocalAppContainer.current.appUpdater
    val state by updater.state.collectAsStateWithLifecycle()
    val hidden by updater.dialogHidden.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun install(apk: File) {
        try {
            context.startActivity(updater.installIntent(apk))
        } catch (_: ActivityNotFoundException) {
            updater.dismiss()
        }
    }

    // The installer opens by itself once the download is done; the dialog stays behind it
    // in case the user backs out of the installer.
    val ready = state as? UpdateState.ReadyToInstall
    LaunchedEffect(ready) { ready?.let { install(it.apk) } }

    if (hidden) return
    when (val s = state) {
        UpdateState.None -> Unit
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text(stringResource(R.string.update_available_title, s.update.version)) },
            text = {
                Column {
                    Text(stringResource(R.string.update_available_message, BuildConfig.VERSION_NAME))
                    if (s.update.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            s.update.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = updater::download) { Text(stringResource(R.string.update_download)) } },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text(stringResource(R.string.update_later)) } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = updater::hideDialog,
            title = { Text(stringResource(R.string.update_downloading, s.update.version)) },
            text = {
                if (s.progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = updater::hideDialog) { Text(stringResource(R.string.update_in_background)) } },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text(stringResource(R.string.cancel)) } },
        )
        is UpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text(stringResource(R.string.update_ready_title, s.update.version)) },
            text = { Text(stringResource(R.string.update_ready_message)) },
            confirmButton = { TextButton(onClick = { install(s.apk) }) { Text(stringResource(R.string.update_install)) } },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text(stringResource(R.string.update_later)) } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(stringResource(R.string.update_failed_message)) },
            confirmButton = { TextButton(onClick = updater::download) { Text(stringResource(R.string.retry)) } },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text(stringResource(R.string.close)) } },
        )
    }
}
