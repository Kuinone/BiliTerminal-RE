package com.RobinNotBad.BiliClient.util

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.RobinNotBad.BiliClient.activity.disguise.DisguiseLauncher
import java.util.WeakHashMap

/**
 * 「双击顶栏 → 打开伪装页」的手势层（issue #22）。
 *
 * <h3>为什么必须接管触摸，而不是只挂一个双击监听</h3>
 * 顶栏单击的语义是「返回上一页 / 打开菜单」，长按是「打开菜单列表 / 视频详情页关闭所有页」。
 * 双击的**第一次抬手已经是一次完整 click** —— 若还让 View 自己发 click，页面会先 `finish()`，
 * 第二次点击根本落不到同一个 View 上。所以这里：
 * - `OnTouchListener` 返回 `true` 消费全部触摸（View 不再自己发 click / longclick）；
 * - 单击 → [View.performClick]、长按 → [View.performLongClick]，把动作**回放**给各页面自己装的监听器；
 * - 双击 → 打开伪装页。
 *
 * 这样各页面的顶栏监听器一行都不用改，语义全保留（详见 `docs/architecture-map.md` §7.53）。
 *
 * <h3>只在对同一个 View 安装一次</h3>
 * 用 [WeakHashMap] 记录装过的 View：Activity 每次 `onStart` 都会调 [install]（开关可能在别处改过），
 * 重复安装会让手势叠加、单击被延迟两次。用弱引用是为了 RecyclerView 的 item 顶栏被回收后能自然释放。
 *
 * <h3>代价</h3>
 * 单击要等一个双击判定窗口（系统双击超时，约 300ms）。所以**只在开关打开时安装**，
 * 关闭时由调用方 [detach] 摘掉。
 */
object DisguiseGesture {

    private val installed: MutableSet<View> = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /**
     * 在 [topBar] 上安装手势层。
     *
     * @return true 表示本次装上了（或之前已装过）；false 表示开关没开 / view 为空
     */
    @JvmStatic
    fun install(context: Context, topBar: View?): Boolean {
        if (topBar == null) return false
        if (!DisguisePrefs.isEnabled()) return false
        if (installed.contains(topBar)) return true

        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 确认不是双击：把单击还给顶栏原本的行为
                topBar.performClick()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                DisguiseLauncher.launch(context)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // 没有长按监听器的页面（菜单页等），旧行为是抬手回落成单击，这里保持一致
                if (!topBar.performLongClick()) topBar.performClick()
            }
        })
        topBar.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true // 自己消费：否则 View 会在 UP 时自己再发一次 click，页面先被关掉
        }
        installed.add(topBar)
        Logu.d("disguise", "顶栏双击伪装已就绪")
        return true
    }

    /**
     * 摘掉手势层（开关被关掉时调用）。
     *
     * 只清 `OnTouchListener`：单击 / 长按监听器从来没被摘过，恢复触摸分发它们就照旧工作。
     */
    @JvmStatic
    fun detach(topBar: View?) {
        if (topBar == null) return
        if (!installed.remove(topBar)) return
        topBar.setOnTouchListener(null)
        Logu.d("disguise", "顶栏双击伪装已摘除")
    }

    /** 该 View 当前是否装着手势层。 */
    @JvmStatic
    fun isInstalled(topBar: View?): Boolean = topBar != null && installed.contains(topBar)
}
