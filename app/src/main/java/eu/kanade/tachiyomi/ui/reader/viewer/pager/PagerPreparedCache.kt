package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Precision
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.data.coil.enhanced
import eu.kanade.tachiyomi.data.coil.pageIndex
import eu.kanade.tachiyomi.data.coil.prewarm
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.isStandardImageStream
import eu.kanade.tachiyomi.util.EnhanceTimings
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Pager 单页的「预处理结果」（SY: Page 模式流畅度优化 A+B+C）。
 *
 * Webtoon 流畅的核心是「解码好了等你滑」（RecyclerView 提前 bind）；Pager 过去是
 * 「你滑了才开始解码」（holder 在滑动当下才实例化并跑完整条 stream→处理→解码链）。
 * 本类把这条链的产物缓存下来：
 * - [source]：处理完成后的整图（内存 Buffer 系；回放用 [BufferedSource.peek]，消费不消耗本体）
 * - [isAnimated] / [background]：C 优化——动图/背景标志全链路只解析一次
 * - [decodedBitmap]：图像增强开启时的屏幕尺寸预解码结果（Lanczos 在滑动前完成），
 *   非空时 holder 直接走 bitmap 路径，不再二次解码
 *
 * [enhancementMode] / [cropBorders] 记录生成时的设置，读取时校验，设置变更即失效。
 * 2026-09-19 起改用 [enhancementKey]（覆盖所有影响像素的设置）—— 见 get()。
 */
class PagerPreparedPage(
    val source: BufferedSource,
    /** 双页合并的原始第二页流；仅 [layoutApplied]=false 的合并分支缓存它，供 holder 补跑布局。 */
    val source2: BufferedSource?,
    val isAnimated: Boolean,
    val background: Drawable?,
    val decodedBitmap: Bitmap?,
    val enhancementMode: Int,
    val cropBorders: Boolean,
    /**
     * Komiho (2026-09-19): 「会影响像素的设置」指纹，取自 [ReaderPreferences.enhancementCacheKey]。
     * 读取时与当前设置比对，不一致即作废。
     *
     * 以前只比 [enhancementMode]，所以换 AI 模型 / 换倍率 / 改裁边之后，这一页仍会用旧设置的
     * 结果，要等 LRU 淘汰、退出重进或一直划动才更新。默认值在**构造时**求值 ⇒ 所有构造点自动
     * 写入当时的设置，不必逐个传参。
     */
    val enhancementKey: String = Injekt.get<ReaderPreferences>().enhancementCacheKey(),
    /** 增强预解码耗时（毫秒）；-1 = 未走预解码（关闭/动图/失败/布局未应用）。 */
    val enhanceElapsedMillis: Long,
    /** false = 仅完成通用预处理（流物化+嗅探），布局处理（分割/合并）留待 holder 补跑。 */
    val layoutApplied: Boolean,
)

/**
 * 预处理结果 LRU 缓存（键 = ReaderPage 对象同一性 + 双页配对；容量 3 ≈ 前页/当前页/后页）。
 * ReaderPage 未覆写 equals/hashCode → Pair 作键即身份键；章节重载会生成新 Page 对象，
 * 天然不会命中旧条目，旧条目由 LRU 淘汰。
 */
class PagerPreparedCache(private val maxSize: Int = 3) {

    private val lock = Any()
    private val map = LinkedHashMap<Pair<ReaderPage, ReaderPage?>, PagerPreparedPage>(8, 0.75f, true)

    fun key(page: ReaderPage, extraPage: ReaderPage?): Pair<ReaderPage, ReaderPage?> = page to extraPage

    fun get(key: Pair<ReaderPage, ReaderPage?>): PagerPreparedPage? = synchronized(lock) {
        val value = map[key] ?: return null
        val preferences = Injekt.get<ReaderPreferences>()
        // 档位先比（便宜、失败时日志友好），再比设置指纹（覆盖 AI 模型 / 倍率 / tile / 裁边）。
        // 只比档位是不够的：换模型时 enhancementMode 都是 5，缓存会一直命中 → 设置不生效。
        if (value.enhancementMode != preferences.enhancementMode.get() ||
            value.enhancementKey != preferences.enhancementCacheKey()
        ) {
            map.remove(key)
            return null
        }
        value
    }

    fun put(key: Pair<ReaderPage, ReaderPage?>, value: PagerPreparedPage) = synchronized(lock) {
        map[key] = value
        while (map.size > maxSize) {
            val it = map.entries.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }

    fun clear() = synchronized(lock) { map.clear() }
}

/**
 * 纯 Prepare 管线（无任何副作用），holder 与后台预热共用。
 *
 * C 方案分层：所有页都完成「通用预处理」（流物化 + 动图嗅探）；能无副作用完成布局
 * （process/merge 的纯分支）的页继续做背景选择 + 增强预解码（layoutApplied=true）；
 * 副作用分支（双页分割 onPageSplit、双页合并 splitDoublePages）返回 layoutApplied=false
 * 的通用预处理结果，布局处理由 holder 补跑（applyLegacyLayout）。
 */
object PagerPagePreparer {

    /**
     * 处理一页并返回预处理结果；返回 null 仅表示流不可用或管线异常（此时 holder 走完整旧管线）。
     * [viewHeight] 用于宽页居中边距计算；holder 传自身高度，预热传 pager 高度（两者等高）。
     */
    suspend fun preparePure(
        viewer: PagerViewer,
        page: ReaderPage,
        extraPage: ReaderPage?,
        viewHeight: Int,
    ): PagerPreparedPage? {
        val streamFn = page.stream ?: return null
        val config = viewer.config
        val enhancementMode = Injekt.get<ReaderPreferences>().enhancementMode.get()
        return withIOContext {
            try {
                // C：整图一次性物化进内存 Buffer（共享分段），后续所有 peek/回放零重复 IO
                val source1 = streamFn().buffered(16).use { Buffer().readFrom(it) }
                val source2 = extraPage?.stream?.invoke()?.buffered(16)?.use { Buffer().readFrom(it) }
                // C：动图标志只嗅探一次，handleWideImage 等下游直接复用（旧实现会重复嗅探）
                val isAnimated = ImageUtil.isAnimatedAndSupported(source1) ||
                    (source2 != null && ImageUtil.isAnimatedAndSupported(source2))

                if (config.dualPageSplit) {
                    val itemSource = processPure(viewer, page, source1)
                        // C 方案：副作用分支（宽图分割需 onPageSplit 插 InsertPage）——
                        // 通用预处理照常缓存，布局处理留给 holder 补跑；增强预解码跳过
                        //（预解码输入须是布局后的半图，整图比例对不上）。
                        ?: return@withIOContext PagerPreparedPage(
                            source = source1,
                            source2 = null,
                            isAnimated = isAnimated,
                            background = null,
                            decodedBitmap = null,
                            enhancementMode = enhancementMode,
                            cropBorders = config.imageCropBorders,
                            enhanceElapsedMillis = -1L,
                            layoutApplied = false,
                        )
                    finishLayout(viewer, itemSource, isAnimated, enhancementMode, config, page.index)
                } else {
                    val itemSource = mergePure(viewer, page, extraPage, source1, source2, isAnimated, viewHeight)
                    if (itemSource == null) {
                        // C 方案：合并分支（splitDoublePages 副作用）——同上，缓存通用预处理结果
                        PagerPreparedPage(
                            source = source1,
                            source2 = source2,
                            isAnimated = isAnimated,
                            background = null,
                            decodedBitmap = null,
                            enhancementMode = enhancementMode,
                            cropBorders = config.imageCropBorders,
                            enhanceElapsedMillis = -1L,
                            layoutApplied = false,
                        )
                    } else {
                        finishLayout(viewer, itemSource, isAnimated, enhancementMode, config, page.index)
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.INFO, e) { "pager preparePure failed, falling back to legacy path" }
                null
            }
        }
    }

    /** 布局完成后的收尾：背景选择 + 计时的增强预解码（A）。 */
    private suspend fun finishLayout(
        viewer: PagerViewer,
        itemSource: BufferedSource,
        isAnimated: Boolean,
        enhancementMode: Int,
        config: PagerConfig,
        pageIndex: Int,
    ): PagerPreparedPage {
        val background = if (!isAnimated && config.automaticBackground) {
            ImageUtil.chooseBackground(viewer.activity, itemSource.peek())
        } else {
            null
        }
        val decodeStart = android.os.SystemClock.uptimeMillis()
        val bitmap = maybePreDecodeEnhanced(
            viewer,
            itemSource,
            isAnimated,
            enhancementMode,
            config.imageCropBorders,
            pageIndex,
        )
        // Komiho: 角标口径 —— 优先用解码器登记的「解码 + 增强实际计算耗时」（已剔除等锁与
        // 线程排队，见 EnhanceTimings）；取不到才回退旧口径（Coil 外层墙钟，含排队会虚高，
        // 实测同一页可被显示成 6.9s / 12.1s，而实际只花 2.7s）。
        val pureEnhanceMs = EnhanceTimings.take(pageIndex)
        return PagerPreparedPage(
            source = itemSource,
            source2 = null,
            isAnimated = isAnimated,
            background = background,
            decodedBitmap = bitmap,
            enhancementMode = enhancementMode,
            cropBorders = config.imageCropBorders,
            enhanceElapsedMillis = if (bitmap != null) {
                pureEnhanceMs ?: (android.os.SystemClock.uptimeMillis() - decodeStart)
            } else {
                -1L
            },
            layoutApplied = true,
        )
    }

    /** 对应 PagerPageHolder.process() 的纯版本：宽页分割需回调 onPageSplit → 返回 null（holder 补跑布局）。 */
    private fun processPure(viewer: PagerViewer, page: ReaderPage, imageSource: BufferedSource): BufferedSource? {
        val config = viewer.config
        if (config.dualPageRotateToFit) {
            return if (ImageUtil.isWideImage(imageSource)) {
                val rotation = if (config.dualPageRotateToFitInvert) -90f else 90f
                ImageUtil.rotateImage(imageSource, rotation)
            } else {
                imageSource
            }
        }

        if (page is eu.kanade.tachiyomi.ui.reader.model.InsertPage) {
            return splitInHalfPure(viewer, page, imageSource)
        }

        if (!ImageUtil.isWideImage(imageSource)) {
            return imageSource
        }

        // 宽页 + 双页分割：旧管线会 onPageSplit 插入 InsertPage（副作用）→ 放弃
        return null
    }

    /** 对应 PagerPageHolder.mergePages() 的纯版本：合并全程有副作用 → 返回 null（holder 补跑布局）。 */
    private fun mergePure(
        viewer: PagerViewer,
        page: ReaderPage,
        extraPage: ReaderPage?,
        imageSource: BufferedSource,
        imageSource2: BufferedSource?,
        isAnimated: Boolean,
        viewHeight: Int,
    ): BufferedSource? {
        if (imageSource2 == null) {
            return handleWideImagePure(viewer, imageSource, isAnimated, viewHeight)
        }
        // 原实现无副作用的唯一分支：fullPage 已置位时直接原样返回
        if (page.fullPage) return imageSource
        return null
    }

    /** 对应 PagerPageHolder.handleWideImage()，C：复用已解析的 isAnimated，不再重复嗅探。 */
    private fun handleWideImagePure(
        viewer: PagerViewer,
        imageSource: BufferedSource,
        isAnimated: Boolean,
        viewHeight: Int,
    ): BufferedSource {
        val config = viewer.config
        return if (
            !isAnimated &&
            viewHeight > 0 &&
            ImageUtil.isWideImage(imageSource) &&
            config.centerMarginType and PagerConfig.CenterMarginType.WIDE_PAGE_CENTER_MARGIN > 0 &&
            !config.imageCropBorders
        ) {
            ImageUtil.addHorizontalCenterMargin(imageSource, viewHeight, viewer.activity)
        } else {
            imageSource
        }
    }

    /** 对应 PagerPageHolder.splitInHalf() 的纯版本（仅 InsertPage 分支会走到）。 */
    private fun splitInHalfPure(viewer: PagerViewer, page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is eu.kanade.tachiyomi.ui.reader.model.InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is eu.kanade.tachiyomi.ui.reader.model.InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer -> ImageUtil.Side.LEFT
            else -> ImageUtil.Side.RIGHT
        }
        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }
        val sideMargin = if (
            (viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN) > 0 &&
            viewer.config.doublePages &&
            !viewer.config.imageCropBorders
        ) {
            48
        } else {
            0
        }
        return ImageUtil.splitInHalf(imageSource, side, sideMargin)
    }

    /**
     * 增强预解码：与 ReaderPageImageView 增强路径同参（customDecoder + enhanced + 屏幕尺寸），
     * 用 [Buffer.clone] 喂数据不消耗缓存本体。失败静默返回 null，holder 回退原路径。
     */
    private suspend fun maybePreDecodeEnhanced(
        viewer: PagerViewer,
        source: BufferedSource,
        isAnimated: Boolean,
        enhancementMode: Int,
        cropBorders: Boolean,
        pageIndex: Int,
    ): Bitmap? {
        if (isAnimated || enhancementMode == 0) return null
        if (!isStandardImageStream(source)) return null
        val width = viewer.pager.width
        val height = viewer.pager.height
        if (width <= 0 || height <= 0) return null
        return runCatching {
            val context = viewer.activity
            val request = ImageRequest.Builder(context)
                .data(source.peek())
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .enhanced(true)
                .customDecoder(true)
                // Komiho 诊断：标出「这是预载请求」+ 页号，便于与 holder 侧请求区分。
                .prewarm(true)
                .pageIndex(pageIndex)
                .size(width, height)
                .precision(Precision.INEXACT)
                .cropBorders(cropBorders)
                .crossfade(false)
                .build()
            val result = context.imageLoader.execute(request) as? SuccessResult
            (result?.image as? BitmapImage)?.bitmap
        }
            .onFailure { logcat(LogPriority.INFO, it) { "pager pre-decode failed, holder will decode in-view" } }
            .getOrNull()
    }
}
