package com.RobinNotBad.BiliClient.activity.disguise

import android.view.View
import com.RobinNotBad.BiliClient.util.SettingsKeys
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

/**
 * 给伪装页套上「圆屏适配」的内边距。
 *
 * 两个伪装页都**刻意不继承 `BaseActivity`**（那边会带进应用主题、教程触发、EventBus，
 * 任何一项都会让伪装露馅），所以 `BaseActivity.setRound()` 那套留白用不上。
 * 但**圆屏留白是设备适配、不是外观**：圆屏手表上不缩进的话，最外圈的按键/文字会被切掉。
 *
 * 这里只读设置里的内边距并换算成 px，语义与 `BaseActivity.setRound()` 一致；
 * 抽成共享函数是为了不让两个伪装页各写一份相同的换算。
 */
internal fun applyDisguiseRoundPadding(root: View) {
    val padH = SharedPreferencesUtil.getInt(SettingsKeys.PADDING_H, 0)
    val padV = SharedPreferencesUtil.getInt(SettingsKeys.PADDING_V, 0)
    if (padH <= 0 && padV <= 0) return
    val density = root.resources.displayMetrics.density
    val h = (padH * density).toInt()
    val v = (padV * density).toInt()
    root.setPadding(h, v, h, v)
}
