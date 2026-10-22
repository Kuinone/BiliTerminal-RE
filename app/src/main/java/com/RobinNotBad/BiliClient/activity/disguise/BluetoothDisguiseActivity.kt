package com.RobinNotBad.BiliClient.activity.disguise

import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.util.DisguisePrefs
import com.RobinNotBad.BiliClient.util.Logu

/**
 * 伪装成「蓝牙已断开」的系统提示（issue #22 的第二种样式，双击顶栏触发）。
 *
 * <h3>为什么是"假系统提示"</h3>
 * 相比计算器，它更省事也更容易被当成"正在处理耳机连接"而放过。窗口是**半透明**的
 * （`Theme.BiliClient.Disguise.Dialog`），底下的页面会透出来再压一层暗色，
 * 观感就是「系统提示框盖在当前画面上」。
 *
 * <h3>所有交互都是假的</h3>
 * 「重新连接」只把正文换成"正在重新连接…"，1.5 秒后换回「连接失败」；「取消」什么也不做。
 * 不做任何真实蓝牙操作——本应用根本没有蓝牙权限，也不能因为伪装去申请。
 *
 * <h3>退出</h3>
 * 这一款没有数字键盘，所以**长按标题 2 秒**退出（见 [setupExitGesture]）。
 * 短按标题毫无反应，旁观者不容易试出来。返回键同样被吃掉（[onBackPressed]）。
 */
class BluetoothDisguiseActivity : AppCompatActivity() {

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disguise_bluetooth)

        body = findViewById(R.id.disguise_bt_body)

        findViewById<TextView>(R.id.disguise_bt_retry).setOnClickListener {
            // 假的：只换一行字，1.5 秒后回到原样
            body.text = getString(R.string.text_disguise_bt_retrying)
            body.postDelayed({
                if (!isFinishing && !isDestroyed) body.text = getString(R.string.text_disguise_bt_failed)
            }, 1_500L)
        }
        findViewById<TextView>(R.id.disguise_bt_cancel).setOnClickListener {
            // 假的：「取消」本来就不该关掉系统提示，这里保持不动
        }

        setupExitGesture()
    }

    /**
     * 长按标题 2 秒退出伪装。
     *
     * 用 `postDelayed` 而不是系统的 `setOnLongClickListener`：手表上很多 ROM 的长按延迟被调过，
     * 这里要的是"够久、试不出来"，所以自己计时，并在 `ACTION_UP` / `ACTION_CANCEL` 时取消。
     */
    private fun setupExitGesture() {
        val title = findViewById<TextView>(R.id.disguise_bt_title)
        val exit = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            Logu.d("disguise", "长按标题，退出伪装")
            finish()
        }
        title.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.postDelayed(exit, HOLD_TO_EXIT_MS)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> view.removeCallbacks(exit)

                else -> return@setOnTouchListener false
            }
            false // 不消费事件：标题本身没有点击行为，交给系统照常处理
        }
    }

    /** 返回键被吃掉：伪装页必须只能靠长按标题退出。 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 故意什么都不做
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) return true
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        /** 长按多久才退出伪装（毫秒）。短按与 1 秒内的长按都不该露馅。 */
        const val HOLD_TO_EXIT_MS = 2_000L
    }
}
