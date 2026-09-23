package ru.timegrip.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException
import java.io.IOException

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

    companion object {
        private val LOCATION_PARTS = setOf("body", "query", "path", "header", "cookie")

        fun from(error: HttpException, json: Json): ApiException {
            val body = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            val parsed = body?.let { runCatching { json.decodeFromString(ErrorDto.serializer(), it) }.getOrNull() }
            val detail = parsed?.detail
            return when {
                detail is JsonPrimitive -> ApiException(error.code(), parsed.code, detail.contentOrNull)
                detail is JsonArray && detail.isNotEmpty() -> fromValidation(error.code(), detail.first())
                else -> ApiException(error.code(), parsed?.code, null)
            }
        }

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
