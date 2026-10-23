package com.RobinNotBad.BiliClient.activity.settings

import android.os.Bundle
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.AnnouncementAdapter
import com.RobinNotBad.BiliClient.api.TerminalApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.R

class AnnouncementsActivity : RefreshListActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setPageName(getString(R.string.pagename_announcementsactivity_001))

        // 本页此前有两个问题：
        // 1. 没有接下拉刷新——RefreshListActivity 在 onCreate 里默认把 swipeRefreshLayout 置为
        //    disabled，只有子类调过 setOnRefreshListener 才会打开，所以这里既刷不了也重试不了；
        // 2. catch 分支没有复位转圈——接口一旦异常，页面会一直转圈，只能退出重进。
        setOnRefreshListener { loadAnnouncements() }
        setOnEmptyRetry { loadAnnouncements() }
        loadAnnouncements()
    }

    private fun loadAnnouncements() {
        setRefreshing(true)
        CenterThreadPool.run {
            // 只跟自建服务器这一个源要公告：上游 api.biliterminal.cn 的公告已改由服务端拉取、
            // 管理员在控制台逐条审核后透传（source=relay），客户端不再直连上游。因此这里既
            // 没有「两路合并」，也没有「部分公告源连不上」的中间状态。
            try {
                val announcements = TerminalApi.getOwnAnnouncementList()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (announcements.isEmpty()) showEmptyView() else hideEmptyView()
                    setAdapter(AnnouncementAdapter(this@AnnouncementsActivity, announcements))
                    // 加载完必须复位：漏了会一直转圈，下拉也不再响应
                    setRefreshing(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setRefreshing(false)
                    MsgUtil.err(e)
                }
            }
        }
    }
}
