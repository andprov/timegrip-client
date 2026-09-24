package ru.timegrip.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.timegrip.app.data.prefs.SettingsStore
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Whether our own API server answers, as opposed to whether the phone has a
 * network. Every API call reports its outcome through [interceptor]; [probe]
 * asks the server directly when nothing else is talking to it.
 */
class ServerReachability(private val settingsStore: SettingsStore) {
    private val state = MutableStateFlow<Boolean?>(null)

    /** true: the server answered the last request; false: it did not; null: not known yet. */
    val reachable: StateFlow<Boolean?> = state.asStateFlow()

    private val probingState = MutableStateFlow(false)

    /** A [probe] is waiting for the server's answer. */
    val probing: StateFlow<Boolean> = probingState.asStateFlow()

    /** Forgets the last outcome, e.g. when the phone switches networks. */
    fun reset() {
        state.value = null
    }

    /**
     * Any answer from the API counts, errors included: a 401 or a 422 still means
     * the server is up. Only nginx's "backend is down" answers and a request that
     * never got an answer count against it. Cancelled calls say nothing.
     */
    val interceptor = Interceptor { chain ->
        val response = try {
            chain.proceed(chain.request())
        } catch (error: IOException) {
            if (!chain.call().isCanceled()) state.value = false
            throw error
        }
        state.value = response.code !in SERVER_DOWN
        response
    }

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(PROBE_TIMEOUT_S * 2, TimeUnit.SECONDS)
        .build()

    /**
     * Asks the API for the current user without credentials: the backend itself
     * answers 401, which proves it is up. True when the server answered.
     */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(HttpClientFactory.resolve(settingsStore.apiBaseUrl.value, "users/me"))
            .build()
        probingState.value = true
        val answered = try {
            probeClient.newCall(request).execute().use { it.code !in SERVER_DOWN }
        } catch (_: IOException) {
            false
        } finally {
            probingState.value = false
        }
        state.value = answered
        answered
    }

    companion object {
        /** Bad gateway, unavailable, gateway timeout: nginx is up, the API behind it is not. */
        val SERVER_DOWN = 502..504
        private const val PROBE_TIMEOUT_S = 5L
    }
}
