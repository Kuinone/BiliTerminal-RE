package com.RobinNotBad.BiliClient.activity.disguise

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.util.CalculatorEngine
import com.RobinNotBad.BiliClient.util.DisguisePrefs
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.SettingsKeys
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

/**
 * 伪装成计算器（issue #22，双击顶栏触发，见 `BaseActivity.setupTopbarDoubleTapDisguise`）。
 *
 * <h3>两件事同时成立</h3>
 * 1. **它必须真的是个计算器**：老师/同学走近时看到的、甚至上手指的，都得是正常的加减乘除，
 *    所以求值走 [CalculatorEngine]（纯函数、有单测），不是随便回显；
 * 2. **它是退出伪装唯一的门**：输入配置里的数字暗号（[DisguisePrefs.code]，默认 1234）后按 `=`，
 *    才会 `finish()` 回到原来的页面；暗号不对就照常算给你看。
 *
 * <h3>为什么返回键只当清空</h3>
 * 手表上的返回键太容易顺手按。若它退出伪装，这层伪装就形同不存在（旁观者按一下就看到 B 站）。
 * 所以 `KEYCODE_BACK` 只当作 `C`（清空当前输入）。代价是**忘了暗号只能强杀应用**——
 * 这一点写在设置的说明文案里，改文案时别删。
 *
 * <h3>为什么不用 BaseActivity</h3>
 * 本页要长得完全不像哔哩终端：BaseActivity 会套主题、DPI 适配、圆角、教程触发、EventBus、
 * 顶栏手势……任何一项都可能把「这是 B 站客户端」暴露出来。所以直接用 [AppCompatActivity] +
 * 专用主题 `Theme.BiliClient.Disguise`（在清单里声明）。
 */
class CalculatorDisguiseActivity : AppCompatActivity() {

    private lateinit var display: TextView

    /** 当前输入的表达式（同时就是"暗号"的比对对象）。 */
    private var expression: String = ""

    /** 已经按过 `=` 之后的展示状态；此时再按数字应当开新表达式，而不是接在后面。 */
    private var showingResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disguise_calculator)

        display = findViewById(R.id.disguise_display)
        bindKeypad(findViewById(R.id.disguise_keypad))
        applyRoundScreenPadding(findViewById(R.id.disguise_root))
        render()
    }

    /**
     * 尊重「圆屏适配」的内边距。
     *
     * 本页刻意不继承 `BaseActivity`（那边会把应用主题/教程/EventBus 一并带进来），
     * 但**圆屏留白是设备适配、不是外观**：圆屏手表上不缩进的话，最外圈的按键会被切掉。
     * 所以这里单独读一次设置里的内边距，语义与 `BaseActivity.setRound()` 一致。
     */
    private fun applyRoundScreenPadding(root: View) {
        val padH = SharedPreferencesUtil.getInt(SettingsKeys.PADDING_H, 0)
        val padV = SharedPreferencesUtil.getInt(SettingsKeys.PADDING_V, 0)
        if (padH <= 0 && padV <= 0) return
        val density = resources.displayMetrics.density
        root.setPadding(
            (padH * density).toInt(),
            (padV * density).toInt(),
            (padH * density).toInt(),
            (padV * density).toInt()
        )
    }

    /**
     * 按键分发：每个键的 `android:tag` 就是它的输入字符（见布局注释）。
     *
     * 用 tag 而不是给每个键各写一个 `setOnClickListener`：少一堆样板，加键时只改布局。
     */
    private fun bindKeypad(keypad: ViewGroup) {
        for (index in 0 until keypad.childCount) {
            val row = keypad.getChildAt(index) as? ViewGroup ?: continue
            for (keyIndex in 0 until row.childCount) {
                val key = row.getChildAt(keyIndex)
                val tag = key.tag as? String ?: continue
                key.setOnClickListener { onKey(tag) }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun onKey(key: String) {
        when (key) {
            "C" -> {
                expression = ""
                showingResult = false
            }

            "⌫" -> {
                if (showingResult) {
                    expression = ""
                    showingResult = false
                } else if (expression.isNotEmpty()) {
                    expression = expression.substring(0, expression.length - 1)
                }
            }

            "=" -> {
                // 暗号判定放在求值之前：暗号本身就是纯数字，先算的话等于把它原样显示在屏幕上
                if (DisguisePrefs.matchesSecret(expression, DisguisePrefs.code())) {
                    Logu.d("disguise", "暗号命中，退出伪装")
                    finish()
                    return
                }
                expression = CalculatorEngine.evaluate(expression)
                showingResult = true
            }

            "+", "-", "×", "÷" -> {
                if (showingResult) {
                    // 用上次结果继续算（计算器习惯）；此时展示的已经是数字
                    showingResult = false
                }
                if (expression.isEmpty()) return // 以运算符开头没有意义
                if (expression.last() in "+-×÷") expression = expression.dropLast(1)
                expression += key
            }

            "." -> {
                if (showingResult) {
                    expression = ""
                    showingResult = false
                }
                val current = expression.takeLastWhile { it.isDigit() || it == '.' }
                if (current.contains('.')) return // 一个数里只能有一个小数点
                expression += if (current.isEmpty()) "0." else "."
            }

            else -> {
                if (showingResult) {
                    expression = ""
                    showingResult = false
                }
                expression += key
            }
        }
        render()
    }

    private fun render() {
        display.text = expression.ifEmpty { "0" }
    }

    /** 返回键当作 `C`：**绝不能**让它退出本页（见类注释）。 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        expression = ""
        showingResult = false
        render()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            onBackPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 触摸事件不再需要额外处理：按键是普通 View，系统自己分发给它们的 OnClickListener。 */
    override fun onTouchEvent(event: MotionEvent): Boolean = super.onTouchEvent(event)
}
