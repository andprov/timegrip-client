package ru.timegrip.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.timegrip.app.data.local.AppDatabase
import ru.timegrip.app.data.prefs.SessionStore
import ru.timegrip.app.data.prefs.SettingsStore
import ru.timegrip.app.data.remote.HttpClientFactory
import ru.timegrip.app.data.remote.ServerReachability
import ru.timegrip.app.data.repository.AccountRepository
import ru.timegrip.app.data.repository.AuthRepository
import ru.timegrip.app.data.repository.ProjectRepository
import ru.timegrip.app.data.repository.SyncIssuesRepository
import ru.timegrip.app.data.repository.TimerRepository
import ru.timegrip.app.data.sync.Outbox
import ru.timegrip.app.data.sync.SyncEngine
import ru.timegrip.app.data.sync.SyncManager
import ru.timegrip.app.data.update.AppUpdater
import ru.timegrip.app.notification.RunningTimerNotifier

/** Manual dependency injection: every long-lived object of the app, wired once. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val json = HttpClientFactory.json
    val sessionStore = SessionStore(context)
    val settingsStore = SettingsStore(context)
    private val serverReachability = ServerReachability(settingsStore)
    private val api = HttpClientFactory.createApi(sessionStore, settingsStore, serverReachability)
    private val database = AppDatabase.create(context)
    private val outbox = Outbox(database.outboxDao(), json)

    private val syncEngine = SyncEngine(database, api, sessionStore, json)
    val syncManager = SyncManager(context, syncEngine, sessionStore, database.outboxDao(), serverReachability, appScope)

    val projectRepository = ProjectRepository(database, outbox, syncManager)
    val timerRepository = TimerRepository(database, outbox, syncManager)
    val authRepository = AuthRepository(context, api, json, database, sessionStore, syncEngine, syncManager)
    val accountRepository = AccountRepository(
        api,
        json,
        database,
        outbox,
        sessionStore,
        syncEngine,
        syncManager,
        authRepository,
    )
    val syncIssuesRepository = SyncIssuesRepository(database, syncEngine, syncManager)

    val runningTimerNotifier = RunningTimerNotifier(context, timerRepository, projectRepository, appScope)

    val appUpdater = AppUpdater(context, appScope)
}
