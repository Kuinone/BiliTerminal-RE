package com.RobinNotBad.BiliClient.ui.widget

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.view.ViewConfigurationCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.util.SettingsKeys
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import kotlin.math.roundToInt

/**
 * 表冠（旋转编码器）滚动的公共实现。
 *
 * 合并自原先三份近乎逐行的实现：[RotaryRecyclerView]、[RotaryScrollView]、[RotaryNestedScrollView]。
 * 三者只有「读哪个灵敏度设置键」与「滚动后是否抢焦点」两处差异，但基类分别是
 * `RecyclerView` / `ScrollView` / `NestedScrollView`，无法用共同父类归并，
 * 因此这里用**组合**：控件各自保留自己的事件接入方式（监听器 / dispatch 覆写），
 * 把「判定 + 换轴值 + 滚动」这段真正重复的逻辑收进来。
 *
 * 两类入口：
 * 1. 控件级——[handle]，由三个 `Rotary*` 控件自己的监听器调用（只在该控件拿到事件时触发）；
 * 2. 页面级兜底——[handlePage]，由 `BaseActivity` 挂在 **DecorView** 的 generic motion 监听器上。
 *
 * **为什么必须有页面级兜底**：旋转编码器事件只沿焦点路径分发，页面上没有任何控件获焦时，
 * 事件会一路退到 DecorView 而没人消费。列表页的 `RecyclerView` 默认不抢焦点，于是
 * 「设置页里能用、退出就失灵」——设置页能用只是因为点过输入框，`EditText` 拿了焦点，
 * 事件恰好沿焦点链经过外层的 `RotaryRecyclerView`。DecorView 监听器只在**所有子 View
 * 都没处理**时才会被调用，所以不会和控件级监听器重复滚动。
 *
 * **26.10.17 起开关与灵敏度改成「每次事件现读」**（原先只在 `onAttachedToWindow` 读一次）：
 * 手表上改完设置通常不会重进页面（返回栈里的页面只是不可见，`onAttachedToWindow` 不会再触发），
 * 旧写法等于「改完不生效」。SharedPreferences 的读取是内存 Map，热路径上没有性能问题。
 */
internal object RotaryEncoderSupport {

    /**
     * 灵敏度默认值。
     *
     * 与设置页 `SettingPrefActivity` 里 `input_float` 的 `defaultValue` 必须一致（单一来源）。
     * 1.0 = 系统原始滚动量 ×2（`deltaOf` 里那个 `* 2`），是「不用调手感就对」的经验值；
     * 用户反馈过的可用值也是 1.0（issue：三星表圈只能在设置页内用）。
     */
    const val DEFAULT_MULTIPLE = "1.0"

    /** 表冠滚动总开关（`SettingsKeys.UI_ROTATORY_ENABLE`，且需要 Android 8.0+）。 */
    fun isEnabled(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                SharedPreferencesUtil.getBoolean(SettingsKeys.UI_ROTATORY_ENABLE, false)

    /**
     * 读取某个灵敏度设置键。
     *
     * 总开关关闭时恒返回 0——各控件原本都是 `rotaryEnabled && scrollMultiple > 0` 双条件，
     * 归并成一个「倍数 ≤ 0 即不生效」的判断。用户显式把灵敏度填 0 也就等于关掉该类控件。
     */
    fun multipleOf(key: String): Float =
        if (isEnabled()) SharedPreferencesUtil.getFloat(key, DEFAULT_MULTIPLE.toFloat()) else 0f

    /**
     * 处理一次 generic motion 事件（控件级）。
     *
     * 命中表冠滚动则滚动并返回 `true`；否则返回 `false`，由调用方继续走自己的默认分发。
     *
     * @param multiple    灵敏度倍率，≤ 0 直接不处理
     * @param requestFocus 是否在滚动后抢焦点。`RecyclerView`/`ScrollView` 原本会抢，
     *        `NestedScrollView` 原本不抢——这个差异原样保留，避免改变现有手感。
     * @param smoothScrollBy 竖直平滑滚动动作。`View` 本身没有可访问的 `smoothScrollBy`，
     *        只有各具体子类有，所以由调用方传进来（`{ smoothScrollBy(0, it) }`）。
     */
    fun handle(
        view: View,
        event: MotionEvent,
        multiple: Float,
        requestFocus: Boolean,
        smoothScrollBy: (Int) -> Unit
    ): Boolean {
        if (multiple <= 0f) return false
        if (!isRotaryEvent(event)) return false

        smoothScrollBy((deltaOf(view, event) * multiple).roundToInt())
        if (requestFocus) view.requestFocus()
        return true
    }

    /**
     * 页面级兜底：挂在 DecorView 的 generic motion 监听器上，谁都没处理时才被调用。
     *
     * 找出本页第一个「可见、且当前确实能上下滚」的竖直滚动容器并滚它。
     * 横向容器（如 `ViewPager2` 内部、横向 `RecyclerView`）的 `canScrollVertically` 恒为
     * false，天然被排除，不会把翻页手势抢成竖滚。
     *
     * @return 是否消费了这次事件
     */
    fun handlePage(root: View, event: MotionEvent): Boolean {
        if (!isRotaryEvent(event)) return false
        val target = findScrollTarget(root) ?: return false
        val multiple = if (target is RecyclerView)
            multipleOf(SettingsKeys.UI_ROTATORY_RECYCLER)
        else
            multipleOf(SettingsKeys.UI_ROTATORY_SCROLL)
        if (multiple <= 0f) return false

        val dy = (deltaOf(target, event) * multiple).roundToInt()
        if (dy == 0) return false
        when (target) {
            is RecyclerView -> target.smoothScrollBy(0, dy)
            is NestedScrollView -> target.smoothScrollBy(0, dy)
            is ScrollView -> target.smoothScrollBy(0, dy)
            else -> return false
        }
        return true
    }

    private fun isRotaryEvent(event: MotionEvent): Boolean =
        event.action == MotionEvent.ACTION_SCROLL &&
                (event.source and InputDevice.SOURCE_ROTARY_ENCODER) == InputDevice.SOURCE_ROTARY_ENCODER

    private fun deltaOf(view: View, event: MotionEvent): Float =
        -event.getAxisValue(MotionEvent.AXIS_SCROLL) *
                ViewConfigurationCompat.getScaledVerticalScrollFactor(
                    ViewConfiguration.get(view.context), view.context
                ) * 2

    /** 深度优先找本页第一个「可见且当前确实能上下滚」的竖直滚动容器。 */
    private fun findScrollTarget(view: View): View? {
        if (view.isShown &&
            (view is RecyclerView || view is NestedScrollView || view is ScrollView) &&
            (view.canScrollVertically(1) || view.canScrollVertically(-1))
        ) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findScrollTarget(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
