package eu.kanade.tachiyomi.data.track.komga

import kotlinx.serialization.Serializable

@Serializable
data class SeriesDto(
    val id: String,
    val libraryId: String,
    val name: String,
    val created: String?,
    val lastModified: String?,
    val fileLastModified: String,
    val booksCount: Int,
    val booksReadCount: Int,
    val booksUnreadCount: Int,
    val booksInProgressCount: Int,
    val metadata: SeriesMetadataDto,
    val booksMetadata: BookMetadataAggregationDto,
)

@Serializable
data class SeriesMetadataDto(
    val status: String,
    val created: String?,
    val lastModified: String?,
    val title: String,
    val titleSort: String,
    val summary: String,
    val summaryLock: Boolean,
    val readingDirection: String,
    val readingDirectionLock: Boolean,
    val publisher: String,
    val publisherLock: Boolean,
    val ageRating: Int?,
    val ageRatingLock: Boolean,
    val language: String,
    val languageLock: Boolean,
    val genres: Set<String>,
    val genresLock: Boolean,
    val tags: Set<String>,
    val tagsLock: Boolean,
)

@Serializable
data class BookMetadataAggregationDto(
    val authors: List<AuthorDto> = emptyList(),
    val releaseDate: String?,
    val summary: String,
    val summaryNumber: String,

    val created: String,
    val lastModified: String,
)

@Serializable
data class AuthorDto(
    val name: String,
    val role: String,
)

@Serializable
data class ReadProgressUpdateDto(
    val lastBookRead: Int,
)

@Serializable
data class ReadProgressUpdateV2Dto(
    val lastBookNumberSortRead: Double,
)

@Serializable
data class ReadListDto(
    val id: String,
    val name: String,
    val bookIds: List<String>,
    val createdDate: String,
    val lastModifiedDate: String,
    val filtered: Boolean,
)

// MihonSY -->
/** Payload for Komga's per-book read-progress endpoint: PATCH /api/v1/books/{id}/read-progress */
@Serializable
data class BookReadProgressUpdateDto(
    val completed: Boolean? = null,
)

/** Payload for updating a single book's page progress (partial read): {"page": N} */
@Serializable
data class BookReadProgressPageUpdateDto(
    val page: Int,
)

@Serializable
data class BookPageDto(
    val id: String,
    val metadata: BookPageMetadataDto,
    val readProgress: BookReadProgressDto? = null,
)

@Serializable
data class BookPageMetadataDto(
    val numberSort: Float,
)

/** Book-level read progress from Komga's books endpoint (per-book granularity). */
@Serializable
data class BookReadProgressDto(
    val page: Int = 0,
    val completed: Boolean = false,
    val lastModified: String? = null,
)

@Serializable
data class BookPageWrapperDto(
    val content: List<BookPageDto>,
)
// MihonSY <--

@Serializable
data class ReadProgressDto(
    val booksCount: Int,
    val booksReadCount: Int,
    val booksUnreadCount: Int,
    val booksInProgressCount: Int,
    val lastReadContinuousIndex: Int,
) {
    fun toV2() = ReadProgressV2Dto(
        booksCount,
        booksReadCount,
        booksUnreadCount,
        booksInProgressCount,
        lastReadContinuousIndex.toDouble(),
        booksCount.toFloat(),
    )
}

@Serializable
data class ReadProgressV2Dto(
    val booksCount: Int,
    val booksReadCount: Int,
    val booksUnreadCount: Int,
    val booksInProgressCount: Int,
    val lastReadContinuousNumberSort: Double,
    val maxNumberSort: Float,
)
