package ru.timegrip.app.data.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Header that keeps the bearer token (and the refresh-and-retry) off a request. */
const val NO_AUTH_HEADER = "X-TimeGrip-No-Auth"
private const val NO_AUTH = "$NO_AUTH_HEADER: true"

/** Marks a request that carries its own token: a 401 must not be retried with the stored one. */
const val EXPLICIT_AUTH_HEADER = "X-TimeGrip-Explicit-Auth"
private const val EXPLICIT_AUTH = "$EXPLICIT_AUTH_HEADER: true"

/** Paths are relative to the configured API base URL (`https://host/api/`). */
interface TimeGripApi {
    // --- auth ---

    @Headers(NO_AUTH)
    @POST("auth/signup")
    suspend fun signUp(@Body body: SignUpBody): UserDto

    @Headers(NO_AUTH)
    @POST("auth/signin")
    suspend fun signIn(@Body body: SignInBody): TokenPairDto

    @Headers(NO_AUTH)
    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshBody)

    @Headers(NO_AUTH)
    @POST("auth/password/forgot")
    suspend fun forgotPassword(@Body body: EmailBody)

    @Headers(NO_AUTH)
    @POST("auth/password/reset")
    suspend fun resetPassword(@Body body: ResetPasswordBody)

    // --- users ---

    @GET("users/me")
    suspend fun me(): UserDto

    /** Checks freshly issued tokens before they are stored. */
    @Headers(EXPLICIT_AUTH)
    @GET("users/me")
    suspend fun me(@Header("Authorization") authorization: String): UserDto

    @POST("users/me/activate")
    suspend fun activate(@Body body: CodeBody)

    @POST("users/me/activate/resend")
    suspend fun resendActivationCode()

    @GET("users/me/activate/resend-cooldown")
    suspend fun resendCooldown(): ResendCooldownDto

    @GET("users/me/sessions")
    suspend fun sessions(): List<SessionDto>

    @DELETE("users/me/sessions/{id}")
    suspend fun revokeSession(@Path("id") id: Long)

    @DELETE("users/me/sessions")
    suspend fun revokeAllSessions()

    @PATCH("users/me/password")
    suspend fun updatePassword(@Body body: PasswordUpdateBody): TokenPairDto

    @PATCH("users/me/email")
    suspend fun updateEmail(@Body body: EmailUpdateBody): UserDto

    @PATCH("users/me/time-format")
    suspend fun updateTimeFormat(@Body body: TimeFormatBody): UserDto

    @PATCH("users/me/locale")
    suspend fun updateLocale(@Body body: LocaleBody): UserDto

    @DELETE("users/me")
    suspend fun deleteMe()

    // --- projects ---

    @GET("projects")
    suspend fun projects(
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int,
    ): PageDto<ProjectDto>

    @GET("projects/{id}")
    suspend fun project(@Path("id") id: String): ProjectDto

    @POST("projects")
    suspend fun createProject(@Body body: ProjectCreateBody): ProjectDto

    /** A JSON object so a `null` hourly rate is sent explicitly (it clears the rate). */
    @PATCH("projects/{id}")
    suspend fun updateProject(@Path("id") id: String, @Body body: JsonObject): ProjectDto

    @DELETE("projects/{id}")
    suspend fun deleteProject(@Path("id") id: String)

    // --- timers ---

    @GET("timers")
    suspend fun timers(
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int,
        @Query("date_from") dateFrom: String?,
        @Query("date_to") dateTo: String?,
        @Query("include_archived_projects") includeArchivedProjects: Boolean,
    ): PageDto<TimerDto>

    /** `null` in the body when no timer is running, hence the raw element. */
    @GET("timers/running")
    suspend fun runningTimer(): JsonElement

    @GET("timers/{id}")
    suspend fun timer(@Path("id") id: String): TimerDto

    @POST("timers/start")
    suspend fun startTimer(@Body body: TimerStartBody): TimerDto

    @POST("timers/stop")
    suspend fun stopTimer(): TimerDto

    @POST("timers")
    suspend fun createTimer(@Body body: TimerCreateBody): TimerDto

    @PATCH("timers/{id}")
    suspend fun updateTimer(@Path("id") id: String, @Body body: JsonObject): TimerDto

    @DELETE("timers/{id}")
    suspend fun deleteTimer(@Path("id") id: String)

    companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
