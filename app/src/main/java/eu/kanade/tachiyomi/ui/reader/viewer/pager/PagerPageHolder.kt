package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import mihon.core.common.archive.ArchivePasswordException
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.EnhanceBurst
import eu.kanade.tachiyomi.util.EnhanceTimings
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import tachiyomi.i18n.MR
import java.io.FileNotFoundException
import kotlin.math.max

/** Komiho 诊断 TAG：预处理 / 预载路径（沿用既有名字，抓取脚本不用改）。 */
private const val KOMIHA_PREFETCH_TAG = "Waifu2xPrefetch"

/** 双页合并前，等第二页进入终态的最长时间（超时就按现状渲染，不卡住页面）。 */
private const val PAIR_SETTLE_TIMEOUT_MS = 3000L

/** 页面是否已进入终态（就绪或失败）—— 双页合并必须等到这一步。 */
private fun Page.State.isSettled(): Boolean = this is Page.State.Ready || this is Page.State.Error

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    private var extraPage: ReaderPage? = null,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page to extraPage

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    /**
     * Job for loading the page.
     */
    private var extraLoadJob: Job? = null

    /** Komiho 诊断：本 holder 已执行过几次 setImage（用于识别重复增强）。 */
    private var setImageCount = 0

    /**
     * Komiho P1（双页性能）：本 holder 的两条加载任务（page / extraPage）都会在各自页面 Ready 时
     * 调 [setImage]，双页模式下会把整条流水线跑两遍（实测同一跨页算两次、各 ≈2.5s）。
     * 用互斥 + [renderedKey]「同一对页只渲染一次」把它收敛成一份。
     */
    private val renderMutex = Mutex()

    /** 已经渲染完成的 (page, extraPage)；null = 还没渲染过。 */
    private var renderedKey: Pair<ReaderPage, ReaderPage?>? = null

    /** 渲染 [renderedKey] 时的增强档位 —— 档位变了要允许重跑（否则改设置后这一页不刷新）。 */
    private var renderedEnhancementMode = -1

    /**
     * Komiho (2026-09-19): 渲染 [renderedKey] 时的「影响像素的设置」指纹
     * （[ReaderPreferences.enhancementCacheKey]）。空串 = 还没渲染过。
     *
     * 只比档位不够：换 AI 模型时档位仍是 5，同一对页会被判成「已渲染」直接跳过 ——
     * 表现就是改完设置要退出重进或一直划动才生效。
     */
    private var renderedEnhancementKey = ""

    // 停顿补算（快速翻页被抢占打断的页，停在此页时补超一次）。
    // currentImageEnhanced 定义在 ReaderPageImageView（预解码位图路径与现场解码路径共用）。

    /** 补算重发解码请求用的源（仅未增强时保留；内存 Buffer，零网络）。 */
    private var appliedSource: BufferedSource? = null

    /** 当前屏上图是否动图（动图不进增强链，不补算）。 */
    private var appliedAnimated = false

    /** 补算请求在飞标记——防止停顿回调与迟到回调叠加重复发。 */
    private var reEnhanceInFlight = false

    /**
     * 本 holder 已发起的补算次数。
     *
     * GPU / NPU 档没有 CPU 兜底（引擎失败即保持原图并登记「可重试」），所以持续失败时
     * 「停稳 → 补算 → 再失败」可以无限循环。这里封顶 [maxReEnhanceAttempts]，
     * 一旦某次真的出图（`enhanced == true`）就清零。
     */
    private var reEnhanceAttempts = 0
    private val maxReEnhanceAttempts = 3

    /**
     * 迟到补算延迟（ms）：等预热抢占的 abort 发生、被打断的解码出图后再判一次。
     *
     * Komiho (2026-10-25)：400 → **150**。连点期间增强被整段跳过（见 [EnhanceBurst]），
     * 停稳后要尽快把当前页补上；原生那边置 abort 标志到真正退出只隔一两个 tile，
     * 150ms 足够，再等 400ms 只会让用户多看着原图 0.25s。
     */
    private val reEnhanceDelayMs = 150L

    private val pendingReEnhance = Runnable { maybeReEnhance("late") }

    init {
        // Komiho 诊断：记录 holder 实例身份 —— 用于判断「同一页被增强两次」是
        // 两个 holder 实例各算一次，还是同一个 holder 被调了两次 setImage。
        android.util.Log.d(
            "Waifu2xHolder",
            "CREATE page=${page.index} extra=${extraPage?.index ?: -1} id=${System.identityHashCode(this)}",
        )
        // 现场解码完成回执——记录增强结果；未增强且被打断、用户就停在这一页时，
        // 安排一次迟到补算（停顿回调到达时 abort 可能还没发生）。
        onDecodeCompleted = { enhanced, aborted -> handleDecodeCompleted(enhanced, aborted) }
        loadJob = scope.launch { loadPageAndProcessStatus(1) }
        extraLoadJob = scope.launch { loadPageAndProcessStatus(2) }
    }

    /**
     * 现场解码完成。记录本次屏上图是否真增强；未增强且被抢占打断、且用户仍停在这一页时，延迟
     * [reEnhanceDelayMs] 安排补算（届时复核仍是当前页）。
     */
    private fun handleDecodeCompleted(enhanced: Boolean, aborted: Boolean) {
        currentImageEnhanced = enhanced
        reEnhanceInFlight = false
        if (enhanced) {
            reEnhanceAttempts = 0
            appliedSource = null
            removeCallbacks(pendingReEnhance)
            return
        }
        if (aborted && viewer.isCurrentItem(item)) {
            removeCallbacks(pendingReEnhance)
            postDelayed(pendingReEnhance, reEnhanceDelayMs)
        }
    }

    /**
     * 停顿补算——当前屏上图是「被抢占打断的未增强图」时，重跑一次解码 + 增强（reader 页请求缓存
     * 全 DISABLED，重发即真重算；源走内存 Buffer，零网络）。触发点：ViewPager 停顿回调（settle）
     * 与迟到回调（late）。
     *
     * 闸门（缺一不可）：
     * - 增强开着（mode != 0 或降噪开着）、非动图；
     * - [EnhanceTimings.hasInterrupted] 为真——只有**真被抢占过**的页才有这份登记（尺寸门 /
     *   HARDWARE / 引擎失败等永久跳过不会登记），且真正动手时消费掉，天然防循环；
     * - 仍是当前页（快速翻页时只为停下的那一页补算，不形成重试风暴）；
     * - 没有已在飞的补算。
     */
    private fun maybeReEnhance(reason: String) {
        if (currentImageEnhanced || reEnhanceInFlight) return
        // 没有 CPU 兜底后，引擎持续失败会「补算 → 再失败 → 再补算」循环，这里封顶。
        if (reEnhanceAttempts >= maxReEnhanceAttempts) return
        val src = appliedSource ?: return
        if (appliedAnimated) return
        val preferences = Injekt.get<ReaderPreferences>()
        if (preferences.enhancementMode.get() == 0 && preferences.denoiseLevel.get() == 0) return
        // Komiho (2026-10-25): 还在连点 —— 等它停，别在这儿发起必然被打断的推理。
        // 每轮重排 150ms，连点结束后自然落到下面的正常流程（不消耗补算次数）。
        if (EnhanceBurst.isBursting) {
            removeCallbacks(pendingReEnhance)
            postDelayed(pendingReEnhance, reEnhanceDelayMs)
            return
        }
        // Komiho (2026-10-25): 刚离开连点 —— 顺手补一次邻居预热。
        // 预热只在换页时触发，连点期间被挡掉后如果用户就此停下、不再翻页，邻居会一直是冷的。
        // （viewer 内部有 1s 节流，多个 holder 同时回调也不会重复。）
        viewer.onBurstSettled()
        // Komiho (2026-10-16): 闸门换成**应用层**的「本页被抢占过」登记（由 MihonSyEnhancer 在
        // 抢占当场写入、按页保留）。原来查的是原生 `Waifu2x.wasAborted()` —— 那个槽位只有一个，
        // 下一次推理一起步就被清成 -1，而这里是几百毫秒后才回头看，实测必为 false ⇒ 这条兜底
        // 从未生效（issue #8 日志里 `Waifu2xReEnhance` 出现 0 次）。
        // 消费放在**其余闸门都通过之后**：否则一次「不满足条件」的检查就把标记吃掉，
        // 之后真需要补算时反倒读不到了。
        if (!EnhanceTimings.hasInterrupted(page.index)) return
        if (!viewer.isCurrentItem(item)) return
        EnhanceTimings.takeInterrupted(page.index)
        reEnhanceInFlight = true
        reEnhanceAttempts++
        // 作废预处理缓存里那份未增强位图 + 放行 already-rendered 守卫：否则回翻再命中还是原图，
        // 状态重发也被守卫挡掉。缓存删除后本 holder 持有的源是唯一引用。
        val key = viewer.preparedCache.key(page, extraPage)
        viewer.preparedCache.remove(key)
        renderedKey = null
        // Komiho (2026-10-25): 给这次补算开放行窗口 —— 否则它会被连点判定挡在门外
        // （停稳到真正发起补算之间可能只有 150ms，仍在 burst 窗口内）。
        EnhanceBurst.openResumeWindow()
        android.util.Log.d(
            "Waifu2xReEnhance",
            "page=${page.index} reason=$reason id=${System.identityHashCode(this)}",
        )
        setImage(src.peek(), appliedAnimated, viewerImageConfig())
    }

    /**
     * ViewPager 停顿回调 = 「翻页停止」。除既有的 landscapeZoom 外，若当前屏上图是被抢占打断的
     * 未增强图，补算一次（issue：快速翻页后停在此页停在原图）。
     */
    override fun onPageSelected(forward: Boolean) {
        super.onPageSelected(forward)
        maybeReEnhance("settle")
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Komiho 诊断：holder 何时被回收（与 CREATE 配对即可看出是否同页多实例并存）。
        android.util.Log.d(
            "Waifu2xHolder",
            "DETACH page=${page.index} id=${System.identityHashCode(this)}",
        )
        loadJob?.cancel()
        loadJob = null
        extraLoadJob?.cancel()
        extraLoadJob = null
        // 离屏即取消待执行的补算——补算只为「停下来的那一页」服务。
        removeCallbacks(pendingReEnhance)
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus(pageIndex: Int) {
        // SY -->
        val page = if (pageIndex == 1) page else extraPage
        page ?: return
        // SY <--
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
                            progressIndicator?.setProgress(value)
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
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     *
     * Komiho P1（双页性能）：本 holder 有**两条**加载任务（page / extraPage），各自在所属页面
     * Ready 时进来一次 —— 单页模式只有一条（extraPage 为空时另一条直接退出），但**双页模式下
     * 两条都会跑到这里**，于是「合并 + 解码 + 增强」整条流水线被完整执行两遍。真机实测
     * （2026-09-17）：同一跨页被算两次、各 ≈2.5s，第二次还排在第一次后面等锁 ≈2.45s，
     * 用户实际要等 ≈5.0s；单页对照只有 1 次 ≈1.4s。
     * ⇒ 用「[renderMutex] 互斥 + [renderedKey] 同一对页只渲染一次」收敛成一份。
     */
    private suspend fun setImage() {
        renderMutex.withLock {
            val key = viewer.preparedCache.key(page, extraPage)
            val preferences = Injekt.get<ReaderPreferences>()
            val enhancementMode = preferences.enhancementMode.get()
            // Komiho: 档位之外还要比「影响像素的设置」指纹，否则换模型 / 换倍率 / 改裁边都
            // 会被判成「这一对页已渲染」而跳过（见 renderedEnhancementKey 的说明）。
            val enhancementKey = preferences.enhancementCacheKey()
            if (renderedKey == key && renderedEnhancementMode == enhancementMode &&
                renderedEnhancementKey == enhancementKey
            ) {
                // 同一对页已经渲染过（另一条 load 任务或状态重发）→ 直接退出，不再烧一次 GPU。
                // 放在最前面：省掉下面的等待与整条流水线。
                android.util.Log.d(
                    KOMIHA_PREFETCH_TAG,
                    "page=${page.index} skip reason=already-rendered extra=${extraPage?.index ?: -1}",
                )
                return@withLock
            }

            // 双页：先等第二页也进入终态，否则会拿「只有一半」的输入去合并，并把那个结果
            // 当成这一对页的结果缓存下来（配合上面的「只渲染一次」会把半页固化）。
            awaitPairSettled()

            // Komiho P3：可见页优先 —— 若上一份流水线是别的页（可能还在 GPU 上跑），把它打断，
            // 把引擎让给当前页（详见 PagerViewer.onPrepareStart）。
            viewer.onPrepareStart(pageIndex = page.index, visible = viewer.isCurrentItem(item))

            if (extraPage == null) {
                progressIndicator?.setProgress(0)
            } else {
                progressIndicator?.setProgress(95)
            }

            // SY（A+B+C，Page 流畅度优化）：
            // 1) 命中预处理缓存（预热/上次离开时算好的整图+标志+可能的增强预解码位图）→ 直接应用；
            // 2) 未命中走纯管线（PagerPagePreparer，无副作用）并把结果入缓存（B: 回翻/重建复用）；
            // 3) C 方案：副作用分支（双页分割 onPageSplit / 合并 splitDoublePages）不再整体放弃——
            //    纯管线缓存「通用预处理」（流物化+嗅探，layoutApplied=false），此处补跑布局；
            // 4) 补跑失败才回退完整旧管线 prepareLegacy（从流重新读取）。
            // Komiho 诊断：把页号写到 view 上（供增强日志区分为第几页），并记下是否命中预载结果。
            pageIndex = page.index
            var prepared = viewer.preparedCache.get(key)
            val prewarmHit = prepared != null
            if (prepared == null) {
                // 走 viewer 的单飞入口：预载正在算同一页时就等它的结果，不重复烧推理。
                prepared = viewer.prepareShared(
                    page = page,
                    extraPage = extraPage,
                    viewHeight = if (height > 0) height else viewer.pager.height,
                )
            }
            if (prepared != null && !prepared.layoutApplied) {
                prepared = applyLegacyLayout(prepared)
            }
            if (prepared == null) {
                try {
                    prepared = prepareLegacy()
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Komiho: 同 WebtoonPageHolder —— 章节缓存文件被清掉时，prepareLegacy 读流会抛
                    // FileNotFoundException（preparePure 那一层只是记 INFO 后返回 null）。交给 loader
                    // 静默重新入队重下，别把它变成一个「点重试不生效」的死页。
                    if (e is FileNotFoundException &&
                        page.chapter.pageLoader?.healMissingCache(page) == true
                    ) {
                        logcat(LogPriority.WARN) {
                            "page=${page.index} 章节缓存文件丢失，已重新入队: ${e.message}"
                        }
                        return@withLock
                    }
                    logcat(LogPriority.ERROR, e)
                    withUIContext { setError(e) }
                    return@withLock
                }
            }
            val result = prepared ?: return@withLock
            viewer.preparedCache.put(key, result)

            withUIContext {
                val bitmap = result.decodedBitmap
                val visible = viewer.isCurrentItem(item)
                // Komiho 诊断：预载到底有没有省下等待。
                // usedPreDecoded=true 表示直接命中预解码位图（零等待）；false 表示要走现场解码+增强。
                // count>1 = 同一 holder 被调了不止一次（P1 修复后不该再出现）；
                // visible = 这次渲染的是不是当前显示页（P3 判据）。
                // ⚠️ 这里的 `this` 是 withUIContext 的 CoroutineScope，**不是 holder**，
                // 别拿它当 holder id（会得出「同一页有两个 holder」的错误结论）。
                android.util.Log.d(
                    KOMIHA_PREFETCH_TAG,
                    "page=${page.index} prewarmHit=$prewarmHit usedPreDecoded=${bitmap != null} " +
                        "enhanceMs=${result.enhanceElapsedMillis} visible=$visible " +
                        "count=${++setImageCount}",
                )
                if (bitmap != null && !bitmap.isRecycled) {
                    // 增强预解码已完成：直接走 bitmap 路径（与旧增强成功路径等价）
                    setImage(
                        android.graphics.drawable.BitmapDrawable(resources, bitmap),
                        viewerImageConfig(),
                    )
                    // 登记本次屏上图是否真增强。未增强（被抢占打断）时保留源，停顿后由
                    // maybeReEnhance 补算一次。
                    currentImageEnhanced = result.enhanced
                    appliedAnimated = result.isAnimated
                    appliedSource = if (result.enhanced) null else result.source
                    if (result.enhanced) reEnhanceAttempts = 0
                    // Komiho (2026-10-16): 预解码路径也要安排补算。这条路径**不走 Coil 回调**，
                    // `handleDecodeCompleted` 永远不会被调，所以「迟到的复核」以前只对现场解码路径
                    // 生效 —— 预解码降级图一旦渲染就没人再管，这正是 issue #8 里「翻回还是原图，
                    // 只有退出重进才恢复」的一半原因。命中可重试的降级图（被抢占、且没进缓存）时
                    // 也排一次，到时仍复核「是不是当前页」。
                    if (result.retryable && viewer.isCurrentItem(item)) {
                        removeCallbacks(pendingReEnhance)
                        postDelayed(pendingReEnhance, reEnhanceDelayMs)
                    }
                    // SY: 预解码路径补发增强角标（现场解码路径由 SSIV 的 Coil 回调触发）
                    // Komiho: enhanceElapsedMillis = -1 表示这份预处理**没有做过增强**（尺寸门 /
                    // 已解到位图等），角标要显示 SKIP 而不是 CPU OK。这条路径不走 Coil 解码，
                    // 因此顺手把 skip 标记消费掉，避免陈旧标记残留、误标后续同号页。
                    val enhancementSkipped = result.enhanceElapsedMillis < 0 ||
                        EnhanceTimings.takeSkipped(pageIndex)
                    showEnhancementOutcome(
                        success = !enhancementSkipped,
                        elapsedMillis = result.enhanceElapsedMillis,
                        output = "${bitmap.width}×${bitmap.height}",
                    )
                } else {
                    // 现场解码路径——先保留源（未增强时补算要用），增强结果由 onDecodeCompleted
                    // 回执后置位（成功则顺手释放源）。
                    appliedAnimated = result.isAnimated
                    appliedSource = result.source
                    setImage(result.source.peek(), result.isAnimated, viewerImageConfig())
                }
                if (!result.isAnimated) {
                    pageBackground = result.background
                }
                removeErrorLayout()
            }

            // 真的把图交出去之后才记「这一对页已渲染」；上面的 setError 分支是非局部 return，
            // 不会走到这里 ⇒ 失败后仍可重来。
            renderedKey = key
            renderedEnhancementMode = enhancementMode
            renderedEnhancementKey = enhancementKey
        }
    }

    /**
     * 双页：等「第二页」也进入终态（Ready / Error）再开始合并。
     * 超时（[PAIR_SETTLE_TIMEOUT_MS]）就照常继续 —— 宁可先出半页，也不要把整页卡住不显示。
     */
    private suspend fun awaitPairSettled() {
        val second = extraPage ?: return
        val settled = withTimeoutOrNull(PAIR_SETTLE_TIMEOUT_MS) {
            page.statusFlow.first { it.isSettled() }
            second.statusFlow.first { it.isSettled() }
            true
        }
        if (settled == null) {
            android.util.Log.w(
                KOMIHA_PREFETCH_TAG,
                "page=${page.index} pair settle timeout extra=${second.index}; 按现状继续",
            )
        }
    }

    private fun viewerImageConfig() = Config(
        zoomDuration = viewer.config.doubleTapAnimDuration,
        minimumScaleType = viewer.config.imageScaleType,
        cropBorders = viewer.config.imageCropBorders,
        cropBordersAggressive = viewer.config.imageCropBordersAggressive,
        zoomStartPosition = viewer.config.imageZoomType,
        landscapeZoom = viewer.config.landscapeZoom,
        // 关掉后双击不再放大，点击翻页才能「抬手即启动」（见 Pager.tapImmediate）。
        doubleTapZoom = viewer.config.pagedDoubleTapZoom,
    )

    /**
     * 旧同步管线（保留副作用：双页分割 onPageSplit / 合并 splitDoublePages 与进度回调），
     * 仅在纯管线放弃（双页相关配置）时使用。结果同样包装进缓存供回翻复用。
     */
    private suspend fun prepareLegacy(): PagerPreparedPage {
        val streamFn = page.stream ?: throw IllegalStateException("page stream is null")
        val streamFn2 = extraPage?.stream

        val (source, isAnimated, background) = withIOContext {
            streamFn().buffered(16).use { source ->
                // SY（教训留档）：这里曾加 check(source.read() != -1) 判空，但
                // InputStream.read() 会消费掉首字节（BufferedInputStream 缓冲区是
                // 读取位置不是 peek），每张图片被吃掉第一个字节 → JPEG/PNG 头损坏
                // → 解码器嗅探失败 "No decoder found" → pager 全部页面渲染失败
                // （webtoon 无此行不受影响）。空流兜底已由读取层
                // ArchivePageLoader.readEntryBytes 承担，此处不再判空。
                // SY -->
                if (extraPage != null) {
                    streamFn2?.invoke()
                        ?.buffered(16)
                } else {
                    null
                }.use { source2 ->
                    // 双页布局下不拆（否则宽图被切成两页，跨页配错）。
                    val itemSource = if (viewer.config.dualPageSplitActive) {
                        process(item.first, Buffer().readFrom(source))
                    } else {
                        mergePages(Buffer().readFrom(source), source2?.let { Buffer().readFrom(it) })
                    }
                    // SY <--
                    val isAnimated = ImageUtil.isAnimatedAndSupported(itemSource)
                    val background = if (!isAnimated && viewer.config.automaticBackground) {
                        ImageUtil.chooseBackground(context, itemSource.peek())
                    } else {
                        null
                    }
                    Triple(itemSource, isAnimated, background)
                }
            }
        }
        return PagerPreparedPage(
            source = source,
            source2 = null,
            isAnimated = isAnimated,
            background = background,
            decodedBitmap = null,
            enhancementMode = Injekt.get<ReaderPreferences>().enhancementMode.get(),
            cropBorders = viewer.config.imageCropBorders,
            enhanceElapsedMillis = -1L,
            layoutApplied = true,
        )
    }

    /**
     * SY（C 方案）：为「通用预处理已完成、布局未应用」（layoutApplied=false）的结果补跑
     * 旧布局管线（双页分割 onPageSplit / 合并 splitDoublePages 等副作用保留在本 holder）。
     * 输入流已物化（免重复 IO），失败返回 null，由调用方回退完整 prepareLegacy。
     */
    private suspend fun applyLegacyLayout(raw: PagerPreparedPage): PagerPreparedPage? {
        return try {
            val itemSource = withIOContext {
                if (viewer.config.dualPageSplitActive) {
                    process(page, raw.source)
                } else {
                    mergePages(raw.source, raw.source2)
                }
            }
            val isAnimated = ImageUtil.isAnimatedAndSupported(itemSource)
            val background = if (!isAnimated && viewer.config.automaticBackground) {
                ImageUtil.chooseBackground(context, itemSource.peek())
            } else {
                null
            }
            PagerPreparedPage(
                source = itemSource,
                source2 = null,
                isAnimated = isAnimated,
                background = background,
                decodedBitmap = null,
                enhancementMode = Injekt.get<ReaderPreferences>().enhancementMode.get(),
                cropBorders = viewer.config.imageCropBorders,
                enhanceElapsedMillis = -1L,
                layoutApplied = true,
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "pager applyLegacyLayout failed, falling back to full legacy" }
            null
        }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        // 双页布局下整条拆分管线都不该走（见 PagerConfig.dualPageSplitActive）。
        if (!viewer.config.dualPageSplitActive) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
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

    private fun mergePages(imageSource: BufferedSource, imageSource2: BufferedSource?): BufferedSource {
        // Handle adding a center margin to wide images if requested
        if (imageSource2 == null) {
            return handleWideImage(imageSource)
        }

        if (page.fullPage) return imageSource
        if (ImageUtil.isAnimatedAndSupported(imageSource)) {
            page.fullPage = true
            splitDoublePages()
            return imageSource
        } else if (ImageUtil.isAnimatedAndSupported(imageSource2)) {
            page.isolatedPage = true
            extraPage?.fullPage = true
            splitDoublePages()
            return imageSource
        }

        val imageBitmap = decodeImage(imageSource)
        if (imageBitmap == null) {
            imageSource2.close()
            page.fullPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) { "Cannot combine pages" }
            return imageSource
        }

        scope.launch { progressIndicator?.setProgress(96) }
        if (imageBitmap.height < imageBitmap.width) {
            imageSource2.close()
            page.fullPage = true
            splitDoublePages()
            return imageSource
        }

        val imageBitmap2 = decodeImage(imageSource2)
        if (imageBitmap2 == null) {
            imageSource2.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) { "Cannot combine pages" }
            return imageSource
        }

        scope.launch { progressIndicator?.setProgress(97) }
        if (imageBitmap2.height < imageBitmap2.width) {
            imageSource2.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            return imageSource
        }

        val isLTR = (viewer !is R2LPagerViewer) xor viewer.config.invertDoublePages
        val centerMargin = calculateCenterMargin(imageBitmap.height, imageBitmap2.height)

        imageSource.close()
        imageSource2.close()

        return ImageUtil.mergeBitmaps(imageBitmap, imageBitmap2, isLTR, centerMargin, viewer.config.pageCanvasColor) {
            updateProgress(it)
        }
    }

    private fun handleWideImage(imageSource: BufferedSource): BufferedSource {
        return if (
            !ImageUtil.isAnimatedAndSupported(imageSource) &&
            ImageUtil.isWideImage(imageSource) &&
            viewer.config.centerMarginType and PagerConfig.CenterMarginType.WIDE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            ImageUtil.addHorizontalCenterMargin(imageSource, height, context)
        } else {
            imageSource
        }
    }

    private fun decodeImage(imageSource: BufferedSource): Bitmap? {
        return try {
            ImageDecoder.newInstance(imageSource.inputStream())?.decode()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cannot decode image" }
            null
        }
    }

    private fun calculateCenterMargin(height: Int, height2: Int): Int {
        return if (viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            96 / (this.height.coerceAtLeast(1) / max(height, height2).coerceAtLeast(1)).coerceAtLeast(1)
        } else {
            0
        }
    }

    private fun updateProgress(progress: Int) {
        scope.launch {
            if (progress == 100) {
                progressIndicator?.hide()
            } else {
                progressIndicator?.setProgress(progress)
            }
        }
    }

    private fun splitDoublePages() {
        scope.launch {
            delay(100)
            viewer.splitDoublePages(page)
            if (extraPage?.fullPage == true || page.fullPage) {
                extraPage = null
            }
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        val sideMargin = if ((viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN) >
            0 &&
            viewer.config.doublePages &&
            !viewer.config.imageCropBorders
        ) {
            48
        } else {
            0
        }

        return ImageUtil.splitInHalf(imageSource, side, sideMargin)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
        // MihonSY: page image is Ready — let the auto-webtoon check run immediately
        // (its own guards make it a cheap no-op once done/decided).
        viewer.activity.onPageLoaded(page)
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        // SY --> Komiho: 渲染期密码异常（加密包构造期漏网/密码被换）→ 直接弹密码框，
        // 输对后 submitArchivePassword 会整章重载，错误占位随之消失
        if (error is ArchivePasswordException) {
            viewer.activity.viewModel.openArchivePasswordDialog()
        }
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                if (error is ArchivePasswordException) {
                    viewer.activity.viewModel.openArchivePasswordDialog()
                } else {
                    page.chapter.pageLoader?.retryPage(page)
                }
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
