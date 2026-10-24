package com.RobinNotBad.BiliClient.activity.search

import android.os.Bundle
import android.view.View

import androidx.annotation.NonNull

import com.RobinNotBad.BiliClient.adapter.video.VideoCardAdapter
import com.RobinNotBad.BiliClient.api.SearchApi
import com.RobinNotBad.BiliClient.model.VideoCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool

import org.json.JSONArray

class SearchVideoFragment : SearchFragment() {
    private var videoCardList = ArrayList<VideoCard>()
    private var videoCardAdapter: VideoCardAdapter? = null

    companion object {
        fun newInstance(): SearchVideoFragment {
            return SearchVideoFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun sortOptions(): List<SearchSortOption> = SearchSortOptions.VIDEO

    override fun onSortChanged(order: String) {
        refreshInternal()
    }

    override fun onViewCreated(@NonNull view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        videoCardList = ArrayList()
        videoCardAdapter = VideoCardAdapter(requireContext(), videoCardList)
        setAdapter(videoCardAdapter!!)

        setOnRefreshListener { refreshInternal() }
        setOnLoadMoreListener { page -> continueLoading(page) }
    }

    private fun continueLoading(page: Int) {
        CenterThreadPool.run {
            // 请求发出时先记下排序：网络慢的时候用户可能已经切了档位，
            // 那这批结果属于旧排序，不能再往新列表里追加（否则排序会串）。
            val orderAtRequest = sortOrder
            try {
                val list = ArrayList<VideoCard>()
                if (orderAtRequest == SearchSortOptions.TOTALRANK) {
                    // 综合排序走聚合搜索：它会把番剧结果混进第一页，且这条接口不支持 order
                    val result = SearchApi.search(keyword, page)
                    if (result != null) SearchApi.getVideosFromSearchResult(result, list, page == 1)
                } else {
                    val result = SearchApi.searchType(keyword, page, "video", orderAtRequest) as JSONArray?
                    SearchApi.getVideosFromTypeResult(result, list)
                }
                if (orderAtRequest != sortOrder) return@run // 已切换排序，这批结果作废
                markSortBlocked(false)

                if (page == 1) showEmptyView(false)
                if (list.size == 0) bottom = true
                else CenterThreadPool.runOnUiThread {
                    if (!isAdded || view == null) return@runOnUiThread
                    // 26.10.04 批次 3（B5）：起点写成了 lastSize + 1，第 2 页起会多算 1 格，
                    // RecyclerView 直接抛 IndexOutOfBoundsException。正确起点就是插入前的 size。
                    val lastSize = videoCardList.size
                    videoCardList.addAll(list)
                    videoCardAdapter!!.notifyItemRangeInserted(lastSize, list.size)
                }
            } catch (e: Exception) {
                // 与成功分支同一个判据：旧排序的失败也不该动状态
                // （风控链路比正常链路慢，最坏情况是新排序已经刷出来了、旧请求才抛异常，
                //  那时把按钮锁成「不可用」就冤枉了——26.10.24 审查发现）
                if (orderAtRequest != sortOrder) return@run
                if (e is SearchApi.RiskControlException) markSortBlocked(true)
                e.printStackTrace()
                loadFail(e)
            }
            setRefreshing(false)
        }
    }

    override fun refreshInternal() {
        CenterThreadPool.runOnUiThread {
            if (!isAdded || view == null) return@runOnUiThread
            page = 1
            if (this.videoCardAdapter == null)
                this.videoCardAdapter = VideoCardAdapter(this.requireContext(), this.videoCardList)
            val size_old = this.videoCardList.size
            this.videoCardList.clear()
            if (size_old != 0) this.videoCardAdapter!!.notifyItemRangeRemoved(0, size_old)
            CenterThreadPool.run { continueLoading(page) }
        }
    }
}