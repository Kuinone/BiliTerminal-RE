package com.RobinNotBad.BiliClient.activity.user.favorite

import com.RobinNotBad.BiliClient.util.UiText

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.FavoriteApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.TerminalDialog

import com.google.android.material.card.MaterialCardView

/**
 * 收藏夹管理页（长按收藏夹卡进入）。
 *
 * 页面分三块，默认收藏夹只留中间那块：
 * 1. 信息编辑（名称/简介/隐私 + 保存）——默认收藏夹不能改，整块隐藏；
 * 2. 内容管理（批量删除内容 / 清空失效内容）——与改名无关，默认收藏夹同样可用；
 * 3. 危险操作（删除收藏夹）——默认收藏夹不能删，整块隐藏。
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

    /** 清空失效内容的请求是否在飞：避免连点发多个请求 */
    private var cleaning = false

    private lateinit var editTitle: EditText
    private lateinit var editIntro: EditText
    private lateinit var radioGroup: RadioGroup
    private lateinit var radioPublic: RadioButton
    private lateinit var radioPrivate: RadioButton
    private lateinit var btnSave: MaterialCardView
    private lateinit var btnDelete: MaterialCardView
    private lateinit var groupInfo: LinearLayout
    private lateinit var groupDanger: LinearLayout
    private lateinit var hintDefault: TextView
    private lateinit var btnBatchDelete: MaterialCardView
    private lateinit var btnCleanInvalid: MaterialCardView
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
        groupInfo = findViewById(R.id.groupInfo)
        groupDanger = findViewById(R.id.groupDanger)
        hintDefault = findViewById(R.id.hintDefault)
        btnBatchDelete = findViewById(R.id.btnBatchDelete)
        btnCleanInvalid = findViewById(R.id.btnCleanInvalid)

        // 列表已有的标题先占位；标题/简介/隐私等 folder/info 回来后再以服务端值回填
        if (!originalTitle.isNullOrEmpty()) {
            editTitle.setText(originalTitle)
        }
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            privacy = if (checkedId == R.id.radioPrivate) 1 else 0
        }

        setupContentManagement()

        if (isDefault) {
            lockAsDefaultFolder()
        } else {
            btnSave.setOnClickListener { saveFolder() }
            btnDelete.setOnClickListener { handleDeleteClick() }
            loadFolderInfo()
        }
    }

    /**
     * 内容管理入口：批量删除内容 / 清空失效内容。
     *
     * 这两件事只认 media_id，与能否改名/删除无关，所以默认收藏夹也能用。
     */
    private fun setupContentManagement() {
        btnBatchDelete.setOnClickListener { openContentManager() }
        btnCleanInvalid.setOnClickListener { confirmCleanInvalid() }
    }

    /** 进夹内内容页，并直接进入多选态（selectMode extra 由 FavoriteVideoListActivity 读取） */
    private fun openContentManager() {
        if (mediaId <= 0) {
            MsgUtil.showMsg(getString(R.string.uitext_favoritefolderlistactivity_001))
            return
        }
        val intent = Intent(this, FavoriteVideoListActivity::class.java)
        intent.putExtra("mid", SharedPreferencesUtil.getLong("mid", 0))
        intent.putExtra("name", originalTitle ?: "")
        intent.putExtra("mediaId", mediaId)
        intent.putExtra("readOnly", false)
        intent.putExtra("selectMode", true)
        startActivityForResult(intent, REQUEST_CONTENT)
    }

    private fun confirmCleanInvalid() {
        if (mediaId <= 0) {
            MsgUtil.showMsg(getString(R.string.uitext_favoritefolderlistactivity_001))
            return
        }
        if (cleaning) return
        TerminalDialog.confirm(
            this,
            getString(R.string.text_activityfavoritefolderedit_12),
            getString(R.string.uitext_favoritefoldereditactivity_004),
            getString(R.string.uitext_favoritefoldereditactivity_007)
        ) { cleanInvalid() }
            .show()
    }

    /** 清空失效内容：服务端没有下发「可清理数量」的接口，所以不做数量预检，直接请求 */
    private fun cleanInvalid() {
        cleaning = true
        setCleanInvalidEnabled(false)
        CenterThreadPool.run {
            try {
                val code = FavoriteApi.cleanInvalidResources(mediaId)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    cleaning = false
                    setCleanInvalidEnabled(true)
                    if (code == 0) {
                        MsgUtil.showMsg(getString(R.string.uitext_favoritefoldereditactivity_005))
                        setResult(RESULT_OK)
                    } else {
                        MsgUtil.showMsg(FavoriteApi.resourceErrorMsg(code))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    cleaning = false
                    setCleanInvalidEnabled(true)
                    report(e)
                }
            }
        }
    }

    private fun setCleanInvalidEnabled(enabled: Boolean) {
        btnCleanInvalid.isClickable = enabled
        btnCleanInvalid.alpha = if (enabled) 1f else 0.5f
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 内容页里可能删了内容，数量变了：让收藏夹列表在返回后重拉一次（多刷一次不值得省）
        if (requestCode == REQUEST_CONTENT) {
            setResult(RESULT_OK)
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

    /**
     * 默认收藏夹：隐藏信息编辑与删除，只留内容管理。
     *
     * 不再弹「默认收藏夹不能编辑或删除」的吐司——页面上常驻一行说明（hintDefault）代替，
     * 否则每次打开都要等它消失。
     */
    private fun lockAsDefaultFolder() {
        isDefault = true
        editTitle.isEnabled = false
        editIntro.isEnabled = false
        radioPublic.isEnabled = false
        radioPrivate.isEnabled = false
        setSaveEnabled(false)
        groupInfo.visibility = View.GONE
        groupDanger.visibility = View.GONE
        hintDefault.visibility = View.VISIBLE
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

    private companion object {
        /** 夹内内容页的 requestCode；返回后让收藏夹列表重拉一次 */
        const val REQUEST_CONTENT = 3
    }
}
