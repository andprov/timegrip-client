package ru.timegrip.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
 * has been closed.
 */
class SyncManager(
    private val context: Context,
    private val engine: SyncEngine,
    private val sessionStore: SessionStore,
    outboxDao: OutboxDao,
    private val reachability: ServerReachability,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val queued = AtomicBoolean(false)
    private val requestedRange = AtomicReference<DateRange?>(null)
    private val recentRangePulls = mutableMapOf<DateRange, Long>()

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val network = MutableStateFlow(isNetworkAvailable())
    private val syncing = MutableStateFlow(false)
    private val lastSyncAt = MutableStateFlow(sessionStore.lastSyncAt)
    private val problem = MutableStateFlow<Pair<SyncProblem, Instant>?>(null)
    private var reconnectJob: Job? = null

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
    }

    /**
     * Asks for a sync soon. [range] additionally refreshes the entries of a
     * period the user is looking at (the regular sync covers recent months).
     */
    fun requestSync(range: DateRange? = null) {
        if (!canSync()) return
        if (range != null) requestedRange.set(range)
        SyncWorker.enqueue(context)
        // No network, or the server is known to be down: WorkManager and the
        // probes above take over, so nothing spins in vain.
        // Read the sources, not [isOnline]: it lags a moment behind them.
        if (!network.value || reachability.reachable.value == false) return
        if (queued.compareAndSet(false, true)) {
            scope.launch { runSync() }
        }
    }

    /** Refreshes a period shown on screen, at most once a minute per period. */
    fun requestRangeRefresh(range: DateRange) {
        val now = System.currentTimeMillis()
        synchronized(recentRangePulls) {
            val last = recentRangePulls[range]
            if (last != null && now - last < RANGE_REFRESH_INTERVAL_MS) return
            recentRangePulls[range] = now
        }
        requestSync(range)
    }

    /** Runs a sync now and waits for it; true when it completed. */
    suspend fun syncNow(range: DateRange? = null): Boolean {
        if (range != null) requestedRange.set(range)
        return runSync()
    }

    private suspend fun runSync(): Boolean = mutex.withLock {
        queued.set(false)
        if (!canSync()) return false
        val range = requestedRange.getAndSet(null)
        syncing.value = true
        try {
            engine.push()
            if (!sessionStore.fullSyncDone) {
                engine.pull(null, null)
                sessionStore.fullSyncDone = true
            } else {
                val window = DateRange(LocalDate.now().minusMonths(1).withDayOfMonth(1), null)
                engine.pull(window.startInstant, null)
                if (range != null && !range.isAll && range.from?.isBefore(window.from) != false) {
                    engine.pull(range.startInstant, range.endInstant)
                } else if (range != null && range.isAll) {
                    engine.pull(null, null)
                }
            }
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
            syncing.value = false
        }
    }

    fun clearState() {
        lastSyncAt.value = null
        sessionStore.lastSyncAt = null
        problem.value = null
        synchronized(recentRangePulls) { recentRangePulls.clear() }
    }

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
    }
}
