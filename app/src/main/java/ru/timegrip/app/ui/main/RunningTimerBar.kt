package ru.timegrip.app.ui.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.timegrip.app.R
import ru.timegrip.app.TimeGripApplication
import ru.timegrip.app.data.repository.ProjectRepository
import ru.timegrip.app.data.repository.TimerRepository
import ru.timegrip.app.domain.Project
import ru.timegrip.app.domain.TimeEntry
import ru.timegrip.app.domain.formatElapsed
import ru.timegrip.app.ui.common.ColorDot
import ru.timegrip.app.ui.common.ProjectPickerWindow
import ru.timegrip.app.ui.common.UiMessage
import ru.timegrip.app.ui.common.appViewModel
import ru.timegrip.app.ui.common.rememberNow
import ru.timegrip.app.ui.common.text
import ru.timegrip.app.ui.theme.LocalExtraColors

data class TimerBarState(
    val running: TimeEntry? = null,
    val runningProject: Project? = null,
    val activeProjects: List<Project> = emptyList(),
)

class TimerBarViewModel(
    private val timerRepository: TimerRepository,
    projectRepository: ProjectRepository,
) : ViewModel() {
    val state: StateFlow<TimerBarState> = combine(
        timerRepository.observeRunning(),
        projectRepository.observeProjects(),
    ) { running, projects ->
        TimerBarState(
            running = running,
            runningProject = running?.let { entry -> projects.firstOrNull { it.id == entry.projectId } },
            activeProjects = projects.filter { it.isActive },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimerBarState())

    private val _errors = Channel<UiMessage>(Channel.BUFFERED)
    val errors = _errors.receiveAsFlow()

    var busy by mutableStateOf(false)
        private set

    fun start(projectId: String) = act { timerRepository.start(projectId) }

    fun stop() = act { timerRepository.stop() }

    private fun act(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                _errors.send(UiMessage.of(failure))
            } finally {
                busy = false
            }
        }
    }
}

/** Web: components/layout/RunningTimerBar.tsx. Works offline: the timer lives on the device. */
@Composable
fun RunningTimerBar() {
    val vm = appViewModel { TimerBarViewModel(it.timerRepository, it.projectRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }

    val errorText = remember { mutableStateOf<UiMessage?>(null) }
    LaunchedEffect(vm) { vm.errors.collect { errorText.value = it } }
    errorText.value?.let { message ->
        val text = message.text()
        LaunchedEffect(message) {
            snackbar.showSnackbar(text)
            errorText.value = null
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) (context.applicationContext as TimeGripApplication).container.runningTimerNotifier.refresh()
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        val running = state.running
        if (running != null) {
            val now = rememberNow()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = BAR_HEIGHT)
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorDot(state.runningProject?.color ?: "#9E9E9E", size = 12.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.runningProject?.name.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatElapsed(running.durationSeconds(now)),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold,
                        color = LocalExtraColors.current.running,
                    )
                }
                Button(
                    onClick = vm::stop,
                    enabled = !vm.busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalExtraColors.current.running,
                        contentColor = LocalExtraColors.current.onRunning,
                    ),
                    modifier = Modifier.height(40.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.stop))
                }
                SyncStatusButton()
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = BAR_HEIGHT)
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        picking = true
                    },
                    enabled = state.activeProjects.isNotEmpty() && !vm.busy,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.start_timer))
                }
                SyncStatusButton()
            }
        }
    }

    if (picking) {
        ProjectPickerWindow(
            projects = state.activeProjects,
            title = stringResource(R.string.start_timer),
            enabled = !vm.busy,
            onSelect = vm::start,
            onDismiss = { picking = false },
        )
    }
}

/** Both states of the bar are this tall, so the sync icon never shifts when a timer starts. */
private val BAR_HEIGHT = 52.dp
