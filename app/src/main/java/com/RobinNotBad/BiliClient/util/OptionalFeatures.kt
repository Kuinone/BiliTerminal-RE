package com.RobinNotBad.BiliClient.util

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * 可选私有功能的反射门面。
 *
 * 有一小批功能只存在于私有构建：源码在仓库根目录的 `private/`（已 gitignore，
 * 由 CI 从私有仓库 checkout 进来），公开仓库里只有这个门面与少量条件挂点。
 * 机制说明见 `docs/optional-features.md`。
 *
 * 规则：
 *  - **这里绝不写实现**，只反射转发到 `com.RobinNotBad.BiliClient.feature.FeatureHolder`；
 *  - 所有方法都吞异常：私有代码不在场时返回 null / no-op，公共构建的行为与从前完全一致；
 *  - 于是公开仓库能编、能跑、能发版，私有构建才多出这些功能。
 *
 * 私有侧的 FeatureHolder 用 @JvmStatic 暴露同名静态方法，参数只有框架类型。
 */
object OptionalFeatures {

    private const val HOLDER_CLASS = "com.RobinNotBad.BiliClient.feature.FeatureHolder"

    /** 私有注入是否在场。null = 不在场（公开构建、PR 构建、别人的开发机）。 */
    private val holder: Class<*>? by lazy {
        try {
            Class.forName(HOLDER_CLASS)
        } catch (e: Throwable) {
            null
        }
    }

    val available: Boolean
        get() = holder != null

    /** 菜单项 key，不在场时为 null。 */
    fun menuKey(): String? = invokeString("menuKey")

    /** 菜单项显示名，不在场时为 null。 */
    fun menuLabel(context: Context): String? = try {
        holder?.getMethod("menuLabel", Context::class.java)?.invoke(null, context) as? String
    } catch (e: Throwable) {
        null
    }

    /** 菜单项对应的页面 Class，不在场时为 null。 */
    fun menuActivity(): Class<*>? = try {
        holder?.getMethod("menuActivity")?.invoke(null) as? Class<*>
    } catch (e: Throwable) {
        null
    }

    /**
     * 往视频详情页的动作区插一行按钮（插在 [anchor] 之前）。具体插什么、点了做什么，
     * 全部由私有侧决定；不在场时什么都不做，调用方不用判空。
     */
    fun attachDetailActionRow(
        container: ViewGroup,
        anchor: View,
        videoId: String,
        aid: Long,
        title: String
    ) {
        try {
            holder?.getMethod(
                "attachDetailActionRow",
                ViewGroup::class.java,
                View::class.java,
                String::class.java,
                java.lang.Long.TYPE,
                String::class.java
            )?.invoke(null, container, anchor, videoId, aid, title)
        } catch (e: Throwable) {
            // 私有功能异常绝不能影响详情页正常显示
            Logu.w("OptionalFeatures", "attachDetailActionRow failed: " + e.message)
        }
    }

    private fun invokeString(name: String): String? = try {
        holder?.getMethod(name)?.invoke(null) as? String
    } catch (e: Throwable) {
        null
    }
}
