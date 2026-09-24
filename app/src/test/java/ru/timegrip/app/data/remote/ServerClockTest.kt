package ru.timegrip.app.data.remote

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.timegrip.app.data.local.MonoStamp
import ru.timegrip.app.data.sync.TimerPayload
import java.time.Instant

class ServerClockTest {
    /** A phone whose wall clock is [ahead] of the real time; elapsed counts from boot. */
    private class FakeDevice(var realNow: Long, var ahead: Long, var sinceBoot: Long, override var boot: Int = 7) : DeviceClock {
        override fun elapsed() = sinceBoot
        override fun wall() = realNow + ahead
        fun pass(ms: Long) {
            realNow += ms
            sinceBoot += ms
        }
    }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun answerAt(serverDate: String) = Response.Builder()
        .request(Request.Builder().url("https://timegrip.ru/api/users/me").build())
        .protocol(Protocol.HTTP_1_1)
        .code(401)
        .message("Unauthorized")
        .header("Date", serverDate)
        .build()

    @Test
    fun monotonicStampIgnoresAWrongWallClock() {
        // The phone is 3 hours ahead; the timer really started at 10:00.
        val device = FakeDevice(realNow = ms("2026-09-25T10:00:00Z"), ahead = 3 * 3_600_000L, sinceBoot = 1_000_000)
        val clock = ServerClock(device)
        val wallAtStart = device.wall()
        val stamp = device.stamp()
        device.pass(25 * 60_000L)
        clock.record(answerAt("Fri, 25 Sep 2026 10:25:00 GMT"))
        assertEquals(ms("2026-09-25T10:00:00Z"), clock.toServer(wallAtStart, stamp))
    }

    @Test
    fun clockChangedWhileTheTimerRan() {
        val device = FakeDevice(realNow = ms("2026-09-25T10:00:00Z"), ahead = 0, sinceBoot = 50_000)
        val clock = ServerClock(device)
        val wallAtStart = device.wall()
        val stamp = device.stamp()
        device.pass(10 * 60_000L)
        device.ahead = -2 * 3_600_000L // moved two hours back meanwhile
        clock.record(answerAt("Fri, 25 Sep 2026 10:10:00 GMT"))
        assertEquals(ms("2026-09-25T10:00:00Z"), clock.toServer(wallAtStart, stamp))
    }

    @Test
    fun afterARebootTheCurrentOffsetIsUsed() {
        val device = FakeDevice(realNow = ms("2026-09-25T10:00:00Z"), ahead = 90_000, sinceBoot = 1_000)
        val clock = ServerClock(device)
        val wallAtStart = device.wall()
        val stamp = MonoStamp(boot = 6, elapsed = 999_999) // taken before the reboot
        device.pass(60_000)
        clock.record(answerAt("Fri, 25 Sep 2026 10:01:00 GMT"))
        assertEquals(ms("2026-09-25T10:00:00Z"), clock.toServer(wallAtStart, stamp))
    }

    @Test
    fun timesWithoutAStampOrBeforeAnyAnswerStayAsTheyAre() {
        val device = FakeDevice(realNow = ms("2026-09-25T10:00:00Z"), ahead = 3_600_000, sinceBoot = 1_000)
        val clock = ServerClock(device)
        assertFalse(clock.known)
        assertEquals(123L, clock.toServer(123L, device.stamp()))
        clock.record(answerAt("Fri, 25 Sep 2026 10:00:00 GMT"))
        assertTrue(clock.known)
        // A time typed in by the user, or one that came from the server.
        assertEquals(123L, clock.toServer(123L, null))
    }

    @Test
    fun smallDifferencesAcrossRebootAreLeftAlone() {
        val device = FakeDevice(realNow = ms("2026-09-25T10:00:00.400Z"), ahead = 0, sinceBoot = 1_000)
        val clock = ServerClock(device)
        clock.record(answerAt("Fri, 25 Sep 2026 10:00:00 GMT"))
        val wall = ms("2026-09-25T09:30:00Z")
        assertEquals(wall, clock.toServer(wall, MonoStamp(boot = 6, elapsed = 5)))
    }

    @Test
    fun operationsQueuedByOlderVersionsStillDecode() {
        val payload = HttpClientFactory.json.decodeFromString(
            TimerPayload.serializer(),
            """{"projectId":"p","startTime":1,"endTime":2}""",
        )
        assertEquals(TimerPayload("p", 1, 2, null, null), payload)
    }
}
