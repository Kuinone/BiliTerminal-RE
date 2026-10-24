package com.RobinNotBad.BiliClient.activity.video

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.reply.ReplyFragment
import com.RobinNotBad.BiliClient.activity.video.info.VideoInfoFragment
import com.RobinNotBad.BiliClient.activity.video.info.VideoRcmdFragment
import com.RobinNotBad.BiliClient.ui.widget.SwipePageLayout
import com.RobinNotBad.BiliClient.util.Logu

/**
 * 短视频页右侧的第 1~3 页：视频详情 / 评论 / 相关推荐。
 *
 * 为什么不直接把 [VideoInfoFragment] 等塞进外壳 pager：它们的 `newInstance(aid)` 是
 * 「创建那一刻定 aid」，而短视频会一直往下翻、当前视频随时在变。这里做成一层**壳**：
 * 壳本身按页码创建（不变），真正的子 Fragment 在 [render] 里按**当前**短视频重建。
 *
 * 换视频时不重建壳、也不让外壳 pager 重绑（见 [ShortVideoShellAdapter.refreshDetailPages]）：
 * 26.10.21 真机踩过——把 aid 编进 `getItemId` 再 `notifyItemRangeChanged`，旧 fragment 的
 * 视图被摘掉后新视图挂不回来，整页只剩背景色（日志里子 Fragment 明明 `children=1`）。
 *
 * 26.10.24：换条时的重建不再同步 `commitNow`，改为「延后一拍再渲染」（当前可见页立即
 * 下一帧渲染、不可见页延迟 [RENDER_DELAY_MS]）。原因：短视频竖向翻页时 3 个详情页会在
 * 同一帧里各 commitNow 一次重量级 Fragment，主线程被占满，横滑/竖滑都会掉帧。
 */
class ShortVideoDetailPageFragment : Fragment() {

    private val pageKind: Int
        get() = arguments?.getInt(ARG_PAGE) ?: PAGE_DETAIL

    /** 当前锚点视频；开头来自 arguments，之后由 [updateItem] 热更新。 */
    private var anchorAid = 0L
    private var anchorBvid: String? = null

    private var container: ViewGroup? = null
    private var statusText: TextView? = null

    /** 已经塞进容器的子 Fragment 的 aid，避免重复重建。 */
    private var renderedAid = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private var renderPosted = false
    private val renderRunnable = Runnable {
        renderPosted = false
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        anchorAid = arguments?.getLong(ARG_AID) ?: 0L
        anchorBvid = arguments?.getString(ARG_BVID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_short_video_detail_page, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view.findViewById(R.id.detailContainer)
        statusText = view.findViewById(R.id.detailStatus)

        // 详情/评论/相关推荐三页的横向翻页由外壳经典 ViewPager 原生处理，
        // 本页根布局不接管手势（不设 edgeExitCheck，SwipePageLayout 永不拦截）。
        scheduleRender(immediate = false)
    }

    override fun onResume() {
        super.onResume()
        scheduleRender(immediate = true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiHandler.removeCallbacks(renderRunnable)
        renderPosted = false
        container = null
        statusText = null
        renderedAid = 0L
    }

    /**
     * 短视频换了当前条：热更新本页内容。
     *
     * 只改数据不重建外层的壳，调用点见 [ShortVideoShellAdapter.refreshDetailPages]。
     */
    fun updateItem(aid: Long, bvid: String?) {
        if (aid == anchorAid && bvid == anchorBvid) return
        anchorAid = aid
        anchorBvid = bvid
        if (isAdded && view != null) scheduleRender(immediate = false)
    }

    /**
     * 合并多次渲染请求，并按「当前是否可见」决定时机：
     * 可见页下一帧渲染（用户马上要看到内容），不可见页延迟 [RENDER_DELAY_MS]
     * 再渲染（避开正在进行的翻页/列表滚动帧），三页再按页号错开 [STAGGER_MS]，
     * 避免同一帧里连续重建 3 个重量级 Fragment。
     */
    private fun scheduleRender(immediate: Boolean) {
        if (view == null) return
        if (anchorAid > 0L && renderedAid == anchorAid) {
            renderPosted = false
            uiHandler.removeCallbacks(renderRunnable)
            return
        }
        renderPosted = true
        val host = activity as? ShortVideoPlayerActivity
        val visible = host?.currentShellPage() == pageKind
        val delay = if (immediate && visible) 0L
        else RENDER_DELAY_MS + (pageKind - 1) * STAGGER_MS
        uiHandler.removeCallbacks(renderRunnable)
        uiHandler.postDelayed(renderRunnable, delay)
    }

    private fun render() {
        val container = container ?: return
        val statusText = statusText ?: return

        if (anchorAid <= 0L) {
            statusText.text = getString(R.string.text_shortvideoplayer_detail_not_ready)
            statusText.visibility = View.VISIBLE
            renderedAid = 0L
            return
        }
        if (renderedAid == anchorAid) return

        val target: Fragment = when (pageKind) {
            PAGE_DETAIL -> VideoInfoFragment.newInstance(anchorAid, anchorBvid)
            PAGE_REPLY -> ReplyFragment.newInstance(anchorAid, 1, 0, -1L, 0L)
            else -> VideoRcmdFragment.newInstance(anchorAid)
        }

        statusText.visibility = View.GONE
        try {
            childFragmentManager.beginTransaction()
                .replace(container.id, target)
                .commitNow()
            renderedAid = anchorAid
        } catch (e: Exception) {
            // 宿主正在销毁时 commitNow 会抛 IllegalStateException，忽略即可（下次绑定会重来）
            Logu.e("ShortVideoDetailPage", "render 失败：${e.message}")
        }
    }

    companion object {
        const val PAGE_DETAIL = 1
        const val PAGE_REPLY = 2
        const val PAGE_RELATED = 3

        private const val ARG_PAGE = "page"
        private const val ARG_AID = "aid"
        private const val ARG_BVID = "bvid"

        /** 不可见页延迟多久再重建，给正在进行的翻页/滚动让出主线程。 */
        private const val RENDER_DELAY_MS = 180L

        /** 三页错峰间隔，避免同一帧内连续 commit 三个重 Fragment。 */
        private const val STAGGER_MS = 60L

        fun newInstance(page: Int, aid: Long, bvid: String?): ShortVideoDetailPageFragment {
            val fragment = ShortVideoDetailPageFragment()
            fragment.arguments = Bundle().apply {
                putInt(ARG_PAGE, page)
                putLong(ARG_AID, aid)
                putString(ARG_BVID, bvid)
            }
            return fragment
        }
    }
}
