package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Color
import androidx.annotation.ColorInt
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Configuration used by pager viewers.
 */
class PagerConfig(
    private val viewer: PagerViewer,
    scope: CoroutineScope,
    readerPreferences: ReaderPreferences = Injekt.get(),
) : ViewerConfig(readerPreferences, scope) {

    var theme = readerPreferences.readerTheme.get()
        private set

    var automaticBackground = false
        private set

    var dualPageSplitChangedListener: ((Boolean) -> Unit)? = null

    var reloadChapterListener: ((Boolean) -> Unit)? = null

    var imageScaleType = 1
        private set

    var imageZoomType = ReaderPageImageView.ZoomStartPosition.LEFT
        private set

    var imageCropBorders = false
        private set

    /** Komiho: 深度裁剪边缘（只与 [imageCropBorders] 同开时生效，见 TachiyomiImageDecoder）。 */
    var imageCropBordersAggressive = false
        private set

    var navigateToPan = false
        private set

    var landscapeZoom = false
        private set

    // SY -->
    var usePageTransitions = false

    /**
     * 翻页动画是否生效（等价于用户开着「翻页动画」开关）。
     *
     * Komiho 这里还额外关掉了 E-Ink 模式的动画，MihonSY 暂无 E-Ink 主题，故只看开关。
     */
    val pageSlideAnimationsEnabled: Boolean
        get() = usePageTransitions

    /**
     * 页漫翻页方式（0 = 平移 / 1 = 覆盖 / 2 = 翻书）。
     *
     * 只影响翻页动画的绘制，不改任何位图输出，所以不接 `imagePropertyChangedListener`。
     */
    var pageTurnStyle = readerPreferences.pageTurnStyle.get().coerceIn(0, 2)
        private set

    /** 是否走「非平移」的翻页动画（覆盖 / 翻书由叠加层接管拖动）。 */
    val pageFlipActive: Boolean
        get() = pageTurnStyle != 0 && pageSlideAnimationsEnabled

    /**
     * 点击 / 按键翻一页的实际时长（毫秒）。平移与覆盖共用。
     *
     * 定为常量 **400ms**（不再可调）：它与「拖动后抬手结算」同一量级，足够稳，
     * 又不会像 500ms 那样显得拖沓。
     */
    val pageFlipDurationMs = TAP_FLIP_DURATION_MS

    /**
     * 页漫双击缩放。关闭后点击翻页改为「抬手即派发」——
     * 没有双击判定，也就没有那 ~300ms 的固定延迟（见 `Pager.tapImmediate`）。
     */
    var pagedDoubleTapZoom = readerPreferences.pagedDoubleTapZoom.get()
        private set

    var shiftDoublePage = false

    // 拆分双页**不再关掉双页合并** —— 这是两件独立的事：
    // 「双页合并」决定一屏几页，「拆分双页」决定一张宽图要不要切成两页。
    // 以前绑死的结果是：开着拆分就永远进不了双页（横屏也只剩单页，用户看到的就是
    // 「双页无法合并」）。真正的互斥放在 [dualPageSplitActive]。
    var doublePages = readerPreferences.pageLayout.get() == PageLayout.DOUBLE_PAGES
        set(value) {
            field = value
            if (!value) {
                shiftDoublePage = false
            }
        }

    /**
     * 拆分双页**实际生效**的条件。
     *
     * 开着「拆分双页」但当前是双页布局时**不拆** —— 一张宽图在跨页里本来就该整屏铺开
     * （走 handleWideImage 的中缝逻辑），拆成两半会把左右页配错，也正好是用户看到的
     * 「横屏开拆分后双页合不起来」。
     */
    val dualPageSplitActive: Boolean
        get() = dualPageSplit && !doublePages

    var invertDoublePages = false

    var autoDoublePages = readerPreferences.pageLayout.get() == PageLayout.AUTOMATIC

    @ColorInt
    var pageCanvasColor = Color.WHITE

    var centerMarginType = CenterMarginType.NONE

    /**
     * ViewPager 离屏缓冲 —— **固定 1 页**，不再对外暴露。
     *
     * 原因：
     * - **增强开启时**：引擎是单槽串行的，离屏窗口里的每个 holder 都会各自启动解码+增强，
     *   窗口越大只是让更多页去抢同一把锁 → 队列变长、顺序不可控，可见页排在后面，
     *   最后被 `prewarm-far` 打断（表现为 skip）。
     * - **增强关闭时**：现代机型解码一页只要 50~100ms，调大换来的是内存（每页 6MB 级位图），
     *   感知不到速度差。
     *
     * 老偏好 [ReaderPreferences.pagerOffscreenLimit] 保留但不再消费（老数据不影响）。
     */
    var offscreenPageLimit = OffscreenPages.DEFAULT
        private set

    // SY <--

    init {
        readerPreferences.readerTheme
            .register(
                {
                    theme = it
                    automaticBackground = it == 3
                },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.imageScaleType
            .register({ imageScaleType = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.zoomStart
            .register({ zoomTypeFromPreference(it) }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.cropBorders
            .register({ imageCropBorders = it }, { imagePropertyChangedListener?.invoke() })

        // Komiho: 深度裁剪边缘 —— 只与裁边开关同开时生效；裁边关闭时切换它不引起重载
        // （位图不会有任何变化，白刷一页）。
        readerPreferences.cropBordersAggressive
            .register(
                { imageCropBordersAggressive = it },
                { if (imageCropBorders) imagePropertyChangedListener?.invoke() },
            )

        // Komiho (2026-09-19): 增强设置同样属于「图像配置」，变更必须立刻重渲染 —— 否则
        // preparedCache 与 holder 的「同一对页已渲染」守卫都还认旧设置，用户得退出重进或
        // 一直划动才看到效果。这几个偏好没有对应的 config 属性，写回 lambda 是空操作，
        // 只为触发 refreshAdapter()。
        readerPreferences.enhancementMode
            .register({ }, { imagePropertyChangedListener?.invoke() })
        readerPreferences.lanczosScale
            .register({ }, { imagePropertyChangedListener?.invoke() })
        readerPreferences.aiModelId
            .register({ }, { imagePropertyChangedListener?.invoke() })
        readerPreferences.aiTileSize
            .register({ }, { imagePropertyChangedListener?.invoke() })
        readerPreferences.denoiseLevel
            .register({ }, { imagePropertyChangedListener?.invoke() })
        readerPreferences.aiBypassFitGate
            .register({ }, { imagePropertyChangedListener?.invoke() })
        // Komiho (2026-10-01): AI 面积回缩开关/强度同样进 imageFingerprint（经
        // enhancementCacheKey），不注册的话 preparedCache 与 holder 都还认旧设置。
        readerPreferences.aiAreaDownscale
            .register({ }, { imagePropertyChangedListener?.invoke() })
        readerPreferences.aiAreaDownscaleStrength
            .register({ }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.navigateToPan
            .register({ navigateToPan = it })

        readerPreferences.landscapeZoom
            .register({ landscapeZoom = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.navigationModePager
            .register({ navigationMode = it }, { updateNavigation(navigationMode) })

        readerPreferences.pagerNavInverted
            .register({ tappingInverted = it }, { navigator.invertMode = it })
        readerPreferences.pagerNavInverted.changes()
            .drop(1)
            .onEach { navigationModeChangedListener?.invoke() }
            .launchIn(scope)

        readerPreferences.dualPageSplitPaged
            .register(
                { dualPageSplit = it },
                {
                    imagePropertyChangedListener?.invoke()
                    dualPageSplitChangedListener?.invoke(it)
                },
            )

        readerPreferences.dualPageInvertPaged
            .register({ dualPageInvert = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.dualPageRotateToFit
            .register(
                { dualPageRotateToFit = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.dualPageRotateToFitInvert
            .register(
                { dualPageRotateToFitInvert = it },
                { imagePropertyChangedListener?.invoke() },
            )

        // SY -->
        // MihonSY (2026-10-03): usePageTransitions 只在 setCurrentItem 调用时现读（PagerViewer
        // 的 moveToNext/moveToPrevious），不改任何位图输出，不再接 imagePropertyChangedListener
        // —— 否则拨一下开关会白白重建 adapter、清 preparedCache，可见页重新解码 + 重跑 AI 增强。
        readerPreferences.pageTransitionsPager
            .register({ usePageTransitions = it })
        // 翻页方式（平移 / 覆盖 / 翻书）。只影响翻页动画的绘制，
        // 不改位图输出，所以不接 imagePropertyChangedListener。
        readerPreferences.pageTurnStyle
            .register({ style -> pageTurnStyle = style.coerceIn(0, 2) })
        // 双击缩放开关同时决定点击是否「抬手即派发」（关掉它才有 0 延迟）。
        readerPreferences.pagedDoubleTapZoom
            .register({ enabled ->
                pagedDoubleTapZoom = enabled
                viewer.pager.tapImmediate = !enabled
                // 已渲染出来的页也要立刻跟着变，否则「点击没延迟了、双击却还能放大」。
                viewer.applyDoubleTapZoom(enabled)
            })
        readerPreferences.readerTheme
            .register(
                {
                    themeToColor(it)
                },
                {
                    themeToColor(it)
                    reloadChapterListener?.invoke(doublePages)
                },
            )
        readerPreferences.pageLayout
            .register(
                {
                    autoDoublePages = it == PageLayout.AUTOMATIC
                    if (!autoDoublePages) {
                        doublePages = it == PageLayout.DOUBLE_PAGES
                    }
                },
                {
                    autoDoublePages = it == PageLayout.AUTOMATIC
                    if (!autoDoublePages) {
                        doublePages = it == PageLayout.DOUBLE_PAGES
                    }
                    reloadChapterListener?.invoke(doublePages)
                },
            )

        readerPreferences.centerMarginType
            .register({ centerMarginType = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.invertDoublePages
            .register({ invertDoublePages = it }, { imagePropertyChangedListener?.invoke() })

        // 预载页数已固定为 1（原因见 [offscreenPageLimit]），因此不再订阅
        // `pagerOffscreenLimit`；老用户存的值不再生效。
        // SY <--
    }

    private fun zoomTypeFromPreference(value: Int) {
        imageZoomType = when (value) {
            // Auto
            1 -> when (viewer) {
                is L2RPagerViewer -> ReaderPageImageView.ZoomStartPosition.LEFT
                is R2LPagerViewer -> ReaderPageImageView.ZoomStartPosition.RIGHT
                else -> ReaderPageImageView.ZoomStartPosition.CENTER
            }
            // Left
            2 -> ReaderPageImageView.ZoomStartPosition.LEFT
            // Right
            3 -> ReaderPageImageView.ZoomStartPosition.RIGHT
            // Center
            else -> ReaderPageImageView.ZoomStartPosition.CENTER
        }
    }

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = this.tappingInverted }
        }

    /**
     * Komiho: 见 [ViewerConfig.imageFingerprint]。必须覆盖 init 里所有注册到
     * `imagePropertyChangedListener` 的偏好 —— 新增这类 register 时记得同步补进来。
     * 增强类偏好由 [ReaderPreferences.enhancementCacheKey]（模式/倍率/模型/tile + 两处 cropBorders）
     * 一次覆盖。
     */
    override fun imageFingerprint(): String = buildString {
        append(readerPreferences.enhancementCacheKey())
        append('|').append(readerPreferences.readerTheme.get())
        append('|').append(readerPreferences.imageScaleType.get())
        append('|').append(readerPreferences.zoomStart.get())
        append('|').append(readerPreferences.landscapeZoom.get())
        append('|').append(readerPreferences.dualPageSplitPaged.get())
        append('|').append(readerPreferences.dualPageInvertPaged.get())
        append('|').append(readerPreferences.dualPageRotateToFit.get())
        append('|').append(readerPreferences.dualPageRotateToFitInvert.get())
        // MihonSY: pageTransitionsPager 不改位图输出（见上方 register 处注释），不进指纹。
        append('|').append(readerPreferences.centerMarginType.get())
        append('|').append(readerPreferences.invertDoublePages.get())
        // 「拆分是否实际生效」取决于**当前**的布局（双页时不拆），而布局在 AUTOMATIC 下会随
        // 方向/展开变化 —— 不进指纹的话，横竖切换会复用上一方向切好/合并好的位图，
        // 表现就是「方向对了但页还是错的」。
        append('|').append(if (dualPageSplitActive) "split" else "merged")
    }

    override fun defaultNavigation(): ViewerNavigation {
        return when (viewer) {
            is VerticalPagerViewer -> LNavigation()
            else -> RightAndLeftNavigation()
        }
    }

    override fun updateNavigation(navigationMode: Int) {
        navigator = when (navigationMode) {
            0 -> defaultNavigation()
            1 -> LNavigation()
            2 -> KindlishNavigation()
            3 -> EdgeNavigation()
            4 -> RightAndLeftNavigation()
            5 -> DisabledNavigation()
            else -> defaultNavigation()
        }
        navigationModeChangedListener?.invoke()
    }

    object CenterMarginType {
        const val NONE = 0
        const val DOUBLE_PAGE_CENTER_MARGIN = 1
        const val WIDE_PAGE_CENTER_MARGIN = 2
        const val DOUBLE_AND_WIDE_CENTER_MARGIN = 3
    }

    object PageLayout {
        const val SINGLE_PAGE = 0
        const val DOUBLE_PAGES = 1
        const val AUTOMATIC = 2
    }

    /**
     * Komiho：预载页数（前后各 N 页）的取值范围。[DEFAULT] = 1 —— 最省内存、也是长期默认。
     * 上限 3：再往上每档要多撑 2 页已解码的增强位图（双页一跨页 ≈46MB），且 GPU 单引擎串行，
     * 多出来的前瞻只会排队。
     */
    object OffscreenPages {
        const val DEFAULT = 1
        const val MIN = 1
        const val MAX = 3
    }

    fun themeToColor(theme: Int) {
        pageCanvasColor = when (theme) {
            1 -> Color.BLACK
            2 -> 0x202125
            else -> Color.WHITE
        }
    }

    private companion object {
        /** 点击 / 按键翻页的时长（ms），见 [pageFlipDurationMs]。 */
        const val TAP_FLIP_DURATION_MS = 400
    }
}
