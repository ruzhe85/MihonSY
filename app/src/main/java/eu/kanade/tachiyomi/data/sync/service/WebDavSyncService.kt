package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.sync.SyncNotifier
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
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
            val remoteSData = pullSyncData()

            if (remoteSData != null) {
                val localDeviceId = syncPreferences.uniqueDeviceID()
                val lastSyncDeviceId = remoteSData.deviceId

                logcat(LogPriority.DEBUG, "SyncService") {
                    "Local device ID: $localDeviceId, Last sync device ID: $lastSyncDeviceId"
                }

                // check if the last sync was done by the same device if so overwrite the remote data with the local data
                return if (lastSyncDeviceId == localDeviceId) {
                    pushSyncData(syncData)
                    syncData.backup
                } else {
                    // Merge the local and remote sync data
                    val mergedSyncData = mergeSyncData(syncData, remoteSData)
                    pushSyncData(mergedSyncData)
                    mergedSyncData.backup
                }
            }

            pushSyncData(syncData)
            return syncData.backup
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, "SyncService") { "Error syncing: ${e.message}" }
            notifier.showSyncError(e.message)
            return null
        }
    }

    private suspend fun pullSyncData(): SyncData? {
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

            val byteArray = response.body.byteStream().use { stream ->
                GZIPInputStream(stream).use { gzipStream ->
                    gzipStream.readBytes()
                }
            }

            return try {
                protoBuf.decodeFromByteArray(SyncData.serializer(), byteArray)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Bad sync data received from WebDAV server: ${e.message}" }
                // Return null so the next push overwrites the corrupted remote data
                null
            }
        }
    }

    private suspend fun pushSyncData(syncData: SyncData) {
        val backup = syncData.backup ?: return

        // Encode the full SyncData (including deviceId) so the next pull can tell
        // whether the remote was last written by this device
        val byteArray = protoBuf.encodeToByteArray(SyncData.serializer(), syncData)
        if (byteArray.isEmpty()) {
            throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
        }

        withIOContext {
            val gzipped = ByteArrayOutputStream().also { bos ->
                GZIPOutputStream(bos).use { it.write(byteArray) }
            }.toByteArray()

            ensureDirectoryExists()

            val body = gzipped.toRequestBody("application/octet-stream".toMediaType())
            val request = requestBuilder(fileUrl()).put(body).build()
            client.newCall(request).await().use {
                if (!it.isSuccessful) {
                    it.body.string()
                    logcat(LogPriority.ERROR) { "Failed to upload sync data: HTTP ${it.code}" }
                    throw IOException("Failed to upload sync data: HTTP ${it.code}")
                }
            }
        }
        logcat(LogPriority.DEBUG) { "WebDAV sync data uploaded" }
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

    suspend fun deleteSyncData(): DeleteSyncDataStatus {
        return withIOContext {
            try {
                if (syncPreferences.webdavUrl.get().isBlank()) {
                    return@withIOContext DeleteSyncDataStatus.NOT_INITIALIZED
                }
                val request = requestBuilder(fileUrl()).delete().build()
                client.newCall(request).await().use {
                    when {
                        it.code == HttpStatus.SC_NOT_FOUND -> DeleteSyncDataStatus.NO_FILES
                        it.isSuccessful -> DeleteSyncDataStatus.SUCCESS
                        else -> {
                            logcat(LogPriority.ERROR) { "Failed to delete sync data: HTTP ${it.code}" }
                            DeleteSyncDataStatus.ERROR
                        }
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Error occurred while deleting WebDAV sync data: ${e.message}" }
                DeleteSyncDataStatus.ERROR
            }
        }
    }
}
