package com.RobinNotBad.BiliClient.activity.disguise

import android.content.Context
import android.content.Intent
import com.RobinNotBad.BiliClient.util.DisguisePrefs
import com.RobinNotBad.BiliClient.util.Logu

/**
 * 伪装页的统一入口：按设置里选的样式决定打开哪一个。
 *
 * 触发点在 `BaseActivity.setupTopbarDoubleTapDisguise`（双击顶栏）。抽成一个函数是因为
 * 触发点以后可能不止一处（比如播放页如果也要接），样式判定只该有一份。
 */
object DisguiseLauncher {

    /**
     * 打开当前设置的伪装页。
     *
     * 启动失败（页面被裁掉、设备限制后台启动等）只记日志，不弹提示——
     * 弹一个"打开伪装页失败"的 toast 比不伪装更显眼。
     */
    @JvmStatic
    fun launch(context: Context) {
        val target: Class<out android.app.Activity> =
            if (DisguisePrefs.style() == DisguisePrefs.STYLE_BLUETOOTH) {
                BluetoothDisguiseActivity::class.java
            } else {
                CalculatorDisguiseActivity::class.java
            }
        try {
            context.startActivity(Intent(context, target))
        } catch (e: Exception) {
            Logu.e("disguise", "打开伪装页失败: ${e.message}")
        }
    }
}
