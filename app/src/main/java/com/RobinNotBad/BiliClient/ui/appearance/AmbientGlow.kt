package com.RobinNotBad.BiliClient.ui.appearance

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.RobinNotBad.BiliClient.util.SettingsKeys
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import kotlin.math.max
import kotlin.math.min

/**
 * 外观模块四：**弥散光晕**（页面背景的顶部光晕 + 上→下渐变底色），可在「外观设置」开关。
 *
 * 每套主题一份 [Spec]：顶部底色 + 1～2 团径向光晕（颜色 / 位置 / 半径各不相同），
 * 下半屏渐变回主题本来的背景色——所以列表下半部分、导航栏与关闭光晕时完全一致。
 *
 * ## 性能（手表优先，改这里前必读）
 * - **没有 blur**：CSS 里的 `filter: blur()` 在手表 GPU 上是整屏多次采样，代价最高的一步。
 *   这里用 4 段色标的径向渐变直接画出「已经模糊过」的柔和衰减，视觉等价、代价是一次普通填充。
 * - **没有噪点层**：噪点要再叠一层整屏纹理；改为 `Paint.isDither = true` 抑制渐变色带。
 * - Shader 只在 [AmbientGlowDrawable.onBoundsChange]（窗口尺寸变化）时创建，
 *   [AmbientGlowDrawable.draw] 里只有 1 次 `drawRect` + 1～2 次 `drawCircle`，无分配。
 * - 光晕只画在自己的圆内（不是整屏），声明 `OPAQUE` 让系统跳过其下方的绘制。
 * - **关闭时零开销**：[ColorScheme.applyWindowTheme] 走原来的纯色背景，本模块一行都不执行。
 *
 * 写入同其它外观模块一样只走 [AppearanceManager.setAmbientGlow]（递增外观版本号）。
 */
object AmbientGlow {

    const val KEY = SettingsKeys.UI_AMBIENT_GLOW

    /** 默认关闭：保持老用户观感不变，也不让低端表默认多付这点填充率。 */
    const val DEFAULT = false

    /** 线性渐变在高度的这个比例处回到主题背景色（对应参考稿的 `bottom-color 50%`）。 */
    const val FADE_END = 0.5f

    /** 亮色模式的渐变终点：浅底上色差更难察觉，顶部晕染多铺一段。 */
    const val LIGHT_FADE_END = 0.62f

    /**
     * 一团光晕。
     * @param color 中心颜色（含 alpha，即参考稿的 `glow-color × glow-opacity`）
     * @param cx 圆心横坐标，占宽度的比例
     * @param cy 圆心纵坐标，占 `min(宽, 高)` 的比例（手表方 / 圆屏宽高相等；手机竖屏保持贴顶）
     * @param radius 半径，占 `min(宽, 高)` 的比例
     */
    class Glow(val color: Int, val cx: Float, val cy: Float, val radius: Float)

    /**
     * 一套主题的光晕方案：[top] 为顶部底色，底部颜色取主题背景色（见 [createDrawable]）；
     * [fadeEnd] 为线性渐变回到背景色的位置（占高度比例）。
     */
    class Spec(val top: Int, val glows: Array<Glow>, val fadeEnd: Float = FADE_END)

    fun isEnabled(): Boolean = SharedPreferencesUtil.getBoolean(KEY, DEFAULT)

    /**
     * 主题 key → 光晕方案（纯函数，供 JVM 单测）。
     *
     * 设计原则：顶部底色 = 背景色向主色偏 15% 左右；光晕色 = 主色压暗到不抢正文的亮度，
     * 对暖白 / 冷白正文保持足够对比度。
     */
    fun specFor(themeKey: String, light: Boolean = false): Spec =
        if (light) lightSpecFor(themeKey) else darkSpecFor(themeKey)

    /**
     * 亮色模式的光晕：浅底上「提亮」没有意义，改成**主色的彩色晕染**（像晨光透过彩色玻璃）。
     *
     * 26.10.26 加强：第一版沿用暗色的强度，实测在浅底上几乎看不出来（浅色区域里同样的色差，
     * 人眼感知更弱，而且晕染色比底色暗、读起来像「发灰」而不是「发光」）。现在按 CIELAB 色差对齐暗色：
     * 光晕中心对页面底色 ΔE ≈ 32～42（暗色 23～45），顶部底色 ΔE ≈ 10～16（暗色 8～24），
     * 半径放大一档、渐变回底色的位置下移到 62%。正文是深色，对比度不受影响。
     */
    private fun lightSpecFor(themeKey: String): Spec = when (themeKey) {
        ColorScheme.THEME_ZHIHU_BLUE -> Spec(
            top = 0xFFCCDDF4.toInt(),
            glows = arrayOf(Glow(0x995AA2F8.toInt(), 0.5f, 0.04f, 0.70f)),
            fadeEnd = LIGHT_FADE_END
        )
        ColorScheme.THEME_IQIYI_GREEN -> Spec(
            top = 0xFFC9E8D5.toInt(),
            glows = arrayOf(Glow(0x8533CC7A.toInt(), 0.5f, 0.03f, 0.66f)),
            fadeEnd = LIGHT_FADE_END
        )
        ColorScheme.THEME_PURPLE_FANTASY -> Spec(
            top = 0xFFDDCBF1.toInt(),
            glows = arrayOf(
                Glow(0x8CAD6BE3.toInt(), 0.5f, 0.05f, 0.72f),
                Glow(0x70C9A0F0, 0.88f, 0.42f, 0.46f)
            ),
            fadeEnd = LIGHT_FADE_END
        )
        ColorScheme.THEME_RAINBOW_FANTASY -> Spec(
            top = 0xFFF6D3C6.toInt(),
            glows = arrayOf(
                Glow(0x99FF8080.toInt(), 0.22f, 0.03f, 0.62f),
                Glow(0x8C45C7BE.toInt(), 0.80f, 0.06f, 0.58f)
            ),
            fadeEnd = LIGHT_FADE_END
        )
        ColorScheme.THEME_CLASSIC_GRAY -> Spec(
            top = 0xFFD3D3EE.toInt(),
            glows = arrayOf(Glow(0x858C8CF5.toInt(), 0.5f, 0.04f, 0.68f)),
            fadeEnd = LIGHT_FADE_END
        )
        ColorScheme.THEME_CLASSIC_TERMINAL -> Spec(
            top = 0xFFEFC8D6.toInt(),
            glows = arrayOf(Glow(0x94FF7AA6.toInt(), 0.5f, 0.04f, 0.68f)),
            fadeEnd = LIGHT_FADE_END
        )
        else -> Spec(
            top = 0xFFF4D0DF.toInt(),
            glows = arrayOf(Glow(0x99FF85AD.toInt(), 0.5f, 0.04f, 0.70f)),
            fadeEnd = LIGHT_FADE_END
        )
    }

    private fun darkSpecFor(themeKey: String): Spec = when (themeKey) {
        // 知乎蓝：深海蓝，居中一团
        ColorScheme.THEME_ZHIHU_BLUE -> Spec(
            top = 0xFF142540.toInt(),
            glows = arrayOf(Glow(0xCC0F3D75.toInt(), 0.5f, 0.04f, 0.62f))
        )
        // 爱奇艺绿：主色很亮，光晕压得更暗、更小，避免整屏发绿
        ColorScheme.THEME_IQIYI_GREEN -> Spec(
            top = 0xFF10291F.toInt(),
            glows = arrayOf(Glow(0xB30D4D30.toInt(), 0.5f, 0.02f, 0.58f))
        )
        // 紫色空灵：顶部主光晕 + 右侧中部一团淡紫余晖，做出「空灵」的层次
        ColorScheme.THEME_PURPLE_FANTASY -> Spec(
            top = 0xFF26173D.toInt(),
            glows = arrayOf(
                Glow(0xCC4A2378.toInt(), 0.5f, 0.05f, 0.66f),
                Glow(0x80331C5C.toInt(), 0.88f, 0.42f, 0.42f)
            )
        )
        // 五彩斑斓：左上珊瑚红 + 右上青绿（取自 rainbow_red / rainbow_green），双色交叠
        ColorScheme.THEME_RAINBOW_FANTASY -> Spec(
            top = 0xFF2B1B22.toInt(),
            glows = arrayOf(
                Glow(0xB85E2C2C.toInt(), 0.22f, 0.03f, 0.55f),
                Glow(0xA0184A4D.toInt(), 0.80f, 0.06f, 0.52f)
            )
        )
        // 经典灰：低饱和的雾紫
        ColorScheme.THEME_CLASSIC_GRAY -> Spec(
            top = 0xFF20203A.toInt(),
            glows = arrayOf(Glow(0xB3363663.toInt(), 0.5f, 0.04f, 0.60f))
        )
        // 经典终端：即参考稿默认参数（#3b151f / #4f1b2c × 0.8），底部回到纯黑
        ColorScheme.THEME_CLASSIC_TERMINAL -> Spec(
            top = 0xFF3B151F.toInt(),
            glows = arrayOf(Glow(0xCC4F1B2C.toInt(), 0.5f, 0.04f, 0.60f))
        )
        // B站粉（及未知 key 的兜底）：玫瑰粉
        else -> Spec(
            top = 0xFF3A1A2B.toInt(),
            glows = arrayOf(Glow(0xCC5C2342.toInt(), 0.5f, 0.04f, 0.62f))
        )
    }

    /** 为当前主题创建背景 drawable。每个 Activity 创建一次（冷路径），不缓存。 */
    fun createDrawable(themeKey: String, background: Int, light: Boolean = false): Drawable =
        AmbientGlowDrawable(specFor(themeKey, light), background)

    /** 保留 RGB、把 alpha 乘以 [factor]。渐变终点用「同色 alpha=0」而不是透明黑，避免边缘发灰。 */
    internal fun scaleAlpha(color: Int, factor: Float): Int {
        val a = ((color ushr 24) * factor + 0.5f).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /**
     * 径向光晕的色标：模拟「实心中心 → 65% 处透明」再经 blur 后的平滑衰减（近似 smoothstep）。
     * 位置与 alpha 系数一一对应。
     */
    internal val GLOW_STOPS = floatArrayOf(0f, 0.3f, 0.62f, 1f)
    internal val GLOW_ALPHAS = floatArrayOf(1f, 0.75f, 0.32f, 0f)

    private class AmbientGlowDrawable(
        private val spec: Spec,
        private val bottom: Int
    ) : Drawable() {

        private val basePaint = Paint().apply { isDither = true }
        private val glowPaints = Array(spec.glows.size) { Paint().apply { isDither = true } }
        /** 每团光晕的 (cx, cy, r)，在 onBoundsChange 里算好，draw 时直接用。 */
        private val geometry = FloatArray(spec.glows.size * 3)
        private var ready = false

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) {
                ready = false
                return
            }
            val left = bounds.left.toFloat()
            val top = bounds.top.toFloat()
            val unit = min(w, h)

            basePaint.shader = LinearGradient(
                0f, top, 0f, top + h * spec.fadeEnd,
                spec.top, bottom, Shader.TileMode.CLAMP
            )
            for (i in spec.glows.indices) {
                val g = spec.glows[i]
                val cx = left + w * g.cx
                val cy = top + unit * g.cy
                val r = max(1f, unit * g.radius)
                val colors = IntArray(GLOW_ALPHAS.size) { scaleAlpha(g.color, GLOW_ALPHAS[it]) }
                glowPaints[i].shader = RadialGradient(cx, cy, r, colors, GLOW_STOPS, Shader.TileMode.CLAMP)
                geometry[i * 3] = cx
                geometry[i * 3 + 1] = cy
                geometry[i * 3 + 2] = r
            }
            ready = true
        }

        override fun draw(canvas: Canvas) {
            if (!ready) {
                canvas.drawColor(bottom)
                return
            }
            canvas.drawRect(bounds, basePaint)
            for (i in glowPaints.indices) {
                canvas.drawCircle(geometry[i * 3], geometry[i * 3 + 1], geometry[i * 3 + 2], glowPaints[i])
            }
        }

        // 窗口背景不做整体透明度 / 滤色，这两个按 Drawable 契约留空即可。
        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
