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

    /** 出错时的显示文本（除零、表达式残缺等）。 */
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
     * @param expression 形如 `12+3×4`、`7.5÷2`；末尾多余的运算符会被忽略（用户正敲到一半按了 `=` 也不会出错）
     * @return 结果的显示文本；整数不带小数点后缀，无法计算时返回 [ERROR]
     */
    @JvmStatic
    fun evaluate(expression: String?): String {
        val text = expression?.trim().orEmpty()
        if (text.isEmpty()) return "0"

        // 词法：数字（含小数点）与运算符
        val numbers = ArrayList<Double>()
        val operators = ArrayList<Char>()
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (isOperator(c)) {
                // 末尾运算符（`12+`）直接忽略；连续运算符（`12++`）取最后一个
                if (numbers.isEmpty()) return ERROR
                if (operators.size == numbers.size) operators[operators.size - 1] = normalizeOp(c)
                else operators.add(normalizeOp(c))
                index++
                continue
            }
            if (c.isDigit() || c == '.') {
                val start = index
                var dots = 0
                while (index < text.length && (text[index].isDigit() || text[index] == '.')) {
                    if (text[index] == '.') dots++
                    index++
                }
                if (dots > 1) return ERROR
                val value = text.substring(start, index).toDoubleOrNull() ?: return ERROR
                numbers.add(value)
                continue
            }
            return ERROR // 未知字符（空白、字母等）
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

    /** 结果格式化：整数不显示 `.0`，小数最多保留 8 位并去掉尾随 0。 */
    @JvmStatic
    fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return ERROR
        if (value == Math.floor(value) && Math.abs(value) < 1e15) {
            return value.toLong().toString()
        }
        val text = String.format("%.8f", value).trimEnd('0').trimEnd('.')
        return if (text.isEmpty() || text == "-") ERROR else text
    }
}
