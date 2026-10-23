package com.RobinNotBad.BiliClient.util

/**
 * 「伪装 / 安全」开关：一键把界面换成不会引人注意的假页面（issue #22）。
 *
 * <h3>为什么需要</h3>
 * 学生等场景下，被人走近时需要一个**比"退出应用"更快**的动作。触发方式定为**双击顶栏**
 * （见 `BaseActivity.setupTopbarDoubleTapDisguise`），伪装页有两种：
 * - [STYLE_CALCULATOR]：真能算的计算器，输入正确暗号再按 `=` 才退出；
 * - [STYLE_BLUETOOTH]：假的「蓝牙已断开」提示页，长按标题才退出。
 *
 * <h3>默认关，且每次都要重新判断</h3>
 * 默认关（[DEFAULT_ENABLED]）——这个功能会改掉「单击顶栏」的响应时序（需要多等一个双击判定窗口），
 * 不该强迫所有用户承担。手势层只在开启时安装（`BaseActivity` 每次 `onStart` 判断一次），
 * 关闭时立刻摘掉监听器，关闭状态下单击顶栏与改动前完全一致。
 *
 * <h3>暗号</h3>
 * 暗号只允许 [CODE_MIN_LENGTH]~[CODE_MAX_LENGTH] 位数字：计算器上能敲出来的只有这些字符，
 * 否则"暗号"永远输不进去（配了 `abc` 就再也退不出伪装页，只能强杀应用）。
 * 归一化与比对都是纯函数，有 JVM 单测（[com.RobinNotBad.BiliClient.util.DisguisePrefsTest]）。
 */
object DisguisePrefs {

    /** 伪装样式：假计算器。 */
    const val STYLE_CALCULATOR = "calculator"

    /** 伪装样式：假的「蓝牙已断开」。 */
    const val STYLE_BLUETOOTH = "bluetooth"

    /** 默认伪装样式。 */
    const val DEFAULT_STYLE = STYLE_CALCULATOR

    /** 默认关闭。 */
    const val DEFAULT_ENABLED = false

    /** 默认暗号。 */
    const val DEFAULT_CODE = "1234"

    const val CODE_MIN_LENGTH = 4
    const val CODE_MAX_LENGTH = 8

    /** 伪装是否开启。 */
    @JvmStatic
    fun isEnabled(): Boolean =
        SharedPreferencesUtil.getBoolean(SettingsKeys.DISGUISE_ENABLED, DEFAULT_ENABLED)

    /**
     * 当前伪装样式，取值只会是 `STYLE_*` 之一。
     *
     * 存档里出现脏值（手改 / 回滚版本）时回退 [DEFAULT_STYLE]，不让设置页显示成半残状态。
     */
    @JvmStatic
    fun style(): String {
        val saved = SharedPreferencesUtil.getString(SettingsKeys.DISGUISE_STYLE, "")
        return if (saved == STYLE_CALCULATOR || saved == STYLE_BLUETOOTH) saved else DEFAULT_STYLE
    }

    /**
     * 当前暗号，**保证是合法的**（[normalizeCode] 过滤后仍不合法时回退 [DEFAULT_CODE]）。
     *
     * 读取侧再兜一次底：设置页会拦非法输入，但存档可能被手改，
     * 一旦存进非数字暗号，用户在计算器上永远敲不出来 —— 那就只能强杀应用了。
     */
    @JvmStatic
    fun code(): String = normalizeCode(SharedPreferencesUtil.getString(SettingsKeys.DISGUISE_CODE, ""))
        ?: DEFAULT_CODE

    /**
     * 把用户输入归一化成合法暗号；不合法返回 `null`。
     *
     * 只接受纯数字（长度 [CODE_MIN_LENGTH]~[CODE_MAX_LENGTH]）：前后空格会被裁掉，
     * 中间夹空格、字母、符号一律判非法。设置页据此决定"要不要落盘"。
     */
    @JvmStatic
    fun normalizeCode(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.length < CODE_MIN_LENGTH || trimmed.length > CODE_MAX_LENGTH) return null
        if (!trimmed.all { it in '0'..'9' }) return null
        return trimmed
    }

    /**
     * 计算器上敲出来的内容是否就是暗号。
     *
     * 空暗号恒不匹配：不能因为"暗号配成空"就让伪装页一按 `=` 就退出。
     */
    @JvmStatic
    fun matchesSecret(input: String?, code: String?): Boolean {
        val c = normalizeCode(code) ?: return false
        return input?.trim() == c
    }

    /** 伪装样式是否合法（设置页保存前校验）。 */
    @JvmStatic
    fun isValidStyle(value: String?): Boolean =
        value == STYLE_CALCULATOR || value == STYLE_BLUETOOTH
}
