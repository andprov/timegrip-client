package ru.timegrip.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

/** Same cases as backend/tests/test_billing.py: offline amounts must match the server's. */
class BillingTest {
    private fun amount(seconds: Long, rate: String?, roundToHour: Boolean = false) =
        calculateBillableAmount(seconds, rate?.let(::BigDecimal), roundToHour)

    @Test
    fun noRateMeansNoAmount() {
        assertNull(amount(3600, null))
    }

    @Test
    fun exactFractionalHours() {
        assertEquals(BigDecimal("74.17"), amount(89 * 60L, "50.00"))
    }

    @Test
    fun roundsSecondsToNearestMinute() {
        mapOf(29L to "0.00", 30L to "1.00", 89L to "1.00", 90L to "2.00").forEach { (seconds, expected) ->
            assertEquals("$seconds s", BigDecimal(expected), amount(seconds, "60.00"))
        }
    }

    @Test
    fun roundToHour() {
        mapOf(29L to "0.00", 30L to "50.00", 89L to "50.00", 90L to "100.00").forEach { (minutes, expected) ->
            assertEquals("$minutes min", BigDecimal(expected), amount(minutes * 60, "50.00", roundToHour = true))
        }
    }

    @Test
    fun roundsToMinuteBeforeHour() {
        assertEquals(BigDecimal("100.00"), amount(3600 + 29 * 60 + 30L, "50.00", roundToHour = true))
    }

    @Test
    fun countsWholeDays() {
        assertEquals(BigDecimal("510.00"), amount(2 * 86_400 + 3 * 3600L, "10.00"))
    }

    @Test
    fun roundsCentsHalfUp() {
        assertEquals(BigDecimal("0.00"), amount(60, "0.25"))
        assertEquals(BigDecimal("0.01"), amount(180, "0.10"))
    }

    @Test
    fun parsesHourlyRateLikeTheForm() {
        assertNull(parseHourlyRate(""))
        assertNull(parseHourlyRate("0"))
        assertEquals(BigDecimal("45.50"), parseHourlyRate("45,5"))
        assertEquals(BigDecimal("1.50"), parseHourlyRate("1.500"))
    }

    @Test(expected = DomainException::class)
    fun rejectsThreeDecimalPlaces() {
        parseHourlyRate("1.505")
    }

    @Test(expected = DomainException::class)
    fun rejectsNegativeRate() {
        parseHourlyRate("-1")
    }
}
