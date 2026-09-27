package ru.timegrip.app.data.remote

import android.os.Build
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.timegrip.app.BuildConfig
import ru.timegrip.app.data.prefs.SessionStore
import ru.timegrip.app.data.prefs.SettingsStore
import java.util.concurrent.TimeUnit

/**
 * Retrofit is built once against a placeholder host; [BaseUrlInterceptor]
 * points every request at the server chosen in settings, so changing the
 * server does not require rebuilding the client.
 */
private const val PLACEHOLDER_BASE_URL = "http://timegrip.invalid/"

object HttpClientFactory {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    fun createApi(
        sessionStore: SessionStore,
        settingsStore: SettingsStore,
        reachability: ServerReachability,
        serverClock: ServerClock,
    ): TimeGripApi {
        val baseUrlInterceptor = BaseUrlInterceptor(settingsStore)
        val userAgentInterceptor = UserAgentInterceptor()

        // A bare client for the token refresh itself, so it can never recurse
        // into the authenticator below.
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor(reachability.interceptor)
            .addInterceptor(serverClock.interceptor)
            .addInterceptor(userAgentInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val refresher = TokenRefresher(sessionStore, settingsStore, refreshClient)

        val client = OkHttpClient.Builder()
            // First, so it sees the final outcome of a call, after token refreshes and retries.
            .addInterceptor(reachability.interceptor)
            .addInterceptor(serverClock.interceptor)
            .addInterceptor(baseUrlInterceptor)
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(AuthInterceptor(sessionStore, refresher))
            .authenticator(TokenAuthenticator(refresher))
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
                }
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(TimeGripApi::class.java)
    }

    fun resolve(baseUrl: String, relative: String): HttpUrl =
        (baseUrl.trimEnd('/') + "/" + relative.trimStart('/')).toHttpUrl()
}

private class BaseUrlInterceptor(private val settingsStore: SettingsStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val relative = request.url.toString().removePrefix(PLACEHOLDER_BASE_URL)
        val url = HttpClientFactory.resolve(settingsStore.apiBaseUrl.value, relative)
        return chain.proceed(request.newBuilder().url(url).build())
    }
}

/** Shown in the account's session list instead of OkHttp's default agent. */
private class UserAgentInterceptor : Interceptor {
    private val userAgent =
        "TimeGrip-Android/${BuildConfig.VERSION_NAME} (${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE})"

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}

/**
 * Adds the stored token. One about to run out is refreshed first: sending it
 * would only earn a 401 and a second round trip (the token lives 15 minutes,
 * so that is the first request of nearly every app start).
 */
private class AuthInterceptor(
    private val sessionStore: SessionStore,
    private val refresher: TokenRefresher,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(NO_AUTH_HEADER) != null) {
            return chain.proceed(request.newBuilder().removeHeader(NO_AUTH_HEADER).build())
        }
        if (request.header("Authorization") != null) return chain.proceed(request)
        // No session (it expired, or the user signed out meanwhile): the server
        // would only answer 401, so the request does not go out.
        var token = sessionStore.accessToken ?: return sessionExpired(request)
        val expiresAt = sessionStore.accessExpiresAt
        if (expiresAt != null && System.currentTimeMillis() >= expiresAt - EXPIRY_MARGIN_MS) {
            // The server refused the refresh token: the session is over. Any other
            // failure (no network) sends the request as it is and the 401 path decides.
            token = refresher.refreshed(token) ?: sessionStore.accessToken ?: return sessionExpired(request)
        }
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }

    /** What the server would answer, without asking it. */
    private fun sessionExpired(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(401)
        .message("Session expired")
        .body("""{"detail":"Session expired","code":"session_expired"}""".toResponseBody("application/json".toMediaType()))
        .build()

    private companion object {
        const val EXPIRY_MARGIN_MS = 30_000L
    }
}

/** Refreshes the session on a 401 and replays the request once. */
private class TokenAuthenticator(private val refresher: TokenRefresher) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val failedAuth = response.request.header("Authorization") ?: return null
        if (response.priorResponse != null || response.request.header(EXPLICIT_AUTH_HEADER) != null) return null
        val token = refresher.refreshed(failedAuth.removePrefix("Bearer ")) ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer $token").build()
    }
}

/**
 * Trades the refresh token for new tokens. Refreshes are serialized: the
 * backend rotates refresh tokens and treats a reused one as stolen, so two
 * parallel refreshes would revoke the session (the web client guards against
 * the same race in api/client.ts).
 */
private class TokenRefresher(
    private val sessionStore: SessionStore,
    private val settingsStore: SettingsStore,
    private val refreshClient: OkHttpClient,
) {
    private val lock = Any()

    /** A token to use instead of [stale]; null when the session cannot be refreshed. */
    fun refreshed(stale: String): String? = synchronized(lock) {
        val current = sessionStore.accessToken
        // Another request already refreshed while this one waited.
        if (current != null && current != stale) return current
        val refreshToken = sessionStore.refreshToken ?: return null
        val tokens = refresh(refreshToken) ?: return null
        sessionStore.saveTokens(tokens.accessToken, tokens.refreshToken)
        tokens.accessToken
    }

    private fun refresh(refreshToken: String): TokenPairDto? {
        val json = HttpClientFactory.json
        val body = json.encodeToString(RefreshBody.serializer(), RefreshBody(refreshToken))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(HttpClientFactory.resolve(settingsStore.apiBaseUrl.value, "auth/refresh"))
            .post(body)
            .build()
        return try {
            refreshClient.newCall(request).execute().use { refreshResponse ->
                when {
                    refreshResponse.isSuccessful -> json.decodeFromString(
                        TokenPairDto.serializer(),
                        refreshResponse.body.string(),
                    )
                    // The refresh token itself was rejected: only signing in again helps.
                    refreshResponse.code in 400..499 && refreshResponse.code != 429 -> {
                        sessionStore.markExpired()
                        null
                    }
                    else -> null
                }
            }
        } catch (_: java.io.IOException) {
            null
        }
    }
}
