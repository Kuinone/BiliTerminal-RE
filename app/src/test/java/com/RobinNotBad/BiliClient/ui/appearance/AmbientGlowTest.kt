package com.RobinNotBad.BiliClient.ui.appearance

import com.RobinNotBad.BiliClient.util.FakeSharedPreferences
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * [AmbientGlow]（外观模块四：弥散光晕）的纯 JVM 单测。
 *
 * 钉住几件**错了不会报错、只会画得不对**的事：
 * 1. 每套主题的顶部底色必须不透明——drawable 声明了 `OPAQUE`，系统据此跳过下层绘制，
 *    底色带 alpha 就会露出未定义的内容；
 * 2. 光晕几何参数都在合理范围内（圆心在屏内、半径为正）；
 * 3. 径向色标与 alpha 系数一一对应、首尾为 1 / 0（否则光晕边缘出现硬边）；
 * 4. 亮色 `top` 与亮色背景的色差没有漂出注释给的区间——改了米白底色却忘了重算 `top` 时这里会红。
 */
class AmbientGlowTest {

    private val fakePrefs = FakeSharedPreferences()

    private val allThemeKeys = listOf(
        ColorScheme.THEME_BILIBILI_PINK,
        ColorScheme.THEME_ZHIHU_BLUE,
        ColorScheme.THEME_IQIYI_GREEN,
        ColorScheme.THEME_PURPLE_FANTASY,
        ColorScheme.THEME_RAINBOW_FANTASY,
        ColorScheme.THEME_CLASSIC_GRAY,
        ColorScheme.THEME_CLASSIC_TERMINAL
    )

    @Before
    fun setUp() {
        SharedPreferencesUtil.sharedPreferences = fakePrefs
        ColorScheme.invalidateCache()
    }

    @After
    fun tearDown() {
        ColorScheme.invalidateCache()
        SharedPreferencesUtil.sharedPreferences = null
    }

    // ==================== 方案表 ====================

    @Test
    fun everySpec_topIsOpaque() {
        for (key in allThemeKeys) for (light in listOf(false, true)) {
            val spec = AmbientGlow.specFor(key, light)
            assertEquals("$key light=$light 的 top 必须不透明（drawable 声明了 OPAQUE）", 0xFF, spec.top ushr 24)
        }
    }

    @Test
    fun everySpec_glowGeometryIsSane() {
        for (key in allThemeKeys) for (light in listOf(false, true)) {
            val spec = AmbientGlow.specFor(key, light)
            assertTrue("$key light=$light 至少要有一团光晕", spec.glows.isNotEmpty())
            assertTrue("$key light=$light fadeEnd 越界", spec.fadeEnd > 0f && spec.fadeEnd <= 1f)
            for (g in spec.glows) {
                assertTrue("$key 光晕中心不能全透明", (g.color ushr 24) > 0)
                assertTrue("$key 光晕圆心 cx 越界", g.cx in 0f..1f)
                assertTrue("$key 光晕圆心 cy 越界", g.cy in 0f..1f)
                assertTrue("$key 光晕半径越界", g.radius > 0f && g.radius <= 1f)
            }
        }
    }

    @Test
    fun unknownThemeKey_fallsBackToBilibiliPink() {
        // 与 ColorScheme.darkTableOf 的兜底保持一致，否则未知 key 下光晕色和底色会对不上
        for (light in listOf(false, true)) {
            val pink = AmbientGlow.specFor(ColorScheme.THEME_BILIBILI_PINK, light)
            val unknown = AmbientGlow.specFor("theme_removed_long_ago", light)
            assertEquals(pink.top, unknown.top)
            assertEquals(pink.glows.size, unknown.glows.size)
            assertEquals(pink.glows[0].color, unknown.glows[0].color)
        }
    }

    @Test
    fun lightSpecs_useLightFadeEnd_darkSpecsUseDefault() {
        for (key in allThemeKeys) {
            assertEquals(AmbientGlow.LIGHT_FADE_END, AmbientGlow.specFor(key, true).fadeEnd)
            assertEquals(AmbientGlow.FADE_END, AmbientGlow.specFor(key, false).fadeEnd)
        }
    }

    // ==================== 色标 ====================

    @Test
    fun glowStops_areParallelAndMonotonic() {
        val stops = AmbientGlow.GLOW_STOPS
        val alphas = AmbientGlow.GLOW_ALPHAS
        assertEquals("色标位置与 alpha 系数必须一一对应", stops.size, alphas.size)
        assertEquals(0f, stops.first())
        assertEquals(1f, stops.last())
        assertEquals("中心是实心", 1f, alphas.first())
        assertEquals("边缘必须完全透明，否则圆周出现硬边", 0f, alphas.last())
        for (i in 1 until stops.size) {
            assertTrue("色标位置必须递增", stops[i] > stops[i - 1])
            assertTrue("alpha 必须递减", alphas[i] < alphas[i - 1])
        }
    }

    @Test
    fun scaleAlpha_keepsRgbAndScalesAlpha() {
        val c = 0xCC4F1B2C.toInt()
        assertEquals(c, AmbientGlow.scaleAlpha(c, 1f))
        assertEquals("alpha=0 时保留 RGB（同色透明，不是透明黑）", 0x004F1B2C, AmbientGlow.scaleAlpha(c, 0f))
        assertEquals(0x664F1B2C, AmbientGlow.scaleAlpha(c, 0.5f))
        assertEquals("系数超过 1 也不能溢出 alpha", 0xFF4F1B2C.toInt(), AmbientGlow.scaleAlpha(c, 2f))
    }

    // ==================== 亮色 top 与米白底色接得上 ====================

    @Test
    fun lightTop_colorDifferenceToLightBackground_staysInRange() {
        SharedPreferencesUtil.putString(ColorMode.KEY, ColorMode.LIGHT)
        for (key in allThemeKeys) {
            SharedPreferencesUtil.putString(ColorScheme.PREF_KEY_THEME, key)
            ColorScheme.invalidateCache()
            val background = ColorScheme.BACKGROUND
            val de = deltaE(AmbientGlow.specFor(key, true).top, background)
            assertTrue(
                "$key 亮色 top 与背景色差 ΔE=$de 超出 [8, 20]——改了米白底色要重算 lightSpecFor 的 top",
                de in 8.0..20.0
            )
        }
    }

    /** CIE76 色差（sRGB → XYZ(D65) → Lab）。 */
    private fun deltaE(a: Int, b: Int): Double {
        val la = lab(a)
        val lb = lab(b)
        return sqrt((la[0] - lb[0]).pow(2) + (la[1] - lb[1]).pow(2) + (la[2] - lb[2]).pow(2))
    }

    private fun lab(color: Int): DoubleArray {
        fun lin(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = lin((color shr 16) and 0xFF)
        val g = lin((color shr 8) and 0xFF)
        val b = lin(color and 0xFF)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }
}
