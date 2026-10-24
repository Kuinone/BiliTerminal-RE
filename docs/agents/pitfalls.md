# 改前必查的已知坑

> 触发：准备改 API 解析 / 通知 / 下载 / 播放器 / 弹幕 / 清单 / 列表 adapter / 跨包 id / 伪装与顶栏手势。**完整清单见 `docs/architecture-map.md` 第 7 节**；这里是仍存在、且踩过就会出事的那批，每条都标了实测/线上事故依据。

## API 与数据

- 视频卡片解析**重复 21 处**（`RankingApi`/`RecommendApi`×4/`WatchLaterApi`/`SearchApi`×3/`SeriesApi`/`FavoriteApi`×2/`UserInfoApi`/`HistoryApi`/`BangumiApi`/`MessageApi`×3/`DynamicApi`×2/`VideoInfo.java`），改一处要 grep 其余。旧文档写「7 份」、26.08 快照写「19 处」，26.10.04 实测 21 处（`SearchApi` 的番剧搜索分支与 `DynamicApi:826` 为漏计项）。
- `PlayerApi.java:308` 的 `fnvar` 应为 `fnver`；`DanmakuApi.java:93` 的 `segment_index` 起始值与注释不符（`:80` 的 javadoc 说从 0 开始，调用点 `:133`/`:144` 实际从 1 起）。
- `SettingsKeys.PLAYER` / `PLAY_QN` 已收敛（26.10.04 批次 4）：13 处字面量全部改调常量、`SharedPreferencesUtil.player` 死字段已删，**新代码不要再写 `"player"` / `"play_qn"` 字面量**；`SettingMainActivity.kt:118` 的 `"player"` 是分组 id 不是 SP 键，别顺手替换。其余键（`mid` 等）仍有字面量（详见 `docs/architecture-map.md` §7.12）。
- **`model/Dynamic` 的 `stats` 可空，用前必须判空**（26.10.05 线上崩溃）：`DynamicApi.analyzeDynamic` 在 `DYNAMIC_TYPE_NONE`（动态已删除/被屏蔽）时会**在填充 `module_stat` 之前提前 return**，部分动态类型也没有 `module_stat`，所以 `stats` 会是 null（`userInfo` 则无条件赋值，非空）。渲染卡片时 `DynamicHolder` 自己判空，详情页这类「直接取字段」的消费点要调 `dynamic.ensureDetailFields()`（只补 null、不覆盖已有值）。**别在 `analyzeDynamic` 里统一补空 `Stats`**——`DynamicHolder` 正是靠 `stats == null` 来隐藏点赞区的。
- **私信发图（`msg_type=2`）的 content 有硬格式**（26.10.04 批次 5 新增）：`url` 必须是 B 站图床地址（否则 21037「图片格式不合法，不要调戏接口啦」），`width`/`height` 必带（缺了接口不报错、客户端显示异常），`size` 单位是 **KB**。组装一律走纯函数 `PrivateMsgApi.buildImageContent(...)`，别手拼 JSON（详见 `docs/architecture-map.md` §7.14）。
- **`model/` 里 `VideoFolder`/`VideoMeta`/`LocalVideo` 实现 `Parcelable`，且字段名就是磁盘 JSON 存储格式**：删改字段必须两端同步。

## 基类与列表

- `BaseActivity.kt:282` 用 `if (this !is InstanceActivity) setTopbarExit()` 做**向下判断**——任何「让 `RefreshMainActivity` 继承 `RefreshListActivity`」的方案都会把顶栏行为从「打开菜单」变成「点击即返回」。
- **`cell_video_list` / `cell_dynamic_video` 的 id 是跨包事实协议**：改 id 会同时打破 `PrivateMsgAdapter`、`DynamicHolder`、`NoticeHolder`、`OpusContentAdapter`。
- **`notifyItemRangeInserted` 的起点由 adapter 的 `getItemCount()` 决定，`+1` 多数是"头部占位"**：`ReplyAdapter` / `UserDynamicAdapter` / 系列详情内部 adapter 的 `getItemCount()` 都是 `data.size + 1`，通知起点要 `sizeBefore + 1`；而 `VideoCardAdapter` / `ArticleCardAdapter` / `LiveCardAdapter` 无头部、起点就是 `sizeBefore`。**看到 `+1` 先读 `getItemCount()` 再判越界**（26.10.04 批次 3 修掉 4 处真 bug，详见 `docs/architecture-map.md` §7.9）。
- **设备档位参数一律从 `PerformanceManager` 取**（图片质量/宽度、分页大小）：`GlideUtil` 里的 `QUALITY_*` / `MAX_W_*` 四个常量已于 26.10.04 删除，**不要在任何调用点重新写死**，否则又变回"两处真相"（详见 `docs/architecture-map.md` §7.10）。
- **后台请求回主线程时，判活要写在 `runOnUiThread` 的 Runnable 内部**，且 fragment 侧要自己补 `isAdded && view != null`（`BaseFragment.runOnUiThread` / `SearchFragment.runOnUiThread` 自 26.10.20 起已在 Runnable 内部统一判活，但**直接 `CenterThreadPool.runOnUiThread { }` 的地方仍要自己补**，别指望投递时判活）。这是线上 NPE #43–#49 与 ISE #62 的形态：手机/手表都会中，与设备无关。**fragment 里取界面文案优先 `getAppContext().getString(...)`**：detached fragment 上调 `Fragment.getString(...)` 会抛 `IllegalStateException: Fragment … not attached to a context.`（#62 的栈就是 `getString→getResources→requireContext`），详见 `docs/architecture-map.md` §7.44。
- **搜索页的搜索框是「盖」在 fragment 上方的，别把新控件加进 `fragment_simple_refresh` 顶部**（26.10.24 实测）：`activity_search.xml` 里 `ViewPager` 只对齐到 `@id/top` 下方，而 `searchbar` 与它同起点、后声明（画在上层）。所以加在 fragment 顶部的任何一行都会**正好压在搜索框里**——排序条最终放在 `activity_search.xml` 的 `searchSortBar`（锚 `searchbar` 下方），fragment 只提供 `sortOptions()` / `currentSortOrder()` / `selectSort()`，控件归宿主。同理：`hasSearched` 之前不显示排序条，滚动收搜索框时排序条要一起收（见 `SearchActivity.refreshSortBar`）。

## 崩溃页 / 通知 / 下载

- **崩溃页跑在 `:error_activity` 独立进程**（26.10.04 起）：`CatchActivity` 不经过 `PerformanceManager.init` / `ErrorCatch.init` / 未读轮询 / 更新检查，加依赖前先确认它不需要这些初始化；判进程用 `BiliTerminal.currentProcessName()`（读 `/proc/self/cmdline`，`minSdk 19` 用不了 API 28 的 `Application.getProcessName()`）。
- **任何"把 APK 交给系统安装器"的路径都必须先过 `ApkVerifier.verify(context, apkFile)`**（26.10.04 批次 4 新增）：现有两条链路（`UpdateManager.downloadApk`→`installApk`、`DownloadActivity.installApk`）都已接线；下载链路上校验失败要**删掉残片**，否则下次 `Range` 续传会把它当成"已下载一部分"。判定为「包名一致 且 签名集合一致」，**读不到签名一律失败关闭**（详见 `docs/architecture-map.md` §7.11）。
- **通知 ID 不要重号**（26.10.04 批次 5 起有四处）：`DownloadService` = 1027、`PlaybackService` = 1028、`util/MsgNotifier.kt` 新消息 = 1029、同文件追番更新 = 1030。**新消息提醒只在"未读变多"时弹一次**（`MsgNotifier.shouldNotify`，纯函数有单测），触发点是 `BiliTerminal` 既有的未读检查，项目**没有也不需要 WorkManager/AlarmManager**；Android 13+ 的 `POST_NOTIFICATIONS` 在消息页申请，未授权时通知静默不弹（不是 bug）（详见 `docs/architecture-map.md` §7.15）。
- **追番更新提醒靠"最新集 id 快照"判据**（26.10.04 批次 5 的 C16）：只看同一部番的 `new_ep.id` 是否变化（不用总集数、更不用服务端的 `is_new`），快照是 `SharedPreferencesUtil.BANGUMI_UPDATE_SNAPSHOT` 里 `{"media_id": new_ep_id, …}` 的整份 JSON；**首次检查只写快照不提醒、这次新追的番不算更新、拉取失败绝不覆盖快照**（改这三条语义前先看 `util/BangumiUpdateChecker.kt` 与 `docs/architecture-map.md` §7.16）。拉取上限 10 页 × 30 条，`ps` 定义域就是 1-30。
- **`DownloadService` 已按关注点拆分**（26.10.04 批次 8 的 E2）：实现分居 `service/download/`——`DownloadPathSpec`/`DownloadProgressMath`（纯函数）、`DownloadProgressStore`（进程级状态，`internal object`）、`DownloadRepository`（DB）、`DownloadNotifier`（通知，普通 class 持 Service 引用），对外 `@JvmStatic` API 一字未改。改下载代码前记住三条：**`start(Long)` 的 `@JvmStatic @Synchronized`（锁 Companion）与 `DownloadProgressStore.speedLock` 是两把不得互换/合并的锁**；`pausedMap` 必须仍是同一个 `ConcurrentHashMap` 实例（`DownloadListActivity` 直接对它 add/remove）；`speedStr`/`isSpeedMode`/`activeDownloadsCount` 刻意保持**非 volatile**（既有数据竞争，别顺手加）（详见 `docs/architecture-map.md` §7.27）。

## 播放器与弹幕

- `player/` 包不是公共层：`VideoPlayerCore.kt` 已是 `IjkPlayerBridge` 的功能超集，但**还不能直接替换**——`PlayerState`/`IjkOption` 仍定义在 `IjkPlayerBridge.kt:17-36`（删旧类前先搬家），且 `VideoPlayerCore.release()` 有一次性 `released` 标志，`onViewRecycled` 后复用会**泄漏 native 播放器**。动手前先决定它的去留，含糊着抽公共层会造出第 3 套实现。
- **手表右滑返回由 `android:windowSwipeToDismiss` 控制**，它在窗口层直接 `finish()`，**不走 `onBackPressed`**，所以只 override `onBackPressed` 拦不住。且 `onCreate` 里的 `setTheme(ColorScheme.themeResId(theme))` 会覆盖清单上声明的 `Theme.NoSwipe*` —— 要真正禁用它，必须调 `ColorScheme.themeResId(theme, noSwipe = true)` 换用 `Theme.*.NoSwipe.AppCompat`（issue #1 的根因）。26.10.22 起**播放页 `PlayerActivity` 无条件走 noSwipe**（用户要求：播放页禁止右滑退出，不设开关）；其他页面仍由「禁用返回键」或各自的 `forceNoSwipeTheme` 决定。noSwipe 只换窗口属性，不影响应用自己处理的手势（滑动调进度、缩放拖动等）。**它对两类「右滑」天然无效，别以为改成 noSwipe 就万无一失**：① 用「模拟按下返回键」实现右滑的手表（设置的「禁用返回键」说明里点名米兔等）——到应用侧就是一个 `KEYCODE_BACK`，与物理返回键不可区分，只有连返回键一起拦才拦得住；② 手机/平板的系统返回手势（窗口属性不参与），要拦得用 API 29+ 的 `setSystemGestureExclusionRects`，仓库目前没用、也没真机验证（26.10.22 用户拍板：这两类都保持现状，播放页返回键仍可退出）。
- **弹幕时间轴只能由播放器位置驱动**（26.10.22）：DFM 原生 `syncTimer()` 用墙钟计时，播放速率不是 1.0x 时会与画面**速率性发散**（实测 0.9x 播放时每 4 秒差 490ms），以前靠周期性 `seekTo` 吸附，而 `seekTo` = 清屏重建，表现就是「弹幕每隔十来秒闪一下又没了」。现在由 `DanmakuManager.bindMediaClock()` 绑 `video_now`（`DanmakuFlameMaster` 的 `MediaClockSync` + `DrawHandler.MediaClock`），**不要再加周期性纠偏**；`MediaClock` 跑在渲染线程，只能读主线程采样好的 `@Volatile` 值，不许调 `currentPosition`（详见 `docs/architecture-map.md` §7.51）。
- **播放页必须自己保亮屏，且用窗口标志**（26.10.22）：长视频页靠 `activity_player.xml` 的 `android:keepScreenOn="true"`，短视频页原先漏了（看到一半熄屏）。新播放页要用 `window.addFlags(FLAG_KEEP_SCREEN_ON)` 并按「当前活跃播放器是否真在播」**整体重算**，`onStop` 兜底清；View 级 `keepScreenOn` 在 RecyclerView item 上清不干净（详见 `docs/architecture-map.md` §7.52）。
- **原生库加载一律走 `player/IjkNativeLibs.ensureLoaded(context)` 并判返回值**（26.10.19 线上崩溃 #48）：`loadLibrariesOnce` / `IjkMediaPlayer()` 失败抛的是 `UnsatisfiedLinkError`（`Error`，`catch (Exception)` 兜不住），且构造内部会二次加载库。该类已做「失败即锁死 + 诊断写 `Logu` + 按隐私开关上报」，`PlayerActivity` / `IjkPlayerBridge` / `VideoPlayerCore` / `ShortVideoPlayerActivity` 均已接线，**新代码不要再裸调 `loadLibrariesOnce` / `IjkMediaPlayer()`**（详见 `docs/architecture-map.md` §7.44）。
- **弹幕 XML 解析是懒执行的**（26.10.20 修）：交给 `DanmakuManager.createParser` 的流不能在该方法返回后关掉——真解析在 `DanmakuView` 渲染线程（`DrawTask.java:283`），`BiliDanmukuParser.parse()` 读到已关闭流会 `return null`，弹幕**静默全空**（26.10.02 的 `use{}` 就这样弄空了所有本地缓存视频的弹幕）。要传流就传 `ByteArrayInputStream`（close 是空操作），或先把内容读进内存（详见 `docs/architecture-map.md` §7.46）。

## 伪装与顶栏手势

> 需求与验收口径见 `docs/superpowers/specs/2026-10-22-disguise-mode.md`。这一节只记「踩过就会出事」的那几条。

- **双击顶栏必须自己接管触摸，别只挂 `OnDoubleTapListener`**（26.10.22）：双击的**第一次抬手已经是一次完整 click**，页面会先 `finish()`，第二次点击根本落不到同一个 View 上。正确做法是 `OnTouchListener` 返回 `true` 消费全部触摸、事件喂 `GestureDetector`，单击 / 长按再用 `performClick()` / `performLongClick()` **回放**给各页面自己装的监听器（实现只有 `util/DisguiseGesture.kt` 一份，详见 `docs/architecture-map.md` §7.53）。**别改成「在单击监听器里判断两次点击」**——第一次点击已经执行完了，那会变成「退出两次」。
- **在 `OnTouchListener` 里做长按计时，`ACTION_DOWN` 必须返回 `true`**（蓝牙伪装页的教训）：返回 `false` 时 View 没成为触摸目标，系统不再派发 `UP` / `CANCEL`，`removeCallbacks` 永远执行不到 → **轻点一下，2 秒后窗口自己 `finish()`**，伪装直接失效（`BluetoothDisguiseActivity.setupExitGesture`）。返回 `true` 时若要保留点击语义，得自己补 `performClick()`。
- **顶栏延迟装载必须封顶**：`asyncInflate` 页面在 `onStart` 时 `findViewById(R.id.top)` 还是 null；但**有 11 个页面布局里根本没有 `R.id.top`**（Download / Catch / Tutorial / TutorialPager / JumpToDialog / InputDialog / CaptchaWebView / QRLoginRegister / ConfirmDialog / Dialog / ListDialog），无上限 `post` 会在这些页面里每帧投递一次、耗电到页面结束。现在封顶 20 次 × 50ms 并在放弃时打日志。**开开关进这些页面只该看到一条日志**，多了就是又没封顶。
- **接线点三个，装和摘都要做**：`BaseActivity`（普通页）、`PlayerActivity.onStart`（**不继承** `BaseActivity`）、短视频 `PageHolder.syncDisguiseGesture()`（顶栏在分页 item 里）。只 `install` 不 `detach` 的话，开关关掉后已装上的触摸层不会自己消失，必须重进页面才失效。
- **伪装页的暗号只允许 4~8 位数字**：计算器上敲不出字母，配了 `abc` 就是把自己锁在伪装页里、只能强杀应用。`DisguisePrefs.normalizeCode` 拦一次、`code()` 读取侧再兜一次，**别为了"支持复杂暗号"放开这两个校验**。
