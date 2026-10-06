package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import mihon.core.common.archive.ArchivePasswordException
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileNotFoundException

/**
 * Komiho 诊断：条漫路径的绑定 / 增强日志。pager 那侧用的是 `Waifu2xPrefetch` / `Waifu2xHolder`，
 * 条漫原先一条日志都没有，重复解码只能从 Waifu2xTiming 的 `holder#N` 反推。
 */
private const val KOMIHA_WEBTOON_TAG = "Waifu2xWebtoon"

/**
 * Holder of the webtoon reader for a single page of a chapter.
 *
 * @param frame the root view for this holder.
 * @param viewer the webtoon viewer.
 * @constructor creates a new webtoon holder.
 */
class WebtoonPageHolder(
    private val frame: ReaderPageImageView,
    viewer: WebtoonViewer,
) : WebtoonBaseHolder(frame, viewer) {

    /**
     * Loading progress bar to indicate the current progress.
     */
    private val progressIndicator = createProgressIndicator()

    /**
     * Progress bar container. Needed to keep a minimum height size of the holder, otherwise the
     * adapter would create more views to fill the screen, which is not wanted.
     */
    private lateinit var progressContainer: ViewGroup

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    /**
     * Getter to retrieve the height of the recycler view.
     */
    private val parentHeight
        get() = viewer.recycler.height

    /**
     * Page of a chapter.
     */
    private var page: ReaderPage? = null

    private val scope = MainScope()

    /**
     * Job for loading the page.
     */
    private var loadJob: Job? = null

    /**
     * Komiho (2026-09-23): 已经渲染出来的页，以及当时的增强指纹。
     *
     * [bind] 每次都会重启 [loadPageAndProcessStatus]，而后者用 `collectLatest` 订阅
     * [ReaderPage.statusFlow] —— StateFlow 会把当前值**重放**给新订阅者，所以一页只要已经是
     * Ready，**任何一次重新绑定都会立刻再跑一遍 [setImage]**。偏偏增强请求是 memory/disk 双
     * DISABLED 的（[ReaderPageImageView] 里刻意不留缓存），重跑一次就是一次完整解码 + 增强
     * （实测单页 2.5~6s，日志里同一页连着算两遍）。
     *
     * pager 那边靠 `renderedKey` 系列挡住同页重复渲染，条漫一直没有这道守卫 —— 这里补上。
     * 指纹取 [ReaderPreferences.enhancementCacheKey]：换模型 / 换倍率 / 改裁边都会变，
     * 所以「改设置要立刻生效」不受影响；只有设置没变时的重复绑定才会被跳过（画面还在，不需重画）。
     */
    private var renderedPage: ReaderPage? = null
    private var renderedEnhancementMode = -1
    private var renderedEnhancementKey = ""

    /**
     * Komiho: 本次解码请求对应的页。增强是**异步**在 Coil 解码器线程里跑的，[frame.onImageLoaded]
     * 回来时 [page] 可能已经被重绑到别的页，所以用这个字段固定归属，供
     * [WebtoonViewer.onPageRendered] 记账（跨章预载窗口 / 看门狗）。
     */
    private var renderingPage: ReaderPage? = null

    init {
        refreshLayoutParams()

        frame.onImageLoaded = {
            onImageDecoded()
            // Komiho: 图片（含 AI 增强）真正落地 —— 通知 viewer 记账（跨章预载窗口/看门狗）。
            renderingPage?.let { viewer.onPageRendered(it) }
            // MihonSY: with "original resolution" the image is drawn at 1:1 pixels, but the
            // view still measures its height at fit-width ratio (imageWidth * screenWidth),
            // which is taller than the 1:1 image and leaves a large black gap below each
            // strip. Match the item height to the image's real 1:1 height instead.
            if (viewer.config.originalSize && frame.imageSHeight > 0) {
                frame.layoutParams?.height = frame.imageSHeight
                frame.requestLayout()
            }
            // MihonSY: page image is Ready — let the auto-webtoon check run immediately
            // (its own guards make it a cheap no-op once done/decided).
            page?.let { viewer.activity.onPageLoaded(it) }
        }
        frame.onImageLoadError = { error -> setError(error) }
        frame.onScaleChanged = { viewer.activity.hideMenu() }
    }

    /**
     * Binds the given [page] with this view holder, subscribing to its state.
     */
    fun bind(page: ReaderPage) {
        // Komiho 诊断：条漫没有 pager 那套 holder 日志，重复绑定只能靠这一行看出来
        // （同一 id 出现两次 = 同一个 holder 被重绑；不同 id = holder 被重建）。
        android.util.Log.d(
            KOMIHA_WEBTOON_TAG,
            "bind page=${page.index} id=${System.identityHashCode(this)}",
        )
        this.page = page
        loadJob?.cancel()
        loadJob = scope.launch { loadPageAndProcessStatus() }
        refreshLayoutParams()
    }

    private fun refreshLayoutParams() {
        frame.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (!viewer.isContinuous) {
                bottomMargin = 15.dpToPx
            }

            val margin = Resources.getSystem().displayMetrics.widthPixels * (viewer.config.sidePadding / 100f)
            marginEnd = margin.toInt()
            marginStart = margin.toInt()
        }
    }

    /**
     * Called when the view is recycled and added to the view pool.
     */
    override fun recycle() {
        loadJob?.cancel()
        loadJob = null

        removeErrorLayout()
        frame.recycle()
        // Komiho: 本次解码请求作废 —— 之后再来的 onImageLoaded 不能再算到「当前这一页」头上。
        renderingPage = null
        progressIndicator.setProgress(0)
        progressContainer.isVisible = true
        // Komiho (2026-09-26): frame.recycle() 已把当前图像清空，但这里必须同步清掉
        // 「已渲染」标记。否则 holder 被回收后重绑到同一页（该页仍 Ready）时，setImage()
        // 的 renderedPage===currentPage 守卫会命中而直接 return —— 既不重绘清空的图、也不隐藏
        // 转圈，表现为「转圈卡死，退出重进才恢复」。100+ 小图时回收/重绑极频繁，最易触发。
        // 清掉后重绑必走完整 setImage 重绘并隐藏转圈；非回收的重绑（适配器通知）不受影响。
        renderedPage = null
        renderedEnhancementMode = -1
        renderedEnhancementKey = ""
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if there is no page or the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val page = page ?: return
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading
     */
    private fun setDownloading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        val currentPage = page ?: return
        val preferences = Injekt.get<ReaderPreferences>()
        val enhancementMode = preferences.enhancementMode.get()
        val enhancementKey = preferences.enhancementCacheKey()
        if (
            renderedPage === currentPage &&
            renderedEnhancementMode == enhancementMode &&
            renderedEnhancementKey == enhancementKey
        ) {
            // 同一页 + 同一套「影响像素的设置」已经渲染过（另一条状态重发，或同一 holder 被重绑）
            // → 直接退出，不再烧一次解码 + 增强。画面还在，跳过不影响显示。
            android.util.Log.d(
                KOMIHA_WEBTOON_TAG,
                "page=${currentPage.index} skip reason=already-rendered " +
                    "id=${System.identityHashCode(this)}",
            )
            return
        }

        progressIndicator.setProgress(0)

        val streamFn = currentPage.stream ?: return

        try {
            val (source, isAnimated) = withIOContext {
                val source = streamFn().use { process(Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                Pair(source, isAnimated)
            }
            withUIContext {
                // Komiho: 这里**刻意不做** pager 那套 `onPrepareStart` + `abortProcessing()` 抢占：
                // 条漫 holder 没有 pager 的自愈（`renderedPage` 守卫会把「被抢占后的未增强图」一直
                // 钉住，重绑也不会重算），抢占等于把某个预载页永久降级成未增强。排队优先级已由
                // Waifu2x 的可见页 / 准可见页闸门覆盖（见 WebtoonViewer 对 visiblePageIndex /
                // urgentPageIndex 的登记），残差只剩「正在跑的那一次」，不值得拿画质去换。
                // Komiho: 记下本次解码请求归谁，供 onImageLoaded 回调记账。
                renderingPage = currentPage
                // Komiho 诊断：条漫页号（条漫不走 PagerPreparedCache，来源只会是 holder）。
                frame.pageIndex = page?.index ?: -1
                frame.setImage(
                    source,
                    isAnimated,
                    ReaderPageImageView.Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = if (viewer.config.originalSize) {
                            SubsamplingScaleImageView.SCALE_TYPE_ORIGINAL_SIZE
                        } else {
                            SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH
                        },
                        cropBorders =
                        (viewer.config.imageCropBorders && viewer.isContinuous) ||
                            (viewer.config.continuousCropBorders && !viewer.isContinuous),
                    ),
                )
                removeErrorLayout()
            }
            // 真的把图交出去之后才记账：上面的 catch 分支走不到这里 ⇒ 失败后仍可重来。
            renderedPage = currentPage
            renderedEnhancementMode = enhancementMode
            renderedEnhancementKey = enhancementKey
            android.util.Log.d(
                KOMIHA_WEBTOON_TAG,
                "render page=${currentPage.index} mode=$enhancementMode " +
                    "id=${System.identityHashCode(this)}",
            )
        } catch (e: Throwable) {
            // Komiho: 章节缓存文件被清掉时（最常见于 App 退后台被系统清 cache 目录、回前台继续阅读）
            // 会在这里以 FileNotFoundException 冒出来 —— 此刻 page.status 仍是 Ready，报错给用户只会
            // 得到一个「点重试不生效」的死页（原因见 HttpPageLoader.retryPage 的注释）。交给 loader
            // 静默重新入队重下，界面回到「加载中」，随后由 statusFlow 驱动重渲染。
            if (e is FileNotFoundException &&
                currentPage.chapter.pageLoader?.healMissingCache(currentPage) == true
            ) {
                logcat(LogPriority.WARN) {
                    "page=${currentPage.index} 章节缓存文件丢失，已重新入队: ${e.message}"
                }
                return
            }
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun process(imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (viewer.config.dualPageSplit) {
            val isDoublePage = ImageUtil.isWideImage(imageSource)
            if (isDoublePage) {
                val upperSide = if (viewer.config.dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
                return ImageUtil.splitAndMerge(imageSource, upperSide)
            }
        }

        return imageSource
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        // Komiho: 失败同样是「这一页的终态」—— 让跨章预载窗口记账。加载期就报错时
        // renderingPage 还是空的，退回收 page（该页确实已经是终态，不该再被当成「还在跑」）。
        (renderingPage ?: page)?.let { viewer.onPageRendered(it) }
        progressContainer.isVisible = false
        initErrorLayout(error)
    }

    /**
     * Called when the image is decoded and going to be displayed.
     */
    private fun onImageDecoded() {
        progressContainer.isVisible = false
        removeErrorLayout()
    }

    /**
     * Creates a new progress bar.
     */
    private fun createProgressIndicator(): ReaderProgressIndicator {
        progressContainer = FrameLayout(context)
        frame.addView(progressContainer, MATCH_PARENT, parentHeight)

        val progress = ReaderProgressIndicator(context).apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                updateMargins(top = parentHeight / 4)
            }
        }
        progressContainer.addView(progress)
        return progress
    }

    /**
     * Initializes a button to retry pages.
     */
    private fun initErrorLayout(error: Throwable?): ReaderErrorBinding {
        // SY --> Komiho: 渲染期密码异常（加密包构造期漏网/密码被换）→ 直接弹密码框，
        // 输对后 submitArchivePassword 会整章重载，错误占位随之消失
        if (error is ArchivePasswordException) {
            viewer.activity.viewModel.openArchivePasswordDialog()
        }
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), frame, true)
            errorLayout?.root?.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, (parentHeight * 0.8).toInt())
            errorLayout?.actionRetry?.setOnClickListener {
                if (error is ArchivePasswordException) {
                    viewer.activity.viewModel.openArchivePasswordDialog()
                } else {
                    page?.let { it.chapter.pageLoader?.retryPage(it) }
                }
            }
        }

        val imageUrl = page?.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.let {
            frame.removeView(it.root)
            errorLayout = null
        }
    }
}
