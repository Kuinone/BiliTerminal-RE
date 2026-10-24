package com.RobinNotBad.BiliClient.activity.search

import com.RobinNotBad.BiliClient.util.UiText

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.annotation.NonNull
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.api.SearchApi
import com.RobinNotBad.BiliClient.listener.OnLoadMoreListener
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomGridManager
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.ui.appearance.ColorScheme
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.view.ImageAutoLoadScrollListener

open class SearchFragment : Fragment() {
    lateinit var swipeRefreshLayout: SwipeRefreshLayout
    lateinit var recyclerView: RecyclerView
    var emptyView: TextView? = null
    var listener: OnLoadMoreListener? = null
    var refreshListener: SwipeRefreshLayout.OnRefreshListener? = null
    var keyword: String? = null
    // @Volatile：子类（SearchVideoFragment 等）在 CenterThreadPool 后台线程写，
    // 滚动监听在主线程读，无 happens-before 边，主线程可能长期读到陈旧的 false。
    @Volatile
    var bottom: Boolean = false
        set(value) {
            field = value
            if (page == 1) showEmptyView(value)
            else if (value && isAdded) {
                MsgUtil.showMsg(UiText.of("已经到底啦OwO"))
            }
        }
    var page = 1
    var lastLoadTimestamp: Long = 0
    var refreshable = false

    /**
     * 当前排序档位（`order` 参数值）。**@Volatile**：点击在主线程写，子类在
     * CenterThreadPool 的后台线程读来决定请求哪条接口。与 [bottom] 同一类问题——
     * 没有 happens-before 边时后台线程可能读到旧值，表现就是"点了排序但拉回来的还是旧排序的结果"。
     */
    @Volatile
    protected var sortOrder: String = SearchSortOptions.TOTALRANK

    /**
     * 本分类支持的排序档位；返回空列表表示没有排序条（番剧 / 用户 / 直播的排序键与视频不通用，
     * 本功能不做）。排序条的控件在 `SearchActivity` 里——**不能放进 fragment**：搜索页的
     * `ViewPager` 只对齐到顶栏下方，搜索框是盖在 fragment 上方的，放进 fragment 的条会被
     * 搜索框压住（26.10.24 模拟器实测）。
     */
    open fun sortOptions(): List<SearchSortOption> = emptyList()

    /** 当前排序档位（`order` 参数值），供宿主写排序条的高亮状态。 */
    fun currentSortOrder(): String = sortOrder

    /**
     * 这次搜索是否被 B 站风控挡下（wbi 与旧接口都没拿到结果）。
     *
     * 排序按钮据此显示「因B站风控不可用」——`SearchApi` 的风控回退链路见
     * `docs/bilibili-api-notes.md` §3.14。
     */
    @Volatile
    private var sortBlocked: Boolean = false

    /**
     * 请求代次：排序 / 关键词 / 重试任一变化就自增。
     *
     * 光比较 `sortOrder` 不够——检查通过到 `runOnUiThread` 真正执行之间，用户在那一瞬间切了
     * 排序或换了关键词，旧结果仍会追加进新列表；换关键词的情况更是完全没被覆盖过
     * （26.10.24 审查指出）。所以发请求时抓一份代次 + 关键词 + 排序，**改列表前再验一次**。
     */
    @Volatile
    protected var loadGeneration: Int = 0
        private set

    /** 让所有在途请求作废。主线程调用。 */
    protected fun invalidateInFlightRequests() {
        loadGeneration++
    }

    /** 排序可用性变化时通知宿主重画按钮。由 `SearchActivity` 安装。 */
    var onSortStateChanged: (() -> Unit)? = null

    fun isSortBlocked(): Boolean = sortBlocked

    /** 子类在加载成功/被风控挡下时调用；状态真的变了才通知宿主。 */
    protected fun markSortBlocked(blocked: Boolean) {
        if (sortBlocked == blocked) return
        sortBlocked = blocked
        val notify = onSortStateChanged ?: return
        CenterThreadPool.runOnUiThread { notify() }
    }

    /** 排序被用户切换后调用，子类负责清空列表并重拉第一页（[page] 与 [bottom] 已由基类重置）。 */
    protected open fun onSortChanged(order: String) {
    }

    /**
     * 由宿主（`SearchActivity` 的排序条）调用：切换排序并重拉。
     *
     * `page` 必须先回 1 再清 `bottom`——[bottom] 的 setter 会按 `page == 1` 决定是"清空态"还是
     * "弹到底提示"，顺序反了会在切换排序时弹一次「已经到底啦」。
     */
    fun selectSort(order: String) {
        if (order == sortOrder) return
        sortOrder = order
        invalidateInFlightRequests()
        page = 1
        bottom = false
        setRefreshing(true)
        onSortChanged(order)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_simple_refresh, container, false)
    }

    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        emptyView = view.findViewById(R.id.emptyTip)

        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        swipeRefreshLayout.isEnabled = false

        recyclerView = view.findViewById(R.id.recyclerView)
        val layoutManager = getLayoutManager()
        recyclerView.layoutManager = layoutManager
        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(20)
        recyclerView.isNestedScrollingEnabled = true

        // 启用预取优化，提前加载即将进入屏幕的 item，减少滑动时 onBindViewHolder 的等待
        (layoutManager as? LinearLayoutManager)?.let {
            it.initialPrefetchItemCount = 4
            it.isItemPrefetchEnabled = true
        }

        val searchActivity = if (requireActivity() is SearchActivity) requireActivity() as SearchActivity else null
        val linearLayoutManager = layoutManager as? LinearLayoutManager

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {

            override fun onScrollStateChanged(@NonNull recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    if (!recyclerView.canScrollVertically(-1)) {
                        searchActivity?.onScrolled(-114)
                    }
                }
            }

            override fun onScrolled(@NonNull recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (dy > 0 && !swipeRefreshLayout.isRefreshing && !bottom && listener != null) {
                    linearLayoutManager?.let { manager ->
                        val lastItemPosition = manager.findLastVisibleItemPosition()
                        val itemCount = manager.itemCount
                        if (lastItemPosition >= itemCount - 3) {
                            goOnLoad()
                        }
                    }
                }

                searchActivity?.onScrolled(dy)
            }
        })
        ImageAutoLoadScrollListener.installIfEnabled(recyclerView)
    }

    fun setAdapter(adapter: RecyclerView.Adapter<*>) {
        runOnUiThread { recyclerView.adapter = adapter }
    }

    fun setOnRefreshListener(listener: SwipeRefreshLayout.OnRefreshListener) {
        this.refreshListener = listener
    }

    fun setRefreshing(bool: Boolean) {
        runOnUiThread { swipeRefreshLayout.isRefreshing = bool }
    }

    fun setOnLoadMoreListener(loadMore: OnLoadMoreListener) {
        listener = loadMore
    }

    private fun goOnLoad() {
        val timeCurrent = System.currentTimeMillis()
        if (timeCurrent - lastLoadTimestamp > 100) {
            swipeRefreshLayout.isRefreshing = true
            page++
            listener!!.onLoad(page)
            lastLoadTimestamp = timeCurrent
        }
    }

    fun runOnUiThread(runnable: Runnable) {
        // 判活必须写在 Runnable 内部：post 是无条件投递，投递时刻的 isAdded 到执行时可能已经失效，
        // detach 后再碰 view/context 就会抛 Fragment not attached to a context（#62 同族）
        val host = activity ?: return
        host.runOnUiThread {
            if (isAdded && view != null) runnable.run()
        }
    }

    fun showEmptyView(empty: Boolean) {
        if (emptyView != null) {
            runOnUiThread { emptyView!!.visibility = if (empty) View.VISIBLE else View.GONE }
        }
    }

    fun isRefreshing(): Boolean {
        if (swipeRefreshLayout != null) return swipeRefreshLayout.isRefreshing
        return false
    }

    fun report(e: Throwable) {
        // 搜索专用的失败原因（风控 / 服务端错误码）要原样弹出来：
        // `MsgUtil.err` 对任何 IOException 一律换成「网络错误」，带 message 也进不了界面
        // （26.10.24 审查发现）。
        if (e is SearchApi.SearchException) {
            MsgUtil.showMsg(e.message.orEmpty())
            return
        }
        MsgUtil.err(e)
    }

    fun loadFail() {
        page--
        // application context 取文案：失败回调常常发生在 fragment 已 detach 之后
        MsgUtil.showMsgLong(BiliTerminal.context!!.getString(R.string.text_load_failed))
        setRefreshing(false)
    }

    fun loadFail(e: Throwable) {
        page--
        report(e)
        setRefreshing(false)
    }

    fun getLayoutManager(): RecyclerView.LayoutManager {
        return if (SharedPreferencesUtil.getBoolean("ui_landscape", false))
            CustomGridManager(requireContext(), 3)
        else
            CustomLinearManager(requireContext())
    }

    fun update(keyword: String) {
        this.page = 1
        this.keyword = keyword
        this.refreshable = true
        bottom = false
        invalidateInFlightRequests()
        // 换了关键词就重新给一次机会：上一次被风控锁住的按钮不该一直锁着
        markSortBlocked(false)
    }

    fun refresh() {
        if (!refreshable) return
        refreshable = false
        setRefreshing(true)
        refreshListener!!.onRefresh()
    }

    /**
     * 重试当前排序的第一页（清空重拉）。**主线程调用。**
     *
     * 给「因B站风控不可用」那个按钮用：不重试的话风控态下既刷不了新页（下拉刷新关着、
     * 同一个关键词再点搜索会短路），也没有别的入口能解除锁定（26.10.24 审查发现）。
     */
    fun retry() {
        markSortBlocked(false)
        invalidateInFlightRequests()
        page = 1
        bottom = false
        setRefreshing(true)
        onSortChanged(sortOrder)
    }

    protected open fun refreshInternal() {
    }
}