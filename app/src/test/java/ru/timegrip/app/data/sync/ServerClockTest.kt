package ru.timegrip.app.data.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import ru.timegrip.app.data.remote.ApiException
import ru.timegrip.app.data.remote.HttpClientFactory
import java.time.Instant

/** A timer stopped on a phone whose clock runs ahead of the server's. */
class ServerClockTest {
    private fun refusal(code: String, date: String?): ApiException {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://timegrip.ru/api/timers").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .apply { if (date != null) header("Date", date) }
            .build()
        val body = """{"detail":"End time must not be in the future","code":"$code"}"""
            .toResponseBody("application/json".toMediaType())
        return ApiException.from(HttpException(Response.error<Any>(body, raw)), HttpClientFactory.json)
    }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun readsServerTimeFromDateHeader() {
        val error = refusal("end_time_in_future", "Thu, 24 Sep 2026 19:56:10 GMT")
        assertEquals(Instant.parse("2026-09-24T19:56:10Z"), error.serverTime)
    }

    @Test
    fun movesEntryBackKeepingItsLength() {
        val error = refusal("end_time_in_future", "Thu, 24 Sep 2026 19:56:10 GMT")
        val (start, end) = SyncEngine.behindServerClock(error, ms("2026-09-24T19:54:00.500Z"), ms("2026-09-24T19:56:11.300Z"))!!
        assertEquals(ms("2026-09-24T19:56:10Z"), end)
        assertEquals(ms("2026-09-24T19:53:59.200Z"), start)
    }

    @Test
    fun leavesOtherRefusalsAlone() {
        val overlap = refusal("timer_overlap", "Thu, 24 Sep 2026 19:56:10 GMT")
        assertNull(SyncEngine.behindServerClock(overlap, ms("2026-09-24T19:54:00Z"), ms("2026-09-24T19:56:11Z")))
    }

    @Test
    fun givesUpWithoutServerTimeOrWhenAlreadyBehind() {
        assertNull(SyncEngine.behindServerClock(refusal("end_time_in_future", null), 0, ms("2026-09-24T19:56:11Z")))
        val error = refusal("end_time_in_future", "Thu, 24 Sep 2026 19:56:10 GMT")
        assertNull(SyncEngine.behindServerClock(error, 0, ms("2026-09-24T19:56:09Z")))
    }
}
