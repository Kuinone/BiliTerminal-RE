package com.RobinNotBad.BiliClient.activity

import com.RobinNotBad.BiliClient.util.UiText

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.viewpager.widget.ViewPager
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.adapter.viewpager.ViewPagerViewAdapter
import com.RobinNotBad.BiliClient.ui.widget.PhotoViewpager
import com.RobinNotBad.BiliClient.util.FileUtil
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.Target
import com.github.chrisbanes.photoview.PhotoView

class ImageViewerActivity : BaseActivity() {

    private var longClickTimestamp: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题由 BaseActivity 按用户选择设置，这里不再强制回 B站粉
        setContentView(R.layout.activity_image_viewer)
        val intent = intent
        // 本页面 exported=true，外部可传空 Intent，缺少必要参数时直接退出而非崩溃
        val imageList = intent.getStringArrayListExtra("imageList")
        if (imageList.isNullOrEmpty()) {
            MsgUtil.showMsgLong(UiText.of("未收到图片数据"))
            finish()
            return
        }

        val viewPager = findViewById<PhotoViewpager>(R.id.viewPager)
        val textView = findViewById<TextView>(R.id.text_page)

        val photoViewList = ArrayList<View>()

        val download = findViewById<ImageButton>(R.id.btn_download)
        download.setOnClickListener {
            val timeNow = System.currentTimeMillis()
            if (timeNow - longClickTimestamp < 3000) {
                val intent1 = Intent(this, DownloadActivity::class.java)
                    .putExtra("link", imageList[viewPager.currentItem])
                    .putExtra("path", FileUtil.getPicturePath().absolutePath)
                    .putExtra("type", 0)
                startActivity(intent1)
            } else MsgUtil.showMsg(UiText.of("再次点击下载"))
            longClickTimestamp = timeNow
        }

        for (i in imageList.indices) {
            val photoView = PhotoView(this)
            photoView.maximumScale = 6.25f
            photoViewList.add(photoView)
        }

        // 26.10.27 性能：原来 onCreate 里一次性把**所有**图按原尺寸（SIZE_ORIGINAL）解码，
        // 九宫格动态就是 9 张 1024w ARGB 位图同时进内存，手表上容易 OOM（而且 Glide 的异步 OOM
        // 根本不会落到这里的 catch）。现在只加载当前页和左右各一页，翻页时再补。
        val loaded = BooleanArray(imageList.size)
        fun loadAround(position: Int) {
            for (i in (position - 1)..(position + 1)) {
                if (i !in imageList.indices || loaded[i]) continue
                loaded[i] = true
                try {
                    Glide.with(this).asDrawable()
                        .load(GlideUtil.url_zoom(imageList[i]))
                        .transition(GlideUtil.getTransitionOptions())
                        .override(Target.SIZE_ORIGINAL)
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .into(photoViewList[i] as PhotoView)
                } catch (e: OutOfMemoryError) {
                    MsgUtil.showMsg(UiText.of("超出内存，加载失败"))
                } catch (e: Exception) {
                    MsgUtil.err(UiText.of("图片查看"), e)
                }
            }
        }
        loadAround(0)

        val vpiAdapter = ViewPagerViewAdapter(photoViewList)

        viewPager.adapter = vpiAdapter

        viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            @SuppressLint("SetTextI18n")
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                if (positionOffset % 1 == 0f)
                    textView.text = "第${position + 1}/${imageList.size}张"
            }

            override fun onPageSelected(position: Int) {
                loadAround(position)
            }

            override fun onPageScrollStateChanged(state: Int) {
            }
        })
    }
}