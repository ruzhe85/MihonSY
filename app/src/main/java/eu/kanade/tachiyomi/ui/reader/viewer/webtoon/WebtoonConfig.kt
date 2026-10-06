package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Configuration used by webtoon viewers.
 */
class WebtoonConfig(
    scope: CoroutineScope,
    readerPreferences: ReaderPreferences = Injekt.get(),
) : ViewerConfig(readerPreferences, scope) {

    var themeChangedListener: (() -> Unit)? = null

    var imageCropBorders = false
        private set

    /** Komiho: 深度裁剪边缘（只与 [imageCropBorders] / [continuousCropBorders] 同开时生效）。 */
    var imageCropBordersAggressive = false
        private set

    var zoomOutDisabled = false
        private set

    var zoomPropertyChangedListener: ((Boolean) -> Unit)? = null

    var sidePadding = 0
        private set

    var doubleTapZoom = true
        private set

    var doubleTapZoomChangedListener: ((Boolean) -> Unit)? = null

    // MihonSY -->
    var tapScrollDistanceFraction: Float = ReaderPreferences.WebtoonTapScrollFractions
        .getOrElse(readerPreferences.webtoonTapScrollDistance.get()) { 0.75f }
        private set

    var tapScrollDurationMillis: Int = readerPreferences.webtoonTapScrollDuration.get()
        .coerceIn(
            ReaderPreferences.WEBTOON_TAP_SCROLL_DURATION_MIN,
            ReaderPreferences.WEBTOON_TAP_SCROLL_DURATION_MAX,
        )
        private set

    var tapScrollChangedListener: (() -> Unit)? = null

    // Komiho: webtoon 预取深度（extra layout space 倍数）。1 = 当前行为，上限 3。
    var webtoonPrefetchDepth: Int = readerPreferences.webtoonPrefetchDepth.get()
        .coerceIn(
            ReaderPreferences.WEBTOON_PREFETCH_DEPTH_MIN,
            ReaderPreferences.WEBTOON_PREFETCH_DEPTH_MAX,
        )
        private set

    var prefetchChangedListener: (() -> Unit)? = null

    var originalSize = false
        private set
    // MihonSY <--

    val theme = readerPreferences.readerTheme.get()

    // SY -->
    var usePageTransitions = false

    // Komiho: 条页点击滚屏 v2（ComicScreen 手感）。与 usePageTransitions 互斥，
    // 由设置 UI 保证；两个都开时以 v2 为准（见 WebtoonViewer.animateScrollBy）。
    var usePageTransitionsV2 = false

    // v2 速度档位 = 每屏基准时长（ms，越小越快）
    var pageTransitionsV2SpeedMs: Int = readerPreferences.pageTransitionsV2Speed.get()
        private set

    var continuousCropBorders = false
        private set

    // SY <--
    init {
        readerPreferences.cropBordersWebtoon
            .register({ imageCropBorders = it }, { imagePropertyChangedListener?.invoke() })

        // Komiho: 深度裁剪边缘 —— 只与任一裁边开关同开时生效；都关闭时切换它不引起重载。
        readerPreferences.cropBordersAggressive
            .register(
                { imageCropBordersAggressive = it },
                { if (imageCropBorders || continuousCropBorders) imagePropertyChangedListener?.invoke() },
            )

        // Komiho (2026-09-19): 增强设置同样属于「图像配置」，变更必须立刻重渲染 —— 否则
        // 已绑定的 holder 仍显示旧设置的位图，用户得退出重进或一直划动才看到效果。
        // 这几个偏好没有对应的 config 属性，写回 lambda 是空操作，只为触发 refreshAdapter()。
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
        // enhancementCacheKey），不注册的话已绑定 holder 不会重渲染。
        readerPreferences.aiAreaDownscale
            .register({ }, { imagePropertyChangedListener?.invoke() })
        readerPreferences.aiAreaDownscaleStrength
            .register({ }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.webtoonSidePadding
            .register({ sidePadding = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.navigationModeWebtoon
            .register({ navigationMode = it }, { updateNavigation(it) })

        readerPreferences.webtoonNavInverted
            .register({ tappingInverted = it }, { navigator.invertMode = it })
        readerPreferences.webtoonNavInverted.changes()
            .drop(1)
            .onEach { navigationModeChangedListener?.invoke() }
            .launchIn(scope)

        readerPreferences.dualPageSplitWebtoon
            .register({ dualPageSplit = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.dualPageInvertWebtoon
            .register({ dualPageInvert = it }, { imagePropertyChangedListener?.invoke() })

        readerPreferences.dualPageRotateToFitWebtoon
            .register(
                { dualPageRotateToFit = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.dualPageRotateToFitInvertWebtoon
            .register(
                { dualPageRotateToFitInvert = it },
                { imagePropertyChangedListener?.invoke() },
            )

        readerPreferences.webtoonDisableZoomOut
            .register(
                { zoomOutDisabled = it },
                { zoomPropertyChangedListener?.invoke(it) },
            )

        readerPreferences.webtoonDoubleTapZoomEnabled
            .register(
                { doubleTapZoom = it },
                { doubleTapZoomChangedListener?.invoke(it) },
            )

        readerPreferences.readerTheme.changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { themeChangedListener?.invoke() }
            .launchIn(scope)

        // SY -->
        readerPreferences.cropBordersContinuousVertical
            .register({ continuousCropBorders = it }, { imagePropertyChangedListener?.invoke() })

        // MihonSY (2026-10-03): 两个动画开关都只在滚动动作时现读（WebtoonViewer 的
        // scrollUp/scrollDown/scrollDownBy），不改任何位图输出，不再接
        // imagePropertyChangedListener —— 否则拨一下开关会白白重建 adapter，可见页
        // 重新解码 + 重跑 AI 增强。
        readerPreferences.pageTransitionsWebtoon
            .register({ usePageTransitions = it })

        // Komiho: v2 动画开关（切到 v2 不需要重排页面）
        readerPreferences.pageTransitionsWebtoonV2
            .register({ usePageTransitionsV2 = it })

        readerPreferences.pageTransitionsV2Speed
            .register(
                { speed ->
                    pageTransitionsV2SpeedMs = ReaderPreferences.PageTransitionsV2Speeds
                        .firstOrNull { it == speed }
                        ?: ReaderPreferences.PAGE_TRANSITIONS_V2_SPEED_DEFAULT
                },
            )
        // SY <--

        // MihonSY -->
        readerPreferences.webtoonTapScrollDistance
            .register(
                { tapScrollDistanceFraction = ReaderPreferences.WebtoonTapScrollFractions.getOrElse(it) { 0.75f } },
                { tapScrollChangedListener?.invoke() },
            )
        readerPreferences.webtoonTapScrollDuration
            .register(
                {
                    tapScrollDurationMillis = it.coerceIn(
                        ReaderPreferences.WEBTOON_TAP_SCROLL_DURATION_MIN,
                        ReaderPreferences.WEBTOON_TAP_SCROLL_DURATION_MAX,
                    )
                },
                { tapScrollChangedListener?.invoke() },
            )
        readerPreferences.webtoonOriginalSize
            .register({ originalSize = it }, { imagePropertyChangedListener?.invoke() })

        // Komiho: 预取深度变化 → 通知 viewer 重算 extra layout space
        readerPreferences.webtoonPrefetchDepth
            .register(
                {
                    webtoonPrefetchDepth = it.coerceIn(
                        ReaderPreferences.WEBTOON_PREFETCH_DEPTH_MIN,
                        ReaderPreferences.WEBTOON_PREFETCH_DEPTH_MAX,
                    )
                },
                { prefetchChangedListener?.invoke() },
            )
        // MihonSY <--
    }

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = tappingInverted }
        }

    /**
     * Komiho: 见 [ViewerConfig.imageFingerprint]。必须覆盖 init 里所有注册到
     * `imagePropertyChangedListener` 的偏好 —— 新增这类 register 时记得同步补进来。
     * 增强类偏好由 [ReaderPreferences.enhancementCacheKey]（模式/倍率/模型/tile + 两处 cropBorders）
     * 一次覆盖。
     */
    override fun imageFingerprint(): String = buildString {
        append(readerPreferences.enhancementCacheKey())
        append('|').append(readerPreferences.webtoonSidePadding.get())
        append('|').append(readerPreferences.dualPageSplitWebtoon.get())
        append('|').append(readerPreferences.dualPageInvertWebtoon.get())
        append('|').append(readerPreferences.dualPageRotateToFitWebtoon.get())
        append('|').append(readerPreferences.dualPageRotateToFitInvertWebtoon.get())
        append('|').append(readerPreferences.cropBordersContinuousVertical.get())
        // MihonSY: 两个翻页/滚动动画开关不改位图输出（见上方 register 处注释），不进指纹。
        append('|').append(readerPreferences.webtoonOriginalSize.get())
    }

    override fun defaultNavigation(): ViewerNavigation {
        return LNavigation()
    }

    override fun updateNavigation(navigationMode: Int) {
        this.navigator = when (navigationMode) {
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
}
