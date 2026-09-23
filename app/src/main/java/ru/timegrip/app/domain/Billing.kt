package ru.timegrip.app.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

private val SECONDS_PER_HOUR = BigDecimal(3600)
private val MAX_HOURLY_RATE = BigDecimal("99999999.99")

/**
 * Port of `calculate_billable_amount` from the backend (application/timer/billing.py),
 * so entries recorded offline show the same amount the server will store.
 */
fun calculateBillableAmount(
    durationSeconds: Long,
    hourlyRate: BigDecimal?,
    roundToHour: Boolean,
): BigDecimal? {
    if (hourlyRate == null) return null

    var wholeMinutes = durationSeconds / 60
    if (durationSeconds % 60 >= 30) wholeMinutes += 1
    var hours = BigDecimal(wholeMinutes * 60).divide(SECONDS_PER_HOUR, MathContext.DECIMAL128)
    if (roundToHour) hours = hours.setScale(0, RoundingMode.HALF_UP)
    return hours.multiply(hourlyRate).setScale(2, RoundingMode.HALF_UP)
}

/**
 * Parses the hourly rate typed into the project form: blank or zero means a
 * non-billable project, like the backend stores a zero rate as no rate.
 */
fun parseHourlyRate(input: String): BigDecimal? {
    val text = input.trim().replace(',', '.')
    if (text.isEmpty()) return null
    val value = text.toBigDecimalOrNull() ?: throw DomainException("decimal_parsing")
    if (value.signum() < 0) throw DomainException("invalid_hourly_rate")
    if (value.scale() > 2 && value.stripTrailingZeros().scale() > 2) {
        throw DomainException("decimal_max_places")
    }
    if (value > MAX_HOURLY_RATE) throw DomainException("decimal_max_digits")
    return if (value.signum() == 0) null else value.setScale(2, RoundingMode.HALF_UP)
}
