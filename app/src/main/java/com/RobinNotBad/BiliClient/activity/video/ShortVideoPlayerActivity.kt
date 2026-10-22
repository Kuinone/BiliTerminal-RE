package com.RobinNotBad.BiliClient.activity.video

import com.RobinNotBad.BiliClient.util.UiText

import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.api.DanmakuApi
import com.RobinNotBad.BiliClient.api.ShortVideoFeedApi
import com.RobinNotBad.BiliClient.model.ShortVideoItem
import com.RobinNotBad.BiliClient.ui.widget.HighEnergyProgressBar
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.SettingsKeys
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.StringUtil
import com.RobinNotBad.BiliClient.util.VideoPreloadManager
import com.RobinNotBad.BiliClient.player.DanmakuManager
import com.RobinNotBad.BiliClient.player.IjkOption
import com.RobinNotBad.BiliClient.player.IjkPlayerBridge
import com.bumptech.glide.Glide
import master.flame.danmaku.ui.widget.DanmakuView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tv.danmaku.ijk.media.player.IjkMediaPlayer

class ShortVideoPlayerActivity : InstanceActivity() {

    /**
     * 本页要自己处理横滑与「短视频页右滑退出」，必须关掉系统窗口层的右滑返回：
     * 那只手势在窗口层直接 finish()，不走 onBackPressed，应用根本收不到。
     */
    override val forceNoSwipeTheme: Boolean
        get() = true

    /** 外壳横向 pager：第 0 页短视频流，第 1~3 页详情/评论/相关推荐（与手机版一致）。 */
    private lateinit var pager: ViewPager2
    private lateinit var shellAdapter: ShortVideoShellAdapter

    var screenWidth = 0
    var screenHeight = 0
    internal var isBottomControlVisible = true

    /** 右侧详情三页当前对应的 aid；变化时调用 [onFeedAnchorChanged] 重绑。 */
    private var detailAnchorAid = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_short_video_player)

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        pager = findViewById(R.id.pager)
        shellAdapter = ShortVideoShellAdapter(
            this,
            // 复用「视频详情页相关推荐」的既有开关：关掉的人，短视频第 4 页也不出现
            relatedEnabled = SharedPreferencesUtil.getBoolean(SettingsKeys.RELATED_ENABLE, true)
        )
        pager.adapter = shellAdapter
        // 横向翻页不再让 pager 自己抢手势：它判定「横向」的阈值太松，竖向刷短视频时
        // 手指稍微一斜就被它拦成翻页。改由每一页根布局 SwipePageLayout 判方向后驱动
        // fakeDrag（见该类注释），pager 只负责承载页面与吸附。
        pager.isUserInputEnabled = false
        // 详情/评论/相关推荐三页常驻：滑过去是纯横向动画，不用等它们现建
        pager.offscreenPageLimit = shellAdapter.itemCount
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // 从详情页滑回短视频时，当前条可能已经变了（例如播完自动接下一条）
                if (position == 0) onFeedAnchorChanged()
            }
        })

        // 教程改由 BaseActivity 按 Tutorials 注册表集中触发
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    override fun onStart() {
        super.onStart()
        // 回到前台重算一次：后台期间可能已经暂停过，别把上一次的亮屏状态带回来
        syncKeepScreenOn()
    }

    override fun onStop() {
        // 进后台一律交还亮屏权：Fragment 的 onPause 走 pauseCurrent() 也会清，
        // 但那条路依赖「活跃页引用还在」，这里再加一道兜底，避免窗口不可见却挂着标志。
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onBackPressed() {
        // 在右侧详情/评论/相关推荐页时，返回先回到短视频首页，而不是直接退出本页
        if (::pager.isInitialized && pager.currentItem > 0) {
            pager.setCurrentItem(0, true)
            return
        }
        super.onBackPressed()
    }

    /** 供页面根布局驱动 fakeDrag；页面还没建好时返回 null。 */
    internal fun shellPager(): ViewPager2? = if (::pager.isInitialized) pager else null

    /**
     * 页面根布局判出「明确横向」后回调（见 [com.RobinNotBad.BiliClient.ui.widget.SwipePageLayout]）。
     *
     * @param direction -1 = 手指向左（翻到下一页），+1 = 手指向右（翻回上一页）
     */
    internal fun handleSwipe(direction: Int) {
        val pager = shellPager() ?: return
        if (isFinishing) return
        val target = if (direction < 0) pager.currentItem + 1 else pager.currentItem - 1
        if (target < 0) {
            // 短视频首页再向右滑就是退出本页（系统右滑返回已在本页关闭）
            finish()
            return
        }
        if (target < shellAdapter.itemCount) pager.setCurrentItem(target, true)
    }

    /**
     * 短视频页换了当前条：把右侧详情/评论/相关推荐热更新到新视频。
     *
     * 注意不能去 `notifyItemRangeChanged`：那会让 ViewPager2 把已经显示出来的页面
     * 移除再重建，真机上出现过整页空白（详见 [ShortVideoShellAdapter] 注释）。
     */
    internal fun onFeedAnchorChanged() {
        if (!::pager.isInitialized) return
        val item = currentShortVideo()
        val aid = item?.aid ?: 0L
        if (aid == detailAnchorAid) return
        detailAnchorAid = aid
        shellAdapter.refreshDetailPages(aid, item?.bvid)
    }

    /** 当前这条短视频；右侧详情页据此渲染。 */
    fun currentShortVideo(): ShortVideoItem? {
        if (!::pager.isInitialized) return null
        val item = shellAdapter.activeFeedFragment?.currentShortVideo
        return item
    }

    /** 播放完成的回调转交（播放逻辑在短视频页里；外壳只做转发）。 */
    internal fun onVideoCompleted(position: Int) {
        shellAdapter.activeFeedFragment?.handleVideoCompleted(position)
    }

    /**
     * 重算「保持亮屏」。
     *
     * 短视频页原先**没有任何 keep-screen-on**（长视频页靠 `activity_player.xml` 的
     * `android:keepScreenOn="true"`），看短视频到点就熄屏。
     *
     * 走**窗口标志**而不是某个 View 的 `keepScreenOn`：View 级标志挂在
     * RecyclerView 的 item 上并不可靠（26.10.22 实测：暂停后窗口 `fl` 里仍留着
     * `KEEP_SCREEN_ON`）。判据取「当前活跃那一页是否真的在播」，所以暂停、滑走、
     * 进后台都会清掉，不会出现「停在暂停画面上也一直亮」。
     *
     * 每次播放状态变化都**整体重算**而不是增量开关：增量写法在
     * `releasePlayer()` 这类「本来就没在播」的路径上会漏掉，残留一次就是永久亮屏。
     */
    internal fun syncKeepScreenOn() {
        if (isFinishing || isDestroyed) return
        if (!::shellAdapter.isInitialized) return
        val keep = shellAdapter.activeFeedFragment?.isCurrentPlaying() == true
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * 外壳 pager 的适配器：第 0 页是短视频流（[ShortVideoFeedFragment]），
 * 第 1~3 页是视频详情 / 评论 / 相关推荐（[ShortVideoDetailPageFragment]）。
 *
 * **页码与 itemId 都不能跟着 aid 变**：26.10.21 真机上试过把 aid 编进 `getItemId`
 * 再 `notifyItemRangeChanged`，旧 Fragment 的视图被摘掉后新视图挂不回来，整页只剩背景色。
 * 现在 id 恒等于页码，换视频时由 [refreshDetailPages] 直接通知三个页面壳自己换内容。
 */
class ShortVideoShellAdapter(
    activity: ShortVideoPlayerActivity,
    private val relatedEnabled: Boolean
) : FragmentStateAdapter(activity) {

    /** 右侧详情三页当前的锚点 aid；0 表示短视频还没加载出来。 */
    var detailAid: Long = 0L
        private set

    /** 与 [detailAid] 配套的 bvid，详情页问接口时要用。 */
    var detailBvid: String? = null
        private set

    /** 已经创建出来的详情页壳，换视频时逐个热更新内容。 */
    private val detailFragments = mutableMapOf<Int, ShortVideoDetailPageFragment>()

    /** 短视频流那页；外壳从这里取「当前这条视频」并转交播放完成回调。 */
    var feedFragment: ShortVideoFeedFragment? = null
        private set

    /** 只在还挂在页面里时可用：Fragment 被回收后引用会失效。 */
    val activeFeedFragment: ShortVideoFeedFragment?
        get() = feedFragment?.takeIf { it.isAdded }

    override fun getItemCount(): Int = if (relatedEnabled) 4 else 3

    override fun createFragment(position: Int): Fragment {
        if (position == 0) {
            val fragment = ShortVideoFeedFragment()
            feedFragment = fragment
            return fragment
        }
        val fragment = ShortVideoDetailPageFragment.newInstance(position, detailAid, detailBvid)
        detailFragments[position] = fragment
        return fragment
    }

    /**
     * 当前短视频换了：把右侧三页的内容换成新 aid 对应的视频。
     *
     * 不碰 ViewPager2 的 item 与 id，只让页面壳自己重建子 Fragment，
     * 这样屏幕上的页面不会经历「移除 → 重建」的中间态。
     */
    fun refreshDetailPages(aid: Long, bvid: String?) {
        if (aid == detailAid && bvid == detailBvid) return
        detailAid = aid
        detailBvid = bvid
        val iterator = detailFragments.iterator()
        while (iterator.hasNext()) {
            val (page, fragment) = iterator.next()
            if (fragment.isAdded) {
                fragment.updateItem(aid, bvid)
            } else {
                iterator.remove() // 已销毁的页面壳不再持有
            }
        }
    }
}

class ShortVideoPagerAdapter(
    private val activity: ShortVideoPlayerActivity,
    private val preloadManager: VideoPreloadManager,
    private val audioManager: AudioManager
) : androidx.recyclerview.widget.RecyclerView.Adapter<ShortVideoPagerAdapter.PageHolder>() {

    private val holders = mutableMapOf<Int, PageHolder>()
    private var activeHolder: PageHolder? = null

    var lastVisiblePosition = -1

    override fun getItemCount(): Int = preloadManager.getItemCount()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fragment_short_video_page, parent, false)
        return PageHolder(view, audioManager, activity)
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val item = preloadManager.getItem(position) ?: return
        holder.boundPosition = position
        holder.bind(item, position)
        holders[position] = holder
    }

    override fun onViewRecycled(holder: PageHolder) {
        super.onViewRecycled(holder)
        // 回收时必须摘掉 map 里的条目，否则 holder 被复用到新 position 后，
        // 旧 position 的 key 仍指向它，pausePlayer(旧pos) 会暂停错对象
        val pos = holder.boundPosition
        if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION && holders[pos] === holder) {
            holders.remove(pos)
        }
        holder.boundPosition = androidx.recyclerview.widget.RecyclerView.NO_POSITION
        // 被回收的若正好是当前活跃页，同步清掉，避免 activeHolder 悬空指向已释放的播放器
        if (activeHolder === holder) activeHolder = null
        holder.releasePlayer()
    }

    fun setupPlayerAtPosition(position: Int) {
        val holder = holders[position] ?: return
        // 先停掉旧的活跃页，避免拖动过程中新旧两页同时 isActive 而各自自动播放
        activeHolder?.let { if (it !== holder) it.setActive(false) }
        activeHolder = holder
        holder.setActive(true)
        holder.setupPlayer(activity.screenWidth, activity.screenHeight)
    }

    fun playAtPosition(position: Int) {
        val holder = holders[position] ?: return
        // 先停掉之前的活跃页面
        activeHolder?.let { if (it != holder) it.setActive(false) }
        activeHolder = holder
        holder.setActive(true)
        holder.play()
    }

    fun pausePlayer(position: Int) {
        holders[position]?.let {
            it.setActive(false)
            it.pause()
        }
    }

    fun pauseCurrent() {
        activeHolder?.pause()
    }

    fun resumeCurrent() {
        activeHolder?.resume()
    }

    fun isCurrentPlaying(): Boolean {
        return activeHolder?.isPlaying() == true
    }

    fun releaseAll() {
        holders.values.forEach { it.releasePlayer() }
        holders.clear()
        // 释放后不能继续指向已释放的 holder，否则 releaseAll 之后
        // pauseCurrent/resumeCurrent/isCurrentPlaying 会操作到已释放的播放器
        activeHolder = null
        lastVisiblePosition = -1
    }

    fun notifyScreenSizeChanged(width: Int, height: Int) {
        holders.values.forEach { it.updateVideoSize(width, height) }
    }

    // itemView.setOnTouchListener 改为在 init 里只设置一次，抑制注解随之上移到类上
    @Suppress("ClickableViewAccessibility")
    inner class PageHolder(
        itemView: View,
        private val audioManager: AudioManager,
        private val activity: ShortVideoPlayerActivity
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {

        private val videoContainer: FrameLayout = itemView.findViewById(R.id.videoContainer)
        private val bufferingIndicator: ProgressBar = itemView.findViewById(R.id.bufferingIndicator)

        // 上一次应用到 bufferingIndicator 的可见性；状态收集每 250ms 触发一次，
        // 记下来才能只在目标值变化时才动 View，省掉多余的 setVisibility 与布局失效
        private var lastBufferingVisible = false

        private val coverImage: ImageView = itemView.findViewById(R.id.coverImage)
        private val playIcon: ImageView = itemView.findViewById(R.id.playIcon)
        private val top: View = itemView.findViewById(R.id.top)
        private val textTitle: TextView = itemView.findViewById(R.id.text_title)
        private val bottomControl: View = itemView.findViewById(R.id.bottom_control)
        private val videoProgress: HighEnergyProgressBar = itemView.findViewById(R.id.videoprogress)
        private val textProgress: TextView = itemView.findViewById(R.id.text_progress)
        private val buttonVideo: ImageButton = itemView.findViewById(R.id.button_video)
        private val buttonSoundCut: ImageButton = itemView.findViewById(R.id.button_sound_cut)
        private val buttonSoundAdd: ImageButton = itemView.findViewById(R.id.button_sound_add)
        private val buttonSpeed: TextView = itemView.findViewById(R.id.button_speed)
        private val buttonDanmaku: ImageButton = itemView.findViewById(R.id.button_danmaku)
        private val showSound: TextView = itemView.findViewById(R.id.showsound)
        private val speedBoostHint: TextView = itemView.findViewById(R.id.text_speed_boost)

        private val playerBridge = IjkPlayerBridge(onError = { what, _ ->
            Logu.e("ShortVideo", "Player error: $what")
            mainHandler.post {
                lastBufferingVisible = false
                bufferingIndicator.visibility = View.GONE
            }
            MsgUtil.showMsg(UiText.of("播放错误"))
        })
        private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var stateJob: Job? = null
        private var playerReady = false
        private var textureView: TextureView? = null
        private var danmakuView: DanmakuView? = null
        private var danmakuManager: DanmakuManager? = null
        private var currentItem: ShortVideoItem? = null
        private var videoWidth = 0
        private var videoHeight = 0
        private var videoAll = 0
        // 主线程在 state.collect 里写、弹幕渲染线程在位置回调里读，必须 volatile，
        // 否则弹幕线程可能读到撕裂或过期的毫秒值。这里是"内存值"，
        // 存在的意义就是让弹幕回调不再直接调 JNI（见下方 danmakuManager 的说明）。
        @Volatile
        private var videoNow = 0
        private var videoNowLast = 0
        // 已渲染到文本的整秒值，用于避免每 250ms 重复拼字符串与 setText
        private var videoNowLastSec = -1
        private var progressStr = "00:00"
        // 主线程写、弹幕渲染线程读（见 danmakuManager 的位置回调），必须 volatile，
        // 否则弹幕线程可能读到过期的 true，继续把重建窗口期的脏位置灌进 DanmakuTimer
        @Volatile
        private var isPrepared = false

        /**
         * 播放状态。
         *
         * setter 里顺带通知宿主重算「保持亮屏」：本页原先**没有任何 keep-screen-on**
         * （长视频页靠 `activity_player.xml` 根布局的 `android:keepScreenOn="true"`，
         * 短视频这套布局没有），看短视频到点就熄屏。
         */
        private var isPlaying = false
            set(value) {
                field = value
                activity.syncKeepScreenOn()
            }
        private var isSeeking = false
        private var isActive = false         // 当前页面是否为活跃状态
        private var isLoadingDanmaku = false // 是否正在加载弹幕
        private var isDanmakuVisible = true  // 弹幕是否可见

        private val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        private var currentSpeedIndex = 2
        private var currentSpeed = 1.0f

        /** 长按临时加速用的倍速：松开后要还原成 [currentSpeed]，不能污染用户选的档位。 */
        private val boostSpeed = 3.0f
        private var speedBoosted = false

        private var currentScale = 1.0f
        private val minScale = 1.0f
        private val maxScale = 3.0f

        private var isScaling = false

        var isInitialized = false
            private set

        /** 当前绑定的 position，NO_POSITION 表示未绑定或被回收 */
        var boundPosition = androidx.recyclerview.widget.RecyclerView.NO_POSITION

        private val mainHandler = Handler(Looper.getMainLooper())
        private var hideBottomRunnable: Runnable? = null
        private var hideVolumeRunnable: Runnable? = null

        private var gestureDetector: GestureDetector? = null
        private var scaleGestureDetector: ScaleGestureDetector? = null

        fun bind(item: ShortVideoItem, position: Int) {
            // bind 只保留与条目数据相关的内容：监听器、手势检测器已在 init 里随 holder 只建一次
            currentItem = item

            if (item.cover.isNotEmpty()) {
                coverImage.visibility = View.VISIBLE
                Glide.with(activity)
                    .asDrawable()
                    .load(GlideUtil.url_hq(item.cover))
                    .into(coverImage)
            }

            textTitle.text = item.title

            isInitialized = true

            if (activity.isBottomControlVisible) {
                bottomControl.visibility = View.VISIBLE
            } else {
                bottomControl.visibility = View.GONE
            }
        }

        /**
         * 一次性初始化：手势检测器、触摸监听与底部按钮监听都只随 holder 创建一次。
         *
         * 该 init 块写在 [itemView]、[top]、各按钮等所有被引用字段的声明之后，
         * 按 Kotlin 的声明顺序初始化，执行到这里时它们都已赋值；构造时 itemView 已传入，
         * 故 findViewById 也可用。回调体内引用的 currentItem 等字段同样是延迟读取，不受影响。
         */
        init {
            gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleBottomControl()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    togglePlayPause()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    // 长按不再跳视频详情页，改为「按住 3 倍速、松手还原」（与手机版短视频一致）。
                    // 详情页现在是横向第 2 页，右滑即可到达，不需要再用长按另开一个 Activity。
                    startSpeedBoost()
                }
            })

            scaleGestureDetector = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isScaling = true
                    // parent 在 init 阶段可能还没挂到 ViewPager2 上，这里保持回调内按需取，并容错 null
                    itemView.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val scaleFactor = detector.scaleFactor
                    currentScale *= scaleFactor
                    currentScale = currentScale.coerceIn(minScale, maxScale)
                    applyScale()
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isScaling = false
                    itemView.parent?.requestDisallowInterceptTouchEvent(false)
                }
            })

            itemView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    isScaling = false
                }

                scaleGestureDetector?.onTouchEvent(event)
                gestureDetector?.onTouchEvent(event)
                // 长按加速是「按住生效」：抬手、被父容器打断（CANCEL）、或第二根手指按下
                // （开始捏合缩放）都要立刻还原倍速，否则会一直停在 3 倍速上。
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    MotionEvent.ACTION_POINTER_DOWN -> endSpeedBoost()
                }
                // 必须返回 true：返回 false 时本 View 不会成为触摸目标，父容器（竖向 pager）
                // 接管后续事件后**不会**再给这里发 MOVE/UP/CANCEL，GestureDetector 收不到抬手，
                // 于是每次点按/滑动都会在 500ms 后误触发 onLongPress
                // （真机实测：点一下、上下滑一下都会跳详情页）。返回 true 不影响父级滚动：
                // pager 在手指真正纵向移动后仍会 onInterceptTouchEvent 抢走手势并给这里发 CANCEL。
                true
            }

            top.setOnClickListener {
                // 短视频是 InstanceActivity 的「菜单入口页」：顶栏点击的语义是「展开主菜单」，
                // 而不是 finish 掉本页。原来直接 finish() 会把整个页面关掉——短视频作为启动页时
                // 更是直接退出应用。改用基类统一的 menuClick（与推荐/热门/排行榜等页面一致）。
                // 展开菜单前先暂停当前短视频与弹幕，避免菜单在前台时视频仍在后台播放。
                try {
                    pause()
                } catch (e: Exception) {
                    Logu.e("ShortVideo", "顶栏回菜单暂停异常: ${e.message}")
                }
                activity.menuClick.run()
            }

            initBottomButtons()
        }

        private fun initBottomButtons() {
            buttonVideo.setOnClickListener {
                controlVideo()
            }

            buttonDanmaku.setOnClickListener {
                toggleDanmaku()
            }

            buttonSoundAdd.setOnClickListener {
                changeVolume(true)
            }

            buttonSoundCut.setOnClickListener {
                changeVolume(false)
            }

            buttonSpeed.setOnClickListener {
                cycleSpeed()
            }

            videoProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, position: Int, fromUser: Boolean) {
                    if (fromUser) {
                        textProgress.text = StringUtil.toTime(position / 1000) + "/" + progressStr
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    isSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    isSeeking = false
                    if (isPrepared) {
                        val seekPos = seekBar.progress.toLong()
                        playerBridge.seekTo(seekPos)
                        danmakuManager?.seekTo(seekPos)
                    }
                }
            })
        }

        private fun cycleSpeed() {
            currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
            currentSpeed = speedOptions[currentSpeedIndex]
            buttonSpeed.text = "${currentSpeed}x"
            
            playerBridge.setSpeed(currentSpeed)
        }

        /**
         * 长按临时切到 [boostSpeed]（3 倍速），松手由 [endSpeedBoost] 还原。
         *
         * 只改播放器倍速与按钮文案，不动 [currentSpeed]/[currentSpeedIndex]——用户选的档位要保持，
         * 否则长按一次就把底部按钮的倍速档位改掉了。
         */
        private fun startSpeedBoost() {
            if (speedBoosted || isScaling) return
            speedBoosted = true
            playerBridge.setSpeed(boostSpeed)
            buttonSpeed.text = "${boostSpeed}x"

            hideVolumeRunnable?.let { mainHandler.removeCallbacks(it) }
            speedBoostHint.text = activity.getString(R.string.text_shortvideoplayer_speed_boost)
            speedBoostHint.visibility = View.VISIBLE
        }

        private fun endSpeedBoost() {
            if (!speedBoosted) return
            speedBoosted = false
            playerBridge.setSpeed(currentSpeed)
            buttonSpeed.text = "${currentSpeed}x"
            speedBoostHint.visibility = View.GONE
        }

        /**
         * 切换弹幕显示/隐藏
         */
        private fun toggleDanmaku() {
            isDanmakuVisible = !isDanmakuVisible
            if (isDanmakuVisible) {
                danmakuManager?.show()
                buttonDanmaku.setImageResource(R.mipmap.danmakuon)
            } else {
                danmakuManager?.hide()
                buttonDanmaku.setImageResource(R.mipmap.danmakuoff)
            }
        }

        private fun togglePlayPause() {
            if (playerBridge.isPlaying) {
                pause()
            } else {
                resume()
            }
        }

        private fun controlVideo() {
            if (isPlaying) {
                pause()
            } else {
                if (videoNow >= videoAll - 250) {
                    playerBridge.seekTo(0)
                }
                resume()
            }
        }

        private fun changeVolume(addOrCut: Boolean) {
            var volumeNow = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val volumeNew = volumeNow + if (addOrCut) 1 else -1
            if (volumeNew in 0..volumeMax) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeNew, 0)
                volumeNow = volumeNew
            }
            val show = (volumeNow.toFloat() / volumeMax.toFloat() * 100).toInt()

            showSound.visibility = View.VISIBLE
            showSound.text = "音量：$show%"

            hideVolumeRunnable?.let { mainHandler.removeCallbacks(it) }
            hideVolumeRunnable = Runnable { showSound.visibility = View.GONE }
            mainHandler.postDelayed(hideVolumeRunnable!!, 3000)
        }

        private fun applyScale() {
            textureView?.let { view ->
                val matrix = Matrix()
                val pivotX = view.width / 2f
                val pivotY = view.height / 2f
                matrix.setScale(currentScale, currentScale, pivotX, pivotY)
                view.setTransform(matrix)
            }
        }

        fun updateTitle() {
            currentItem?.let { item ->
                textTitle.text = item.title
            }
        }

        private fun toggleBottomControl() {
            if (bottomControl.visibility == View.VISIBLE) {
                hideBottomControl()
            } else {
                showBottomControl()
            }
        }

        private fun showBottomControl() {
            bottomControl.visibility = View.VISIBLE
            activity.isBottomControlVisible = true

            hideBottomRunnable?.let { mainHandler.removeCallbacks(it) }
            hideBottomRunnable = Runnable {
                bottomControl.visibility = View.GONE
                activity.isBottomControlVisible = false
            }
            mainHandler.postDelayed(hideBottomRunnable!!, 10000)
        }

        private fun hideBottomControl() {
            bottomControl.visibility = View.GONE
            activity.isBottomControlVisible = false
            hideBottomRunnable?.let { mainHandler.removeCallbacks(it) }
            hideBottomRunnable = null
        }

        fun setupPlayer(screenW: Int, screenH: Int) {
            val item = currentItem ?: return
            if (item.videoUrl.isEmpty()) {
                CenterThreadPool.run {
                    val success = ShortVideoFeedApi.fetchVideoUrl(item)
                    mainHandler.post {
                        if (success && item.videoUrl.isNotEmpty()) {
                            initPlayer(item, screenW, screenH)
                        } else {
                            lastBufferingVisible = false
                            bufferingIndicator.visibility = View.GONE
                            MsgUtil.showMsg(UiText.of("加载视频失败"))
                        }
                    }
                }
            } else {
                initPlayer(item, screenW, screenH)
            }
        }

        private fun initPlayer(item: ShortVideoItem, screenW: Int, screenH: Int) {
            if (playerReady) return

            // 与 lastBufferingVisible 同步，避免状态收集因“值相同”而不再纠正可见性
            lastBufferingVisible = true
            bufferingIndicator.visibility = View.VISIBLE

            try {
                // 原生库不可用时 createPlayer 返回 false 而不是抛异常（它抛的是
                // UnsatisfiedLinkError，属于 Error，下面这个 catch (e: Exception) 兜不住）：
                // 这里必须提前退出，否则后面 textureView / danmakuManager / setDataSource
                // 全是对着空播放器操作。
                if (!playerBridge.createPlayer(activity, shortVideoOptions)) {
                    lastBufferingVisible = false
                    bufferingIndicator.visibility = View.GONE
                    MsgUtil.showMsgLong(activity.getString(R.string.msg_ijk_native_load_failed))
                    return
                }

                textureView = TextureView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    isOpaque = false

                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            st: SurfaceTexture, w: Int, h: Int
                        ) {
                            playerBridge.setSurface(Surface(st))
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            playerBridge.setSurface(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
                videoContainer.removeAllViews()
                videoContainer.addView(textureView)

                // 初始化弹幕视图
                danmakuView = itemView.findViewById(R.id.danmakuView)
                danmakuManager = DanmakuManager(danmakuView!!).also { manager ->
                    // 弹幕时间轴跟随播放器位置（而不是墙钟），见 DanmakuManager.bindMediaClock
                    // 与 DanmakuFlameMaster 的 MediaClockSync。两个 provider 跑在 DFM 渲染线程上，
                    // 只读主线程采样出来的内存值。
                    manager.bindMediaClock(
                        { if (isPrepared) videoNow.toLong() else -1L },
                        { isPrepared && isPlaying }
                    )
                }
                danmakuManager?.init()

                playerBridge.setOnPrepared {
                    isPrepared = true
                    val st = playerBridge.state.value
                    videoWidth = st.videoWidth
                    videoHeight = st.videoHeight
                    videoAll = st.duration.toInt()
                    progressStr = StringUtil.toTime(videoAll / 1000)
                    // 换了视频要复位进度去重标记，否则新视频的文本要等整秒变化才刷新
                    videoNowLast = -1
                    videoNowLastSec = -1
                    videoProgress.max = videoAll

                    lastBufferingVisible = false
                    bufferingIndicator.visibility = View.GONE
                    coverImage.visibility = View.GONE
                    playIcon.visibility = View.GONE

                    adjustVideoSize(screenW, screenH)
                    playerBridge.setSpeed(currentSpeed)

                    // 只有当前页面活跃时才自动播放，防止弱网延迟准备导致两个视频同时播放
                    if (isActive) {
                        playerBridge.start()
                        isPlaying = true
                        buttonVideo.setImageResource(R.drawable.btn_player_pause)
                        danmakuManager?.resume()
                    } else {
                        // 页面不活跃，暂停准备就绪
                        isPlaying = false
                        buttonVideo.setImageResource(R.drawable.btn_player_play)
                        playIcon.visibility = View.VISIBLE
                    }

                    startStateCollection()
                    // 异步加载弹幕
                    loadDanmaku(item)
                }

                playerBridge.setOnCompletion {
                    // 不再循环当前条：交给 Activity 决定「切下一条 / 相关推荐续接 / 停下」
                    // （旧实现是 seekTo(0) 无限循环同一条，与手机版行为不一致）
                    isPlaying = false
                    buttonVideo.setImageResource(R.drawable.btn_player_play)
                    playIcon.visibility = View.VISIBLE
                    danmakuManager?.pause()
                    activity.onVideoCompleted(boundPosition)
                }

                playerBridge.setScreenOnWhilePlaying(true)

                val headers = HashMap<String, String>()
                headers["Referer"] = "https://www.bilibili.com/"
                headers["Cookie"] = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")
                playerBridge.setDataSource(item.videoUrl, headers)
                playerBridge.prepareAsync()

                playerReady = true

            } catch (e: Exception) {
                Logu.e("ShortVideo", "Player init error: ${e.message}")
                lastBufferingVisible = false
                bufferingIndicator.visibility = View.GONE
                MsgUtil.showMsg(UiText.of("播放器初始化失败"))
            }
        }

        /**
         * 异步加载弹幕数据。
         *
         * **优先走新版 protobuf 分段接口**：`PlayerApi` 给短视频设的弹幕地址是
         * `https://comment.bilibili.com/{cid}.xml`（旧版 XML 接口），B站已基本停用该接口，
         * 这正是短视频"一直没有弹幕"的根因——普通播放器早已切到 `downdanmuNew()` 的分段接口，
         * 短视频没跟上。分段接口确实拿不到数据时才回退旧版 XML。
         */
        private fun loadDanmaku(item: ShortVideoItem) {
            if (isLoadingDanmaku) return
            if (item.aid <= 0 || item.cid <= 0) return

            isLoadingDanmaku = true
            CenterThreadPool.run {
                try {
                    // 短视频时长通常几十秒到几分钟；取不到时长时按 10 分钟算，只会多请求 1 个分段
                    val durationSec = (playerBridge.state.value.duration / 1000).toInt()
                    val segments = DanmakuApi.getAllVideoDanmaku(
                        item.aid, item.cid, if (durationSec > 0) durationSec else 600
                    )
                    if (segments.isNotEmpty()) {
                        mainHandler.post { danmakuManager?.loadFromProtobufSegments(segments) }
                        return@run
                    }
                    loadDanmakuFromXml(item.danmakuUrl)
                } catch (e: Exception) {
                    Logu.e("ShortVideo", "弹幕分段加载失败，回退旧版: ${e.message}")
                    try {
                        loadDanmakuFromXml(item.danmakuUrl)
                    } catch (e2: Exception) {
                        Logu.e("ShortVideo", "弹幕加载失败: ${e2.message}")
                    }
                } finally {
                    isLoadingDanmaku = false
                }
            }
        }

        /** 旧版 XML 弹幕回退路径。 */
        private fun loadDanmakuFromXml(danmakuUrl: String) {
            if (danmakuUrl.isEmpty()) return
            val response = NetWorkUtil.get(danmakuUrl, NetWorkUtil.webHeaders)
            val body = response.body
            if (body != null) {
                // 解压弹幕数据
                val decompressed = NetWorkUtil.decompress(body.bytes())
                val inputStream = java.io.ByteArrayInputStream(decompressed)
                // 必须回主线程：DanmakuManager.createParser 内部用的是进程级单例
                // BiliDanmakuLoader（dataSource 是它的实例字段），并发调用会互相覆盖；
                // 真正的 XML 解析是懒执行的，在 DanmakuView 自己的渲染线程上做，不在这里
                mainHandler.post {
                    danmakuManager?.loadFromXmlInput(inputStream)
                }
            }
            response.close()
        }

        private fun startStateCollection() {
            stateJob?.cancel()
            stateJob = playerScope.launch {
                playerBridge.state.collect { st ->
                    // 播放准备就绪后，用桥接层的缓冲/播放状态驱动缓冲指示器
                    if (st.isPrepared) {
                        val bufferingVisible = st.isBuffering && !st.isPlaying
                        // 只有目标可见性变化时才动 View，避免每 250ms 一次多余的调用与布局失效
                        if (bufferingVisible != lastBufferingVisible) {
                            lastBufferingVisible = bufferingVisible
                            bufferingIndicator.visibility =
                                if (bufferingVisible) View.VISIBLE else View.GONE
                        }
                    }
                    // 进度更新（替代原来的 Timer 轮询）
                    if (isPrepared && isPlaying && !isSeeking) {
                        val pos = st.currentPosition.toInt()
                        if (pos != videoNowLast) {
                            videoNowLast = pos
                            // 弹幕时钟以 videoNow 为时间源（见上面的 bindMediaClock），
                            // 这里只需保证它是最新采样值，**不再做周期性 seekTo 吸附**
                            // —— 吸附会清空屏上弹幕，表现就是「弹幕闪一下又没了」。
                            videoNow = pos
                            // 进度条每 250ms 刷（要平滑），但文本一秒才变一次，
                            // 所以只在整秒变化时才做字符串拼接与 setText
                            videoProgress.progress = pos
                            val sec = pos / 1000
                            if (sec != videoNowLastSec) {
                                videoNowLastSec = sec
                                textProgress.text = StringUtil.toTime(sec) + "/" + progressStr
                            }
                        }
                    }
                }
            }
        }

        private fun adjustVideoSize(screenW: Int, screenH: Int) {
            if (videoWidth == 0 || videoHeight == 0) return

            val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
            val screenRatio = screenW.toFloat() / screenH.toFloat()

            val params = textureView?.layoutParams as? FrameLayout.LayoutParams
            params?.let {
                if (videoRatio > screenRatio) {
                    it.width = screenW
                    it.height = (screenW / videoRatio).toInt()
                } else {
                    it.width = (screenH * videoRatio).toInt()
                    it.height = screenH
                }
                it.gravity = Gravity.CENTER
                textureView?.layoutParams = it
            }
        }

        fun play() {
            playerBridge.start()
            isPlaying = true
            buttonVideo.setImageResource(R.drawable.btn_player_pause)
            playIcon.visibility = View.GONE
            danmakuManager?.resume()
        }

        fun pause() {
            try {
                playerBridge.pause()
            } catch (e: Exception) {
                Logu.e("ShortVideo", "暂停播放器异常: ${e.message}")
            }
            isPlaying = false
            try {
                buttonVideo.setImageResource(R.drawable.btn_player_play)
                playIcon.visibility = View.VISIBLE
            } catch (e: Exception) {
                Logu.e("ShortVideo", "更新UI异常: ${e.message}")
            }
            try {
                danmakuManager?.pause()
            } catch (e: Exception) {
                Logu.e("ShortVideo", "暂停弹幕异常: ${e.message}")
            }
        }

        fun resume() {
            if (isPrepared) {
                // 已经播到结尾（自动接续失败停住的情况）：先回到开头，否则 start() 会立刻再次触发完成
                if (videoAll > 0 && videoNow >= videoAll - 250) {
                    playerBridge.seekTo(0)
                    danmakuManager?.seekTo(0)
                }
                playerBridge.start()
                isPlaying = true
                buttonVideo.setImageResource(R.drawable.btn_player_pause)
                playIcon.visibility = View.GONE
                danmakuManager?.resume()
            }
        }

        /**
         * 设置页面活跃状态。当页面变为活跃时恢复播放，变为非活跃时暂停。
         */
        fun setActive(active: Boolean) {
            isActive = active
            if (active) {
                if (isPrepared && !isPlaying) {
                    playerBridge.start()
                    isPlaying = true
                    buttonVideo.setImageResource(R.drawable.btn_player_pause)
                    playIcon.visibility = View.GONE
                    danmakuManager?.resume()
                }
            } else {
                if (isPlaying) {
                    playerBridge.pause()
                    isPlaying = false
                    buttonVideo.setImageResource(R.drawable.btn_player_play)
                    playIcon.visibility = View.VISIBLE
                    danmakuManager?.pause()
                }
            }
        }

        fun isPlaying(): Boolean {
            return isPlaying
        }

        fun releasePlayer() {
            stateJob?.cancel()
            stateJob = null

            try {
                danmakuManager?.release()
            } catch (e: Exception) {
                Logu.e("ShortVideo", "释放弹幕异常: ${e.message}")
            }
            danmakuManager = null
            danmakuView = null

            try {
                playerBridge.release()
            } catch (e: Exception) {
                Logu.e("ShortVideo", "释放播放器异常: ${e.message}")
            }
            playerReady = false
            textureView = null
            isPrepared = false
            isPlaying = false
            isActive = false
            currentScale = 1.0f
            // 若在"转圈可见"状态下被回收，必须同步复位，否则指示器会一直转到下次 initPlayer
            lastBufferingVisible = false
            bufferingIndicator.visibility = View.GONE
            videoNowLast = -1
            videoNowLastSec = -1
            
            hideBottomRunnable?.let { mainHandler.removeCallbacks(it) }
            hideBottomRunnable = null
            hideVolumeRunnable?.let { mainHandler.removeCallbacks(it) }
            hideVolumeRunnable = null
            
            videoContainer.removeAllViews()
        }

        fun updateVideoSize(screenW: Int, screenH: Int) {
            if (isPrepared) {
                adjustVideoSize(screenW, screenH)
            }
        }
    }

    companion object {
        private const val TAG = "ShortVideoAdapter"

        // 短视频专属播放器选项：加速加载、减少缓冲延迟（与迁移前 setOption 块保持一致）
        private val shortVideoOptions = listOf(
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 1),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek"),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 10L * 1000 * 1000),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "addrinfo_timeout", 5L * 1000 * 1000),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 8 * 1024 * 1024),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 5),
            // 原有一条 "max_cached_duration" = 3000 已删：实测本工程两个 .so（libijkplayer /
            // libijkffmpeg，arm64-v8a）里都找不到这个选项名，设了也不会生效，留着只会误导后来人
            // （依据见 docs/superpowers/specs/2026-10-11-player-network-resilience.md 的 §2.4）。
            // 也不再设 infbuf=1：它是给"实时流"准备的（ff_ffplay_options.h:79-80），会短路
            // max-buffer-size 与 min-frames 两道闸门、让缓存无上限（同文档 §1 第 5 条）。短视频是
            // 点播语义，保留上面的 8MB 上限，宁可多等一点也不要内存随播放时长上涨。
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", NetWorkUtil.USER_AGENT_WEB),
            IjkOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48)
        )
    }
}