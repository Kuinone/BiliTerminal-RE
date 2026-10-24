package com.RobinNotBad.BiliClient.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import kotlin.math.abs

/**
 * 短视频第 0 页的根布局：只负责「右滑退出」这一条边缘手势。
 *
 * 横向翻页本身交给外壳的经典 ViewPager 原生处理（见 `activity_short_video_player.xml`
 * 注释），所以这里不再驱动 `ViewPager2.fakeDrag`，也不拦截左滑。只有同时满足以下条件
 * 才接管手势：
 *
 * 1. [edgeExitCheck] 返回 true（当前停在第 0 页，前面没有上一页可翻）；
 * 2. 手指是**明确向右**的横向滑动（横向位移大于纵向 2 倍），且起点不在进度条等
 *    横向子控件上。
 *
 * 接管后整页跟手右移，松手时超过 [SWIPE_MIN_RATIO] 就滑出屏幕并回调 [onSwipe]，
 * 否则回弹。其它页面根布局也复用这个类，但不设 [edgeExitCheck] 时永不拦截。
 *
 * 历史：26.10.24 之前这里用 `ViewPager2.beginFakeDrag()/fakeDragBy()` 驱动外壳翻页，
 * 但手势未正常收尾时 pager 会卡在 fake-dragging（返回键 `setCurrentItem` 直接崩），
 * 且跟手/吸附在真机上始终有停顿，故整体换回经典 ViewPager，只保留边缘退出。
 */
class SwipePageLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 手指向右滑过阈值时回调（direction 恒为 1）；本页不知道页序，由宿主决定行为。 */
    var onSwipe: ((direction: Int) -> Unit)? = null

    /** 是否允许边缘退出：返回 true 时才接管右滑手势。不设则永不拦截。 */
    var edgeExitCheck: (() -> Boolean)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var decided = false
    private var blockedByChild = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetState()
                downX = ev.x
                downY = ev.y
                blockedByChild = hasHorizontalControlAt(this, ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (decided) return dragging
                if (blockedByChild) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) < touchSlop && abs(dy) < touchSlop) return false
                decided = true
                dragging = edgeExitCheck?.invoke() == true && dx > 0f && abs(dx) > abs(dy) * 2
                if (dragging) {
                    // 从子 View 手里接过手势，并从当前点重新起算位移
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downX = ev.x - dx
                    translationX = 0f
                }
                return dragging
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> resetState()
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                translationX = (ev.x - downX).coerceAtLeast(0f)
            }
            MotionEvent.ACTION_UP -> {
                if (translationX > width * SWIPE_MIN_RATIO) {
                    animate().translationX(width.toFloat())
                        .setDuration(EXIT_DURATION_MS)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { onSwipe?.invoke(1) }
                        .start()
                } else {
                    animate().translationX(0f)
                        .setDuration(SNAP_BACK_DURATION_MS)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                dragging = false
                decided = false
            }
            MotionEvent.ACTION_CANCEL -> {
                animate().translationX(0f).setDuration(SNAP_BACK_DURATION_MS).start()
                dragging = false
                decided = false
            }
        }
        return true
    }

    private fun resetState() {
        if (dragging || translationX != 0f) {
            animate().cancel()
            translationX = 0f
        }
        dragging = false
        decided = false
        blockedByChild = false
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        translationX = 0f
        dragging = false
        decided = false
        blockedByChild = false
        super.onDetachedFromWindow()
    }

    companion object {
        private const val SWIPE_MIN_RATIO = 0.18f
        private const val EXIT_DURATION_MS = 130L
        private const val SNAP_BACK_DURATION_MS = 160L

        /**
         * 触点是否落在进度条、横向滚动容器这类自己消费横向拖动的控件上。
         *
         * 这些控件拖动时会自己调 `requestDisallowInterceptTouchEvent(true)`，但父布局在
         * 它进入拖动状态之前就可能先拦截，所以按坐标主动查一遍，从源头避开。
         */
        private fun hasHorizontalControlAt(root: View, x: Float, y: Float): Boolean {
            if (root is ViewGroup) {
                for (i in root.childCount - 1 downTo 0) {
                    val child = root.getChildAt(i)
                    if (child.visibility != View.VISIBLE) continue
                    val left = child.left + child.translationX
                    val top = child.top + child.translationY
                    val localX = x - left
                    val localY = y - top
                    if (localX >= 0 && localY >= 0 && localX < child.width && localY < child.height) {
                        if (child is SeekBar || child is HorizontalScrollView) return true
                        if (hasHorizontalControlAt(child, localX, localY)) return true
                    }
                }
            }
            return false
        }
    }
}
