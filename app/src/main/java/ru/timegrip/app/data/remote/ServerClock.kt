package ru.timegrip.app.data.remote

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import okhttp3.Interceptor
import okhttp3.Response
import ru.timegrip.app.data.local.MonoStamp
import kotlin.math.abs

/** This phone's clocks: the wall clock the user sets, and the monotonic one since boot. */
interface DeviceClock {
    /** Which boot this is; -1 when the system does not say. */
    val boot: Int
    fun elapsed(): Long
    fun wall(): Long

    /** The current moment on the monotonic clock, or null when boots cannot be told apart. */
    fun stamp(): MonoStamp? = boot.takeIf { it >= 0 }?.let { MonoStamp(it, elapsed()) }
}

class AndroidDeviceClock(context: Context) : DeviceClock {
    override val boot: Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    override fun elapsed(): Long = SystemClock.elapsedRealtime()
    override fun wall(): Long = System.currentTimeMillis()
}

/**
 * The server's clock as its answers show it (the `Date` header), tied to this
 * phone's monotonic clock. Times the phone recorded offline go to the server
 * on the server's clock: a phone set to the wrong time, or moved to another
 * one while a timer ran, would otherwise shift or stretch the entry.
 */
class ServerClock(private val device: DeviceClock) {
    private class Sample(val server: Long, val elapsed: Long, val wall: Long)

    @Volatile
    private var sample: Sample? = null

    /** Some answer from the server has been seen since the app started. */
    val known: Boolean get() = sample != null

    /** Notes the server's time from every answer that carries it. */
    val interceptor = Interceptor { chain -> chain.proceed(chain.request()).also(::record) }

    fun record(response: Response) {
        val date = response.headers.getDate("Date") ?: return
        sample = Sample(date.time, device.elapsed(), device.wall())
    }

    /**
     * The server's time at a moment this phone recorded as [wall] on its own
     * clock and as [stamp] on the monotonic one. [wall] is returned as it is
     * when there is no stamp (a time typed in, or one that came from the
     * server) or nothing is known about the server's clock yet.
     *
     * Within the same boot the answer only depends on the monotonic clock. The
     * server's time is known to the second, rounded down, and the answer
     * arrives after the server wrote it, so the result lands up to about a
     * second early but never in the server's future. Across a reboot the
     * monotonic clock restarts; then the wall time is corrected by how far
     * this phone's clock is off now, assuming it was off by as much back then.
     */
    fun toServer(wall: Long, stamp: MonoStamp?): Long {
        val s = sample ?: return wall
        if (stamp == null) return wall
        if (stamp.boot == device.boot) return s.server + (stamp.elapsed - s.elapsed)
        val ahead = s.wall - s.server
        return if (abs(ahead) > DATE_PRECISION_MS) wall - ahead else wall
    }

    private companion object {
        /** A smaller difference is within what a `Date` header can tell. */
        const val DATE_PRECISION_MS = 2_000L
    }
}
