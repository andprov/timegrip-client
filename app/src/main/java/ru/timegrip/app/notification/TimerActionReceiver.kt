package ru.timegrip.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import ru.timegrip.app.TimeGripApplication

/** Handles the Stop action of the running-timer notification. */
class TimerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        val container = (context.applicationContext as TimeGripApplication).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.timerRepository.stop()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP = "ru.timegrip.app.action.STOP_TIMER"
    }
}
