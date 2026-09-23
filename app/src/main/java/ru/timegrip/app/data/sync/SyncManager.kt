package ru.timegrip.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.timegrip.app.data.local.OutboxCounts
import ru.timegrip.app.data.local.OutboxDao
import ru.timegrip.app.data.prefs.SessionStore
import ru.timegrip.app.data.remote.ApiException
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
    val isOnline: Boolean = false,
    val isSyncing: Boolean = false,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val lastSyncAt: Instant? = null,
    val problem: SyncProblem? = null,
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
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val queued = AtomicBoolean(false)
    private val requestedRange = AtomicReference<DateRange?>(null)
    private val recentRangePulls = mutableMapOf<DateRange, Long>()

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val online = MutableStateFlow(isNetworkAvailable())
    private val syncing = MutableStateFlow(false)
    private val lastSyncAt = MutableStateFlow<Instant?>(null)
    private val problem = MutableStateFlow<SyncProblem?>(null)

    val isOnline: StateFlow<Boolean> = online.asStateFlow()

    val status: StateFlow<SyncStatus> = combine(
        online,
        syncing,
        outboxDao.observeCounts(),
        lastSyncAt,
        problem,
    ) { isOnline, isSyncing, counts: OutboxCounts, last, currentProblem ->
        SyncStatus(
            isOnline = isOnline,
            isSyncing = isSyncing,
            pendingCount = counts.total - counts.failed,
            failedCount = counts.failed,
            lastSyncAt = last,
            problem = currentProblem,
        )
    }.stateIn(scope, SharingStarted.Eagerly, SyncStatus(isOnline = online.value))

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val wasOnline = online.value
                online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (!wasOnline && online.value) requestSync()
            }

            override fun onLost(network: Network) {
                online.value = false
            }
        })
    }

    /**
     * Asks for a sync soon. [range] additionally refreshes the entries of a
     * period the user is looking at (the regular sync covers recent months).
     */
    fun requestSync(range: DateRange? = null) {
        if (!canSync()) return
        if (range != null) requestedRange.set(range)
        SyncWorker.enqueue(context)
        if (!online.value) return
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
            lastSyncAt.value = Instant.now()
            problem.value = null
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            problem.value = classify(error)
            false
        } finally {
            syncing.value = false
        }
    }

    fun clearState() {
        lastSyncAt.value = null
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
        error is ApiException && error.status == 401 -> SyncProblem.Network
        error is ApiException -> SyncProblem.Server(error)
        else -> SyncProblem.Unexpected(error)
    }

    private fun isNetworkAvailable(): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val RANGE_REFRESH_INTERVAL_MS = 60_000L
    }
}
