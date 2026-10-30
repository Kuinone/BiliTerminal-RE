package com.RobinNotBad.BiliClient.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager
import com.RobinNotBad.BiliClient.util.Logu

/**
 * 兜住 AndroidX 旧 [ViewPager] 的多指崩溃。
 *
 * <p>崩溃形态有两种，都出现在库内部的 `onInterceptTouchEvent` / `onTouchEvent` 里：
 * `IllegalArgumentException: pointerIndex out of range`（`getX` 拿到已失效的手指下标）与
 * `IllegalArgumentException: invalid pointerIndex -1 for MotionEvent`。
 * 这是 support/AndroidX 老包里的已知缺陷：事件分发过程中手指抬起与父容器拦截交错时，
 * 库里用 `ACTION_POINTER_UP` 那一帧的 index 去查下一帧的 `MotionEvent`，于是越界。
 *
 * <p>线上实测一周内 **7 例**（4 例 `pointerIndex out of range` + 3 例 `invalid pointerIndex`），
 * 全部停在这两个方法里。库不再维护，只能在我们这层吃掉异常——**只吞指针下标这一类**，
 * 其它 `IllegalArgumentException` 照常抛出，免得把真正的参数错误也藏起来。
 *
 * <p>用法：布局里把 `<androidx.viewpager.widget.ViewPager>` 换成本类即可，
 * 代码侧的 `lateinit var pager: ViewPager` 不用改（子类关系仍在）。
 */
class SafeViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewPager(context, attrs) {

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        guardPointerCrash { super.onInterceptTouchEvent(ev) }

    override fun onTouchEvent(ev: MotionEvent): Boolean =
        guardPointerCrash { super.onTouchEvent(ev) }

    private inline fun guardPointerCrash(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            // 只有 message 里带 pointerIndex 的才是这个库缺陷；其它参数异常必须继续抛
            if (e.message?.contains("pointerIndex", ignoreCase = true) != true) throw e
            Logu.v("ViewPager", "吃掉多指指针下标异常：${e.message}")
            false
        }
}
