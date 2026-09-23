package ru.timegrip.app.data.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.timegrip.app.BuildConfig
import ru.timegrip.app.R
import ru.timegrip.app.data.remote.HttpClientFactory
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

private const val LATEST_RELEASE_URL = "https://api.github.com/repos/andprov/timegrip-client/releases/latest"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val UPDATES_DIR = "updates"

data class AppUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
    /** Hex SHA-256 of the APK as GitHub reports it; null for assets uploaded before GitHub added digests. */
    val sha256: String?,
)

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val update: AppUpdate, val progress: Float?) : UpdateState
    data class ReadyToInstall(val update: AppUpdate, val apk: File) : UpdateState
    data class Failed(val update: AppUpdate) : UpdateState
}

/** Outcome of the check the user started from the account screen. */
enum class CheckStatus { IDLE, CHECKING, UP_TO_DATE, FAILED }

/**
 * The app is distributed as an APK on GitHub releases, so it updates itself:
 * the latest release is checked once per process, and on the user's consent
 * DownloadManager fetches its APK (it keeps going while the app is in the
 * background) and the system installer takes over. The installer only
 * accepts it over the current app when it is signed with the same key.
 */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val downloadManager = checkNotNull(context.getSystemService<DownloadManager>())

    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The dialog is hidden while the download runs; it comes back once the APK is ready. */
    private val _dialogHidden = MutableStateFlow(false)
    val dialogHidden: StateFlow<Boolean> = _dialogHidden.asStateFlow()

    private val _checkStatus = MutableStateFlow(CheckStatus.IDLE)
    val checkStatus: StateFlow<CheckStatus> = _checkStatus.asStateFlow()

    /** Debug builds have their own application id and key, so a release APK would not replace them. */
    val isSupported = !BuildConfig.DEBUG

    private var checked = false
    private var downloadId: Long? = null
    private var watcher: Job? = null

    /** The silent check on launch: failures wait for the next launch. */
    fun checkOnce() {
        if (checked || !isSupported) return
        checked = true
        scope.launch {
            removeLeftoverDownloads()
            val update = try {
                fetchLatest()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            offer(update)
        }
    }

    /** Started by the user: brings back the dialog of an update in progress, or checks GitHub again. */
    fun checkNow() {
        if (!isSupported || _checkStatus.value == CheckStatus.CHECKING) return
        checked = true
        if (_state.value != UpdateState.None) {
            _dialogHidden.value = false
            return
        }
        _checkStatus.value = CheckStatus.CHECKING
        scope.launch {
            _checkStatus.value = try {
                val update = fetchLatest()
                offer(update)
                if (update == null) CheckStatus.UP_TO_DATE else CheckStatus.IDLE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CheckStatus.FAILED
            }
        }
    }

    private fun offer(update: AppUpdate?) {
        if (update != null && _state.value == UpdateState.None) _state.value = UpdateState.Available(update)
    }

    fun download() {
        val update = when (val current = _state.value) {
            is UpdateState.Available -> current.update
            is UpdateState.Failed -> current.update
            else -> return
        }
        val fileName = "TimeGrip_v${update.version}.apk"
        File(context.getExternalFilesDir(UPDATES_DIR), fileName).delete()
        val request = DownloadManager.Request(update.apkUrl.toUri())
            .setTitle(context.getString(R.string.update_notification_title, update.version))
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, UPDATES_DIR, fileName)
        val id = downloadManager.enqueue(request)
        downloadId = id
        _state.value = UpdateState.Downloading(update, null)
        watcher = scope.launch { watch(id, update) }
    }

    fun hideDialog() {
        _dialogHidden.value = true
    }

    /** Hides the dialog until the next launch; a running download is cancelled. */
    fun dismiss() {
        watcher?.cancel()
        watcher = null
        downloadId?.let { downloadManager.remove(it) }
        downloadId = null
        _dialogHidden.value = false
        _state.value = UpdateState.None
    }

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** DownloadManager has no progress callback, so its record is polled until the download ends. */
    private suspend fun watch(id: Long, update: AppUpdate) {
        while (true) {
            val (status, progress, localUri) = downloadManager.query(DownloadManager.Query().setFilterById(id)).use { c ->
                // The record is gone: the download was cancelled from its notification.
                if (!c.moveToFirst()) return dismiss()
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                Triple(
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null,
                    c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
                )
            }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val apk = localUri?.toUri()?.path?.let(::File)
                    _state.value = if (apk != null && apk.exists() && matchesChecksum(apk, update.sha256)) {
                        UpdateState.ReadyToInstall(update, apk)
                    } else {
                        downloadManager.remove(id)
                        UpdateState.Failed(update)
                    }
                    _dialogHidden.value = false
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    downloadManager.remove(id)
                    _state.value = UpdateState.Failed(update)
                    _dialogHidden.value = false
                    return
                }
                else -> _state.value = UpdateState.Downloading(update, progress)
            }
            delay(500)
        }
    }

    /** Whatever an earlier process downloaded was either installed or abandoned. */
    private suspend fun removeLeftoverDownloads() = withContext(Dispatchers.IO) {
        // The query only returns this app's downloads.
        val ids = downloadManager.query(DownloadManager.Query()).use { c ->
            val column = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            buildList { while (c.moveToNext()) add(c.getLong(column)) }
        }
        if (ids.isNotEmpty()) downloadManager.remove(*ids.toLongArray())
        context.getExternalFilesDir(UPDATES_DIR)?.listFiles()?.forEach { it.delete() }
    }

    private suspend fun matchesChecksum(apk: File, sha256: String?): Boolean = withContext(Dispatchers.IO) {
        if (sha256 == null) return@withContext true
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }

    private suspend fun fetchLatest(): AppUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        val release = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            HttpClientFactory.json.decodeFromString(ReleaseDto.serializer(), response.body.string())
        }
        val version = release.tagName.removePrefix("v")
        if (!isNewerVersion(version, BuildConfig.VERSION_NAME)) return@withContext null
        val asset = release.assets.firstOrNull { it.name.endsWith(".apk") && !it.name.endsWith("-debug.apk") }
            ?: return@withContext null
        AppUpdate(
            version = version,
            notes = release.body.orEmpty().trim(),
            apkUrl = asset.downloadUrl,
            sha256 = asset.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
        )
    }
}

/** Compares dotted numeric versions ("1.4.21"); a missing part counts as 0, a non-numeric one as not newer. */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val a = candidate.split('.').map { it.toIntOrNull() ?: return false }
    val b = current.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (diff != 0) return diff > 0
    }
    return false
}

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
private data class AssetDto(
    val name: String,
    val digest: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String,
)
