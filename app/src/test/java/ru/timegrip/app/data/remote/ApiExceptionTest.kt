package ru.timegrip.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bodies as the backend sends them (captured from timegrip.ru). */
class ApiExceptionTest {
    private val json = HttpClientFactory.json

    private fun parse(body: String, status: Int = 422) = ApiException.fromBody(status, body, json)

    @Test
    fun validationListGivesFieldAndType() {
        val error = parse(
            """{"detail":"email: value is not a valid email address: An email address must have an @-sign.",""" +
                """"code":"validation_error","errors":[{"loc":["body","email"],""" +
                """"msg":"value is not a valid email address: An email address must have an @-sign.","type":"value_error"}]}""",
        )
        assertEquals("validation.email.value_error", error.code)
    }

    @Test
    fun validationListKeepsTypesTheTextCannotTell() {
        val error = parse(
            """{"detail":"password: Input should be a valid string","code":"validation_error",""" +
                """"errors":[{"loc":["body","password"],"msg":"Input should be a valid string","type":"string_type"}]}""",
        )
        assertEquals("validation.password.string_type", error.code)
    }

    @Test
    fun missingFieldFromList() {
        val error = parse(
            """{"detail":"password: Field required","code":"validation_error",""" +
                """"errors":[{"loc":["body","password"],"msg":"Field required","type":"missing"}]}""",
        )
        assertEquals("validation.password.missing", error.code)
    }

    @Test
    fun olderBackendTextOnly() {
        assertEquals(
            "validation.email.value_error",
            parse("""{"detail":"email: value is not a valid email address","code":"validation_error"}""").code,
        )
        assertEquals(
            "validation.password.missing",
            parse("""{"detail":"password: Field required","code":"validation_error"}""").code,
        )
    }

    @Test
    fun fastApiDefaultList() {
        val error = parse("""{"detail":[{"loc":["body","email"],"msg":"Field required","type":"missing"}]}""")
        assertEquals("validation.email.missing", error.code)
    }

    @Test
    fun plainErrorCode() {
        val error = parse("""{"detail":"Invalid email or password","code":"invalid_credentials"}""", status = 401)
        assertEquals("invalid_credentials", error.code)
        assertEquals(401, error.status)
    }
}
