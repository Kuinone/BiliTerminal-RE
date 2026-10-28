package com.RobinNotBad.BiliClient.util.view

import androidx.recyclerview.widget.RecyclerView

import com.RobinNotBad.BiliClient.util.SettingsKeys
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.bumptech.glide.Glide

/**
 * 「翻动时不加载图片」：只在**手指甩出去的惯性滑动**期间暂停图片请求，停下来再恢复。
 *
 * 26.10.27 性能审查：
 * - 列表图片原先大多挂在 Application 级 RequestManager 上，这里对 Activity 级的 pause 实际管不到它们，
 *   开关形同虚设；列表改走 [com.RobinNotBad.BiliClient.util.GlideUtil.with] 后才真正生效。
 * - 生效之后要防一个手表专属的坑：表冠滚动走 `smoothScrollBy`，状态是 IDLE → SETTLING，
 *   原实现对 SETTLING 一律暂停，等于「一边转表冠一边看，图片一张都不加载」。所以只认
 *   DRAGGING → SETTLING（甩动）这一种转换；按住拖动时照常加载，用户是在慢慢看。
 */
class ImageAutoLoadScrollListener : RecyclerView.OnScrollListener() {

    private var lastState = RecyclerView.SCROLL_STATE_IDLE
    private var paused = false

    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
        super.onScrollStateChanged(recyclerView, newState)
        val previous = lastState
        lastState = newState
        val shouldPause = shouldPause(previous, newState)
        if (shouldPause == paused) return
        try {
            val manager = Glide.with(recyclerView.context)
            if (shouldPause) manager.pauseRequests() else manager.resumeRequests()
            paused = shouldPause
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        /** 纯函数：只有「拖动后甩出」的惯性阶段暂停；表冠的 IDLE → SETTLING 不暂停。 */
        @JvmStatic
        fun shouldPause(previous: Int, newState: Int): Boolean =
            newState == RecyclerView.SCROLL_STATE_SETTLING && previous == RecyclerView.SCROLL_STATE_DRAGGING

        @JvmStatic
        fun install(recyclerView: RecyclerView) {
            recyclerView.addOnScrollListener(ImageAutoLoadScrollListener())
        }

        /** 按设置项「翻动时不加载图片」决定是否安装（默认开）。列表页一律走这个。 */
        @JvmStatic
        fun installIfEnabled(recyclerView: RecyclerView) {
            if (SharedPreferencesUtil.getBoolean(SettingsKeys.IMAGE_NO_LOAD_ONSCROLL, true))
                recyclerView.addOnScrollListener(ImageAutoLoadScrollListener())
        }
    }
}
