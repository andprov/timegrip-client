package ru.timegrip.app.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/** When an access token (a JWT) expires on the server's clock, in epoch millis; null if it does not say. */
fun jwtExpiry(token: String): Long? = runCatching {
    val payload = String(Base64.getUrlDecoder().decode(token.split('.')[1]))
    Json.parseToJsonElement(payload).jsonObject["exp"]?.jsonPrimitive?.longOrNull?.times(1000)
}.getOrNull()
