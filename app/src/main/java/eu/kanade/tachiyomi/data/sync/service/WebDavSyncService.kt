package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.sync.LightSnapshot
import eu.kanade.tachiyomi.data.sync.ProgressSnapshot
import eu.kanade.tachiyomi.data.sync.SyncBookmarks
import eu.kanade.tachiyomi.data.sync.SyncHistory
import eu.kanade.tachiyomi.data.sync.SyncNotifier
import eu.kanade.tachiyomi.data.sync.SyncProgress
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.http.HttpStatus
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class WebDavSyncService(
    context: Context,
    json: Json,
    syncPreferences: SyncPreferences,
    private val notifier: SyncNotifier,
    private val protoBuf: ProtoBuf = Injekt.get(),
) : SyncService(context, json, syncPreferences) {
    constructor(context: Context) : this(
        context,
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        },
        Injekt.get<SyncPreferences>(),
        SyncNotifier(context),
    )

    enum class DeleteSyncDataStatus {
        NOT_INITIALIZED,
        NO_FILES,
        SUCCESS,
        ERROR,
    }

    private val appName = context.stringResource(MR.strings.app_name)

    private val remoteFileName = "${appName}_sync.proto.gz"

    // SY -->
    /** Guards against a remote that is being rewritten continuously by other devices. */
    private val maxPushAttempts = 3

    /** RFC 7232 precondition failure, returned when the remote changed under us. */
    private val HTTP_PRECONDITION_FAILED = 412
    // SY <--

    private val client: OkHttpClient by lazy { buildClient() }

    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)

        if (syncPreferences.webdavTrustAllCerts.get()) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
        }

        return builder.build()
    }

    private fun requestBuilder(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        basicAuthHeader()?.let { builder.header("Authorization", it) }
        return builder
    }

    private fun basicAuthHeader(): String? {
        val username = syncPreferences.webdavUsername.get()
        val password = syncPreferences.webdavPassword.get()
        if (username.isEmpty() && password.isEmpty()) return null
        return Credentials.basic(username, password)
    }

    private fun requireRemoteUrl(): HttpUrl {
        val rawUrl = syncPreferences.webdavUrl.get().trim().trimEnd('/')
        if (rawUrl.isEmpty()) {
            throw Exception(context.stringResource(SYMR.strings.webdav_not_configured))
        }
        return rawUrl.toHttpUrlOrNull()
            ?: throw Exception(context.stringResource(SYMR.strings.webdav_invalid_url, rawUrl))
    }

    private fun fileUrl(): HttpUrl {
        return requireRemoteUrl().newBuilder()
            .addPathSegment(remoteFileName)
            .build()
    }

    override suspend fun doSync(syncData: SyncData): Backup? {
        try {
            // SY -->
            // Pull, merge and push under optimistic locking. With several devices syncing by hand
            // it is routine for two of them to interleave, and an unconditional PUT silently drops
            // whichever device wrote first. A 412 means someone else won the race, so the merge is
            // redone against their data instead.
            var attempt = 0
            while (true) {
                val pulled = pullSyncData()

                if (pulled == null) {
                    // Remote is absent, so there is nothing to merge with yet. The push is still
                    // conditional: if another device created the file in the meantime, its content
                    // has to be merged rather than overwritten.
                    if (pushSyncData(syncData, null)) {
                        return syncData.backup
                    }
                } else {
                    val remoteSData = pulled.data
                    val mergedSyncData = mergeSyncData(syncData, remoteSData)

                    if (pushSyncData(mergedSyncData, pulled.etag)) {
                        return mergedSyncData.backup
                    }
                }

                attempt++
                if (attempt >= maxPushAttempts) {
                    logcat(LogPriority.ERROR, "SyncService") {
                        "Giving up after $attempt attempts: the remote keeps changing under us"
                    }
                    notifier.showSyncError("Remote changed too often, please sync again")
                    return null
                }
                logcat(LogPriority.INFO, "SyncService") {
                    "Remote changed during sync, retrying (attempt $attempt)"
                }
            }
            // SY <--
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, "SyncService") { "Error syncing: ${e.message}" }
            notifier.showSyncError(e.message)
            return null
        }
    }

    private data class PullResult(val data: SyncData, val etag: String?)

    private suspend fun pullSyncData(): PullResult? {
        val request = requestBuilder(fileUrl()).get().build()
        client.newCall(request).await().use { response ->
            if (response.code == HttpStatus.SC_NOT_FOUND) {
                logcat(LogPriority.INFO) { "No sync data found on WebDAV server" }
                return null
            }

            if (!response.isSuccessful) {
                response.body.string()
                logcat(LogPriority.ERROR) { "Failed to download sync data: HTTP ${response.code}" }
                throw IOException("Failed to download sync data: HTTP ${response.code}")
            }

            val etag = response.header("ETag")?.takeIf { it.isNotBlank() && it != "*" }
                ?: response.header("Last-Modified")?.takeIf { it.isNotBlank() }

            val byteArray = response.body.byteStream().use { stream ->
                GZIPInputStream(stream).use { gzipStream ->
                    gzipStream.readBytes()
                }
            }

            val decoded = try {
                protoBuf.decodeFromByteArray(SyncData.serializer(), byteArray)
            } catch (e: Exception) {
                // SY -->
                // Overwriting here would destroy a remote we merely failed to parse, so the sync
                // fails loudly instead and leaves the remote untouched.
                logcat(LogPriority.ERROR, "SyncService") {
                    "Bad sync data received from WebDAV server: ${e.message}"
                }
                throw IOException("Remote sync data could not be read: ${e.message}", e)
                // SY <--
            }

            return PullResult(decoded, etag)
        }
    }

    /** Returns false when the remote was modified concurrently and the merge has to be redone. */
    private suspend fun pushSyncData(syncData: SyncData, etag: String?): Boolean {
        val backup = syncData.backup ?: return false

        // Encode the full SyncData (including deviceId) so the next pull can tell
        // whether the remote was last written by this device
        val byteArray = protoBuf.encodeToByteArray(SyncData.serializer(), syncData)
        if (byteArray.isEmpty()) {
            throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
        }

        val conflicted = withIOContext {
            val gzipped = ByteArrayOutputStream().also { bos ->
                GZIPOutputStream(bos).use { it.write(byteArray) }
            }.toByteArray()

            ensureDirectoryExists()

            val body = gzipped.toRequestBody("application/octet-stream".toMediaType())
            val builder = requestBuilder(fileUrl()).put(body)
            // SY -->
            // Only overwrite the exact revision that was merged against, and only create the file if
            // it is still absent. Without this, two devices syncing at the same time silently
            // discard whichever wrote first.
            if (etag != null) {
                builder.header("If-Match", etag)
            } else {
                builder.header("If-None-Match", "*")
            }
            // SY <--
            val request = builder.build()

            var conflict = false
            client.newCall(request).await().use {
                when {
                    // SY -->
                    // 412 Precondition Failed: the revision we merged against is gone
                    it.code == HTTP_PRECONDITION_FAILED -> conflict = true
                    // SY <--
                    !it.isSuccessful -> {
                        it.body.string()
                        logcat(LogPriority.ERROR) { "Failed to upload sync data: HTTP ${it.code}" }
                        throw IOException("Failed to upload sync data: HTTP ${it.code}")
                    }
                }
            }
            conflict
        }

        if (conflicted) return false

        logcat(LogPriority.DEBUG) { "WebDAV sync data uploaded" }
        return true
    }

    /**
     * Creates the remote directory hierarchy of the configured WebDAV path.
     * 405 (Method Not Allowed) means the collection already exists and is treated as success.
     */
    private suspend fun ensureDirectoryExists() {
        val baseUrl = requireRemoteUrl()
        val segments = baseUrl.pathSegments.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return

        var current: HttpUrl = HttpUrl.Builder()
            .scheme(baseUrl.scheme)
            .host(baseUrl.host)
            .port(baseUrl.port)
            .build()

        for (segment in segments) {
            current = current.newBuilder().addPathSegment(segment).build()
            val dirUrl = current.toString().trimEnd('/') + "/"
            val request = Request.Builder()
                .url(dirUrl)
                .method("MKCOL", null)
                .apply { basicAuthHeader()?.let { header("Authorization", it) } }
                .build()
            client.newCall(request).await().use {
                val code = it.code
                if (code != HttpStatus.SC_CREATED && code != HttpStatus.SC_OK && code != HttpStatus.SC_METHOD_NOT_ALLOWED) {
                    logcat(LogPriority.ERROR) { "MKCOL failed for $dirUrl: HTTP $code" }
                    throw IOException("Failed to create remote directory: HTTP $code")
                }
            }
        }
    }

    // SY -->
    private val progressFileName = "${appName}_progress.proto.gz"

    /**
     * Timeout for the side channels. They carry a few dozen KB and are used while the reader or a
     * screen is waiting, so they have to fail fast instead of inheriting the leisurely full-sync
     * timeouts.
     */
    private val interactiveTimeoutSeconds = 5L

    private val interactiveClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(interactiveTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(interactiveTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(interactiveTimeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    private fun progressFileUrl(): HttpUrl {
        return requireRemoteUrl().newBuilder()
            .addPathSegment(progressFileName)
            .build()
    }

    /**
     * Reads one of the side-channel files. Returns null when it is absent or unreachable: the caller
     * then falls back to local data, because a missing side channel must never surface as an error
     * while reading.
     *
     * A payload that cannot be decoded is treated the same way, and deliberately not overwritten:
     * the caller keeps the revision it was merged against, so a bad read cannot make it destroy a
     * good copy that some other device still has.
     */
    private suspend fun <T> pullLightFile(
        fileUrl: HttpUrl,
        label: String,
        serializer: KSerializer<T>,
    ): LightSnapshot<T>? {
        return try {
            val request = requestBuilder(fileUrl).get().build()
            interactiveClient.newCall(request).await().use { response ->
                if (response.code == HttpStatus.SC_NOT_FOUND) return null

                if (!response.isSuccessful) {
                    logcat(LogPriority.ERROR) { "Failed to download $label: HTTP ${response.code}" }
                    return null
                }

                val etag = response.header("ETag")?.takeIf { it.isNotBlank() && it != "*" }
                    ?: response.header("Last-Modified")?.takeIf { it.isNotBlank() }

                val byteArray = response.body.byteStream().use { stream ->
                    GZIPInputStream(stream).use { gzipStream ->
                        gzipStream.readBytes()
                    }
                }

                val payload = try {
                    protoBuf.decodeFromByteArray(serializer, byteArray)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR) { "Bad $label payload received: ${e.message}" }
                    return null
                }

                LightSnapshot(payload, etag)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "$label pull failed: ${e.message}" }
            null
        }
    }

    /** Returns false when the remote moved under us or the write failed. */
    private suspend fun <T> pushLightFile(
        fileUrl: HttpUrl,
        label: String,
        serializer: KSerializer<T>,
        payload: T,
        etag: String?,
    ): Boolean {
        val byteArray = protoBuf.encodeToByteArray(serializer, payload)
        return try {
            withIOContext {
                val gzipped = ByteArrayOutputStream().also { bos ->
                    GZIPOutputStream(bos).use { it.write(byteArray) }
                }.toByteArray()

                ensureDirectoryExists()

                val body = gzipped.toRequestBody("application/octet-stream".toMediaType())
                val builder = requestBuilder(fileUrl).put(body)
                if (etag != null) {
                    builder.header("If-Match", etag)
                } else {
                    builder.header("If-None-Match", "*")
                }

                var uploaded = false
                interactiveClient.newCall(builder.build()).await().use {
                    when {
                        it.code == HTTP_PRECONDITION_FAILED -> logcat(LogPriority.INFO) {
                            "$label file changed under us; leaving it to the next sync"
                        }
                        !it.isSuccessful -> logcat(LogPriority.ERROR) {
                            "Failed to upload $label: HTTP ${it.code}"
                        }
                        else -> uploaded = true
                    }
                }
                uploaded
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG) { "$label push failed: ${e.message}" }
            false
        }
    }

    suspend fun pullProgress(): ProgressSnapshot? =
        pullLightFile(progressFileUrl(), "progress", SyncProgress.serializer())
            ?.let { ProgressSnapshot(it.payload, it.etag) }

    suspend fun pushProgress(progress: SyncProgress, etag: String?): Boolean =
        pushLightFile(progressFileUrl(), "progress", SyncProgress.serializer(), progress, etag)

    // ---- reading-history channel --------------------------------------------------------------

    private val historyFileName = "${appName}_history.proto.gz"

    private fun historyFileUrl(): HttpUrl {
        return requireRemoteUrl().newBuilder()
            .addPathSegment(historyFileName)
            .build()
    }

    /** Reads the reading-history file; null when it is absent, unreachable or unreadable. */
    suspend fun pullHistory(): LightSnapshot<SyncHistory>? =
        pullLightFile(historyFileUrl(), "history", SyncHistory.serializer())

    /** Returns false when the remote moved under us or the write failed. */
    suspend fun pushHistory(history: SyncHistory, etag: String?): Boolean =
        pushLightFile(historyFileUrl(), "history", SyncHistory.serializer(), history, etag)

    // ---- page-bookmark channel ----------------------------------------------------------------

    private val bookmarksFileName = "${appName}_bookmarks.proto.gz"

    private fun bookmarksFileUrl(): HttpUrl {
        return requireRemoteUrl().newBuilder()
            .addPathSegment(bookmarksFileName)
            .build()
    }

    /** Reads the page-bookmark file; null when it is absent, unreachable or unreadable. */
    suspend fun pullBookmarks(): LightSnapshot<SyncBookmarks>? =
        pullLightFile(bookmarksFileUrl(), "bookmarks", SyncBookmarks.serializer())

    /** Returns false when the remote moved under us or the write failed. */
    suspend fun pushBookmarks(bookmarks: SyncBookmarks, etag: String?): Boolean =
        pushLightFile(bookmarksFileUrl(), "bookmarks", SyncBookmarks.serializer(), bookmarks, etag)
    // SY <--

    suspend fun deleteSyncData(): DeleteSyncDataStatus {
        return withIOContext {
            try {
                if (syncPreferences.webdavUrl.get().isBlank()) {
                    return@withIOContext DeleteSyncDataStatus.NOT_INITIALIZED
                }
                // SY -->
                // The side channels are separate files. Leaving them behind would let the very next
                // sync read back most of what the user just deleted, so they go with the payload.
                val files = listOf(fileUrl(), progressFileUrl(), historyFileUrl(), bookmarksFileUrl())
                var deleted = 0
                for (file in files) {
                    val request = requestBuilder(file).delete().build()
                    client.newCall(request).await().use {
                        when {
                            it.code == HttpStatus.SC_NOT_FOUND -> Unit
                            it.isSuccessful -> deleted++
                            else -> {
                                logcat(LogPriority.ERROR) { "Failed to delete sync data: HTTP ${it.code}" }
                                return@withIOContext DeleteSyncDataStatus.ERROR
                            }
                        }
                    }
                }
                if (deleted == 0) DeleteSyncDataStatus.NO_FILES else DeleteSyncDataStatus.SUCCESS
                // SY <--
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Error occurred while deleting WebDAV sync data: ${e.message}" }
                DeleteSyncDataStatus.ERROR
            }
        }
    }
}
