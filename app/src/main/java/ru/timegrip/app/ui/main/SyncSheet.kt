package ru.timegrip.app.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.timegrip.app.R
import ru.timegrip.app.data.repository.SyncIssue
import ru.timegrip.app.data.repository.SyncIssuesRepository
import ru.timegrip.app.data.sync.OpType
import ru.timegrip.app.data.sync.SyncManager
import ru.timegrip.app.data.sync.SyncProblem
import ru.timegrip.app.data.sync.SyncStatus
import ru.timegrip.app.domain.TimeFormat
import ru.timegrip.app.domain.formatDateTime
import ru.timegrip.app.domain.formatTime
import ru.timegrip.app.domain.toLocalDate
import ru.timegrip.app.ui.common.ColorDot
import ru.timegrip.app.ui.common.LocalAppContainer
import ru.timegrip.app.ui.common.UiMessage
import ru.timegrip.app.ui.common.appViewModel
import ru.timegrip.app.ui.common.syncErrorMessage
import ru.timegrip.app.ui.common.text

class SyncViewModel(
    private val syncManager: SyncManager,
    private val issuesRepository: SyncIssuesRepository,
) : ViewModel() {
    val status: StateFlow<SyncStatus> = syncManager.status

    val issues: StateFlow<List<SyncIssue>> = issuesRepository.observeIssues()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun syncNow() {
        viewModelScope.launch { syncManager.syncNow() }
    }

    fun retry(entityId: String) {
        viewModelScope.launch { issuesRepository.retry(entityId) }
    }

    fun discard(entityId: String) {
        viewModelScope.launch { issuesRepository.discard(entityId) }
    }
}

/** Cloud icon next to the timer controls: offline, uploading, all synced, or rejected changes. */
@Composable
fun SyncStatusButton() {
    val status by LocalAppContainer.current.syncManager.status.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }

    val (icon: ImageVector, badge: Int) = when {
        status.failedCount > 0 -> Icons.Outlined.SyncProblem to status.failedCount
        !status.isOnline -> Icons.Outlined.CloudOff to status.pendingCount
        status.isSyncing -> Icons.Outlined.Sync to 0
        status.pendingCount > 0 -> Icons.Outlined.CloudUpload to status.pendingCount
        else -> Icons.Outlined.CloudDone to 0
    }
    val rotation = if (status.isSyncing && icon == Icons.Outlined.Sync) {
        val transition = rememberInfiniteTransition(label = "sync")
        transition.animateFloat(
            initialValue = 360f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "sync-rotation",
        ).value
    } else {
        0f
    }

    IconButton(onClick = { open = true }) {
        BadgedBox(badge = {
            if (badge > 0) {
                Badge(
                    containerColor = if (status.failedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                ) { Text(badge.toString()) }
            }
        }) {
            Icon(
                icon,
                contentDescription = stringResource(R.string.sync_title),
                tint = if (status.failedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation),
            )
        }
    }
    if (open) SyncSheet(onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncSheet(onDismiss: () -> Unit) {
    val vm = appViewModel { SyncViewModel(it.syncManager, it.syncIssuesRepository) }
    val status by vm.status.collectAsStateWithLifecycle()
    val issues by vm.issues.collectAsStateWithLifecycle()
    val timeFormat = LocalAppContainer.current.sessionStore.user?.timeFormat ?: TimeFormat.H24

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleLarge)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when {
                            status.isSyncing -> stringResource(R.string.sync_syncing)
                            status.isOnline -> stringResource(R.string.sync_online)
                            else -> stringResource(R.string.sync_offline)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        status.lastSyncAt?.let { stringResource(R.string.sync_last, formatDateTime(it, timeFormat)) }
                            ?: stringResource(R.string.sync_never),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (status.pendingCount > 0) {
                            pluralStringResource(R.plurals.sync_pending, status.pendingCount, status.pendingCount)
                        } else {
                            stringResource(R.string.sync_all_synced)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    status.problem?.let { problem ->
                        Text(
                            problemText(problem),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = vm::syncNow,
                    enabled = status.isOnline && !status.isSyncing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_now))
                }
            }
            if (issues.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.sync_failed_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.sync_failed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(issues, key = { "${it.entityId}-${it.opType}" }) { issue ->
                    IssueCard(issue, timeFormat, onRetry = { vm.retry(issue.entityId) }, onDiscard = { vm.discard(issue.entityId) })
                }
            }
        }
    }
}

@Composable
private fun IssueCard(issue: SyncIssue, timeFormat: TimeFormat, onRetry: () -> Unit, onDiscard: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(opLabel(issue.opType)), style = MaterialTheme.typography.labelLarge)
            if (issue.projectName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    issue.projectColor?.let { ColorDot(it, size = 10.dp) }
                    Spacer(Modifier.width(8.dp))
                    Text(issue.projectName, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (issue.start != null) {
                val end = issue.end
                val range = buildString {
                    append(formatDateTime(issue.start, timeFormat))
                    if (end != null) {
                        append(" – ")
                        append(
                            if (end.toLocalDate() == issue.start.toLocalDate()) {
                                formatTime(end, timeFormat)
                            } else {
                                formatDateTime(end, timeFormat)
                            },
                        )
                    }
                }
                Text(range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                syncErrorMessage(issue.errorCode, issue.errorMessage).text(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard)) }
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

private fun opLabel(type: String): Int = when (type) {
    OpType.PROJECT_CREATE -> R.string.op_project_create
    OpType.PROJECT_UPDATE -> R.string.op_project_update
    OpType.PROJECT_DELETE -> R.string.op_project_delete
    OpType.TIMER_START -> R.string.op_timer_start
    OpType.TIMER_STOP -> R.string.op_timer_stop
    OpType.TIMER_CREATE -> R.string.op_timer_create
    OpType.TIMER_UPDATE -> R.string.op_timer_update
    OpType.TIMER_DELETE -> R.string.op_timer_delete
    OpType.USER_TIME_FORMAT -> R.string.op_user_time_format
    OpType.USER_LOCALE -> R.string.op_user_locale
    else -> R.string.sync_title
}

@Composable
private fun problemText(problem: SyncProblem): String = when (problem) {
    SyncProblem.Network -> stringResource(R.string.sync_problem_network)
    SyncProblem.SessionExpired -> stringResource(R.string.sync_problem_session)
    is SyncProblem.Server -> stringResource(R.string.sync_problem_server, UiMessage.of(problem.error).text())
    is SyncProblem.Unexpected -> stringResource(R.string.sync_problem_server, problem.error.message.orEmpty())
}
