package ru.timegrip.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class JwtTest {
    private fun token(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"HS256","typ":"JWT"}""", payload, "signature")
            .joinToString(".") { encoder.encodeToString(it.toByteArray()) }
    }

    @Test
    fun `reads the expiry in millis`() {
        assertEquals(1_790_000_000_000L, jwtExpiry(token("""{"sub":"user","exp":1790000000}""")))
    }

    @Test
    fun `no expiry or no token gives null`() {
        assertNull(jwtExpiry(token("""{"sub":"user"}""")))
        assertNull(jwtExpiry("not-a-token"))
    }
}
