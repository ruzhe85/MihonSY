package tachiyomi.domain.chapter.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// SY -->
/**
 * The keys this app keeps in [Chapter.memo] next to whatever the source puts there.
 *
 * `memo` is a free-form bag owned by the source, so our entries have to be namespaced and have to
 * be ignored wherever a source memo is compared or copied: otherwise they make every chapter look
 * changed on each library update, and that copy would drop the value we just recorded.
 */
object ChapterMemo {

    /** Total pages of the chapter, recorded by the reader the first time it opens one. */
    const val PAGES_KEY = "sy_pages"

    private val appKeys = setOf(PAGES_KEY)

    /** Recorded page count, or null when this chapter has never been opened here. */
    fun pages(memo: JsonObject): Int? =
        (memo[PAGES_KEY] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    /** [memo] with the page count stored; returns the same instance when nothing changes. */
    fun withPages(memo: JsonObject, pages: Int): JsonObject {
        if (pages <= 0 || pages(memo) == pages) return memo
        return JsonObject(memo + (PAGES_KEY to JsonPrimitive(pages)))
    }

    /** [memo] without this app's keys, i.e. only what the source itself owns. */
    fun withoutAppKeys(memo: JsonObject): JsonObject =
        if (memo.keys.none { it in appKeys }) memo else JsonObject(memo.filterKeys { it !in appKeys })

    /**
     * [source] with this app's keys copied over from [local]: the source stays in charge of its own
     * keys (including removing them), while what this app recorded survives.
     */
    fun merge(source: JsonObject, local: JsonObject): JsonObject {
        val ours = local.filterKeys { it in appKeys }
        if (ours.isEmpty()) return source
        return JsonObject(source + ours)
    }
}
// SY <--
