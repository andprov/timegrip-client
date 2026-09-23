package ru.timegrip.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ru.timegrip.app.data.sync.SyncWorker

class TimeGripApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.runningTimerNotifier.start()

        if (container.sessionStore.state.value.isSignedIn) SyncWorker.schedulePeriodic(this)

        // Fresh data whenever the app comes to the foreground.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.syncManager.requestSync()
            }
        })
    }
}
