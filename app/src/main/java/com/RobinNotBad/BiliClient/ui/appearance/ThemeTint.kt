package com.RobinNotBad.BiliClient.ui.appearance

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat

/**
 * 给「XML 里只能写死颜色」的 drawable 按当前主题着色的小工具。
 *
 * ## 为什么需要它
 * minSdk 19 上 drawable XML 不能用 `?attr/`（见 `docs/architecture-map.md` 的 API 兼容表），
 * 于是点赞 / 投币 / 收藏的激活态、私信发送按钮、动态投票选中框等都只能写死 B站粉，
 * 切到知乎蓝 / 爱奇艺绿时就成了界面上一块突兀的粉。这里在**设置图片的那一刻**按
 * [ColorScheme] 着色。
 *
 * ## 性能
 * 只在调用点（绑定 / 状态切换）做一次 `mutate + setTint`，不遍历视图树、不进绘制路径；
 * `mutate()` 保证不会污染同一资源的其它实例（例如未激活态共用的常量状态）。
 */
object ThemeTint {

    /** 返回着色后的独立副本；[drawable] 为 null 时返回 null。 */
    @JvmStatic
    fun tinted(drawable: Drawable?, @ColorInt color: Int): Drawable? {
        if (drawable == null) return null
        val wrapped = DrawableCompat.wrap(drawable.mutate())
        DrawableCompat.setTint(wrapped, color)
        return wrapped
    }

    /** 读取资源（兼容 API 19 的矢量图）并着色。 */
    @JvmStatic
    fun tinted(context: Context, @DrawableRes resId: Int, @ColorInt color: Int): Drawable? =
        tinted(AppCompatResources.getDrawable(context, resId), color)

    /** 用主题主色着色后设为 [view] 的图片（点赞 / 投币 / 收藏的「已激活」态）。 */
    @JvmStatic
    fun setActiveIcon(view: ImageView, @DrawableRes resId: Int) {
        view.setImageDrawable(tinted(view.context, resId, ColorScheme.PRIMARY))
    }

    /** 激活态走 [setActiveIcon]，未激活态原样设置（未激活图标本就是中性暖白）。 */
    @JvmStatic
    fun setToggleIcon(view: ImageView, active: Boolean, @DrawableRes activeRes: Int, @DrawableRes inactiveRes: Int) {
        if (active) setActiveIcon(view, activeRes) else view.setImageResource(inactiveRes)
    }

    /** 把 [view] 现有背景整体着色为 [color]（保留背景自身的形状与 alpha）。 */
    @JvmStatic
    fun tintBackground(view: View, @ColorInt color: Int) {
        val bg = view.background ?: return
        ViewCompat.setBackground(view, tinted(bg, color))
    }

    /**
     * 「主色描边 + 主色淡底」的选中框（替代写死 #FE679A 的 `bg_vote_option_selected`）。
     * 每次调用新建一个 [GradientDrawable]，只在绑定时用，不在绘制路径上。
     */
    @JvmStatic
    fun outlinedBox(context: Context, @ColorInt color: Int, radiusDp: Float, strokeDp: Float, fillAlpha: Int): Drawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor((color and 0x00FFFFFF) or ((fillAlpha and 0xFF) shl 24))
            setStroke(maxOf(1, (strokeDp * density + 0.5f).toInt()), color)
        }
    }
}
