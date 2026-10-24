package com.RobinNotBad.BiliClient.activity.video.info

import com.RobinNotBad.BiliClient.util.UiText

import android.content.Intent
import android.os.Bundle
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.favorite.FolderChooseAdapter
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

class AddFavoriteActivity : RefreshListActivity() {
    private var adapter: FolderChooseAdapter? = null
    private val folderList = ArrayList<String>()
    private val stateList = ArrayList<Boolean>()
    private val fidList = ArrayList<Long>()
    /** 与服务端 list 一一对应的 media_id：v3 写操作只认它 */
    private val mediaIdList = ArrayList<Long>()
    private val countList = ArrayList<Int>()
    private val maxCountList = ArrayList<Int>()
    private var aid: Long = 0
    private val RESULT_ADDED = 1
    private val RESULT_DELETED = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setPageName(UiText.of("添加收藏"))

        val intent = intent
        aid = intent.getLongExtra("aid", 0)

        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0L) {
            MsgUtil.showMsg(UiText.of("还没有登录喵~"))
            finish()
            return
        }

        CenterThreadPool.run {
            try {
                FavoriteApi.getFavoriteState(aid, folderList, fidList, mediaIdList, stateList, countList, maxCountList)

                adapter = FolderChooseAdapter(this, folderList, mediaIdList, stateList, countList, maxCountList, aid)

                setAdapter(adapter!!)

                setRefreshing(false)
            } catch (e: Exception) {
                loadFail(e)
            }
        }
    }

    override fun finish() {
        if (adapter != null) {
            if (adapter!!.added) {
                setResult(RESULT_ADDED)
            } else if (adapter!!.isAllDeleted()) {
                setResult(RESULT_DELETED)
            }
        }
        super.finish()
    }

    override fun onDestroy() {
        if (adapter != null) {
            if (SharedPreferencesUtil.getBoolean("fav_notice", true)) {
                if (adapter!!.added) MsgUtil.showMsg(UiText.of("添加成功"))
                else if (adapter!!.isAllDeleted()) MsgUtil.showMsg(UiText.of("删除成功"))
                else if (adapter!!.changed) MsgUtil.showMsg(UiText.of("更改成功"))
            }
        }

        super.onDestroy()
    }
}