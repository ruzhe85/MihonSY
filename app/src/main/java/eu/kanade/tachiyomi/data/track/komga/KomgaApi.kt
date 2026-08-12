package eu.kanade.tachiyomi.data.track.komga

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy

private const val READLIST_API = "/api/v1/readlists"

class KomgaApi(
    private val trackId: Long,
    private val client: OkHttpClient,
) {

    private val headers: Headers by lazy {
        Headers.Builder()
            .add("User-Agent", "TachiyomiSY v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()
    }

    private val json: Json by injectLazy()

    suspend fun getTrackSearch(url: String): TrackSearch =
        withIOContext {
            try {
                val track = with(json) {
                    if (url.contains(READLIST_API)) {
                        client.newCall(GET(url, headers))
                            .awaitSuccess()
                            .parseAs<ReadListDto>()
                            .toTrack()
                    } else {
                        client.newCall(GET(url, headers))
                            .awaitSuccess()
                            .parseAs<SeriesDto>()
                            .toTrack()
                    }
                }

                val progress = client
                    .newCall(
                        GET("${url.replace("/api/v1/series/", "/api/v2/series/")}/read-progress/tachiyomi", headers),
                    )
                    .awaitSuccess().let {
                        with(json) {
                            if (url.contains("/api/v1/series/")) {
                                it.parseAs<ReadProgressV2Dto>()
                            } else {
                                it.parseAs<ReadProgressDto>().toV2()
                            }
                        }
                    }

                track.apply {
                    cover_url = "$url/thumbnail"
                    tracking_url = url
                    total_chapters = progress.maxNumberSort.toLong()
                    status = when (progress.booksCount) {
                        progress.booksUnreadCount -> Komga.UNREAD
                        progress.booksReadCount -> Komga.COMPLETED
                        else -> Komga.READING
                    }
                    // MihonSY: use the count of completed books instead of the continuous read
                    // value. The continuous value (`lastReadContinuousNumberSort`) is 0 whenever
                    // books are marked individually (non-sequential), which made SY always show
                    // "0 / total" after switching to per-book PATCH sync.
                    last_chapter_read = progress.booksReadCount.toDouble()
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not get item: $url" }
                throw e
            }
        }

    suspend fun updateProgress(track: Track): Track {
        // MihonSY: for series, mark only the specific book (chapter) as read via per-book PATCH.
        // The old cumulative endpoint (PUT /read-progress/tachiyomi) marks every book up to the
        // given chapter as read, which does not match "I only read chapter N".
        if (track.tracking_url.contains("/api/v1/series/")) {
            updateSeriesBookReadProgress(track)
        } else {
            // Read lists keep the previous behaviour
            val payload = json.encodeToString(ReadProgressUpdateDto(track.last_chapter_read.toInt()))
            client.newCall(
                Request.Builder()
                    .url("${track.tracking_url}/read-progress/tachiyomi")
                    .headers(headers)
                    .put(payload.toRequestBody("application/json".toMediaType()))
                    .build(),
            )
                .awaitSuccess()
        }
        return getTrackSearch(track.tracking_url)
    }

    /**
     * Marks only the book matching [track]'s last_chapter_read as completed in Komga,
     * leaving earlier chapters untouched.
     */
    private suspend fun updateSeriesBookReadProgress(track: Track) {
        markBookCompleted(track.tracking_url, track.last_chapter_read.toDouble())
    }

    /**
     * Updates the page progress of the book matching [chapterNumber] (partial read).
     * Komga will mark the book in-progress, or completed automatically when the page
     * reaches the book's total page count.
     */
    suspend fun updateBookPageProgress(trackingUrl: String, chapterNumber: Double, page: Int) {
        if (!trackingUrl.contains("/api/v1/series/")) return
        val baseUrl = trackingUrl.substringBefore("/api/v1/")
        val seriesId = trackingUrl.substringAfterLast('/')
        val targetBook = findBookByNumberSort(baseUrl, seriesId, chapterNumber) ?: return

        val payload = json.encodeToString(BookReadProgressPageUpdateDto(page = page))
        client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/books/${targetBook.id}/read-progress")
                .headers(headers)
                .patch(payload.toRequestBody("application/json".toMediaType()))
                .build(),
        )
            .awaitSuccess()
        logcat { "Updated Komga book ${targetBook.id} (chapter $chapterNumber) page progress to $page" }
    }

    /**
     * Marks the book matching [chapterNumber] as completed (used when the local SY state
     * is newer than Komga's during reverse sync).
     */
    suspend fun markBookCompleted(trackingUrl: String, chapterNumber: Double) {
        if (!trackingUrl.contains("/api/v1/series/")) return
        val baseUrl = trackingUrl.substringBefore("/api/v1/")
        val seriesId = trackingUrl.substringAfterLast('/')
        val targetBook = findBookByNumberSort(baseUrl, seriesId, chapterNumber) ?: return

        val payload = json.encodeToString(BookReadProgressUpdateDto(completed = true))
        client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/books/${targetBook.id}/read-progress")
                .headers(headers)
                .patch(payload.toRequestBody("application/json".toMediaType()))
                .build(),
        )
            .awaitSuccess()
        logcat { "Marked Komga book ${targetBook.id} (chapter $chapterNumber) as read" }
    }

    /**
     * Fetches all books of the series with their per-book read progress (used by the
     * reverse sync, Komga -> SY).
     */
    suspend fun getSeriesBooks(trackingUrl: String): List<BookPageDto> {
        if (!trackingUrl.contains("/api/v1/series/")) return emptyList()
        val baseUrl = trackingUrl.substringBefore("/api/v1/")
        val seriesId = trackingUrl.substringAfterLast('/')
        return try {
            with(json) {
                withIOContext {
                    client.newCall(
                        GET("$baseUrl/api/v1/series/$seriesId/books?unpaged=true", headers),
                    )
                        .awaitSuccess()
                        .parseAs<BookPageWrapperDto>()
                        .content
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not fetch books for series $seriesId" }
            emptyList()
        }
    }

    /** Looks up the Komga book whose metadata.numberSort equals [numberSort]. */
    private suspend fun findBookByNumberSort(
        baseUrl: String,
        seriesId: String,
        numberSort: Double,
    ): BookPageDto? {
        val books = getSeriesBooks("$baseUrl/api/v1/series/$seriesId")

        val targetBook = books.firstOrNull { it.metadata.numberSort.toDouble() == numberSort }
        if (targetBook == null) {
            logcat(LogPriority.WARN) {
                "No Komga book found for chapter $numberSort in series $seriesId"
            }
        }
        return targetBook
    }

    private fun SeriesDto.toTrack(): TrackSearch = TrackSearch.create(trackId).also {
        it.title = metadata.title
        it.summary = metadata.summary
        it.publishing_status = metadata.status
    }

    private fun ReadListDto.toTrack(): TrackSearch = TrackSearch.create(trackId).also {
        it.title = name
    }
}
