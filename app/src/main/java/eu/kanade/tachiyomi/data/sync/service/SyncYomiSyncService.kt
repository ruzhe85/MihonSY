package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import android.os.Build
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.sync.SyncNotifier
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import logcat.logcat
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.http.HttpStatus
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * SyncYomi client using protocol v2: the server performs the merge and returns the
 * data this device is missing. The request/response body is the same Tachiyomi backup
 * protobuf already produced for v1; only the transport differs.
 */
class SyncYomiSyncService(
    context: Context,
    json: Json,
    syncPreferences: SyncPreferences,
    private val notifier: SyncNotifier,

    private val protoBuf: ProtoBuf = Injekt.get(),
) : SyncService(context, json, syncPreferences) {

    private class SyncYomiException(message: String?) : Exception(message)

    @Serializable
    private data class SyncEvent(
        val event: SyncEventStatus,
        @SerialName("device_name")
        val deviceName: String? = null,
        val message: String? = null,
    )

    @Serializable
    private enum class SyncEventStatus {
        SYNC_STARTED,
        SYNC_SUCCESS,
        SYNC_FAILED,
        SYNC_ERROR,
        SYNC_CANCELLED,
    }

    override suspend fun doSync(syncData: SyncData): Backup? {
        reportSyncEvent(SyncEventStatus.SYNC_STARTED)

        try {
            val backup = syncData.backup ?: return null
            val remote = syncV2Merge(backup)

            reportSyncEvent(SyncEventStatus.SYNC_SUCCESS)
            // remote == null means the server reported nothing new to pull back; returning the
            // local backup reference lets SyncManager skip the restore (its identity check) while
            // still recording a successful sync.
            return remote ?: syncData.backup
        } catch (e: Exception) {
            if (e is CancellationException) {
                reportSyncEvent(SyncEventStatus.SYNC_CANCELLED, e.message)
                throw e
            }
            logcat(LogPriority.ERROR) { "Error syncing: ${e.message}" }
            notifier.showSyncError(e.message)
            reportSyncEvent(SyncEventStatus.SYNC_ERROR, e.message)
            return null
        }
    }

    /**
     * Push the local backup to the SyncYomi v2 server, which merges it server-side
     * and returns the data this device is missing.
     *
     * @return the merged backup to restore, or null when the server reported no changes.
     */
    private suspend fun syncV2Merge(backup: Backup): Backup? {
        val host = syncPreferences.clientHost.get().trimEnd('/')
        val apiKey = syncPreferences.clientAPIKey.get()
        val uploadUrl = "$host/api/sync/v2/merge"
        val timeout = 60L

        val rawBytes = protoBuf.encodeToByteArray(Backup.serializer(), backup)
        if (rawBytes.isEmpty()) {
            throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
        }
        val body = gzip(rawBytes).toRequestBody("application/octet-stream".toMediaType())

        val headers = Headers.Builder()
            .add("X-API-Token", apiKey)
            .add("X-Device-ID", syncPreferences.uniqueDeviceID())
            .add("X-Device-Name", Build.MODEL)
            .add("X-Sync-Cursor", syncPreferences.syncV2Cursor.get().toString())
            .add("X-Sync-Full", if (syncData.isFullSync) "true" else "false")
            .add("Content-Encoding", "gzip")
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(timeout, TimeUnit.SECONDS)
            .build()

        val response = client.newCall(POST(url = uploadUrl, headers = headers, body = body)).await()

        if (response.code == HttpStatus.SC_NOT_FOUND) {
            // Server without the v2 protocol.
            response.close()
            throw SyncYomiException(
                "当前 SyncYomi 服务端不支持 v2 同步协议，请升级到支持 v2 的版本。",
            )
        }
        if (!response.isSuccessful) {
            val responseBody = response.body.string()
            notifier.showSyncError("Failed to upload sync data: $responseBody")
            logcat(LogPriority.ERROR) { "SyncError: $responseBody" }
            throw SyncYomiException("Failed to upload sync data: $responseBody")
        }

        val cursor = response.headers["X-Sync-Cursor"]?.toLongOrNull()
            ?: throw SyncYomiException("Missing X-Sync-Cursor in server response")
        val changed = response.headers["X-Sync-Changed"]?.toBooleanStrictOrNull() ?: true
        val fullRequested = response.headers["X-Sync-Full-Requested"]?.toBooleanStrictOrNull() ?: false

        syncPreferences.syncV2Cursor.set(cursor)
        syncPreferences.syncV2FullRequested.set(fullRequested)

        if (!changed) {
            response.close()
            return null
        }

        val bytes = response.body.bytes()
        return try {
            protoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        } catch (e: SerializationException) {
            logcat(LogPriority.ERROR) { "Bad content responded from server: ${e.message}" }
            notifier.showSyncError("服务端返回的同步数据无法解析")
            throw SyncYomiException("Bad content responded from server: ${e.message}")
        }
    }

    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    private suspend fun reportSyncEvent(event: SyncEventStatus, message: String? = null) {
        withContext(NonCancellable) {
            try {
                val host = syncPreferences.clientHost.get().trimEnd('/')
                val apiKey = syncPreferences.clientAPIKey.get()
                val url = "$host/api/sync/event"

                val headers = Headers.Builder().add("X-API-Token", apiKey).build()

                val bodyObj = SyncEvent(
                    event = event,
                    deviceName = Build.MODEL,
                    message = message,
                )

                val jsonBody = json.encodeToString(SyncEvent.serializer(), bodyObj)
                val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = POST(url = url, headers = headers, body = requestBody)

                val client = OkHttpClient()
                client.newCall(request).await().close()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Failed to report sync event: ${e.message}" }
            }
        }
    }
}
