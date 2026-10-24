package com.RobinNotBad.BiliClient.util

/**
 * 极验（Geetest）gt / challenge 参数的准入规则（安全审计 P2-8）。
 *
 * 这两个值由 B 站接口返回，经 Intent 传进
 * [com.RobinNotBad.BiliClient.activity.settings.login.CaptchaWebViewActivity]，
 * 最终会被拼进 WebView 里执行的 JS。只要允许出现引号、换行或 `</script>`，
 * 拼出来的脚本就能被参数本身改写——参数来自服务端，出问题等于远程可执行代码。
 *
 * 所以这里只放**纯规则**，不引用 android.*，可以在 JVM 单测里直接把各种恶意取值打进来：
 * 官方 gt 是 32 位十六进制（32 字符），challenge 也是等长的十六进制串；
 * 规则放宽到「16~64 位大小写字母 + 数字」，既覆盖官方长度，也留出格式微调余地，
 * 同时天然排除引号、反斜杠、尖括号、空白和任何非 ASCII 字符。
 *
 * 注意：校验通过 **不等于** 可以省掉 JS 转义。拼 HTML 时仍然用 `JSONObject.quote()`
 * 生成字符串字面量，这是纵深防御的第二道（详见 CaptchaWebViewActivity.buildCaptchaHtml）。
 */
object CaptchaParamPolicy {

    /** 官方取值 32 位；下限留到 16，兼容极验可能下发的短 token。 */
    const val MIN_LENGTH = 16

    /** 上限 64，覆盖官方 32 位与可能的加长格式。 */
    const val MAX_LENGTH = 64

    /**
     * 只允许 ASCII 字母与数字。
     *
     * 刻意不用 `\w`：Java 的 `\w` 在默认模式下只有 `[a-zA-Z0-9_]`，但 `Pattern.UNICODE_CHARACTER_CLASS`
     * 一开就会放进非 ASCII；写成白名单字符组最不容易被后续改动带偏。
     */
    private val PATTERN = Regex("^[A-Za-z0-9]{16,64}$")

    /**
     * 单个参数是否可信。
     *
     * null、空串、超长、含引号/斜杠/空白/unicode 一律拒绝。返回布尔值而不是抛异常：
     * 调用方只需要「能不能用」这一个结论。
     */
    fun isValid(value: String?): Boolean {
        if (value == null) return false
        return PATTERN.matches(value)
    }

    /** gt 与 challenge 是否同时可信；不合法时调用方应直接放弃这次验证。 */
    fun areValid(gt: String?, challenge: String?): Boolean = isValid(gt) && isValid(challenge)
}
