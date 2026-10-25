package com.RobinNotBad.BiliClient.activity.settings

import android.annotation.SuppressLint
import android.os.Bundle
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity

/**
 * 「设置」里的 TO DO 清单页。
 *
 * 基类必须是 [BaseActivity] 而不是 `InstanceActivity`：后者是**菜单一级页**的标记
 * （顶栏图标朝下、点它打开菜单切换页面）；本页是从「设置」进来的**子栏目**，
 * 顶栏应当朝左、点它返回上一级。（26.10.25 用户明确规则）
 */
class TodoListActivity : BaseActivity() {

    @SuppressLint("MissingInflatedId", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        asyncInflate(R.layout.activity_todo_list) { _, _ ->
            findViewById<android.view.View>(R.id.scrollView).requestFocus()
        }
    }
}