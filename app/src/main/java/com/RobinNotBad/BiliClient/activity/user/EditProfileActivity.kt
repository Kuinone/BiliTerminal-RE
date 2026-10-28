package com.RobinNotBad.BiliClient.activity.user

import com.RobinNotBad.BiliClient.util.UiText

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.ImagePickerActivity
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.api.UserInfoApi
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.card.MaterialCardView
import java.io.ByteArrayOutputStream

class EditProfileActivity : BaseActivity() {

    private lateinit var avatarIcon: ImageView
    private var isUploading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)

        if (SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0) == 0L) {
            MsgUtil.showMsg(UiText.of("还没有登录喵~"))
            finish()
            return
        }

        avatarIcon = findViewById(R.id.upload_avatar_icon)
        val uploadAvatarCard = findViewById<MaterialCardView>(R.id.upload_avatar)
        val editSignCard = findViewById<MaterialCardView>(R.id.edit_sign)
        val editUserInfoCard = findViewById<MaterialCardView>(R.id.edit_user_info)

        val currentAvatar = SharedPreferencesUtil.getString("avatar", "")
        if (currentAvatar.isNotEmpty()) {
            Glide.with(this)
                .load(GlideUtil.url(currentAvatar))
                .apply(RequestOptions.circleCropTransform())
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .into(avatarIcon)
        }

        uploadAvatarCard.setOnClickListener { selectImage() }

        editSignCard.setOnClickListener {
            val intent = Intent(this, EditSignActivity::class.java)
            startActivity(intent)
        }

        editUserInfoCard.setOnClickListener {
            val intent = Intent(this, EditUserInfoActivity::class.java)
            startActivity(intent)
        }

        findViewById<android.view.View>(R.id.pageName).setOnClickListener { finish() }
    }

    /**
     * 选图。**禁止用 `ACTION_PICK` + `content://media/external/images/media`**：
     * 部分手表 / 定制 ROM 的系统相册不声明 `ACTION_PICK`，点击即抛
     * `ActivityNotFoundException: No Activity found to handle Intent { act=android.intent.action.PICK … }`
     * （线上崩溃 #63，XTC monaco_go Z10 / SDK 30，版本 26.10.19）。
     *
     * 改用 `ACTION_GET_CONTENT`（type 设成 image 通配 MIME）：文件管理器与相册都能响应，兼容性更好
     * （与 [com.RobinNotBad.BiliClient.activity.message.PrivateMsgActivity]、
     * [com.RobinNotBad.BiliClient.activity.reply.WriteReplyActivity]、
     * [com.RobinNotBad.BiliClient.activity.settings.SettingGroupActivity] 的结论一致）。
     * 设备上一个选图应用都没有时只提示、不崩。
     *
     * **注意 Kotlin 块注释可以嵌套**：本注释里别原样写「image 加斜杠加星号」（会开一个嵌套注释，
     * 把整份文件的后半段吞掉、报 Unclosed comment），那个 MIME 串只写在下面的字符串字面量里。
     */
    private fun selectImage() {
        if (isUploading) {
            MsgUtil.showMsg(UiText.of("正在上传中..."))
            return
        }
        if (!SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.cookie_refresh, true)) {
            MsgUtil.showDialog(getString(R.string.text_cannot_upload), getString(R.string.msg_cookie_refresh_failed), -1)
            return
        }
        try {
            // 内置选图页（反馈 #53）；页内「系统选择器」仍是原来的 ACTION_GET_CONTENT，结果格式相同
            startActivityForResult(ImagePickerActivity.intent(this), REQUEST_CODE_PICK_IMAGE)
        } catch (e: Exception) {
            // 精简手表 ROM 可能连 GET_CONTENT 都没人处理，抛 ActivityNotFoundException
            MsgUtil.showMsg(getString(R.string.msg_no_image_picker))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            val imageUri = data.data
            if (imageUri != null) {
                uploadAvatar(imageUri)
            }
        }
    }

    private fun uploadAvatar(imageUri: Uri) {
        isUploading = true
        MsgUtil.showMsg(UiText.of("正在上传头像..."))

        CenterThreadPool.run {
            try {
                val inputStream = contentResolver.openInputStream(imageUri)
                if (inputStream == null) {
                    runOnUiThread {
                        isUploading = false
                        MsgUtil.showMsg(UiText.of("无法读取图片"))
                    }
                    return@run
                }
                val bitmap: Bitmap? = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bitmap == null) {
                    runOnUiThread {
                        isUploading = false
                        MsgUtil.showMsg(UiText.of("无法解码图片"))
                    }
                    return@run
                }

                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                val imageData = baos.toByteArray()
                val fileName = "avatar_" + System.currentTimeMillis() + ".jpg"

                val result = UserInfoApi.uploadAvatar(imageData, fileName)
                val code = result.getInt("code")
                val message = result.optString("message", "")

                if (!isDestroyed) {
                    runOnUiThread {
                        isUploading = false
                        if (code == 0) {
                            MsgUtil.showMsg(UiText.of("头像上传成功，等待审核"))
                            val data = result.optJSONObject("data")
                            if (data != null) {
                                val faceUrl = data.optString("url", "")
                                if (faceUrl.isNotEmpty()) {
                                    SharedPreferencesUtil.putString("avatar", faceUrl)
                                    Glide.with(this@EditProfileActivity)
                                        .load(GlideUtil.url(faceUrl))
                                        .apply(RequestOptions.circleCropTransform())
                                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                                        .into(avatarIcon)
                                }
                            }
                        } else {
                            val errorMsg = when (code) {
                                -101 -> UiText.of("账号未登录")
                                -102 -> UiText.of("CSRF校验失败")
                                -111 -> UiText.of("图片格式不支持")
                                -112 -> UiText.of("图片过大")
                                -400 -> UiText.of("请求参数错误")
                                -403 -> UiText.of("CSRF验证失败")
                                else -> if (message.isNotEmpty()) message else "上传失败"
                            }
                            MsgUtil.showMsg("$errorMsg (错误码:$code)")
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isDestroyed) {
                    runOnUiThread {
                        isUploading = false
                        MsgUtil.err(UiText.of("上传头像失败"), e)
                    }
                }
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PICK_IMAGE = 1001
    }
}