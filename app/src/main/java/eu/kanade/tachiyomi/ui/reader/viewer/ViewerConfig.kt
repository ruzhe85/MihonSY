package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import tachiyomi.core.common.preference.Preference

/**
 * Common configuration for all viewers.
 */
abstract class ViewerConfig(
    protected val readerPreferences: ReaderPreferences,
    private val scope: CoroutineScope,
) {

    var imagePropertyChangedListener: (() -> Unit)? = null

    /**
     * Komiho: 设置窗口打开期间延迟「影响成像」的刷新。
     *
     * 设置窗口（阅读器设置 Dialog）打开时调用 [deferImagePropertyRefresh]，此后的图像属性变更
     * 只置 [pendingImagePropertyChange] 标记、不立即重建；关闭窗口时 [flushImagePropertyRefresh]
     * 一次性触发一次重建。这样连续改增强/缩放等选项不会每改一次就重解码 + 重增强可见页。
     */
    var imagePropertyRefreshDeferred = false
        private set
    var pendingImagePropertyChange = false
        private set
    private var refreshAdapterAction: (() -> Unit)? = null

    /** viewer 在注册 [imagePropertyChangedListener] 时一并绑定真正的重建动作。 */
    fun bindRefreshAdapter(action: () -> Unit) {
        refreshAdapterAction = action
    }

    /** 设置窗口打开：进入延迟模式并清空待刷新标记。 */
    fun deferImagePropertyRefresh() {
        imagePropertyRefreshDeferred = true
        pendingImagePropertyChange = false
    }

    /**
     * 图像属性变更入口：延迟模式下只置标记，否则立即重建。
     * [imagePropertyChangedListener] 应直接委托到这里。
     */
    fun onImagePropertyChanged() {
        if (imagePropertyRefreshDeferred) {
            pendingImagePropertyChange = true
        } else {
            refreshAdapterAction?.invoke()
        }
    }

    /** 设置窗口关闭：退出延迟模式，若有待刷新则一次性重建。 */
    fun flushImagePropertyRefresh() {
        imagePropertyRefreshDeferred = false
        if (pendingImagePropertyChange) {
            pendingImagePropertyChange = false
            refreshAdapterAction?.invoke()
        }
    }

    /**
     * Komiho: 「影响成像」的全部偏好拼成的指纹。
     *
     * 用途见 WebtoonViewer / PagerViewer 的 refreshAdapter()：`register()` 在订阅时会**首发一次
     * 当前值**（AndroidPreference.changes() 的 `onStart { emit(…) }`），viewer 刚建好时这一批回调
     * 并不代表「设置变了」，靠指纹把这种情况和用户真的改了设置区分开，避免无谓的适配器重建
     * （每次重建都会重新解码 + 增强可见页）。
     *
     * 实现方必须列出自己注册到 [imagePropertyChangedListener] 的**每一个**偏好；只列子集会让
     * 被漏掉的那项设置失去「改了即时生效」。指纹直接读 `Preference.get()`（同步可读），不要用
     * config 的属性字段 —— 那些字段是由异步 register 赋值的，构造期可能还是默认值。
     */
    abstract fun imageFingerprint(): String

    var navigationModeChangedListener: (() -> Unit)? = null

    var tappingInverted = ReaderPreferences.TappingInvertMode.NONE
    var longTapEnabled = true
    var doubleTapAnimDuration = 500
    var volumeKeysEnabled = false
    var volumeKeysInverted = false
    var alwaysShowChapterTransition = true
    var navigationMode = 0
        protected set

    var forceNavigationOverlay = false

    var navigationOverlayOnStart = false

    var dualPageSplit = false
        protected set

    var dualPageInvert = false
        protected set

    var dualPageRotateToFit = false
        protected set

    var dualPageRotateToFitInvert = false
        protected set

    abstract var navigator: ViewerNavigation
        protected set

    init {
        readerPreferences.readWithLongTap
            .register({ longTapEnabled = it })

        readerPreferences.doubleTapAnimSpeed
            .register({ doubleTapAnimDuration = it })

        readerPreferences.readWithVolumeKeys
            .register({ volumeKeysEnabled = it })

        readerPreferences.readWithVolumeKeysInverted
            .register({ volumeKeysInverted = it })

        readerPreferences.alwaysShowChapterTransition
            .register({ alwaysShowChapterTransition = it })

        forceNavigationOverlay = readerPreferences.showNavigationOverlayNewUser.get()
        if (forceNavigationOverlay) {
            readerPreferences.showNavigationOverlayNewUser.set(false)
        }

        readerPreferences.showNavigationOverlayOnStart
            .register({ navigationOverlayOnStart = it })
    }

    protected abstract fun defaultNavigation(): ViewerNavigation

    abstract fun updateNavigation(navigationMode: Int)

    fun <T> Preference<T>.register(
        valueAssignment: (T) -> Unit,
        onChanged: (T) -> Unit = {},
    ) {
        changes()
            .onEach { valueAssignment(it) }
            .distinctUntilChanged()
            .onEach { onChanged(it) }
            .launchIn(scope)
    }
}
