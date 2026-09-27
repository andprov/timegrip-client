package ru.timegrip.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.timegrip.app.data.local.OutboxCounts
import ru.timegrip.app.data.local.OutboxDao
import ru.timegrip.app.data.local.OutboxState
import ru.timegrip.app.data.prefs.SessionStore
import ru.timegrip.app.data.remote.ApiException
import ru.timegrip.app.data.remote.ServerReachability
import ru.timegrip.app.domain.DateRange
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed interface SyncProblem {
    data object Network : SyncProblem
    data object SessionExpired : SyncProblem
    data class Server(val error: ApiException) : SyncProblem
    data class Unexpected(val error: Throwable) : SyncProblem
}

data class SyncStatus(
    /** The phone has a network at all. */
    val networkAvailable: Boolean = false,
    /** The network is there and our server answers on it. */
    val isOnline: Boolean = false,
    val isSyncing: Boolean = false,
    /** The server looked down and is being asked whether it is back. */
    val isChecking: Boolean = false,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    /** When the last sync completed successfully. */
    val lastSyncAt: Instant? = null,
    /** Why the last sync failed; null once one succeeds. */
    val problem: SyncProblem? = null,
    /** When the sync that set [problem] failed. */
    val problemAt: Instant? = null,
)

/**
 * Decides when to sync and makes sure only one sync runs at a time. Changes
 * are pushed as soon as they are made while online; otherwise WorkManager
 * (see [SyncWorker]) delivers them once the network is back, even if the app
 * has been closed. The server's data is only downloaded while the app is on
 * screen: a closed app spends no battery and no traffic on it.
 */
class SyncManager(
    private val context: Context,
    private val engine: SyncEngine,
    private val sessionStore: SessionStore,
    private val outboxDao: OutboxDao,
    private val reachability: ServerReachability,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val queued = AtomicBoolean(false)
    private val pullWanted = AtomicBoolean(false)
    private val requestedRange = AtomicReference<DateRange?>(null)
    private val recentRangePulls = mutableMapOf<DateRange, Long>()

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val network = MutableStateFlow(isNetworkAvailable())
    private val syncing = MutableStateFlow(false)
    private val lastSyncAt = MutableStateFlow(sessionStore.lastSyncAt)
    private val problem = MutableStateFlow<Pair<SyncProblem, Instant>?>(null)
    private var reconnectJob: Job? = null

    /** When the recent window was last pulled successfully ([SystemClock.elapsedRealtime]). */
    @Volatile
    private var lastPullAt: Long? = null

    /**
     * Online means our server answers, not that the phone has a network: on a
     * weak signal the network is there and requests still time out. Until the
     * first request says otherwise, a network counts as online.
     */
    val isOnline: StateFlow<Boolean> = combine(network, reachability.reachable) { hasNetwork, reachable ->
        hasNetwork && reachable != false
    }.stateIn(scope, SharingStarted.Eagerly, network.value)

    val status: StateFlow<SyncStatus> = combine(
        combine(network, isOnline, ::Pair),
        combine(syncing, reachability.probing, ::Pair),
        outboxDao.observeCounts(),
        lastSyncAt,
        problem,
    ) { (hasNetwork, isOnline), (isSyncing, isChecking), counts: OutboxCounts, last, currentProblem ->
        SyncStatus(
            networkAvailable = hasNetwork,
            isOnline = isOnline,
            isSyncing = isSyncing,
            isChecking = isChecking,
            pendingCount = counts.total - counts.failed,
            failedCount = counts.failed,
            lastSyncAt = last,
            problem = currentProblem?.first,
            problemAt = currentProblem?.second,
        )
    }.stateIn(scope, SharingStarted.Eagerly, SyncStatus(networkAvailable = network.value, isOnline = network.value))

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val hadNetwork = this@SyncManager.network.value
                this@SyncManager.network.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (!this@SyncManager.network.value) {
                    reconnectJob?.cancel()
                } else if (!hadNetwork) {
                    // A new network: what the server did on the old one says nothing.
                    reachability.reset()
                    // A weak signal drops and comes back every few seconds; sync only
                    // once the connection has held for a while.
                    reconnectJob?.cancel()
                    reconnectJob = scope.launch {
                        delay(RECONNECT_SETTLE_MS)
                        if (this@SyncManager.network.value) requestSync()
                    }
                }
            }

            override fun onLost(network: Network) {
                this@SyncManager.network.value = false
                reconnectJob?.cancel()
            }
        })

        // The server came back (a probe or any other request got through): send what waits.
        scope.launch {
            var previous = reachability.reachable.value
            reachability.reachable.collect { reachable ->
                if (previous == false && reachable == true) requestSync()
                previous = reachable
            }
        }

        // While the app is open and the server is unreachable, knock now and then,
        // backing off, so the status recovers without waiting for WorkManager.
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
        scope.launch {
            combine(network, reachability.reachable, foreground) { hasNetwork, reachable, visible ->
                hasNetwork && reachable == false && visible
            }.distinctUntilChanged().collectLatest { needed ->
                if (!needed) return@collectLatest
                var wait = PROBE_FIRST_DELAY_MS
                while (true) {
                    delay(wait)
                    if (!mutex.isLocked) reachability.probe()
                    wait = (wait * 2).coerceAtMost(PROBE_MAX_DELAY_MS)
                }
            }
        }

        // Leaving the screen with changes not sent yet: from now on WorkManager
        // delivers them, once the network and the server are there.
        scope.launch {
            foreground.distinctUntilChanged().collect { visible ->
                if (!visible && canSync() && outboxDao.getAll().any { it.state != OutboxState.FAILED }) {
                    SyncWorker.enqueue(context)
                }
            }
        }
    }

    /**
     * Asks for a sync soon: local changes go up, the server's data comes down.
     * In the background (the network came back, the server answered again)
     * only changes go up; coming to the foreground downloads anyway.
     */
    fun requestSync() = schedule(null, pull = isVisible())

    /**
     * Sends local changes soon. The server answers each with the saved record,
     * which replaces the local one, so nothing needs downloading afterwards.
     * On screen the app sends them itself, and knocks while the server is away;
     * in the background (Stop in the notification) WorkManager does, once the
     * network and the server are there. Never both: that sent a change twice.
     */
    fun requestPush() {
        if (!canSync()) return
        if (isVisible()) schedule(null, pull = false) else SyncWorker.enqueue(context)
    }

    private fun schedule(range: DateRange?, pull: Boolean) {
        if (!canSync()) return
        if (pull) pullWanted.set(true)
        if (range != null) requestedRange.set(range)
        // No network, or the server is known to be down: WorkManager and the
        // probes above take over, so nothing spins in vain.
        // Read the sources, not [isOnline]: it lags a moment behind them.
        if (!network.value || reachability.reachable.value == false) return
        if (queued.compareAndSet(false, true)) {
            scope.launch { runSync() }
        }
    }

    /**
     * Downloads the entries of a period shown on screen, at most once a minute
     * per period. The regular sync covers recent months, so only an older
     * period costs a request, and only for its own entries.
     */
    fun requestRangeRefresh(range: DateRange) {
        if (!isVisible()) return
        val now = System.currentTimeMillis()
        synchronized(recentRangePulls) {
            val last = recentRangePulls[range]
            if (last != null && now - last < RANGE_REFRESH_INTERVAL_MS) return
            recentRangePulls[range] = now
        }
        schedule(range, pull = false)
    }

    /**
     * Runs a sync now and waits for it; true when it completed. [force] (the
     * user asked for it) downloads even if a sync has just done so.
     */
    suspend fun syncNow(range: DateRange? = null, force: Boolean = false): Boolean {
        pullWanted.set(true)
        if (range != null) requestedRange.set(range)
        return runSync(force)
    }

    /** Sends local changes and waits; true when it completed. Never downloads (see [SyncWorker]). */
    suspend fun pushNow(): Boolean = runSync(pushOnly = true)

    /**
     * Opening the app asks for a sync from several places at once (the app
     * coming to the foreground, the worker, every screen's first range). They
     * queue on [mutex]; once one has downloaded, the others only push.
     */
    private suspend fun runSync(force: Boolean = false, pushOnly: Boolean = false): Boolean = mutex.withLock {
        queued.set(false)
        if (!canSync()) return false
        // A sync queued behind one that just found the server down would only fail
        // the same way; the probes ask for a new one once it answers. The user's
        // own request ([force]) and the worker (no probes in the background) try.
        if (!force && !pushOnly && reachability.reachable.value == false) return false
        // Downloads only happen on screen. A push-only run, or any run in the
        // background (the server came back after a failed sync), leaves a wanted
        // download to the next sync on screen.
        val mayPull = !pushOnly && isVisible()
        val range = if (mayPull) requestedRange.getAndSet(null) else null
        var wantPull = mayPull && pullWanted.getAndSet(false)
        syncing.value = true
        try {
            engine.push()
            if (mayPull) wantPull = engine.takePullNeeded() || wantPull
            val startedAt = SystemClock.elapsedRealtime()
            val fresh = !force && lastPullAt?.let { startedAt - it < PULL_FRESH_MS } == true
            val window = DateRange(LocalDate.now().minusMonths(1).withDayOfMonth(1), null)
            // Without a wanted download only a requested period comes down.
            val plan = if (wantPull || range != null) {
                pullPlan(sessionStore.fullSyncDone, fresh || !wantPull, window, range)
            } else {
                emptyList()
            }
            // The first pull for an account starts from an empty database: whatever
            // ran moments ago (before signing out and in again) counts for nothing.
            engine.pull(plan, refreshAccount = !fresh || !sessionStore.fullSyncDone)
            if (plan.any { it == window || it.isAll }) lastPullAt = startedAt
            if (DateRange.ALL in plan) sessionStore.fullSyncDone = true
            wantPull = false
            val now = Instant.now()
            lastSyncAt.value = now
            sessionStore.lastSyncAt = now
            problem.value = null
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            problem.value = classify(error) to Instant.now()
            false
        } finally {
            // Not downloaded after all: the next sync on screen does it.
            if (wantPull) pullWanted.set(true)
            syncing.value = false
        }
    }

    fun clearState() {
        lastSyncAt.value = null
        sessionStore.lastSyncAt = null
        lastPullAt = null
        problem.value = null
        synchronized(recentRangePulls) { recentRangePulls.clear() }
    }

    /**
     * The state itself, not its flow: the flow only catches up after the
     * observers of a lifecycle event ran, so during the app's own ON_START
     * (which asks for a sync) it would still say "in the background".
     */
    fun isVisible(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun canSync(): Boolean {
        val session = sessionStore.state.value
        return session.isSignedIn && session.user?.isActive == true
    }

    private fun classify(error: Throwable): SyncProblem = when {
        sessionStore.state.value.expired -> SyncProblem.SessionExpired
        error is IOException -> SyncProblem.Network
        error is ApiException && error.status in ServerReachability.SERVER_DOWN -> SyncProblem.Network
        error is ApiException && error.status == 401 -> SyncProblem.Network
        error is ApiException -> SyncProblem.Server(error)
        else -> SyncProblem.Unexpected(error)
    }

    private fun isNetworkAvailable(): Boolean =
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private companion object {
        const val RANGE_REFRESH_INTERVAL_MS = 60_000L
        const val RECONNECT_SETTLE_MS = 3_000L
        const val PROBE_FIRST_DELAY_MS = 10_000L
        const val PROBE_MAX_DELAY_MS = 60_000L
        const val PULL_FRESH_MS = 30_000L
    }
}

/**
 * The periods a sync downloads, [DateRange.ALL] meaning the whole history.
 * [fresh]: the recent [window] was pulled moments ago, so it is not asked for
 * again; a [requested] period inside it neither.
 */
internal fun pullPlan(fullSyncDone: Boolean, fresh: Boolean, window: DateRange, requested: DateRange?): List<DateRange> =
    when {
        !fullSyncDone || requested?.isAll == true -> listOf(DateRange.ALL)
        else -> buildList {
            if (!fresh) add(window)
            if (requested != null && requested.from?.isBefore(window.from) != false) add(requested)
        }
    }
