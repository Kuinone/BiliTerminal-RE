package com.RobinNotBad.BiliClient.activity.settings.setup

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.activity.settings.UIPreviewActivity
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.UiScaleInput
import com.google.android.material.switchmaterial.SwitchMaterial

class SetupUIActivity : BaseActivity() {

    private lateinit var uiScaleInput: EditText
    private lateinit var uiPaddingH: EditText
    private lateinit var uiPaddingV: EditText

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_ui)

        uiScaleInput = findViewById(R.id.ui_scale_input)
        uiScaleInput.setText(SharedPreferencesUtil.getFloat("dpi", 1.0F).toString())

        uiPaddingH = findViewById(R.id.ui_padding_horizontal)
        uiPaddingH.setText(SharedPreferencesUtil.getInt("paddingH_percent", 0).toString())
        uiPaddingV = findViewById(R.id.ui_padding_vertical)
        uiPaddingV.setText(SharedPreferencesUtil.getInt("paddingV_percent", 0).toString())

        val round = findViewById<SwitchMaterial>(R.id.switch_round)
        round.isChecked = SharedPreferencesUtil.getBoolean("player_ui_round", false)
        round.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                uiPaddingH.setText("5")
                uiPaddingV.setText("3")
                SharedPreferencesUtil.putBoolean("player_ui_round", true)
                MsgUtil.showMsg(getString(R.string.msg_settinggroupactivity_003))
            } else {
                uiPaddingH.setText("0")
                uiPaddingV.setText("0")
                SharedPreferencesUtil.putBoolean("player_ui_round", false)
            }
        }

        findViewById<android.view.View>(R.id.preview).setOnClickListener {
            save()
            val intent = Intent()
            intent.setClass(this@SetupUIActivity, UIPreviewActivity::class.java)
            startActivity(intent)
        }

        findViewById<android.view.View>(R.id.confirm).setOnClickListener {
            save()
            val intent = Intent()
            intent.setClass(this@SetupUIActivity, IntroductionActivity::class.java)
            startActivity(intent)
            finish()
        }

        findViewById<android.view.View>(R.id.reset).setOnClickListener {
            SharedPreferencesUtil.putInt("paddingH_percent", 0)
            SharedPreferencesUtil.putInt("paddingV_percent", 0)
            SharedPreferencesUtil.putFloat("dpi", 1.0f)
            SharedPreferencesUtil.putBoolean("player_ui_round", false)
            uiScaleInput.setText("1.0")
            uiPaddingH.setText("0")
            uiPaddingV.setText("0")
            round.isChecked = false
            MsgUtil.showMsg(getString(R.string.msg_settinggroupactivity_002))
        }
    }

    private fun save() {
        // 三个框都可能装着非数字内容（粘贴 / IME 直接提交都能绕过 inputType），
        // 解析与范围校验统一走 UiScaleInput：非法就跳过写盘，页面照常关。
        // 旧代码直接 toFloat()/toInt()，线上因此崩过两次（"界面大小" / "确认设置"）。
        UiScaleInput.parseDpi(uiScaleInput.text)?.let { dpiTimes ->
            SharedPreferencesUtil.putFloat("dpi", dpiTimes)
            Logu.i("dpi", dpiTimes.toString())
        }

        UiScaleInput.parsePadding(uiPaddingH.text)?.let { paddingH ->
            SharedPreferencesUtil.putInt("paddingH_percent", paddingH)
            Logu.i("paddingH", paddingH.toString())
        }

        UiScaleInput.parsePadding(uiPaddingV.text)?.let { paddingV ->
            SharedPreferencesUtil.putInt("paddingV_percent", paddingV)
            Logu.i("paddingV", paddingV.toString())
        }
    }
}