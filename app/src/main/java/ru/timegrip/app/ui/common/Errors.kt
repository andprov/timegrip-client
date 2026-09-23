package ru.timegrip.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.timegrip.app.R
import ru.timegrip.app.data.remote.ApiException
import ru.timegrip.app.domain.DomainException
import java.io.IOException

/** Translation of an API / domain error code (web: i18n `errors` namespace). */
@StringRes
fun errorRes(code: String?): Int? = when (code) {
    null -> null
    "access_denied" -> R.string.error_access_denied
    "activation_code_recently_sent" -> R.string.error_activation_code_recently_sent
    "invalid_activation_code" -> R.string.error_invalid_activation_code
    "invalid_credentials" -> R.string.error_invalid_credentials
    "invalid_current_password" -> R.string.error_invalid_current_password
    "invalid_password" -> R.string.error_invalid_password
    "invalid_hourly_rate" -> R.string.error_invalid_hourly_rate
    "invalid_locale" -> R.string.error_invalid_locale
    "invalid_password_reset_code" -> R.string.error_invalid_password_reset_code
    "invalid_project_color" -> R.string.error_invalid_project_color
    "invalid_project_status" -> R.string.error_invalid_project_status
    "invalid_refresh_token" -> R.string.error_invalid_refresh_token
    "invalid_time_format" -> R.string.error_invalid_time_format
    "invalid_timer_range" -> R.string.error_invalid_timer_range
    "start_time_in_future" -> R.string.error_start_time_in_future
    "start_time_too_early" -> R.string.error_start_time_too_early
    "end_time_in_future" -> R.string.error_end_time_in_future
    "end_time_before_start" -> R.string.error_end_time_before_start
    "project_archived" -> R.string.error_project_archived
    "project_has_running_timer" -> R.string.error_project_has_running_timer
    "project_not_found" -> R.string.error_project_not_found
    "refresh_token_not_found" -> R.string.error_refresh_token_not_found
    "timer_already_running" -> R.string.error_timer_already_running
    "timer_not_found" -> R.string.error_timer_not_found
    "timer_not_running" -> R.string.error_timer_not_running
    "timer_overlap" -> R.string.error_timer_overlap
    "timer_running" -> R.string.error_timer_running
    "too_many_requests" -> R.string.error_too_many_requests
    "unauthorized" -> R.string.error_unauthorized
    "user_already_active" -> R.string.error_user_already_active
    "user_already_exists" -> R.string.error_user_already_exists
    "user_not_found" -> R.string.error_user_not_found
    "server_unavailable" -> R.string.error_server_unavailable
    "weak_password" -> R.string.error_weak_password
    "password_empty" -> R.string.error_password_empty
    "password_too_short" -> R.string.error_password_too_short
    "password_too_long" -> R.string.error_password_too_long
    "password_no_uppercase" -> R.string.error_password_no_uppercase
    "password_no_digit" -> R.string.error_password_no_digit
    "name_required" -> R.string.error_name_required
    "required" -> R.string.error_required
    "network" -> R.string.error_network
    // Local parse errors of the rate field reuse the validation texts.
    "decimal_parsing" -> R.string.error_validation_decimal_parsing
    "decimal_max_places" -> R.string.error_validation_decimal_max_places
    "decimal_max_digits" -> R.string.error_validation_decimal_max_digits
    else -> validationRes(code)
}

@StringRes
private fun validationRes(code: String): Int? {
    if (!code.startsWith("validation.")) return null
    val type = code.substringAfterLast('.')
    val field = code.removePrefix("validation.").substringBeforeLast('.', "")
    if (type == "value_error" && (field == "email" || field == "new_email")) return R.string.error_validation_email
    return when (type) {
        "missing" -> R.string.error_validation_missing
        "value_error" -> R.string.error_validation_value_error
        "string_type" -> R.string.error_validation_string_type
        "int_parsing" -> R.string.error_validation_int_parsing
        "bool_parsing" -> R.string.error_validation_bool_parsing
        "uuid_parsing" -> R.string.error_validation_uuid_parsing
        "timezone_aware" -> R.string.error_validation_timezone_aware
        "datetime_from_date_parsing" -> R.string.error_validation_datetime_parsing
        "decimal_parsing" -> R.string.error_validation_decimal_parsing
        "decimal_max_digits" -> R.string.error_validation_decimal_max_digits
        "decimal_max_places" -> R.string.error_validation_decimal_max_places
        "greater_than_equal" -> R.string.error_validation_greater_than_equal
        "less_than_equal" -> R.string.error_validation_less_than_equal
        "too_short" -> R.string.error_validation_too_short
        "json_invalid", "model_attributes_type" -> R.string.error_validation_malformed
        else -> null
    }
}

/** A user-facing message for any failure of an action. */
sealed interface UiMessage {
    data class Res(@param:StringRes val id: Int) : UiMessage
    data class Text(val text: String) : UiMessage

    companion object {
        fun of(error: Throwable): UiMessage = when (error) {
            is DomainException -> errorRes(error.code)?.let(::Res) ?: Res(R.string.something_went_wrong)
            is IOException -> Res(R.string.error_network)
            is ApiException -> errorRes(error.code)?.let(::Res)
                ?: when {
                    error.status == 429 -> Res(R.string.error_too_many_requests)
                    error.status in listOf(502, 503, 504) -> Res(R.string.error_server_unavailable)
                    !error.detail.isNullOrBlank() -> Text(error.detail)
                    else -> Res(R.string.something_went_wrong)
                }
            else -> Res(R.string.something_went_wrong)
        }
    }
}

@Composable
fun UiMessage.text(): String = when (this) {
    is UiMessage.Res -> stringResource(id)
    is UiMessage.Text -> text
}

/** Message for a failed outbox operation, from the stored code/detail. */
fun syncErrorMessage(code: String?, detail: String?): UiMessage =
    errorRes(code)?.let { UiMessage.Res(it) }
        ?: detail?.takeIf { it.isNotBlank() }?.let { UiMessage.Text(it) }
        ?: UiMessage.Res(R.string.something_went_wrong)
