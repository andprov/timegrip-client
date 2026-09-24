package ru.timegrip.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors backend/src/timegrip/adapters/controllers/schemas.py.

@Serializable
data class TokenPairDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("time_format") val timeFormat: String,
    val locale: String,
)

@Serializable
data class SessionDto(
    val id: Long,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("ip_address") val ipAddress: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ResendCooldownDto(
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int,
)

@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val color: String,
    @SerialName("hourly_rate") val hourlyRate: String? = null,
    @SerialName("round_to_hour") val roundToHour: Boolean = false,
    val status: String,
)

/** Both `TimerData` and `TimerRunningData` (the latter lacks the end fields). */
@Serializable
data class TimerDto(
    val id: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("hourly_rate") val hourlyRate: String? = null,
    @SerialName("round_to_hour") val roundToHour: Boolean = false,
    @SerialName("billable_amount") val billableAmount: String? = null,
    @SerialName("project_id") val projectId: String,
)

@Serializable
data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    @SerialName("page_size") val pageSize: Int,
    val total: Int,
)

@Serializable
data class ErrorDto(
    val detail: kotlinx.serialization.json.JsonElement? = null,
    val code: String? = null,
    /** Pydantic's per-field list (`loc`, `type`, `msg`) next to a `validation_error` detail line. */
    val errors: kotlinx.serialization.json.JsonArray? = null,
)

// --- Request bodies ---

@Serializable
data class SignInBody(val email: String, val password: String)

@Serializable
data class SignUpBody(val email: String, val password: String, val locale: String)

@Serializable
data class RefreshBody(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class EmailBody(val email: String)

@Serializable
data class ResetPasswordBody(
    val email: String,
    val code: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class CodeBody(val code: String)

@Serializable
data class PasswordUpdateBody(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class EmailUpdateBody(
    val password: String,
    @SerialName("new_email") val newEmail: String,
)

@Serializable
data class TimeFormatBody(@SerialName("time_format") val timeFormat: String)

@Serializable
data class LocaleBody(val locale: String)

@Serializable
data class ProjectCreateBody(
    val name: String,
    val color: String,
    @SerialName("hourly_rate") val hourlyRate: String?,
    @SerialName("round_to_hour") val roundToHour: Boolean,
)

@Serializable
data class TimerStartBody(@SerialName("project_id") val projectId: String)

@Serializable
data class TimerCreateBody(
    @SerialName("project_id") val projectId: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
)
