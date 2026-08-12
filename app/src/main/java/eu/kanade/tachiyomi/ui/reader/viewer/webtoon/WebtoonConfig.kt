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

    var originalSize = false
        private set
    // MihonSY <--

    val theme = readerPreferences.readerTheme.get()

    // SY -->
    var usePageTransitions = false

    var continuousCropBorders = false
        private set

    // SY <--
    init {
        readerPreferences.cropBordersWebtoon
            .register({ imageCropBorders = it }, { imagePropertyChangedListener?.invoke() })

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

        readerPreferences.pageTransitionsWebtoon
            .register({ usePageTransitions = it }, { imagePropertyChangedListener?.invoke() })
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
        // MihonSY <--
    }

    override var navigator: ViewerNavigation = defaultNavigation()
        set(value) {
            field = value.also { it.invertMode = tappingInverted }
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
