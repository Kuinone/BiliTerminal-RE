package com.RobinNotBad.BiliClient.ui.appearance

import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.RobinNotBad.BiliClient.util.SettingsKeys
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

/**
 * 外观模块六：**暗色 / 亮色模式**（与主题配色正交：7 套主题 × 2 种模式）。
 *
 * ## 机制：资源限定符，而不是第二套主题
 * - `values/`、`drawable/` 下的现有资源 = **暗色**，一字不动；
 * - 亮色只放在 `values-notnight/`、`drawable-notnight/` 里覆盖（颜色、40 个浅色图标、6 个深灰 shape、主题父类）；
 * - 我们自己决定 `Configuration.uiMode` 的夜间位：暗色 → `NIGHT_YES`（命中默认资源），亮色 → `NIGHT_NO`
 *   （命中 `-notnight`）。两处落实：
 *   1. [com.RobinNotBad.BiliClient.BiliTerminal.getFitDisplayContext]：Activity 的 base context 与全局 `BiliTerminal.context`；
 *   2. [applyGlobal] → `AppCompatDelegate.setDefaultNightMode`：AppCompat 在 `attachBaseContext2` 里会按
 *      「跟随系统」重写夜间位，必须显式告诉它，否则会被改回系统值。
 *
 * ## 为什么暗色是 NIGHT_YES 而不是「不设」
 * 手表系统普遍报告 `NIGHT_NO`（Wear OS 实测 `uiMode=0x16`）。如果暗色模式不显式设成 NIGHT_YES，
 * 暗色用户在手表上就会命中 `-notnight`，整个应用变成亮色。
 *
 * ## 永远暗色的地方
 * 播放器（`PlayerActivity`）与闪屏用 `forceDark = true` 取上下文，始终命中暗色资源；
 * 压在视频 / 图片上的图标与文字改用不参与覆盖的 `overlay_*` 资源。
 * 冷启动窗口由系统按**系统** uiMode 绘制，所以闪屏底色 `terminal_bgblack` 刻意不进亮色覆盖。
 */
object ColorMode {

    const val KEY = SettingsKeys.UI_COLOR_MODE
    const val DARK = "dark"
    const val LIGHT = "light"

    /** 默认暗色：老用户升级后观感不变。 */
    const val DEFAULT = DARK

    val VALUES = listOf(DARK, LIGHT)
    val DISPLAY_NAMES = listOf("暗色", "亮色")

    /** 非法值一律回落暗色（纯函数，供 JVM 单测）。 */
    fun normalize(value: String?): String = if (value == LIGHT) LIGHT else DARK

    fun current(): String = normalize(SharedPreferencesUtil.getString(KEY, DEFAULT))

    fun isLight(): Boolean = current() == LIGHT

    /** 应写进 `Configuration.uiMode` 的夜间位。[forceDark] 用于播放器 / 闪屏等永远暗色的页面。 */
    fun nightBits(forceDark: Boolean = false): Int =
        if (!forceDark && isLight()) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES

    /**
     * 同步 AppCompat 的进程级夜间模式。应用启动时调一次；切换模式时调用会让所有 AppCompat 页面自动重建。
     * 不放进 [AppearanceManager]：那里要能在纯 JVM 单测里跑，不能碰 Android 类。
     */
    fun applyGlobal() {
        AppCompatDelegate.setDefaultNightMode(
            if (isLight()) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
    }
}
