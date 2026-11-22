package eu.kanade.tachiyomi.data.coil

import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.domain.manga.model.PagePreview

class PagePreviewKeyer : Keyer<PagePreview> {
    override fun key(data: PagePreview, options: Options): String {
        // Komiho: 与磁盘缓存同源，否则「同一个 key」在 Coil 内存缓存和磁盘缓存里会是两个东西。
        return data.cacheKey
    }
}
