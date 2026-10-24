package com.RobinNotBad.BiliClient.activity.user.favorite

import com.RobinNotBad.BiliClient.util.UiText

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil

import com.google.android.material.card.MaterialCardView

/**
 * 编辑收藏夹。
 *
 * 标题/简介/隐私一律以 `x/v3/fav/folder/info` 为准：列表接口不下发简介与隐私，
 * 旧实现直接拿 Intent 里的空简介和写死的 privacy=0 去保存，会把私密收藏夹重置成公开、
 * 把简介清空。现在打开页面先拉 folder/info 回填，读不到就禁用保存（不能用默认值提交覆盖）。
 */
class FavoriteFolderEditActivity : BaseActivity() {

    private var mediaId: Long = 0
    private var originalTitle: String? = null
    private var isDefault: Boolean = false

    /** 0=公开 1=私密；由 folder/info 的 attr bit0 解析后回填，不再写死 */
    private var privacy = 0

    /** folder/info 是否读取成功：未成功前禁止保存，避免拿默认值覆盖服务端数据 */
    private var infoLoaded = false

    private lateinit var editTitle: EditText
    private lateinit var editIntro: EditText
    private lateinit var radioGroup: RadioGroup
    private lateinit var radioPublic: RadioButton
    private lateinit var radioPrivate: RadioButton
    private lateinit var btnSave: MaterialCardView
    private lateinit var btnDelete: MaterialCardView
    private var deleteClickCount = 0

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favorite_folder_edit)

        val intent = intent
        mediaId = intent.getLongExtra("mediaId", 0)
        originalTitle = intent.getStringExtra("title")
        isDefault = intent.getBooleanExtra("isDefault", false)

        editTitle = findViewById(R.id.editTitle)
        editIntro = findViewById(R.id.editIntro)
        radioGroup = findViewById(R.id.radioPrivacy)
        radioPublic = findViewById(R.id.radioPublic)
        radioPrivate = findViewById(R.id.radioPrivate)
        btnSave = findViewById(R.id.btnSave)
        btnDelete = findViewById(R.id.btnDelete)

        // 列表已有的标题先占位；标题/简介/隐私等 folder/info 回来后再以服务端值回填
        if (!originalTitle.isNullOrEmpty()) {
            editTitle.setText(originalTitle)
        }
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            privacy = if (checkedId == R.id.radioPrivate) 1 else 0
        }

        if (isDefault) {
            lockAsDefaultFolder()
        } else {
            btnSave.setOnClickListener { saveFolder() }
            btnDelete.setOnClickListener { handleDeleteClick() }
            loadFolderInfo()
        }
    }

    /**
     * 按 mediaId 拉收藏夹详情，回填标题/简介/隐私。
     *
     * 失败（含未登录、已删除、无权限）时禁用保存并清空可编辑状态，绝不带着默认值提交。
     */
    private fun loadFolderInfo() {
        if (mediaId <= 0) {
            onFolderInfoUnavailable()
            return
        }
        infoLoaded = false
        setSaveEnabled(false)
        CenterThreadPool.run {
            try {
                val folder = FavoriteApi.getFolderInfo(mediaId)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    editTitle.setText(folder.name)
                    editIntro.setText(folder.intro)
                    if (folder.isDefault) {
                        // 服务端标记为默认收藏夹（attr bit1=0）：与列表页口径一致，锁死编辑
                        lockAsDefaultFolder()
                        return@runOnUiThread
                    }
                    privacy = folder.privacy
                    radioPublic.isChecked = folder.privacy == 0
                    radioPrivate.isChecked = folder.privacy == 1
                    infoLoaded = true
                    setSaveEnabled(true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    onFolderInfoUnavailable()
                }
            }
        }
    }

    /** folder/info 读取失败：禁用保存与输入，避免用户以为改动已生效 */
    private fun onFolderInfoUnavailable() {
        infoLoaded = false
        setSaveEnabled(false)
        editTitle.isEnabled = false
        editIntro.isEnabled = false
        radioPublic.isEnabled = false
        radioPrivate.isEnabled = false
        MsgUtil.showMsg(UiText.of("读取收藏夹设置失败，已禁用保存，请返回重试"))
    }

    private fun lockAsDefaultFolder() {
        isDefault = true
        editTitle.isEnabled = false
        editIntro.isEnabled = false
        radioPublic.isEnabled = false
        radioPrivate.isEnabled = false
        setSaveEnabled(false)
        btnDelete.visibility = View.GONE
        MsgUtil.showMsg(UiText.of("默认收藏夹不能编辑或删除"))
    }

    private fun setSaveEnabled(enabled: Boolean) {
        btnSave.isClickable = enabled
        btnSave.alpha = if (enabled) 1f else 0.5f
    }

    private fun saveFolder() {
        if (!infoLoaded) {
            MsgUtil.showMsg(UiText.of("收藏夹设置尚未读取成功，暂不能保存"))
            return
        }

        val title = editTitle.text.toString().trim()
        if (title.isEmpty()) {
            MsgUtil.showMsg(UiText.of("请输入收藏夹名称"))
            return
        }

        val intro = editIntro.text.toString().trim()
        setSaveEnabled(false)

        CenterThreadPool.run {
            try {
                // privacy 来自 folder/info 回填值，不能用默认值
                val result = FavoriteApi.editFolder(mediaId, title, intro, privacy)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (result == 0) {
                        MsgUtil.showMsg(UiText.of("保存成功"))
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        setSaveEnabled(true)
                        MsgUtil.showMsg("保存失败，错误码：$result")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    setSaveEnabled(true)
                    report(e)
                }
            }
        }
    }

    private fun handleDeleteClick() {
        deleteClickCount++
        if (deleteClickCount == 1) {
            MsgUtil.showMsg(UiText.of("再次点击删除按钮确认删除"))
            val handler = Handler()
            handler.postDelayed({ deleteClickCount = 0 }, 3000)
        } else if (deleteClickCount >= 2) {
            deleteClickCount = 0
            deleteFolder()
        }
    }

    private fun deleteFolder() {
        btnDelete.isClickable = false

        CenterThreadPool.run {
            try {
                val result = FavoriteApi.deleteFolder(mediaId)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    btnDelete.isClickable = true
                    if (result == 0) {
                        MsgUtil.showMsg(UiText.of("删除成功"))
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        MsgUtil.showMsg("删除失败，错误码：$result")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    btnDelete.isClickable = true
                    report(e)
                }
            }
        }
    }
}
