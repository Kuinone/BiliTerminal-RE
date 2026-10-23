# 修复台账（活文档）

> 这是**唯一**的当前状态 + 待办台账。完整历史正文已归档到 `docs/archive/review/fix-progress-history.md`（§一~§四十三 逐批次流水，4777 行）。
> 什么时候写这里：修完一个 bug / 关掉一个待办 / 每轮真机验证后。**别再往下追加长流水**——超过一屏就并进「历史章节索引」那一行。
> 架构与坑看 `docs/architecture-map.md`；功能清单看 `docs/FEATURES.md`；真机清单看 `docs/review/real-device-regression-checklist.md` 与 `docs/review/device-test-plan.md`。

## 1. 当前版本状态

- **版本号**：`26.10.19`（`app/build.gradle` 的 `versionName`），`versionCode 2610200`（26.10.21 合并上游时上游把它从 `2610190` 抬到 `2610200`，`versionName` 未变）。tag `26.10.19` 已占用。
- **上一版**：`26.10.16` / tag `26.10.16`。
- **下次递增**：先看 `app/build.gradle:23-24`；release immutability 下被 Release 用过的 tag **永久不可复用**，同版本名补丁版只能用 `26.10.19.1` 这种后缀形式，`versionCode` 递增、`versionName` 可不变。改了版本号还要同步 `strings.xml` 里 `update_log_current` 的「【YY.MM.DD 本次更新】」锚点，否则 `:app:verifyVersionConsistency` 会失败。
- **构建命令**（单测任务名**带 flavor 前缀**，`testDebugUnitTest` 自 26.10.11 引入 flavor 后已不存在）：

  ```text
  .\gradlew.bat :app:assembleDebug
  .\gradlew.bat :app:testStandardDebugUnitTest
  .\gradlew.bat :app:testCoexistDebugUnitTest
  .\gradlew.bat :app:assembleRelease
  ```

- **JDK**：需要 17。仓库 `gradle.properties` **不再写死** `org.gradle.java.home`（写死的机器路径换机即报 `Value '...' is invalid`），本机 JVM 不是 17 时在**用户级** `~/.gradle/gradle.properties` 里设，或让 `JAVA_HOME` 指向 17。
- **SDK**：`compileSdk 34` 需要 `platforms/android-34` 与 `build-tools/34.0.0`；缺了 AGP 会在配置阶段自动下载（licenses 已接受时），也可用 `sdkmanager` 预装。`local.properties` 里写 `sdk.dir`，该文件被 gitignore。
- **依赖仓库**：`settings.gradle` 已把 aliyun 的 `google` / `public` / `gradle-plugin` 三个镜像前置，`google()`/`mavenCentral()`/jitpack 仅作兜底。
- **改了 `res/` 文件集合时**：`:app:clean` 与后续命令**必须分两次 Gradle 调用**，且带 `--no-build-cache --no-configuration-cache`，否则会回放陈旧资源（症状是 `Unresolved reference 'R.layout.xxx'`）。
- **版本一致性**：`:app:verifyVersionConsistency` 以 `strings.xml` 的 `update_log_current` 为锚点校验版本一致性；发版说明也由 CI 从它抽取（抽不到直接拒绝发版）。
- **单测（静态计数，26.10.23 合并上游后实测）**：**79 个测试类 / 840 个用例**，两个 flavor 完全相同（同源码），0 失败 0 错误 0 跳过。计数方式：`app/src/test/` 下 `*Test.kt|java` 计数（另有共享假实现 `util/FakeSharedPreferences.kt`），`app/build/test-results/testStandardDebugUnitTest/TEST-*.xml` 的 `tests=` 求和。用例数一律以机器产出为准，队友口述数字不进入文档。
- **26.10.10 那一轮的构建记录（历史，留作「改 minSdk / 重跑 release 链路」时的验证口径）**：`minSdk 24 → 19` 全部改动（含 jsoup 1.16.1、7 个 `res/drawable-v21` 资源）落盘后，按「`clean` → `assembleRelease` → `lintRelease` → 单测」四步跑完（均带 `--no-configuration-cache`，`clean` 与 `assemble` 分两次调用），四步全 `BUILD SUCCESSFUL`（37s / 2m13s / 1m25s / 39s）。当时单测汇总 **49 suites / 460 tests / 0 failures**（任务名当时还是 `testDebugUnitTest`，现已带 flavor 前缀），最新 XML 时间 `2026-10-09T22:59:04`（晚于当轮 APK 出包时间 22:56:58，覆盖了当轮全部源码改动）。`:app:lintRelease` 的 **`NewApi` 从 22 条降到 2 条**，且这 2 条就是 `PlayerActivity.kt:1981` 的已知假阳性。发版前仍建议再跑一次确认。
- **「自动跳过片头片尾」已恢复上线（26.10.22）**：26.10.10 的紧急撤回**已解除**，`SkipOpEdPrefs.FEATURE_ENABLED` 回到 `true`，设置入口与搜索索引均已恢复；口径按用户拍板改为**默认关闭**（`DEFAULT_ENABLED = false`，老用户存档里显式写的值仍然优先）。恢复细节见 §3.3「恢复记录」，乱跳的三个根因修复见 §3.55。
- **本轮基建改动（26.10.10）**：**`minSdk 24 → 21 → 19（Android 4.4）`**，唯一落点是 `app/build.gradle:21`。两级下调的成本不同，分开记：
  - **24 → 21**：代码侧清掉**四处** API 24 才有的调用：`util/AsyncLayoutInflaterX.java` 的 `java.util.function.BooleanSupplier` → 工程内 `CancelledChecker`；`util/SharedPreferencesUtil.java:167` 的 `java.util.function.Consumer` → 工程内 `EditorAction`；`activity/reply/WriteReplyActivity.kt:177` 的 `Map.getOrDefault` → `msgMap[k] ?: 默认值`；`util/VideoPreloadManager.kt:12` 的 `ConcurrentHashMap.newKeySet<Int>()` → `Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())`（**这一处是会真崩的**：类一初始化就 `NoSuchMethodError`，且已确认留在 release dex 里）。两个调用点（`activity/base/BaseActivity.kt:435`、`util/AccountManager.java:202`）走 SAM 转换，未改。
  - **21 → 19**：依赖必须降两处——`androidx.appcompat:appcompat` 1.7.0 → **1.6.1**、`androidx.fragment:fragment-ktx` 1.8.0 → **1.6.2**（只有这两代 AAR 自己还声明 minSdk 14；1.7.0 / 1.8.0 声明的是 21，`checkReleaseAarMetadata` 会硬失败），并**恢复** `androidx.multidex:multidex:2.0.1` + `BiliTerminal.kt` 的 `attachBaseContext()` 里 `MultiDex.install(this)`——26.09.10 那次「minSdk 21 下是死代码」的删除被整体回滚。改回 21 时这三件事要一起撤。
  - **21 → 19 又清掉一批 API 21 调用点**（按 `:app:lintRelease` 的 `NewApi`，`current min is 19`）：`Build.SUPPORTED_ABIS` 三处（**静态数组字段**，低版本一执行即 `NoSuchFieldError`，而它是 `Error`，`catch (Exception)` 兜不住）统一改走 `api/TerminalApi.java` 新增的 `firstAbi()` / `supportedAbis()`（`SDK_INT >= LOLLIPOP` 闸 + 回落 `Build.CPU_ABI`）；`ui/widget/TextClock.kt` 的 `@JvmOverloads` 主构造会经 4 参 `TextView` 构造（API 21），而全工程 **43 个布局**都 inflate 它 → 改成三个显式次构造；`activity/settings/TutorialManagerActivity.kt:195/199` 的 `View.setBackgroundTintList` → `ViewCompat.setBackgroundTintList`；7 个矢量图（`icon_announcement`/`icon_download`/`icon_home`/`icon_nav_dynamic`/`icon_nav_media`/`icon_nav_trending`/`icon_update`）的 `android:tint="?android:attr/…"`（pre-21 解析不了）→ `res/drawable/` 改固定色，**带 `?attr` 的正本原样挪到 `res/drawable-v21/`（两份必须一起改）**。
  - **依赖侧一处真改动（24 → 21 阶段）**：`org.jsoup:jsoup:1.17.2` → **`1.16.1`**。1.16.2 起 jsoup 的 `StringUtil` 在**静态初始化块**里调 API 24 才有的 `ThreadLocal.withInitial(java.util.function.Supplier)`，而 `Jsoup.parse()` 必然用到它，minSdk 19/21 上会 `NoSuchMethodError`。**这不是推测**：把出包的 `classes.dex` 反汇编后确认 1.17.2 的 `withInitial` 就在 `<clinit>` 里，降到 1.16.1 后 dex 中 `java/util/function/` 与 `withInitial` 归零。**jsoup 不要再往上升**。
  - **验证方式**：不只看源码，还把**出包的 `app-universal-release.apk` 的 `classes.dex` 反汇编**逐条归属到类（判据与工具坑见 `docs/architecture-map.md` §1.1 末尾）。定稿包的 dex 实测：`newKeySet` / `KeySetView` / `java/util/function/` / `withInitial` **全为 0**，`newSetFromMap` 为 1；残余 `java/util/stream/` 1 与 `java/time/` 2 已追溯到「只被带 `SDK_INT` 闸或 `@IgnoreJRERequirement` 重载调用」的位置。
  - `:app:lintRelease` 另外两类输出**不改**：`InlinedApi`（常量已内联进字节码，旧系统只是忽略，如 `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` / `MotionEvent.AXIS_SCROLL`）与 `ObsoleteSdkInt`（现在恒真的 `SDK_INT` 分支）。`activity/player/PlayerActivity.kt:1981` 的 `MediaSession.Callback` 是 **lint 假阳性**——所在 `private fun initMediaSession()` 第一行就是 `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return`。
  - **只对 release 包负责**：`jvmTarget 17` 下 Kotlin 会给 21 个数据类的 `hashCode()` 生成 `java/lang/Long.hashCode(J)` 之类的 API 24 调用，release 里被 R8 全部消除（dex 零命中），**debug 包才有**。本分支只发 release，故不动；要发 debug 包必须先处理这一条。
  - **完整兼容清单（含「再往下调会硬失败」的依赖下限、dex 级审计出的第三方库 API 24 引用与为什么可以不管）见 `docs/architecture-map.md` §1.1**，本文件不复述。

## 2. 未完成待办

### 2.1 真机未验证（无设备 / 无登录态）

- [ ] **登录页扫码中途退出的竞态（§3.38）真机未复现**：修法是「判活搬进 Runnable + 文案走 application context」，判据已由 R8 mapping 还原（崩点 `QRLoginFragment.kt:284`）。真机用例：扫码页在「已扫码，等待手机确认」时点「跳过 / 返回」/ 按表冠退出，反复若干次，不应再出 `Fragment … not attached to a context`。
- [ ] **缓存页空态行的真机观感（§3.34）**：只在 380×380 表盘模拟器上跑过，未在真手表上确认「未分类视频 0 个 + 啥都木有~」两行的字号/间距是否合适。

- [ ] **缓存页长按菜单的真机手感（§3.35）**：模拟器上长按 400ms 即触发；真手表需确认「长按播放/进文件夹」不会与长按误触冲突、5 项菜单在 45% 滚动区里滑动是否顺手、两侧约 24px 空白点不点得到。

- [ ] **本地音轨防重（§3.13）**：含内嵌音轨 MP4 + 残留 M4A 只播主音轨；纯视频 DASH + M4A 有声；纯音频不重复；反复切听视频/重试不叠声；暂停/恢复/拖进度正常；退出后静音；错误音频不崩。需用 Debug 包观察 `AudioTrack` 日志确认 IJK 轨道列表，UNKNOWN 应提示。下载双文件替换与音频专用模式残留问题仍待修，不能当本次已闭环。

- [ ] **详情页中央加载遮罩收起时机（§3.11）待真机弱网复测**：用弱网（限速/弱信号）打开视频详情页并立即切到评论栏，评论出现后遮罩必须已消失且不挡触摸；再验番剧详情页的"敬请期待"番（无可用季/集）不再永久停在加载图。纯 UI 时序，JVM 单测覆盖不到。

- [ ] **双击缩放复位与旋转解耦（§3.9）待手表真机确认**：开启优先恢复后，默认竖屏→横屏→双击与默认横屏→竖屏→双击均应暂停且不变方向；再验证放大后双击仅复位且不变方向、第二次双击暂停。覆盖双击快进开/关（开时用中间区域）、系统旋屏/软件旋屏、TextureView/SurfaceView；关闭优先恢复时放大后双击也应暂停。

- [ ] **后台播放前台服务崩溃修复（§3.6）真机未复现**：代码已过两 flavor 构建 + 各 478 例单测，但未在低内存手表上跑过。触发用例：起后台播放后 5 秒内切回前台、快速进出播放页，反复若干次，观察是否再出 `RemoteServiceException: Context.startForegroundService() did not then call Service.startForeground()`。
- [ ] **评论带图 12066**：`img_size` 小数截断是已确证的确定性缺陷，但**与 12066 的因果关系未建立**。
  - 12066 在本地快照与上游 collector 均无文档（`bilibili-API/docs/comment/action.md` 错误码表只到 12001-12052）。
  - 验证方法：真机在 `ReplyApi.sendReply` 失败分支打印**完整响应体**取回 `message` 原文。§二十九(第二处) / §36.5
- [ ] **笔记「无法正常加载」根因未确证**：已排除 csrf、`note_id_str` 精度设计、UI 层。
  - 仍存假设：H1「服务端按 `num` 解析 17 位 `note_id` 丢精度 → 79502」、H2「空 `csrf` 被当参数错误」。**头等未验证项**。
  - 验证方法：同一 id 分别按字符串 / 按 num 各发一次对比。§29.1 / §29.5 / §36.5
  - `api/NoteApi.java:259-276`（getNoteInfo 不带 csrf，与 `bilibili-API/docs/note/info.md:65-71` 一致）
  - 未改清单：`note_id` 传参方式一行未动；`activity/video/` 下无任何写入（`VideoInfoFragment.kt:563-569`）
- [ ] **稍后再看「未看完」第二层待真机**：本地判据已修，但服务端 `viewed=2` 的真实语义未确认。若仍为空 → 抓 `history/toview/web` 的请求 `viewed` 值与响应体。§三十五 / §36.5
- [ ] **收藏夹排序行**：逐条排查后**未发现可修缺陷**（两处入口汇合到同一个 `FavoriteVideoListActivity`）。
  - 真机排查指令：`adb logcat -s "debug-收藏夹排序"`，正常应 `sortBar.visibility=0 mediaId=<非0> fid=<非0> readOnly=false`。
  - 临时诊断埋点在 `activity/user/favorite/FavoriteVideoListActivity.kt` 的 `setupSortBar()` 末尾，倾向保留。§二十二 / §36.5
- [ ] **关注分组移动成员未真机验证**：`FollowGroupAdapter.getUserOwnerGroup(position)` 的位置算术只做过静态复核。§三十六 / §36.5
- [ ] **片头片尾跳过**：跳过逻辑本身（`player/ViewPointSkip.kt`）一行未动，行为改变全在默认值 / 引导判据上；真机清单第 199/200 条未跑（且 §四十三 已整体撤回）。§三十三 / §36.5
- [ ] **内置播放器「切后台回来视频位置出错」代码已修，真机只跑到「未复现」**（26.10.11 用户报告；同日已在 vivo 手机上真机跑过，见下方「真机记录」——本机触发条件不成立，**不等于修复已被证实**）。
  - 机制①（**已修**）：`activity/player/PlayerActivity.kt` 原本**没有** `onSaveInstanceState`/`onRestoreInstanceState`，Activity 被系统回收后重建只能回退到**进入页面那一刻**的 Intent `progress`（`:372` 读、`:3553-3560` `seekTo(progress_history)`）。现加状态保存/恢复（`STATE_POSITION` + `restoredFromSaveState`；恢复的那次不再弹「已从上次的位置播放」）。
  - 机制②（**已修**）：`:2138` 的默认显示方式为 SurfaceView（`player_display` 默认 `SDK_INT < 26`，手表多为 API≥26），切后台回来 Surface 必被销毁重建 → `:2127-2133` 的 `onSurfaceReattached` 用**UI 进度条的值** `seekTo(seekbar_progress.progress)`；叠加 `enable-accurate-seek` 默认 0（不精确 seek）⇒ **每次回前台往回跳一个关键帧间隔**（数秒），反复切会累积。现改为 `seekTo(video_now)` 并加 `!isLiveMode` 守卫，点播开启 `enable-accurate-seek=1`；直播下该进度条恒为 0（`:1190` 只在非 live 分支赋值）的问题随之消失。
  - **遗留未修**：链路页 `activity/video/JumpToPlayerActivity.kt` 的 `onCreate` 仍无 `savedInstanceState` 守卫，系统回收后会重新取流并再 `launch` 一次（不直接造成位置错，但会白跑一次取流）。
  - 真机判据（埋点已在代码里，无需另加）：`adb logcat -s "PlayerActivity>onCreate" "PlayerActivity>onPrepared"` 应看到「恢复位置」+「进度跳转」；`PlayerActivity>checkStall` / `PlayerActivity>switchLiveCandidate` / `PlayerActivity>getExtras`（「直播候选」）对应看门狗与线路轮换。用例见 `docs/review/device-test-plan.md` D3-11~D3-15。
  - **真机记录（26.10.11，设备 vivo `V2229A` / PD2229，Android 16 / SDK 36，arm64-v8a，1080×2520，序列号 `10AC9S2M4L000QL`）**：`adb` 深链 `bilibili://video/170001` → 详情页 → 播放 → 分 P → `PlayerActivity` 正常起播（画面 + 弹幕 + 进度文本，例 `00:12/03:19`）。① 熄屏再亮屏：`00:56 → 00:58`，无回跳；② 把详情页压到播放器之上再返回：无回跳。**但同样的两次操作在修复前的旧 release 包上也复现不出回跳**，说明这台手机上原始触发条件不成立——熄屏不销毁 SurfaceView 的 surface，按 HOME 切走后 vivo 会把任务冻结/回收（再回来落在首页而不是播放器）。⚠️ 装的都是 **release 包**，而 `Logu` 的开关默认只在 `BuildConfig.DEBUG` 打开（`BiliTerminal.kt:228-230`），所以本机 logcat 里**看不到任何 `PlayerActivity>` 埋点**——上面的 logcat 判据必须在 debug 包上执行。
  - 调研与修法见 `docs/superpowers/specs/2026-10-11-player-network-resilience.md` P1-1 / P1-2。§四十四
- [ ] **评论侧「长按双弹窗」未复现**（`res/layout/cell_reply_list.xml` 兄弟节点分析证明布局层面不可能）。§30.2 / §30.4
- [ ] **`MANAGE_DEDUP_MS = 400L` 去重未真机验证**：`app/build.gradle` 只有 junit 与 org.json，**无 Robolectric**，`SystemClock` 无法进 JVM 单测。§30.3 / §30.4
- [ ] **300×300 表盘上弹窗实际可点性未在真机确认**。§30.4
- [ ] **未决项：`activity/reply/ReplyInfoActivity.kt` 分页插入起点 `+2` 疑似越界** ——「发现但未修改」。
  - 建议单独立项 + 真机复现（楼中楼详情页 → 切时间排序 → 持续下滑翻页 → 观察 `IndexOutOfBoundsException` / `Inconsistency detected` / 新页错位）。
  - 判越界前必须先确认**宿主页第 0 位放了什么**：同一个 `ReplyAdapter`，在 `activity/reply/ReplyFragment.kt:250`（主评论列表）第 0 位是「头部占位」，在 `ReplyInfoActivity`（楼中楼详情页）第 0 位却是**根评论**，前提正好相反。§30.5b
- [ ] **动态详情页线上 NPE 未真机复现**：用户只给了混淆栈、未给触发场景。若补丁后同一栈还在，说明真凶是被 R8 内联进来的别处，需新 mapping 重新 retrace，并附触发页面 + 操作步骤。§38.5 / §38.6
- [ ] **收藏夹顶部工具条三处修法均未真机复现**（团队无设备）：
  - (1) 取消勾选不暗（排除法：`itemView.alpha` 只由 `VideoCardHolder.applySelection()` 写）。
  - (3) 若真机仍在列表中途展开，说明 `recyclerView.canScrollVertically(-1)` 不可靠，需改用 `computeVerticalScrollOffset() == 0`。§39.5
- [ ] **滚动收放与长按面板的真机手感未验证**：动画与手势走 View 层，JVM 单测覆盖不到（正因如此才抽出 `util/view/ScrollRetractDecider.kt`）。§37.3 / §37.2
- [ ] **新增反馈页 / 关于页入口 / 崩溃自动上报 / 启动弹窗全部未真机跑过**（条目已补进回归清单第 216~222、223~227 条，**尚未执行**）：
  - ① 关于页 → 反馈页能进、分类、4000 字上限、断网报错文案、成功清空；
  - ② 三个开关关掉后抓包确认无到 `rebiliterminal.zsapp.asia` 的请求；
  - ③ 故意崩溃一次，崩溃页显示「报错ID」且服务端 `/admin/api/crash` 可见；
  - ④ 公告页在上游接口挂掉时仍能显示自建公告（反向也要成立）。正文见 `docs/archive/review/fix-progress-history.md` §41.7。
- [ ] **隐私同意闸门未真机验证**（条目已补进回归清单第 228~231、236 条，**尚未执行**）：全新安装 / 清数据后首次启动必须弹一次；点「不同意」能正常进主界面且抓包无请求。正文见 `docs/archive/review/fix-progress-history.md` §41.8。
- [ ] **minSdk 19 未在 API 19~23 上做过任何运行验证** —— 本机 `D:\Program Files\android-sdk` 下**既无 `emulator` 也无 `system-images`**，本轮 `minSdk 24 → 19` 的结论全部来自静态审计 + 依赖元数据 + lint + dex 反汇编，**没有真机/模拟器背书**。
  - 需覆盖的真机用例已写进 `docs/review/device-test-plan.md`（§1.2 设备侧 + T12 平台 API 兼容）：任一含 `TextClock` 的页面（43 个布局里都有，4.4 上原本会因 4 参 `TextView` 构造直接崩）、列表页 `AsyncLayoutInflaterX.fadeIn`（`activity/base/BaseActivity.kt:435`）、任一写 SharedPreferences 的操作（登录态刷新 / 设置项开关）、发评论或回复（`activity/reply/WriteReplyActivity.kt:177`）、设置页各图标（验证 `res/drawable-v21` 之外的兜底色可接受）、通知（三处 `NotificationChannel` 均有 `SDK_INT >= O` 闸，预期低版本不弹且不崩）。
  - 逐项静态结论与依赖下限见 `docs/architecture-map.md` §1.1。

- [ ] **调试桥（§3.14）未真机验证**：`DebugBridge` 的 `am broadcast` 能否回显结果、`appops set <包名> SYSTEM_ALERT_WINDOW allow` 后 `start` 能否真的拉起 `exported=false` 的页面、`http` 命令在风控下是否可用、日志落盘路径是否可 `adb pull` / `run-as` 取到——全部只过了编译与静态核实。逐条验证清单见 `docs/review/agent-test-guide.md` §2 与 §6。
  - 风险最高的一条：个别精简 ROM 的 `adb shell` 可能不持有 `DUMP` 权限，那样导出组件会被 AMS 拒绝，调试桥整体不可用。兜底方案见手册 §6（`adb root`，或 `run-as <包名> am broadcast`——同 uid 不受导出限制）。

- [ ] **番剧界面补齐（§3.8）未真机验证**：季切 tab、两行选集卡片、追番按钮、全量选集弹窗、历史卡片一键续播、番剧心跳 5 秒 + 看完上报 -1 全部只过了编译 + JVM 单测。真机要看的：① 多季番能切到第二季/剧场版并出集；② 从别的季切回来回到这一季上次看的那一集；③ 一集播完后重进/从历史进来是接下一集而不是停在本集末尾；④ 历史里的番剧卡片点一下直接起播且位置对；⑤ 未登录点追番要提示，不能静默失败。

- [ ] **缩放后左右滑动调进度（§3.32）待真机确认**：开启「左右滑动控制进度」+「视频可缩放」→ 捏合放大 → 松手 → 单指横滑，应完全不出现进度浮层、不动进度条；双指缩回 1 倍后横滑恢复；再验关掉「缩放时可移动」时放大后单指拖动不再移位。判据单测覆盖不到 ACTION_UP 之后的手势分发。

- [ ] **长按顶栏三模式（§3.33）待真机确认**：①「不响应」下普通页面与视频详情页长按都无动作（OPPO 系手表长按顶部本意是系统控制中心，应用侧至少不能自己再动）；②「仅视频详情页」下普通页面无动作、视频详情页仍关闭所有详情页；③「全部页面」= 旧行为；④ 切档后不用退出页面即时生效；⑤ 26.10.19 显式关过旧开关的老用户，升级后设置页显示「仅视频详情页」（不是「不响应」）。

- [ ] **短视频取流协议修订（§3.41）待真机**：本会话无可用设备，只过了两 flavor 构建 + JVM 单测。真机用例：①进入短视频页首屏能出内容、连续上下滑不出现相邻重复视频；②反复退出再进短视频页，首屏正常（旧实现的静态页码已删）；③飞行模式进入 → 出现「获取视频列表失败」，恢复网络后从页面既有入口重试成功；④观察是否还有明显横屏内容（解析层已丢弃 `dimension` 明确宽>高且 `rotate==0` 的卡片）。

- [ ] **缓存视频断点续播（§3.53）待真机**：本机只过了 `:app:assembleDebug` + 两 flavor 单测，位置读写纯函数有 JVM 覆盖，但「退出再进真的接着播」必须真机。用例：① 缓存视频播到约 01:30 退出再进 → 从约 01:30 起播且提示「已从上次的位置播放」（±2s）；② 关掉「从历史位置播放」→ 从头且不提示；③ 看到距结尾 5 秒内 / 播完 → 下次从头；④ 多 P 视频 A 播一半、B 从头 → 各自记住不串；⑤ 单文件、DASH 双文件、听视频（`audio_only`）、虚拟合集连播、多 P 选择页五个入口都验；⑥ 缓存文件夹改名/移动后仍能续播；⑦ 播放中直接杀进程（后台划掉）→ 最多丢 15 秒；⑧ 文件被删/损坏/换成更短的版本 → 静默从头不崩。

### 2.2 功能与工程待办

- [x] ~~**「自动跳过片头片尾」处于撤回状态**~~ ——26.10.22 **已恢复**（默认关闭），恢复清单 ①–④ 已完成，⑤⑥（发版公告与版本号）随下次发版做。§3.3
- [ ] **播放器弱网 / 直播：P0-1、P0-3、P1-1、P1-2、P2-1 与半个 P2-3 已实施，其余待办**（26.10.11 已过编译 + 两 flavor 各 478 个 JVM 单测全绿；真机只验证到「播放器能正常起播、回前台位置未复现回跳」，**看门狗/线路轮换未在真机触发**——release 包 `Logu` 默认关闭，需 debug 包 + 真实直播间）：
  - 已实施：**P0-1** `activity/player/PlayerActivity.kt` 的 `setDisplay()` 给在线流加 `rw_timeout=10s` / `addrinfo_timeout=5s` / `reconnect_streamed=1` / `reconnect_delay_max=5s`；**P0-3** 新增 `checkStall()` 停滞看门狗（直播 20s / 点播 30s 不动就重试，直播切候选线路、点播原地重建；最多 3 次、冷却 15s、位置前进 5s 后复位）；**P1-1** 状态保存恢复；**P1-2** 重挂 Surface 改用 `video_now`；**P2-1** 删 `activity/video/ShortVideoPlayerActivity.kt` 里实测不存在的 `max_cached_duration`；**P2-3 的一半** 新增纯函数 `util/live/LiveStreamSelector.kt`（14 个单测），候选按「FLV 各线路 → HLS」排序，随 Intent `live_url_candidates` 交给播放器做运行时线路轮换。
  - **未做**：P0-2 的「重连前重调 `getRoomPlayInfo` 拿新 URL」（现在只在已到手的候选里轮换，URL 过期后仍会失败）、P1-3 后台保活与音频焦点、P2-2 短视频激进参数复核、`PlayerActivity.kt:892` 的 `dns_cache_clear`（二手证据不足，未动）。
  - **明确不采纳**：P0-4 提高直播默认清晰度（`util/TerminalContext.java:259` 写死 `qn=80`=流畅）——用户诉求是弱网可看，低码率是刻意保守；`reconnect_at_eof` 不开——点播正常播完会被当成 EOF 触发重连，有「永远播不完」的风险。
  - 建议清单见 `docs/superpowers/specs/2026-10-11-player-network-resilience.md` §4；该文档 §2.4 附**本仓库 `.so` 的选项存在性实测**（`reconnect_on_network_error`、`max_cached_duration`、`http-detect-range-support` 均不存在）。§四十四
- [ ] **26.10.05 那个已发布的 Release 说明没能补上**：本机无 `gh` CLI 也无 GitHub token，只能人工在网页端编辑，把 `update_log_current` 的 28 条粘进去。§40.5
- [ ] **服务端没有告警渠道**：只有 systemd `Restart` + `health.log` 留痕（`*/10` 探活 cron 失败只追加一行，要真告警还得接通知渠道）。§42.9
- [ ] **管理控制台只有口令登录**，没有 TOTP / IP 白名单；口令泄露 = 全部后台能力。§42.9
- [ ] **控制台 WebUI 没有真机 / 真浏览器回归记录**（环境无浏览器），只验证了 HTML 结构与接口联通。§42.9
- [ ] **话题广场仍是单页**（`bottom = true`，不翻页）；`page_info.has_more` 已在数据里，将来要翻页可直接接。§三十四
- [ ] **`WriteReplyActivity.kt` 的 `pendingUploads++/--`（`:224`/`:253`）未加同步保护**：已发现主线程入口与后台 `finally` 各改一次、无同步，「与本次用户报的 bug 无关，改动会引入无法验证的风险」→ **发现但不动**。§29.5
- [ ] **§32 遗留的视觉面未动**：
  - 新增 `colorError` 语义色**未重新做对比度测量**（`docs/archive/visual-experience-report.md` §2.25：`#EBE0E2` 叠 `#FF6699` 约 2.1:1）；
  - §2.12「86 处布局引用静态调色板」与 `styles.xml` 组件样式硬编码同源，本次未动。§三十二
- [ ] **收藏夹排序行「更醒目」的视觉标识没做**（无依据，且会动 `res/layout/activity_simple_refresh.xml`）。§二十二
- [ ] **`docs/review/real-device-regression-checklist.md` 第 159 条未改**（不属该轮范围）。§二十二
- [ ] **「个性化设置」开关落点需用户确认**：`long_press_panel_enable` 落在 `activity/settings/SettingPrefActivity.kt`（`setPageName("偏好设置")`，「内容与浏览 → 通用偏好」进入）的「功能」组、紧挨「长按复制」；用户心里的「个性化设置」若是别处，搬走只需改这一行。§37.1
- [ ] **`DynamicApiTest` 曾失败的 2 例（`DynamicApiTest.kt:151`/`:203`）与 `NoteApiTest.kt:198` 1 例**：**已闭环**（§36.3 修掉并全绿），修法见归档「长期结论」；此处仅作登记。
- **功能类 backlog 一律看** `docs/superpowers/plans/2026-08-27-new-features-roadmap.md`，不在本文件展开。

### 2.3 明确不做（别再排期）

- **F4 漫画**：快照 `bilibili-API/docs/manga/` 无任何「追漫/收藏漫画列表」接口，也无漫画搜索接口，项目内漫画实现为零（只有 `activity/user/VipActivity.kt:79-80` 会员权益文案）。
- 用户 26.10.04 明确「漫画算了」，记 **不做**（不是暂缓：暂缓意味着还打算做）。§三十一
- **番剧 B/E 两项（26.10.11 用户拍板暂不做，别顺手补）**：B = 进番剧详情页自动查历史接口定位"上次看到的那一集"；E = 实验室里的「番剧进度诊断页」。同一轮对比里远端的 `BangumiActivity.java`（番剧主页：新番/追番/索引）是**跨全部 tag 都不可达的死代码**，同样不要照抄。§3.8

### 2.4 视觉技术债（26.10.10 从 `docs/archive/visual-experience-report.md` 回迁；数字均为当时 grep 实测）

> 真机验证项已并进 `docs/review/real-device-regression-checklist.md` 第十三节（第 238~247 条）。下面只留「代码里真的还没做」的部分。

- [ ] **顶栏仍是 45 份手抄复制品**：`cell_topbar.xml` 与 `include layout="@layout/cell_topbar"` 全工程 **0 匹配**，`android:id="@+id/top"` 在 **45 个 layout** 里各自手写（`activity_simple_refresh.xml:9`、`activity_menu.xml:10`、`activity_player.xml:144` …），`@dimen/topbar_height`（30dp / w300dp 36dp）**0 引用**（死 token）；退出靠 `BaseActivity.kt:182-197 setTopbarExit()` 代码兜。**批次 4 的公共顶栏成果实际已被回退**（详见 `docs/architecture-map.md` §8.1/§8.5/§7.4），顶部返回是 45 个页面的唯一返回入口、高度无 48dp 保证。值得做（风险中）。
- [ ] **无障碍：`contentDescription` 覆盖率 4.3%**：全 `res/layout` 仅 5 处（`activity_loading.xml:46`、`cell_follow_group.xml:46`、`cell_loading.xml:9`、`fragment_qr_login.xml:57`、`item_vote_option.xml:20`=`@null`），而 `<ImageView|<ImageButton` 共 **115 处** → 缺口 **110**；装饰图也没有 `importantForAccessibility="no"`。TalkBack 全站不可用。可脚本化批量补，风险低。
- [ ] **播放器触控热区大量 <48dp**：`activity_player.xml` 28dp 高 11 处（`:310/319/328/337/347/357/367/388/397/406/418`）、37dp 5 处（`:226/236/245/255/265`）、进度条 `:201`=20dp / `:464`=32dp / `:645`=35dp；该文件对 `@dimen/touch_min`（48dp）**0 引用**。（2.17「点下去零反馈」已修，尺寸没改。）
- [ ] **无自适应图标**：`res/mipmap*` 只有 `mipmap-nodpi/`（约 52 个 png/webp），无 `mipmap-anydpi-v26`、无 `ic_launcher_foreground/background`；桌面图标形状由系统强制裁切。需要设计资产，非纯代码任务。
- [ ] **残留 px 单位与全透明短写色**：`layout_height="0px"` `activity_download.xml:23`、`cell_video_local.xml:87`，`1px` `activity_player.xml:16/17`（共 5 处）；`background="#6000"` `activity_search.xml:129`，`background="#0000"` `cell_article_end.xml:117/140/164` + `fragment_video_info.xml:229/252/276`（共 7 处）。一次改完，零风险。
- [ ] **过小字号**：`textSize="8|9|10|11sp"` 共 **57 处**，其中真正不可读的 6 处是 8sp（`activity_player.xml:181`）、9sp（`activity_captcha_webview.xml:33`）、10sp（`activity_feedback.xml:66/109`、`activity_player.xml:479/493`）；`android:alpha` 在 layout 下仍有 **142 处**（文字靠 alpha 拉层级基本没动）。先修那 6 处成本极低。
- [ ] **浅色主题 `Theme.BiliClient.Light` 是死样式**：`themes.xml:45` 定义、全工程 **0 引用**（活的是 7 套 `Theme.*` + `Theme.*.NoSwipe.AppCompat`）；弹幕颜色仍无设置项（grep `danmaku_.*color` 0 匹配）。二选一：接线或删掉。
- [ ] **`colors.xml` 别名膨胀**：`<color name=` **≥328 条**（`zhihu_*`/`iqiyi_*`/`purple_fantasy_*`/`rainbow_*`/`classic_gray_*`/`terminal_*` 各约 60 条，绝大多数指向同一 hex）。有了 `ui/appearance/ColorScheme.kt` 这个真源后，别名层是纯负担；清理需一批回归。风险中。

**别再重复排查（已排除）**：WebView 白底闪屏**不存在**；菜单按钮 `MaterialButton` 默认 `minHeight=48dip` **已达标**；`ui/widget/recycler/` 与 `fragment_empty.xml`、`BiliTerminalApp.kt` 是**死代码**（不当在用组件排查）。**不做**：裸 `Activity` 收编（`PlayerActivity`/`ImageViewerActivity`/`SplashActivity`/`GetIntentActivity`）——播放器保持黑色系统栏是有意为之，且 `PlayerActivity.kt` 3000 行，收益不抵回归风险（理由已进 `docs/architecture-map.md` §8.7.3）。**可接受**：`CardStyle`/`ButtonStyle` 强制 `clickable+focusable`；间距/圆角 token 已建但布局未全量改用；同构布局重复；布局层静态色已清干净（`@color/(card_dark|pink|divider_dark|pink_light)` 在 `res/layout` 0 处）。

## 3. 最新状态快照（26.10.09 ~ 26.10.14）

### 3.0 控制台 v2 与共存版统计（26.10.14）

一次性做完 5 项需求，横跨自建后端（`server/`）与 APK 遥测链路。**已部署上线并验证。**

**后端 / 前端**（`server/rebiliterminal_api.py`、`server/console.html`）

- **数据稳定性**：`_db_ping` 改成**空值不覆盖**（`CASE WHEN`）—— 修掉「客户端某次上报空 `abi`，
  分布图凭空冒出 `(未知)` 分组、原分组同时 -1」这个主要波动源；分布查询加次级排序键
  （`ORDER BY 2 DESC, 1 ASC`）消除同数时的顺序抖动；`today_new` 从滚动 24h 改成自然日，与图表对齐。
  **版本分布保持「当前在用版本」语义 —— 升级后旧版本计数减少是期望行为**，用户 26.10.14 明确确认，
  故**未**引入历史版本表（`install_versions` / `daily_snapshot` 都没做，迁移也不回填历史）。
- **单独回复**：`POST /admin/api/feedback/reply` 把回复写成一条**定向公告**。客户端**零改动**
  —— 可见性靠 `X-Install-Id` 头判定，而 `TerminalApi.headers()` 本来就一直带着它。
  **关键坑**：可见性必须下推到 SQL `WHERE`，否则 `LIMIT 100` 会被不可见公告占满，
  导致公开公告**永久饥饿**（游标不推进 → 每次查同一段）。自测 §17 专门钉这条。
- **删除**：`feedback/delete`、`crash/delete`，复用既有两道 CSRF 防线。**不给 `installs` 开删除口**
  （删安装行会让唯一设备数与当日新增回退）。
- **设备视图**：`GET /admin/api/devices` 按 `dev_key` 聚合，**一行一台设备**，直接标
  「仅标准版 / 仅共存版 / 两个都装」。平铺 `installs` 会把双版本手机读成两台设备。
- **前端**：设计令牌统一、卡片层次、手写 SVG 图表加悬停准线与浮层、骨架屏、hash 路由、
  键盘快捷键、搜索筛选、批量操作、乐观更新、详情抽屉、CSV 导出。
  **仍是零外部依赖单文件**（后端按 mtime 缓存吐出，改前端不用重启进程）。
- **迁移**：新增 `migrate()`（`PRAGMA user_version` + 幂等步骤，先探测现状再动手）；
  `daily_active` 主键从 `(install_id, day)` 重建为 `(dev_key, day)`。
  生产库已迁移：**迁移前后行数一条不差**（installs 22 / daily_active 40 / feedback 1 / crash 32 /
  announcements 2，`user_version` 0 → 1）；迁移前已 `.backup` 备份为
  `data.db.bak-20261014-160444-premigrate`（**不能用 `cp`**，WAL 下会丢 `-wal` 里的最近写入）。
- 自测从 56 项扩到 **92 项**，本地与生产服务器均全绿。

**APK 侧**（`api/TerminalApi.java`、`util/SettingsKeys.kt`、`res/values/strings.xml`）

- 新增 `device_key = sha256(ANDROID_ID + 固定盐)` 的截断值，用于把同机两版合并成**一台设备**。
  **只上报哈希，原始 ANDROID_ID 不上报也不落库。** 两个 flavor 用同一签名 → `ANDROID_ID` 相同 →
  哈希相同；**将来换不同签名会让去重失效**。取不到就发空串，服务端 `dev_key_of()` 回落 `install_id`。
- 上报新增 `device_key` / `is_coexist`（后者取 `BuildConfig.COEXIST`，构建配置 26.10.11 就已存在）。
- **隐私文案三处同步改掉**（`privacy_consent_message` / `about_to_uncle` / `text_setup_introduction`）：
  原文写的是「不读取 ANDROID_ID 等任何设备唯一标识」，与新的哈希方案直接矛盾，不改就是自相矛盾。
  `PRIVACY_VERSION` **1 → 2**，老用户会重看一次同意弹窗（**有意为之**）。
- 单测：`TerminalApiTest.kt` 同步新签名，并新增 `hashDeviceKey` 的形状断言
  （32 位小写十六进制、同输入稳定复现、绝不含原始 ANDROID_ID）。两个 flavor 各
  **56 suites / 558 tests / 0 failed**。

设计文档：`docs/superpowers/specs/2026-10-14-console-v2-design.md`（含实施后的修订说明）。

### 3.2 管理控制台 WebUI + 服务端并发加固（§四十二）

- 需求：域名 `console.zsapp.asia`（泛解析 `*.zsapp.asia` 已指向本机，**不需另加 DNS 记录**）；登录用**登录页 + HttpOnly 会话 Cookie**，旧 `GET /admin?token=…` 保留作兜底。
- 容量结论：`telemetry/ping` 每安装每天最多 1 次，公告每次启动 1 次差量 → 日均请求 ≈ 2~3 × 日活。
  - 1 万日活 ≈ 0.3 req/s 均值、约 1 req/s 高峰；10 万日活 ≈ 3 req/s 均值、25~30 req/s 高峰。
  - **瓶颈不是 Python 而是每次 commit 的 fsync**。
- **并发加固两条（真实隐患）**：
  - ① 阻塞 I/O 跑在事件循环上 —— `telemetry_ping`/`feedback_submit`/`upload_stack` 是 `async def` 却直接 `conn.execute(...)`；一旦撞锁走 `busy_timeout=15000`，**整个事件循环卡最多 15 秒**，连 `/terminal/health` 都不响应。修法：库访问全抽成同步 `_db_*` 函数，由 `from starlette.concurrency import run_in_threadpool` 调度，**api 层不再出现 `connect()`**。
  - ② `connect()` 没设 `synchronous` → WAL 下默认仍 `FULL` → 每次 commit 都 fsync；改 `PRAGMA synchronous=NORMAL`。
- 会话：`_session_value(exp) = <exp>.<hmac_sha256(ADMIN_TOKEN, exp)>`，`exp = now + 7 天`，**不存服务端状态**。
- `_check_token(request, token)` 三种凭据任一通过（URL `token` / `X-Admin-Token` 头 / 会话 Cookie）→ **12 处既有调用点一次都没动**；Cookie `HttpOnly; Secure; SameSite=strict; Max-Age=604800; Path=/`。
- **CSRF 两道独立防线**：`SameSite=strict` + 状态变更接口额外要求 `Content-Type: application/json`（跨站表单只能发 urlencoded/plain，必须先过 CORS 预检），不满足直接 415；登录接口 `rate_limit(ip,"login",20,3600)`。
- WebUI：`server/console.html` 单文件、**零外部依赖**（不引 CDN / 图表库，国内访问 jsdelivr/unpkg 不稳），折线图手写内联 SVG。
- `GET /` 原样吐出，**带 mtime 缓存**（改前端只覆盖文件、不用重启）。四个页签：公告/反馈/崩溃 + 顶部遥测卡片与图表；60s 只刷遥测，**不覆盖公告输入框内容**。
- 新接口：`GET /admin/api/announcements`（返回**内部 id**，客户端看到的仍是 `ANN_ID_OFFSET + 内部 id`）、`POST /admin/api/announcement`、`/announcement/update`（`{id, pinned?, active?}`）、`/announcement/delete`。
- `GET /admin/api/stats` 扩了 `week_active`/`today_new`/`feedback_pending`/`crash_pending`/`brands`，`daily` 每条多了 `new_installs`（**纯增量，旧字段一个没改**）。
- nginx：`server/nginx/console.zsapp.asia.conf` 80+443、HTTP 跳 HTTPS、`/terminal/` 直接 `return 404`（控制台域名不开放客户端接口）、HSTS + `X-Content-Type-Options` + `X-Frame-Options DENY` + `Referrer-Policy: no-referrer` + 紧 CSP。
- 备份：`server/backup.sh` + cron `7 4 * * *`，用 **`sqlite3 .backup` 而不是 `cp`**（WAL 下最近写入还在 `-wal` 里），存 `/opt/rebiliterminal-api/backups/data-<YYYYMMDD>.db`，保留 14 天，校验表数量（少于 5 张非零退出）。探活 cron `*/10` 失败只追加 `health.log` 一行。
- 验证：`server/selftest.py`（临时 sqlite + 临时口令，**不碰生产数据**）**39 项断言全通过**。
- 公网端到端全部按契约返回（`console.zsapp.asia/` 200 + 证书 + 5 个安全头；发布公告 `public_id=1000000002` → 立刻能从 `rebiliterminal.zsapp.asia/terminal/announcement/get_list` 拉到）。测试数据已清理，`sqlite_sequence` 复位。

### 3.3 紧急撤回「自动跳过片头片尾」（§四十三，**26.10.22 已恢复**）

> **现状（26.10.22）**：撤回**已解除**，功能整体恢复上线，但口径改为**默认关闭**。
> 撤回与本恢复的判定都只动 `FEATURE_ENABLED` 这一个常量，`ViewPointSkip` 判定逻辑与其单测从头到尾一行未动。
> 恢复相关的落点、默认值取舍与单测见本节末尾「恢复记录」；撤回过程保留在下文作历史依据。

- **用户原话**：「将跳过片头片尾的功能紧急撤回，直接关闭且隐藏此设置，立刻发新版本26.10.10 2610102 tag打新的」，追加「更新公告追加一条说明即可」。要求是**撤回而非删除**：代码与设置项保留，只让它不生效、入口不可见。
- **为什么必须新加开关，而不是把默认值改成 false**：`SharedPreferences.getBoolean(key, def)` 里的 `def` 只在**键不存在**时生效，而老用户从 26.10.09 起就已被写入 `true`（首次读取即落盘），改默认值对这些人一点作用都没有。
- **撤回否决位（逐字，恢复时只改这一行）** —— `app/src/main/java/com/RobinNotBad/BiliClient/player/SkipOpEdPrefs.kt`：

  ```kotlin
  const val DEFAULT_ENABLED = true   // 保持 true，恢复后行为与本补丁前完全一致
  const val FEATURE_ENABLED = false  // 26.10.10 撤回；恢复时只改这一行
  fun isEnabled(prefValue: Boolean): Boolean = FEATURE_ENABLED && prefValue
  ```

- `DEFAULT_ENABLED` **必须保持 `true`**：`app/src/test/java/com/RobinNotBad/BiliClient/player/SkipOpEdPrefsTest.kt:41` 的 `默认值是开启` 断言钉着它（那是用户上一轮明确要求的「默认必须是开启」）。改它既撤回不了老用户，还会弄红测试。
- **撤回四处落点**（`isEnabled` 组合是运行期闸门，另三处是可见性 / 白拉接口的收口）：

  | 位置 | 改动 |
  | --- | --- |
  | `player/SkipOpEdPrefs.kt` | 新增 `FEATURE_ENABLED` + `isEnabled(prefValue)` 纯函数；object 头注释加「26.10.10 已紧急撤回」 |
  | `activity/player/PlayerActivity.kt` `skipOpEdEnabled()` | 改为 `SkipOpEdPrefs.isEnabled(getBoolean(PLAYER_SKIP_OP_ED, DEFAULT_ENABLED))`，即运行期恒 `false` |
  | 同上 `maybeShowSkipGuide()` | 开头 `if (!SkipOpEdPrefs.FEATURE_ENABLED) return`（引导里「开启」按钮会写回存档） |
  | 同上 `needViewPoints()` | **显式删掉 `\|\| PLAYER_SKIP_OP_ED`**，只留 `PLAYER_SHOW_VIEWPOINTS`（光有运行期闸门不够：老用户存档还是 `true`，不改判断仍会为「跳过」白拉一次 `view_points`） |
  | `activity/settings/SettingTerminalPlayerActivity.kt:132-133` | `add(SettingSection("switch", "自动跳过片头片尾", …))` 两行注释掉，`:10` 的 `import ...player.SkipOpEdPrefs` 一并注释，两处写明恢复方法 |
  | `activity/settings/SettingsIndex.kt` | 搜索关键词表删掉 `"自动跳过片头片尾"`，否则留一个点进去定位不到的幽灵条目 |

- `player/ViewPointSkip.kt`（纯判定）与其单测**一行未动** —— 撤回是关闸门，不是拆功能。
- **测试钉子**：`SkipOpEdPrefsTest.kt` 新增 3 例 —— `功能已撤回时总开关必须是关闭`、`撤回期间存档里写着开启也不能跳`（先断言存档确实读出 `true`，再断言 `isEnabled` 为 `false`）、`撤回期间两种存档取值都判定为不启用`；类注释新增第 4 条「撤回必须与存档无关」。
- **公告与版本**：`app/src/main/res/values/strings.xml` 的 `update_log_current` 追加第 8 条「暂时下线「自动跳过片头片尾」：设置入口已隐藏，之前开启过的也不再自动跳过，播放时不会再有相关提示。相关代码与设置项保留，后续视情况恢复。」
- `app/build.gradle` versionCode `2610101` → **`2610102`**，versionName 仍为 `26.10.10`；tag 用 **`26.10.10.1`**（`26.10.10` 已被 2610101 的不可变 Release 用掉，release immutability 下被 release 用过的 tag 永久不可复用）。
- **版本序列（26.10.09 → 26.10.10）**：写 §四十一 时版本仍是 `2610090` / `26.10.09`，随后按发版技能正常发版，落成 **`2610101` / `26.10.10`**（`update_log_current` 补本次 7 条 + `update_history_log` 顶部新增 `## 2026-10-09` 分组）；同日又因紧急撤回追加 **`2610102` / `26.10.10`**。
- 本地 `verifyVersionConsistency` + `testDebugUnitTest`（457 例）+ `assembleRelease` 全绿，四个 ABI 的 release APK 已产出，然后推 tag `26.10.10` 走 `.github/workflows/build-release.yml`。
- **恢复清单（6 步）——已于 26.10.22 执行 ①–④，⑤⑥ 留待下次发版**：
  - ① `player/SkipOpEdPrefs.kt` 的 `FEATURE_ENABLED` 改回 `true`；✅
  - ② `activity/settings/SettingTerminalPlayerActivity.kt` 两行与 `:10` 的 import 取消注释；✅
  - ③ `activity/settings/SettingsIndex.kt` 搜索关键词加回 `"自动跳过片头片尾"`；✅
  - ④ `PlayerActivity.needViewPoints()` 的 `PLAYER_SKIP_OP_ED` 判断恢复；✅
  - ⑤ `strings.xml` 的 `update_log_current` 追加一条恢复说明；⏳ 未做（恢复不是这次发版的一部分）
  - ⑥ 递增 versionCode / versionName 并按发版技能出包；⏳ 未做

- **恢复记录（26.10.22）**：
  - **口径由用户拍板**：在「A 只显示设置项但不生效 / B 整体恢复且默认开启 / C 整体恢复且**默认关闭**」三项里选了 **C**。
    理由：26.10.10 撤回的起因就是「乱跳」，修掉之后让没主动开启的用户被动跳片头仍有风险，默认关闭可把决策权完全交还用户。
  - **`DEFAULT_ENABLED` 由 `true` 改为 `false`**（历史上它是 `true`，被 `SkipOpEdPrefsTest` 钉着）。这不是「撤回」，是恢复后的新默认值；
    真正的撤回手段仍是 `FEATURE_ENABLED`（与存档无关），两者职责不同、不可互相替代。
  - **老用户不受默认值影响**：`getBoolean(key, def)` 只在键不存在时用 `def`，所以
    ① 存档里显式开过的老用户恢复后**仍然是开的**（他们当初主动开过，不该被升级悄悄关掉）；
    ② 存档里关过的保持关闭；③ 新装用户默认关闭。三种情形各有单测钉住。
  - 四处落点全部按清单恢复；`player/ViewPointSkip.kt` 与其单测仍一行未动。
  - **单测变化**：`SkipOpEdPrefsTest` 由 10 例变 13 例——删掉撤回期的 3 例（`功能已撤回时总开关必须是关闭` 等），
    新增 4 例（`功能已恢复上线时总开关必须是开启`、`恢复后存档里写着开启就能跳`、`恢复后默认关闭不会自己跳`、`老用户开过的话恢复后仍然开启`），
    并把 `默认值是开启` / `从未存过时读取默认值得到开启` 翻转为关闭。类注释第 1、4 条同步改写。
  - 验证：`assembleDebug` 通过；`testStandardDebugUnitTest` 与 `testCoexistDebugUnitTest` 各 **818 例全绿**（`--rerun-tasks` 强制重跑）。

### 3.4 播放器网络韧性：弱网卡死 / 切后台位置错（§四十四，26.10.11）

- **用户原话**：「播放视频时切换别的软件再回来时视频位置会出错」+「弱网看直播惨不忍睹。。。」；先要求「你先找原因，不修复」，同日批准「来吧，开始修复」。
- **根因**（完整证据链见 `docs/superpowers/specs/2026-10-11-player-network-resilience.md`）：① 主播放器 `setDisplay()` 的 IJK 选项**没有读超时**（`rw_timeout` 默认 0 = 读可永久阻塞且永不回调 `onError`），且 `reconnect=1` 对 chunked 直播无效（ffmpeg `http.c` 有 `is_streamed && !reconnect_streamed` 就 break 的判定）；② 直播取流三处硬取 `stream[0].format[0].codec[0]` = 永远 FLV 单长连接，HLS 拿回不用、线路不自动 failover；③ 没有任何「位置 N 秒不前进」看门狗（ijkplayer 自身也没有）；④ 切后台重建 Surface 时 `onSurfaceReattached` 用 **UI 进度条值** seek 且无 live 守卫，叠加 `enable-accurate-seek` 默认 0 ⇒ 每次回前台往回跳一个关键帧间隔；⑤ 全页没有 `onSaveInstanceState`，被系统回收后只能回到进页那一刻的 Intent `progress`。
- **改动（26.10.11 一次落地；`assembleDebug` + 474 个 JVM 单测全绿）**：
  - `activity/player/PlayerActivity.kt`：`setDisplay()` 给在线流加 `rw_timeout=10s` / `addrinfo_timeout=5s` / `reconnect_streamed=1` / `reconnect_delay_max=5s`，点播开 `enable-accurate-seek=1`；`onSurfaceReattached` 改为 seek 到 `video_now` 并加 `!isLiveMode`；新增 `onSaveInstanceState` + `STATE_POSITION` 恢复；新增停滞看门狗 `checkStall()`（直播 20s / 点播 30s 无进展即重试，直播轮换候选线路、点播原地重建，最多 3 次、冷却 15s）；`retryAfterPlayerError(resumePositionMs = video_now)` 加默认参数。
  - 新增 `util/live/LiveStreamSelector.kt`（纯函数，14 单测）：候选按「FLV 各线路 → HLS」稳定排序（首个候选与旧行为一字不差）；`activity/live/LiveInfoActivity.kt` 三处改用它，候选经 Intent `live_url_candidates`（`model/PlayerData.java` 新增字段 + Parcel 读写）交给播放器做运行时线路轮换。
  - 删 `activity/video/ShortVideoPlayerActivity.kt` 的 `max_cached_duration`（`.so` 实测不存在）；`player/VideoPlayerCore.kt` 的 `http-detect-range-support` 保留但标注为空转。
- **明确不做**：`reconnect_at_eof`（点播正常播完会被当成 EOF 无限重连）、提高直播默认清晰度（`TerminalContext.java:259` 的 `qn=80`，弱网可看优先）、`dns_cache_clear` 与短视频激进参数（证据不足）。
- **真机验证（26.10.11，vivo `V2229A` / Android 16）**：只装了 release 包（两 flavor 双包，见 §3.5），`adb shell am start` 深链进播放器正常起播；熄屏再亮屏与「详情页压上去再返回」两种回前台路径都**没有**位置回跳（`00:56 → 00:58`），但**修复前的旧 release 包同样复现不出回跳**（本机熄屏不销毁 surface、按 HOME 切走后任务被 vivo 回收），故此项属「未复现」而非「已证实修复」。**弱网看门狗未在真机触发**（未进直播间，且 release 包 `Logu` 默认关闭、无埋点日志可查）。
- **未验证**：D3-13/D3-15（弱网看门狗、断网重连续播）需在**带埋点的 debug 包 + 真实直播间**上跑；真机用例见 `docs/review/device-test-plan.md` 的 D3-11~D3-15。

### 3.5 共存版：product flavor 拆分（§四十五，26.10.11）

- **用户原话**：「可不可以像mt管理器一样发两个版本，一个共存版本一个普通版本」；后续三问的答复：产物**保持旧文件名**（普通版由 CI 改名回 `app-<abi>-release.apk`，老客户端继续能收到更新）、外链**两版都响应**（系统弹选择框，接受）、共存版**只换图标**（用上游原图标 `.dsh/ref/Re-BiliTerminal/.../icon.png`，即 22/33 泡面碗那张；普通版保持现行纳西妲 `icon.webp`）；**后续用户追加要求：「名字不变」「桌面名和应用名字，两个版本都改为 RE:哔哩终端」**——即两版同名，中途试过的「哔哩终端共存版」命名已回退。
- **实现**：`app/build.gradle` 加 `flavorDimensions 'channel'` + `standard` / `coexist`（`applicationIdSuffix ".coexist"`、`buildConfigField COEXIST`）；清单 FileProvider `android:authorities` 改 `${applicationId}.FileProvider`（**全仓库唯一硬编码包名**）；`app/src/coexist/res/` 只放一枚上游原图标 `mipmap-nodpi/icon.png`；**两版名称保持一致**（应用名与桌面名都是 `@string/app_name` = 「RE:哔哩终端」，`SplashActivity` 的 label 由字面量「哔哩终端」改回 `@string/app_name`——桌面显示的是启动 Activity 的 label，字面量会让两处名字不一致）；`util/UpdateRelease.kt` 的 `pickApkUrl` 加 `namePrefix` 参数 + `apkNamePrefix(BuildConfig.COEXIST)`，`util/UpdateManager.kt` 传参（+4 单测，含「只有普通版附件时必须返回 null」）。
- **CI**：`build-release.yml` 单测任务改 `testStandardDebugUnitTest`，并在构建后新增「整理发行 APK」步骤把 `standard` 产物改名回历史名、`coexist` 产物原样搬进 `app/build/outputs/apk/release/`（**下游 MD5 / 附件 / 直链步骤一字未改**）；`ci.yml` 同步改任务名与报告路径。
- **验证**：`assembleDebug`（两 flavor）+ `testStandardDebugUnitTest` / `testCoexistDebugUnitTest` 全绿，各 **478 例 / 0 失败**；`aapt2 dump badging` 实测两包 `package` / `application-label` / 图标 / 启动 label 均正确区分。技术要点已进 `docs/architecture-map.md` §7.37，构建命令已进 `AGENTS.md`。
- **更新兼容性（26.10.11 核实）**：① 老客户端（含已发布的 2610102）看的是历史附件名，CI 整理步骤保证一字未变，元数据格式也没动 → 照常收到更新；② 共存版附件名是 `app-coexist-<abi>-release.apk`，由 `UpdateRelease.apkNamePrefix(BuildConfig.COEXIST)` 选中，`UpdateReleaseTest` 锁了「只有普通版附件时必须返回 null」；③ 取不到自己的包时 `parseRelease` 抛 `IOException` → `doFetchUpdateConfig()` 自动往下一个源走（Gitee → GitHub），**不会静默装错包**；④ 双保险：安装前 `util/ApkVerifier.kt` 要求「包名一致 + 签名集合一致」，不符就删残片并提示「安装包包名不符」；⑤ 下载落在 `context.externalCacheDir/update/`（按包名隔离），FileProvider authority 用 `${applicationId}`。**Gitee 中转服务是否同步 `app-coexist-*`（26.10.11 实测 → 已修复）**：中转服务端的附件白名单（`assets.include`）原先是 5 个历史文件名（普通版 4 包 + `md5sums.txt`），所以 Gitee 的 26.10.11 发行版**只有普通版**，共存版按 ③ 回落到 GitHub 源；已在服务端白名单补上 4 个 coexist 名并手工补传（见 §3.5 的「未验证」条）。
- **取舍**：共存版数据（登录态 / 设置 / 缓存 / `/Android/media/<包名>/`）与普通版完全隔离，**要重新登录**；外链两版都注册，系统弹选择框。
- **真机验证（26.10.11，vivo `V2229A` / Android 16 / arm64-v8a，全部成功）**：① 普通版 `adb install -r`（同 versionCode 2610102、同签名 SHA-256 `991386eb…`，实测与设备原装包签名一致）**覆盖升级并保留登录态**，启动后推荐流正常；② 共存版全新安装 → 停在扫码登录页（**数据隔离成立**），两包进程可同时在跑（pid 7500 / 7776），均无 `FATAL EXCEPTION`；③ `aapt2 dump badging` + 从 release 包内导出图标核对：普通版图标 `res/fe.webp`（纳西妲）、共存版 `res/yP.png`（上游 22/33 泡面碗），两包 `application-label` / 启动 label 都是「RE:哔哩终端」（**同名要求成立**）；④ 外链 `https://www.bilibili.com/video/...` 会弹「使用哔哩终端打开」选择框（两版都能接，即用户接受的取舍）。
- **未验证 / 已补测（26.10.11）**：① Gitee 中转服务是否同步共存版附件 —— **实测不同步**（服务端白名单只有 5 个历史文件名），已在中转服务端把 `app-coexist-{arm64-v8a,armeabi-v7a,universal,x86}-release.apk` 加进白名单并重启服务，并对 26.10.11 补传了这 4 个包（4 个包 MD5 与 CI 的 `md5sums.txt` 逐一致、Gitee 直链实测可下载）；旧发行版不用回补（老版本本来就没有共存版）。② 共存版「应用内自更新 → 下载 → 安装」全链路仍未在真机跑通：设备上的共存版是 **debug 签名**、发行包是 release 签名，`ApkVerifier` 必然拒装（属预期，不是 bug）——要真机验证得等共存版自身也发过一次 release 之后。

### 3.6 线上崩溃：PlaybackService 前台服务 5 秒义务未兑现（§四十六，26.10.11）

- **用户原话**：贴出线上崩溃报告 + 「修复报错」。报告原文：
  `android.app.RemoteServiceException: Context.startForegroundService() did not then call Service.startForeground(): ServiceRecord{2662445 u0 com.RobinNotBad.BiliClient/.service.PlaybackService}`，
  栈只有 `ActivityThread$H.handleMessage(:1821)` → `Handler.dispatchMessage(106)` → `Looper.loop(193)` → `ActivityThread.main(7083)`（系统发给主线程的强制崩溃，没有业务栈）。
  设备：`26.10.10 / BiDeZhi uws6152_1h10_go CD_Watch_A · SDK 28 · 内存 3906MB / 2591MB · 线程 main`，时间 2026-10-11 11:14。
- **根因（两条，都在 `service/PlaybackService.kt`，均已修）**：
  1. **主因 — `stop()` 与 `start()` 的竞态把尚未兑现的义务作废**。`PlayerActivity.onPause:2016` 调 `PlaybackService.start()`（`startForegroundService`），
     `onResume:2024` / `onDestroy:2055` 调 `PlaybackService.stop()`（`stopService`），二者在时间上紧挨。而**创建服务是异步的**——
     线上机型可用内存只剩 2591MB、创建服务本来就慢，若 `stopService` 抢在 `onCreate`/`onStartCommand` 之前到达，
     这次 `startForegroundService()` 留下的义务就**永远无人兑现**，5 秒后系统直接崩主线程。
     对应 Google issue 300134171（用户明明选了停止服务，仍收到 `ForegroundServiceDidNotStartInTimeException`）。
  2. **次因 — `startUpdateTimer()` 原先放在 `onCreate()`**（上游 Java 版放在 `onStartCommand` 的 `startForeground` 之后）：
     `onCreate` 与 `onStartCommand` 是主线程上**两条独立消息**，主线程被占满时，Timer 会在 1 秒后先 `stopSelf()`、
     抢在 `startForeground` 之前，义务同样作废。
- **已排除的触发路径（本次只读核实）**：① 旋转重建 Activity —— `AndroidManifest.xml:179` 的 `PlayerActivity` 带
  `android:configChanges="orientation|screenSize|keyboardHidden"`，旋转不重建，只会走 `onConfigurationChanged`；
  ② `onPause` 里主线程被网络阻塞 —— `PlayerActivity.kt:1412 reportProgressNow()` 非阻塞，网络丢进 `CenterThreadPool.run { }`。
- **改动（`app/src/main/java/com/RobinNotBad/BiliClient/service/PlaybackService.kt`，本轮新增两条顺序铁律）**：
  - 新增进程级 `@Volatile foregrounded` / `@Volatile stopPending` 两个标志；`stop()` 里 `playerRef = null` 后先判
    `!foregrounded` → 只置 `stopPending = true` 并 return，**不发 `stopService`**；`start()` 每次复位 `stopPending`（避免新启动被上一轮的停止请求误伤）。
  - `onCreate()` 末尾新增 `if (!enterForeground()) stopSelf()`；`onStartCommand()` 先 `enterForeground()`（失败即 `stopSelf` + `START_NOT_STICKY`），
    再消费 `stopPending`（`stopSelf`），**之后**才 `startUpdateTimer()`——即「先进场、再停止」。
  - 抽出 `enterForeground(): Boolean`：`buildNotification()` 抛异常时退到新增的 `buildFallbackNotification()`（只有图标 + 标题，**不含 `PendingIntent`**，
    因为组装失败点常在 `packageManager.getLaunchIntentForPackage`），保证任何时候都能先把义务兑现；`startForeground` 再抛才返回 false。
  - `startUpdateTimer()` 改为 `if (timer != null) return`（不再 cancel 重建）；`onDestroy()` 复位 `foregrounded` / `stopPending`。
- **文档同步**：`docs/architecture-map.md` §7.5 里原来那句「`onStartCommand` 第一行必须 `startForeground`」已升级为
  「`onCreate` 也要进前台 + 进前台前禁止 `stopService` + `enterForeground()` 返回 false 必须 `stopSelf()`」四条，并写明线上出处。
- **验证（26.10.11，本机补跑，全部通过）**：`:app:assembleDebug` 两 flavor 全成功；`:app:testStandardDebugUnitTest` / `:app:testCoexistDebugUnitTest` **各 50 类 / 478 例 / 0 失败 0 错误**（与 §3.5 基线一致）；额外用 `:app:compileCoexistDebugKotlin --rerun` 强制重编 coexist 主源码通过（只有工程既有的 deprecation / unchecked cast 警告，无本文件相关告警），并核对两 flavor 的 `app/build/tmp/kotlin-classes/<flavor>Debug/.../PlaybackService.class` 均含 `enterForeground` / `stopPending` / `buildFallbackNotification`。
  > 起因：初次构建时 coexist 侧报 `compileCoexistDebugKotlin UP-TO-DATE`（源码明明改过），故补一次 `--rerun` 排除构建缓存回放。
- **未验证（真机）**：低内存手表上「起后台播放后 5 秒内切回前台」「快速进出播放页」反复若干次，确认不再出现 `RemoteServiceException`。本机无该机型（线上是 `BiDeZhi uws6152_1h10_go CD_Watch_A` / SDK 28）。

### 3.6 弹幕「一抖一抖」：时钟基准错位（§四十六，26.10.11）

- **报告**：User said (m01538)「这个弹幕总是一抖一抖的有办法修吗」。上一版修复是提交 `f1ec31a`「停止每 250ms 硬拨弹幕时钟」（2026-10-03，已在装机包里），说明「不要每次都 `timer.update`」这一层已经做了，但抖动仍在。
- **根因（真机取证，不是推断）**：弹幕时钟与视频位置**不同步的不是容差，而是基准**。
  - DFM 的 `DanmakuTimer` 自走；`DanmakuFlameMaster/src/main/java/master/flame/danmaku/controller/DrawHandler.java` 的 `syncTimer()`（`:431-476`）每帧按 `gapTime = (SystemClock.uptimeMillis() - mTimeBase) - timer.currMillisecond` 把时钟**拉回墙钟基准**，`gapTime > 2000` 时直接整段硬跳（`:448`）。
  - `mTimeBase` 只在 `PREPARE` / `RESUME` / `SEEK_POS` 里写；`DanmakuTimer.update(pos)` **改不到它**。所以「我们拉回 pos → DFM 下一帧又追回旧基准」会反复发生。
  - 实测（standard release 包 + 临时埋点，VOD `av170001`）：`updateTimer=192 次/2s、resync=84 次/2s、drift=[-1803, 0] ms`；从片尾点 `button_video` 重播后仍是 `resync=1 次/2s、drift=[-199244, 0] ms`（DFM 时钟还带着上一轮 ~199 秒的基准，每轮被整段硬跳拉回）。**约 42 次/秒的硬校正在肉眼上就是一抖一抖。**
- **改动**：
  - `app/src/main/java/com/RobinNotBad/BiliClient/player/DanmakuManager.kt`：`updateTimer` 回调**不再动时钟**（既不 `timer.update(pos)`，也不再在这个渲染线程上读播放位置），只留注释说明为什么——校正必须重设基准，而 `seekTo` 是唯一会 `mTimeBase -= deltaMs` 的入口（`DrawHandler` `SEEK_POS` 分支 `:222-234`）。构造函数里那个「返回当前播放位置的 lambda」随之删除（它是上一版的产物，现已无用）。
  - `app/src/main/java/com/RobinNotBad/BiliClient/activity/player/PlayerActivity.kt`：对齐改到**主线程** `progressChange()` 的 250ms 循环里，用纯函数 `player/DanmakuSync.kt`（`TOLERANCE_MS = 400L`，>250ms 采样间隔）判定 `video_now` 与 `mDanmakuView.currentTime`，命中才 `mDanmakuView.seekTo(video_now)`（同时重设 `mTimeBase`，属于"一次性对齐、之后自走"）；`onPrepared` 起播时把弹幕时钟锚到 `resumeTarget`（0 也锚），消除起播缓冲造成的固定偏移。
  - 判据保留：`progressChange` 的对齐只在 `isPrepared && isPlaying && !isSeeking && !destroyed` 内执行，且 `(hasDanmaku || isLiveMode)` 才做（直播的弹幕视图走 `prepareEmpty()`，`hasDanmaku` 可能为 false）。
- **未采纳**：把 `TOLERANCE_MS` 调大或彻底删掉校正。前者只是降低触发频率（偏差仍会累积成一次可见跳），后者会让缓冲/解码漂移永久残留。
- **真机验证方法学（踩过的坑，留给下次）**：这台 vivo 的 logcat 通道时有时无——`Log.d` 一律抓不到，`Log.e`/`Log.i` 间歇可见；同一台设备的 `adb install` 常返回 `INSTALL_FAILED_ABORTED: User rejected permissions`，必须在「安全守护提示您」弹窗点「继续安装」（`[432,2209][648,2272]`）。采样点必须是**播放中段**：`videoprogress`（`[0,2223][1080,2283]`）用 `input tap`/`swipe` 点不动，靠播放到片尾后点 `button_video`（`[492,2283][588,2394]`）重播来拿到"从 0 开始"的窗口。
- **验证（本机构建/单测）**：`:app:assembleDebug` + `:app:testStandardDebugUnitTest` / `:app:testCoexistDebugUnitTest` **各 52 类 / 493 例 / 0 失败 0 错误**（用例数较 §3.4/§3.5 的 478 多出的部分来自同一工作区里并行的番剧剧集状态改动，与本次弹幕改动无关）；`DanmakuSync` 语义未改，`DanmakuSyncTest` 保持全绿。
- **未验证（真机）**：修复后「抖动是否消失」需要用户在自己的使用场景里确认（本机测试期间设备被用户占用，只完成了机制取证与代码落地）。

### 3.8 番剧界面补齐：对齐 Re-BiliTerminal（26.10.11，未真机验证）

起因：与 `cyq114514/Re-BiliTerminal`（基于 BiliClient 2.9.8）逐文件对比番剧界面，按用户 26.10.11 的拍板只补 **A 番剧详情页 UI + C 进度线 + D 历史卡片续播**，**明确不做** B（进详情页自动查历史定位上次观看）与 E（实验室诊断页）。每批一个 commit，均跑 `:app:assembleDebug` + `:app:testStandardDebugUnitTest` 后提交。

- **批次 1 追番按钮**（`6a0c8e1`）：`BangumiApi.getFollowStatus/parseFollowStatus/setFollow` + `FOLLOW_KEY_PREFIX = "bangumi_follow_"`；`res/layout/fragment_media_info.xml` 的 `btn_play` 改由 `btnRow` 包住，右侧是 `btn_follow`（OutlinedButton，文案"追番/已追番"）。缓存键**必须用 season_id**——远端追番列表页拿 media_id 写同一前缀，是 bug，没抄。
- **批次 2 两行选集卡片**（`ab38b1e`）：新增 `app/src/main/java/com/RobinNotBad/BiliClient/adapter/video/EpisodeCardAdapter.kt`、`app/src/main/res/layout/cell_episode_card.xml`（固定 128dp：第一行集号 + `episode_playing` 声波图标 + 会员/限免角标，第二行长标题）、`app/src/main/res/drawable/ic_episode_playing.xml`。**不复用 `MediaEpisodeAdapter`**（那套是单行 MaterialButton，直播页清晰度/线路也在用）；颜色走 `ColorScheme`、圆角交给外观档位，不照抄远端的固定色 drawable。
- **批次 3 季切换**（`46e726f`）：新增 `SeasonTabAdapter` + `cell_season_tab.xml`，`fragment_media_info.xml` 里 `rv_season_tabs` **取代旧 `section_choose` 弹窗按钮**；`BangumiApi.getSeasonDetail/parseSeasonDetail`（只发 `pgc/view/web/season`，**不发 user/status**，因为季级 `lastEpid` 定位属于不做的 B 项）；fragment 里改用 `sections`/`currentSeasonId`/`currentSeasonType`，切季懒加载 + `seasonCache` + `seasonSelection` 位置记忆，播放时用**当前展示的季**调 `episode.toPlayerData(currentSeasonId, currentSeasonType)`（不是 `info.season_id`）。
- **批次 4 状态文案 + 全量选集**（`2ffbd25`）：`episodeStatusText(seasonDesc, infoDesc, isFinish, mainEpCount)` 纯函数（优先级：当前季 desc → 详情 desc → "已完结/连载中，共N话" → "选集"）+ `updateEpisodeStatusText()`；`getEposideChooseDialog()` 改为拍平**当前季全部分区**（多分区前缀【分区名】），走 `TerminalDialog.singleChoice`（远端用的是裸 `AlertDialog.Builder`，本仓库硬约定禁止）。
- **批次 5 历史卡片一键续播**（`24f7d8d`）：`VideoCardHolder` 的 `media_bangumi` 分支在 `card.epid > 0` 时直接 `BangumiApi.getEpisodeFromEpid` → `JumpToPlayerActivity`（带 `card.progress * 1000`；注意 `PlayerData.progress` 是 **Int 毫秒**），失败才退回详情页。
- **批次 6 进度线**（`491d6d5`）：番剧心跳 15 秒 → **5 秒**（`PlayerActivity.progressReportIntervalMs` 改为按 `epid` 取值，投稿视频不变）；`onCompletion` 立即上报 `HistoryApi.PROGRESS_FINISHED = -1`（"已看完"），退出路径的补报保持 -1，`reportHistoryPgc` 守卫由 `progress <= 0` 改为 `== 0`；`PlayerApi.getLastPlayProgress` 要求 **`last_play_cid == 请求 cid`** 才采用 `last_play_time`（防"看着这一集点开另一集却从上一集位置起播"），观看记录兜底 `findProgressMsByAid(aid, epid)` 也带 epid 身份校验（解析抽成纯函数 `parseHistoryProgressMs`）。
- **测试**：`HistoryApiTest`（9 例，含"oid 命中但 epid 不符必须丢弃"）、`BangumiApiTest` 扩到 16 例、`EpisodeCardAdapterTest` 4 例、`BangumiInfoFragmentTest` 4 例，全绿。真机项见 §2.1。

### 3.9 双击缩放复位与横竖屏旋转冲突（26.10.12）

- **代码已修**：删除 `handleDoubleTapAction()` 中“横屏双击回竖屏”的方向分支，普通双击与双击快进的中间区域共用 `PlayerDoubleTapPolicy`；仅开启优先恢复且实际比例偏离 1 时复位缩放与居中，未缩放或关闭优先恢复时播放/暂停，不改变方向。
- **设置兼容**：保留 `player_doubletap_restore_screen` 存储键，名称与搜索索引同步为“有缩放时，双击优先恢复缩放”，描述明确“只转屏不算缩放、复位保持当前方向”。旧 `can_reset` 标志删除。
- **验证**：`:app:assembleDebug :app:testStandardDebugUnitTest :app:testCoexistDebugUnitTest --no-configuration-cache` 全部 `BUILD SUCCESSFUL`；机器结果两 flavor 各 **54 suites / 515 tests / 0 failures / 0 errors / 0 skipped**。其中新增 `PlayerDoubleTapPolicyTest` 10 例，验证方向不参与判据、真实缩放/单轴缩放、复位后再双击、开关关闭、禁用缩放、比例浮点误差及直播。**纯判据单测不等于实际转屏与触摸事件回归**，手表真机结果未确认（§2.1）。

### 3.10 设置页导航箭头改朝下（26.10.13）

- **问题**：设置主页面的一级分组入口（`res/layout/cell_group_header.xml` 的 `group_arrow`）与分组内页的导航项（`res/layout/cell_setting_nav.xml` 的 `nav_arrow`）右侧箭头都是 `arrow_forward`（向右），用户反馈应朝下。
- **改动**：两个布局的箭头 `android:src` 由 `@drawable/arrow_forward` 改为 `@drawable/arrow_down`；`activity/settings/SettingMainActivity.kt` 里两处硬编码 `setImageResource(R.drawable.arrow_forward)`（`addResult()` 的搜索结果卡片、`addGroup()` 的分组入口）同步改为 `arrow_down`，注释「箭头始终向前，表示跳转」改为「箭头朝下，表示进入下级」。
- **注意**：`arrow_down.xml` 固有尺寸 16dp，比 `arrow_forward` 的 18dp 略小；两处 ImageView 都是 `wrap_content`，差异约 2dp，手表上不可辨。`arrow_down` 另被消息页 / 搜索页 / 媒体信息页等多处复用，**未改其固有尺寸**以免波及。
- **验证**：`:app:assembleDebug` 两 flavor `BUILD SUCCESSFUL`（1m4s）。纯资源与静态设置，无逻辑分支，未加单测。

### 3.11 弱网下详情页中央加载遮罩盖住已加载的评论（26.10.13）

- **现象**：视频详情页切到评论栏，评论已经加载出来，但中央的「2233」加载图仍盖在列表上（还吃掉触摸）。**弱网下复现概率明显升高**，属偶发。
- **根因**：`activity_simple_viewpager.xml` 的 `loading` 是 ViewPager 的兄弟节点、盖在最上层，由宿主的 `crossFade(loading, fragmentView)` 收起。而 `VideoInfoFragment.initView()` 把 `onFinishLoad()` 放在了后台**取流预取**（`PlayerApi.getVideo`）之后——取流是弱网下最慢的一步；同时 `viewPager.offscreenPageLimit = fragmentList.size` 会把评论页一并建好（评论请求快得多），带 `seekReply` 时还会直接 `currentItem = 1` 落在评论页。于是"评论已出、遮罩未收"。
- **修法（`activity/video/info/VideoInfoFragment.kt`）**：`onFinishLoad()` 从取流预取的回调里移出，改到 `initView()` 末尾（内容装配完成即收起）；预取保留，只负责填 `playerData`，未就绪时 `playClick`/`startDownloadFlow` 已有"视频信息还在加载中"兜底。
- **顺带修掉一处同类隐患（`activity/video/info/BangumiInfoFragment.kt`）**：`initView()` 的"敬请期待"分支（没有可用季/集）提前 `return` 且从未调 `onFinishLoad()` → 遮罩**永不收起**，用户永久停在加载图上。已在该分支补调用。
- **未动**：`LiveInfoActivity`（另一套布局，且在数据到达前就收起，口径不同）；`OpusInfoFragment.onFinishLoad`（声明为 `Runnable?` 但全工程无人赋值，是死字段，清理不属本次范围）。
- **验证**：`:app:assembleDebug :app:testStandardDebugUnitTest :app:testCoexistDebugUnitTest --no-configuration-cache` 全部 `BUILD SUCCESSFUL`（1m15s），两 flavor 各 **55 suites / 534 tests / 0 failures / 0 errors / 0 skipped**（本用例数含工作区其他未提交的 `service/download/DownloadStreamTransferTest.kt`）。改动是 UI 时序（View 可见性），**无纯 JVM 单测可覆盖**，真机弱网复测见 §2.1 与回归清单第 248 条。技术口径已记入 `docs/architecture-map.md` §7.39。

### 3.12 下载完整性 C3：高速单连接不能将残片报成功（26.10.13）

- **已修范围**：`DownloadService.downFileSpeedSingle`（含分片失败回退）接入 `service/download/DownloadStreamTransfer.kt`。真实 EOF + 非空 + 已知长度相等才成功；GET 长度优先 HEAD，HEAD 错误页长度不作媒体大小，未知长度不伪造为 1。
- **保护**：网络读取与文件写入失败分开，资源关闭成功后才发布阶段完成；非暂停失败清理本次目标，暂停保留临时文件；不删除旧媒体成品、不更改库结构、锁、默认引擎和续传行为。契约见 `docs/architecture-map.md` §7.27。
- **验证**：`DownloadStreamTransferTest` 新增 22 例真实内存流测试（完整/少一字节/超量/未知长度/空响应/1 字节/大长度/读写异常/flush/暂停/取消）；`:app:assembleDebug`、`:app:testStandardDebugUnitTest`、`:app:testCoexistDebugUnitTest` 全通过（最终调用 1m6s）；XML 实测每个 flavor **55 suites / 537 tests / 0 failures / 0 errors / 0 skipped**，本次类每版 **22 tests**。计数包含工作区既有其他修改。
- **后续状态**：音频重复的播放侧已补 §3.13；DASH 多阶段进度归零、分片进度滞后及其余待办仍见 `docs/superpowers/specs/2026-10-13-download-bug-diagnosis.md`；**本次未在手表真机或真实 CDN 上验证**。

### 3.13 本地音频重复：音轨判据与外部播放器生命周期（26.10.13）

- **已修 B1 播放侧**：`PlayerActivity` 按当前 IJK `trackInfo` 选择唯一音源；内嵌音轨优先，只有确认纯视频才启外部音频，未知时提示且不盲目双播。纯判据 `player/LocalAudioTrackPolicy.kt`，契约见 `docs/architecture-map.md` §7.4。
- **追加确证缺陷**：原 `audioPlayer = MediaPlayer().apply { prepare(); start() }` 在重建时覆盖旧引用而不释放；prepare 抛异常时新对象也尚未赋值。现先释放旧实例、创建即保存引用；重载/重试/切听视频/切页/错误/销毁清理，旧实例迟到回调不触发新播放，异步音频错误释放当前实例。
- **安全边界**：没有删除、扫描或改写用户缓存；没有增加 MediaExtractor 扫描、网络访问、新依赖或修改下载事务/数据库。B1 的下载侧原子替换、audio_only 模式切换、A1/A2 进度仍未修。
- **验证**：`:app:assembleDebug` + 两 flavor 全量单测通过（最终 1m11s），XML 每版 **56 suites / 549 tests / 0 failures / 0 errors / 0 skipped**；新增 `LocalAudioTrackPolicyTest` 每版12例，只证明纯判据，不证明真实解码/播放生命周期。**未跑手表真机**。

### 3.14 Debug 包自动化测试基建：调试桥 + 可观测性 + Agent 模式（26.10.14）

起因：`docs/review/device-test-plan.md` 的 88 条真机用例一条未执行，根因不是没人想测，而是 debug 包**没给自动化留任何入口**——`TestActivity` 是 `exported=false`（adb shell 是 uid 2000、非 root，拉不起非导出组件）、全应用仅两个导出组件且 `GetIntentActivity` 只认 4 种 `type`、日志开关改完要重启、`Logu` 的 tag 是 `类名>方法名`（`logcat -s` 筛不出全量）、`NetWorkUtil` 不记状态码/响应体/耗时、UETool 在 debug 下启动时会跳系统设置页并 `return` 掉启动主流程。**完整用法见 `docs/review/agent-test-guide.md`，取舍见 `docs/architecture-map.md` §7.40。**

- **新增 `app/src/debug/java/.../debug/DebugBridge.kt`**：exported 的 `BroadcastReceiver`，`am broadcast -n <包名>/.debug.DebugBridge` 驱动。命令：`ping`/`agent`/`get`/`set`/`remove`/`dump`/`start`/`activities`/`http`/`log`/`logs`/`logclear`/`trail`/`top`。`goAsync()` + `setResultData` 回传，结果同时落 `logs/bridge-last.txt`（个别 ROM 的 `am` 不转发 receiver 结果）。**`start` 走进程内 `startActivity`，因此能拉起全部 `exported=false` 的页面**——这是 UI 自动化做不到的；受 Android 10+ 后台启动限制，需 `appops set <包名> SYSTEM_ALERT_WINDOW allow`（结果里会提示）。
- **新增 `BiliTerminal.AGENT_TEST_MODE` + `applyLogSwitches()`**（`SettingsKeys.DEV_AGENT_MODE` / `DEV_LOG_FILE`）：日志级别、落盘、HTTP 追踪、跳过 UETool 四件事的**唯一落地处**，且改完即生效（原实现只在 `Application.onCreate` 读一次，改完要重启）。`agentMode = debugBuild && 开关`，release 下被编译期常量折叠。
- **`Logu`**：新增异步落盘（业务线程 O(1) 入队 + `rebili-log-writer` 守护线程，队列 4096 满则计数丢弃）与单文件 2MB 上限；新增 `readTail` / `clearFile` / `getLogDir`。**刻意不做同步写**——`Logu.d` 会被 UI 线程调用。
  - **logcat 的 tag 一律没动，仍是 `类名>方法名`**：改的过程中一度统一成常量 `ReBili`，随后发现这会作废 `docs/review/device-test-plan.md` D3-11~D3-14 四条写死的 `adb logcat -s "PlayerActivity>onPrepared"` / `"PlayerActivity>getExtras"` / `"PlayerActivity>checkStall"` 诊断命令（`api/NoteApi.java` 的 `BiliNote` 也是同一套契约），**已回退**。logcat 输出与改动前逐字节一致，「一次抓全」改用 `adb logcat --pid=$(adb shell pidof -s <包名>)` 或读落盘日志。
- **`NetWorkUtil.HTTP_TRACE` + `HttpTraceInterceptor`**：每次请求落一行 `方法 脱敏URL -> 状态码 (耗时ms) | req=… | res=…`，URL 里 `access_key`/`csrf`/`bili_jct` 等值替换成 `***`。只预览文本类响应（二进制跳过），用 `peekBody` 而非 `body.string()`（后者会把流读干）。
- **`SplashActivity.ensureUEToolOverlayPermission()`**：Agent 模式下整体跳过——原来未授权会 `return` 掉启动主流程并跳系统设置页，授权后悬浮窗还会盖住界面吞掉点击。
- **`TestActivity` 改为 `exported="true"`**（debug 清单内），两个导出组件都挂 `android:permission="android.permission.DUMP"`：DUMP 是签名级权限，普通第三方应用拿不到，adb shell 持有（`dumpsys` 就靠它），本应用天然放行。**别摘掉它**——那等于让同设备任何应用都能 dump 登录态。
- **验证**：`:app:assembleDebug` 通过（49s，无新增编译告警）；`:app:assembleRelease` 通过（2m14s）；两 flavor 单测 **56 suites / 554 tests / 0 failures / 0 errors / 0 skipped**（基线 549 + 新增 5 例 `NetWorkUtilTest.maskUrl_*` 安全回归，锁住「URL 里的 `access_key`/`csrf` 值必须打码」）。静态核实边界：**release 合并清单里 `DebugBridge` 与 `TestActivity` 均 0 命中**，`app-standard-arm64-v8a-release.apk` 的 `classes.dex` 里 `DebugBridge` 字符串 0 命中（清单里那处 `android.permission.DUMP` 来自 `androidx.profileinstaller` 的 `ProfileInstallReceiver`，与本次改动无关，改动前就在）。

#### 3.14.1 真机实测（26.10.14，Android 12 / x86_64 模拟器，`coexistDebug`）

首轮真机跑通，**并实测抓出 3 个只有上手才会暴露的缺陷**（已全部修复并复验）：

1. **`start` 短类名解析漏子包**：原先靠一张硬编码包名表挨个 `Class.forName` 猜，实测 `VideoInfoActivity` 报「找不到」——它在 `activity.video.info` 下，多一层子包就漏。改为**从 PackageManager 的清单匹配简单名**（实测 104 个页面无重名，匹配无歧义），再也不会过期。
2. **`start` 静默失效**：只加 `FLAG_ACTIVITY_NEW_TASK` 时，若目标页恰好是某个已存在任务的栈根，系统**复用任务而不新建实例**——`startActivity` 只是把任务提到前台，栈顶仍停着别的页，而命令照样返回 `OK`。现默认叠 `FLAG_ACTIVITY_CLEAR_TOP`（要压栈不清栈用 `--ez keepstack true`）。
3. **教程页盖住目标页**（全新安装必现）：`BaseActivity.autoTriggerTutorial()` 在 `Tutorials.forPage(javaClass)` 有未读教程时启动 `TutorialPagerActivity`，GUIDE 类还要求「翻到最后一页 + 停留 3 秒」才能点已阅，靠 UI 自动化点掉成本极高。新增 `notutorials` 命令，走官方 API（`Tutorials.all` + `TutorialStore.markRead`）一次标记 11 篇为已读。

另外两处实测调整：`appops` 在 Android 12 上不存在，手册改用 `cmd appops`；HTTP 追踪的 WBI 指纹参数（`dm_img_list` 等）与响应体预览会撑爆日志（单行 2200+ 字符），已加去噪与限长。

**实测结论**：`am broadcast` 的 result data 回显正常；`cmd appops set … SYSTEM_ALERT_WINDOW allow` 的效果有系统日志为证（`W ActivityTaskManager: Background activity start for <包名> allowed because SYSTEM_ALERT_WINDOW permission is granted.`）；`am start -n` 拉非导出页面确实被拒（`SecurityException: … not exported from uid 10045`）；**12 个页面（8 个无参 + 4 个带 extras）批量回归 12/12 PASS**；HTTP 追踪确实抓到了**应用自身**发出的请求（推荐页 `rcmd` 返回真实数据），不只是调试桥的 `http` 命令；并实拍到风控现场（`-> 412 (43ms) | res=<!DOCTYPE html> … <title>出错啦! - bilibili.com</title>`），正是改造前「风控 HTML / `-412` / 请求没发出去」三者不可分辨的那种场景。

**实测又改了两处**：① 日志取回路径——`adb shell cat` 与 `adb pull` 可用，**`run-as` 读外部目录报 `Permission denied`**（它只进得了应用内部数据目录，外部 `Android/data/.../files/` 不在其挂载命名空间），手册已改；② HTTP 追踪去噪与限长（WBI 指纹参数值换 `…`、URL 600 字符、响应体预览 600 字符），实测单行 2200+ → 949~1130 字符，同样工作量 `http.log` 75KB → 17KB。

**仍未覆盖**：真手表（圆屏/表冠）、Android 10 及以下、`http` 命令在真实登录态下的表现、`run-as am broadcast` 这条兜底路径。

### 3.15 线上崩溃两条：WebView 建不出来 / 系统设置页不存在（26.10.14）

同一台设备（`xiaomi grasslte M2505W1`，SDK 34，即小米手表）在 2026-10-13 23:15 连报两条，**都是「设备能力缺失」却把整个应用带崩**，本可优雅降级。

**① `CaptchaWebViewActivity` — `Error inflating class android.webkit.WebView`**

- **现场**：`Unable to start activity ComponentInfo{…/CaptchaWebViewActivity}: InflateException: Binary XML file line #59 in …:layout/activity_captcha_webview: Error inflating class android.webkit.WebView`，根因是 `WebViewFactory.getProvider()` 抛 `UnsupportedOperationException`。
- **判据**：`WebViewFactory.getProvider()` 里那句 `throw new UnsupportedOperationException("For security reasons, WebView is not allowed in privileged processes")` 只在**进程 uid 是 root(0) / system(1000)**（或应用声明了 `android.uid.system` 的 `sharedUserId`）时命中——框架层从 5.1 起就禁止在特权进程里创建 WebView。**本工程全仓库没有任何 `sharedUserId` 声明**，所以是那台手表把应用跑在了特权进程里。另有一种可能性较低的解释是 ROM 改过 WebView 实现，但**无论哪种，应用都不该崩**。
- **为什么崩得这么彻底**：`activity_captcha_webview.xml` 第 55~59 行**写死了一个 `<WebView>`**，inflate 阶段就抛异常，异常直接冒到 `ActivityThread` 把整个应用带崩——而这个页面是完全可降级的叶子页。
- **修法**：布局里改成 `FrameLayout` 容器（`@+id/webViewContainer`），WebView 改**运行期创建**：进页面前先 `isWebViewAvailable()` 探一次（建一个再 `destroy()`，捕获 `Throwable`），探不通就提示「请改用扫码登录或 Cookie 登录」并以新增的 `RESULT_UNAVAILABLE` 收尾；`initWebView()` 里再兜一层 try/catch（探针过了不代表后续构造一定成功）。
- **为什么是「提示改用扫码」而不是「想办法建出来」**：`LoginActivity` 三个 tab 是 **扫码 / 密码 / 短信**，扫码登录（默认第一个 tab）**完全不依赖 WebView**，另有 `SpecialLoginActivity` 的 Cookie 登录。给一条走得通的路，比让用户对着崩溃报告发呆强。两个密码/短信 Fragment 原来只报「验证已取消」，会把「设备不支持」误报成「用户自己取消了」，现按 `RESULT_UNAVAILABLE` 分开报。

**② `FileUtil.requestStoragePermission` — `ActivityNotFoundException`**

- **现场**：`Unable to start activity ComponentInfo{…FeedbackActivity}: android.content.ActivityNotFoundException: No Activity found to handle Intent { act=android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION dat=package: }`。
- **判据**：`FileUtil.requestStoragePermission()` 直接 `activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION))`，**没有任何兜底**。手表 ROM 普遍不带完整设置应用，这个 action 没有对应 Activity 是常态；异常从调用页的 `onCreate` 冒出去就是「Unable to start activity」。**这不是 `FeedbackActivity` 自己的问题**——全工程三个调用点（`DownloadActivity`、`VideoInfoFragment`、`LocalListActivity`）都会踩。
- **修法**：三级降级——① 带包名直达本应用页面 → ② 通用的 `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` 列表页 → ③ 只弹提示教用户手动授权。`startSettings()` 吞掉所有异常（不只 `ActivityNotFoundException`：部分 ROM 该页存在但未导出，抛的是 `SecurityException`，处理方式相同）。

**验证**：`:app:assembleDebug` + 两 flavor 单测 + `:app:assembleRelease` 全绿。**两条都未在出问题的那台手表上复验**（本机 adb 上已无在线设备），也**未做「WebView 缺失」的模拟测试**——可用的复现手段是 `adb shell pm disable-user com.android.webview` 后走一遍验证码页，届时预期是「提示 + 优雅退出」而不是崩溃。

### 3.16 点进视频详情页不进观看记录（26.10.15）

**现象（用户实测）**：点进视频详情页，观看记录里什么都没有；但只要播一下这个视频，记录就出现了。先前描述成"只有点进已经记录过的视频才会记录"——同一个根因的两面：`progress > 0` 的稿件会被重新上报一遍，`progress == 0` 的新稿件整条请求根本不发。

**根因**：`activity/video/info/VideoInfoFragment.kt` 的 `initView()` 里，26.10.02（`fd947f0`，P8 playerData 可见性那一批）给详情页的观看记录上报加了 `if (data.progress > 0)` 闸门，注释说"报 0 会把服务端该分P的续播进度覆盖掉"。闸门挡的是真问题（多P视频：上次看第 3P、详情页预取第 1P，报 `(aid, cid1, 0)` 会把第 3P 的续播抹成 0），但把"新稿件登记"连带挡掉了——`PlayerApi.getVideo` 对没有记录的稿件恰好会归一化出 `cidHistory = cid`、`progress = 0`。播放器那侧 `PlayerActivity` 自己会上报，所以"播了才有记录"，缺口只在详情页这一处。

**修法**：判据从"有没有进度"换成"上报目标是不是本次打开的这一P"——`shouldReportHistoryOnEnter(cidHistory, cid) = cid != 0 && cidHistory == cid`，上报的 cid 由 `cidHistory` 改为 `data.cid`。新稿件 `cidHistory == cid` → 报 0 登记；本P有进度 → 原样回写；别的P → 跳过、续播不动。口径见 `docs/architecture-map.md` §7.38。

**真机验证（26.10.15 12:41，MuMu Nebula 模拟器，Android 12 / SDK 32 / x86_64，adb `127.0.0.1:7555`，已登录）**：测试对象用接口 `x/web-interface/view?bvid=` 解析得到（未硬编码任何 av），先用 `x/web-interface/history/cursor` 翻页确认**不在**历史记录里，再进详情页。结果：

- 调试桥 `http` 与 logcat 双通道确认 `POST https://api.bilibili.com/x/v2/history/report` 请求体 `aid=117052639545707&cid=40676492842&progress=0&platform=pc&csrf=…`，响应 **`{"code":0,"message":"OK"}`**；
- 再查 `history/cursor`，目标已出现在**第二页**（`view_at=1792039303` 恰为请求时刻，`progress=0` → App 内显示"还没看过"），确认服务端真的落库，而非仅发出请求；
- 判据链同时被佐证：`PlayerApi` 日志 `history-report>0,40676492842` 即"服务端无记录 → `cidHistory` 归一化成本 P → 判据放行"。

**踩过的两个坑**（重测时务必避开）：① 首个对照组选了个**已被记录**的 av（`170001`，failover：`progress>0` 时旧代码照样上报，A/B 因此无效）——必须先用 `history/cursor` 比对确认目标不在记录里；② 调试桥 `start --es extras 'bvid=…'` **不生效**，会回落成 `aid=114514` 并发 `稿件不可可见`（`TerminalContext.fetchVideoInfoByAidOrBvId` 的分支问题），必须传 `aid=`。

**仍未覆盖**：多P 视频"进度属于别的分P"的真机断言（需要一个看到第 3P 的账号）、番剧详情页的登记（未实现，见 §7.38）。

### 3.17 真实多P 切P 失败卡死 + 虚拟合集切集后进度写错稿件（26.10.15）

**现象**：在真实多P 视频里切P（手动选集或"自动下一P"）**直接失败、播放器卡在原地**。

**根因（真机复现，日志见下）**：`activity/player/PlayerActivity.kt` 的 `switchToOnlinePage` 用 `aid != cids[pageIndex] && cidValue > 0 && pagenames.size > 1` **猜**这份 `pagenames/cids` 是不是"收藏夹虚拟合集"。这个猜测在两种模式下都错：

- **真实多P**：`cids` 存的是真 cid，av 号与 cid 永不相等 → 恒判为虚拟合集 → 把 cid 当 aid 去 `x/web-interface/view?aid=<cid>` → `62002 稿件不可见` → `playerData.cid` 落 0 → 取流 `playurl?avid=<cid>&cid=0` → `-400 请求错误` → 抛异常被 catch，播放器卡住。
- **虚拟合集**：`cids` 存的是各视频的 **aid**，但 Activity 的 `aid` 字段只在 `:412` 从 Intent 读一次，切集后**只同步了 `cid`、没同步 `aid`** → 进度上报变成 `reportHistory(视频1的aid, 视频N的cid)`，**把 N 的进度写进 1 的记录**（污染 3.16 刚修好的历史上报链路）。

**修法**（`PlayerData` + `PlayerApi` + `PlayerActivity` + `FavoriteVideoListActivity`，共 4 处）：给 `PlayerData` 加显式字段 `virtualCollection`（进 Parcelable，因为要过 `startGettingUrl` 那一跳）→ `jumpToPlayer` 透传成 Intent extra → 播放器读进 `isVirtualCollectionMode` 取代猜测 → **并在切页后同步 `aid`**（与既有的 `cid` 同步并列）。

**真机验证（26.10.15 14:16，同一台 MuMu Nebula）**：

| 场景 | 修复前 | 修复后 |
| --- | --- | --- |
| 真实多P（170001，10P）P1→P2 | `view?aid=275431`(cid 当 aid) → 62002 → `playurl?avid=275431&cid=0` → -400，**卡在 03:19** | `playurl?avid=170001&cid=275431` → 200，P2 正常播；周期上报 `aid=170001 cid=275431` code=0 |
| 收藏夹虚拟合集切集 | 可切，但上报 `aid=`第一个视频、`cid=`第N个视频 | `view?aid=117422627490443` → 200；上报 `aid=117422627490443 cid=42657383242` **双正确** |

> 复现手法（不依赖点按 UI）：调试桥 `set --es key player_default_autonext --es value on --es type string` 打开"自动下一P"，把进度拖到末尾（点 `1400,2372`，即 `videoprogress` 条），`onCompletion` 会自动触发 `switchToPage` —— 与手动选集是同一条代码路径。**注意三态设置先看模式键**：只写 `player_last_autonext` 没用，`resolveTriState` 见模式为 `off`（默认）就直接返回 false。

**仍未覆盖**：番剧（`epid != 0`）切集走的是 `switchToOnlinePage` 的同一分支，但番剧不进虚拟合集、`cids` 也是真 cid，预期不受影响，未单独跑。

### 3.18 收藏夹连播遇到「真实合集」自动套一层（26.10.15）

**需求**：在收藏夹虚拟合集里连播到一条**属于某个真实合集**（`ugc_season`）的视频时，直接以该合集起播；并在选集卡片里能看出"现在在哪一层"、能回主合集。

**关键成本结论（推翻了先前的判断）**：这件事**零额外请求**。`PlayerActivity.switchToOnlinePage` 的虚拟合集分支本来就要调 `VideoInfoApi.getVideoInfo(aid)` 拿首个 cid，而它内部的 `getInfoByJson` **已经解析了 `ugc_season` → `videoInfo.collection`**（`VideoInfoApi.java:41-48 → :118 → :244-246`）。原来这份数据解析完就扔，只用 `cids[0]`。收藏夹入口 `playFavoriteVirtualCollection` 同理。

**实现**（4 处）：

- `model/Collection.toPlaylist(Collection)`：**纯函数 + 6 例 JVM 单测**。跨节展平成有序 `(aid, cid, title)`；`sections` 为空时退回 `cards`（"收藏的合集"只填 cards）；aid/cid 缺失的脏数据跳过。
- `PlayerData` 加 `collectionId` / `collectionTitle` / `returnPlaylist`（一层返回栈），三者都进 Parcelable。
- `PlayerActivity`：`switchToOnlinePage` 里检测 `collection` → `enterCollectionPlaylist()` 整体换播放列表并压栈；`returnToCallerPlaylist(advance)` 弹栈；合集播完在 `onCompletion` 里自动弹栈（受 `auto_next_enabled` 管，与"自动下一P"同一档语义）。
- `FavoriteVideoListActivity.playFavoriteVirtualCollection`：首条即合集也直接以合集起播，收藏夹列表压成返回栈。
- 选集卡片：标题改为「合集 · 名（i/n）」或「收藏夹名（i/n）」，并新增一行「← 返回主合集」（仅当 `returnPlaylist != null` 时可见）。

**设计取舍**：合集播放列表沿用"cids 装 aid"的形状（与收藏夹虚拟合集一致），切集时反查 cid——那次请求本来就要发，所以不额外增加开销，也免了给播放器加第三种模式。合集里每一集是**不同的视频**，所以起播时 `aid`/`cid` 都要换成那一集的。

**踩到的坑（真机复现后修）**：点「返回主合集」会**立刻又被切回合集**——返回的位置正是触发切换的那条合集视频。加 `suppressedCollectionId` 按**合集**抑制（不是全局抑制，否则别的合集也永远切不进去了）。显式操作压过隐式自动化。

**真机验证（26.10.15 15:00~15:07，MuMu Nebula）**：

| 场景 | 结果 |
| --- | --- |
| 收藏夹点「这次把荧妹也调小了一点」（属《原神》合集 336 集，位于合集第 336 集） | `jumpToPlayer: 视频标题>原神`，选集卡片标题 `合集 · 原神（336/336）` + 「← 返回主合集」 |
| 点「返回主合集」 | `MsgUtil: 返回主合集`，标题变回 `这次把荧妹也调小了一点（9/20）`，「← 返回主合集」消失，不再被切回 |
| 合集播完 | 进《登 神 の 路》（3 集）播到末尾 → `MsgUtil: 合集播放完毕，继续收藏夹` → 接着放收藏夹下一条（`【纸绘MV】d0tc0mmie…`） |

**未覆盖**：`returnPlaylist` 只存一层，多层嵌套（合集里的视频又在另一个合集里）不处理；`PageSelectorAdapter` 对合集仍显示「P1/P2…」而不是「第 1 集」（纯文案，未改）；没有开关能关掉这个自动切换（用户明确要求"遇到就切"，若要开关需另加）。

### 3.19 线上崩溃：账号切换页「销毁后启动图片加载」（26.10.16）

**现场（用户提供的混淆栈）**：

```text
java.lang.IllegalArgumentException: You cannot start a load for a destroyed activity
  at R1.l.d(SourceFile:83)      ← Glide RequestManagerRetriever.assertNotDestroyed
  at Q0.b.run(SourceFile:53)    ← 主线程 Handler 里的 Runnable
```

设备 Android 5.0.2（API 21），**打开账号切换页后等几秒即触发**，弱网 / 低性能设备更易复现。

**根因（已在源码定位，唯一命中点）**：`activity/settings/login/AccountSwitchActivity.kt` 的 `createAccountCard()`。账号没有缓存过昵称/头像时，走 `CenterThreadPool.run { UserInfoApi.getUserInfo(mid) → runOnUiThread { Glide.with(this)… } }`。`UserInfoApi.getUserInfo` 是一次真实网络请求，**请求期间用户完全可以离开这个页面**——点左上角标题 `finish()`、点另一个账号切走（`startActivity(SplashActivity)` + `finish()`）、或低内存设备上被系统回收。请求回来时 `runOnUiThread` 的 Runnable 才落到主线程，`Glide.with(activity)` 一进来就 `assertNotDestroyed` → 抛异常 → 无人捕获 → 应用崩溃。**弱网只是把这个时间窗从几十毫秒拉长到几秒**，低性能设备只是更容易在后台被回收——都不是原因本身。

**为什么本工程比原生 Glide 更容易踩**：`activity/base/BaseActivity.kt:473` 重写了 `isDestroyed()`，语义是「已销毁 **或** 正在 finish」。Glide 的判活是虚调用，因此**连「只是正在 finish、还没 onDestroy」的窗口也会崩**。另外 `Glide.with(view)` 会沿 View 的 Context 往上找宿主 Activity，最后走同一个判断——所以「传 View」的写法（`GlideUtil.request/requestRound`）同样会崩，不止传 Activity 才会。

**修法（三处，`AccountSwitchActivity.kt`）**：

1. **异步回调内部判活**：`runOnUiThread {}` 里第一行 `if (isDestroyed) return@runOnUiThread`。**必须在 Runnable 内部判**，只在 post 之前判是错的——判完到 Runnable 真正执行之间，主线程完全可能已经跑完 `onDestroy`。请求起跑前也补了一次 `if (isDestroyed) return@run`，省掉一次注定无用的网络往返。
2. **页级 `RequestManager`**：新增字段 `requestManager`，所有头像加载统一走 `loadAvatar()`（内含判活），不再每张卡各写一次 `Glide.with(this)`。
3. **`onDestroy` 收尾**：新增 `override fun onDestroy()`，在 `super.onDestroy()` **之前**调 `clearAvatarRequests()`（`super` 一跑，Glide 挂在生命周期上的 RequestManager 已进入 destroyed，再 clear 属于事后补救），随后置空 `requestManager`。

**顺带修的同类缺陷（同一页面）**：`refreshAccountList()` 每次 `onResume` 都重建一遍列表，而 `removeAllViews()` **只是把 View 从父容器摘下来，Glide 不会因此取消请求**——ViewTarget 仍吊着那个 ImageView，一轮轮往上堆（低性能设备上先卡顿、后 OOM）。现在 `clearAvatarRequests()` 在重建列表前也调一次，把上一轮请求显式清掉。

**新增可复用落点**：`util/GlideUtil.canLoad(View)`（Java），判据是「沿 ContextWrapper 链找宿主 Activity；**找到了才判** `isFinishing() || isDestroyed()`，找不到（View 挂在 Application 上下文上）一律放行」——放行分支保证与改动前行为一致。三个 `request*` 方法已内建这道闸，调用方只有 `RecentUpAdapter` / `HotSearchAdapter` 两处，都在主线程 `onBindViewHolder` 里，无误伤。口径见 `docs/architecture-map.md` §7.44。

**验证**：`:app:assembleDebug` + 两 flavor 单测全绿（`testStandardDebugUnitTest` / `testCoexistDebugUnitTest` 各 **58 suites / 568 tests / 0 failures / 0 errors / 0 skipped**，读自 `app/build/test-results/`）。本改动无纯逻辑可抽（判活是一行谓词），**未新增单测**；`AccountSwitchActivity` 是 Activity，工程无 Robolectric，JVM 单测覆盖不到。

**未真机复验**：需要在弱网下走「打开账号切换页 → 立即返回/切账号」的用例，logcat 判据是**不再出现** `IllegalArgumentException: You cannot start a load for a destroyed activity`。本轮无在线设备。

**同类残留（本轮未动，同一崩溃类）**：`activity/user/EditProfileActivity.kt:125→135` 的判活写在 `runOnUiThread` **之外**（块内第 135 行直接 `Glide.with(this@EditProfileActivity)`），仍留着「判完 → Runnable 执行」的窄窗口。全工程这种「判活写在 post 之外」的形态共 4 个文件 10 处（`VoteInfoActivity` 4 处、`EditProfileActivity` 2 处、`EditSignActivity` 2 处、`EditUserInfoActivity` 2 处），其中**只有 `EditProfileActivity` 那一处在块内加载图片**，其余块内不涉及 Glide。

### 3.20 日志基建 + 崩溃 / 反馈附带日志 + 「我的反馈」页面（§四十八，26.10.16）

四块一起做。**技术口径与取舍统一记在 `docs/architecture-map.md` §6.5（日志能力 / 我的反馈），这里只记现状与待办。**

**一、日志基建（客户端，新增）**

- `util/Logu.java` 新增**内存环形缓冲**：`ArrayDeque<String>` + 累计字符数，上限 `RING_MAX_CHARS = 65536`（约 128KB），超限从头部逐条淘汰；读写一律 `synchronized (RING)`。API：`ringSnapshot()` / `ringChars()` / `ringClear()`。
- **只记 W/E/F 级别**（`w/e/wtf` 本来就恒执行、本来就调 `getCaller()`，故**不新增开关、不新增栈遍历，release 行为零变化**）；**不落盘**（release `LOG_TO_FILE` 恒 false，内存缓冲是 release 下唯一能拿到历史日志的手段）。
- 新增 `util/LogSanitizer.kt`（object + `@JvmStatic`）：按 `键=值` 脱敏、**不解析 URL**、有快速路径、正则按键名长度降序拼接；键名复用 `NetWorkUtil.SENSITIVE_QUERY_KEYS` / `NOISE_QUERY_KEYS`（二者由 `private` 改 `public`，`maskUrl()` 未动）。
- `Logu.toFile()` 顺序改为**先脱敏 → 再 `shorten()` → 同时进缓冲与落盘**（先截断再脱敏会让超长行凭证只被截掉一半）。

**二、崩溃报告附带日志**

- `ErrorCatch.java` 崩溃瞬间多塞 `intent.putExtra("ring_log", Logu.ringSnapshot())`（与既有 `trail` 同一条路，只取值不做采集）；`util/CrashReporter.kt` 的 `buildPayload` / `upload` 加 `ringLog` 参数，`mergeLog()` 把轨迹与应用日志合并进**同一个 `log` 字段**（服务端零改动，老数据与新数据同构）。
- **`CatchActivity` 跑在 `:error_activity` 独立进程，那边的 `Logu` 缓冲恒为空**，只能读 Intent extra、**不能**调 `ringSnapshot()`。
- 崩溃页（`activity_catch.xml` + `CatchActivity.kt`）加「附带应用日志」勾选框，**默认勾选**；缓冲为空时置灰并改文案「本次没有可附带的日志」。

**三、反馈附带日志 + 本地导出**

- `FeedbackActivity` 加「附带应用日志」勾选框，**默认不勾选、不持久化**（与崩溃页相反：反馈是用户主动行为）。
- 新增 `util/LogExporter.kt`：子线程把 `ringSnapshot()` 写成应用私有 `cache/terminal/rebili-log-yyyyMMdd-HHmmss.txt`，经 FileProvider + `ACTION_SEND` 分享。
- `TerminalApi.buildFeedbackPayload` / `submitFeedback` 加 `log` 参数（服务端该字段**可选**，老客户端不发就是空串）。
- **`ringClear()` 只在「勾选 + 提交成功」时清空**：没勾选就没送出去、失败要留着重试。

**四、服务端（HK，已上线）+「我的反馈」页面**

- 新增 `GET /terminal/feedback/mine`（`X-Install-Id` 认领 + 可选 `X-Mid` 头）；`feedback` 表加 `log` 列（`SCHEMA_VERSION` 1 → 2）；`/terminal/feedback/submit` 收可选 `log`。
- `/admin/api/feedback` 与 `/admin/api/crash` 列表都改回 `LENGTH(log) AS log_len`（**不回全文**），新增 `/admin/api/feedback/detail?id=` 与 `/admin/api/crash/detail?id=` 拉全文；`console.html` 反馈 / 崩溃详情抽屉都能显示日志（异步拉 detail，用 `textContent` 写入而非拼 HTML）。**管理台此前根本看不到 `crash.log`**（列表 SELECT 里没有、详情也不显示），本次补上。
- 客户端新增「我的反馈」列表 + 只读详情（`activity/feedback/`），入口在设置页「反馈与统计」分组（`SettingGroupActivity` + `SettingsIndex` 搜索索引，不新增菜单项）；状态文案用「**待受理 / 已受理**」——`handled` 是开发者待办状态，**不写「已处理」**。

**真机验证结论（26.10.16 晚，MuMu Nebula 模拟器 / Android 12 / SDK 32 / x86_64；全部通过）**

- [x] **内存缓冲在累积**：Agent 模式下 `v/d/i` 全开，HTTP trace 行会自动进缓冲——印证「详细模式一开，`v/d/i` 自动进缓冲、不需要额外逻辑」这条设计（见 `docs/architecture-map.md` §6.5）。
- [x] **脱敏生效**：经调试桥新增命令 `logring --es inject` 注入 `SESSDATA=SECRET123 access_key=xyz normal=keep`，快照里是 `SESSDATA=*** access_key=*** normal=keep`——敏感值被替换、`normal=keep` 未被误伤，caller tag 仍是 `DebugBridge>logRing`（`Logu` 的 tag 契约没被破坏）。这条同时把「`LogSanitizer` 只在 JVM 单测里跑过」补成了真实链路核对。
- [x] **崩溃页勾选框**：`ring_log` 非空时 `checked="true" enabled="true"`；为空时 `checked="false" enabled="false"`、文案变成「本次没有可附带的日志」。
- [x] **跨进程那条约束被实测印证**：`CatchActivity` 确实跑在 `:error_activity` 独立进程（`ps` 可见），系统级 `topResumedActivity` 能确认它在前台，而调试桥的 `top` 命令（读主进程弱引用）**看不到它**——这正是「崩溃页不能直接调 `Logu.ringSnapshot()`」的现场证据。
- [x] **反馈端到端提交带日志**：反馈页输入内容 + 勾选「附带应用日志」+ 提交 → 服务端返回编号 4；`/admin/api/feedback/detail?id=4` 查到 `log` 为 **4152 字符 / 19 行**，格式 `MM-dd HH:mm:ss.SSS 级别 类名>方法名 | 正文`，正则自检无明文凭证。
- [x] **「我的反馈」列表页**：显示分类「功能异常」、状态「待受理」、正文与时间。
- [x] **详情页**：`FeedbackDetailActivity` 正常；该条没有开发者回复时不显示空的回复区。
- [x] **`ringClear()` 生效**：提交成功后立刻查缓冲为空，随后新日志能重新累积。
- [x] **隐私升版生效**：隐私同意版本号（`SettingsKeys.PRIVACY_CONSENT_VERSION` / `PRIVACY_PROMPTED_VERSION`）由 2 升到 3 后，模拟器上此前同意过 v2 的用户**确实被要求重新确认**（弹出了隐私同意弹窗）。
- [x] **全程无崩溃**：`logcat -b crash` 里本应用 0 条。

**仍待办 / 未覆盖**

**补充真机验证（26.10.16 晚二轮，全部通过）**

- **崩溃自动上报端到端**：用调试桥新增的 `crash` 命令在主线程抛真实异常 → `ErrorCatch` 正确拉起 `CatchActivity`（`:error_activity` 独立进程）→ 服务端 `crash` 表收到 **id=34**，`log` 为 **11140 字符 / 45 行**：前半是页面轨迹、中间是分隔行 `--- 应用日志（内存缓冲，最近若干行）---`、后半是应用日志；**正则自检无明文凭证**。这条补上了「只验接收端、没验发送端」的缺口。
- **日志本地导出**：点「导出日志到文件」→ 生成 `cache/terminal/rebili-log-<时间戳>.txt`（实测 3771 字节），并唤起系统分享面板（`ChooserActivity`）。
- **脱敏三个维度**：噪音键 `dm_img_list=…` / `dm_cover_img_str=…`（用 `…` 而非 `***`）、Cookie 串（`;` 分隔）`SESSDATA=***; bili_jct=***`、大小写不敏感且 `refresh_token` 未被 `token` 抢先匹配。
- **详情页开发者回复**：给反馈 #4 加一条服务端回复后，列表状态由「待受理」变为「**已受理**」，详情页正确显示「开发者回复」标题 + 回复标题 + 时间 + 正文。
- **一处误判已撤销**：详情页页名实为「**反馈详情**」，此前记的「与列表页同名」不成立——那次 `uiautomator dump` 拿到的是列表页残留内容（详情页尚在启动中）。

**第三轮：用户截图反馈驱动的修复（26.10.16 晚）**

用户发来两张真机截图，暴露三个问题（其中两个是本次实现引入的缺陷）：

1. **「附带应用日志」勾选框根本不生效**（功能性缺陷，本次引入）：`CatchActivity.onCreate` 里**同步读**勾选状态后**立刻**发起上报，而用户要等页面渲染完才看得到勾选框——那时日志早已发出，「可取消」是句空话。修法：改成 `postDelayed` **延迟 3 秒**上报，状态文字分两阶段（「将在 3 秒后自动上报…」→「已自动上报」）。**实测对比**：勾选 → 服务端 `log` 10239 字符且含应用日志段（#35）；**3 秒内取消 → `log` 为 0、不含应用日志段**（#36）。
2. **「上传堆栈」按钮从未显示过**（既有 bug，非本次引入）：`upload_btn` 在 XML 里是 `visibility="gone"`，而 `CatchActivity` 的 `allowUpload` 分支**只挂了监听、没把 visibility 设回来**。这同时让 `desc_crash` 那句「建议点击**下面的按钮**上传」指向一个不存在的按钮。修法：补 `visibility = VISIBLE`；并在自动上报开启时**隐藏** `desc_crash`（两套语义不能同时摆着）。
3. **反馈页勾选框与周围卡片不对齐**（本次引入）：裸 `CheckBox` 夹在两个 `MaterialCardView` 之间，左右边距不一致（卡片内文字 8dp vs 勾选框 4dp），且 CheckBox 方块占位把文字又推右一截。修法：包进同规格卡片 + 卡片内 8dp + `MaterialCheckBox`；文案缩短为「附带应用日志（便于定位问题）」避免换行。**实测**：三个卡片 bounds 完全一致（`[32,…][1408,…]`）、间距均为 24px、文案单行。

4. **两处样式 / 间距对齐（用户第二轮截图）**：
   - 反馈页「发送反馈」按钮的 `marginTop` 是 **8dp**，而上面几个卡片全是 **6dp** —— 实测 32px vs 24px，肉眼可辨。已改齐 6dp，三处间距现在均为 **24px**（`attach_log_card` / `export_log_card` / `submit_btn`）。
   - 崩溃页的勾选框此前是**裸控件**夹在说明文字与上报状态之间，已与「自动上报状态」一起**包进同一个卡片**（与反馈页样式一致）；卡片内 8dp 水平边距，实测内容从 x=48 起（卡片起点 16 + 32px 内边距）。

5. **隐藏「上传堆栈」按钮（用户第三轮反馈）**：上一轮把它修可见后，用户点了发现上游 `api.biliterminal.cn` 一律返回「请使用最新版本上传」（那台服务器只服务其自身版本），而我加的那段解释文字很长很难看。用户拍板**直接隐藏**。做法：`CatchActivity` 不再设 `visibility = VISIBLE`（XML 里本就是 gone），代码保留并注明「上游接口按约定原样保留，将来上游若开放本版本把 VISIBLE 加回来」；`desc_crash`（「点击下面的按钮上传」）也固定 `gone`，否则它指向一个不存在的按钮。**实测**：崩溃页视图树里已无 `upload_btn` / `manual_upload_hint`。
6. **状态文字移出卡片（用户第三轮反馈）**：第 4 点里我把勾选框与 `auto_report_status` 包进了同一个卡片——**做过头了**。状态文字不可点，被卡片框着会让人以为能点。已改成卡片内只放勾选框，状态文字回到卡片外。**实测**：勾选框 `x` 从 48 起（卡片内）、状态文字从 16 起（卡片外）。

**顺带发现并修正的文案问题**：

- 反馈页顶部隐私说明 `feedback_privacy` 原写「只发送你写的内容、版本号与机型」，**没提日志** —— 与新增的「附带应用日志」直接矛盾，已补上（含「已脱敏」的说明）。属于 v3 文案的同步补齐，`PRIVACY_VERSION` 无需再升。
- **「请使用最新版本上传」不是本工程文案**：点「上传堆栈」时上游 `api.biliterminal.cn` 返回该 `msg`，被 `btnUpload.text = res.message` 原样显示。上游只服务它自身版本、会拒绝本 fork。已在该分支文案后补一句说明，免得用户以为是自己版本旧。

**仍待办**

- [ ] **服务端「我的反馈」跨设备找回未验证**：换设备 / 重装后靠 `X-Mid` 找回、`X-Install-Id` 认领——本次只验了同设备的列表 / 详情。
- [ ] **缓冲 64K 上限的淘汰行为未验证**：需要灌入超过上限的日志量才能观察到「从头部逐条淘汰」，本次未构造该场景。

### 3.21 反馈对话：用户可追加说明（§四十九，26.10.16）

用户看着详情页里那条开发者回复问「用户想继续回复咋办」。此前「我的反馈」是**只读**形态（当时拍板不做评论），本轮把它补成**一来一回的对话**。

**服务端**（`server/rebiliterminal_api.py`，1708 → 1909 行，已上线 HK）

- 新增表 `feedback_comment`（`feedback_id` / `author` / `content` / `ctime` + 来源字段），`SCHEMA_VERSION` 2 → 3。
  **v3 刻意不做 DDL**：新表定义只写在 `SCHEMA` 里、由 `init_db()` 的 `executescript` 建好；在迁移里再抄一份建表语句就是同一份 schema 有两处真相。`selftest` §19 的「迁移必须幂等」正好会抓这种「每次跑都报做了事」的步骤。
- 新增 `POST /terminal/feedback/reply`（body `{id, content}`，身份全走请求头）。**归属校验与 `/feedback/mine` 共用同一份 SQL 片段 `_MINE_SQL`** —— 两边各写一份必然漂移，症状是「列表里看得到、却回不了」，用户只会觉得功能坏了。
- **不是自己的反馈与「不存在」返回同一个 `code=404`**：区分开就等于给了一个枚举 id 的探针，能扫出哪些编号真实存在。
- **副作用：把该反馈 `handled` 置回 0（待受理）**。这是 GitHub 的 reopen 语义 —— 开发者的 `?pending=1` 列表与「待处理」计数会自动把它顶上来，**不必另建未读 / 提醒机制**。
- 对话由两个来源合并（`_db_feedback_thread`）：`announcements`（`author='dev'`，顺带承担启动弹窗通知）+ `feedback_comment`（`author='user'`），按 `ctime` 排序。**合并逻辑只有这一份**，客户端 `/feedback/mine` 与管理台 `/admin/api/feedback/detail` 的 `thread` 共用。
- 管理台：列表多回 `user_reply_count`；`console.html` 打「用户回复 ×N」标签，详情抽屉按时间交错渲染两侧消息（左侧色条区分：蓝 = 开发者，橙 = 用户）。

**客户端**

- `util/TerminalDialog.kt` 新增 `input()`（+ `Sheet.addInput` / `prepareForTyping`）：多行输入框 + **固定底栏**按钮（输入框会长高，按钮跟着滚会被顶出可视区），自动弹键盘。**外观自己画**（`colorOnSurface` 10% 透明度的圆角块）—— 7 套主题都没声明 `editTextBackground`，交给框架会落到 Material 默认值（深色主题下是一条近白下划线）。
- `model/FeedbackReply.java` 加 `author` 与 `isFromUser()`。**旧服务端不返回这个字段**，缺失 → 空串 → 非用户，正好等于升级前「只可能有开发者回复」的事实，不需要额外兼容分支。
- `TerminalApi`：`buildFeedbackReplyPayload`（纯函数，**只有 id 与 content**）+ `submitFeedbackReply`。后者走新增的 `postWithHeaders(..., feedbackHeaders())`；**其余接口仍用 `headers()`** —— 不能为了省事把 mid 加进通用请求头，那等于对所有接口默认带 UID。
- `FeedbackDetailActivity` 改成**一条按时间交错的时间线**：标题文字（「我的补充」vs 开发者标题）是主判据，用户侧另加一圈主题色描边便于扫读；只有开发者回复时区块标题仍是「开发者回复」，一旦有来回就变「对话记录」。底部「追加说明」卡片走弹窗输入。
  发送成功：本地追加 + 状态回落「待受理」+ 滚到末尾 + `setResult(RESULT_OK)`。
- **失败不丢内容**：弹窗的 `onConfirm` 是在 `dismiss` **之后**才回调的，拿不到「失败了就别关」这个中间态，所以失败时**重开弹窗并预填**刚写的内容（与 `FeedbackActivity` 失败保留输入同一原则）。
- `FeedbackListActivity` 用 `registerForActivityResult` 接结果码，**只在真的改过时才重拉**（无条件 `onResume` 重拉会让每次返回都多一次请求）。

**真机验证（MuMu Nebula，Android 12 / SDK 32）**

- 端到端：详情页 → 追加说明 → 弹窗输入 → 发送 → 状态由「已受理」变「**待受理**」、区块标题由「开发者回复」变「**对话记录**」、时间线出现「我的补充 20:47」。
- 服务端核对：`feedback_comment` 落库 1 行（`author=user`）；`feedback#4` 的 `handled` 由 1 回到 0；线上 `/feedback/mine` 返回 `[dev] → [user]` 两条；管理台列表 `user_reply_count=1`、详情 `thread` 两条、WebUI 含新代码。
- 边界：空内容点「发送」→ **弹窗不关**；点「取消」→ 正常关闭；两者服务端**都未被误写入**（仍 1 行）。
- 返回列表 → 自动重拉，状态显示「待受理」。

**未覆盖**

- [ ] **跨设备追加（`X-Mid`）未验证**：模拟器未登录，只验了同设备 / 同 `install_id`。
- [ ] **发送失败后「重开弹窗 + 预填」未构造真实断网场景**，仅代码路径审查。

### 3.22 「我的」页用户卡片与首个功能项紧贴（26.10.16）

用户截图反馈：「我的」页顶部用户信息卡与下方「关注」**紧贴成一张卡**，而「关注」以下的各功能项之间都有间隔。

**根因**：`menuContainer` 的项间距由 `divider=@drawable/empty` + `showDividers="middle"` 提供（`@dimen/linearlayout_divider` = 4dp），而 **`showDividers` 只作用于容器内「项与项之间」**——`myinfo` 是 `menuContainer` 的**前一个兄弟节点**，不在 divider 的覆盖范围内，于是与第一项零间距。不是主题、不是圆角档位、不是卡片样式的问题。

**修复**（`app/src/main/res/layout/activity_myspace.xml`，1 行）：给 `myinfo` 加 `android:layout_marginBottom="@dimen/linearlayout_divider"`。

**为什么放在 `myinfo` 的 marginBottom，而不是 `menuContainer` 的 marginTop**：`MySpaceMoreActivity` 复用同一布局并把 `myinfo` 置为 `GONE`。`GONE` 视图在 `LinearLayout` 测量时被整体跳过、其 margin 也不计入，所以「更多」页不会平白多出 4dp 顶部空白；反之若挂在 `menuContainer` 上，「更多」页就会多一截。**两者视觉结果在主页面完全相同，只有「更多」页能区分**——这是本条唯一需要小心的地方。

**实测（MuMu Nebula，Android 12 / SDK 32，1440x2560 / density 640，即 1dp = 4px）**

用 `uiautomator dump` 取绝对坐标（`dumpsys activity top` 的子 View bounds 是相对父容器的，不能用来算）：

- **基线尺寸**：单个功能项高度 200px，相邻项 pitch 216px（= 200 + 4dp divider 16px）；顶栏底 100px；`LinearLayout` 的 `paddingVertical` 2dp = 8px。
- **主页面**：`myinfo` 卡底 350px、首个 cell 顶 368px → **间隙 18px（4dp，含 2px 取整）**。修复前此处为 0。
- **「更多」页**（先把 `edit_profile` 经 `myspace_more` 移入 more 才出现入口）：`myinfo` GONE 后首个 cell 顶 = **108px** = 顶栏 100 + padding 8，**没有多出 4dp** ✓ 与设计意图一致。
- 测试用的 `myspace_main` / `myspace_more` 已用调试桥 `remove` 清回默认。

**未覆盖**

- [ ] **真机（手表）未验证**，仅在模拟器上量过。纯静态间距、无运行时分支，风险低；但手表窄屏下 4dp 的观感是否够，需要真机确认。

### 3.23 自建收藏夹上限显示成 999（26.10.16）

用户截图反馈：「我的收藏夹」页里自建收藏夹显示 `22/999`、`777/999`、`1000/999`，而实际能装 1000 条。

**根因（服务端字段，非本工程算错）**：这条链走老接口 `space.bilibili.com/ajax/fav/getBoxList`，它**返回 `max_count`，但自建收藏夹恒为 `999`**（默认收藏夹那条是正确的 `50000`）。用调试桥 `cmd http`（带应用登录态）实测响应体确认。注意另一条链 `x/v3/fav/folder/created/list-all`（加收藏时的选择框）**根本不返回上限**、一直硬编码 1000 —— 两条链对同一概念给了不同值，所以同一个收藏夹在选择框里是 1000、在列表页是 999。

**修法**（`api/FavoriteApi.java`）：新增常量 `MAX_COUNT_DEFAULT_FOLDER` / `MAX_COUNT_CUSTOM_FOLDER` 与纯函数 `resolveMaxCount(serverMaxCount, isDefault)`——**只采信「非 999 且 > 0」的服务端值**，其余（999 / 字段缺失 / 非法）回落约定值。`getFavoriteFolders` 改用它（顺带把 `getInt("max_count")` 换成 `optInt(..., 0)`，字段缺失不再整页失败）；`parseFavoriteState` 的 `50000 / 1000` 改用同一对常量。取舍：不直接无视服务端字段，是为了服务端将来放宽上限并同步字段时能自动跟随。

**验证**

- 新增 3 个 JVM 用例（`FavoriteApiTest.resolveMaxCount_*`）。两 flavor 单测 **60 suites / 596 tests / 0 failures / 0 errors / 0 skipped**（机器产出），`assembleDebug` 通过。
- **模拟器实测（MuMu Nebula，Android 12 / SDK 32，共存 debug 包）**：`uiautomator dump` 取回 `1912/50000`、`22/1000`、`777/1000`、`90/1000`、`1000/1000`、`115/1000` —— 与用户截图的六个数字一一对应，`/999` 全部消失，默认收藏夹不受影响。
- 结论沉淀：`docs/architecture-map.md` §7.34（口径）、`docs/bilibili-api-notes.md` §6（实测字段）。

**未覆盖**：真机（手表）未验证（模拟器上文本已确认，风险低）。

### 3.24 虚拟合集里的「分P视频」做成可进入的层级，且**进入改为手动**（26.10.16）

**背景（用户的心智模型）**：这不是"两种并列类型"，而是**一棵目录树**——

```text
收藏夹（虚拟合集）= 根目录
├── 单视频 / 合集里的单视频.mp4   ← 直接播
├── 分P视频/                     ← 文件夹：进去看各分P，退出来回根目录
└── 合集/                        ← 文件夹
```

**缺口（真机确认）**：`playFavoriteVirtualCollection` 用收藏夹条目建 `pagenames/cids`，而 `switchToOnlinePage` 的虚拟合集分支只取 `videoInfo.cids[0]`——于是**多P视频只能播第 1 个分P，P2 以后完全不可达**。在「重要收藏夹」点「黑马程序员Java」（105 分P）时，选集卡片给的是**收藏夹的 20 条**，不是那 105 个分P（证据 `outputs/collection-check/虚拟合集里多P视频的选集卡片.png`）。

**关键设计转折**：§3.18 做的是**自动**进入合集，本轮先按同样思路做了自动进入分P层，**用户当即否决**——"点击这个视频后不要自动进入子合集啊，要点击后主动进入"。理由成立：自动进入会在你只想顺着收藏夹听的时候，突然被换成一门 105 讲课程的分P 列表，**收藏夹里排在后面的条目全被跳过**。于是两层统一改成**手动**：

- 点收藏夹条目 = 只起播那条（多P 只播 P1），**不换列表**
- 选集卡片上多一行：当前这条能进得去时显示「进入分P（N 个）」/「进入合集 · 标题（N 集）」，进去后同一行变成「← 返回主合集」（两个方向互斥，共用一行，id `page_selector_nest`）
- 播完层内最后一个分P/剧集 → 自动弹回根目录（受 `auto_next_enabled` 管）

**实现**（4 处）：

- `PlayerActivity` 加 `enterableCollection` / `enterableMultiPage` 与统一重算函数 `updateEnterable(videoInfo)`（合集优先于分P）；`enterCurrentNestLayer()` 由那一行的点击触发；`enterMultiPageLayer()` / `enterCollectionPlaylist()` 换列表 + 压返回栈；`returnToCallerPlaylist()` 弹栈
- 分P层的 `cids` 是**真 cid**（同一个 aid 的多个分P），所以 `virtualCollection = false`，切页走普通多P 分支；`aid` 必须换成该视频的 aid
- 顺带修文案：根目录层标题原本用 `videoTitle`，而它会被 `doSwitchPage` 改成当前条目名，从嵌套层返回后会显示成「黑马程序员Java…（1/20）」（像文件夹名其实是某一条）→ 新增 `rootTitle`（返回时取自返回栈的 `title`）
- **`PlayerData` 只剩 `virtualCollection` 一个层相关字段**：层状态全在播放器内部，不再走 Intent。唯一例外是**起播那一条**——它的播放列表由构建方直接拼、不走 `switchToOnlinePage`，所以播放器拿不到它的 videoInfo；构建方把已经请求过的 `VideoInfo` 通过 `PlayerData.enterableInfo` 带过去（零额外请求）。注：`putExtra` 处必须显式转 `Parcelable`，`VideoInfo` 同时实现了 `Parcelable`/`Serializable`，不转会报"引用不明确"

**真机验证（26.10.16 22:2x~23:0x，MuMu Nebula）**：

| 场景 | 结果 |
| --- | --- |
| 点「黑马程序员Java」（105 分P） | 标题停在 `重要收藏夹（虚拟合集）（1/20）`，**没有**被换列表；多出一行「进入分P（105 个）」（`手动入口-进入分P.png`） |
| 点「进入分P」 | 换成分P层：`分P · 黑马程序员Java…（1/105）`，P1~P105 可点，行变成「← 返回主合集」（`手动进入后的分P层.png`） |
| 点「← 返回主合集」 | 回到 `重要收藏夹（虚拟合集）（1/20）`，返回入口消失（`返回后回到收藏夹根目录.png`） |
| 点「超神人辉夜姬 第一集」（合集《辉夜姬》18 集） | 标题停在 `重要收藏夹（虚拟合集）`，**同样不自动进入**（`合集条目不再自动进入.png`） |

**未覆盖**：① 「进入合集 · 标题（N 集）」这一行的**文案渲染**未单独截到图（合集分支与分P 分支共用同一行渲染代码，且 `enterCollectionPlaylist` 在 §3.18 已验证可进入；真机上选集卡片在几次尝试里都因 adb/UI 时序没打开）；② 进入分P层一律从**第 1 个分P** 起播（`VideoInfo` 不带"上次看到哪个分P"）；③ 一个视频**既多P 又在合集里**时只给"进入合集"（合集优先），它的分P 仍不可达；④ 本地缓存的虚拟合集走另一条路径（`switchToLocalPage`），未做嵌套层。

### 3.25 CI 新增「只构建不发版」入口 `build_only`（26.10.17）

**动机**：需要一份**正式签名**的 release APK 去传应用商店 / 自己装，但**不想产生 Release**——Release 会进 `releases/latest`（客户端更新检查读它）、会被中转服务同步到 Gitee、并消耗一个不可复用的 tag（release immutability）。原先唯一「只出包」的办法是手工触发时把 `tag` 留空，但那条路仍会执行「计算发行 tag 与发布说明」，于是**只要 `update_log_current` 抽不出条目就会 `::error::` 退出**——为拿一个包而被迫先写好当版更新日志，不合理。

**实现**（`.github/workflows/build-release.yml`，5 处）：

- `workflow_dispatch` 新增 `build_only`（boolean，默认 `false`）
- job 级 `env.BUILD_ONLY` 归一化：`${{ github.event_name == 'workflow_dispatch' && inputs.build_only }}`。**必须显式判 `event_name`**——推 tag 时 `inputs.*` 恒空（与发布说明那条坑同源）
- 「计算发行 tag 与发布说明」「生成发行版直链清单」「生成给老客户端的一次性 config.json」「创建 GitHub Release」「Notify relay」五步的 `if` 前置 `env.BUILD_ONLY != 'true'`

**两个刻意的取舍**：

1. **比较字符串而非布尔**。`type: boolean` 的输入在表达式里是真布尔，但 `env` 渲染后是字符串 `"true"`／`"false"`，混用布尔与 `'true'` 是这类开关最经典的翻车点（类型强转方向一变，开关就静默失效）。
2. **跳过「计算发行 tag」这一步本身**，所以 `steps.tag.outputs.tag` 为空 → 下游 `if: steps.tag.outputs.tag != ''` 天然为假。五处条件里既有显式判 `BUILD_ONLY`（自解释、防后人删了 tag 判空），又有 tag 为空这层兜底（**双保险**：任一层失效都不会误建 Release）。

**验证**：`actionlint 1.7.7` 通过（0 问题）；另做**反向对照实验**确认它不是一律放行——同一个表达式写在 **job 级 `if`** 时 actionlint 明确报 `context "env" is not allowed here`，写在 **step 级 `if`** 时通过，说明它对上下文可用性的建模是有效的，因此 step 级用 `env` 可信（job 级 `if` 用 `env` 是 GitHub 的已知限制，见 github/docs#21927）。`yaml.safe_load` 解析通过。

**未验证**：**没有实际触发过一次 CI**（本机无 JDK 17 / Android SDK，无法跑 Gradle；且改动需先推到 main 才能在 Actions 页面手工触发）。首次使用时应确认两点：① artifact `release-apks` 里有 4 个 ABI 的普通版 + 共存版签名包；② **Releases 列表没有新增条目**、Gitee 没有新增发行版。

### 3.26 表冠 / 表圈只在设置页内生效（三星 Galaxy Watch 6 Classic 反馈，26.10.17）

**现象**（用户反馈，未附日志）：旋转表圈无反应；开「表冠适配」并把灵敏度调到 1.0 后，**只有在「偏好设置」那一页里能用**，退出即失灵；数值与开关确认已持久化。官方客户端与另一 RE 版本正常。

**根因（源码可证，两处叠加）**：

1. **旋转编码器事件只沿焦点路径分发**。`RotaryRecyclerView` / `RotaryScrollView` / `RotaryNestedScrollView` 三个控件把监听器挂**自己身上**，而列表页的 `RecyclerView` 默认不抢焦点——页面上没有任何控件获焦时事件没人接。之所以「设置页里能用」：用户点过输入框，`EditText` 拿了焦点，事件恰好沿焦点链经过外层的 `RotaryRecyclerView`；退回上一页后焦点没了，于是又失灵。
2. **灵敏度在 `onAttachedToWindow` 里只读一次**（`multiple <= 0` 就连监听器都不挂）。返回栈里的页面只是不可见、不会重新 attach，所以「改完设置、旧页面不生效」——这正是设置页文案里那句「打开新页面才能生效」的来历。

**改动**：

- `ui/widget/RotaryEncoderSupport.kt`：三份重复实现归并后新增页面级入口 `handlePage(root, event)`——找本页第一个「可见且当前能上下滚」的竖直容器并滚动它（横向 `ViewPager2` / 横向 `RecyclerView` 的 `canScrollVertically` 恒 false，天然不抢翻页手势）。
- `activity/base/BaseActivity.kt`：`onCreate` 末尾 `installRotaryFallback()`，把 `handlePage` 挂在 **DecorView** 的 generic motion 监听器上。DecorView 只在**所有子 View 都没处理**时才会走到自己的监听器，所以不会与控件级监听器重复滚动。**不要改回覆写 `Activity.dispatchGenericMotionEvent`**：generic motion 不是那条路（ViewRootImpl 直接派给 DecorView）。
- 三个 `Rotary*` 控件 + `tutorial/TutorialPagerActivity`：监听器改为构造/创建时无条件挂上，**每次事件现读开关与灵敏度**（SP 是内存 Map，热路径无性能问题）；教程翻页额外在 DecorView 上兜一层（先翻页，翻不动再交给页面级滚动）。
- 默认值：灵敏度默认 `0` → `1.0`（`RotaryEncoderSupport.DEFAULT_MULTIPLE`，设置页 `input_float` 的 `defaultValue` 引用它，单一来源）。**开关默认仍是关**——开启后会丢系统表冠震动，代码里补不出触感反馈，默认开等于替所有手表用户做取舍；文案已改成「改完立即生效，无需重进页面」「填 0 等于该类页面不响应表冠」。

**验证**：`:app:assembleDebug` 通过；**未真机验证**（无三星表圈设备）。待验：① 开关打开后各列表页 / 长文页 / 教程翻页均可滚；② 设置页改灵敏度后**不重进页面**立即生效；③ 横向翻页页（番剧选集、教程分页）不被抢成竖滚；④ 关掉开关后行为回到修复前。
**已知残留**：① 若某设备把表圈事件**完全不上报给应用**（只在系统侧处理），应用侧无解——需要用户提供日志确认事件是否到达；② 页面没有任何竖直滚动容器时（纯按钮页）表圈仍无作用，属预期。

### 3.27 设置页输入框「把值写进别人的键」（互动选项字体大小自己变成 17.0，26.10.18）

**现象**（用户反馈，附「内置播放器设置」页截图）：首次「选择播放器」进入设置页时，最底下「互动选项字体大小」会自己变成 `17.0`；截图里那一格实际填的是 `1.0`（别一行的值）。用户描述「只在首次设置中出现」「不改的话选项不会变，只要改就会变」。

**根因（源码可证）**：`app/src/main/java/com/RobinNotBad/BiliClient/adapter/SettingsAdapter.kt` 的 `InputHolder.bind()` 原本三个分支各自 `input.addTextChangedListener(object : TextWatcher {...})`，旧监听器**既不摘除、又闭包捕获了旧的 `settingSection`**。RecyclerView 复用 holder 时，`bind()` 里的 `setText` 会逐个触发全部历史监听器，把**新行**的值写进**旧行**的键——跨键污染。首次进入所有键都还是默认值，互相覆盖看不出来；改过一个值之后错位才暴露，所以「只在首次设置出现」。滚到底后受害的是弹幕字号 / 弹幕不透明度 / 弹幕速度 / 字幕校准（被写成最后一行的 `17.0`），而「互动选项字体大小」被前一行的 `1.0` 顶掉。

**改动**：

- `adapter/SettingsAdapter.kt`：`InputHolder` 的监听器改为只在 `init` 装一次，写回目标由 `private var boundSection` 决定；`bind()` 里先 `boundSection = null`（灌值 / 切 `inputType` 期间不落盘，保住「不改就不写」的语义），`setText` 之后再绑定。顺带把 `input.inputType` 提到 `setText` 之前（数字键盘过滤器不会再吃掉已填文本）。
- `adapter/InteractionDebugAdapter.kt`：`removeTextChangedListener` 移到 `setText` 之前（同一类 bug 的第二处：旧监听器会先用本行值污染上一行的 `var.value`）。
- **`17.0` 是合法默认值，不是脏值**：`app/src/main/res/layout/cell_interaction_choice.xml` 的 `android:textSize="17sp"` 与播放器里的 `getFloat(..., 17.0f)` 本来就一致。为免再出现「两处真相」，新增 `SettingsKeys.PLAYER_INTERACTION_CHOICE_SIZE_DEFAULT = 17f`（`util/SettingsKeys.kt:147`），`activity/settings/SettingTerminalPlayerActivity.kt:170` 的 `defaultValue` 与 `activity/player/PlayerActivity.kt:3672` 的 `createChoiceView()` 都改读它。**要换默认值只改这一个常量 + 布局里的 `17sp`**（XML 读不到 Kotlin 常量，必须两处一起看）。

**验证**：`:app:assembleDebug` 通过、`:app:testStandardDebugUnitTest` 通过；**未真机复现验证**。待验：① 首次进入设置页，滚到底再改中间任意一行的值，其余行不应变化；② 进去后一个字都不改、退出再进，各键不应被写入默认值。修复后首次进入该行仍显示 `17.0`——那是默认值，属预期。

**构建坑（本次实际踩到）**：Kotlin 增量缓存损坏，报 `AssertionError: java.lang.Exception: Could not close incremental caches in app/build/kotlin/compileStandardDebugKotlin/cacheable/caches-jvm/lookups: lookups.tab`（内层 `ArrayIndexOutOfBoundsException: Index 6 out of bounds for length 0`），伴随 `IOException: Couldn't delete …processStandardDebugResources/R.jar`。`--no-build-cache --no-configuration-cache` 不够，`app/build` 整目录删除 + `-Pkotlin.incremental=false` 才通过；另外该参数在 PowerShell 里会被拆成 `-Pkotlin` 与 `.incremental=false` 两个参数（报 `Task '.incremental=false' not found`），要经 `cmd /c "…"` 传。

### 3.28 崩溃 #42 复查：「所有文件访问」判据改成试写（默认目录不再要求权限，26.10.18）

**现象（崩溃后台 #42，版本 26.10.11，`xiaomi grasslte M2505W1` / SDK 34，2026-10-17 16:16）**：`ActivityNotFoundException: No Activity found to handle Intent { act=android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION dat=package: }`，栈顶是 `VideoInfoActivity` 点下载 → `w1.b.x` → `FileUtil.requestStoragePermission`。

**结论一：#42 报的是旧版本，崩溃本身 26.10.14 已修**。见 §3.15 ②（commit `9bd8b72`）：`requestStoragePermission` 已做三级降级 + `startSettings()` 吞所有 `Exception`；tag `26.10.11` 里还是裸 `startActivity`。**升级到 26.10.12+ 即不再崩**，这条无需再改。

**结论二（真正没修的是「永远授不到 → 下载被永久挡死」）**：`FileUtil.checkStoragePermission()` 只看 `Environment.isExternalStorageManager()`。默认视频目录 `Android/media/<包名>/` 是**应用专属外部存储**，API 29+ 写这里**本来不需要任何权限**，却被判「无权限」；手表 ROM 没有该设置页，用户永远授不到 → `VideoInfoFragment.downloadClick` 每次点下载都弹一次设置页然后 `return`，**下载永远开始不了**。§3.15 只修了「点设置页崩」，没动这个语义。

**改动**：

- `util/FileUtil.java`：删 `checkStoragePermission()`；新增 `canWriteDirectory(File)`（不存在先 `mkdirs`，再 `createTempFile(".write_probe")` 试写并删除，`catch (Throwable)` 一律归为不可写）与 `ensureDirectoryWritable(Activity, File)`（能写 → `true`；写不了 → 申请权限后返回 `false`，调用方应停下等用户授权后重试）。
- 三个调用点改成「按**真实目标目录**判」：`activity/video/info/VideoInfoFragment.kt` `downloadClick()`（视频目录，写不了才 `return` 去授权）、`activity/video/local/LocalListActivity.kt`（视频目录，非阻塞）、`activity/DownloadActivity.kt`（`type == 0` 的图片下载用 Intent 里的 `path`，目标在公共 `Pictures/`；其余类型用视频目录）。
- 顺带堵同类隐患：`BiliTerminal.requestOverlayPermission` 改为返回 `Boolean`，拉不起设置页就提示 + `false`；`activity/SplashActivity.kt` 只在返回 `true` 时停在启动页——否则启动页会卡在一个**永远不会回来的** `onActivityResult` 上（这条只在 Debug 的 UETool 流程里，release 不可达）。
- 新增 JVM 单测 `app/src/test/java/com/RobinNotBad/BiliClient/util/FileUtilWriteProbeTest.kt`（5 例：可写目录、深层不存在目录、目录内已有文件、父节点是文件、`null`）。

**为什么用「试写」而不是其他接口探测**：`Environment.isExternalStorageManager()` 回答的是「有没有 `MANAGE_EXTERNAL_STORAGE`」，与「这个目录能不能写」不是一回事；`checkSelfPermission(WRITE_EXTERNAL_STORAGE)` 在 API 30+ 恒 `granted` 且不反映 scoped storage 下的真实访问。试写是唯一同时覆盖「应用专属目录 / 公共目录 / 旧运行时权限 / 只读文件系统 / 目录被并发删除」的判据；探测文件用 `createTempFile` 随机名（固定名在并发下载探测同一目录时会互相误判）。

**验证**：`:app:assembleDebug` 通过、`:app:testStandardDebugUnitTest` / `:app:testCoexistDebugUnitTest` 通过（构建手法见 §3.27 末尾的「构建坑」，同一台机器）。**未真机复现**（本机 adb 无在线设备）。待验：① 默认保存目录下点下载**不再弹任何设置页**、直接开始；② 把保存路径改到公共目录后再点下载，才弹授权提示；③ #42 那台设备上升级后悬浮窗设置页缺失只提示、不崩。

### 3.29 线上崩溃两路清剿：`T0.A.run` 无消息 NPE（#43–#47/#49）与 `libijksdl.so` 加载失败（#48，26.10.19）

**现场（崩溃后台 #43–#49，客户端版本全是 26.10.16）**：

- **6 条同一个 NPE**（#43 10-18 21:46 起，到 #49 10-19 16:03）：`java.lang.NullPointerException`，**无 message**，主线程，唯一业务帧 `T0.A.run(SourceFile:1425)`。设备跨 OPPO OWW211/OWW231/OWW261/OWW263（SDK 30）、XIAOMI grasslte M2505W1（SDK 34）、HUAWEI MDS-AL00（SDK 31）——**不是某台设备的兼容问题**。
- **#48**：`UnsatisfiedLinkError: dlopen failed: can't read file "…/lib/arm/libijksdl.so": Bad file descriptor`，XIAOMI baiji Mi Watch（SDK 28，共存版），栈是 `PlayerActivity.onCreate` → `IjkMediaPlayer.loadLibrariesOnce`。

**retrace（A 路）**：用本机 26.10.16 的 release mapping（`app/build/outputs/mapping/standardRelease/mapping.txt`）。`T0.A` 的表头是 `VipActivity$$ExternalSyntheticLambda4`，实际是 **R8 合并类**；崩溃行 1425 落在 `1417:1426` 行段 → 内联帧 `activity/user/info/UserDynamicFragment.continueLoading$lambda$4$lambda$3:0:0`，左右相邻行段 `1427:1431` / `1432:1456` 分别对应同一 lambda 的 Kotlin 行 90 / 91。即崩在 `continueLoading()` 的 `runOnUiThread {}` 里。

**根因（A 路，不是「页面销毁后 adapter 被置空」）**：`adapter` 全程只在 `onViewCreated` 的后台线程赋值，目录里既没有 `adapter = null` 也没有 `onDestroyView`。真正可达的 null 路径是**首屏加载失败**：`onViewCreated` 的请求走 `catch { loadFail(e) }`，`adapter` 从此永远不会被创建；而 `loadFail` 只把 `isRefreshing` 复位，`bottom` 仍是 false、下拉加载监听仍在 → 用户在**空列表**上拖动满足 `onScrollStateChanged` 的翻页条件 → `goOnLoad()` → `continueLoading()` 这一次网络成功 → `adapter!!.notifyItemRangeInserted(...)` 抛 `Intrinsics.checkNotNull` 的**无 message** NPE，与线上现象逐条吻合（`requireActivity()` 抛的是带 message 的 ISE、`lateinit` 也有 message，都不是）。同构代码还有 `UserVideoFragment` / `UserArticleFragment`。

**修法（A 路，`activity/user/info/` 三文件）**：

- 三处翻页回调统一守卫：`runOnUiThread { 列表.addAll(list); if (isAdded && view != null) adapter?.notifyItemRangeInserted(...) }`；`setRefreshing(false)` 从 `try` 末尾挪进 `finally`——否则异常路径不复位，翻页会永久卡死（硬约定）。
- 其余 `adapter!!` 一并收口：`onViewCreated` 用局部变量再赋字段 + `setAdapter(newAdapter)`；`UserDynamicFragment.onDynamicRemove` 改 `adapter?.let { ... }`；`requireActivity().finish()` → `activity?.finish()`。
- 顺带修 `UserVideoFragment` 的既有越界 bug：插入起点原为 `videoCardList.size - list.size`，漏了头部占位 `+1`（`UserVideoAdapter.getItemCount()` = `videoCardList.size + 1`，第 0 项是「视频系列」入口）。`UserArticleFragment` 用的 `ArticleCardAdapter` 无 header，**仍不加 1**。

**根因（B 路）**：`IjkMediaPlayer.loadLibrariesOnce` 依次加载 `ijkffmpeg` / `ijksdl` / `ijkplayer`，**三个全成功才置静态 `mIsLibLoaded`**；而 `IjkMediaPlayer()` 构造内部会**再调一次** `loadLibrariesOnce` → 只包住前者不够。`UnsatisfiedLinkError` 继承 `Error`，仓库里既有的 `catch (e: Exception)` 全部兜不住。「Bad file descriptor」是设备侧 .so 打不开，客户端没有系统播放器可回落，只能兜住 + 提示 + 退出，并留下可诊断的现场。

**修法（B 路）**：

- 新增 `player/IjkNativeLibs.kt`（`object`，进程级唯一入口）：三态 `STATE_UNLOADED/LOADED/FAILED`（`@Volatile`，快路径只读一次），`ensureLoaded(context)` 在 `synchronized` 内用 `catch (t: Throwable)` 兜住加载；**失败即锁死本进程不再重试**，避免每开一个页面刷一遍同样的错误。失败时采集 ABI（`SDK_INT >= 21` 才读 `Build.SUPPORTED_ABIS`，否则回落 `CPU_ABI`/`CPU_ABI2`，minSdk 19 硬约束）、`nativeLibraryDir`、三个 .so 的存在/可读/大小、目录内容、原始栈写进 `Logu`（进内存环形缓冲，崩溃与反馈都会附带）；在用户开启崩溃自动上报时经 `TerminalApi.isCrashReportAutoEnabled()` 走 `CrashReporter.upload(...)`（每进程一次）。
- 接线：`PlayerActivity.onCreate` 改 `ijkPlayer = createIjkPlayerOrBail() ?: return`（失败 `MsgUtil.toastLong` + `finish()`；早退点在 `setDisplay()` 被 `postDelayed` 排上之前，所以 `ijkPlayer!!` 不会被执行）；`retryAfterPlayerError` 的重建同样走 helper（原来在 `runOnUiThread` 里裸构造，外层 `catch (Exception)` 抓不到主线程的 `Error`）；`IjkPlayerBridge.createPlayer` / `VideoPlayerCore.createPlayer` 改为返回 `Boolean`；`ShortVideoPlayerActivity` 判 false → 复位 buffering 状态 + 提示 + `return`，不再对着空播放器做 `textureView` / `setDataSource`。
- `res/values/strings.xml` 新增 `msg_ijk_native_load_failed`（"播放器组件加载失败，无法播放视频，请重装应用后再试"）。

**验证**：`:app:assembleDebug` 通过（standard + coexist 两个 flavor 都出包）；`:app:testStandardDebugUnitTest` / `:app:testCoexistDebugUnitTest` 各 **61 suites / 601 tests / 0 failures / 0 errors / 0 skipped**（读自 `app/build/test-results/`，`--offline --no-configuration-cache`）。本改动没有新增单测：A 路的守卫是生命周期谓词、B 路的加载是设备环境相关，工程无 Robolectric，JVM 单测覆盖不到（先例同 §3.19）。

**未真机复现**（本机 adb 无在线设备）。待验：① 断网让「用户动态/投稿/专栏」首屏失败 → 恢复网络后在空列表拖动翻页，**不再崩**；② 原生库不可用设备上开播放页应提示并退回上一页，短视频滑到该条同理；③ #48 那台设备升级后，`IjkNativeLibs` 的诊断（`.so` 存在性/可读/大小）应出现在崩溃报告附带的 `ring_log` 里。

**本轮遗留**：

- 首屏失败后页面仍是空白、拖动仍会静默翻页（数据只进列表、不显示）——本轮只止损不恢复；治本需要「加载失败重试入口」或失败时置 `bottom = true`（都会改变用户可见行为，未擅自加）。
- 未改：`UserFavoriteFragment.kt:50` 的 `setAdapter(adapter!!)`（该页 adapter 在 `onViewCreated` 同步创建，可证明安全）、三处 `arguments!!`。
- B 路刻意未动 `PlayerActivity` 另外 4 处「加载成功之后」的 `IjkMediaPlayer()` 构造（听视频切换 / 直播换线路 / 切清晰度 / 换 P），此时 `mIsLibLoaded` 已为 true，不会再碰 .so。
- #43–#49 是否绝迹要看下一版发布后的后台；若同一 `T0.A.run` 栈再现，需要**用新版 mapping 重新 retrace**（mapping 不进版本库，`clean` 后即失）。

### 3.30 全面代码审查修复批次（26.10.19，基线 `2d2b63b` → `0ea6ce4`）

- **来源**：用户 `/code-review`，从上次安全审计（`docs/archive/review/audit-2026-09-24.md`，基线 `2d2b63b`）审到 HEAD。两轴（Standards / Spec）、8 个审查单元，共 **18 条硬性 + 56 条判断 + 若干 Spec 缺口**；本轮集中修复，明确跳过的项列在最后。
- **凭据落盘**：`util/Logu.java` 的 `appendToFile` 改为**先 `LogSanitizer.sanitize` → 再 shorten → 再 enqueue**；`util/NetWorkUtil.java` 的 HTTP 追踪进 logcat 前同样先脱敏；`util/LogSanitizer.kt` 扩到 **4 种形态**（`键=值` / URL 编码 / JSON 键值 / 裸手机号）并补 `access_token`、`buvid3/4`、`phone` 等键，`LogSanitizerTest.kt` 18 个用例。**这是唯一未过脱敏的落盘入口，现在闭环。**
- **隐私闸**：`TerminalApi.headers()` 的 `X-Install-Id` 挂 `hasPrivacyConsent()`（同意前不上报）；`buildCrashPayload` 补 `is_coexist`（保留旧 21 参重载供单测）；DebugBridge 的 `get` 与 `dump` 统一打码、`http` 的 `appheaders` 默认改 **false**；反馈输入框补 `maxLength=4000`。
- **网络 / 下载**：`NetWorkUtil.post` 非 2xx 关流并抛 `IOException`（不再把错误页当成功）；`DownloadActivity` 写盘前判 `isSuccessful` + `content-length`、`onDestroy` 只删本次写过的文件；`FileUtil.cleanDownloadTempFiles` 改为**成品缺失时用 `.bak` 恢复**、成品存在才删 `.bak`；DASH 音视频替换原子化（失败回滚）；失败分片的已计字节回滚；`DownloadSqlHelper` 的 `ALTER` 失败不再 drop 整表。
- **播放器**：直播分支补 `reconnect_at_eof=1`（点播不加，见 `docs/architecture-map.md` §7.45）；短视频删 `infbuf=1`（与 `max-buffer-size` 矛盾）；起 `PlaybackService` 前判 `isPlaying`；跨线程播放状态字段补 `@Volatile`；片头片尾功能关闭时不再 `buildSegments`；4 处裸 `IjkMediaPlayer()` 改走 `createIjkPlayerOrBail()`；8 处文案迁 `R.string`。
- **API**：七张散落的错误码表收敛到 `api/ApiErrorText.kt`（新增 `loginRequired` / `tokenExpired` / `requestIllegal`）；三个新 POST 补 `isSuccessful` 与 try-with-resources；`SearchApi` 番剧卡片解析复用纯函数（删第二套）；`DynamicApi.parseAtContent` 修「无 @ 时正文写两遍」并按 `start` 排序；`buildContents` / `containsEmoteText` 补 5 个单测；私信日志去 PII。
- **UI**：`MessageActivity` 失败分支复位 `isRefreshing` / `isEnabled`（不再永久转圈）；`DynamicHolder` / `ReplyAdapter` / `FavoriteVideoListActivity` 的管理动作改为**结构化 `kind` + `danger`**，不再拿显示文案当身份标识；收藏夹 / 稍后再看 / 关注分组改确认弹窗并按 `aid` 重定位；`BangumiInfoFragment` 追番状态加 token 世代守卫、切季后重取 `seasonId`；`DynamicAdapter` 的筛选参数改稳定 `value`；`SearchActivity` / `WriteReplyActivity` 的匹配关键词还原成普通字符串；写死色值改 `?attr/` 或 `ColorScheme`；文案迁移补 **63 个资源键**（strings.xml + `UiText` MAP 56 条）。
- **基建**：`ci.yml` 的 upload 路径适配 flavor 产物目录（`apk/*/debug/*.apk`）；minSdk 注释统一到 19；`PasswordEncryptUtil` 的 `strip()`（API 33）改 `trim()` —— 这是 **API 19–32 密码登录静默失败**的根因。
- **明确跳过 / 保留**：`tutorial/Tutorials.kt` 的过度拆分未回并（不在本轮范围）；`TopicDynamicAdapter` 仍用旧入口；`episodeStatusText` / `STATUS_TEXT_FALLBACK` 仍字面量（被 `BangumiInfoFragmentTest` 断言钉死）；`LocalPageChooseActivity` 同类路径问题；`WatchLater` multipart 收敛与 `ConfInfoApi.signWBI` 锁（改动风险大）；`DownloadActivity` 覆盖同名成品仍直接截断（中途断流会丢旧成品）。
- **台账更正**：§3.18 里「`PlayerData` 加 `collectionId`/`collectionTitle`/`returnPlaylist`（Parcelable）」是**当时的描述**；现状是这三个字段为 `PlayerActivity` 私有字段（`PlayerData` 只有 `virtualCollection` / `enterableInfo`），系统重建时会丢返回栈，见本次 Spec 报告。
- **验证（26.10.19 全绿）**：`:app:clean`（42s）→ `:app:assembleDebug`（2m10s，standard + coexist 各 4 个 ABI 包，`BUILD SUCCESSFUL`）→ `:app:testStandardDebugUnitTest` + `:app:testCoexistDebugUnitTest`（18s，`BUILD SUCCESSFUL`）。单测读自 `app/build/test-results/`：两个 flavor 各自 **62 suites / 629 tests / 0 failures / 0 errors / 0 skipped**（较上轮 61/601 增加 1 套 27 例：`SearchApiTest`、`DynamicApiTest` 新增用例、`LogSanitizerTest` 扩充等）。命令均带 `--offline --no-configuration-cache`；`clean` 与 `assemble` 分两次调用。未跑真机（本机无在线设备），UI 时序类修复仍需真机复测。

### 3.31 动态正文 `#话题#` 点不动：真实 jump_url 是站内搜索链接（26.10.19）

- **现象**：动态正文里 `#星塔旅人#` 这类话题显示为主题主色（说明命中了话题分支），但点不动。
- **根因（实测，不是推测）**：带登录态抓 `x/polymer/web-dynamic/v1/feed/all`（20 条）与 `v1/detail`（id `1260873720887509041`）的真实响应，`RICH_TEXT_NODE_TYPE_TOPIC` 的 `jump_url` **只有一种形态** `//search.bilibili.com/all?keyword=<URL 编码的话题名>`，既没有 `topic_id` 也没有 `rid`。于是 `util/LinkUrlUtil.java` 的 `parseTopicId` 返回 0、`util/StringUtil.java` 的 `setSingleTopic` 走 `topicId <= 0` 分支只上色——26.10.05 §三十四 那个"解析不出 id 时宁可有色不可点"的退化分支，在真实数据下等于**所有话题都点不动**。
- **反查 topic_id 不可行（三种端点实测）**：`x/topic/pub/search?keywords=` 只有模糊搜索（`绝区零` / `星塔旅人` / `转发抽奖` 都搜不到精确同名，只有 `星塔旅人一周年` 恰好有同名）；`x/topic/web/details/top?topic_name=` 报 `-400 Key: 'TopicInfoReq.TopicId' … failed on the 'gt' tag`（必须给 id）；`x/topic/pub/detail?topic_name=` 直接 404。
- **修法**（与 B 站网页行为一致：话题链接本来就指向搜索）：`LinkUrlUtil` 新增纯函数 `parseTopicSearchKeyword(jumpUrl)`（要求含 `search.bilibili.com`，从 `keyword=` 取值后 `URLDecoder` 解成中文，取不到返回空串）；`StringUtil.setSingleTopic` 改三段——有 `topic_id` 仍进 `DynamicTopicActivity`；只有搜索链接则用新内部类 `TopicSearchClickableSpan` 进 `SearchActivity`（`putExtra("keyword", …)` + `putExtra("auto_search", true)`）；两者都没有才只上色。`model/TopicNode` 补 `getSearchKeyword()`，`isClickable()` 改为「有 topic_id 或有搜索关键词」。`SearchActivity` 只在 `auto_search=true` 时**进入即搜**，其余只传 `keyword` 的调用方行为不变（原来只预填输入框、用户还得再点一次放大镜）。
- **单测**：`LinkUrlUtilTest` 补 4 例（协议相对的真实跳转能解码、https + 额外参数、非搜索链接 / 无 keyword / 空值返回空、topic-detail 链接不误判为搜索）；`DynamicApiTest` 补 1 例（搜索链接话题 `topicId=0` 但 `isClickable=true`）。
- **验证**：`:app:assembleDebug --offline` + 两个 flavor 的单测全 `BUILD SUCCESSFUL`；真机 `emulator-5554`（coexist debug 包）点正文话题 → 进站内搜索页。

### 3.32 缩放视频后左右滑动仍在调进度（26.10.20）

- **现象**：开启「左右滑动控制进度」+「视频可缩放」后，捏合放大画面 → 松手 → 单指左右滑动，仍会弹出进度浮层并拖改进度条。设置项文案写的是「在视频区域左右滑动快进/快退（未缩放视频时生效）」。
- **根因（读码确证）**：`activity/player/PlayerActivity.kt` 的 `handleSwipeSeekTouch()` 用 `gesture_scaled` 挡缩放，但那个标志只表示「**本轮触摸里**捏合过」——捏合时置位、`ACTION_UP` 一到就复位，随后只剩「吞掉手势结束那次点击」一个用途。于是「放大 → 松手（标志复位）→ 再横滑」时事件又被 seek 分支 `return true` 消费。同一缺陷还波及 MOVE 分支的单指拖动闸门（`!(gesture_scaled && !doublemove_enabled)`）：关掉「缩放时可移动」后，放大画面仍能被单指拖走。
- **修法**：
  - 新增 `activity/player/PlayerGesturePolicies.kt`：`PlayerZoomPolicy.isZoomed(scaleX, scaleY)` 只读画面实际比例（ε=0.001f，缩放状态唯一判据）；`PlayerSwipeSeekPolicy.isSeekAllowed(swipeSeekEnabled, prepared, isLive, playerReady, scaleX, scaleY)` 汇总调进度全部前置条件。`PlayerDoubleTapPolicy` 改为复用前者，删掉重复的 ε 定义（原 10 例单测不动仍绿）。
  - `PlayerActivity`：新增 `isVideoZoomed()`（读 `layout_video.scaleX/scaleY`）；`handleSwipeSeekTouch()` 改走 `PlayerSwipeSeekPolicy`；单指拖动闸门改为 `(!isVideoZoomed() || doublemove_enabled)`；`gesture_scaled` 保留但注释写明「只用于点击抑制」；顺带把 `"player_scale"` / `"player_doublemove"` 字面量换成 `SettingsKeys.PLAYER_SCALE` / `SettingsKeys.PLAYER_DOUBLEMOVE`。
  - 顺带堵掉一个相邻的残留：横滑已弹出进度浮层后再捏合缩放，最终 `ACTION_UP` 会被门闸提前挡掉，`swipeSeekActive` 分支的清理永远执行不到，而缩放期间又进不来 ⇒ 浮层一直挂在画面上（修复前下次横滑会自愈）。门闸失败时改调 `cancelSwipeSeekIfActive()` 收掉（仅当浮层可见或 active 时碰 UI）。
- **单测**：新增 `app/src/test/java/com/RobinNotBad/BiliClient/player/PlayerGesturePoliciesTest.kt`（9 例）：未缩放放行、双轴/单轴缩放一律否决、缩放复位后恢复、浮点噪声不算缩放、其余开关各自仍生效。
- **验证**：`:app:assembleDebug`（standard + coexist 各 4 ABI debug 包）`BUILD SUCCESSFUL`；`:app:testStandardDebugUnitTest` + `:app:testCoexistDebugUnitTest` 各 **64 suites / 649 testcases / 0 failures / 0 errors**（较 §3.30 的 62/629 增加 2 套 20 例，其中本次新增 1 套 9 例）。命令带 `--offline --no-configuration-cache`，无 res 文件集合变化故未 clean。
- **未覆盖 / 残留**：① **无真机**（本机 `adb devices` 为空），判据单测锁的是「读实际 scaleX」这条契约，**覆盖不到 ACTION_UP 之后的手势分发**，复测条目见 §2.1；② 关掉「缩放时可移动」时，放大后的单指拖动现在完全不动画面，松手仍可能被当成一次点击把控制栏收起/唤出——与「未开启滑动调进度」时的既有行为一致，本轮不改。

### 3.33 长按顶栏改成三模式，让「不响应」真正罩住视频详情页（26.10.20）

- **来源**：反馈 #9（OPPO 系手表：长按屏幕顶部是系统控制中心）。26.10.19 的布尔开关（`8857b48`）只拦住普通页面那条「长按顶栏 → 打开菜单列表」；视频详情页的长按走自己的监听器（`activity/video/info/VideoInfoActivity.setupLongPressToRoot` →「关闭所有详情页」），读都不读这个开关。所以用户关掉开关后在视频详情页长按，仍会关掉所有详情页、页面直接消失，观感就是「长按和单击一样」，于是要求三档：不响应 / 仅视频详情页 / 全部页面。
- **语义（刻意沿用各页原有动作，不新增动作）**：「生效」= 该页原本的长按行为发生——普通页面打开菜单列表，视频详情页关闭所有详情页。「全部页面」就是 26.10.19 的现状；这样模式③不会等于删掉 `docs/FEATURES.md` 承诺的「长按顶栏返回首页」。
- **修法**：
  - `util/TopbarLongPressPrefs` 由布尔开关改为三态字符串：`all`（默认）/ `video_only` / `off`，新键 `SettingsKeys.TOPBAR_LONG_PRESS_MODE`。读不到新键时按旧布尔键懒迁移（`true` → `all`、`false` → `video_only`）——**旧「关」映射成「仅视频详情页」而不是「不响应」**，升级后行为与升级前完全一致，不会突然把用户已有的视频详情页手势也一起关掉。判据只留两个出口：`isEnabledForNormalPages()`（= `all`）、`isEnabledForVideoPages()`（≠ `off`）。
  - 两个监听器都是「装一次、按下时读模式」：普通页（`activity/base/BaseActivity.setupTopbarLongPressToHome`）只在 `all` 下打开菜单；视频详情页只在非 `off` 下关闭所有详情页。其余情况**返回 true 把手势吞掉**——返回 false 时 Android 会在抬手时把它回落成一次单击，而顶栏单击是「退出当前页」，等于换了一种误触。
  - `BaseActivity` 新增 `protected open fun handlesTopbarLongPress()`：视频详情页返回 true，基类不再安装通用监听器。此前只靠 `ViewCapabilityProbe.probeBoolean(topBar, "hasOnLongClickListeners")` 判断「子类是否已装」，探测失败（裁掉该方法的杂牌手表，返回 null）时基类会覆盖子类监听器，把视频详情页的「关闭所有详情页」变成「打开菜单」——这类设备恰恰最需要正确行为。顺带把基类 doc 注释里「子类若已自定义…此处不覆盖」的空头承诺落实。
  - 设置项从 `switch` 换成 `list_choose`（`ListChooseActivity` 三选一）：`activity/settings/SettingPrefActivity` 的 `sections` 提成字段并补 `onActivityResult`（本页此前没有 list_choose，不接回写就是「点了没反应」），默认值取 `TopbarLongPressPrefs.mode()`，保证迁移后的老用户看到的档位就是实际生效的档位。菜单列表页（`MenuActivity`）本来就不装长按监听，三档都管不到它，刻意不改。
  - 文案：「长按顶部打开菜单」→「长按顶部标题栏」（选项里已不止「打开菜单」，旧名会误导），三选项「不响应 / 仅视频详情页 / 全部页面」，说明 `desc_topbar_long_press_mode`；新增 `settingtext_settingprefactivity_029~032` 并在 `activity/settings/SettingText` 登记（`resolve()` 未登记会原样返回，list_choose 的当前值会显示空白），`SettingsIndex` 的通用偏好搜索名同步改成「长按顶部标题栏」（跨文件按名字匹配）。
- **单测**：`app/src/test/…/util/TopbarLongPressPrefsTest.kt` 重写为 11 例（默认 `all`、三模式各自放行视频详情页/普通页面、旧开关 `true`/`false` 的迁移、新键优先于旧键、新键脏值回退默认、两个键名稳定、不与长按操作面板共用键）。
- **验证**：`:app:assembleDebug --offline --no-configuration-cache` `BUILD SUCCESSFUL`（standard + coexist 各 4 ABI debug 包）；`:app:testStandardDebugUnitTest` + `:app:testCoexistDebugUnitTest` 各 **64 suites / 654 testcases / 0 failures / 0 errors**（较 §3.32 的 64/649 增加本次 5 例，套数不变）；`strings.xml` 仅 5 增 2 删、无新增资源文件，故未 clean。
- **未覆盖 / 残留**：① **无真机验证**（手边没有 OPPO 系手表），复测条目见 §2.1；② `MenuActivity` 长按仍回落成单击=退出菜单，属既有行为，三档不覆盖；③ `asyncInflate` 路径（`BaseActivity.asyncInflate`）原本就不装长按监听，未改。

### 3.34 缓存页空态压住「新建文件夹」（26.10.20）

- **现象**：缓存页没有视频时，`啥都木有~` 正好压住「新建文件夹」按钮（用户截图）。
- **根因**：`activity_simple_main_refresh.xml` 末尾的 `emptyTip` 是 `match_parent` 的**覆盖层兄弟节点**，`LocalListActivity.checkEmpty()` 在 `videoList`/`folderList` 都空时点亮它，而此时列表里仍有「下载列表 / 新建文件夹 / 未分类视频 0 个」三项，文字必然叠在按钮上。
- **修法**：空态改成**列表内一行**——`CacheListAdapter` 新增 `TYPE_EMPTY_TIP`（新布局 `app/src/main/res/layout/cell_empty_tip.xml`，文字 `@string/text_list_empty`），`rebuildFlatList()` 在「未分类视频」标题之后、该分区 0 个视频时插入；进文件夹后该文件夹 0 个视频同样显示。`LocalListActivity` 删掉 `emptyTip` 字段、`checkEmpty()` 及两处调用（共享布局里的 `emptyTip` 保留给其它页）。
- **不在本次范围**：「下载列表」「新建文件夹」仍是 `CacheListAdapter` 的 `TYPE_GOTO_DOWNLOAD` / `TYPE_ACTION_NEW_FOLDER` 列表项，随列表滚走、回到顶部自然回位。26.10.20 曾把它们改成列表上方的固定条 + `AutoHideBars` 滚动折叠，**用户要求回退**，相关代码与文档已撤销，不要再按「固定条」实现。
- **构建 / 单测**：新增资源文件，按 AGENTS.md 先 `:app:clean --offline --no-configuration-cache`，再 `:app:assembleDebug --offline --no-build-cache --no-configuration-cache`；两个 flavor 的 JVM 单测见下方验证行。
- **验证（模拟器实测，非真手表）**：`127.0.0.1:16384`（MuMu，380×380 表盘模拟器）装 coexist debug 包，用 `debug/DebugBridge` 的 `start` 命令直接拉起 `LocalListActivity`：零视频时「啥都木有~」排在「未分类视频 0 个」下方，**不再压住按钮**；进文件夹后同样显示在该文件夹标题下。
- **未覆盖 / 残留**：「有文件夹、但未分类区为空」时显示「未分类视频 0 个 + 啥都木有~」两行，属有意保留（提示未分类区确实是空的）。

### 3.35 缓存页右滑设置面板改成「长按一次弹窗」（26.10.20）

- **需求（用户原话）**：「对了，按钮向右滑动不是会有功能列表吗，我希望你把他改成长按一次后弹窗（沿用应用已有弹窗组件），长按两次删除也并入弹窗改为一个功能」。
- **改动**：
  - `adapter/video/CacheListAdapter.kt`：删掉自写的右滑手势（`dispatchTouchEvent`/`OnItemTouchListener` 那段、`openVideoSettingsIdx`/`openFolderSettingsIdx`/`waitingSecondLongPressIdx`/`justDeletedIdx` 及面板显隐/`scrollToOpenSettings`），改为 `itemView.setOnClickListener`（点＝播放/进文件夹）+ `setOnLongClickListener`（长按回调 `onVideoMenu(index)` / `onFolderMenu(index)`）；`VideoItemHolder`/`FolderItemHolder` 里的面板字段一并删除。
  - `activity/video/local/LocalListActivity.kt`：新增 `showVideoMenu()` / `showFolderMenu()`（走 `util/TerminalDialog.kt` 的 `menu`）；菜单项按 `isInFolder` 在「添加到文件夹 / 移出文件夹」间切换；**删掉「长按两次免确认删除」**，删除前统一 `TerminalDialog.confirm`；「拆散文件夹」也改成 `confirm`。
  - 布局清理：`res/layout/cell_video_local.xml` 删 `videoSettingsPanel`、`res/layout/cell_video_folder.xml` 删 `folderSettingsPanel`，`git rm res/layout/panel_video_settings.xml`（已无引用）。`cell_video_local` 仍被 `DownloadAdapter` 使用（下载列表页已回归验证）。
- **弹窗组件的坑（已修，判据见 `docs/architecture-map.md` §8.7.0）**：
  - `TerminalDialog.menu/confirm/alert/choice` 返回 `AlertDialog` 但**不会自己 `show()`**，调用方必须 `}.show()`（漏了就是「点了没反应」，logcat 里连异常都没有，本次排查耗时最久）。
  - 弹窗默认窗口是整屏宽且不带 `WATCH_OUTSIDE_TOUCH`，导致「点空白处取消」根本收不到点击；现在 `TerminalDialog` 统一加 `FLAG_WATCH_OUTSIDE_TOUCH` 并把菜单宽度限到约 90%，表盘两侧才有真正可点的空白（约 24px）。提示行本身也做成可点，避免用户找空白。
  - 表盘 380×380：`menu` 标题按 12 字截断（`menuTitle()`），`confirm` 正文压到一行语义（「此操作不可恢复。」「视频会移回未分类区域。」），否则按钮行被挤出屏幕——`confirm` 的按钮跟正文走，不像 `choice` 那样固定底栏。
- **验证（模拟器实测，非真手表）**：`127.0.0.1:16384`（MuMu 380×380）coexist debug 包；长按文件夹卡 →「重命名 / 拆散文件夹」；长按视频卡 → 5 项菜单（末项「删除视频」红字，弹窗内可滚动）→ 点删除 → 确认框「取消 / 删除」全在屏内 → 取消后视频仍在；点提示行、点两侧空白均可关闭；`input tap`（点按）不受影响。
- **构建 / 单测**：删了资源文件，按 AGENTS.md 先 `:app:clean` 再 `:app:assembleDebug`（两 flavor 均 `BUILD SUCCESSFUL`）；`:app:testStandardDebugUnitTest` + `:app:testCoexistDebugUnitTest` `BUILD SUCCESSFUL`（64 suites / 662 tests，0 失败；本次改动无 JVM 单测覆盖，靠回归）。
- **不在本次范围**：下载列表页（`DownloadListActivity`）自身的「长按两次删除」未动，仍是旧交互。

### 3.36 用户报告的三条问题：缓存视频弹幕全空 / DASH 拖动音频停顿 / 下载进度 2MB 一跳（26.10.20）

- **用户报告（26.10.19 / versionCode 2610190，手表端）**：①「下载页面进度显示异常」→ 追问后确认是「**分片进度 2MB 一跳而卡顿**」；②「缓存弹幕无法正常下载」→「视频下载成功，但播放缓存视频时没有弹幕」，进一步确认「**所有缓存视频都没弹幕**」「**点『更新弹幕』重下之后仍然没有**」；③「播放 DASH 格式视频拖动进度条音频会卡一下（非 DASH 没有）」→「**松手后声音停顿约半秒到一秒，然后才从新位置继续**」。
- **② 根因（影响面最大）**：`player/DanmakuManager.kt:64` 的 `loadFromXmlFile` 在 26.10.02 的审计提交 `11ba14a9` 里被改成 `file.inputStream().use { createParser(it); configureAndPrepare(...) }`，注释断言「解析是同步的，返回后流已读完」。**实际 DFM 的 XML 解析是懒执行的**（`DrawTask.java:283` 在渲染线程才 `getDanmakus()`，`AndroidFileSource` 只存流引用），`BiliDanmukuParser.parse()` 读已关闭的流 → `catch (IOException)` → `return null`，**弹幕为空且无任何提示**。所以自 26.10.02 起所有走 XML 的弹幕都没了：本地缓存视频（本地播放只读 `danmaku.xml`）、以及把「新版弹幕获取方式」关掉的在线播放。「更新弹幕」只重写文件、不改变加载方式，故重下也没用。修法：先把文件 `readBytes()` 读进内存，再交给 `ByteArrayInputStream`（`close()` 是空操作）。判据与踩坑记录见 `docs/architecture-map.md` §7.46。
- **③ 根因**：`activity/player/PlayerActivity.kt` 的进度条 `onStopTrackingTouch` 只 `seekTo` 了 ijkPlayer 与弹幕视图，**没有 seek 外部音轨**（DASH 本地双文件 `video.mp4` + `audio.m4a`，音频是独立的 `android.media.MediaPlayer`）；随后 `progressChange()` 每 250ms 发现音画偏差 > 800ms 才异步 `ap.seekTo(video_now)`，而异步 seek 期间 `currentPosition` 仍是旧值、又会触发一次校正 → 可闻的 0.5–1s 停顿。修法：松手改走统一的 `seekToPosition()`（它本来就 seek 音轨），并在 seek 后设 1.2s 冷却（`AUDIO_RESYNC_COOLDOWN_MS`），冷却期内不做偏差校正，避免重复 seek。
- **① 根因（两处）**：a）DASH 下载的进度三段各自从 0 走满——附件阶段用 0–1.0、视频 `downFile(..., 0f, 1.0f)`、音频再 `0f→1.0f`，整条进度条来回满三次；b）分片（`downFileSpeedSeg`）的 `totalDownloaded` 只在**整片完成后**才 `addAndGet`，2MB 一片时 200ms 轮询看到的进度每 2MB 才动一次 → 一跳一跳、ETA 虚高。修法：DASH 改单调权重（附件 0.00–0.12 / 视频 0.12–0.55 / 音频 0.55–1.00），`downloadSegment` 改成**写完一块就计入**，失败/重试时按已写字节回滚（`totalDownloaded` 与 `addDownloadedBytes` 同进同退）。
- **顺手修**：`activity/video/local/LocalListActivity.kt` 的 `downloadDanmakuFile` 原来不判 HTTP 状态码、把错误页直接写进 `danmaku.xml`，改成非 2xx / 空 body 抛 `IOException`（上层提示「弹幕更新失败」）。
- **构建 / 单测**：`:app:assembleDebug` 两个 flavor `BUILD SUCCESSFUL`（1m 2s）；`:app:testStandardDebugUnitTest` 与 `:app:testCoexistDebugUnitTest` 均 **65 suites / 675 tests / 0 失败 / 0 错误 / 0 跳过**（两个 flavor 同源码同结果）。本次三条都无 JVM 单测覆盖（播放器/下载服务依赖 Android），靠回归。
- **未验证 / 残留**：①③ 未在真机或模拟器上实际拖动/下载验证（本机只有 380×380 MuMu 且无缓存视频）；② 的根因由「懒解析 + 已关闭流」的代码链路确证，装包后打开任一缓存视频即可确认弹幕恢复。合集「更新弹幕」多 P 循环仍沿用 `meta.cid`（各分 P 会写入同一份弹幕），以及 XML 接口对大视频会截断（实测 25564 条只返回 7200 条），两件都不在本次范围，见 §2.2。

### 3.37 线上崩溃 #63：编辑资料点头像 → `ACTION_PICK` 无人处理（26.10.20）

- **现象（用户报告：26.10.20 18:04 / 版本 26.10.19 / XTC monaco_go Z10 / SDK 30）**：`android.content.ActivityNotFoundException: No Activity found to handle Intent { act=android.intent.action.PICK dat=content://media/external/images/media }`，`main` 线程；栈：`Instrumentation.checkStartActivityResult:2073` ← `Activity.startActivityForResult:5341` ← `T0.b.onClick:109`；页面轨迹停在 `EditProfileActivity`（前序 `MySpaceActivity` → `UserInfoActivity`）。
- **复现 / 预期 / 实际（先问过用户）**：只有特定设备会崩；预期是「弹出系统选图 → 选完上传头像」；实际直接进崩溃页（`CatchActivity`）。该机型预装了文件管理器 / 相册。
- **根因**：`activity/user/EditProfileActivity.kt` 的 `selectImage()` 用 `Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)`（当时全仓唯一一处）。该 action 只由「声明了 `ACTION_PICK` 的系统相册」响应；这类手表 ROM 的图片提供方只接 `ACTION_GET_CONTENT` / `ACTION_OPEN_DOCUMENT`，于是 `startActivityForResult` **同步**抛异常（`BaseActivity` 无覆写，异常直接冒到点击回调，没人兜）。同类四个入口早已改过：`PrivateMsgActivity.kt:203`、`WriteReplyActivity.kt:209`、`SendDynamicActivity.kt:168`、`SettingGroupActivity.kt:374`。
- **修法**：改成 `ACTION_GET_CONTENT` + `type = "image/*"` + `CATEGORY_OPENABLE`，并 `try/catch (e: Exception)`：设备上一个选图应用都没有时提示新字符串 `R.string.msg_no_image_picker`（「没有可用的相册或文件管理器」），不再崩。**没有先 `resolveActivity` 判活**——targetSdk ≥ 30 的包可见性限制下它会对真实存在的相册返回 `null`，等于把崩溃换成「点了没反应」。判据与语法坑（Kotlin 块注释可嵌套，KDoc 里写 `image/*` 会吞掉后半份文件）见 `docs/architecture-map.md` §7.47。
- **构建 / 单测**：`:app:assembleDebug` 两个 flavor `BUILD SUCCESSFUL`（1m 8s，8 个 APK 齐）；`:app:testStandardDebugUnitTest` + `:app:testCoexistDebugUnitTest` 均 **65 suites / 675 tests / 0 失败 / 0 错误 / 0 跳过**。本次改动无 JVM 单测覆盖（依赖真机相册），靠回归。
- **未验证**：手表真机未复测（本机无该机型、也无登录态，进不到「编辑资料」页）；但改动是「换 intent + 加兜底」，与四个既有的可用入口同写法，且 `ActivityNotFoundException` 已不可能再冒到崩溃页。

### 3.38 线上崩溃 #62：登录页扫码轮询在 fragment detach 后调 `getString`（26.10.20）

- **现象（用户报告：26.10.20 15:47 / 版本 26.10.19 / OPPO OWW202 / SDK 27）**：`java.lang.IllegalStateException: Fragment q{de30e4c} (a1fb2446-1d35-4c0f-b046-34e3a949be9d) not attached to a context.`，`main` 线程；栈 `h0.u.M(SourceFile:18)` ← `h0.u.l(1)` ← `h0.u.m(1)` ← `R0.m.run(477)` ← `Handler.handleCallback` ← `Looper.loop` ← `ActivityThread.main`。页面轨迹：`LoginActivity`(15:46:44) → `SplashActivity`(15:46:56) → `DialogActivity`(15:46:56) → 15:47 崩溃。
- **复现 / 预期 / 实际（先问过用户）**：用户手上只有别人的崩溃报告，复现步骤不知道；「**偶现，只见过一两次**」；预期/实际都不知道；未能提供应用日志。崩溃前的 `LoginActivity` 是**TV 扫码**流程（扫码页由登录页的「登录方式切换」卡片打开，`isTVMode` 可在运行期切换）。
- **retrace（R8 下源码行号不可信，必须用 mapping）**：用 26.10.19 tag `ee57bcf` 在独立 worktree 重建 `:app:assembleStandardRelease`，取 `app/build/outputs/mapping/standardRelease/mapping.txt`（25.8 MB，不进版本库）逐帧还原：
  - `androidx.fragment.app.Fragment -> h0.u`，且 `requireContext() -> M` / `getResources() -> l` / `getString(int) -> m` ⇒ 那三帧就是 `getString → getResources → requireContext`，最后一句抛的正是这条 ISE；
  - `activity.settings.login.QRLoginFragment$$ExternalSyntheticLambda10 -> R0.m`（R8 把该页所有 lambda 合并成一个合成类，每个 lambda 在 `run` 里占一段行号），崩溃行 477 落在 `470:491 … detectTVLoginState$lambda$13(...):284:284 -> run` 那一段；
  - ⇒ 崩点 `app/src/main/java/com/RobinNotBad/BiliClient/activity/settings/login/QRLoginFragment.kt:284`（**26.10.19 版本的行号**，修复后该处已改为 `getAppContext().getString(...)`）：`scanStat.text = getString(R.string.text_qr_hint_tv)`，位于 `detectTVLoginState()` 的 `CenterThreadPool.runOnUiThread { … }`（TV 扫码「已扫码，等待手机确认」分支）。
- **根因**：`util/CenterThreadPool.java:143` 的 `runOnUiThread` 只是无条件 `MAIN_THREAD_HANDLER.post(...)`（主线程调用也入队），而 QR 轮询跑在 `Timer().schedule(task, 500, 1000)` 的 Timer 线程里；`detectTVLoginState()` 在 `if (!isAdded) return` **之后还要读一次网络响应**（`response.body!!.string()`）才 post 那个 Runnable —— **判活与执行之间隔着一整个网络往返**。用户点「跳过」（`from_setup` 分支 `startActivity(SplashActivity)` + `requireActivity().finish()`）使 fragment detach，在飞的 tick 返回后 post 的 Runnable 在 detach 之后才执行，`getString` → `requireContext` 抛 ISE。反向也印证：Timer 线程上的同类异常会被 `CenterThreadPool.run` 的 `catch (Throwable)` 吞掉、不崩，**只有 post 到主 Handler 的那条会带走进程**。
- **为什么 26.10.19 才炸**：26.10.19 的文案迁移（`3253d9a chore(text): 文案迁移 WIP 基线`）把这批 `setText("正在获取二维码")` 之类的**字面量**换成了 `getString(R.string.…)`。迁移前 detach 后写 TextView 只是「静默无效」，迁移后同一个竞态直接升级成 `IllegalStateException`（`docs/copy-inventory/STRINGS-MIGRATION.md:338` 记二维码状态机 24 处）。
- **修法（沿用「判活写进 Runnable 内部」这条既有铁律，不再在 post 前判）**：
  - `activity/base/BaseFragment.kt` 的 `runOnUiThread`：`if (isAdded) requireActivity().runOnUiThread(runnable)` → `val host = activity ?: return; host.runOnUiThread { if (isAdded && view != null) runnable.run() }`；`activity/search/SearchFragment.kt` 里那份重复实现同改，`activity/search/` 的 5 个子 Fragment（Video/Article/Live/Bangumi/User）另在各自 `CenterThreadPool.runOnUiThread` 块首行补 `if (!isAdded || view == null) return@runOnUiThread`。
  - 登录三碎片（`QRLoginFragment` / `SMSLoginFragment` / `PasswordLoginFragment`）统一继承 `BaseFragment`；**纯文案一律在后台线程用 `getAppContext().getString(...)` 先取好**（application context 不随 detach 失效），post 出去的 Runnable 只做 view 赋值；必须用 activity 的地方（`startActivity(Intent(requireContext(), …))`、`requireActivity().finish()`）整段包进 Runnable 内部的 `if (isAdded)`。
  - QR 的 TV / Web 两个登录成功分支整段包进 `if (isAdded)`（原先是先 `startActivity(requireContext()…)` 再判活，**顺序反了**，Web 分支还会在 Timer 线程上抛）；`SMSLoginFragment.setStatusText` 的 14 个调用点改成传 `getAppContext().getString(...)`（`setStatusText` 自己的 `if (!isAdded) return` 在求值之后才生效，挡不住 `getString`）；`SMSLoginFragment.handleLoginSuccess` 的落盘 + `requestSSOs()` 从主线程挪进 `CenterThreadPool.run`（原来整段网络请求跑在 UI 线程，顺带修卡顿）。
  - `activity/base/RefreshListFragment.loadFail()`、`SearchFragment.loadFail()` 的「加载失败」提示改用 application context 取文案（失败回调常发生在 detach 之后）。
- **构建 / 单测**：`:app:assembleDebug` 两 flavor `BUILD SUCCESSFUL`（58s，135 tasks）；`:app:testStandardDebugUnitTest` 与 `:app:testCoexistDebugUnitTest` 均 **675 tests / 0 失败 / 0 错误 / 0 跳过**。本次改动依赖 Android 运行时的 Fragment 生命周期，无 JVM 单测覆盖，靠回归。
- **未验证 / 残留**：本机无登录态与 TV 端，无法真机复现「扫码中途点跳过」。判据来自 mapping 还原出的崩溃行 + 「判活跨一次网络往返」的代码链路，属确定结论而非猜测。`Timer` 仍不会在 `onDestroy` 取消已在飞的 tick（`util/CenterThreadPool.java` 目前只有 `post`/`postDelayed`、没有 `removeUiCallbacks`），后续要彻底清理可补 API 并在 `onDestroyView` 撤销。

### 3.39 正文整段是一个链接时长按复制失效（26.10.20）

- **现象（用户报告）**：用户贴了两张评论区截图——评论正文只有一行 `https://www.bilibili.com/`，「管理员 纯洁のkekewhere」在下面回复「**如果评论的内容只有网站是将无法长按复制**」。即：正文只要整段是网址，长按不弹「操作面板 / 复制」，抬手反而把链接打开；更一般地，**手指落在链接字符上长按就无效**，落在同一段文字的非链接字符上则正常。影响不止评论——动态正文、专栏正文等所有经 `StringUtil.setLink` 处理的文本同理。
- **根因**：`util/StringUtil.java` 的 `ClickableSpanTouchListener`（改动前在 `onTouch` 的 ACTION_DOWN 命中 `ClickableSpan` 时**直接 `return true`**）。OnTouchListener 由 `View.dispatchTouchEvent` 在 `TextView.onTouchEvent` **之前**调用，DOWN 返回 true 即把整段触摸从 View 手里拿走：View 收不到按下事件就**不会启动长按检测**（长按检测只在 `View.onTouchEvent` 的 DOWN 分支里 post），于是同一个 TextView 上的 `setOnLongClickListener`——`adapter/ReplyAdapter.kt:246` 的「长按操作面板」、`util/StringUtil.java:220` 的 `setCopy` 复制——**永远不会触发**；旧实现还在 ACTION_UP 里直接把 `span.onClick(widget)` 打开链接，所以观感是「长按没反应、抬手跳走了」。评论正文由 `model/Reply.java:167` 的 `StringUtil.setLink(messageSpannable)` 自动打链接 span，正文只有网址时整块文字都被同一个 span 覆盖，于是整块长按都失灵。同一个监听器同时挂在 `StringUtil.setLink(TextView...)`（`:291`）、`setAtLink(TextView...)`（`:429`）与动态正文（`adapter/dynamic/DynamicHolder.kt:749`）上。
- **修法（`util/StringUtil.java`，不再消费 ACTION_DOWN，改成「DOWN 只记录 + UP 判定」）**：
  - DOWN：记下命中的 span 与 `event.getEventTime()`，**return false**，把事件交还 View 正常启动长按判定；
  - UP：先用新纯函数 `StringUtil.isLongPressGesture(widget.isLongClickable(), downTime, event.getEventTime(), ViewConfiguration.getLongPressTimeout())`（`:541`）判「按住时长是否已达到系统长按阈值」。是 → 长按回调已经处理过这次手势（`ReplyAdapter` / `setCopy` 的回调都 `return true`，View 不会补发 click），直接 return false 什么都不做；
  - 判抬手点是否仍命中**同一个** span（新增私有 `findLinkAt(TextView, Spanned, MotionEvent)`，`:640`；判空 layout、clamp 行号、`getSpans(off, off, ClickableSpan.class)`），不命中就 return false（滑动不该被当成点击）；
  - 命中则 `widget.cancelLongPress()` + `widget.setPressed(false)` + `span.onClick(widget)` + return true。**`cancelLongPress()` 不能省**：UP 被我们消费后 View 收不到收尾事件，它 post 出去的长按任务不会自己消失，会在抬手之后把长按面板弹出来；
  - CANCEL 清状态。没走自建 `postDelayed` 计时（`adapter/video/VideoCardHolder.kt:122-144` 的 `suppressClickAfterLongPress` 那套）——这里复用 View 原生长按机制，阈值、滑动取消、系统「长按延迟」设置全都跟系统一致。
- **单测**：`app/src/test/java/com/RobinNotBad/BiliClient/util/StringUtilTest.java` 新增 4 例覆盖 `isLongPressGesture`：阈值内 / 超阈值、边界（时长恰好等于阈值算长按）、TextView 不可长按时**永远**不判长按（否则链接点不动）、没有 DOWN 时间记录时保持旧的「抬手即点链接」行为。
- **构建 / 单测**：`:app:assembleDebug` 两个 flavor `BUILD SUCCESSFUL`（10s，121 tasks）；`:app:testStandardDebugUnitTest` **66 suites / 694 tests / 0 失败 / 0 错误**（`StringUtilTest` 7 例全过）；`:app:testCoexistDebugUnitTest`（同源码）`BUILD SUCCESSFUL`。
- **未验证 / 残留**：无真机复测（评论页要登录态）。长按判据是「按住时长 ≥ `ViewConfiguration.getLongPressTimeout()`」，若某 ROM 把长按判定调得比该值更晚，极短的一小段区间内抬手可能仍被当成点击——真机回归第 249 条会确认。真机验证清单见 `docs/review/real-device-regression-checklist.md` 第 249 条，判据与复用规则见 `docs/architecture-map.md` §7.48。

### 3.40 低分辨率设备上弹窗显示不全：宽度没落地 + 高度预算把提示行挤没了（26.10.20）

- **现象（用户报告：26.10.20，附截图）**：启动时的「隐私说明」弹窗在低分辨率设备上显示不全——正文右边缘被切断（半字）、底部「正文较长，可上下滑动阅读」看不见、按钮行贴底甚至被顶出屏幕。用户原话只有「修复低分辨率设备应用的弹窗显示不全的问题」+ 一张图，设备型号与分辨率未提供；截图里弹窗左沿距屏幕仅约 2px、右侧看不到描边。
- **复现（MuMu 模拟器 380×380 / 320dpi + DebugBridge 改 `dpi`，等价于「设置 - 布局适配 - 界面大小」）**：
  - 默认 1.0 倍、380×380：`dumpsys window` 里弹窗窗口 `mAttrs=(wrapxwrap)`、`mFrame=[0,0][380,380]`，即**整屏宽**；视图树 `title [0,0][380,80]`、`scroll [0,82][380,253]`(171=45%×380)、`buttons [0,253][380,341]`、`hint [0,341][380,380]` —— 四行**刚好**凑满 380，纯属巧合。
  - 320×320：`title 80 + divider 2 + scroll 144(45%) + buttons 88 + hint` = 314，提示行只剩 **6px**（文字必然被裁）；300×300 同理需 344px。
  - 1.8 倍：`widthPixels` 随 dpi 一起放大 → 窗口比屏幕还宽（WMS 裁到屏内，表现为「右侧被切」）；dp 固定行也放大 1.8 倍 → 高度溢出、提示行整个消失；两个三字按钮 `minimumWidth` 各 158px、合计 468px > 342px，**右对齐的「同意」被挤出屏幕**（`choice()` 是 `setCancelable(false)`，等于用户卡死在闪屏）。
- **根因（两条，互相独立）**：
  1. **宽度写早了**：`Sheet.init` 里 `window.setLayout(屏宽×0.9, WRAP_CONTENT)` 在 `show()` 之前调用，而 appcompat `AlertDialog.onCreate()` 的 `installContent()` 会把自定义视图**重新** `addView(child, MATCH_PARENT, MATCH_PARENT)` —— 于是宽度被覆盖回整屏宽（26.10.20 之前 `docs/architecture-map.md` §8.7.0 写的「已把宽度限到约 90%、两侧各约 24px」**从未生效**）。
  2. **高度预算是加法凑的**：`capScrollHeight()` 把滚动区钉死 `屏高 × 45%`，再加上一堆 dp 固定高度（标题/分割线/底栏/提示行）——`45% + 固定部分` 在小屏上必然超屏，且「界面大小」放大后固定部分 ×1.8、`45%` 不变，越放大越挤。
- **修法**：
  - 新增 `app/src/main/java/com/RobinNotBad/BiliClient/util/view/DialogSizing.kt`（纯函数 + JVM 单测）：`Frame`/`available()`（扣「界面边距」百分比，与 `BaseActivity` 同一套整数算法，保证圆屏不被切角）/`width()`（90%）/`heightBudget()`（94%）/`plan()`（预算 − 实测的标题/分割线/底栏/提示行，放不下先舍提示行再舍标题，滚动区兜底一行高）。
  - `Sheet`：`init` 末尾挂 `setOnShowListener`，在窗口 attach 完、第一帧 layout 之前再调一次 `prepareLayout()` 把宽度落到 `content.layoutParams`；`init` 里那次保留当兜底。行高**实测**（都随界面大小缩放，不能写死 dp）；打字模式（`typingEdit != null`）不钉滚动区高度。
  - `Sheet.fitButtonBars()`：底栏/按钮行放不下时逐级缩字号（下限 9sp，同时松内边距与 44dp 最小宽度），仍放不下改成均分宽度——修掉 1.8 倍下「同意」点不到的死局。
- **构建 / 单测**：`:app:assembleDebug` 两个 flavor `BUILD SUCCESSFUL`；`:app:testStandardDebugUnitTest` **690 tests / 0 失败**、`:app:testCoexistDebugUnitTest` **694 tests / 0 失败**（新增 `DialogSizingTest` 15 例，其中 `parts380` 用模拟器实测值）。
  - 模拟器实测（coexist 包，`uiautomator dump` 取真实 bounds）：380×380@1.0 → 窗口 `[19,10][361,369]`（342 宽、左右各 19px）、标题/正文/按钮/**提示行全在屏内**；320×320@1.0 → `[16,9][304,311]`、舍提示行；240×240@1.0 → `[12,7][228,232]`、舍标题与提示行；380×380@1.8 与 320×320@1.5 → 两个按钮都完整可见、未被挤出。
- **未验证 / 残留**：真机未复测（本机只有模拟器 + 用户正式包，无低分辨率真机）；用户原设备分辨率未知，但两条根因与分辨率无关（任何「屏高 < 约 400px」或「界面大小 > 1.0」都会命中）。1.8 倍下按钮字号被缩到 9sp——可读、可点，但明显小于正文；那档位本身已属于「整屏只放得下两三个元素」的极端档。

### 3.41 短视频取流协议修订：分页状态、推荐路由、去重与降级（26.10.21，待真机）

- **背景**：短视频页（`short_video`）的取流是手拼查询串的一次性实现，有四个缺陷：①`pull` 传的是字符串 `true`，而接口取值是 `0`/`1`；②丢弃服务端下发的 `track_id`，翻页无法跟随推荐路由；③主接口失败后的兜底请求 `/x/v2/feed/index` 不在接口文档端点表里（可用的是动态流 `/x/v2/feed/index/dynamic/story`）；④页码 `currentPage` 挂在 object 上，退出再进不归零。
- **修法**：
  - 新增 `api/StoryFeedQuery.kt`（纯函数，无 Android 依赖）：`buildParams`（`display_id` 批次序号、首屏 `pull=0`/翻页 `1`、`open_event=cold`、`trackid` 仅正式路由回传、**不发 `from`/`from_spmid`/`spmid`**——三者混传服务端直接 `-400`）、`buildUrl`、`percentEncode`（不用 `URLEncoder`，空格必须是 `%20`）、`isDegradedTrackId`、`isClearlyHorizontal`（有 `dimension` 且 `width>height` 且 `rotate==0` 才丢）、`parseFeed`（`code!=0`→空、横屏过滤、按 `aid` 批内 + 跨批去重、取最后非空 `track_id`）。
  - `api/ShortVideoFeedApi.kt` 保留 `fetchVideoUrl` 不动；新增 `class StoryFeedPager` 持会话级分页状态（`display_id`/`lastTrackId`/`seenAids`/降级只记一次日志），主信息流空则走动态流兜底；旧 `currentPage` 与 `fetchIndexFeed` 删除。
  - `util/VideoPreloadManager.kt` 改为持有 `StoryFeedPager` 实例，页面销毁随实例丢弃（不再有跨页面的静态分页状态）。
- **构建 / 单测**：`:app:assembleDebug` 两个 flavor `BUILD SUCCESSFUL`；`:app:testStandardDebugUnitTest` / `:app:testCoexistDebugUnitTest` 各 **67 suites / 714 tests / 0 失败**（新增 `StoryFeedQueryTest` 20 例）。首次跑挂 1 例——测试里的 raw string 以 `"` 结尾被 Kotlin 吃掉一个引号导致 JSON 非法，改成整条 JSON 字面量后通过（产品代码无问题）。
- **未验证 / 残留**：真机未跑（本会话无设备），用例见 §2.1 第一条。未做：`relate/story`、`space/story`、`request_from` 池切换、点赞/投币/收藏、曝光与行为上报（见 `docs/superpowers/specs/2026-10-21-short-video-feed-protocol.md` §2.1）。
- **口径提醒**：降级池（`track_id` 以 `gateway_fallback_` 开头）只记日志、内容照常播放；**不要**把降级 `track_id` 回传（会把后续请求钉死在降级链路）。

### 3.42 短视频分辨率不跟随「视频设置 → 分辨率」（26.10.21，待真机）

- **现象**：在设置里把分辨率从 360P 改到 720P/1080P，普通视频生效，**短视频页仍是 360P**。
- **根因**：`ShortVideoFeedApi.fetchVideoUrl(item, qn = 16)` 把清晰度硬编码成 16（360P），播放页与预加载两处调用都没传 `qn`——设置里的 `SettingsKeys.PLAY_QN` 根本没被读过。
- **修法**：默认值改为 `defaultPlayQuality()`（读 `SharedPreferencesUtil.getInt(SettingsKeys.PLAY_QN, 16)`，老用户无该键仍回退 360P），`item.currentQuality` 改用新增纯函数 `StoryFeedQuery.pickQuality(qn, qnValueList)` 还原服务端**实际能给的档位**（≤请求值的最高档；列表缺失时原样返回请求值），不一致时记一条日志便于排查风控降级。
- **构建 / 单测**：`:app:assembleDebug` 两 flavor `BUILD SUCCESSFUL`；两 flavor 单测各 **67 suites / 718 tests / 0 失败**（`StoryFeedQueryTest` 20→24 例，新增 4 例覆盖 pickQuality）。
- **未验证 / 残留**：真机未跑。**改设置后需要重新进入短视频页才生效**（同一页内的 `item.videoUrl` 是会话内缓存，不主动重取）；未登录时 1080P 会被服务端降级，属预期。

### 3.43 内测码更新：长按「检查更新」从内测分享站下载（26.10.21，待真机）

- **需求（用户原话）**：「新功能，检查更新按钮长按弹出输入内测码功能，之后拼接二维码（`https://share.lanol.cn/#/?code=12345`）（纯数字），之后开始从这个链接下载更新」；用户补充「域名两个个，`file.zsapp.asia`，`share.lanol.cn`，那个有软件下载哪个」，后又要求「文件不存在和解析到非apk文件直接返回内测码错误提示即可」。**不显示二维码**：码只用来拼分享链接，应用内直接下载安装。
- **新增 `util/BetaUpdate.kt`（纯函数，JVM 单测直调，不读资源/Context）**：`HOSTS`（`file.zsapp.asia` → `share.lanol.cn` 顺序探测）、`SHARE_HOST`、`normalizeCode`（`^\d{1,20}$`）、`buildShareUrl`、`selectUrl`/`buildSelectBody`（`POST /share/select/`，body `{"code":"12345"}`）、`parseSelectResponse`（分享站 FileCodeBox：成功取 `detail.download_url`，相对路径经 `absoluteUrl` 补全；`is_text` 或 `download_url` 为空 → `NotFound`）、`isApkFileName`（`endsWith(".apk")`，分享站不校验上传内容）、`formatSize`。新增 `BetaUpdateTest` 12 例。
- **`util/UpdateManager.kt`**：新增 `resolveBetaShare(code, onResult, onError)`，`CenterThreadPool.run` 内按 `HOSTS` 逐个 `selectBetaShare`，第一个 `Found` 即回调；**两个域名都没有包 / 解析到的不是 `.apk` → 统一回调「内测码错误」**（不拼域名细节）；只有出现网络异常才回「获取内测包失败：<原因>」。不做版本比较。
- **`activity/settings/UpdateActivity.kt`**：`onCreate` 改成 `when` 三分支，新增 `beta_code` 模式——先 `deleteOldApkFile()`（否则 Range 续传会拼两份不同 APK），`resolveBeta()` → 解析成功即 `showBetaInfo()` + `startDownload()`；`retryBtn` 在内测模式重新解析（分享站下载地址带签名/时间窗，旧地址可能失效）。
- **`activity/settings/SettingGroupActivity.kt`**：关于页「检查更新」加 `onLongClick` → `showBetaCodeDialog()`；短按仍是进 `UpdateActivity` 检查更新。`util/TerminalDialog.input` 新增 `digitsOnly` 参数（数字键盘 + `InputFilter`，粘贴的字母也会被过滤）。
- **坑 1（本次排查最久）**：`TerminalDialog` 的工厂方法**只 `create()` 不 `show()`**，调用方漏了 `.show()` 就「长按没反应」——没有弹窗、没有异常、logcat 干净。同 §3.35 已记的结论，本次又踩一次：**长按无反应先查 `.show()`**。
- **坑 2（380×380 错误态布局）**：下载失败态原来同时显示「进度条 + 两行错误文案 + 取消/重试两个 44dp 按钮」，`progress_container` 比屏幕还高，`alignParentBottom` 被顶到 y=0、错误文案压住标题栏。修法：错误态隐藏进度条与「取消下载」（返回键等价），只留「重试下载」；`startDownload()` 里恢复进度条可见。
- **`.txt` 内测码按公告展示（26.10.21 追加）**：用户要求「如果检测到的是.txt的文本就像展示公告一样的展示他」。分享站不校验上传内容，内测码可能对应一段文本：`BetaUpdate.isTextFileName`（`endsWith(".txt")`）与 `isApkFileName` 并列成为「可展示」白名单；`UpdateActivity.resolveBeta()` 里分支到 `openBetaText()` → `UpdateManager.downloadBetaText(url, onResult, onError)`（`okHttpClient` GET、UTF-8、`BetaUpdate.decodeText` 去 BOM、上限 `BETA_TEXT_MAX_BYTES = 2MB`、不落盘、不走 `downloadApk`）→ `MsgUtil.showText(标题, 正文)`，标题取文件名去掉 `.txt`（空则用 `text_update_beta_text_title`=内测公告），页面就是公告列表点开的 `ShowTextActivity`（支持长按复制）。新增字符串 `text_update_beta_text_loading`=正在下载内测文本…、`text_update_beta_text_failed`=获取内测文本失败：%1$s、`text_update_beta_text_title`=内测公告。
- **`.txt` 链路验证方式（没有真实 txt 内测码，用 mock 打通全链路后回退）**：`build/mock_share.py` 本地起 HTTP mock（`POST /share/select/` 返回 `{"detail":{"name":"内测公告.txt","download_url":"/files/notice.txt"}}`，`GET /files/notice.txt` 返回带 BOM 的 UTF-8 文本），临时把 `BetaUpdate.HOSTS`/`SHARE_HOST` 指向 `http://127.0.0.1:18080` 并在 `network_security_config.xml` 临时放行 `127.0.0.1` 明文，`adb reverse tcp:18080 tcp:18080` 让模拟器回环到宿主机；装包后长按「检查更新」→ 输入 `12345` → 直接进 `ShowTextActivity`，标题「内测公告」、正文 BOM 已去掉、logcat `内测包来自 http://127.0.0.1:18080：内测公告.txt`。**验证完已全部回退**（`git checkout` 网络配置、`BetaUpdate.kt` 从备份还原、删除 mock 与 `adb reverse --remove-all`），重装干净包对真实域名复测 `12345` → 仍提示「内测码错误」。
- **验证（MuMu 380×380 模拟器，非真手表）**：长按「检查更新」→ 弹窗「输入内测码 / 纯数字内测码 / 取消 / 下载」；输入 `ab12c34` 只留下 `1234`；`99999`（分享站上是一张 jpg）与 `0000000000`、`12345`（不存在）均提示「内测码错误」并返回设置页，**不再下载**；截图确认错误态不再压标题栏。
- **构建 / 单测**：`:app:assembleDebug` 两 flavor `BUILD SUCCESSFUL`；`:app:testStandardDebugUnitTest` / `:app:testCoexistDebugUnitTest` 各 **68 suites / 733 tests / 0 失败**（`BetaUpdateTest` 15 例：新增 txt 解析、`isTextFileName`、`decodeText` 去 BOM）。
- **未验证 / 残留**：没有真实有效的内测码，**未跑通「下载 → 校验 → 安装」完整正链**（旧版本链路已在 `docs/FEATURES.md` §14.15 的长按说明范围内验证过下载与 `ApkVerifier`）；`.txt` 展示链路已用本地 mock 打通（见上）；真机未测；`SettingsIndex` 里搜索出来的「检查更新」条目仍是直接进 `UpdateActivity`，无长按。

### 3.44 图片下载器「下载完成」但相册/文件管理里找不到：写完从不登记媒体库（26.10.21）

- **现象（用户报告）**：长按/点击图片（视频封面、动态图、评论图等）→「保存图片」，**有「下载完成」提示，但相册/图库里没有，文件管理器里也找不到**。用户环境记不清，按代码逻辑排查。
- **复现（已实际复现，手表模拟器 Android 12 / SDK 32 / 380×380，小米 `24031PN0DC`，debug 包 26.10.19）**：用 DebugBridge 直接拉起 `DownloadActivity`（`type=0`、`link=https://i0.hdslb.com/bfs/face/member/noface.jpg`、`path=/storage/emulated/0/Pictures/哔哩终端/`），4 秒后：
  - `ls -la` → 文件**确实下好**：`-rwxrwxrwx 1 root root 3719 … noface.jpg`（内容正确）→ 排除「写盘失败」「onDestroy 误删」（成功流程先 `finishFlag = true` 再 `finish()`，`onDestroy` 只在 `!finishFlag` 时删 `touchedFiles`）；
  - `content query --uri content://media/external/images/media` → 表里**没有这一行**（当时 160 行，全是 Screenshots/DCIM 的历史图）→ 相册/图库（只读 MediaStore）当然看不到；
  - 手动补一次 `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///…/noface.jpg`，2 秒后立刻出现 `_id=462 _display_name=noface.jpg relative_path=Pictures/哔哩终端/`（表 161 行）→ **缺的就是登记这一环**。
  - 设备上相册/文件相关包只有 `com.android.gallery3d` / `com.android.documentsui` / `com.android.providers.media.module`——「文件管理器里也找不到」同样是「媒体库没有这条记录」的表现（按媒体分类浏览走 MediaStore）。
- **根因**：`activity/DownloadActivity.kt` 的 `handleDownloadComplete()` 里图片分支**只弹一句「下载完成」**，下载本身用裸 `File`/`FileOutputStream` 写公共 `Pictures/哔哩终端/`，全仓**没有任何 MediaStore 写入或 `MediaScannerConnection`**。Android 10 起（本应用 `targetSdk 34`）用文件路径写进公共目录的文件**不会被媒体库自动索引**，于是文件在、相册没有。成功提示又是通用的 `text_update_download_complete`（「下载完成」，不含路径），用户连去哪儿翻都不知道。
- **修法**：
  - 新增 `app/src/main/java/com/RobinNotBad/BiliClient/util/PictureMediaStore.kt`：`mimeTypeOf(fileName)`（纯函数，按后缀判 `image/*`，认不出返回 null 就不登记，免得把错误页塞进相册）、`displayFolder(folderPath, externalRoot)` / `displayFolder(file)`（纯函数 + 便捷重载，把绝对路径裁成 `Pictures/哔哩终端` 这种能对上文件管理器的层级）、`scan(context, file)`（`MediaScannerConnection.scanFile`，异步回调里 `uri == null` 记日志；**吞异常只落日志**——文件已经落盘、下载确实成功，扫描失败最坏是相册晚点出现，不该反过来判下载失败）。
  - `DownloadActivity.handleDownloadComplete()` 图片分支（`type == 0`，入口只有 `ImageViewerActivity`）先 `PictureMediaStore.scan(this, downloadedFile)`，提示改成 `R.string.msg_picture_saved` =「图片已保存：%1$s\n可在相册中查看」（`%1$s` 就是上面裁出来的目录）。视频/APK 两条分支不动（视频目录是应用专属 `Android/media/`，且`save_ban_gallery` 本来就把 `.nomedia` 写进去刻意不进相册）。
- **构建 / 单测**：`:app:clean` 后 `:app:testStandardDebugUnitTest :app:assembleDebug` 通过；`:app:testStandardDebugUnitTest` / `:app:testCoexistDebugUnitTest` 各 **70 个测试类 / 747 例 / 0 失败 0 错误 0 跳过**（两个 flavor 同源码；`PictureMediaStoreTest` 各 7 例）；`app/build/outputs/apk/` 下两个 flavor 的 debug 四个 ABI 分包都在。
- **真机验证（模拟器，`MediaStore` 实测，已通过）**：`adb install -r` 装新包后连下两张图（`Pictures/哔哩终端/verify/`、`verify2/`），`content query --uri content://media/external/images/media` **不靠手动扫描**就直接出现 `_id=465 relative_path=Pictures/哔哩终端/verify/`、`_id=467 … /verify2/`（全表 160→161→162 行）；`am start -a android.intent.action.VIEW -d content://media/external/images/media/467 -t image/jpeg` 由系统相册 `com.android.gallery3d` 正常渲染出该图 → **相册里确实能看到**。提示文案实测为「图片已保存：Pictures/哔哩终端/verify3\n可在相册中查看」（`displayFolder` 已把绝对路径裁成相对层级）。
- **残留**：Android 11+ 上「应用能不能用文件路径写公共 `Pictures/`」本身取决于是否拿到「所有文件访问」（`FileUtil.ensureDirectoryWritable` 会在不可写时拉起授权页并直接结束页面，所以这里不会出现「没权限却假装成功」）；更彻底的写法是 API 29+ 直接走 `MediaStore.Images` 的 `IS_PENDING` 插一条再写流（免权限、天然登记），本次没做，属可选后续。`getFileNameFromLink` 不去 query 串、重名覆盖等既有问题不在本次范围。

### 3.45 线上崩溃 #66：回车推进焦点，平台自己抛 ISE（26.10.21）

- **现象（用户报告：26.10.20 21:18 / 版本 26.10.16 / XTC `msm8937_32go_i25d` Z7S / SDK 27 / 380×380 档手表）**：`java.lang.IllegalStateException: focus search returned a view that wasn't able to take focus!`，`main` 线程；栈自下而上：`ActivityThread.main` → `ViewRootImpl$ViewPostImeInputStage.processKeyEvent` → `DecorView.dispatchKeyEvent` → 三层业务 `dispatchKeyEvent`（`h.x`/`h.j`/`G.j`，即混淆后的 Activity/AppCompat 包装链）→ `q3.b.o` → `PhoneWindow.superDispatchKeyEvent` → `DecorView.superDispatchKeyEvent` → 11 层 `ViewGroup.dispatchKeyEvent` → `KeyEvent.dispatch` → **`android.widget.TextView.onKeyUp(TextView.java:7456)`**。页面轨迹最后停在 `SettingTerminalPlayerActivity`（21:18:21 打开，21:18:26 去过 `ListChooseActivity` 又返回，21:18:27 之后崩）。应用日志无相关信息（只有短视频接口 `-400`）。
- **复现 / 预期 / 实际（先问过用户）**：用户手上只有崩溃报告，**没有可复现步骤**；预期是「在设置页的输入框里按回车/软键盘完成键 → 收起键盘或跳下一项」；实际直接进崩溃页（`CatchActivity`）。
- **根因（平台前后判据不一致，不是本仓库某处写错）**：AOSP `TextView.onKeyUp` 的 `KEYCODE_ENTER` 分支（8.1 `TextView.java:7424-7477`、throw 在 `7453`；12 `8662-8716`、throw 在 `8690`，逐字同构）：`event.hasNoModifiers()` 且该 TextView **没有 OnClickListener** 时，若 `(event.getFlags() & KeyEvent.FLAG_EDITOR_ACTION) != 0` **或** `shouldAdvanceFocusOnEnter()` 为真，就 `focusSearch(FOCUS_DOWN)`；`v != null && !v.requestFocus(FOCUS_DOWN)` → **平台自己 throw**。`shouldAdvanceFocusOnEnter()`（8.1 `7145-7171` / 12 `8387-8406`，逐字一致）= 单行（`mSingleLine`）为真；或文本类且 variation 是 `EMAIL_ADDRESS`/`EMAIL_SUBJECT`。两处判据不一致：**找候选**只按 `focusableInTouchMode` 过筛（触摸模式下用 `FOCUSABLES_TOUCH_MODE`，`View.addFocusables`），**给焦点**时 `requestFocusNoSearch()` 还拒「触摸模式下不是 `focusableInTouchMode`」和「祖先 `descendantFocusability=blocksDescendants` / `shouldBlockFocusForTouchscreen()`」（8.1 `View.java:10850-10870`；12 `13517-13532` 另加一道 `canTakeFocus()`，连 DISABLED/零尺寸也拒 → 缺口更大）。应用侧改不了平台判据，只能兜住。
- **为什么 `adb input keyevent 66` 在模拟器上复现不出来（重要，别再照着试）**：物理 ENTER 的 **DOWN 会先退出 touch mode**（`ViewRootImpl.isNavigationKey` 含 `KEYCODE_ENTER`，`checkForLeavingTouchModeAndConsume` 在无 `FLAG_KEEP_TOUCH_MODE` 时 `ensureTouchMode(false)`；12 `7476`/`7497-7532`，8.1 `5816-5874` 同）→ 到 `onKeyUp` 时已是非触摸模式，两处判据一致，选中的 view 基本必能拿到焦点；`focusSearch` 返回 null 时那个 `if` 直接跳过、也不抛。已在 380×380 Android 12 模拟器上按此路径试过四个页面（`SettingMainActivity` 搜索框、`SettingTerminalPlayerActivity` 数字输入行、`InputDialogActivity`、播放器），全部 pid 不变、logcat 无 ISE。真要制造错配只能人为写 `android:nextFocusDown` 指向 `blocksDescendants` 容器里的 view / 零尺寸 view，本仓库 `res/` 里 **0 处** `nextFocus*`、`descendantFocusability`、`touchscreenBlocksFocus`（已 grep），所以此路不通。
- **软键盘也被这条把式覆盖**：`BaseInputConnection.performEditorAction` 不直接调 `TextView.onEditorAction(int)`，而是合成一对带 `FLAG_SOFT_KEYBOARD|FLAG_KEEP_TOUCH_MODE|FLAG_EDITOR_ACTION` 的 ENTER DOWN/UP（8.1 `569-582`、12 `665-678`），经 `InputMethodManager.dispatchKeyEventFromInputMethod`（8.1 `1913-1923`、12 `2630-2651`）→ `ViewRootImpl.dispatchKeyFromIme` 入队 → **和物理按键同一条分发栈**。所以崩溃栈里分不出是手按还是软键盘按的，而「IME 动作」这条也一并被下面的兜底管住。
- **修法（不猜具体输入框，兜在按键进 Activity 的必经之路上）**：
  - 新增 `app/src/main/java/com/RobinNotBad/BiliClient/util/view/EditorEnterKeyGuard.kt`：`FOCUS_SEARCH_FAILURE_MESSAGE`（平台原文常量）+ 纯函数 `isFocusSearchFailure(Throwable?)`（沿 cause 链最多 8 层找「`IllegalStateException` 且 message 逐字等于原文」）+ `swallowFocusSearchFailure(tag, e)`（命中记 `Logu.e` 并返回 true）。**只认原文、不做 `IllegalStateException` 一把抓**（同名 ISE 别处可能是真 bug）。
  - `activity/base/BaseActivity.kt` 与 `activity/player/PlayerActivity.kt` 各加一个 `dispatchKeyEvent` 覆写：`try { super.dispatchKeyEvent(event) } catch (e: IllegalStateException) { if (!EditorEnterKeyGuard.swallowFocusSearchFailure(tag, e)) throw e; true }`。选 `dispatchKeyEvent` 是因为崩溃在 `super.dispatchKeyEvent` 往下调视图树时才发生，`onKeyDown`/`onKeyUp` 那时根本轮不到；`PlayerActivity` 单独加是因为它**不继承 `BaseActivity`**（`class PlayerActivity : Activity()`）且带单行 `danmaku_send_edit`。
  - 弹窗（`TerminalDialog.input()` 造的 `AlertDialog`，全仓 2 处调用）是独立窗口，按键不经 Activity，给 Dialog 换 `Window.Callback` 也不能保证在崩溃前拦到；靠 `setOnEditorActionListener` 改掉分支又会变多行输入框的回车行为——两条都没做，记为**已知缺口**（线上从未出现弹窗内回车崩溃）。`InputDialogActivity` 是普通 BaseActivity 页，天然被覆盖。
- **单测**：新增 `app/src/test/java/com/RobinNotBad/BiliClient/util/view/EditorEnterKeyGuardTest.kt` 7 例（原文命中 / 包在 cause 里 / 大小写·空白·少末字符不命中 / 普通 ISE 不命中 / 同文案 `RuntimeException` 不命中 / null / 8 层上限边界）。
- **构建 / 单测（已跑，全绿）**：`:app:assembleDebug --offline` 两 flavor `BUILD SUCCESSFUL`（30s，121 tasks）；新单测 7 例全过；全量 `:app:testStandardDebugUnitTest :app:testCoexistDebugUnitTest` 各 **70 个测试类 / 747 例 / 0 失败 0 错误**（`--offline --no-build-cache --no-configuration-cache`）。
  - **踩到的坑（与本次改动无关，会误导判据）**：coexist 变体首跑全量单测时 `:app:compileCoexistDebugUnitTestKotlin` 报 `Unresolved reference 'formatDownloadSpeed'` / `'tutorial'` / `'page'` 等一堆错，看起来像主源码缺符号，实际是**增量编译把 `coexistDebug` 的 Kotlin 模块元数据写坏了**——`app/build/tmp/kotlin-classes/coexistDebug/META-INF/app_coexistDebug.kotlin_module` 只有 24 字节（正常 233 字节），于是「类能解析、顶层函数/DSL 解析不到」。删掉 `app/build/tmp/kotlin-classes/coexistDebug` 与 `app/build/kotlin/compileCoexistDebugKotlin` 后重编即恢复（同 AGENTS.md「增量编译单独坏掉」那条）。
- **未验证 / 残留**：本机无该机型（SDK 27）也无登录态，**未真机复现**；判据来自 AOSP 8.1/12 源码逐字核对 + 崩溃栈行号对位，属确定结论。风险面：只有「按键分发经过 Activity」的窗口被覆盖（弹窗见上）；`TextView.onEditorAction(IME_ACTION_NEXT/PREVIOUS)` 里同一条 ISE（8.1 `5926`/`5936`，12 `7164`/`7174`）不在本次范围——框架内只有无障碍 `accessibilityActionImeEnter` 直接调它。

### 3.46 短视频第二轮：推荐续接（指纹/去重/relate）与播放页左右滑（26.10.21，待真机）

- **需求（用户原话）**："改两个，第一个是短视频推荐，第二个是短视频的操作我想改成和b站手机版一样的操作流程，不懂的问我，我很多都没说清楚"。两轮追问后确定：推荐侧「内容更像 B 站 / 少重复、横屏、低质 / 加相关推荐、播完自动接下一条」；操作侧**只加左右滑**（右滑进 UP 主主页、左滑返回），单击 / 双击 / 长按与底栏**全部保持现状**，不新增互动按钮，并关闭本页的系统右滑返回。
- **取流侧改动**：
  - `api/ShortVideoFeedApi.kt` `buildHeaders()` 补 `Buvid`（Cookie 里的 `buvid3`，取不到就不发）与 `Display-ID`（`util/DeviceIdentity.kt`：32 位大写十六进制、持久化复用）；桌面报告实测缺指纹会落降级池。
  - `api/StoryFeedQuery.kt`：`buildParams(...)` 新增 `requestFrom`（0..6，默认 0，越界夹取，本轮不暴露 UI）；新增 `PATH_RELATE = "/x/v2/feed/index/relate/story"`、`buildRelatedParams(anchorAid, ...)`、`parseRelated(...)`。
  - `api/StorySeenStore.kt`（新增）：最近 **300** 条已消费 `aid` 存 `SharedPreferences("short_video_seen_aids")`，合并式写入（旧→新，超限丢最旧）；`StoryFeedPager` 每次成功取流后写回，解析时与它比对。
  - `api/ShortVideoFeedApi.kt` 的 `StoryFeedPager` 新增 `fetchRelated(anchorAid)`；`util/VideoPreloadManager.kt` 新增 `loadRelated()` 与 `onRelatedLoaded` 回调。
- **播放页改动**（`activity/video/ShortVideoPlayerActivity.kt`）：
  - `BaseActivity` 新增 `protected open val forceNoSwipeTheme: Boolean get() = false`，并入 `setTheme(ColorScheme.themeResId(theme, noSwipe = forceNoSwipeTheme || BACK_DISABLE))`；短视频页覆写 true。**不能在子类 `super.onCreate` 前再 `setTheme`**，会被 BaseActivity 覆盖。
  - `GestureDetector.onFling`：横向位移 ≥ 24dp 且横向占优才处理（缩放中 `isScaling` 忽略）——右滑 `openAuthorPage()`（`UserInfoActivity` + `mid`，没 mid 提示），左滑 `activity.onSwipeLeftToBack()`（暂停后 finish）。用 `onFling` 而不是 `onScroll`，避免与 ViewPager2 纵向滑动抢手势。
  - `setOnCompletion` 由「`seekTo(0)` 循环」改为 `activity.onVideoCompleted(boundPosition)`：列表还有下一条就 `setCurrentItem(next)`；到底则以当前 `aid` 调 `loadRelated()`，结果到了自动切到追加的第一条；空结果提示「没有更多短视频了」并停下。`pendingAdvanceAfterRelated` 在用户手动翻页时清掉，避免被异步结果强行切走。`resume()` 保留「快看完时重播从头」的既有行为。
  - 新增文案 `R.string.text_shortvideoplayer_no_more` / `text_shortvideoplayer_no_author`（`strings.xml`）。
- **教程 / 文档**：`tutorial/Tutorials.kt` 的 `shortVideo` 教程补充左右滑与自动续接、`version` 1→2；旧 `res/xml/tutorial_short_video.xml` 同步补两条（旧链路已不展示，只作存证）；`docs/superpowers/specs/2026-10-21-short-video-feed-protocol.md` 增加 §6 记录本轮回合并修正 §2.1 非目标；`docs/FEATURES.md` §2.7、`docs/copy-inventory/03-tutorials.md` T-010/T-036 同步。
- **单测**：`StoryFeedQueryTest` 新增 7 例（`request_from` 夹取、`buildRelatedParams`、`parseRelated` 的数组/items/横屏过滤/跨批去重）；新增 `StorySeenStoreTest` 5 例（合并去重、300 上限、编解码容错、空值与脏数据）。
- **构建 / 单测（已跑）**：`:app:assembleDebug --offline` 两 flavor `BUILD SUCCESSFUL`（1m24s）；`:app:testStandardDebugUnitTest :app:testCoexistDebugUnitTest` 各 **71 个测试类 / 759 例 / 0 失败 0 错误 0 跳过**。
- **未验证 / 残留**：本机无可用设备，**全部交互与 relate 真机行为未验证**；`relate/story` 的响应结构是按桌面报告的字段表复用主信息流解析，真机若结构不同需单独解析。`space/story`、`request_from` 的用户入口、曝光/点击行为上报、点赞等互动仍未做（用户明确不要互动按钮）。短视频倍速下弹幕不同步（`DanmakuSync` 用播放器时间，倍速只在 IJK 侧生效）是老问题，本轮未动。
- **后续**：其中「右滑进 UP 主页 / 左滑返回」的手势在真机上直接被用户反馈为**闪退**，且用户要求改成与手机版一致的「横滑进入详情」，见 §3.47。

### 3.47 短视频改横向四页结构：短视流 → 详情 → 评论 → 相关推荐（26.10.21，待真机）

- **需求（用户原话）**："滑动直接闪退，而不是进入主页，建议滑动后视频详情页面做到右边，像搜索一样连起来，连贯一些，第一页，短视频，第二页到第四页，原有的视频详情卡片，不懂的问我"。追问后确定：第 2 页视频详情、第 3 页评论、第 4 页相关推荐；**手势完全按手机版**（单击显隐底栏、双击播放/暂停、长按详情、上下滑切视频、**横滑切详情/评论/相关推荐**）；短视频页**右滑 = 退出本页**；「试看模式」= 停下视频 + 给「播放」按钮（不由本页实现）；相关推荐返回才自动切下一条、失败则停；相关推荐**复用既有「视频详情页相关推荐」开关**（关掉时没有第 4 页）。
- **闪退处理**：本机无设备、用户未提供 logcat，根因未定位；直接**删掉整条旧手势路径**（页内 `onFling` + `openAuthorPage` + `UserInfoActivity` 跳转），横向手势全部交给外壳 pager，不再由页面内的 `GestureDetector` 抢。
- **结构重构**：
  - `activity_short_video_player.xml` 改为「黑底 FrameLayout + 横向 `ViewPager2`（id `pager`）」，短视频流挪进 `fragment_short_video_feed.xml`（id `viewPager` 的竖向 pager + 加载/错误三件套）。
  - 新增 `ShortVideoFeedFragment`：原 `ShortVideoPlayerActivity` 里的取流/预加载/竖向翻页/播放器生命周期/播完续接**整段搬入**；`itemView.setOnTouchListener` 返回值由 `true` 改为 **`false`**，否则事件被页内吞掉、横滑永远翻不到详情；`onPause` 暂停、`onResume` 续播（横向滑到详情时 FragmentStateAdapter 自动把这一页降到 STARTED，无需外壳通知）。
  - 新增 `ShortVideoDetailPageFragment` + `fragment_short_video_detail_page.xml`：右侧三页的**壳**，`onViewCreated` 按当前 aid 塞 `VideoInfoFragment.newInstance(aid, bvid)` / `ReplyFragment.newInstance(aid, 1, 0, -1, 0)` / `VideoRcmdFragment.newInstance(aid)`；aid 未就绪时显示「短视频还没加载好，先滑回去看看」。
  - 新增 `ShortVideoShellAdapter`（`FragmentStateAdapter`，注意包名是 `androidx.viewpager2.adapter`）：第 0 页 id 恒为 0（翻视频不重建），第 1~3 页 `getItemId = detailAid * 10 + position`（aid 一变整页重建）；`offscreenPageLimit = itemCount` 常驻。
  - 外壳只做三件事：`onFeedAnchorChanged()`（取当前 aid、变则 `notifyItemRangeChanged(1, count-1)`）、`currentShortVideo()`、转发 `onVideoCompleted(position)`。返回键在右侧页先回第 0 页；`dispatchTouchEvent` 里第 0 页右滑 fling → `finish()`。
- **文案/教程**：删除 `text_shortvideoplayer_no_author`，新增 `text_shortvideoplayer_detail_not_ready`；`tutorial/Tutorials.kt` 的左右滑说明改为「滑到右侧看详情、评论和相关推荐；短视频页右滑退出」，`version` 2→3；`res/xml/tutorial_short_video.xml` 同步。
- **未验证 / 残留**：无设备，**全部交互未验证**；详情/评论/相关推荐三页复用 `VideoInfoFragment` / `ReplyFragment` / `VideoRcmdFragment`，它们内部若假设宿主是 `VideoInfoActivity`（如 `viewPager` 字段）需真机确认——已 grep 过三者的 `viewPager` / `parentFragment` / `as VideoInfoActivity`，均无强依赖（`VideoInfoFragment` 仅在 `activity is VideoInfoActivity` 时才联动翻页）。`ReplyFragment` 首次进入会自己拉评论，评论页在「短视频还没加载好」时不会创建。闪退根因仍未定位，若真机复现需 logcat。

### 3.48 短视频横滑误触发修复：横滑不再交给 pager 抢，改由 `SwipePageLayout` 判向 + fakeDrag 跟手（26.10.21，待真机）

- **反馈（用户原话）**："误触发左右滑动的概率极高，所有滑动都会触发进入视频详情，且这些界面并没有真正连接到一起，而是跳转类似的"。
- **根因**：上一版把横滑完全交给外壳 `ViewPager2`，它判断「横向」的标准是 `dx > dy`（1:1），竖向刷短视频时手指稍微一斜就被它 intercept 成翻页；而且翻页判定发生在 pager 内部，页面层没有「这到底是不是横滑」的裁决权。
- **方案**：`pager.isUserInputEnabled = false` 彻底禁掉 pager 自己接触摸（已反编译 `viewpager2-1.1.0.aar` 的 `classes.jar` 确认：`ViewPager2$RecyclerViewImpl.onInterceptTouchEvent/onTouchEvent` 都以 `isUserInputEnabled()` 为闸门），改由**每一页的根布局** `ui/widget/SwipePageLayout` 判方向后驱动 pager 的 fakeDrag：
  - 判据：超过 `ViewConfiguration.touchSlop` 后 `|dx| > |dy| * 2` 才算横向（60° 以内的斜滑全算竖向，交给页内竖向 pager / 列表）；多指（缩放）一律不参与翻页。
  - 横向成立后 `beginFakeDrag()` + 每次 MOVE `fakeDragBy(ev.x - lastX)`，页面**跟手**移动；松手 `endFakeDrag()` 由 pager 自己按速度/位移吸附到目标页。
  - 松手时若已超过 `SWIPE_MIN_RATIO = 0.18` 屏宽、但 pager 因吸附阈值更严没翻过去，则补一次 `setCurrentItem`，避免「明明滑了却不翻页」。
  - 第 0 页向右滑：fakeDrag 无页可退，改用整页 `translationX` 跟手位移作反馈；超过阈值后整页滑出屏幕再 `finish()`（手感对齐系统右滑返回）。**判退出必须比对「按下时所在的页」**（`startItem == 0`），否则从详情页滑回第 0 页那一下也会被误判成退出。
- **fakeDrag 方向（反编译确认，别凭感觉改）**：`FakeDrag.fakeDragBy(float f)` 内部先 `mRequestedDragDistance -= f`，再 `RecyclerView.scrollBy(round(mRequestedDragDistance - mActualDraggedDistance), 0)`；`scrollBy` 正数 = 向后一页，所以**传负值 = 向后一页**（手指左移时 `ev.x - lastX` 为负，直接用这个差值即可；§3.49 修正了本节此前的符号写反）。`beginFakeDrag()` 只检查当前是否正在拖动，**不受 `isUserInputEnabled` 影响**，故禁用触摸后 fakeDrag 仍可用；`endFakeDrag()` 的吸附是**异步**的，松手后立刻读 `currentItem` 还是按下时的页。
- **接线**：`fragment_short_video_feed.xml` / `fragment_short_video_detail_page.xml` 根节点由 `FrameLayout` 换成 `SwipePageLayout`；两个 Fragment 在 `onViewCreated` 里 `pagerProvider = { host.shellPager() }`、`onSwipe = { host.handleSwipe(it) }`；`ShortVideoPlayerActivity` 删掉 `dispatchTouchEvent` 与 `swipeExitDetector`，新增 `shellPager()` / `handleSwipe(direction)`（-1 = 手指向左翻下一页，+1 = 手指向右翻上一页，`target < 0` 即退出本页）。
- **验证**：`./gradlew.bat :app:assembleDebug --offline` → BUILD SUCCESSFUL（两 flavor）；`:app:testStandardDebugUnitTest` / `:app:testCoexistDebugUnitTest` 各 71 类 / 759 例、0 失败（与 §3.47 时一致，本次只改 UI 手势链路，未动可选为单测的纯函数）。**真机手感（跟手、阈值 2:1、退出动画）未验证**。

### 3.49 短视频横滑方向/跟手修正 + 长按改 3 倍速（26.10.21，真机已验证）

- **反馈**：① "修复，在进入视频详情卡片页面后，滑动方向和实际手指方向相反的bug"；② "长按视频改为3倍数播放"。
- **根因（方向 + 不跟手）**：`FakeDrag.fakeDragBy(f)` 内部是 `mRequestedDragDistance -= f`，再 `RecyclerView.scrollBy(round(requested - actual), 0)`，**正数 scrollBy 才是下一页**。旧代码传 `lastX - ev.x`（手指左移为正）→ 实际 `scrollBy` 拿到负值：第 0 页没有更左的页可滚，被 clamp 成 0（表现为「怎么拖页面都不动，松手才跳页」）；在详情/评论等中间页则往反方向滚（表现为「方向和手指相反」）。
- **根因（偶尔翻两页）**：`endFakeDrag()` 的吸附是异步的，旧代码紧接着读 `currentItem` 仍是按下时的页，于是又补一次 `handleSwipe`，与 pager 自己的吸附叠加成 +2。
- **修复**：
  - `SwipePageLayout` 的 MOVE 改为 `fakeDragBy(ev.x - lastX)`（源码注释写明别照抄常见手写公式）。
  - 松手后改 `pager.postDelayed(150ms)` 再判断「pager 是否还停在按下时的页」，只有确实没吸附过去才补一次 `onSwipe`；`SNAP_CHECK_DELAY_MS = 150L`。
  - 第 0 页右滑退出的判据从 `dx > 0 && startItem == 0 && current == 0` 简化为 `direction > 0 && startItem == 0`（不再依赖异步的 `currentItem`）。
- **长按改 3 倍速**：`GestureDetector.onLongPress` → `PageHolder.startSpeedBoost()`；`ACTION_UP` / `ACTION_CANCEL` / `ACTION_POINTER_DOWN` → `endSpeedBoost()`。只动播放器倍速与底部倍速按钮文案，`currentSpeed` 保持用户选的档位；提示用 `fragment_short_video_page.xml` 新增的 `text_speed_boost`（`layout_gravity="top|center_horizontal"` + `marginTop=32dp`，**贴在顶栏下沿而不是画面中央**——放中间会挡视频，用户明确反馈过），文案新增 `R.string.text_shortvideoplayer_speed_boost`。`openVideoDetail()` 与其 `VideoInfoActivity` / `Intent` import 一并删除（详情页已作为横向第 2 页存在）。教程 `Tutorials.kt` `version` 3→4、长按条目改写；`res/xml/tutorial_short_video.xml` 同步；`docs/FEATURES.md` §2.7 长按条目改写。
- **真机验证（`emulator-5554`，release 覆盖安装保留登录态）**：
  - 拖动中截图：短视频页左移、详情页从右侧进入 —— **跟手成立**；
  - 短视频页左滑 → 详情（page 1）→ 评论（page 2）→ 相关推荐（page 3），一次一页；末页再左滑不前进；
  - 详情页右滑 → 回短视频页（方向与手指一致）；短视频页右滑 → 退出本页；
  - 单击 / 纵向滑动不再误跳 `VideoInfoActivity`。
- **长按 3 倍速验证（`emulator-5554`）**：`input swipe 720 1200 720 1200 2500` 按住 2.5s，按住期间截图显示「3.0x 倍速播放中」贴在顶栏下沿、不遮画面（V4 版曾放正中央，按用户反馈移到这里），松手后提示消失、底部倍速按钮回到 `1.0x`；教程 v4 弹出且长按条目已是「按住时 3 倍速播放，松手恢复正常倍速」。
- **未验证**：手表窄屏（300x300）下的 18% 阈值、内容列宽与长按手势；`testCoexist` 包未装机。
- **已知现象（非 bug）**：横滑动画进行中截图会抓到两页叠加/视频帧残留（TextureView 与 ViewPager2 动画的合成帧），动画结束后显示正常。

### 3.50 短视频横滑「一卡一卡」：拖动增量改成按帧合并提交（26.10.21，真机手感未复验）

- **反馈（用户原话）**："短视频的视频详情界面滑起来没有那么丝滑，一卡一卡的"。
- **本机测不出**：MuMu（`emulator-5554`）上对详情页做 6 次纵向滚动、4 组左右横滑，`dumpsys gfxinfo` 与 `logcat -s Choreographer` 都是 0 janky / 0 skipped frames，`50th/90th/95th` 全是 5ms——x86 模拟器跑不出用户手机上的掉帧，所以按「高采样率触摸 + 每事件一次布局」这条已知的卡顿来源做结构性优化。
- **根因（推断，非实测）**：现代屏幕触摸采样 120~240Hz，比帧率高一倍以上。原来每个 `MotionEvent.ACTION_MOVE` 都直接 `fakeDragBy` → `RecyclerView.scrollBy`，一帧里可能滚 2~4 次，每次都触发两侧页面量算/绘制；弱机于是表现成一格一格地动。
- **修复**：`SwipePageLayout` 增加 `pendingDrag` 累加器与 `ViewCompat.postOnAnimation` 的帧回调，MOVE 只累加、每帧统一 `fakeDragBy` 一次（`queueDrag()`/`flushPendingDrag()`）；松手、`ACTION_CANCEL`、`onDetachedFromWindow` 前调 `flushDragNow()` 立即提交残余量，避免最后几像素被吞。顺带确认 `ViewPager2` 没有 `setItemAnimator`（反编译 `viewpager2-1.1.0` 无该 API），所以没有从动画器这条线动。
- **验证**：`assembleDebug` + 两 flavor 单测各 71 类 / 759 例全绿；真机上慢速拖动仍跟手、四页翻页与退出正常。**手感是否变顺滑需要用户在自己手机上复验**（本机无法复现）。

### 3.51 弹幕突然消失 / 拖进度条后闪一下就没了（26.10.21）

- **现象（用户描述）**：有时看到某处弹幕突然整屏消失；拖进度条或快进快退后弹幕消失；消失后偶尔又出现零点几秒再消失。
- **根因一（清屏机制，代码级确证）**：`DrawHandler.SEEK_POS` → `DrawTask.seek()` → **`reset()`**（`danmakus = new Danmakus()` + `mRenderer.clear()`），屏上弹幕被清空，再由 `CacheManagingDrawTask.CacheHandler.SEEK` 的 `prepareCaches(true)` 重建缓存（零点几秒后才回来）。也就是说 **`seekTo` 天生就是"清屏 + 重建"**。而 `PlayerActivity.progressChange` 里原来的纠偏是「偏差 > 400ms 且距上次 > 1s 就 seekTo」——只要漂移持续（缓冲/卡顿时弹幕时钟按墙钟走、播放位置停住），就**每秒清一次屏**，正好是「闪现零点几秒又消失」。
- **根因二（跳转没更新判据，真 bug）**：拖进度条（`onStopTrackingTouch`）与快进后退键（`seekToPosition`）只调 `ijkPlayer.seekTo` + `mDanmakuView.seekTo`，**没有更新 `video_now`**。下一次采样用旧位置比新时钟，算出几百秒"偏差"，纠偏立刻把弹幕视图 seek 回**旧位置** → 用户看到"拖完弹幕就没了"。
- **根因三（分段空洞，解释"不拖动也会消失"）**：`DanmakuApi.getAllVideoDanmaku` 并发拉 6 分钟一段的弹幕，任一段失败**不重试且完全不报**，那 6 分钟就是零弹幕（不是"少几条"）；`segmentCount = duration/360 + 1` 在时长恰好整除时还会多发一次空请求；`duration` 未就绪时回落 3600 秒 → 每次都按 11 段请求。
- **修法**：
  - `player/DanmakuSync.kt` 判据改为 `decide()`：阈值 **1.5s**（`SNAP_THRESHOLD_MS`）、吸附后冷静 **1.5s**（`SETTLE_MS`）、连续 3 次不收敛退避 **30s**（`BACKOFF_MS`）、收敛后计数清零；`posMs < 0` 仍不吸附。
  - `PlayerActivity`：`seekToVideo()` 成为拖进度条/快进后退的**唯一入口**，内部 `markSeekTarget()` 同步 `video_now`、强制刷新进度（`video_now_last = -1`）、清零纠偏计数、给冷静期；重播路径也调 `markSeekTarget(0)`。`ShortVideoPlayerActivity` 的同一处判据同步改 `decide()`。
  - `api/DanmakuApi.java`：新增纯函数 `segmentCountFor(sec)`（`ceil`，≤0 视为 1 段）与 `SEGMENT_SECONDS`；每个分段失败重试 1 次（`fetchSegmentWithRetry`）；拿不满时**按区间打缺口日志**（"分段 3~5，720~1080 秒无弹幕"），事后能区分"数据缺失"和"渲染问题"。
- **单测**：`DanmakuSyncTest` 重写为 9 例（稳态不吸附、小偏差反复采样也不吸附、大跳吸附、冷静期挡住第二次、连续 3 次退避、退避期满恢复、收敛清零、位置不可信不吸附、阈值远大于采样间隔）；新增 `DanmakuSegmentCountTest` 6 例（未知时长 1 段、刚满一段仍是 1 段、整倍数不多发、361 秒必须 2 段、2 小时 20 段、`SEGMENT_SECONDS=360`）。
- **验证（本机构建/单测，26.10.21；数字为合并上游后的实测）**：`:app:assembleDebug`（两个 flavor 各 4 个 ABI 包，`BUILD SUCCESSFUL in 6m 3s`）；`:app:testStandardDebugUnitTest` + `:app:testCoexistDebugUnitTest` 各 **72 suites / 769 tests / 0 failures**。
- **真机/模拟器实测（26.10.22，MuMu Android 15 x86_64，coexist debug 包）**：
  - 视频 `av170001` P1（3:19 / 1754 条弹幕）：正常渲染；**连续 3 次快进**后截图仍满屏弹幕，`弹幕纠偏` 只有跳转 11s 后的一条正常吸附，**没有出现"几百秒偏差"的误吸附**（`markSeekTarget` 生效）。
  - **拖动进度条**到 02:16 后弹幕正常（截图确认），30 秒稳态观察 `弹幕纠偏` 仅 **2 次**（旧逻辑在同样漂移下是每秒一次 = 每秒清屏一次）。
  - **长视频** `av117466583799078`（44:41 / 4947 条）：按 10 段请求，**8 段有数据、9~10 段为空**（该视频只到 44:41，正好覆盖到第 8 段），与预期一致。
  - 顺带修掉一处自测才暴露的问题：`弹幕缺口` 日志原先把"成功但为空"也算缺口，短 P 会天天刷 `分段 2~10（360~3600 秒无弹幕）`这种误报；现在只在**重试后仍为 null**（真失败）时打 E 级日志，空段只体现在"X/Y 段非空"里。
  - 顺带确认 `segmentCountFor(3600) = 10`（旧公式 `3600/360+1 = 11`，白发一个必空请求）。
- **仍未做（另立项）**：`duration` 未就绪时按 3600 秒硬算 10 段（短 P 会白拉 9 段）——正确做法是先只取第 1 段、`onPrepared` 拿到真实时长后再补拉剩余分段（涉及二次加载时序，风险比本批大）。
- **本节已被 §3.57 取代**：上面那套「周期性 `seekTo` 吸附」判据（`DanmakuSync.decide`）已于 26.10.22 **整体删除**。用户反馈「每隔一会自动纠偏本身就是不正常的行为」，根因是弹幕时钟按墙钟走导致速率性发散（见 §3.57），改为以播放器位置为时间源后不再需要任何周期性吸附。本节保留的是当时的排查过程与清屏机制的代码级结论，仍然有效。

### 3.52 存储兼容：默认目录按 API 分档 + 无全盘权限时自动降级（Wear OS，26.10.22，待真机）

- **背景**：§3.28 只把「判据」从权限查询改成试写，试写失败之后仍然是 `finish()` / `return` —— 手表（Wear OS）上「所有文件访问」的接口存在，但系统 UI 不提供这个开关，用户授不了权，于是缓存/下载整条链路走不通。用户要求同时适配高低版本 Android 与这类无法授权的设备。
- **根因（能力假设错）**：默认目录 `Android/media/<包名>/` 在 API 29+ 属公共媒体目录，写它需要 `MANAGE_EXTERNAL_STORAGE`；API 30+ 上只有用户在系统 UI 里勾选才生效，`isExternalStorageManager()` 查得再准、权限在清单里声明得再全都救不了。而下载链路把「能写这个目录」当成了功能前提。
- **改法（`util/FileUtil.java` + 新增 `util/StoragePathPolicy.kt`）**：
  - `util/StoragePathPolicy.kt`（纯逻辑、无 Android API，可 JVM 单测）：`defaultVideoDir(apiLevel, mediaDir, appExternalDir, internalDir)` —— API≥21 用 `Android/media/<包名>/`，API 19/20 用 `getExternalFilesDir("video")`（应用专属、免权限），都拿不到再退 `filesDir/video`；`chooseVideoDir(...)` 保留老用户目录；`hasRealContent(names)` 把 `.nomedia` 这类隐藏标记排除在「有内容」之外。
  - `FileUtil.getVideoDownloadPath()` 拆成「正式目录 `getConfiguredVideoDownloadPath()`」与「降级目录 `getFallbackVideoDownloadPath()`（`getExternalFilesDir("video")` → `filesDir/video`）」，进程级 `videoFallbackActive` 决定返回哪个 —— 下载服务、缓存列表、元数据必须看到同一个根目录，不能各算各的。
  - `resolveVideoDownloadDir(Context)` / `resolvePictureDownloadDir(Context, File)`：正式目录可写就用正式目录（若此前降级过，`mergeMoveDirectoryContents` 把降级目录里的东西搬回，只增不覆盖、同名改成 `name (1).ext`、跳过 `.nomedia`、绝不删目标已有内容）；正式目录不可写但降级目录可写就降级并 toast 实际位置（每个进程只提示一次）；两个都写不了才返回 null。
  - **下载链路不再主动跳系统设置页**（手表上那是死路），`requestStoragePermission` 只保留给设置页里的手动入口。所有入口统一先 resolve：`LocalListActivity`（含 `onResume` 复检，降级状态变过就刷新列表）、`VideoInfoFragment.downloadClick`、`DownloadActivity.onCreate`（图片走 `resolvePictureDownloadDir`）、`VideoQuickCache.handle`（列表长按缓存，以前完全不判可写）、`DownloadService.startSingle` / `startDownload` / `onStartCommand`（服务可能在新进程恢复，标记为空，必须再 resolve 一次）。
  - 设置页「缓存路径」预填 `getConfiguredVideoDownloadPath()`（降级时不会把降级目录写进设置项），新增「去系统设置授予所有文件访问」入口与说明（手表会提示打不开，下载自动降级）。
  - 图片降级保存到应用专属目录时，完成提示改用 `msg_picture_saved_fallback` 明说「不在系统相册中」，且不再调 `PictureMediaStore.scan`（降级目录扫了也不会有 MediaStore 记录，白扫）。
- **为什么不用其它方案**：SAF `ACTION_OPEN_DOCUMENT_TREE` 在手表上通常没有 `DocumentsUI`；`MediaStore` 插入只能管媒体单文件，而缓存目录还要放 `.quality` / `.DOWNLOADING` / 弹幕等附属文件；回退 `targetSdk 29` + `requestLegacyExternalStorage` 是能力倒退，且本仓 `targetSdk 34` 下已失效。结论：把「全盘权限」从**必需**变成**可选**。
- **单测**：新增 `StoragePathPolicyTest` 12 例（API 分档、配置优先、老目录保留、隐藏文件不算内容）、`FileUtilMergeMoveTest` 5 例（搬移、同名不覆盖、递归合并、跳过 `.nomedia`、空输入安全）。判据与理由已同步 `docs/architecture-map.md` §7.41 ④。
- **验证（本机，已跑）**：`$env:JAVA_HOME='C:\Program Files\Java\jdk-17'; ./gradlew.bat :app:assembleDebug :app:testStandardDebugUnitTest :app:testCoexistDebugUnitTest --offline --no-configuration-cache` → `BUILD SUCCESSFUL in 1m 49s`；两 flavor 各 **75 个测试类 / 800 例 / 0 失败 0 错误 0 跳过**。
- **真机待验（全部未验证）**：① 手表 Android 7/8/9/11 首次进缓存页 / 点下载直接成功，不弹系统设置页；② 把缓存路径改成公共 `/sdcard/Download/xxx`（无权限）后下载**自动降级**并提示实际位置，文件落在应用专属目录、缓存页能列出；③ PC 的 MTP / 文件管理器能看到该目录（`Android/media` 比 `Android/data` 可见性略好，但都比公共目录差，需在文档/教程里说清）；④ 图片降级保存提示「不在相册中」；⑤ 手机上授权后回到缓存页自动切回正式目录，降级期间下的缓存被搬回。

### 3.53 缓存视频断点续播：内置播放器播本地/缓存视频也从上次位置起（26.10.22，待真机）

- **需求**：内置播放器播放**下载的缓存视频**时也要「从历史位置播放」。此前只有在线视频（含番剧）能续播——位置来自服务端观看历史；本地视频入口构造 `PlayerData(TYPE_LOCAL)` 时从不填 `progress`，`onPrepared` 的 `resumeTarget` 恒为 0，所以永远从头。用户确认口径：进度存 `.video_meta.json`、复用现有「从历史位置播放」开关（不新增）、多 P 按分 P 分别记、纯本地记录、四个入口全要（缓存列表单文件/DASH、多P 选择页、虚拟合集连播、听视频/纯音频）。
- **为什么入口一行都不用改**：四个入口传的 `video_url` 都指向该视频（或该分 P）自己的缓存目录，`File(video_url).absoluteFile.parentFile` 就是 `.video_meta.json` 所在目录。播放器在 `getExtras()` 里反推目录、在 `doSwitchPage()` 里跟着切 P 换目录，入口侧无需传新 extra。
- **数据层**：
  - `model/VideoMeta.java` 新增 `progressMs`（0=从头/已看完）与 `durationMs`（0=未知；存它是为了判断"停在距结尾几秒"），`toJson`/`fromJson` 读写，老 meta 缺键时 `optLong` 兜 0。
  - 新增 `util/LocalVideoProgress.kt`（纯函数、无 Context/文件，可 JVM 单测）：读与写用**同一条判定**，避免"存得进去读不出来"——`<=5s` 无意义、`时长-位置<=5s` 视为看完、位置超过时长视为看完；时长未知（<=0）只按位置判断。
  - `util/VideoMetaManager.kt`：新增 `readResumeMs(folder)` 与 `@Synchronized saveProgress(folder, positionMs, durationMs)`；`saveMeta` 也加 `@Synchronized`。`saveProgress` **先读旧 meta 再合并**（直接 new 一份会冲掉 title/画质列表）。
  - `service/download/DownloadRepository.kt:saveVideoMeta` 原来是 new 一份 `VideoMeta()` 整份覆盖 —— 这正是重下载/换清晰度会冲掉进度的坑，改成先 `readMeta` 合并。
- **播放器侧（`activity/player/PlayerActivity.kt`）**：`getExtras` 里对非 http 的 url 设 `localMediaDir` 并用 `readResumeMs` 补 `progress_history`（单位毫秒，与在线一致）；`doSwitchPage` 切 P 时重算目录并清去重标记；新增 `maybeSaveLocalProgress()`（挂在既有 250ms 定时器上，墙钟 15s 节流）+ `saveLocalProgressNow(reason)`（**主线程同步写**：异步会让"退出补写"被队列里的周期写覆盖）；落盘点＝周期/`onPause`/`onStop`/`onDestroy`/`finish`/`onCompletion`（看完清零）；`onCompletion` 后单曲循环重新开始时清 `finishWatching`，否则第二轮会一直按"看完"写 0。续播本身复用 `onPrepared` 既有那段（`restoredFromSaveState` → `player_from_last` 开关 → 0），未新增开关。
- **已知取舍**：播放器内手动/自动切 P 从 0 起播（沿用 `progress_history = 0` 的既有行为）；位置在首次 prepare 之前不会落盘（防"刚进页面就退出"把旧进度清掉）。
- **单测**：新增 `LocalVideoProgressTest`（14 例：阈值边界、结尾容差、超时长、时长未知、读写一致性循环）与 `VideoMetaProgressTest`（6 例：老格式 meta 读回 0、位置往返、落盘不冲掉 title/aid/cid/画质列表、看完清零、时长未知可续播、目录不存在不抛）。`org.json` 在单测里是真实现（`app/build.gradle` 有 `testImplementation 'org.json:json'`），所以 `VideoMeta` JSON 与文件读写都能在纯 JVM 里测。
- **验证（本机，已跑）**：`.\gradlew.bat :app:assembleDebug --offline` → `BUILD SUCCESSFUL`（121 tasks，1 分内）；`.\gradlew.bat :app:testStandardDebugUnitTest :app:testCoexistDebugUnitTest --offline` → `BUILD SUCCESSFUL`，两 flavor 各 **76 类 / 804 例 / 0 失败 0 错误**（其中新增 `LocalVideoProgressTest` 12 例、`VideoMetaProgressTest` 6 例）。
- **真机待验**：播到 01:30 退出再进应从约 01:30 起播且提示「已从上次的位置播放」（±2s）；关掉「从历史位置播放」则从头且不提示；看到距结尾 5 秒内/播完 → 下次从头；多 P 不串位置（A 看到一半、B 从头）；单文件 / DASH / 听视频 / 虚拟合集 / 多 P 选择页均生效；缓存文件夹改名或移动后仍有效；视频被删/文件损坏/时长变短时静默从头不崩。

### 3.54 播放页禁止系统右滑返回（无条件，26.10.22，待真机）

- **需求**（用户确认口径）：长视频播放页**禁止通过右滑手势退出应用**，且**不设开关**、页内处处生效；用户明确补充「不要屏蔽其他手势（如滑动调节进度和移动画面等）」。范围只到 `PlayerActivity`（投稿 / 番剧 / 直播 / 缓存视频都走它）；短视频页 `ShortVideoPlayerActivity` 的「右滑退出」是产品定义的另一种语义，不动。反馈方式为静默忽略（不弹提示）。
- **根因（与 issue #1 同源）**：手表右滑返回由窗口属性 `android:windowSwipeToDismiss` 实现，**窗口层直接 `finish()`，不走 `onBackPressed`**，应用收不到。改前 `PlayerActivity.onCreate` 是 `val noSwipe = swipeSeekEnabled || SharedPreferencesUtil.getBoolean(SettingsKeys.BACK_DISABLE, false)`，即**两个开关都关着时右滑照常退出**——这正是用户看到的现象。
- **改法（一处）**：`activity/player/PlayerActivity.kt` `onCreate` 删掉那个复合判断，改为无条件 `setTheme(ColorScheme.themeResId(theme, noSwipe = true))`；`swipeSeekEnabled` 的赋值保留（`onBackPressed` 仍用它判断手势型返回键）。不新增设置项、不改基类、不动 `res/`。
- **为何不碰其他手势**：`*.NoSwipe.AppCompat` 相对主主题**只多一条 `windowSwipeToDismiss=false`**（`ColorScheme.kt:526-557` 的 7 套映射 + `res/values/themes.xml`），配色 / 圆角 / 字体全同；它只改窗口属性，**不参与触摸分发**，滑动调进度、双击、长按倍速、捏合缩放与拖动画面仍由本类自己的手势逻辑处理。
- **行为边界**：左滑 / 右滑调进度（`PLAYER_SWIPE_SEEK`）开启时 `onBackPressed` 里 `if (swipeSeekEnabled) return` 的既有兜底**保留未改**（issue #1 对「把右滑模拟成返回键」的手表）；未开启时返回键照常退出播放页。退出路径仍是顶栏返回按钮 + 返回键。
- **手表端复核（26.10.22 二次审查；用户提示「这个主要是针对手表端的右滑返回设计的」）**：
  - **覆盖的正是手表那条链路**：关闭的就是手表系统「窗口层 swipe-to-dismiss」(`android:windowSwipeToDismiss`)，即 issue #1 实测机型的机制（窗口层直接 `finish()`、应用收不到）。这同时兑现了 `docs/superpowers/specs/2026-10-20-player-touch-lock.md` §4.4 留的预案——「若右滑拦截有副作用，退路是播放页无条件 `noSwipe = true`」。
  - **映射无遗漏**：`ColorScheme.noSwipeThemeResId`（`ui/appearance/ColorScheme.kt:550-558`）对 6 套具名主题 + 默认主题都有 `*.NoSwipe.AppCompat`，没有回落到主主题的分支；`res/values/themes.xml` 里每套 `NoSwipe` 相对主主题只多 `windowSwipeToDismiss=false`，配色 / 圆角 / 字体一致。
  - **无子窗口干扰**：`PlayerActivity` 全文件没有 `Dialog` / `PopupWindow` / `WindowManager.addView`（弹幕、控制栏、互动选项都是 Activity 内的普通 View），不存在「某个弹窗窗口自己还开着右滑」的情况；视频画面是 `layout_video.addView(surfaceView/textureView)`，不是独立 Window。
  - **手机端无副作用**：该窗口属性只有手表系统消费，手机上换 `NoSwipe` 主题的效果与主主题完全相同。
  - **已知不覆盖的两类（用户 26.10.22 拍板：保持现状）**：① 用「模拟按下返回键」实现右滑的手表（应用自己的设置文案点名米兔等，见 `docs/copy-inventory/04-settings.md` 的「禁用返回键」说明）——到应用侧就是一个 `KEYCODE_BACK`，与物理返回键不可区分，窗口属性对它无效，播放页在此类手表上右滑仍会退出（要拦只能连返回键一起拦，违背「只禁右滑手势」的口径）；② 手机 / 平板的系统返回手势不读该窗口属性，仍可退出播放页（要拦需 API 29+ 的 `setSystemGestureExclusionRects`，仓库未用、无真机可验）。
  - **锁定态反馈变化（轻微）**：触摸锁定期间右滑不再触发「开锁图标浮现」——事件在窗口层就被丢掉了，到不了 `finish()` / `onBackPressed`。锁定的语义不变（仍出不去），只是少了一次视觉反馈。
  - **真机判断方法**：若用户那台表实测「右滑仍退出」，就说明它属于上面第 ① 类（模拟返回键），此时开「左右滑动控制进度」或「禁用返回键」都能拦住，但这属于既有开关，不是本功能的缺陷。
- **验证（本机，26.10.22 已跑）**：`.\gradlew.bat :app:assembleDebug :app:testStandardDebugUnitTest --offline` → `BUILD SUCCESSFUL in 56s`（两 flavor 都出 Debug 包，128 tasks）；单测按 `app/build/test-results/testStandardDebugUnitTest/*.xml` 实测 **76 类 / 804 例 / 0 失败 0 错误 0 跳过**，与改动前基线一致——本次只换窗口主题属性，没有可 JVM 单测的纯逻辑，故未新增用例（`noSwipe` 是 `ColorScheme.themeResId` 的既有参数，已被既有主题映射覆盖）。
- **真机待验**：① 手表上「左右滑动控制进度」与「禁用返回键」都关着时，播放页右滑不再退出；② 开启「左右滑动控制进度」后右滑仍是调进度、不退出；③ 双击 / 长按倍速 / 捏合缩放 / 拖动画面全部照常；④ 顶栏返回按钮与返回键仍能退出；⑤ 主题配色与改动前一致；⑥ 短视频页右滑退出行为不变（本次未改，回归确认）。

### 3.55 自动跳过片头片尾「乱跳」：三处判定互相污染（26.10.22，待真机）

- **现象**（用户报告）：「有乱跳的问题 / 问题好像是判断循环没有及时退出导致的」。实际表现是开播后进度自己往前跳、跳过的位置不对、偶尔跳完又弹回原处。
- **澄清**：`maybeAutoSkipOpEd()` 里**没有任何循环**，不存在「判断循环没及时退出」。真正的驱动是既有 250ms 主线程定时器（`progressChange` → `maybeAutoSkipOpEd`），它每拍都会重新判定一次，所以任何「已被处理」的标记只要丢了，就会在下一拍立刻重跳。
- **根因三条（互相独立，都会造成乱跳）**：
  1. **跳转入口分不清调用者**：`seekToVideo()` 是拖进度条、按键、滑动、点看点、自动跳过**共用的唯一入口**，原先它无条件调 `onUserSeekTo(position)` 把落点所在片段记为已处理。自动跳过自己跳到区间右端点本来在区间外，但只要与用户操作撞在同一帧/相邻帧，就会被当成「用户想看这段」而把真正该跳的片段吃掉；反过来用户拖动途中的中间帧也可能误标片段。
  2. **重复拉取清空已处理集合**：`loadViewPoints()` 在一次播放里会被调用多次（启动、投屏恢复等），原先**无条件** `skipHandled.clear()`。只要某次响应在用户已越过片头之后才到达，就把「片头已处理」抹掉 → 下一拍定时器重新命中 → 再跳一次，**每多拉一次就多跳一次**。
  3. **异步 seek 回弹**：`seekToVideo()` 先写 `video_now` 再调 `ijkPlayer.seekTo()`，而 IJK 的 seek 是异步的——落地前 `progressChange` 会把 `video_now` 拉回**旧位置**，旧位置若仍在片段内就触发下一轮判定，表现为「跳完又跳回去」。原先这条不变式只靠 `skipHandled` 一条防线，而它恰好会被第 2 条清掉。
- **改法（不改 `ViewPointSkip` 的纯判定，只修接线）**：
  - 新增 `player/SkipCancelPolicy.kt`（纯函数，无 Android 依赖，可 JVM 单测）两个判据：`cancelKeyFor(userInitiated, segmentKey, handled)` 与 `segmentsChanged(old, new)`。
  - `seekToPosition` / `seekToVideo` 新增 `userInitiated: Boolean = true`，由调用点**如实声明**；`maybeAutoSkipOpEd` 传 `false`，其余用户手势/按键/看点列表/撤回按钮都走默认 `true`。`onUserSeekTo` 改走 `SkipCancelPolicy.cancelKeyFor`：非用户发起一律不标记。
  - `loadViewPoints()` 改为只在 `SkipCancelPolicy.segmentsChanged(...)` 为真（片段表真的变了＝换集/换 P）时才清 `skipHandled`；同一批片段（迟到的重复响应、投屏恢复重拉）保留已处理状态。
  - 新增 `SKIP_QUIET_AFTER_SEEK_MS = 600L` 与 `skipQuietUntilMs`：自动跳过跳完起一段静默期（`markSeekTarget` 经 `pendingSeekIsAutoSkip` 触发），盖住「新位置已写、IJK seek 未落地」的窗口。**只由自动跳过起**——用户主动拖动的落点已被正式记为已处理，不需要时间兜底，也不该因为拖一下就压掉 600ms 的自动跳过。
- **为什么不给自动跳过用更短的静默期**：定时器周期本身就是 250ms，静默期短于它拦不住回弹；而片头后紧跟片尾时，任何长于两段间隔的静默期都会把片尾吃掉。600ms（约 2 拍）是「拦得住一次 seek 往返」与「不越过相邻片段」之间的取值。
- **单测**：新增 `SkipCancelPolicyTest`（11 例：用户主动跳进片段算反悔 / 自动跳过自己不算 / 落点不在片段内不标记 / 已处理不重复标记 / 首次加载算变化 / 重复拉到相同片段不算变化 / 内容变了算变化 / 数量相同区间不同算变化 / 换集算变化 / 两批都空不算变化 / 有内容变空算变化）。
- **验证（本机，26.10.22 已跑）**：`.\gradlew.bat :app:assembleDebug --offline --no-configuration-cache` → `BUILD SUCCESSFUL`（两 flavor 都出包）；`testStandardDebugUnitTest` 与 `testCoexistDebugUnitTest` → 各 **817 例 / 0 失败 0 错误**（改动前基线 806 例，新增 11 例）。改动只涉及播放器接线与纯判定，无新增用户可见文案。
- **真机待验**：① 有片头片尾的视频开播后只跳一次，不再出现「十几秒后又跳」；② 番剧切集/切 P 后新一集仍能正常跳（不应因保留 handled 而漏跳）；③ 用户主动拖进片头后不被弹走；④ 连续两段（片头紧邻片尾）时片尾仍跳得到；⑤ 投屏/后台恢复后不重复跳。

### 3.56 公告单源：上游公告改由服务端逐条审核透传 + 正文页标注来源（26.10.23，生产已部署 / 待真机）

- **需求**（用户）：「从应用端移除上游路的公告」，把上游公告接口接到自建服务器，**由维护者亲自逐条选择是否透传**；透传的公告**只在正文页顶部标注一行来源**（列表卡片不加徽标）；透传公告**参与启动差量弹窗**。来源文案定为「此公告来自原版哔哩终端」。
- **客户端改法（两路合并整体拆除，单源化）**：
  - `api/AppInfoApi.java`：删掉 `checkAnnouncement()` / `getAnnouncementList()` 两个方法、`check()` 里的调用、`Announcement` import；`check()` 不再发公告请求，只留更新包清理 / debug 警告（原先那条 `catch (IOException)` 随之失去可抛出点而删除，`uploadStack` 的 catch 仍需要 import）。
  - `api/TerminalApi.java`：删 `mergeAnnouncements` / `loadMergedAnnouncements`，`parseAnnouncements` 增加 `source`（`optString("source","local")`）；`checkAnnouncement()` 改为单一源差量、`MsgUtil.showText(title, content, isRelayed())`；反馈的 `feedbackHeaders` 顺带修掉一处行合并。
  - `model/Announcement.java` 加 `source` 与 `isRelayed()`；`MsgUtil.showText(title,text,relayed)` 新重载传 `relay_source` extra；`activity/ShowTextActivity.kt` 据 extra 显隐顶部来源行（`res/layout/activity_simple_text.xml` 新 `TextView relaySource`）；`adapter/AnnouncementAdapter.kt` 点击时透传 `isRelayed()`。
  - `activity/settings/AnnouncementsActivity.kt`：只调 `getOwnAnnouncementList()`，空态/失败态走 `showEmptyView` + `MsgUtil.err`，删掉「部分公告源连不上」提示（`strings.xml` 该串换成 `text_announcement_relay_source`）。
  - `SettingsKeys.TERMINAL_ANNOUNCEMENT_LAST` 注释更新：本地公告与透传公告**共用这一个游标**。
- **服务端（`server/`，26.10.23 由 teammate 实现，`SCHEMA_VERSION=4`）**：`announcements` 加 `source`/`origin_id`/`relay_state` + 部分唯一索引、新增 `meta` 表存 `relay_last_pull`；上游拉取用标准库 `urllib.request`（`UPSTREAM_ANN_URL` 可被环境变量覆盖、超时 10s、`run_in_threadpool`）；管理接口 `GET /admin/api/announcement/relay`、`POST …/relay/refresh`（失败回 HTTP 200 + `{code:1,msg}`）、`POST …/relay/decision`（approve / reject，reject 可撤销已透传项，refresh 不复活 rejected）；`_VISIBLE_SQL` 只放 `relay_state='approved'`；控制台「公告」页签新增「上游透传（原版哔哩终端）」区块（待审 / 已透传 / 已拒绝 + 手动拉取 + `last_pull` 超 300s 自动补拉一次）；`selftest.py` 新增 §22（8 小节）。
- **契约**：客户端 `get_list` 每行多 `"source": "local"|"relay"`；id 仍是本地自增 + `1_000_000_000`（两类公告共用 id 空间，relay 另存 `origin_id` 去重）；`local` 行行为与排序/差量语义**完全不变**；pending / rejected 对客户端与 `from` 差量都不可见。
- **部署（HK 生产，26.10.23 21:02 完成）**：生产 `/opt/rebiliterminal-api/` 的三件套原为 **26.10.17 基线**（比本目录工作副本新：含 `/admin?token=` 303 → HttpOnly 会话 Cookie、控制台 UI 打磨、selftest §5 旧口令用例），直接用工作副本覆盖会回退这些改动，因此把 relay 改动**合并到生产基线**（产物 diff：`rebiliterminal_api.py` +336/−9、`console.html` +129/−4、`selftest.py` +250/−0），本机用合并产物跑 `selftest.py` 187 项全通过后才上传。
  - 备份：`./backup.sh` → `backups/data-20261023.db`（7 张表）；旧文件另存 `.bak-20261023-210201-beforerelay`。
  - 上传后 `systemctl restart rebiliterminal-api` → active，`/terminal/health` `code=0`；`PRAGMA user_version` 3→4，`announcements` 已加 `source/origin_id/relay_state` 三列、`meta` 表已建。
  - 上线冒烟：公网 `get_list` 正常（local 行带 `"source":"local"`）；未登录 `GET /admin/api/announcement/relay` 与 `POST …/relay/decision` 均 **403**；`https://console.zsapp.asia/` 200 且含 relay 区块（`CONSOLE_VERSION = "v4 2026-10-23"`）；带 token 真拉上游 → `added=3 total=3`，relay 列表 `pending=3 approved=0 rejected=0`；`from=1000000000` 差量返回 4 行且 `relay=0`（待审不泄漏）；本地公告管理接口 41 行且仅 local。
  - 部署后本目录 `server/` 三件套已同步为合并产物（106452 / 83457 / 45849 bytes）。
- **验证（本机，26.10.23 已跑）**：
  - `.\gradlew.bat :app:assembleDebug --offline` → `BUILD SUCCESSFUL`（standard + coexist 两个 Debug 包）。
  - `:app:testStandardDebugUnitTest` 与 `:app:testCoexistDebugUnitTest` → 两 flavor 各 **815 例 / 0 失败 0 跳过**（`TerminalApiTest` 现 17 例：删掉 3 例「两路合并」并改为断言 `source`）。
  - `server/`：`python -m py_compile` OK；`python server/selftest.py` → **187 项全通过**（含真上游冒烟；离线时 185 项）；本地 uvicorn 真服务 E2E 验证 approve 后客户端立即可见 `source=relay`、上游不可达时 `code=1`、登录后控制台含 relay 区块；`console.html` 的 script 块 `node --check` 通过。
  - markdownlint：本次改动的 5 个 md 0 错误。
- **未验证 / 待办**：① 生产上 3 条待审上游公告**未做 approve/reject 实操**（避免真实客户端看到未定内容；按用户要求「亲自选择是否透传」留给维护者在控制台点）；② 控制台按钮**没在真实浏览器点过**；③ 客户端未真机验证（列表只剩自建源、透传标注、待审不可见、差量弹窗只弹一次、断网不卡启动）；④ 上游长时间不可达时控制台每次打开页签会重试一次（无退避，属取舍）。
- **注意**：`server/` 整个目录在 `.gitignore` 里（既有约定，服务端代码不进版本库），所以本次服务端改动只存在于工作区与 HK 部署目录，不随客户端提交。

### 3.57 弹幕时间轴改由播放器位置驱动（彻底去掉周期性纠偏，26.10.22）

- **用户反馈**：「弹幕每隔一会自动纠偏本就是一个不正常的行为，按理说应该全程跟随视频进度；其次在快进时这个纠偏更为严重。」并要求核实「弹幕的滚动速度计算是否有问题，导致弹幕与视频差距越来越大」。
- **结论：确实是速率问题，不是阈值问题。** §3.51 的 `decide()` 只是把「清屏」限了频，没有消除漂移本身——根因在 DFM 内部：`DrawHandler.syncTimer()` 把 `SystemClock.uptimeMillis() - mTimeBase` 当成媒体时间，只有「视频恰好按 1.0x 实时播放」时才成立。
- **实测取证（MuMu Android 15 x86_64，1.0x，临时诊断日志 1 秒一次）**：

  ```text
  视频=3743  弹幕=4249  差=-506      ← 弹幕跑快
  视频=7754  弹幕=8722  差=-968
  视频=11773 弹幕=13217 差=-1444
  视频=15789 弹幕=15787 差=2         ← 触发一次吸附（= 清屏重建）
  ```

  视频每 4 秒只前进 3981ms（≈0.9x 墙钟，解码跟不上），弹幕每 4 秒前进 4478ms（≈1.0x 墙钟）——**速率差约 12.5%，差距线性增长**，所以「每隔十来秒必清一次屏」。倍速下同理：2.0x 时墙钟每 3 秒只该走 6 秒的一半，偏差扩大得更快，与用户「快进时更严重」的观察一致。
- **为什么不能继续用 `seekTo` 修**：`seekTo` → `DrawTask.seek()` → `reset()` 是「清空屏上弹幕 + 重建缓存」（§3.51 已确证），拿它做纠偏等于周期性清屏。要让「全程跟随」成立，只能换时间源。
- **修法（4 层，`DanmakuFlameMaster` 模块可改）**：
  - 新增纯逻辑类 `DanmakuFlameMaster/.../controller/MediaClockSync.java`：`project(采样位置, 采样时刻, 当前时刻, 倍速)` 在两次采样之间外推（否则弹幕每 250ms 才动一次）；`nextTimerValue(当前, 理想)` 每帧**最多挪 40ms**（把采样抖动摊平，不出现画面回退）；`isDiscontinuity(上次, 本次)` 位置差 >1s 判为跳转/换源，直接硬对齐不走蠕动。
  - `DrawHandler` 新增 `interface MediaClock { long positionMs(); boolean isPlaying(); }` 与 `setMediaClock()`；`syncTimer()` 优先走 `syncTimerByMediaClock()`，位置为负数（未就绪/已销毁）时**回退原生墙钟分支**。
  - `IDanmakuView` / `DanmakuView` 暴露 `setMediaClock()`（转发不判 `isPrepared`，因为绑定通常早于 prepare）。
  - `player/DanmakuManager.kt` 新增 `bindMediaClock(position, isPlaying)` 并在 `configureAndPrepare()` 末尾重放一次；`PlayerActivity.bindDanmakuView()` 绑 `video_now`（就是 `progressChange` 每 250ms 采样的那个 `@Volatile` 字段）与 `isPrepared && isPlaying && !destroyed`；`ShortVideoPlayerActivity` 同样绑定（`videoNow` 已补 `@Volatile`）。**直播不绑**（没有有意义的播放位置）。
  - `PlayerActivity.progressChange` 里整块周期性吸附删除，`DanmakuSync.kt` 及其 9 例单测**删除**；新增 `MediaClockSyncTest`（12 例：外推、倍速、空档封顶、不倒推、限幅蠕动、半秒内收敛、不连续判定、阈值边界）。
- **为什么不用改单条弹幕的滚动时长**：`DanmakuFactory` 的 `scrollSpeedFactor` 决定「一条弹幕在屏上停留多久（以媒体时间为单位）」。倍速下时钟推进 2x，同一条弹幕自然也是 2 倍速划过屏幕——这正是期望行为，弹幕出现时机与画面严格对齐，不需要额外放大滚动速度。
- **线程安全边界（改动中最容易踩的坑）**：`MediaClock` 的两个方法跑在 DFM **渲染线程**上，只能读主线程采样好的内存值；**绝不能**在里调 `IjkMediaPlayer.currentPosition`（取 native 锁，与主线程 seek/release 并发有卡死风险，也是 26.09.10 那版「渲染线程读位置导致弹幕全没」的老坑）。
- **模拟器实测（26.10.22，coexist debug 包；日志为当时的体检输出，现已收敛为「仅异常时、≥5 秒一条」）**：
  - **1.0x 稳态 40 秒**：偏差 `9 / 11 / 25 / 26 / 5 / 15 / 18 / 10 / 13 / 16 / 9` ms，**横盘不增长**（改前同样条件每 4 秒涨 490ms）。
  - **2.0x 播放 20 秒**（视频每 3 秒推进 ≈6020ms）：偏差 `42 / 39 / 34 / 40 / -102 / 26` ms，仍不累积；`-102` 是单帧采样抖动，下一帧即回落。
  - **拖进度条**：从 81s 拖到 128s 后首个采样偏差 **8ms**（`isDiscontinuity` 硬对齐生效），截图确认弹幕立即可见、无"闪一下又没了"。
  - **长时间无周期性清屏**：整段日志再无任何「纠偏/清屏」记录；`弹幕缺口` 无输出；错误日志只有 MuMu 自己的旋转提示，应用侧零异常。
- **验证（本机）**：`:app:assembleCoexistDebug` 通过；`:app:testStandardDebugUnitTest` = **76 类 / 809 例 / 0 失败**（806 − 9 + 12）。**手表端仍待真机**（媒体时钟在弱机上的收敛速度、暂停/恢复的手感）。

### 3.58 短视频页没有阻止自动熄屏（26.10.22）

- **问题**（用户提出「检查短视频部分是否没有做阻止自动熄屏」）：**确实没有**。全仓与亮屏相关的代码只有两处——`res/layout/activity_player.xml` 根布局的 `android:keepScreenOn="true"`（长视频播放页）与 `DownloadListActivity` 的下载期 `FLAG_KEEP_SCREEN_ON`；短视频这套（`activity_short_video_player.xml` + `fragment_short_video_page.xml`）**一处都没有**，看短视频到系统熄屏时间就黑屏。
- **改法**（`activity/video/ShortVideoPlayerActivity.kt` + `ShortVideoFeedFragment.kt`）：
  - `PageHolder.isPlaying` 加自定义 setter，任何一处改播放状态都触发宿主 `syncKeepScreenOn()`——赋值点有 8 处（`play` / `pause` / `resume` / `setActive` / `onPrepared` / 完成回调 / `releasePlayer` / state 收集），逐个补调用必漏。
  - 判据取「当前活跃那一页是否真的在播」（`ShortVideoFeedFragment.isCurrentPlaying()` → `ShortVideoPagerAdapter.isCurrentPlaying()` → `activeHolder.isPlaying()`），**每次整体重算**而不是增量 add/clear：增量写法在 `releasePlayer()` 这类「本来就没在播」的路径上会漏，残留一次就是永久亮屏。
  - `onStop` 兜底清标志、`onStart` 回前台重算，避免后台期间把状态带回来。
- **为什么用窗口标志而不是 View 级 `keepScreenOn`**：先试过在 `PageHolder` 里设 `itemView.keepScreenOn`，模拟器实测**暂停后窗口 `fl` 里仍留着 `KEEP_SCREEN_ON`**（RecyclerView 的 item 挂着，View 级标志的清退不可靠）。换成 `window.addFlags/clearFlags(FLAG_KEEP_SCREEN_ON)` 后一次就对。
- **实测（MuMu Android 15 x86_64，coexist debug，判据是 `dumpsys window windows | grep -c 'fl=KEEP_SCREEN_ON'`）**：播放中 `1` → 暂停后 `0` → 恢复播放 `1` → 上滑切下一条 `1`（新一条自动播）。用户随后在自己环境复验通过。
- **验证（本机）**：`:app:assembleCoexistDebug` 通过；`:app:testStandardDebugUnitTest` = 76 类 / 809 例 / 0 失败（本次只有 UI 状态逻辑，无新增可单测的纯函数）。
- **顺带记一笔**：`mWakefulness` **不能**当判据——MuMu 本身不自动熄屏（桌面对照实验 22 秒后仍 `Awake`），必须看窗口 `fl`。

### 3.59 新增「伪装与安全」：双击顶栏切到假计算器 / 假蓝牙提示（issue #22，26.10.22）

- **需求**（issue #22，用户定的三条口径）：① 两种样式都做、可在设置里选；② 计算器的退出方式是「输入暗号再按 `=`」；③ 做成设置开关、**默认关**；触发方式为**双击顶栏**。
- **新增**：
  - `util/DisguisePrefs.kt`：开关 / 样式（`calculator` / `bluetooth`）/ 暗号（4~8 位数字）与纯函数 `normalizeCode`、`matchesSecret`。暗号**只允许数字**，否则计算器上敲不出来 = 用户把自己锁在伪装页里，只能强杀应用。
  - `util/CalculatorEngine.kt`：真能算的四则运算求值（先乘除后加减、小数、除零报错、末尾多余运算符忽略、**绝不抛异常**）。伪装页算错就等于暴露。
  - `activity/disguise/CalculatorDisguiseActivity`：深灰底 + 橙色运算符的手表计算器（**刻意不继承 BaseActivity**——主题、DPI 适配、教程、EventBus 任何一项都会让伪装露馅）。暗号命中 → `finish()`；**返回键只当 `C`**，不退页。
  - `activity/disguise/BluetoothDisguiseActivity`：半透明窗口（`Theme.BiliClient.Disguise.Dialog`）上盖一张仿系统提示卡，底下的页面会透出来再压一层暗色；「重新连接」只换一行字；**长按标题 2 秒**退出，返回键被吃掉。
  - `activity/disguise/DisguiseLauncher`：按设置选样式的唯一入口。
  - `BaseActivity.setupTopbarDoubleTapDisguise`：顶栏手势层，技术结论见 `docs/architecture-map.md` §7.53。
  - 设置：新分组 `GROUP_DISGUISE`（「伪装与安全」）+ `SettingMainActivity` 一级入口 + `SettingsIndex` 搜索条目；`strings.xml` 全部文案；清单两个 Activity 均 `exported="false"` 并各带伪装主题。
- **单测**：`CalculatorEngineTest` 12 例（空表达式、单数字、加减、乘除优先、小数显示、整除不带 `.0`、末尾运算符、连续运算符、除零、非法输入、`*` `/` 等价、负数）、`DisguisePrefsTest` 7 例（合法/裁空格/长度/非数字/匹配/非法暗号恒不匹配/样式合法性）。合并上游后的全量基线是 **79 类 / 840 例 / 0 失败**。
- **模拟器实测（MuMu Android 15 x86_64，coexist debug，用户本人操作）**：双击顶栏 → `WindowManagerShell` 日志确认 `CalculatorDisguiseActivity` 在 display 22 打开；输入暗号按 `=` → `disguise>暗号命中，退出伪装` 并回到原页面；全程 **0 条 `FATAL EXCEPTION` / `AndroidRuntime` 异常**；`BaseActivity>setupTopbarDoubleTapDisguise: 顶栏双击伪装已就绪` 在每个页面正常打印。
- **手表端待验**：单击顶栏的 0.3 秒延迟在真机手感（尤其列表页"点顶栏返回"）是否可接受；半透明蓝牙提示页在圆屏手表上的布局。

### 3.60 上游审查意见修复（26.10.23）

上游对 PR 提了 1 个 P0 + 7 条 P1/P2。逐条核对后的处理：

- **P0（真回归，§3.57 引入）短视频首播弹幕不显示**：`ShortVideoPlayerActivity` 在 `loadDanmaku()` **之前**调 `danmakuManager?.resume()`，而 `DanmakuManager.resume()` 有 `isPrepared` 守卫，此刻必然 false → 空操作；全页又没有任何 `start()`。DFM 的 UPDATE 循环**只由 START / SEEK_POS / RESUME 三个分支拉起，`prepare()` 本身不会启动**，旧实现是靠周期性 `seekTo` 的副作用（SEEK_POS 落穿到 RESUME）间接启动的。§3.57 删掉周期性 seekTo 时把这个副作用一起删了。
  - 修法：`DanmakuManager` 新增 `start(positionMs = -1)`，并在 `configureAndPrepare()` 末尾自动调用（起播位置优先取媒体时钟的已知位置，续播/切页别从 0 重走）。等价于长视频页原本在 `onPrepared` 里做的 `mDanmakuView.start()`。
  - 实测（模拟器）：进入短视频页**不做任何操作**，弹幕区出现「弹幕准备完毕」+ 多条滚动弹幕（修复前只有烧进画面的字幕）。
- **P1-1 双击顶栏覆盖面与文案不一致**：`PlayerActivity` 不继承 `BaseActivity`（没有手势）；20 个 `asyncInflate` 页面在 `onStart` 时视图还没进来，`findViewById(R.id.top)` 为 null 直接 return（首次进入无效、退出再进才生效）；短视频的顶栏在分页 item 里也装不上。
  - 修法：手势实现抽成 `util/DisguiseGesture.kt`（`install` / `detach` / `isInstalled`，用 `WeakHashMap` 防重复安装）；`BaseActivity` 拿不到顶栏时 `decorView.post` 重试一次；`PlayerActivity.onStart` 与短视频 `PageHolder` 各接一次线。现在「双击任意页面顶栏」与文案一致。
- **P1-2 3× 长按媒体时钟追不上**：`MAX_STEP_MS = 40` 在 60fps 下只能追 2400ms/s，3× 需要 3000ms/s；而 `DISCONTINUITY_MS = 1000` 又大于 3× 的相邻采样差 750ms，永不触发硬对齐。
  - 修法：新增 `stepFor(speed)`（步进上限随倍速放大）与 `SNAP_ERROR_MS = 500`（误差过半秒直接吸附）。`nextTimerValue` 增加 `speed` 参数，补单测 2 例。
- **P1-3 计算器负数无法续算**：`3-6` 之后表达式里留下 `-3`，再按运算符或 `=` 会因为「首字符是运算符」直接 `ERROR`，伪装页显示「错误」很扎眼。
  - 修法：`CalculatorEngine` 支持表达式开头的一元负号；补负数用例 6 条（`-3+2` / `-3×2` / `0-3-3` / `-3` / `-3÷2`。
- **P1-6 文档一致性**：`architecture-map` 的测试清单（§3/§8 两处 76 类 806 例 → 79 类 840 例，补 `MediaClockSyncTest` / `CalculatorEngineTest` / `DisguisePrefsTest` / `SkipCancelPolicyTest`，删已不存在的 `DanmakuSyncTest`）、弹幕时钟旧口径（把「DanmakuSync + 周期性 seekTo」整段换成 §7.51 的现行做法）、`docs/README.md`「19 章」→「20 章」、`FEATURES.md` 重编号后 6 处旧引用、`readme.md` 的分支制度与已不存在的 `testDebugUnitTest`。
- **P2 顺手修**：短视频 `onError` 未把 `isPlaying` 置 false（画面停住却一直挂着 `KEEP_SCREEN_ON`）；`isPlaying` 补 `@Volatile`（弹幕渲染线程读）；`format` 指定 `Locale.US`（默认 Locale 会把小数点写成逗号，结果再回填就解析不回来）；`DrawHandler` 的 SEEK_POS / RESUME 分支重置媒体时钟采样（避免暂停恢复后计时器前冲再回落）；长视频续播 seek 补 `markSeekTarget`；设置里的暗号不再**逐键**弹 toast（`TextWatcher` 每次击键都会触发）。
- **未做（需单独决策）**：把 PR 拆成三份、`readme.md` 功能表补伪装条目之外的市场化描述、`excludeFromRecents`/图标（需真机确认最近任务卡片表现）、`setScreenOnWhilePlaying(true)` 对 TextureView 无效但仍保留（SurfaceView 路径可能有意义，改动收益不明确）。
- **验证**：`:app:assembleCoexistDebug` 通过；`:app:testStandardDebugUnitTest` **79 类 / 840 例 / 0 失败**；模拟器实测 P0 与双击手势；markdownlint 31 文件 0 issue。

## 4. 历史章节索引

> 完整正文在 `docs/archive/review/fix-progress-history.md`。**编号在历史上有重复**（`## 二十二` / `## 二十九` 各出现两次、`## 30.` 是阿拉伯数字写法，原为不打断既有交叉引用而保持不动），下表**以归档文件的物理行序为准**。
> §一~§三十 的「收尾状态」按归档正文与 §五 / §十 的登记推断：批次类记「已闭环」，正文明确登记遗留的记「部分遗留」，总览 / 明细 / 建议 / 验证记录类记「纯历史」。

| §编号 | 标题 | 一句话内容 | 收尾状态 |
| --- | --- | --- | --- |
| 一 | 修复总览 | 286 条基线（Critical 23 / High 52 / Medium 105 / Low 106）+ 2026-09-24 深度审查新增 严重 10 / 中等 13 组 / 轻微 11 | 纯历史 |
| 二 | 已修复问题明细 | 逐轮修复记录，逐条 file:line（第一~四轮表格为主） | 纯历史 |
| 三 | 审查前已修复（本次核查确认，无需改动） | 审查前已完成、本次核查确认无需改动的项 | 纯历史 |
| 四 | 构建验证状态 | 当时的构建 / 单测记录与结论 | 纯历史 |
| 五 | 待处理问题（按优先级） | 绝大多数已 `[x]`；仍未关闭的 A7 WebView 输入校验 / A8 教程键污染 / A9 硬编码专栏 id / E1 拆 PlayerActivity / 发布侧 APK 校验等见 §2.2 | 部分遗留 |
| 六 | 下一步建议 | 4 条早期建议，已过时 | 纯历史 |
| 七 | GitHub issue 修复轮次（2026-10-03） | issue #1 手表右滑返回与滑动调进度冲突；根因 `android:windowSwipeToDismiss` 在窗口层直接 finish + `setTheme(ColorScheme.themeResId(theme))` 覆盖 `Theme.NoSwipe*` | 已闭环 |
| 八 | 发版链路改造：Gitee 发行版同步 + 更新检查改读发行版（2026-10-03） | 发行版同步到 Gitee，客户端更新检查改读发行版而非 tag | 已闭环 |
| 九 | 高能进度条接口变更适配（2026-10-04） | pbp 接口变更后的适配 | 已闭环 |
| 十 | 26.10.04 逐条拍板后的待做队列 | A1~A12 / C6b / E1~E6 结论映射表 + 已作废项（D2 跑马灯、D3 圆屏、D5 候选词）+ 数字勘误 + 8 批落地顺序；仍开放的即 A7 / A8 / A9 / E1 | 部分遗留 |
| 十一 | 26.10.04 批次 1：A 组快修落地记录 | A 组快修逐条落地 | 已闭环 |
| 十二 | 26.10.04 批次 2：A1 csrf 实时化 + A10 评论点踩 | csrf 实时化与评论点踩 | 已闭环 |
| 十三 | 26.10.04 批次 3：性能参数接线 + 列表增量刷新越界 + 崩溃页独立进程 | 性能参数接线、增量刷新越界排查（判据进 `architecture-map.md` §7.9）、崩溃页独立进程 | 已闭环 |
| 十四 | 26.10.04 批次 4：SettingsKeys 收敛收尾 + 删假批量 API + 更新包校验 | E4 键名收敛（`SettingsKeysTest` 钉键名）+ 删假批量 API + 更新包校验（`ApkVerifier`） | 已闭环 |
| 十五 | 26.10.04 批次 5（1/4）：私信会话管理（C13） | 私信会话管理（置顶 / 删除 / 查看主页） | 已闭环 |
| 十六 | 26.10.04 批次 5（2/4）：私信发图（C12） | 私信发图；`size` 小 1024 倍缺陷（见 `architecture-map.md` §7.14） | 已闭环 |
| 十七 | 26.10.04 批次 5（3/4）：新消息通知栏通知（C14） | 新消息通知栏通知 | 已闭环 |
| 十八 | 26.10.04 批次 5（4/4）：追番更新提醒（C16） | 追番更新提醒 | 已闭环 |
| 十九 | 26.10.04 批次 6（1/8）：评论置顶 / 取消置顶（C3） | 评论置顶与取消置顶 | 已闭环 |
| 二十 | 26.10.04 批次 6（2/8）：评论楼中楼排序（C4） | 楼中楼排序开关（纯客户端 `Reply.sortReplies`）；后续由 §30. 重做成真双向排序 | 已闭环 |
| 二十一 | 26.10.04 批次 6（3/8）：带图评论的三个静默出错（C6b） | 带图评论三个静默出错；12066 未复现 | 部分遗留 |
| 二十二 | 26.10.04 批次 6（4/8）：动态编辑与管理菜单（C7） | 动态编辑与管理菜单（点击与长按弹同一个菜单） | 已闭环 |
| 二十三 | 26.10.04 批次 6（5/8）：动态置顶与取消置顶（C8） | 动态置顶与取消置顶 | 已闭环 |
| 二十四 | 26.10.04 批次 6（6/8）：动态定时发布（C9） | 动态定时发布 | 已闭环 |
| 二十五 | 26.10.04 批次 6（7/8）：话题广场与话题动态列表（C10） | 话题广场与话题下动态列表；广场仍单页不翻页 | 部分遗留 |
| 二十六 | 26.10.04 批次 6（8/8）：视频笔记查看（C27） | 视频笔记查看；「无法正常加载」根因未确证 | 部分遗留 |
| 二十七 | 26.10.04 批次 7（1/4）：稍后再看「未看完」分类（C18） | 稍后再看「未看完」分类；服务端 `viewed=2` 语义待真机 | 部分遗留 |
| 二十八 | 26.10.04 批次 7（2/4）：收藏夹排序与复制 / 移动（C19） | 收藏夹排序行与复制 / 移动 | 已闭环 |
| 二十九 | 26.10.04 批次 7（3/4）：收藏夹多选删除（C20） | 收藏夹多选删除 | 已闭环 |
| 三十 | 26.10.04 批次 7（4/4）：关注分组增删改（C21） | 关注分组增删改（系统分组不可编辑） | 已闭环 |
| 三十一 | 26.10.04 批次 8（1/2）：拆分 DownloadService（E2） | `service/download/` 分层（`DownloadPathSpec`/`DownloadProgressMath`/`DownloadProgressStore`/`DownloadRepository`/`DownloadNotifier`），对外 `@JvmStatic` API 一字未改；F4 漫画裁为不做；测试数 38 类 / 327 例 | 已闭环 |
| 三十二 | 26.10.05：弹窗（选择框）主题接入 + 终端列表收口 | 三个缺失主题属性、7 份 `ThemeOverlay.<X>.Dialog`、`dialog_background.xml`、`util/TerminalDialog.kt`（369 行四方法）、17 处调用点全迁 | 已闭环（当时的编译验证缺口由 §36.3 关闭；对比度未重测属遗留） |
| 二十二（第二处） | 26.10.04 批次 7（5/5）：置顶会话排序 + 收藏夹排序行 + 关注分组移动成员（C22） | `util/SessionSorter.kt`、`FavoriteVideoListActivity` 链路排查（代码无缺陷 + 诊断埋点）、`FollowApi.moveFollowTagUsers` + 长按移动成员 | 部分遗留（真机未验证） |
| 三十三 | 26.10.05：片头片尾跳过默认开启 + 新增「通知设置」大类 | `player/SkipOpEdPrefs.kt`、`GROUP_NOTIFY` 分组、`SettingsIndex` 条目 | 纯历史（后由 §四十三 整体撤回；「通知设置」部分仍生效） |
| 三十四 | 26.10.05：话题广场加载不出来 + `#话题#` 不解析（C23） | `TopicApi` 换 `pub/search`、三处富文本 switch 补 `RICH_TEXT_NODE_TYPE_TOPIC`、`StringUtil.TopicClickableSpan`、`LinkUrlUtil.parseTopicId` | 已闭环（广场单页不翻页属遗留） |
| 二十九（第二处） | 26.10.04 批次 6 复查（C27 视频笔记 + C6b 带图评论） | `NoteApi.failureText` 分级 + `BiliNote` 埋点、`img_size` 改 double / `buildPictures`、私信 `buildImageContentOfKb` | 部分遗留（笔记根因未确证、12066 未复现） |
| 30. | 长按冲突与楼中楼排序二次切换失效（task-4） | `Reply.sortReplies` 真双向排序 + `ctime`、`DynamicHolder.showManage()` 去重、`ReplyAdapter.canManage`、`cell_reply_list.xml` 兄弟节点分析 | 部分遗留（§30.5b 未决项、真机未验证） |
| 三十五 | 26.10.05：稍后再看「未看完」为空（C18 回归）+ 新增「清除所有已看完」（task-2） | 服务端 `viewed` 参数、`isUnfinished`/`filterUnfinished` 判据翻转、`history/toview/clear` + `clearWatched` | 部分遗留（服务端 `viewed=2` 语义待抓包确认） |
| 三十六 | 26.10.05 Lead 终验：全队冻结后的串行全量验证 + 本轮问题闭环对照 | 13 条用户报告逐条对照表、43 类 / 400 例全绿、`AlertDialog` 自检 0 命中、方法论 5 条 | 已闭环（关闭 §29.4/§30.4/§35.6 的构建缺口） |
| 三十七 | 26.10.05 批次 8：长按操作面板（复制收进弹窗）+ 顶部工具条滚动收回 | `util/LongPressPrefs.kt` + `long_press_panel_enable`、「复制文字」进面板、`setupAutoHideBars` / `expandAutoHideBars` / `util/view/ScrollRetractDecider.kt` | 部分遗留（真机手感未验证） |
| 三十八 | 26.10.05 线上 release 崩溃：动态详情页 NPE（用户提供混淆栈） | mapping retrace 定位 lambda、`Dynamic.ensureDetailFields()`、`diFragment.view!!` 改 `view?.let`、6 例新单测 | 部分遗留（未真机复现） |
| 三十九 | 26.10.05 第三批真机反馈：收藏夹顶部工具条三处 | `supportsChangeAnimations = false`、`manageBar` 只在多选态可见 + 长按菜单「多选」入口、`ScrollRetractDecider.action()` 展开条件收紧为「仅 `!canScrollUp`」、`suppressClickAfterLongPress` | 部分遗留（三处均未真机复现） |
| 四十 | Release 说明漏掉更新日志：根因与兜底（26.10.05 发版后） | `.github/scripts/extract_update_log.py`、`build-release.yml` fail-closed、AGENTS/SKILL 硬要求 | 部分遗留（26.10.05 Release 正文未人工补） |
| 四十一 | 26.10.09：自建反馈界面 + 自建公告源 + 匿名统计 / 崩溃自动上报（新功能） | `server/` 全栈 + `api/TerminalApi.java` + `util/TelemetryReporter.kt` / `CrashReporter.kt` / `CrashTrail.kt` + `activity/FeedbackActivity.kt` + 隐私文案改写 + §41.8 隐私再同意闸门（`PRIVACY_CONSENT_VERSION` / `PRIVACY_PROMPTED_VERSION` / `TerminalDialog.choice`） | 已闭环（明细回归清单待补）；正文见 `docs/archive/review/fix-progress-history.md` |
| 四十二 | 26.10.09：管理控制台 WebUI（遥测 / 反馈 / 崩溃 / 公告四合一）+ 服务端并发加固 | `server/console.html` 零依赖单文件、`run_in_threadpool` + `synchronous=NORMAL`、无状态签名 Cookie + 双 CSRF 防线、`console.zsapp.asia` vhost / 证书、`backup.sh` + 探活、selftest 39 项 | 部分遗留（无告警渠道、无 TOTP/IP 白名单、无浏览器回归） |
| 四十三 | 26.10.10 补丁：紧急撤回「自动跳过片头片尾」（2610102） | `SkipOpEdPrefs.FEATURE_ENABLED = false` + `isEnabled()`、四处落点、`SkipOpEdPrefsTest` +3 例、tag `26.10.10.1` | **26.10.22 已恢复**（`FEATURE_ENABLED = true`，口径改为默认关闭），详见 §3.3 恢复记录 |
| 四十四 | 26.10.11：播放器网络韧性（弱网卡死 / 直播线路 / 切后台位置错） | `PlayerActivity.kt` 加读超时与重连选项 + 停滞看门狗 + `onSaveInstanceState` + 重挂 Surface 改 seek `video_now`；新增 `util/live/LiveStreamSelector.kt`（+14 单测）与 `LiveInfoActivity.kt` 接线、`PlayerData.liveUrlCandidates`；删 `max_cached_duration` | 部分遗留（真机只跑到「位置未复现回跳」；看门狗未真机触发；P0-2 重取 URL、P1-3 后台保活未做） |
| 四十五 | 26.10.11：共存版 product flavor（普通版 + 共存版双包） | `standard` / `coexist` flavor、FileProvider authority 去硬编码、`app/src/coexist/res/mipmap-nodpi/icon.png` 图标覆盖（两版同名 RE:哔哩终端，靠图标区分）、`UpdateRelease.pickApkUrl(namePrefix)`（+4 单测）、`build-release.yml` 整理发行 APK 步骤 | 已闭环（真机双包共存 + 数据隔离 + 同名 + 图标已验；共存版自更新待下次发版） |
| 四十六 | 26.10.11：弹幕「一抖一抖」——时钟基准错位 | `player/DanmakuManager.kt` 的 `updateTimer` 不再 `timer.update(pos)`（删掉位置回调参数）；对齐改到主线程 `PlayerActivity.progressChange()` + `DanmakuSync` 判定 + `mDanmakuView.seekTo(video_now)`（`SEEK_POS` 会重设 `mTimeBase`）；`onPrepared` 起播锚定；短视频播放器同样处理 | 真机取证已完成（42 次/秒硬校正、偏差最大 199s）；修复后待用户实机确认 |
| 四十六 | 26.10.11：线上崩溃 `PlaybackService` 5 秒义务未兑现（`RemoteServiceException`） | 根因 = `onPause` 的 `startForegroundService` 与 `onResume`/`onDestroy` 的 `stopService` 竞态（低内存手表上服务创建慢，`stopService` 先到 → 义务作废 → 5 秒后崩主线程）+ `startUpdateTimer()` 误放 `onCreate`；修法 = `foregrounded`/`stopPending` 两标志 + `onCreate` 就 `enterForeground()` + 兜底通知 + 定时器移到进场之后 | 已过构建与单测（两 flavor 各 478 例全绿）；真机未复现 |
| 四十七 | 26.10.11：控制台补「反馈 / 崩溃」已处理开关，并关掉已解决项 | `server/rebiliterminal_api.py`（`_db_set_handled` + `POST /admin/api/{feedback,crash}/handled`；两个列表接口加 `handled` 与 `pending=1`）、`server/console.html`（默认只看未处理 + 状态标签 + 标记/重开按钮 + 待处理计数）、`server/selftest.py` +17 例、`server/README.md` 与 `docs/architecture-map.md` 同步 | 已闭环（服务器实测：56 项 selftest 全通过、curl 覆盖 403/415/400/404；crash #2「PlaybackService 5 秒义务」与 feedback #2 测试内容已标记；crash #3/#4/#5 `TimeUtil$cache` 未修故保留待处理） |
| 四十八 | 26.10.16：日志基建 + 崩溃 / 反馈附带日志 + 「我的反馈」页面 | `util/Logu.java` 内存环形缓冲（`RING_MAX_CHARS=65536`、只记 W/E/F、不落盘）、`util/LogSanitizer.kt`（键=值脱敏，复用 `NetWorkUtil` 两份键名清单）、`util/LogExporter.kt`（FileProvider 分享）、`util/CrashReporter.kt` 的 `mergeLog()`、`ErrorCatch.java` 的 `ring_log` Intent extra、`CatchActivity.kt` / `FeedbackActivity.kt` 勾选框、`TerminalApi` 的 `log` 参数与 `getMyFeedback()`、`activity/feedback/` 列表 + 详情、服务端 `feedback.log` 列（`SCHEMA_VERSION` 1→2）+ `/terminal/feedback/mine` + `/admin/api/{feedback,crash}/detail` + `log_len` | 部分遗留（服务端已上线；客户端已真机验证通过，仅日志本地导出、崩溃自动上报端到端、跨设备找回未覆盖，见 §3.20） |
| 四十九 | 26.10.16：反馈对话——用户可追加说明 | 服务端 `feedback_comment` 表（`SCHEMA_VERSION` 2→3，**v3 刻意无 DDL**）+ `POST /terminal/feedback/reply` + 归属判据 `_MINE_SQL` 与 `/feedback/mine` **共用同一份** + 追加即把 `handled` 打回 0（reopen 语义）+ `_db_feedback_thread` 合并两侧消息 + 管理台 `user_reply_count` 与对话抽屉；客户端 `TerminalDialog.input()`（+`Sheet.addInput`）、`FeedbackReply.author`、`submitFeedbackReply`（走新增 `postWithHeaders`）、`FeedbackDetailActivity` 时间线与「追加说明」入口、`FeedbackListActivity` 结果码重拉 | 部分遗留（跨设备 `X-Mid` 追加、发送失败重开预填未真机覆盖，见 §3.21） |
| 五十 | 26.10.19：线上崩溃两路清剿（#43–#49） | A 路：#43–#47/#49 无 message NPE = `UserDynamicFragment.continueLoading` 里 `adapter!!`（首屏加载失败 → adapter 从未创建 → 空列表拖动翻页）；三处 `runOnUiThread` 加 `isAdded && view != null` + `adapter?.`，`setRefreshing(false)` 进 `finally`，顺带修 `UserVideoFragment` 插入起点漏 `+1`。B 路：#48 `libijksdl.so` 加载失败 = `UnsatisfiedLinkError`（`Error`）冒到主线程 + `IjkMediaPlayer()` 构造会二次加载；新增 `player/IjkNativeLibs.kt`（失败锁死 + 诊断上传），`PlayerActivity` / `IjkPlayerBridge` / `VideoPlayerCore` / `ShortVideoPlayerActivity` 全部改判返回值并降级 | 部分遗留（构建与两 flavor 单测全绿，**未真机复现**；见 §3.29） |

## 5. 仍待回迁 / 仅见于归档的长期结论

> 下列结论目前**只存在于归档正文**，`docs/architecture-map.md` §7.29~§7.36 与 AGENTS.md 都还没有；要么回迁到 `architecture-map.md` §7，要么保持「归档可查」。已被 §7 / AGENTS / §6.5 覆盖的结论不再重复。

- **默认值必须收敛到唯一常量**：片头片尾跳过被用户误判为「压根没实现」，实际接线完整，唯一原因是**四处调用点各写一遍字面量 `false`**。漏掉一处就会出现「设置页显示开着、播放器却不跳」这种**半生效状态**（范式：`player/SkipOpEdPrefs.kt` 的 `DEFAULT_ENABLED`、`util/LongPressPrefs.kt`）。§三十三 / §37.1
- **改默认值天然满足「新装默认开 + 老用户尊重显式选择」，不需要任何迁移 / 重置代码**：`SharedPreferences.getBoolean(key, def)` 的语义就是「键不存在时才用 def」。既有同款范式：`util/SharedPreferencesUtil.java:132-144` 的 `loadMenuEnabled()` 用 `sharedPreferences.contains(key)` 区分。§三十三 / §37.1
- **改默认值 / 改键名是两种效果**：改键名等于把老用户设置读成「没设过」，默认值会把他关掉的开关重新打开 → 单测必须有「键名必须稳定」的护栏。§37.1
- **撤回一个已发布的特性，不能只改默认值**（见 §3.3 的 `FEATURE_ENABLED` 否决位）。§四十三
- **废弃接口的经典陷阱：`code=0` + `data=null` 造成静默空列表**：话题广场原走的 `app.bilibili.com/x/topic/web/dynamic/rcmd` **恒返回 `{"code":0,…, "data":null}`**（带 / 不带参数、游客 Cookie、伪装浏览器 headers、`page_size` 9/25/26 全部一样）。因为 `code` 是 0，`if (code != 0) throw` 永不触发 → 静默 `data == null → 返回空列表` → `showEmptyView()`。**按「网络异常 / 抛异常」方向排查全是死路**。替代源：`api.bilibili.com/x/topic/pub/search`（`keywords` 留空即按热度返回整页话题，稳定 20 条，匿名可用）。§三十四
- **单向排序缺陷的形态**：`model/Reply.java` 的 `sortReplies` 首行 `if (replies == null || sort != SORT_LIKE) return;` → `SORT_TIME`（= 0）直接 return、从不重排 → 「时间→热度→时间」往返后顺序**永久停在热度序**。这是**推翻一个旧的设计取舍**（归档正文 `fix-progress.md:1758`「时间序 = 不做任何本地重排」），不是修疏忽。修法用 `ctime`（服务端**恒返回**）显式升序，两个比较器 `LIKE_ORDER`/`TIME_ORDER` 都是 `private static final` 且各自 null 兜底，**两个分支都幂等**。**`pubTime` 是展示文案、不单调，不能做排序键。**§30.1
- **「排序前备份原列表、切回时还原」被否决**：`Reply` 实现 `Serializable` 且跨页传递，模型里藏 mutable 备份字段经翻页 / 点赞 / 删除后极易变脏。§30.1
- **`Reply` 只实现 `Serializable`、无任何 `Parcelable`**（`model/Reply.java:31`），评论相关 `putExtra` 只传标量 → 新增 `ctime` 无需同步 Parcelable（属**理由修正、结论不变**）。§30.1
- **同一手势承载两种语义 = 用户嘴里的「冲突」**：`util/StringUtil.java:170` 的 `setCopy(TextView, String)` 是业务代码自己注册的长按监听（`SharedPreferencesUtil.getBoolean("copy_enable", true)` + `startActivity(CopyTextActivity)`），不是 `LinkMovementMethod`/`textIsSelectable`。**同一个 TextView 只能有一个长按结果** → 修复方向是「按需让位」，不是去改复制本体。§30.2 / §37.1
- **`performLongClick()` 转发 click 会弹两次菜单**：长按手势结束后系统还会补发一次 click。动态侧原代码 `item_dynamic_delete!!.setOnClickListener { item_dynamic_delete!!.performLongClick() }` 就是真实缺陷。修法：**存普通 lambda 而不是 `View.OnLongClickListener`**，收敛到唯一真相源 `DynamicHolder.showManage()` + `MANAGE_DEDUP_MS = 400L` 去重；`showManage()` 在 `manageAction == null` 时返回 `false` → 不消费长按 → 自动回落到 `setCopy`。§30.2 / §37.1
- **`setOnTouchListener` + `ACTION_DOWN` 返回 `false` 也会有补发 click**（`VideoCardHolder.bindClick()`）：多选态下「长按先选上、抬手立刻取消 = 什么也选不中」，非多选态下「长按弹菜单、抬手又跳详情页」。修法：`suppressClickAfterLongPress` 标记，长按回调真执行就立起、随后那次点击自己吞掉，`bindClick()` 里随新绑定清零。§39.2
- **RecyclerView 的 item animator 会吃掉你的 alpha**：`DefaultItemAnimator` 对 `notifyItemChanged` 是另建 ViewHolder 交叉淡入（`supportsChangeAnimations = true` 时 `canReuseUpdatedViewHolder()` 返回 `false`），动画收尾把新布局 alpha 设成 `1f` → 「勾选（目标 1f）看不出问题、取消勾选（目标 0.45f）被静默改回 1f」。修法：`VideoCardAdapter.onAttachedToRecyclerView()` 里 `(recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false`。§39.1
- **`ScrollRetractDecider` 一条反直觉返回值必须保留**：已收着时**继续向下滚仍返回 `COLLAPSE`**，唯一作用是让调用方把累加值清零；返回 `NONE` 会让累加值一直涨，表现为「条收起来以后怎么滚都不回来」。§37.2 / §39.1（工具条的兄弟节点结构与「展开只允许在列表到顶」的口径已在 §7.28 / AGENTS）
- **「自然高度」必须缓存**：条本是 `wrap_content`，收回被压成 0，而 0 高度量不出「原本多高」→ 否则「收得回去、展不回来」；且「收回」只动当前真正可见的条。§37.2
- **`danger` 不要写死下标**：`setOf(2)` 在菜单插入新项后会漂移，改为 `setOf(actions.indexOfFirst { it.first == "取消收藏" })`。§39.1
- **`local` 判据的朝向决定用户看到什么**：稍后再看旧 `isUnfinished` 首行 `if (progress <= 0) return false;` 把「从未播放」当成「不是未看完」→ 整表都从未播放时「未看完」档**就是空的**（与用户现象逐字吻合）；且本工程**从未给 `history/toview/web` 带过 `viewed` 参数**，服务端能力从未被使用。正确：**主判据 = 服务端 `viewed`（0 全部 / 2 未看完）**，本地 `filterUnfinished` 退化为兜底（`duration <= 0` → 一律算「未看完」；只有 `progress >= duration && duration > 0` 才算「已看完」）。§三十五（§7.23 只覆盖了 `isUnfinished` 的判据本身）
- **`content` 长按点击的 span 陷阱**：`StringUtil.LinkClickableSpan` 的 `case TYPE_WEB_URL:` 取的是 `text`（span 显示文本）而**不是**第三个构造参数 `val`（`StringUtil.java:205` 能工作是因为那里 `text` 就是 URL）；且 `updateDrawState` **硬编码 `ds.setColor(Color.rgb(0x66,0xcc,0xff))`**，执行时会**覆盖 `ForegroundColorSpan`** → 话题必须新写 `StringUtil.TopicClickableSpan`（颜色取 `ColorScheme.INSTANCE.getPRIMARY()`，**不硬编码色值**），解析不出 `topic_id` 时退化为「只上色、不可点」。§三十四
- **R8 合并类的 mapping 不是一对一**：`E0.j` 表头写 `DynamicInfoActivity$$ExternalSyntheticLambda1`，但它同时有 `(CollectionInfoActivity, long)` 构造来源；`C0.c` 底下合并了 `VideoRcmdFragment`/`VideoInfoFragment`/`VideoInfoActivity`/`BangumiInfoFragment` 一串同类 SAM 包装。**单看表头类名定归属会错**，必须结合 `residualsignature` 注释与实际方法签名。§38.1
- **崩的是 release 包就必须单独验 release 链路**：`assembleRelease`（含 `minifyReleaseWithR8`）不能只跑 debug。§38.4
- **`app/build/outputs/mapping/release/mapping.txt` 不进版本库**，换机或 `clean` 后即失 → 线上混淆栈要趁 mapping 还在先 retrace。ProGuard retrace 能读 R8 写进 mapping 的 `# {"id":"sourceFile"}` 与 `residualsignature` 注释，**不需要额外装 R8 retrace**。§38.1
- **`Result.onSuccess` 自己已判空**（`if (isSuccess()) { T value = getOrNull(); if (value != null) accept(value); }`）→ **NPE 只可能在消费者 lambda 体内**，不在 `Result` 里，这是比读 mapping 表头更快的定位路径。§38.1
- **并发构建必须串行**：`app/build/` 共享，Gradle 对 `tmp/kotlin-classes`、`intermediates/javac` 先删再写，多人同跑必互踩并产生**假失败**。判据三选：① 复跑同一命令 ② `git status --short` 前后对比确认工作树静止 ③ 报错文件 `git diff` 与 HEAD 有实际差异；不全只能报「疑似在途中间态」。**类型不匹配 = 真错误；引用不存在的类 = 多为中间态。** §36.7
- **用例数一律以机器产出为准**：读 `app/build/test-results/testDebugUnitTest/*.xml` 的 `<testcase>` 计数；队友口述「23 例」实际 25 例（场景数口径与 `@Test` 数口径混用）。口述数字不进入正式文档。§36.7
- **正式文档只写正确结论**；唯一例外是「以错误理由做出的正确决定」，写成「**理由修正、结论不变**」并保留原错误理由，否则后人会重新推演出同一个错误理由。§36.7
- **JVM 单测里的 Android 桩陷阱（`§7` 只覆盖了「判据抽纯函数」这一半）**：`android.util.Pair` 这类**直接 `new` 出来的框架类**在单测下字段恒 null，只断言列表长度完全测不出。判据不是「类型名里有没有 `android`」，而是「**这个值是真实实现算出来的，还是桩给的默认值**」（自实现 Android 接口的 fake 如 `FakeSharedPreferences`、以及 `app/build.gradle` 单独补了真实实现的 `org.json` 都不踩坑）；**「测试文件里没有 `import android.*`」≠「该测试不依赖 Android 桩」**。修法：新建 `model/TopicNode.java` 取代 `Pair` + 回归守卫 `parseTopicNodes_returnsRealObjectsNotAndroidStubs`。§36.3 / §36.7
- **异常堆栈里没有被测方法的帧 → 失败在构造参数转型那一层**：`NoteApiTest.kt:198` 原写 `as String`，Kotlin 在构造器调用当成就抛 NPE，异常根本没进 `failureText`；改 `as String?` 后 null 才传进去（`NoteApi.failureText` 的 null 兜底 `NoteApi.java:237` 失败时就已存在、一行未改）。⚠️ 不要读成「产品代码曾有 null 返回缺陷」。§36.3
- **断言「某属性全工程缺失」必须同时 grep 相邻属性做对照组**：最初结论「弹窗层从未接入主题系统」被「7 族主题都声明了 `colorSurface`」与「XML 侧 71 处 `?attr/` 引用」直接推翻。§三十二（`§8.4` 只覆盖了主题层根因）
- **`Sheet.init` 必须清 window 背景**（`setBackgroundDrawable(TRANSPARENT)`），否则圆角被方角底衬盖掉。§三十二
- **`colorError` 的对比度未重测**：`docs/archive/visual-experience-report.md` §2.25（`#EBE0E2` 叠 `#FF6699` 约 2.1:1）仍待处理。§三十二
- **Python 服务的瓶颈不是 Python 而是 fsync**：单 uvicorn worker 处理「小 JSON + 一次小写入」天花板在几百 req/s；加固两件事：阻塞 sqlite 调用必须 `run_in_threadpool`（否则撞锁时 `busy_timeout=15000` 卡死整个事件循环、连 `/health` 都不响应）、`PRAGMA synchronous=NORMAL`（WAL 下默认 `FULL` = 每次 commit 都 fsync）。§42.2
- **带 `Secure` 的 Cookie 在 http 下不会被回发**：selftest 一开始用 `TestClient(api.app)` 默认 `http://testserver` → 「登录成功但后续全 403」；改成 `TestClient(api.app, base_url="https://console.test")` 才对。这个假失败恰好证明 `Secure` 确实生效。§42.3
- **nginx 签证书的坑**：证书还没签出来时 vhost 不能引用证书文件，得先用只 `listen 80` 的引导配置；引导配置若写了 `location / { return 404; }` 而**没给 `/.well-known/` 单独开口**，acme 的 http-01 会拿到 404（`Invalid response from …/.well-known/acme-challenge/…: 404`）。补 `location ^~ /.well-known/ { root /www/wwwroot/<域名>; allow all; }` 后签发成功。§42.6
- **WAL 模式下备份必须用 `sqlite3 .backup`，不能 `cp`**（最近写入还在 `-wal` 里，直接拷会丢数据）。§42.7
- **PowerShell 传 JSON 的坑**：`curl.exe --data-raw '{"title":"中文…"}'` 含中文时会被 Windows 控制台代码页搞坏 → 服务端 `json.loads` 失败 → `read_json` 返回 `{}` → 报「标题和正文都不能为空」。正确姿势：**把请求体写成无 BOM 的 UTF-8 文件，再用 `--data-binary @file`**。§42.8
- **`update_log_current` 是当版日志的单一真相**（关于页读它、`:app:verifyVersionConsistency` 拿它当锚点），不引入第二份需要维护的文案。§40.3
