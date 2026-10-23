package com.RobinNotBad.BiliClient.util

/**
 * 伪装成计算器时用的四则运算求值（纯 Kotlin，无 Android 依赖，可直接 JVM 单测）。
 *
 * 它是**伪装的一部分**：老师/同学看到的是一个能正常算数的计算器，所以这里必须真的算对，
 * 不能"随便回显点什么"。同时也是退出通道——[evaluate] 之外的暗号判定在 [DisguisePrefs.matchesSecret]。
 *
 * 只支持 `+ - × ÷` 与小数（计算器上的按键就这些），按「先乘除后加减、从左到右」求值，
 * 不支持括号与科学计算。任何非法输入都返回 [ERROR]，绝不抛异常——伪装页崩掉比算错更糟。
 */
object CalculatorEngine {

    /**
     * 出错时的显示文本（除零、表达式残缺等）。
     *
     * **故意让纯函数带上这句中文**：本类由 JVM 单测直接调用，而单测里没有 Context，
     * 取 `R.string.*` 必然空指针（AGENTS.md 的「纯函数不读资源」条款）。
     * 这个常量只被伪装页和单测消费，调用方不要再包一层翻译。
     */
    const val ERROR = "错误"

    private const val OP_ADD = '+'
    private const val OP_SUB = '-'
    private const val OP_MUL = '×'
    private const val OP_DIV = '÷'

    /** 计算器面板上可出现的运算符（`*` `/` 是键盘/测试用的等价写法）。 */
    private fun isOperator(c: Char): Boolean =
        c == OP_ADD || c == OP_SUB || c == OP_MUL || c == OP_DIV || c == '*' || c == '/'

    private fun normalizeOp(c: Char): Char = when (c) {
        '*' -> OP_MUL
        '/' -> OP_DIV
        else -> c
    }

    /**
     * 求值。
     *
     * @param expression 形如 `12+3×4`、`7.5÷2`、`5×-2`（运算符后接负号表示乘/除一个负数）
     * @return 结果的显示文本；整数不带小数点后缀，无法计算时返回 [ERROR]
     */
    @JvmStatic
    fun evaluate(expression: String?): String {
        val text = expression?.trim().orEmpty()
        if (text.isEmpty()) return "0"

        val numbers = ArrayList<Double>()
        val operators = ArrayList<Char>()
        var index = 0
        while (index < text.length) {
            val c = text[index]
            val expectNumber = operators.size == numbers.size
            if (isOperator(c)) {
                if (expectNumber) {
                    // 期望数字的位置出现运算符：只有 +/- 能当正负号（`5×-2` = -10），
                    // 其余视为"连续运算符"，取最后一个（`12+×` → `12×`，末尾再丢掉）
                    if (c == OP_SUB || c == OP_ADD) {
                        index++
                        val (value, next) = scanNumber(text, index) ?: return ERROR
                        if (next == index) return ERROR
                        numbers.add(if (c == OP_SUB) -value else value)
                        index = next
                        continue
                    }
                    if (numbers.isEmpty()) return ERROR
                    operators[operators.size - 1] = normalizeOp(c)
                } else {
                    operators.add(normalizeOp(c))
                }
                index++
                continue
            }
            if (expectNumber || numbers.isEmpty()) {
                val (value, next) = scanNumber(text, index) ?: return ERROR
                if (next == index) return ERROR // 当前位置不是数字
                numbers.add(value)
                index = next
                continue
            }
            return ERROR // 数字后面直接跟数字/未知字符
        }

        if (numbers.isEmpty()) return ERROR
        // 末尾多敲的运算符：没有对应的第二个操作数，直接忽略
        while (operators.size >= numbers.size) operators.removeAt(operators.size - 1)

        // 一遍扫描：乘除就地折叠进当前项，加减把当前项结算进 total
        var total = 0.0
        var sign = 1.0
        var current = numbers[0]
        var opIndex = 0
        for (i in 1 until numbers.size) {
            val operator = operators.getOrNull(opIndex) ?: break
            when (operator) {
                OP_MUL -> current *= numbers[i]
                OP_DIV -> {
                    if (numbers[i] == 0.0) return ERROR
                    current /= numbers[i]
                }

                OP_SUB -> {
                    total += sign * current
                    sign = -1.0
                    current = numbers[i]
                }

                else -> {
                    total += sign * current
                    sign = 1.0
                    current = numbers[i]
                }
            }
            opIndex++
        }
        total += sign * current
        return format(total)
    }

    /**
     * 从 [from] 开始读一个数字（整数或小数）。
     *
     * @return 数值与下一个位置；当前位置不是数字时返回 `null`。只有一个点、且点前至少一位数字才算合法。
     */
    private fun scanNumber(text: String, from: Int): Pair<Double, Int>? {
        var index = from
        var dots = 0
        while (index < text.length && (text[index].isDigit() || text[index] == '.')) {
            if (text[index] == '.') dots++
            index++
        }
        if (index == from || dots > 1) return null
        val value = text.substring(from, index).toDoubleOrNull() ?: return null
        return value to index
    }

    /** 结果格式化：整数不显示 `.0`，小数最多保留 8 位并去掉尾随 0。 */
    @JvmStatic
    fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return ERROR
        if (value == Math.floor(value) && Math.abs(value) < 1e15) {
            return value.toLong().toString()
        }
        // 必须指定 Locale：默认 Locale 会把小数点格式化成逗号（如 de-DE 的 0,5），
        // 那串再被回填进表达式就解析不回来了
        val text = String.format(java.util.Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
        return if (text.isEmpty() || text == "-") ERROR else text
    }
}
