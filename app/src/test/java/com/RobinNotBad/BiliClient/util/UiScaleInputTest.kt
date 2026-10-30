package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「布局适配」输入框解析的纯逻辑单测。
 *
 * 这块错了的后果很重：旧代码直接 `toFloat()`，线上真崩过两次
 * （`For input string: "界面大小"` / `"确认设置"`），一次是点「确认设置」整页崩。
 */
class UiScaleInputTest {

    // ---- 界面缩放倍数 ----

    @Test
    fun parseDpi_acceptsNumbersInRange() {
        assertEquals(1.0f, UiScaleInput.parseDpi("1.0"))
        assertEquals(1.25f, UiScaleInput.parseDpi("1.25"))
        assertEquals(0.1f, UiScaleInput.parseDpi("0.1"))
        assertEquals(10.0f, UiScaleInput.parseDpi("10"))
    }

    @Test
    fun parseDpi_acceptsSurroundingWhitespace() {
        assertEquals(1.2f, UiScaleInput.parseDpi("  1.2  "))
    }

    @Test
    fun parseDpi_rejectsNonNumericInsteadOfThrowing() {
        // 这两条就是线上真实崩过的两个值：都是页面自己的文案，疑似被粘贴进输入框
        assertNull("标签文案", UiScaleInput.parseDpi("界面大小"))
        assertNull("按钮文案", UiScaleInput.parseDpi("确认设置"))
        assertNull("字母", UiScaleInput.parseDpi("abc"))
        assertNull("混合", UiScaleInput.parseDpi("1.0x"))
    }

    @Test
    fun parseDpi_rejectsEmpty() {
        assertNull(UiScaleInput.parseDpi(""))
        assertNull(UiScaleInput.parseDpi("   "))
        assertNull("null 不能抛异常", UiScaleInput.parseDpi(null))
    }

    @Test
    fun parseDpi_rejectsOutOfRange() {
        // 与旧代码的口径一致：0.1~10 之外不写盘
        assertNull(UiScaleInput.parseDpi("0.05"))
        assertNull(UiScaleInput.parseDpi("10.1"))
        assertNull(UiScaleInput.parseDpi("-1"))
        assertNull(UiScaleInput.parseDpi("100"))
    }

    // ---- 边距百分比 ----

    @Test
    fun parsePadding_acceptsNumbersUpToLimit() {
        assertEquals(0, UiScaleInput.parsePadding("0"))
        assertEquals(30, UiScaleInput.parsePadding("30"))
        assertEquals(5, UiScaleInput.parsePadding("  5 "))
    }

    @Test
    fun parsePadding_rejectsOverLimit() {
        assertNull(UiScaleInput.parsePadding("31"))
        assertNull(UiScaleInput.parsePadding("999"))
    }

    @Test
    fun parsePadding_rejectsNonNumeric() {
        assertNull(UiScaleInput.parsePadding("界面大小"))
        assertNull(UiScaleInput.parsePadding("10%"))
        assertNull(UiScaleInput.parsePadding(""))
        assertNull(UiScaleInput.parsePadding(null))
    }

    @Test
    fun parsePadding_keepsOldNegativeBehaviour() {
        // 旧代码只卡上限（`paddingH <= 30`），负数会被放行。这里刻意保持不动：
        // 顺手收紧会改掉既有行为，属于另一件事。
        assertEquals(-1, UiScaleInput.parsePadding("-1"))
    }
}
