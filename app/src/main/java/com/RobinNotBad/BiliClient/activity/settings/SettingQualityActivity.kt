package com.RobinNotBad.BiliClient.activity.settings

import android.os.Bundle
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.QualityChooseAdapter
import com.RobinNotBad.BiliClient.listener.OnItemClickListener
import com.RobinNotBad.BiliClient.player.QualityAccessPolicy
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.AccountStateStore
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.SettingsKeys
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import java.util.LinkedHashMap

/**
 * 起播默认清晰度设置。
 *
 * 这里只列「当前账号选得到」的档位：未登录看不到 1080P 及以上，非大会员看不到 1080P60/4K。
 * 与播放器清晰度面板的锁态同源（[QualityAccessPolicy]），只是设置页直接隐藏——设置项没有
 * 「点一下看看提示」的语境，留着一堆点不动的选项更迷惑。
 */
class SettingQualityActivity : BaseActivity() {
    private lateinit var adapter: QualityChooseAdapter
    private lateinit var recyclerView: RecyclerView

    companion object {
        @JvmField
        val qnMap: LinkedHashMap<String, Int> = LinkedHashMap<String, Int>().apply {
            put("360P", 16)
            put("720P", 64)
            put("1080P", 80)
            put("1080P60", 116)
            put("4K", 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        asyncInflate(R.layout.activity_simple_list) { _, _ ->
            recyclerView = findViewById(R.id.recyclerView)
            findViewById<android.view.View>(R.id.top).setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }

            setPageName(getString(R.string.pagename_settingqualityactivity_001))

            adapter = QualityChooseAdapter(this)
            adapter.onItemClickListener = OnItemClickListener { save(it) }

            recyclerView.layoutManager = CustomLinearManager(this)
            recyclerView.adapter = adapter

            // 先用缓存渲染，避免进设置要等一次网络；后台查到真实状态再刷新一次列表
            renderList(AccountStateStore.cached())
            CenterThreadPool.run {
                val state = AccountStateStore.refresh()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) renderList(state)
                }
            }
        }
    }

    /** 只列出账号权限内的档位（见 [QualityAccessPolicy.maxUnlockedQn]）。 */
    private fun renderList(state: AccountStateStore.State) {
        val maxQn = QualityAccessPolicy.maxUnlockedQn(state.loggedIn, state.vip)
        val names = ArrayList<String>()
        for ((name, qn) in qnMap) {
            if (qn <= maxQn) names.add(name)
        }
        adapter.nameList = names
    }

    private fun save(position: Int) {
        val str = adapter.getName(position)
        if (qnMap.containsKey(str))
            SharedPreferencesUtil.putInt(SettingsKeys.PLAY_QN, qnMap[str]!!)
        finish()
    }
}
