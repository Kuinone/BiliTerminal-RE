package com.RobinNotBad.BiliClient.activity.search

import androidx.annotation.StringRes
import com.RobinNotBad.BiliClient.R

/**
 * 搜索结果的一个排序档位。
 *
 * [order] 直接就是接口参数值，见 `bilibili-API/docs/search/search_request.md`「分类搜索」一节。
 * 只有视频与专栏有排序（用户要 `fans`/`level`、直播要 `online`/`live_time`，是另一套键，本功能不做）。
 */
data class SearchSortOption(@StringRes val labelRes: Int, val order: String)

/**
 * 视频 / 专栏搜索的排序档位表。
 *
 * <h3>为什么只有这四项</h3>
 * 接口还支持 `dm`（最多弹幕）、`scores`（最多评论），但手表屏宽只放得下 4 格，
 * 且这四项覆盖了绝大多数使用场景。**加档位必须先确认 380px 宽度放得下**——
 * 排序条不能做成横向可滚动：手表右滑返回由窗口层 `windowSwipeToDismiss` 处理，
 * 一横划就退出页面（见 `docs/architecture-map.md` 第 7 节）。
 *
 * <h3>为什么视频和专栏各一份</h3>
 * `order` 值完全相同，只有文案不同——`click` 在视频上是播放量、在专栏上是阅读量。
 * 合起来写会让其中一边的文案错位，所以两份表共用同一批 [SearchSortOption] 实例，只换 click 那一个。
 */
object SearchSortOptions {

    /** 综合排序。视频分类选它时走聚合搜索（`all/v2`），其余档位走分类搜索（`search/type`）。 */
    const val TOTALRANK = "totalrank"

    /** 排序条固定 4 格，见 `fragment_simple_refresh.xml` 的 `sortOption1..4`。 */
    const val SLOT_COUNT = 4

    private val TOTALRANK_OPTION = SearchSortOption(R.string.text_search_sort_totalrank, TOTALRANK)
    private val PUBDATE_OPTION = SearchSortOption(R.string.text_search_sort_pubdate, "pubdate")
    private val STOW_OPTION = SearchSortOption(R.string.text_search_sort_stow, "stow")

    val VIDEO: List<SearchSortOption> = listOf(
        TOTALRANK_OPTION,
        SearchSortOption(R.string.text_search_sort_click_video, "click"),
        PUBDATE_OPTION,
        STOW_OPTION
    )

    val ARTICLE: List<SearchSortOption> = listOf(
        TOTALRANK_OPTION,
        SearchSortOption(R.string.text_search_sort_click_article, "click"),
        PUBDATE_OPTION,
        STOW_OPTION
    )
}
