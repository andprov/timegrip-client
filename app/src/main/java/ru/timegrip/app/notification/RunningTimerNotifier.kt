package ru.timegrip.app.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.timegrip.app.MainActivity
import ru.timegrip.app.R
import ru.timegrip.app.data.repository.ProjectRepository
import ru.timegrip.app.data.repository.TimerRepository

/**
 * Keeps an ongoing notification with a live chronometer and a Stop action
 * while a timer runs, so it can be stopped without opening the app.
 */
class RunningTimerNotifier(
    private val context: Context,
    private val timerRepository: TimerRepository,
    private val projectRepository: ProjectRepository,
    private val scope: CoroutineScope,
) {
    private data class Shown(val timerId: String, val startMillis: Long, val projectName: String, val color: String)

    @Volatile
    private var current: Shown? = null

    fun start() {
        createChannel()
        scope.launch {
            combine(timerRepository.observeRunning(), projectRepository.observeProjects()) { running, projects ->
                running?.let { timer ->
                    val project = projects.firstOrNull { it.id == timer.projectId }
                    Shown(timer.id, timer.start.toEpochMilli(), project?.name.orEmpty(), project?.color ?: DEFAULT_COLOR_HEX)
                }
            }.distinctUntilChanged().collect { shown ->
                current = shown
                if (shown == null) cancel() else show(shown)
            }
        }
    }

    /** Re-posts the notification, e.g. right after the permission was granted. */
    fun refresh() {
        current?.let(::show)
    }

    private fun show(shown: Shown) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, TimerActionReceiver::class.java).setAction(TimerActionReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setColor(runCatching { shown.color.toColorInt() }.getOrDefault(DEFAULT_COLOR))
            .setContentTitle(shown.projectName.ifEmpty { context.getString(R.string.app_name) })
            .setContentText(context.getString(R.string.notification_timer_running))
            .setWhen(shown.startMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(openApp)
            .addAction(0, context.getString(R.string.stop), stop)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_timer),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "running_timer"
        const val NOTIFICATION_ID = 1

        // The app's accent, also used when a project color cannot be parsed.
        const val DEFAULT_COLOR_HEX = "#4F46E5"
        val DEFAULT_COLOR = DEFAULT_COLOR_HEX.toColorInt()
    }
}
