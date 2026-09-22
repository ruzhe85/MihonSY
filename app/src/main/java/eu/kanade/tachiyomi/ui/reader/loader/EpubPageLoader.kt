package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.storage.EpubFile
import mihon.core.common.archive.ArchiveHandle
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Loader used to load a chapter from a .epub file.
 *
 * Komiho: 本地 / WebDAV / SMB 的 EPUB 都走这里（参数为窄接口 [ArchiveHandle]，
 * 本地是 ArchiveReader，远程是 RemoteZipReader / CachingArchiveHandle）。
 * 阅读方式是「抽取图片页」—— [EpubFile] 只解析 OPF/spine 里的 `<img>`/`<image xlink:href>`，
 * 因此纯文字书（小说）会得到 0 页，这是设计限制，不是文件损坏；
 * 这里给出明确提示，避免上层用通用的「No pages found」含糊带过。
 */
internal class EpubPageLoader(
    reader: ArchiveHandle,
    private val context: Context,
) : PageLoader() {

    private val epub = EpubFile(reader)

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val images = epub.getImagesFromPages()
        if (images.isEmpty()) {
            // MihonSY: reader 的 context 来自 ReaderActivity（Mihon 的 BaseActivity），
            // 本 fork 没有 Komiho 的 per-app 语言包装层，直接用应用资源即可。
            throw Exception(context.stringResource(MR.strings.loader_epub_no_images_error))
        }
        return images.mapIndexed { i, path ->
            val streamFn = { epub.getInputStream(path)!! }
            ReaderPage(i).apply {
                stream = streamFn
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        epub.close()
    }
}
