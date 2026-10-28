package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.os.Parcelable
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.viewpager.widget.DirectionalViewPager
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap

/**
 * Pager implementation that listens for tap and long tap and allows temporarily disabling touch
 * events in order to work with child views that need to disable touch events on this parent. The
 * pager can also be declared to be vertical by creating it with [isHorizontal] to false.
 */
open class Pager(
    context: Context,
    isHorizontal: Boolean = true,
) : DirectionalViewPager(context, isHorizontal) {

    /**
     * Tap listener function to execute when a tap is detected.
     */
    var tapListener: ((MotionEvent) -> Unit)? = null

    /**
     * Long tap listener function to execute when a long tap is detected.
     */
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    // SY -->
    var isRestoring = false

    override fun onRestoreInstanceState(state: Parcelable?) {
        isRestoring = true
        val currentItem = currentItem
        super.onRestoreInstanceState(state)
        setCurrentItem(currentItem, false)
        isRestoring = false
    }
    // SY <--

    /**
     * Gesture listener that implements tap and long tap events.
     */
    private val gestureListener = object : GestureDetectorWithLongTap.Listener() {
        override fun onSingleTapUp(ev: MotionEvent): Boolean {
            // 关闭双击缩放后点击在**抬手当帧**派发，不再等双击窗口（见 [tapImmediate]）。
            if (!tapImmediate) return false
            tapListener?.invoke(ev)
            return true
        }

        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            // 已由 onSingleTapUp 即时派发过，跳过，避免一次点击翻两页。
            if (tapImmediate) return true
            tapListener?.invoke(ev)
            return true
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            val listener = longTapListener
            if (listener != null && listener.invoke(ev)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    /**
     * Gesture detector which handles motion events.
     */
    private val gestureDetector = GestureDetectorWithLongTap(context, gestureListener)

    /**
     * Whether the gesture detector is currently enabled.
     */
    private var isGestureDetectorEnabled = true

    /**
     * 点击是否「抬手即派发」。
     *
     * `GestureDetector` 只有在挂了双击监听时才需要等双击窗口（约 300ms）才敢确认单击 ——
     * 那 300ms 就是点击翻页的固定延迟。页漫关掉「双击缩放」后这里置 true：把双击监听
     * 从探测器上摘掉，点击由 `onSingleTapUp` 在抬手当帧派发，动画立刻启动。
     *
     * 打开双击缩放时保持 false（走 `onSingleTapConfirmed`），双击放大照旧。
     */
    var tapImmediate: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            gestureDetector.setOnDoubleTapListener(if (value) null else gestureListener)
        }

    /** 是否为横向 pager。竖向翻页没有覆盖翻页动画。 */
    val isHorizontalPager: Boolean = isHorizontal

    /**
     * 手势按下回调。点击翻页动画进行中若用户触摸屏幕，用它立刻落位，
     * 把控制权交回手势（见 `PageFlipController.abortTapFlip`）。
     */
    var onTouchDownListener: ((Float) -> Unit)? = null

    /**
     * Dispatches a touch event.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // 用户重新触摸时立刻让点击翻页动画落位，把控制权交回手势。
            onTouchDownListener?.invoke(ev.y)
        }
        val handled = super.dispatchTouchEvent(ev)
        if (isGestureDetectorEnabled) {
            gestureDetector.onTouchEvent(ev)
        }
        return handled
    }

    /**
     * Whether the given [ev] should be intercepted. Only used to prevent crashes when child
     * views manipulate [requestDisallowInterceptTouchEvent].
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Handles a touch event. Only used to prevent crashes when child views manipulate
     * [requestDisallowInterceptTouchEvent].
     */
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return try {
            super.onTouchEvent(ev)
        } catch (e: NullPointerException) {
            false
        } catch (e: IndexOutOfBoundsException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Executes the given key event when this pager has focus. Just do nothing because the reader
     * already dispatches key events to the viewer and has more control than this method.
     */
    override fun executeKeyEvent(event: KeyEvent): Boolean {
        // Disable viewpager's default key event handling
        return false
    }

    /**
     * Enables or disables the gesture detector.
     */
    fun setGestureDetectorEnabled(enabled: Boolean) {
        isGestureDetectorEnabled = enabled
    }
}
