package com.RobinNotBad.BiliClient.activity.search

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import com.RobinNotBad.BiliClient.adapter.article.ArticleCardAdapter
import com.RobinNotBad.BiliClient.api.SearchApi
import com.RobinNotBad.BiliClient.model.ArticleCard
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import org.json.JSONArray

class SearchArticleFragment : SearchFragment() {

    private var articleCardList: ArrayList<ArticleCard> = ArrayList()
    private var articleCardAdapter: ArticleCardAdapter? = null

    companion object {
        fun newInstance(): SearchArticleFragment {
            return SearchArticleFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun sortOptions(): List<SearchSortOption> = SearchSortOptions.ARTICLE

    override fun onSortChanged(order: String) {
        refreshInternal()
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        articleCardList = ArrayList()
        articleCardAdapter = ArticleCardAdapter(requireContext(), articleCardList)
        setAdapter(articleCardAdapter!!)

        setOnRefreshListener { refreshInternal() }
        setOnLoadMoreListener { continueLoading(it) }
    }

    private fun continueLoading(page: Int) {
        CenterThreadPool.run {
            // 同 SearchVideoFragment：抓代次 + 关键词 + 排序，落地前再验一次
            val genAtRequest = loadGeneration
            val keywordAtRequest = keyword
            val orderAtRequest = sortOrder
            fun stale() = genAtRequest != loadGeneration ||
                    keywordAtRequest != keyword || orderAtRequest != sortOrder
            try {
                val result = SearchApi.searchType(keyword, page, "article", orderAtRequest) as JSONArray?
                if (stale()) return@run // 已切换排序 / 关键词，这批结果作废
                markSortBlocked(false)
                if (result != null) {
                    if (page == 1) showEmptyView(false)
                    val list = ArrayList<ArticleCard>()
                    SearchApi.getArticlesFromSearchResult(result, list)
                    if (list.size == 0) bottom = true
                    CenterThreadPool.runOnUiThread {
                        if (!isAdded || view == null) return@runOnUiThread
                        if (stale()) return@runOnUiThread
                        // 26.10.04 批次 3（B5）：起点 lastSize + 1 → lastSize（原写法第 2 页越界）
                        val lastSize = articleCardList.size
                        articleCardList.addAll(list)
                        articleCardAdapter!!.notifyItemRangeInserted(lastSize, list.size)
                    }
                } else bottom = true
            } catch (e: Exception) {
                // 旧请求的失败同样不该动状态（见 SearchVideoFragment 同处注释）
                if (stale()) return@run
                if (e is SearchApi.RiskControlException) markSortBlocked(true)
                report(e)
            }
            setRefreshing(false)
        }
    }

    override fun refreshInternal() {
        CenterThreadPool.runOnUiThread {
            if (!isAdded || view == null) return@runOnUiThread
            page = 1
            if (this.articleCardAdapter == null)
                this.articleCardAdapter = ArticleCardAdapter(this.requireContext(), this.articleCardList)
            val size_old = this.articleCardList.size
            this.articleCardList.clear()
            if (size_old != 0) this.articleCardAdapter!!.notifyItemRangeRemoved(0, size_old)
            CenterThreadPool.run { continueLoading(page) }
        }
    }
}