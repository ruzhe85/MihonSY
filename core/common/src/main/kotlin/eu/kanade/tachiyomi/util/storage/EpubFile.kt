package eu.kanade.tachiyomi.util.storage

import mihon.core.common.archive.ArchiveHandle
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Wrapper over ZipFile to load files in epub format.
 *
 * Komiho: 参数类型是窄接口 [ArchiveHandle] 而非具体 ArchiveReader，
 * 本类只用到 getInputStream，与具体归档实现解耦。
 *
 * 解析健壮性对齐 Koharia 的 EpubReader（2026-09）：
 *  - spine 直接引用 image 开头 media-type 条目的图片型 EPUB（漫画/条漫/Divina）也能出页；
 *  - href 走 URLDecoder 解码（%20 / 中文 / 特殊字符不再拼错路径导致 NPE）；
 *  - 单个条目缺失时跳过该页而非整体崩溃。
 */
class EpubFile(private val reader: ArchiveHandle) : Closeable by reader {

    /**
     * Path separator used by this epub.
     */
    private val pathSeparator = getPathSeparator()

    /**
     * Returns an input stream for reading the contents of the specified zip file entry.
     */
    fun getInputStream(entryName: String): InputStream? {
        return reader.getInputStream(entryName)
    }

    /**
     * Returns the path of all the images found in the epub file.
     */
    fun getImagesFromPages(): List<String> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val pages = getPagesFromDocument(doc)
        return getImagesFromPages(pages, ref)
    }

    /**
     * Returns the path to the package document.
     */
    fun getPackageHref(): String {
        val meta = getInputStream(resolveZipPath("META-INF", "container.xml"))
        if (meta != null) {
            val metaDoc = meta.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
            val path = metaDoc.getElementsByTag("rootfile").first()?.attr("full-path")
            if (path != null) {
                return path
            }
        }
        return resolveZipPath("OEBPS", "content.opf")
    }

    /**
     * Returns the package document where all the files are listed.
     */
    fun getPackageDocument(ref: String): Document {
        // Komiho: 原来是 getInputStream(ref)!!。容器声明的路径拼不上（大小写/编码/DRM 包的
        // 非常规写法）时抛 NPE，而 NPE 的 message 是空的，一路传到阅读器只剩
        // 「Failed to load pages: null」。换成带上下文的报错，至少知道是哪个路径找不到。
        val stream = getInputStream(ref)
            ?: throw IOException("EPUB 包文档缺失: $ref（META-INF/container.xml 指向的路径在压缩包里不存在）")
        return stream.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    }

    /**
     * Komiho: 是否受 DRM 保护（Adobe ADEPT / Readium LCP / FairPlay 等）。
     *
     * 只做字符串级判定、不解密 —— 本类只抽图片，够用且零成本。判据：
     *  - `META-INF/rights.xml` 存在 → ADEPT 的版权文件，直接判定；
     *  - `META-INF/encryption.xml` 里出现**非字体混淆**的 EncryptionMethod → DRM。
     *    字体混淆（IDPF embedding / Adobe pdf enc#RC）是合法且极常见的：它只把字体字节做
     *    XOR，不影响图片抽取，必须排除，否则会把一大批正常书误判成加密。
     *
     * 读不出 / 格式异常一律返回 false —— 宁可回落原来的「没有图片页」提示，也不误拦正常书。
     */
    fun isDrmProtected(): Boolean {
        val rights = getInputStream(resolveZipPath("META-INF", "rights.xml"))
        if (rights != null) {
            rights.close()
            return true
        }
        val enc = getInputStream(resolveZipPath("META-INF", "encryption.xml")) ?: return false
        val xml = enc.use { it.readBytes().toString(Charsets.UTF_8) }
        return ENCRYPTION_METHOD_ALGORITHM.findAll(xml)
            .map { it.groupValues[2] }
            .any { it !in FONT_OBFUSCATION_ALGORITHMS }
    }

    /**
     * Returns all the pages from the epub, in spine reading order.
     * 与 Mihon 原版不同：spine 直接引用 image 开头 media-type 条目的「图片型 EPUB」也纳入，
     * 否则这类书会被过滤成 0 页 → 上层报「无图片」→ 回退文件浏览器。
     */
    private fun getPagesFromDocument(document: Document): List<ManifestItem> {
        val manifest = getManifestFromDocument(document)
        return document.select("*|spine > *|itemref")
            .mapNotNull { itemRef -> manifest[itemRef.attr("idref")] }
    }

    private fun getManifestFromDocument(document: Document): Map<String, ManifestItem> {
        return document.select("*|manifest > *|item")
            .associate { item ->
                item.attr("id") to ManifestItem(
                    href = item.attr("href"),
                    mediaType = item.attr("media-type"),
                    properties = item.attr("properties"),
                )
            }
    }

    /**
     * Returns all the images contained in every page from the epub.
     * 保留宽松行为（收集页面里所有 img / svg:image，不去重到「必须恰好 1 张」），
     * 以不回归现有能正常打开的 EPUB；同时新增 image 开头 media-type 的 spine 直引页与 href 解码。
     */
    private fun getImagesFromPages(pages: List<ManifestItem>, packageHref: String): List<String> {
        if (pages.isEmpty()) return emptyList()
        val basePath = getParentDirectory(packageHref)
        val result = ArrayList<String>(pages.size)
        pages.forEach { page ->
            val entryPath = resolveZipPath(basePath, decodePathHref(page.href))
            // 图片型 EPUB：spine 项本身就是一整页图，直接采用。
            if (page.mediaType.startsWith("image/", ignoreCase = true)) {
                result += entryPath
                return@forEach
            }

            val document = getInputStream(entryPath)?.use { Jsoup.parse(it, null, "") } ?: return@forEach
            val imageBasePath = getParentDirectory(entryPath)
            val imagePaths = buildList {
                document.allElements.forEach {
                    when (it.tagName()) {
                        "img" -> it.attr("src").ifBlank { null }?.let(::add)
                        "image" -> it.attr("xlink:href").ifBlank { it.attr("href") }.ifBlank { null }?.let(::add)
                    }
                }
            }
                .map { resolveZipPath(imageBasePath, decodePathHref(it)) }
                .distinct()
            result += imagePaths
        }
        return result.distinct()
    }

    /** 解码百分号转义，且不让字面 '+' 被当成空格（EPUB href 常见）。 */
    private fun decodePathHref(href: String): String {
        return URLDecoder.decode(href.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }

    /**
     * Returns the path separator used by the epub file.
     */
    private fun getPathSeparator(): String {
        val meta = getInputStream("META-INF\\container.xml")
        return if (meta != null) {
            meta.close()
            "\\"
        } else {
            "/"
        }
    }

    /**
     * Resolves a zip path from base and relative components and a path separator.
     */
    private fun resolveZipPath(basePath: String, relativePath: String): String {
        if (relativePath.startsWith(pathSeparator)) {
            // Path is absolute, so return as-is.
            return relativePath
        }

        var fixedBasePath = basePath.replace(pathSeparator, File.separator)
        if (!fixedBasePath.startsWith(File.separator)) {
            fixedBasePath = "${File.separator}$fixedBasePath"
        }

        val fixedRelativePath = relativePath.replace(pathSeparator, File.separator)
        val resolvedPath = File(fixedBasePath, fixedRelativePath).canonicalPath
        return resolvedPath.replace(File.separator, pathSeparator).substring(1)
    }

    /**
     * Gets the parent directory of a path.
     */
    private fun getParentDirectory(path: String): String {
        val separatorIndex = path.lastIndexOf(pathSeparator)
        return if (separatorIndex >= 0) {
            path.substring(0, separatorIndex)
        } else {
            ""
        }
    }

    private data class ManifestItem(
        val href: String,
        val mediaType: String,
        val properties: String = "",
    )

    private companion object {
        /**
         * 字体混淆算法 —— **不是** DRM。只对字体字节做 XOR，图片不受影响，因此不能因为
         * encryption.xml 里有它就判成加密。
         */
        val FONT_OBFUSCATION_ALGORITHMS = setOf(
            "http://www.idpf.org/2008/embedding",
            "http://ns.adobe.com/pdf/enc#RC",
        )

        /** 抓 EncryptionMethod 的 Algorithm 属性（单/双引号都认），值在捕获组 2。 */
        val ENCRYPTION_METHOD_ALGORITHM = Regex("""Algorithm\s*=\s*(["'])([^"']+)\1""")
    }
}
