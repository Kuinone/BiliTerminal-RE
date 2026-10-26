package com.RobinNotBad.BiliClient.ui.appearance

import android.app.Activity
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.util.SettingsKeys
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

/**
 * 外观模块五：**玻璃质感组件**（卡片 / 按钮半透明化，让弥散光晕透进来）。
 *
 * 与 [AmbientGlow] 是两个独立开关：单开本模块时卡片 / 按钮在原主题纯色底上同样成立
 * （只是更通透、边缘带一道主题高亮色）；两者同开效果最好，光晕设置项的说明里会推荐。
 *
 * ## 机制（零运行时开销）
 * 每套主题一份 `GlassOverlay.<X>`（`res/values/themes_glass.xml`），只替换
 * `materialCardViewStyle` / `materialButtonStyle` 两个**具体样式引用**；
 * [applyTo] 在 `BaseActivity.onCreate` 里（`super.onCreate` 之后、子类 `setContentView` 之前）
 * 对 Activity 主题 `applyStyle(force = true)` 一次。不遍历视图树、不在列表绑定路径上做任何事。
 * 关闭时 [applyTo] 直接 return。
 *
 * > 注意与 §8.7.1 圆角走过的弯路区分：那次失败的是「维度属性的 `?attr/` 间接层」；
 * > 这里覆盖层里写的都是 `@style/` / `@color/` 具体值，没有 `?attr/` 间接层。
 *
 * ## 可读性（手表字小）
 * 卡片保持约 78% 不透明，光晕只是「隐约透出」；卡片与按钮 elevation 一律 0——
 * 半透明底下的阴影会透出来，看起来像脏边。
 */
object GlassStyle {

    const val KEY = SettingsKeys.UI_GLASS_COMPONENTS

    /** 默认关闭：不改老用户观感。 */
    const val DEFAULT = false

    fun isEnabled(): Boolean = SharedPreferencesUtil.getBoolean(KEY, DEFAULT)

    /** 主题 key → 玻璃覆盖层（纯函数，供 JVM 单测）。未知 key 与 [ColorScheme.themeResId] 一样回落到 B站粉。 */
    fun overlayResId(themeKey: String): Int = when (themeKey) {
        ColorScheme.THEME_ZHIHU_BLUE -> R.style.GlassOverlay_Zhihu
        ColorScheme.THEME_IQIYI_GREEN -> R.style.GlassOverlay_IQIYI
        ColorScheme.THEME_PURPLE_FANTASY -> R.style.GlassOverlay_PurpleFantasy
        ColorScheme.THEME_RAINBOW_FANTASY -> R.style.GlassOverlay_Rainbow
        ColorScheme.THEME_CLASSIC_GRAY -> R.style.GlassOverlay_ClassicGray
        ColorScheme.THEME_CLASSIC_TERMINAL -> R.style.GlassOverlay_Terminal
        else -> R.style.GlassOverlay_Pink
    }

    /** 开启时把当前主题的玻璃覆盖层叠到 [activity] 的主题上。必须在 `setContentView` 之前调用。 */
    fun applyTo(activity: Activity) {
        if (!isEnabled()) return
        activity.theme.applyStyle(overlayResId(ColorScheme.getCurrentThemeName()), true)
    }
}
