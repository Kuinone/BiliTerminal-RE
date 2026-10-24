package com.RobinNotBad.BiliClient.activity.video.info

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TerminalDialog
import com.RobinNotBad.BiliClient.util.UiText

/**
 * Android 13+（API 33）通知权限与「下载」之间的关系。
 *
 * 事实依据：POST_NOTIFICATIONS 只控制**通知能不能显示**。被拒绝时前台服务照常运行
 * （DownloadService 的 startForeground 不会因此失败或被系统杀），只是进度通知不可见。
 * 因此它**不能**作为下载的前置条件：旧行为（拒绝一次就不再下载）等于把核心功能永久挡死，
 * 而手表 ROM 往往没有「通知权限」设置页，用户找不到恢复入口（issue #42 同类情况）。
 *
 * 策略：
 * 1. 未授权时仍弹一次系统权限框（为了拿到可见的进度通知），但调用方在请求后**立刻**开始下载，
 *    不等回调结果；
 * 2. 已经问过一次就不再弹（连续弹窗在 300×300 表盘上极易误触，而且 Android 13 第二次拒绝后
 *    系统会永久静默），改为一次非阻断的「去设置 / 继续下载」提示；
 * 3. 「去设置」跳 [Settings.ACTION_APP_NOTIFICATION_SETTINGS]，部分手表 ROM 没有该页面，
 *    回退到应用详情页；都打不开就只提示，不影响已经开始的下载。
 */
internal object DownloadNotificationGate {

    /**
     * 记录「是否已经弹过系统权限框」的键。
     *
     * 用字面量键而不是 SettingsIndex.kt（本次修复范围外），与 `first_play` 等既有
     * 调用点写法一致；`SharedPreferencesUtil` 对未登记的键也会正常读写。
     */
    private const val PREF_ASKED = "download_notification_permission_asked"

    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * 未授权时请求一次；已经请求过则只给「去设置」出口。
     *
     * 本方法保证：不抛异常、不阻塞、不重复弹系统框。调用方应当在调用之后**无条件**继续下载。
     */
    fun requestOnce(activity: Activity, launcher: ActivityResultLauncher<String>) {
        if (!needsPermission(activity)) return

        if (SharedPreferencesUtil.getBoolean(PREF_ASKED, false)) {
            // 读 rationale 只为区分「还能再弹」和「已被永久拒绝」——两种状态的 UI 出口相同
            // （都是去设置），但日志里能看出用户停在哪一步，便于排查「通知一直不出现」的反馈。
            val canAskAgain = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            Logu.d("download-notify", "rationale=$canAskAgain")
            showSettingsHint(activity)
            return
        }

        // 先记「问过」再弹：即使进程在弹框期间被杀，下次也不会再连续弹一次。
        SharedPreferencesUtil.putBoolean(PREF_ASKED, true)
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 非阻断提示：说明下载已开始、通知不可见，并给出去设置的出口。 */
    private fun showSettingsHint(activity: Activity) {
        TerminalDialog.choice(
            context = activity,
            title = UiText.of("通知权限未开启"),
            message = UiText.of("下载仍会继续，只是通知栏看不到进度。可以在系统设置里允许通知。"),
            primaryText = UiText.of("去设置"),
            secondaryText = UiText.of("继续下载"),
            onPrimary = { openNotificationSettings(activity) },
            onSecondary = {}
        ).show()
    }

    /** 打开本应用的通知设置页；设备不支持时逐级回退，最后只提示，不崩溃、不影响下载。 */
    private fun openNotificationSettings(activity: Activity) {
        val packageUri = Uri.parse("package:" + activity.packageName)
        val candidates = ArrayList<Intent>(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            candidates.add(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            )
        }
        // 应用详情页几乎所有 ROM 都有，作为兜底（通知设置页在部分手表 ROM 上被裁掉）。
        candidates.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))

        for (intent in candidates) {
            try {
                activity.startActivity(intent)
                return
            } catch (ignored: ActivityNotFoundException) {
                // 换下一个候选页
            } catch (ignored: Exception) {
                // 少数 ROM 会抛 SecurityException / IllegalArgumentException 之类，
                // 不能因为这个跳转失败把下载入口带崩
            }
        }
        MsgUtil.showMsg(UiText.of("打不开系统设置，请手动在系统设置中允许通知"))
    }
}
