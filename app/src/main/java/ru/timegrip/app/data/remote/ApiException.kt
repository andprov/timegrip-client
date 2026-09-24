package ru.timegrip.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant

/**
 * A non-2xx answer from the API. [code] is the backend's machine-readable
 * error code (`timer_overlap`, ...) or, for a 422, `validation.<type>` of the
 * first failed field, optionally prefixed by the field name.
 */
class ApiException(
    val status: Int,
    val code: String?,
    val detail: String?,
) : Exception(detail ?: code ?: "HTTP $status") {
    /** Worth retrying later: the request never reached a verdict about its content. */
    val isTransient: Boolean
        get() = status == 408 || status == 429 || status >= 500

    /** The server's clock when it answered (the `Date` header, to the second), if it sent one. */
    var serverTime: Instant? = null
        private set

    companion object {
        private val LOCATION_PARTS = setOf("body", "query", "path", "header", "cookie")

        fun from(error: HttpException, json: Json): ApiException {
            val body = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            return fromBody(error.code(), body, json).apply {
                serverTime = error.response()?.headers()?.getDate("Date")?.toInstant()
            }
        }

        internal fun fromBody(status: Int, body: String?, json: Json): ApiException {
            val parsed = body?.let { runCatching { json.decodeFromString(ErrorDto.serializer(), it) }.getOrNull() }
            val detail = parsed?.detail
            val errors = parsed?.errors
            return when {
                // The backend's validation answer: the per-field list says exactly what failed.
                !errors.isNullOrEmpty() -> fromValidation(status, errors.first())
                // Older backends sent only the `field: message` line.
                detail is JsonPrimitive && parsed.code == VALIDATION_ERROR ->
                    fromValidationText(status, detail.contentOrNull)
                detail is JsonPrimitive -> ApiException(status, parsed.code, detail.contentOrNull)
                // FastAPI's default 422: the list sits in `detail` itself.
                detail is JsonArray && detail.isNotEmpty() -> fromValidation(status, detail.first())
                else -> ApiException(status, parsed?.code, null)
            }
        }

        /**
         * A validation answer from a backend that sends only `code: validation_error`
         * and a `field: message` line, e.g. `email: value is not a valid email address: ...`.
         * The type is guessed from the message; mapped onto the same codes as Pydantic's list.
         */
        private fun fromValidationText(status: Int, text: String?): ApiException {
            val field = text?.substringBefore(": ", "")?.takeIf { it.matches(FIELD_NAME) }
            val message = if (field != null) text.substringAfter(": ") else text
            val type = if (message?.startsWith("Field required") == true) "missing" else "value_error"
            val code = if (field != null) "validation.$field.$type" else "validation.$type"
            return ApiException(status, code, text)
        }

        private const val VALIDATION_ERROR = "validation_error"
        private val FIELD_NAME = Regex("[a-z_][a-z0-9_]*")

        // Pydantic reports a `type` per failed field (web: formatValidationError).
        private fun fromValidation(status: Int, item: kotlinx.serialization.json.JsonElement): ApiException {
            val obj = item as? JsonObject ?: return ApiException(status, null, null)
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
            val message = (obj["msg"] as? JsonPrimitive)?.contentOrNull
            val field = (obj["loc"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.lastOrNull { it !in LOCATION_PARTS }
            val code = when {
                type == null -> null
                field != null -> "validation.$field.$type"
                else -> "validation.$type"
            }
            return ApiException(status, code, message)
        }
    }
}

/** Runs an API call, turning HTTP errors into [ApiException]; network failures stay [IOException]. */
suspend inline fun <T> apiCall(json: Json, block: () -> T): T =
    try {
        block()
    } catch (error: HttpException) {
        throw ApiException.from(error, json)
    }

fun Throwable.isNetworkError(): Boolean = this is IOException
