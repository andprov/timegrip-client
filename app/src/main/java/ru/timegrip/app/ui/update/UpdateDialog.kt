package ru.timegrip.app.ui.update

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.timegrip.app.BuildConfig
import ru.timegrip.app.R
import ru.timegrip.app.data.update.AppUpdate
import ru.timegrip.app.data.update.UpdateState
import ru.timegrip.app.ui.common.FullScreenDialog
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

    // "What's new" replaces the offer while it is open; closing it brings the offer back.
    var notesOpen by rememberSaveable { mutableStateOf(false) }

    if (hidden) return
    when (val s = state) {
        UpdateState.None -> Unit
        is UpdateState.Available -> if (notesOpen) {
            ReleaseNotesWindow(
                s.update,
                onDismiss = { notesOpen = false },
                onDownload = {
                    notesOpen = false
                    updater.download()
                },
            )
        } else {
            UpdateOfferDialog(s.update, onShowNotes = { notesOpen = true }, onDownload = updater::download, onDismiss = updater::dismiss)
        }
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

/** The release notes in full, as GitHub shows them, with the update button below. */
@Composable
private fun ReleaseNotesWindow(update: AppUpdate, onDismiss: () -> Unit, onDownload: () -> Unit) {
    val blocks = remember(update.notes) {
        // The window's title already says what the top heading ("What's Changed") would.
        val parsed = parseMarkdown(update.notes)
        val top = parsed.firstOrNull() as? MdBlock.Heading
        if (top != null && top.level <= 2) parsed.drop(1) else parsed
    }
    FullScreenDialog(
        title = stringResource(R.string.update_notes_title, update.version),
        actionLabel = stringResource(R.string.update_download),
        onDismiss = onDismiss,
        onAction = onDownload,
    ) {
        MarkdownText(blocks)
    }
}

/** The offer itself; the notes open in their own window from the link under the message. */
@Composable
private fun UpdateOfferDialog(update: AppUpdate, onShowNotes: () -> Unit, onDownload: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available_title, update.version)) },
        text = {
            Column {
                Text(stringResource(R.string.update_available_message, BuildConfig.VERSION_NAME))
                if (update.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onShowNotes, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.update_whats_new))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text(stringResource(R.string.update_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) } },
    )
}
