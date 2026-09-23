package ru.timegrip.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.timegrip.app.data.repository.ProjectInput
import ru.timegrip.app.data.sync.SyncEngine
import ru.timegrip.app.domain.DateRange
import ru.timegrip.app.domain.ProjectColors
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * End-to-end check of offline work against a real backend. Skipped unless the
 * instrumentation gets an activated account:
 *
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.apiUrl=http://10.0.2.2:8000/api/ \
 *   -Pandroid.testInstrumentationRunnerArguments.email=user@example.com \
 *   -Pandroid.testInstrumentationRunnerArguments.password=Passw0rd
 *
 * "Offline" is simulated by pointing the app at a port nothing listens on, so
 * every request fails like it does without a network.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSyncTest {
    private val args = InstrumentationRegistry.getArguments()
    private val apiUrl: String? = args.getString("apiUrl")
    private val container = ApplicationProvider.getApplicationContext<TimeGripApplication>().container
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun signIn() = runBlocking {
        assumeTrue("apiUrl/email/password instrumentation arguments are required", apiUrl != null)
        container.authRepository.signOut()
        assertTrue(container.settingsStore.setApiBaseUrl(apiUrl!!))
        container.authRepository.signIn(args.getString("email")!!, args.getString("password")!!)
        assertTrue("initial sync", container.syncManager.syncNow())
        // Stop anything left running by an earlier run.
        if (container.timerRepository.observeRunning().first() != null) {
            container.timerRepository.stop()
            assertTrue(container.syncManager.syncNow())
        }
    }

    @Test
    fun offlineChangesReachTheServerInOrder() = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        goOffline()

        container.projectRepository.create(
            ProjectInput("Offline $suffix", ProjectColors.all[5], BigDecimal("100"), roundToHour = false),
        )
        val project = container.projectRepository.observeProjects().first().first { it.name == "Offline $suffix" }

        // A project created and deleted offline never reaches the server.
        container.projectRepository.create(ProjectInput("Discarded $suffix", ProjectColors.DEFAULT, null, false))
        val discarded = container.projectRepository.observeProjects().first().first { it.name == "Discarded $suffix" }
        container.projectRepository.delete(discarded.id)

        val now = Instant.now()
        container.timerRepository.create(project.id, now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(2)))
        val manual = entries().first { it.projectId == project.id }
        // Edited before it was ever uploaded.
        val manualEnd = now.minus(Duration.ofMinutes(110))
        container.timerRepository.update(manual.id, project.id, manual.start, manualEnd)

        // A timer started and stopped offline becomes a manual entry with its real times.
        container.timerRepository.start(project.id)
        Thread.sleep(3_000)
        container.timerRepository.stop()
        val offlineTimer = entries().first { it.projectId == project.id && it.id != manual.id }

        container.projectRepository.update(
            project.id,
            ProjectInput("Renamed $suffix", project.color, BigDecimal("100"), roundToHour = false),
        )
        assertTrue(pendingCount() > 0)

        goOnline()
        assertTrue("sync after reconnect", container.syncManager.syncNow())
        assertEquals(0, pendingCount())

        val projects = serverItems("projects?page=1&page_size=100")
        val serverProject = projects.single { it["name"]!!.jsonPrimitive.content == "Renamed $suffix" }
        assertEquals("100.00", serverProject["hourly_rate"]!!.jsonPrimitive.content)
        assertTrue(projects.none { it["name"]!!.jsonPrimitive.content == "Discarded $suffix" })

        val projectServerId = serverProject["id"]!!.jsonPrimitive.content
        val timers = serverItems("timers?page=1&page_size=100&include_archived_projects=true")
            .filter { it["project_id"]!!.jsonPrimitive.content == projectServerId }
        assertEquals(2, timers.size)

        val serverManual = timers.single { close(instant(it["start_time"]), manual.start, 1_000) }
        assertTrue(close(instant(serverManual["end_time"]), manualEnd, 1_000))
        // 70 minutes at 100/hour.
        assertEquals("116.67", serverManual["billable_amount"]!!.jsonPrimitive.content)

        val serverTimer = timers.single { it !== serverManual }
        assertTrue(close(instant(serverTimer["start_time"]), offlineTimer.start, 1_000))
        assertTrue(close(instant(serverTimer["end_time"]), offlineTimer.end!!, 1_000))
    }

    @Test
    fun timerStartedOnlineAndStoppedOfflineKeepsTheRealEndTime() = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        container.projectRepository.create(ProjectInput("Live $suffix", ProjectColors.all[3], null, false))
        assertTrue(container.syncManager.syncNow())
        val project = container.projectRepository.observeProjects().first().first { it.name == "Live $suffix" }

        container.timerRepository.start(project.id)
        assertTrue(container.syncManager.syncNow())
        val running = serverRunning()
        assertNotNull("a live start is sent right away", running)

        goOffline()
        Thread.sleep(2_000)
        container.timerRepository.stop()
        val stoppedAt = entries().first { it.projectId == project.id }.end!!
        // The server would stamp this moment if the stop were replayed naively.
        Thread.sleep(6_000)

        goOnline()
        assertTrue(container.syncManager.syncNow())
        assertNull(serverRunning())
        val serverTimer = json.parseToJsonElement(get("timers/${running!!["id"]!!.jsonPrimitive.content}")).jsonObject
        assertTrue(
            "end must be the moment Stop was pressed",
            close(instant(serverTimer["end_time"]), stoppedAt, SyncEngine.TIME_TOLERANCE_MS),
        )
    }

    private fun goOffline() {
        container.settingsStore.setApiBaseUrl("http://127.0.0.1:9/api/")
    }

    private fun goOnline() {
        container.settingsStore.setApiBaseUrl(apiUrl!!)
    }

    private suspend fun entries() = container.timerRepository.observeEntries(DateRange.ALL).first()

    private suspend fun pendingCount(): Int = container.authRepository.unsyncedChangesCount()

    private fun serverRunning(): JsonObject? =
        json.parseToJsonElement(get("timers/running")).let { if (it is JsonNull) null else it.jsonObject }

    private fun serverItems(path: String): List<JsonObject> =
        json.parseToJsonElement(get(path)).jsonObject["items"]!!.jsonArray.map { it.jsonObject }

    private fun get(path: String): String {
        val request = Request.Builder()
            .url(apiUrl!!.trimEnd('/') + "/" + path)
            .header("Authorization", "Bearer ${container.sessionStore.accessToken}")
            .build()
        return http.newCall(request).execute().use { response ->
            assertTrue("GET $path -> ${response.code}", response.isSuccessful)
            response.body.string()
        }
    }

    private fun instant(value: JsonElement?): Instant = SyncEngine.parseInstant(value!!.jsonPrimitive.content)

    private fun close(a: Instant, b: Instant, toleranceMs: Long): Boolean =
        abs(a.toEpochMilli() - b.toEpochMilli()) <= toleranceMs
}
