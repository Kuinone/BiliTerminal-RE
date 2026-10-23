package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CalculatorEngine] 的纯 JVM 单测。
 *
 * 这是伪装页唯一的"真功能"：算错就被一眼看穿是假应用，所以每条规则都钉住。
 */
class CalculatorEngineTest {

    @Test
    fun `空表达式显示 0`() {
        assertEquals("0", CalculatorEngine.evaluate(""))
        assertEquals("0", CalculatorEngine.evaluate(null))
        assertEquals("0", CalculatorEngine.evaluate("   "))
    }

    @Test
    fun `单个数字原样返回`() {
        assertEquals("1234", CalculatorEngine.evaluate("1234"))
    }

    @Test
    fun `加减从左到右`() {
        assertEquals("3", CalculatorEngine.evaluate("1+2"))
        assertEquals("9", CalculatorEngine.evaluate("10-1"))
        assertEquals("6", CalculatorEngine.evaluate("1+2+3"))
        assertEquals("4", CalculatorEngine.evaluate("10-1-5"))
    }

    @Test
    fun `乘除优先于加减`() {
        assertEquals("14", CalculatorEngine.evaluate("2+3×4"))
        assertEquals("6", CalculatorEngine.evaluate("10-8÷2"))
        assertEquals("5", CalculatorEngine.evaluate("2×3-1"))
        assertEquals("2", CalculatorEngine.evaluate("8÷2÷2"))
    }

    @Test
    fun `小数按计算器习惯显示`() {
        assertEquals("0.5", CalculatorEngine.evaluate("1÷2"))
        assertEquals("1.5", CalculatorEngine.evaluate("3÷2"))
        assertEquals("2.25", CalculatorEngine.evaluate("1.5×1.5"))
        assertEquals("0.1", CalculatorEngine.evaluate("0.3-0.2"))
    }

    @Test
    fun `整除不带小数点后缀`() {
        assertEquals("4", CalculatorEngine.evaluate("8÷2"))
    }

    @Test
    fun `末尾多余运算符被忽略`() {
        // 用户正敲到一半按了 = 不该炸
        assertEquals("12", CalculatorEngine.evaluate("12+"))
        assertEquals("12", CalculatorEngine.evaluate("12+×"))
    }

    @Test
    fun `连续运算符取最后一个`() {
        assertEquals("8", CalculatorEngine.evaluate("5++3"))
        assertEquals("2", CalculatorEngine.evaluate("5+-3"))
        // 乘除后面接负号是「乘除一个负数」，不是替换运算符
        assertEquals("-10", CalculatorEngine.evaluate("5×-2"))
        assertEquals("-3", CalculatorEngine.evaluate("6÷-2"))
    }

    @Test
    fun `减负数等于加`() {
        assertEquals("8", CalculatorEngine.evaluate("5--3"))
    }

    @Test
    fun `除零返回错误`() {
        assertEquals(CalculatorEngine.ERROR, CalculatorEngine.evaluate("1÷0"))
        assertEquals(CalculatorEngine.ERROR, CalculatorEngine.evaluate("0÷0"))
    }

    @Test
    fun `负数结果可以继续算`() {
        // 结果态会在表达式里留下 `-3`，接着按运算符/再按 = 都不能炸（伪装页显示"错误"就露馅了）
        assertEquals("-3", CalculatorEngine.evaluate("3-6"))
        assertEquals("-1", CalculatorEngine.evaluate("-3+2"))
        assertEquals("-6", CalculatorEngine.evaluate("-3×2"))
        assertEquals("-6", CalculatorEngine.evaluate("0-3-3"))
        assertEquals("-3", CalculatorEngine.evaluate("-3"))
        assertEquals("-1.5", CalculatorEngine.evaluate("-3÷2"))
    }

    @Test
    fun `无法识别的内容返回错误`() {
        assertEquals(CalculatorEngine.ERROR, CalculatorEngine.evaluate("abc"))
        assertEquals(CalculatorEngine.ERROR, CalculatorEngine.evaluate("1.2.3"))
        assertEquals(CalculatorEngine.ERROR, CalculatorEngine.evaluate("-"))
        assertEquals(CalculatorEngine.ERROR, CalculatorEngine.evaluate("-x"))
        // 前置正号按正号处理（等价于 5）；前置负号见「负数结果可以继续算」
        assertEquals("5", CalculatorEngine.evaluate("+5"))
    }

    @Test
    fun `星号斜杠等价于乘除`() {
        assertEquals("6", CalculatorEngine.evaluate("2*3"))
        assertEquals("2", CalculatorEngine.evaluate("6/3"))
    }

    @Test
    fun `负数结果正常显示`() {
        assertEquals("-3", CalculatorEngine.evaluate("3-6"))
    }
}

/**
 * [DisguisePrefs] 纯函数部分的单测（不碰 SharedPreferences）。
 */
class DisguisePrefsTest {

    @Test
    fun `合法暗号原样返回`() {
        assertEquals("1234", DisguisePrefs.normalizeCode("1234"))
        assertEquals("12345678", DisguisePrefs.normalizeCode("12345678"))
    }

    @Test
    fun `前后空格被裁掉`() {
        assertEquals("5678", DisguisePrefs.normalizeCode("  5678  "))
    }

    @Test
    fun `长度不在范围内的暗号非法`() {
        assertNull(DisguisePrefs.normalizeCode("123"))
        assertNull(DisguisePrefs.normalizeCode("123456789"))
        assertNull(DisguisePrefs.normalizeCode(""))
        assertNull(DisguisePrefs.normalizeCode(null))
    }

    @Test
    fun `非数字暗号非法`() {
        // 计算器上敲不出字母，配了就永远退不出伪装页
        assertNull(DisguisePrefs.normalizeCode("12a4"))
        assertNull(DisguisePrefs.normalizeCode("12 34"))
        assertNull(DisguisePrefs.normalizeCode("12.34"))
    }

    @Test
    fun `暗号匹配`() {
        assertTrue(DisguisePrefs.matchesSecret("1234", "1234"))
        assertTrue(DisguisePrefs.matchesSecret(" 1234 ", "1234"))
        assertFalse(DisguisePrefs.matchesSecret("12345", "1234"))
        assertFalse(DisguisePrefs.matchesSecret("123", "1234"))
        assertFalse(DisguisePrefs.matchesSecret(null, "1234"))
    }

    @Test
    fun `非法或空暗号永不匹配`() {
        // 否则「暗号被手改成空」时会一按 = 就退出伪装
        assertFalse(DisguisePrefs.matchesSecret("", ""))
        assertFalse(DisguisePrefs.matchesSecret("1234", ""))
        assertFalse(DisguisePrefs.matchesSecret("12a4", "12a4"))
    }

    @Test
    fun `样式合法性判定`() {
        assertTrue(DisguisePrefs.isValidStyle(DisguisePrefs.STYLE_CALCULATOR))
        assertTrue(DisguisePrefs.isValidStyle(DisguisePrefs.STYLE_BLUETOOTH))
        assertFalse(DisguisePrefs.isValidStyle(""))
        assertFalse(DisguisePrefs.isValidStyle(null))
        assertFalse(DisguisePrefs.isValidStyle("other"))
    }
}
