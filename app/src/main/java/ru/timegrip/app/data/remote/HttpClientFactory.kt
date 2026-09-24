package ru.timegrip.app.data.remote

import android.os.Build
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    fun createApi(sessionStore: SessionStore, settingsStore: SettingsStore, reachability: ServerReachability): TimeGripApi {
        val baseUrlInterceptor = BaseUrlInterceptor(settingsStore)
        val userAgentInterceptor = UserAgentInterceptor()

        // A bare client for the token refresh itself, so it can never recurse
        // into the authenticator below.
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor(reachability.interceptor)
            .addInterceptor(userAgentInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val client = OkHttpClient.Builder()
            // First, so it sees the final outcome of a call, after token refreshes and retries.
            .addInterceptor(reachability.interceptor)
            .addInterceptor(baseUrlInterceptor)
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(AuthInterceptor(sessionStore))
            .authenticator(TokenAuthenticator(sessionStore, settingsStore, refreshClient))
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

private class AuthInterceptor(private val sessionStore: SessionStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(NO_AUTH_HEADER) != null) {
            return chain.proceed(request.newBuilder().removeHeader(NO_AUTH_HEADER).build())
        }
        if (request.header("Authorization") != null) return chain.proceed(request)
        val token = sessionStore.accessToken ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * Refreshes the session on a 401 and replays the request once. Refreshes are
 * serialized: the backend rotates refresh tokens and treats a reused one as
 * stolen, so two parallel refreshes would revoke the session (the web client
 * guards against the same race in api/client.ts).
 */
private class TokenAuthenticator(
    private val sessionStore: SessionStore,
    private val settingsStore: SettingsStore,
    private val refreshClient: OkHttpClient,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val failedAuth = response.request.header("Authorization") ?: return null
        if (response.priorResponse != null || response.request.header(EXPLICIT_AUTH_HEADER) != null) return null

        synchronized(lock) {
            val current = sessionStore.accessToken
            // Another request already refreshed while this one was in flight.
            if (current != null && "Bearer $current" != failedAuth) {
                return response.request.newBuilder().header("Authorization", "Bearer $current").build()
            }
            val refreshToken = sessionStore.refreshToken ?: return null
            val tokens = refresh(refreshToken) ?: return null
            sessionStore.saveTokens(tokens.accessToken, tokens.refreshToken)
            return response.request.newBuilder().header("Authorization", "Bearer ${tokens.accessToken}").build()
        }
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
