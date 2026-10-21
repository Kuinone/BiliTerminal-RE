# AGENTS.md

## 语言约定

**与用户交流、代码注释、文档一律中文。**

## 先读架构地图

改任何非平凡代码前先读 **`docs/architecture-map.md`**：真实分层、基类体系、43 个 API 类映射、UI 模板、逐条坑点。本文件只放每次都要遵守的约定。

其他参考：**`docs/README.md`（文档总索引——先看它决定该读哪份）**、`bilibili-API/`（B 站接口文档快照）、`docs/bilibili-api-notes.md`（接口与风控常识，改 API 前读）、`docs/FEATURES.md`（用户可见功能总表）、`docs/review/agent-test-guide.md`（**用 adb / agent 驱动 Debug 包测试的手册**，加调试能力前先读）、`docs/superpowers/specs/`（功能设计文档，写新功能前读）、`docs/tutorial-system-redesign.md`（教程系统重做中——**改教程前先读它**，旧链路正在被替换）。**`docs/archive/` 里全是历史资料，记录的是当时、不是现状**，别照着它改代码。

## 项目概况

- RE:哔哩终端（ReBiliClient），第三方 B 站**手表**安卓客户端，`main` 分支。
- 典型 vibe coding 产物：代码大量由 AI 生成。**改动前先读源码核实，注释和文档都可能是错的。**
- 版本号按 YY.MM.DD（`app/build.gradle`）。

## 架构（三条与直觉相反的）

1. **入口是 `BiliTerminal.kt`**（26.09.10 由 `BiliTerminal.java` 迁移而来）。原 `BiliTerminalApp.kt` 已于 26.10.02 整体删除——它从未被实例化，只因 `SplashActivity` 的 UETool 逻辑引用其静态方法而残留；那些方法与常量已移入 `BiliTerminal.kt` 伴生对象。全局 Context 取 `BiliTerminal.context`——它在 companion 里用 `@JvmField` 暴露成**静态字段**，所以 Java 侧仍是 `BiliTerminal.context` 的字段读法，Kotlin 侧按需 `!!`。
2. **没有 DI / Retrofit / ViewModel**——26.09.11 死代码清理后这已是**事实**而非"死依赖残留"：Hilt、Retrofit、kotlinx-serialization、Jetpack Navigation、protobuf-javalite、geetest sensebot、asynclayoutinflater、cardview、lifecycle-viewmodel-ktx 等**已全部从 `app/build.gradle` 移除**，`ksp` 与 `kotlin.plugin.serialization` 两个插件也一并去掉（`@HiltAndroidApp` 随之从 `BiliTerminalApp.kt` 摘除）。原先 24 个空目录（`di/`、`network/`、`data/`、`ui/base` 等）已删除。新功能写进 `api/` + `activity/`，沿用静态方法 + `org.json`。
3. **只有一条链**：`activity/` → `api/`（全同步阻塞）→ `util/` → `model/`。导航由 `MenuActivity.btnNames` + `util/MenuConfig.kt` 决定。

## 硬约定

- 网络请求包在 `CenterThreadPool.run { }` 里。
- 列表页继承 `RefreshMainActivity`（菜单入口页）或 `RefreshListActivity`（返回式）；加载完必须 `setRefreshing(false)`，否则翻页永久卡死。`onLoad(page)` 的 page 已自增。
- `activity_simple_refresh.xml` 里的 `filterBar`/`sortBar`/`manageBar`/`groupBar` 都是列表的**兄弟节点**（不是列表项），不会随列表滚走。想让某条**滚动时自动收回**，在子类里调 `setupAutoHideBars(条…)`（`RefreshListActivity`，**默认不启用**）；**别把「可见性在运行时才变化」的条放进去**（如收藏夹的多选条 `manageBar`）——展开时会把它强行点亮。判据抽在 `util/view/ScrollRetractDecider.kt`（纯函数，有单测）：**收回随时可以，展开只允许在列表到顶（`!canScrollUp`）时**——条的高度动画会顶动列表内容，中途展开会和滑动手势打架（真机反馈过，别把「向上滚就展开」的旧口径改回去）。
- 新增菜单页改三处：`MenuActivity.btnNames`、`MenuConfig.ALL_ITEMS`、`AndroidManifest.xml`。**但新菜单 key 对老用户不会自动出现**——`MenuConfig.loadEnabled` 对已存的 `menu_enabled` 直接返回，只能靠「菜单设置」手动开。想让所有人都能立刻用到入口，别加菜单项，挂到既有页面（如动态页动作卡片）。
- **竖向列表里要加「横滑翻页」时，别让外层 `ViewPager2` 自己抢手势**：它判横向的门槛是 `dx > dy`（1:1），竖向滑动稍一倾斜就被它 intercept 成翻页（短视频页真机实测「几乎每次滑动都翻页」）。正确做法是 `pager.isUserInputEnabled = false`，每页根布局用 `ui/widget/SwipePageLayout`（`|dx| > |dy| * 2` 才算横滑，之后 `beginFakeDrag`/`fakeDragBy` 跟手、松手 `endFakeDrag` 吸附；超过 18% 屏宽还没吸附则补 `setCurrentItem`）。反编译结论：`beginFakeDrag()` 不受 `isUserInputEnabled` 影响；`fakeDragBy(f)` 内部是 `mRequestedDragDistance -= f` 再 `RecyclerView.scrollBy(...)`，**传负值 = 向后一页**（手指左移时 `ev.x - lastX` 正好为负，直接用这个差值即可）。`endFakeDrag()` 的吸附是异步的，松手后立刻读 `currentItem` 还是旧页。细节见 `docs/review/fix-progress.md` §3.48 / §3.49。
- 新增设置项改三处：`util/SettingsKeys.kt`、设置页 `SettingSection`、`activity/settings/SettingsIndex.kt`。
- 外观设置（配色 / 卡片圆角 / 字体）走 `ui/appearance/` 三模块：**模块只放候选值与纯函数，写入一律走 `AppearanceManager`**（它负责递增外观版本号）；模块里别做几何计算，也别在热路径上缓存。细则见 `docs/architecture-map.md` §8.7。
- **弹窗一律走 `util/TerminalDialog.kt`，禁止在业务代码里裸写 `AlertDialog.Builder`。**（26.10.05 起完成迁移，见 `docs/archive/review/dialog-redesign-progress.md`；技术结论已并进 `docs/architecture-map.md` §8.7.0）
  - **禁止的写法**：`AlertDialog.Builder(context).setItems(...)` / `.setSingleChoiceItems(...)` / `.setMultiChoiceItems(...)` / `.setMessage(...).setPositiveButton(...)`，以及任何自己 `inflate` 一个带选项列表的弹窗布局。全工程现有 17 处此类调用已在 26.10.05 全部迁移，`app/src/main/java` 下除 `TerminalDialog.kt` 自身外**不应再出现 `AlertDialog` 字样**（用 `grep -r "AlertDialog" app/src/main/java` 自检）。
  - **为什么**：弹窗的花色不只取决于调用点，更取决于主题层。7 族主题此前都没声明 `alertDialogTheme`，于是弹窗走 MaterialComponents 自带的 alert overlay，正文色取自全工程 0 处声明的 `colorOnSurface`（回退近白 → 用户看到的「灰底白字」），破坏性按钮缺 `colorError` 语义，而**唯一跟随主题的 `colorAccent` 恰好是 `#FF6699` 荧光粉**（→「粉按钮」），背景又是方角无描边（→「像安卓原生的一样」）。裸 Builder 无论怎么写都会踩这一套。
  - **正确写法**：菜单型 `TerminalDialog.menu(context, title, items, danger)`、单选型 `TerminalDialog.singleChoice(context, title, items, checked)`、确认型 `TerminalDialog.confirm(context, title, message, confirmText, cancelText, confirmIsDanger)`、纯提示 `TerminalDialog.alert(context, title, message)`。破坏性项（删除/取消收藏之类）**必须**进 `danger`，别让用户靠文案猜。
  - **给新主题补 overlay**：`ThemeOverlay.<X>.Dialog` 里 `colorSurface` / `colorOnSurface` / `colorError` / `android:background=@drawable/dialog_background` 四项缺一不可，并在该主题里加 `<item name="alertDialogTheme">`。漏写不会崩（`TerminalDialog` 取属性带兜底默认值），但弹窗会退回 Material 默认配色。
  - 颜色**只读 `?attr/`**（`colorPrimary` / `colorOnSurface` / `colorError`），`TerminalDialog.kt` 内不写任何色值常量。
- 新增设置子页面有两种形态，**先想清楚用哪种**：
  1. **独立 Activity**（如「菜单设置」「我的页面设置」）——有自定义交互/独立布局时用，改三处：`SettingGroupActivity` 对应分组加 `nav`、`SettingsIndex` 加可搜索条目、`AndroidManifest.xml` 注册。
  2. **本页的另一个 `group_type` 分组**——只是若干列表项、无自定义交互时用（如 `GROUP_APPEARANCE`「外观设置」），只需：`buildContent` 加分支 + 写一个 `buildXxxGroup()` + 在上级分组加 `nav` + `SettingsIndex` 加条目。**不需要新 Activity、manifest、布局**。对用户同样是独立一屏。
- 页面级排序/分区配置沿两个既有范式：`MenuConfig`（启用/未启用）与 `MySpaceConfig`（主列表/更多列表）——都是纯 Kotlin 单一数据源 + JVM 单测 + 复用 `MenuSettingAdapter` 式双分区拖拽写法。
- 请求与解析分离，纯解析抽成 `static`/`object` 函数并补 JVM 单测（参考 `HotSearchApi.parseHotSearch`；需要 SharedPreferences 时注入 `SharedPreferencesUtil.sharedPreferences = FakeSharedPreferences()`——该假实现是共享助手 `app/src/test/…/util/FakeSharedPreferences.kt`，**别再抄一份**）。
- **小步提交**：把改动切成小步，每步都能独立验证（`assembleDebug` + 单测通过）并说清楚改了什么，再进入下一步；不要一次性堆大量改动。
- **做完就提交并推送，且按改动类型落到不同分支**（26.10.20 起）：一次改动完成后**自己写 commit message、自己 commit、自己 push**，不要等用户催；只有改动没验证过（构建/单测没跑通）才停下来问。惯例：中文、`type(scope): 摘要`（`fix` / `feat` / `docs` / `chore` / `refactor` / `test`），一次提交只装一个关注点，末尾带 `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`。**不开 PR**（自己的仓库不需要），一个改动做完后把该分支 merge 回 `main` 并 push，保证 `main` 始终是最新可发布状态。
- **分支映射**（三条都是长期类型分支，2026-10-20 已全部同步到最新 `main`，此后各自往上叠；分支名是历史名字，**不要改名**，它们现在承担的是类型桶的角色）：

  | 改动类型 | 推到哪个分支 |
  | --- | --- |
  | `feat/*`（新功能、新能力） | `feat/gitee-release` |
  | `fix/*`（修 bug、行为纠正） | `fix/issue-1-swipe-seek-back` |
  | `docs/*` / `chore/*` / `refactor/*` / `test/*` | `main`（直接提交） |

- **改完必须自测，不许只说"应该没问题"**（26.10.22 起）：本机常驻 **MuMu 模拟器**（`adb connect 127.0.0.1:7555`，Android 15 / x86_64，`adb root` 可用 → 用 `app-coexist-x86-debug.apk`）。凡涉及播放器 / 网络 / UI 时序的改动，改完自己装包、跑一遍、看日志与截图，把实测结论（日志原文、关键截图、未复现的原因）写进 `docs/review/fix-progress.md`。**"编译通过 + 单测绿"只证明纯函数没写错，不证明功能可用**（见 `docs/review/device-test-plan.md` 〇节）。测法见 `docs/review/agent-test-guide.md`；MuMu 的两个坑：**活动会落在 display 3/4/5 等副屏**（`screencap -d 0` 拍到的是桌面，要用 `screencap -p -d <physicalId>`、`input -d <displayId>`，映射由 `dumpsys display | grep -o 'displayId=[0-9]*, uniqueId=.local:[0-9]*'` 查），且 `input` **必须带 `-d <displayId>`**，否则点击落在桌面。
- **用户可见文案一律进 `app/src/main/res/values/strings.xml`**（26.10.19 起从「只有设置页用」改为全仓迁移，台账与规范见 `docs/copy-inventory/STRINGS-MIGRATION.md`）。命名沿用既有前缀：`pagename_` 页面标题 / `btn_` 按钮 / `text_` 界面文本 / `desc_` 设置说明 / `msg_` 一次性提示。**新代码不要再硬编码中文**；迁移未覆盖的老代码可以留着，但改到哪块顺手迁哪块，别新增第二份。写法：Kotlin `getString(R.string.x)`；`companion object` / `object` 里用 `BiliTerminal.context!!.getString(...)`；Java 用 `BiliTerminal.context.getString(...)`（别忘了 import `R` 与 `BiliTerminal`）。

  **唯一例外：会被 JVM 单测直接调用的纯函数不要读资源**。本仓库不用 Robolectric，单测里没有 Context，`getString` 必然空指针（26.10.19 B6 一次挂掉 6 个用例）。这类「服务端错误码 → 人话」的映射统一放 `api/ApiErrorText.kt`（`object` + `@JvmStatic`，同包 Java 直接调），要改那几句改它一处。
- 不轻易引入新第三方库。
- **一切功能优先考虑手表端**，手机等设备只是顺便适配

## 安全与清单硬约定（26.09.13 起）

- **新增 Activity 一律 `android:exported="false"`**；只有确实要被外部应用/系统拉起（LAUNCHER、外链分享）才开 `true`，并说明理由。**Release 包**收官后全应用仅 `SplashActivity` 与 `GetIntentActivity` 导出；Debug 包另有两个仅供测试的导出组件（`TestActivity`、`debug/DebugBridge`），它们只存在于 debug 源集，不进发布包。
- **只走 https**。新的自建接口不得用 `http://`；明文域名白名单在 `res/xml/network_security_config.xml`，默认禁止明文，只放行 `bilibili.com`/`hdslb.com`/`bilivideo.com`/`afdiancdn.com`。往白名单加域名要写原因。
- **`android:allowBackup` 保持 `false`**，登录 Cookie 存于 SharedPreferences，绝不允许随备份/迁移外流。`:brotlij` 自带 `allowBackup="true"`，故主清单必须保留 `tools:replace="android:allowBackup"`（删了会清单合并失败）。
- **调试页只进 Debug 包**。`TestActivity` 是范例：类留在 `src/main`，清单声明在 `app/src/debug/AndroidManifest.xml`，入口用**编译期常量** `BuildConfig.DEBUG` 包住（不是 `BiliTerminal.isDebugBuild()` 这种运行期判断），这样 R8 才能在 release 折叠分支并 strip 掉整个类。
- **Debug 包的外部可达组件必须挂 `android:permission="android.permission.DUMP"`**（26.10.14 起）。给 agent 用的调试入口必然要 `exported="true"`（adb shell 是 uid 2000、非 root，拉不起非导出组件），但**裸导出等于把登录态送人**——DUMP 是签名级权限，普通第三方应用拿不到，而 adb shell 持有（`dumpsys` 就靠它）、本应用自己天然放行。类与清单一律放 `app/src/debug/`，安全边界是「发布包不含它」，不是「它自己判断是不是 debug」。用法与坑见 `docs/review/agent-test-guide.md`。
- **调试能力的开关必须「改完即生效」**。日志级别、落盘、HTTP 追踪统一由 `BiliTerminal.applyLogSwitches()` 一处落地（读 `SettingsKeys.DEV_*`），改完开关要立刻调它——别让用户/agent 为了生效去重启应用，自动化流程里「重启」意味着上下文丢失加一轮等待。`Agent 测试模式`（`DEV_AGENT_MODE`）是这套的总开关，Release 下因 `BuildConfig.DEBUG` 是编译期常量而被整体折叠。

## 每次改完必须同步文档

- **修 bug / 关掉一个待办** → 更新 `docs/review/fix-progress.md`（**唯一活台账**，只写现状与待办；经过超过一屏就并进它的「历史章节索引」一行，正文归档）。
- **改架构 / 基建 / 新增基类** → 更新 `docs/architecture-map.md` 对应章节。
- **新增用户可见功能** → 更新 `docs/FEATURES.md`（必要时一并更新仓库首页 `readme.md`）。
- **核实出新的接口 / 风控结论** → 更新 `docs/bilibili-api-notes.md`。
- **本文件描述的内容变了**（入口、约定、坑清单）→ 顺手改 `AGENTS.md`，别留过时描述。
- **一份知识只有一个落点**，别在多处复述；文档间引用一律写仓库相对路径（`docs/...`），源码注释里的文档路径同样要跟着改。
- **过期文档不删，归档**：`git mv` 进 `docs/archive/` 并在 `docs/archive/README.md` 加一行「内容 + 为什么归档」；**归档前先回迁**——把文中唯一还活着的知识（不要回退的取舍、未落地的待办、实测数字）搬进活文档。归档后同步改掉所有指向它的引用。

### Markdown 格式规范（26.10.20 起，`.markdownlint.json` 固化）

改任何 `.md` 后跑一次，**要求 0 错误**（`docs/archive/` 不参与：归档正文一字不改）：

```bash
npx --yes markdownlint-cli2 "AGENTS.md" "readme.md" "docs/**/*.md" "!docs/archive/**"
```

**必修项**（默认全开）：标题/围栏/列表/表格前后留一个空行（MD022/031/032/058）、代码围栏**必须写语言**（MD040，纯日志用 `text`）、列表符号统一 `-`（MD004）、表格分隔行统一 `| --- | --- |`（MD055/056）、行尾不留空格（MD009）、文件以单个换行结尾（MD047）、标题不跳级（MD001）。

**已豁免**（写进 `.markdownlint.json`，理由是这些规则与中文技术文档的既有写法冲突，不是"懒得改"）：`MD013` 行长（中文长句本来就超 80）、`MD033` 内联 HTML（文档里有 `<br>`）、`MD034` 裸 URL、`MD036` 用加粗当小标题、`MD042` 空链接（徽章）、`MD041` 首行标题、`MD046` 代码块风格（列表内嵌围栏会被误判成缩进代码）、`MD024` 重复标题（按同级判断，更新日志日期会重复）、`MD028` 引用块内空行、`MD029` 有序列表编号风格。

**`docs/copy-inventory/*.md` 是逐字素材**：文件里行首的 `*` / `+` / `##`、引用块内的列表、以及 `` `✓ ` `` 这类**结尾带空格的 code span** 都是**被照抄的原文**，不是格式问题。这类文件在头部用 `<!-- markdownlint-disable ... -->` 单独豁免，**别为了过 lint 去改里面的引文**——改了清单就不再是"逐字照抄"。

## 构建

```bash
./gradlew.bat :app:assembleDebug                 # 编译验证（无 CI，以此为准；两个 flavor 都编）
./gradlew.bat :app:testStandardDebugUnitTest     # 纯 JVM 单测（普通版）
./gradlew.bat :app:testCoexistDebugUnitTest      # 纯 JVM 单测（共存版，同源码）
./gradlew.bat :app:assembleRelease               # 正式包（R8 + ABI 分包，两个 flavor 都出）
```

**单测任务名带 flavor 前缀**（26.10.11 起引入 `standard` / `coexist` 两个 product flavor，见 `docs/architecture-map.md` §7.37）：`testDebugUnitTest` **已不存在**，写成它 Gradle 直接报任务找不到。产物也按 flavor 分目录：`app/build/outputs/apk/<flavor>/<buildType>/app-<flavor>-<abi>-<buildType>.apk`（发布附件里普通版会被 CI 改名回历史名 `app-<abi>-release.apk`，共存版是 `app-coexist-<abi>-release.apk`）。

**交互式构建 / 装包用 `build_tui.py`**（26.10.20 起，纯标准库、无第三方依赖）：`python build_tui.py` 进全屏 TUI —— 首两行切换「构建类型（Debug/Release）」与「发行渠道（普通版/共存版）」，下面是「构建 Debug / 构建 Release / **构建全部**（两渠道 × 两类型，4 个包一次出，带 `--continue` 所以某个变体失败其余照跑）/ **版本号 / 版本名**（`V`，直接改 `app/build.gradle` 的 `versionCode` / `versionName`，改完会询问是否同步改 `strings.xml` 的「【YY.MM.DD 本次更新】」锚点——不同步则 `check` / CI 的 `verifyVersionConsistency` 会失败，`assemble` 不受影响）/ 安装到真机 / 安装到模拟器」，安装时按设备 `ro.product.cpu.abi` 自动挑 ABI 分包（没有对应分包退通用包），包比源码旧会先自动构建。`↑↓` 选择、`←→` 切换、`Enter` 执行、`1-4` 直达、`A` 全构建、`V` 改版本、`R` 刷新设备、`C` 连接模拟器、`Q` 退出。非 TTY 环境（CI / 管道）自动降级为行式菜单；也可走参数：`--connect` / `--list-devices` / `--list-apks` / `--list-version` / `--build --type release --flavor coexist` / `--build-all`（四变体一次构建）/ `--install --target emulator` / `--version-name 26.10.20 --version-code 2610200 [--sync-anchor]`（可与 `--build` / `--build-all` 连用，先改版本再构建）/ `--dry-run`（只打印命令，ABI 也真读）。**调用的就是上面那几条 Gradle 任务**，所以任务名变化时要同步改脚本里的 `task_name()`。adb 从 PATH / `ANDROID_HOME` / `local.properties` 的 `sdk.dir` 里找。改版本只动 `build.gradle` 那一行、按原样保留换行（CRLF / LF 都行，写后回读校验）。

> **模拟器不自己出现在 `adb devices` 里是常态**：MuMu / 雷电 / 夜神这类要 `adb connect 127.0.0.1:<端口>`（MuMu 12 是 16384，老版 5555 / 7555）。脚本在「一台在线设备都没有」时自动扫这几个端口，也可按 `C` / `--connect` 手动触发。**别用 `ro.kernel.qemu` 判真机/模拟器**——MuMu 不写这个属性（实测为空），脚本改成看 AVD 序列号、`ro.hardware`、虚拟机 DMI（MuMu 报 `innotek GmbH` / `NiohBox`）、本机回环 TCP、x86 ABI 兜底。另外 MuMu 会同时开 5555 / 7555 / 16384 三个转发口，`adb devices` 会出现三条同一台机器，脚本按 `settings get secure android_id` 去重成一台。调试包签名与已装包不一致时（例如设备上是 release 包、要装 debug 包）adb 会拒装，脚本会提示先卸载（**会清空登录态**），别自动替用户卸。

多模块 `:app` / `:ijkplayer-java` / `:DanmakuFlameMaster` / `:brotlij`（后三个别乱动）。Gradle 8.11.1、AGP 8.5.2、Kotlin 2.0.0、JDK 17、minSdk 19 / compileSdk 34、`resConfigs 'zh'`。降 minSdk 时必须先看 `docs/architecture-map.md` §1.1 的兼容清单（依赖版本下限、`Build.SUPPORTED_ABIS`、`res/drawable-v21` 双份资源等）。

**Release 说明必须包含完整的本次更新说明，不许省略**：发布说明里要有 `strings.xml` 中 `update_log_current` 的全部条目（去掉 `<item>` 标签、保留「【YY.MM.DD 本次更新】」标题与编号）。**只留 `### APK 校验值（MD5）` 与 `<!-- update: … -->` 机器元数据不算合格**，拿 GitHub 自动生成的「Full Changelog」commits 摘要顶替也不算。这条已由 `build-release.yml` 保证：手工触发用输入的 `release_body`，**推 tag 时 `inputs.*` 恒空，改由 `.github/scripts/extract_update_log.py` 从 `update_log_current` 自动抽取**，抽不到就 `::error::` + 退出 1、拒绝发版（26.10.05 曾因没有这道兜底而漏掉说明）。**别把这段逻辑改回「只取 `inputs.release_body`」**。发完仍要回读 Release 正文确认条数对得上。

**发版与更新检查**：推 tag 或手工触发 `.github/workflows/build-release.yml` —— 构建签名 APK → 创建 GitHub Release 并上传全部附件 → **通知中转服务**（地址与共享密钥都在仓库 Secrets：`RELAY_URL` / `RELAY_SECRET`；请求体经 HMAC-SHA256 签名），由中转服务自己去 GitHub 拉附件并同步到 Gitee `zisekongling/bili-terminal-re`。**Action 侧不要直接访问 Gitee**（网络不通），参见该文件末尾的 Notify relay 步骤；**中转地址与密钥都不要写进代码库或日志**。版本号与是否强制更新由工作流从 `app/build.gradle` 读出、写进 Release 说明的机器可读元数据。客户端更新检查按「Gitee → GitHub」读两边的 `releases/latest`（见 `util/UpdateRelease.kt`），**客户端已不再读 config.json**。发版细则见 `.dsh/skills/rebili-version-release/SKILL.md`；漏同步时用 `.github/workflows/relay-notify.yml` 重新通知（幂等，重复通知无害）。**中转服务端有一份附件名白名单（`assets.include`），只同步里面列出的文件名**——新增发行渠道（如 26.10.11 的共存版 `app-coexist-*`）时必须同步把该渠道的 4 个附件名加进白名单并重启中转服务，否则该渠道在 Gitee 上取不到包、只能回落到 GitHub 源（26.10.11 首次发共存版就踩到过）。

> **老客户端迁移（一次性）**：26.10.02 及更早的客户端只认 123pan 上那份 `config.json`。发版时若把 `emit_config_json` 打开，工作流会额外产出一份**给老客户端**的 `config.json`（`downloadUrl` = Gitee 上 32 位包的直链、`forceUpdate` 与本次一致）并挂到 Release，需人工把它传到 123pan。**它只用于渠道切换那一次**，新客户端不读它。

> **只想拿包、不想发版**：手工触发 `build-release.yml` 时勾选 `build_only`（26.10.17 新增）。它照常跑单测 + `assembleRelease` + 整理发行 APK + 生成 MD5 并上传 artifact `release-apks`，但**跳过**「计算发行 tag 与发布说明」「创建 GitHub Release」「Notify relay」等全部发行动作——不产生 Release、不占 tag（release immutability）、不同步 Gitee，适合传应用商店或自己装。实现上由 job 级 `env.BUILD_ONLY` 归一化（**推 tag 时 `inputs.*` 恒空，必须显式判 `event_name`**），五处步骤条件前置 `env.BUILD_ONLY != 'true'`，并**比较字符串而非布尔**。「tag 留空」不是等价做法：那条路仍会组装发布说明，`update_log_current` 抽不出条目时会 `::error::` 退出。细节见 `docs/review/fix-progress.md` §3.25。

坑：`gradle.properties` **不再写死** `org.gradle.java.home`（26.10.20 起；旧版指向 `C:\Program Files\Java\jdk-17`，换台机器/换 JDK 发行版就报 `Value '...' given for org.gradle.java.home Gradle property is invalid`，整个构建起不来）——本机 JVM 不是 JDK 17 时在**用户级** `~/.gradle/gradle.properties` 里设，或让 `JAVA_HOME` 指向 17；代理配置在第 94-97 行**是注释状态**；`compileSdk 34` 需要 `platforms/android-34` + `build-tools/34.0.0`（缺了 AGP 自动下载，licenses 已接受时），`sdk.dir` 写在 gitignore 的 `local.properties`，release 签名也读它；依赖仓库已在 `settings.gradle` 前置 aliyun 的 `google`/`public`/`gradle-plugin` 镜像；`copyApkToDesktop` 未挂在 `assembleRelease` 上，需手动跑。

**更容易踩的坑（build cache 回放陈旧资源）**：`gradle.properties` 开着 `org.gradle.configuration-cache` 与 build cache。只要你改动 `res/` 的**文件集合**（新增/删除/移动资源文件），`:app:mergeDebugResources` 可能 `FROM-CACHE` 回放一份旧结果，症状是编译报莫名其妙的 `Unresolved reference 'R.layout.xxx'` 或新的 string/color 找不到，而源文件明明存在。此时必须：

```bash
./gradlew.bat :app:clean --offline --no-configuration-cache
./gradlew.bat :app:assembleDebug --offline --no-build-cache --no-configuration-cache
```

判断依据：`app/build/intermediates/merged_res/debug/**/` 里找不到该资源对应的 `.flat` 文件，但 `aapt2 compile` 单独编译它又是成功的。

注意两点：

1. **`clean` 和 `assemble` 必须分两次 Gradle 调用**。写在同一次调用里（哪怕顺序正确）可能因配置缓存复用而报 `ManifestMerger2$MergeFailureException: NoSuchFileException: .../navigation_json/debug/extractDeepLinksDebug/navigation.json`。
2. 光加 `--no-build-cache` 不够时，把 `--no-configuration-cache` 也带上——配置缓存会复用过期的任务图，让上面那条 `Unresolved reference 'R.layout.xxx'` 继续复现。

增量编译还可能单独坏掉，报 `org.jetbrains.kotlin.util.FileAnalysisException … FileNotFoundException: app\build\tmp\kotlin-classes\debug\…\Xxx.class`（某个 class 被删了但增量状态没更新）。同样是 `clean` 后重建解决。

**同一个坏状态的第二种长相（26.10.21 实测，更坑）**：某个 flavor 的 Kotlin 模块元数据被写成空文件，单测编译报一堆 `Unresolved reference`（顶层函数 / DSL / 扩展函数全解析不到，**类却能正常解析**），看着像主源码真的删了符号。判据是 `app/build/tmp/kotlin-classes/<flavor>Debug/META-INF/app_<flavor>Debug.kotlin_module` 的大小——正常 233 字节左右，坏的时候只有 24 字节。只删该 flavor 的 `kotlin-classes/<flavor>Debug` 与 `build/kotlin/compile<Flavor>DebugKotlin` 后重编即可恢复，不必整包 clean。

**量体积必须先 clean**：debug 包在**增量**构建下会产生大量 `classesN.dex` 分片，体积可以虚高 3~4 MB（实测同一份代码增量构建 19.96 MB、clean 构建 15.44 MB）。任何"改动前后比体积"的结论都必须在 `:app:clean` 之后分别测量。

**APK 体积构成（26.09.11 实测，release arm64 9.73 MB）**：native `.so` **67.3%**（`libijkffmpeg.so` 单文件就占一半）、dex 17.9%、`res/` 8.2%、`resources.arsc` 5.0%。**死代码删除对 release 体积几乎无影响**——R8 本来就会 strip 掉未引用的类；实测整轮死代码+死依赖清理只减了约 0.22 MB（2.2%）。真正的大头是 `libijkffmpeg.so`（5.16 MB），要动它得用 NDK 重编精简编解码器，属于另一件事。

## 改前必查的已知坑

完整清单见 `docs/architecture-map.md` 第 7 节。仍存在的：

- 视频卡片解析**重复 21 处**（`RankingApi`/`RecommendApi`×4/`WatchLaterApi`/`SearchApi`×3/`SeriesApi`/`FavoriteApi`×2/`UserInfoApi`/`HistoryApi`/`BangumiApi`/`MessageApi`×3/`DynamicApi`×2/`VideoInfo.java`），改一处要 grep 其余。旧文档写「7 份」、26.08 快照写「19 处」，26.10.04 实测 21 处（`SearchApi` 的番剧搜索分支与 `DynamicApi:826` 为漏计项）。
- `PlayerApi.java:308` 的 `fnvar` 应为 `fnver`；`DanmakuApi.java:93` 的 `segment_index` 起始值与注释不符（`:80` 的 javadoc 说从 0 开始，调用点 `:133`/`:144` 实际从 1 起）。
- `SettingsKeys.PLAYER` / `PLAY_QN` 已收敛（26.10.04 批次 4）：13 处字面量全部改调常量、`SharedPreferencesUtil.player` 死字段已删，**新代码不要再写 `"player"` / `"play_qn"` 字面量**；`SettingMainActivity.kt:118` 的 `"player"` 是分组 id 不是 SP 键，别顺手替换。其余键（`mid` 等）仍有字面量（详见 `docs/architecture-map.md` §7.12）。
- `BaseActivity.kt:282` 用 `if (this !is InstanceActivity) setTopbarExit()` 做**向下判断**——任何「让 `RefreshMainActivity` 继承 `RefreshListActivity`」的方案都会把顶栏行为从「打开菜单」变成「点击即返回」。
- **`cell_video_list` / `cell_dynamic_video` 的 id 是跨包事实协议**：改 id 会同时打破 `PrivateMsgAdapter`、`DynamicHolder`、`NoticeHolder`、`OpusContentAdapter`。
- **`notifyItemRangeInserted` 的起点由 adapter 的 `getItemCount()` 决定，`+1` 多数是"头部占位"**：`ReplyAdapter` / `UserDynamicAdapter` / 系列详情内部 adapter 的 `getItemCount()` 都是 `data.size + 1`，通知起点要 `sizeBefore + 1`；而 `VideoCardAdapter` / `ArticleCardAdapter` / `LiveCardAdapter` 无头部、起点就是 `sizeBefore`。**看到 `+1` 先读 `getItemCount()` 再判越界**（26.10.04 批次 3 修掉 4 处真 bug，详见 `docs/architecture-map.md` §7.9）。
- **崩溃页跑在 `:error_activity` 独立进程**（26.10.04 起）：`CatchActivity` 不经过 `PerformanceManager.init` / `ErrorCatch.init` / 未读轮询 / 更新检查，加依赖前先确认它不需要这些初始化；判进程用 `BiliTerminal.currentProcessName()`（读 `/proc/self/cmdline`，`minSdk 19` 用不了 API 28 的 `Application.getProcessName()`）。
- **设备档位参数一律从 `PerformanceManager` 取**（图片质量/宽度、分页大小）：`GlideUtil` 里的 `QUALITY_*` / `MAX_W_*` 四个常量已于 26.10.04 删除，**不要在任何调用点重新写死**，否则又变回"两处真相"（详见 `docs/architecture-map.md` §7.10）。
- **任何"把 APK 交给系统安装器"的路径都必须先过 `ApkVerifier.verify(context, apkFile)`**（26.10.04 批次 4 新增）：现有两条链路（`UpdateManager.downloadApk`→`installApk`、`DownloadActivity.installApk`）都已接线；下载链路上校验失败要**删掉残片**，否则下次 `Range` 续传会把它当成"已下载一部分"。判定为「包名一致 且 签名集合一致」，**读不到签名一律失败关闭**（详见 `docs/architecture-map.md` §7.11）。
- **私信发图（`msg_type=2`）的 content 有硬格式**（26.10.04 批次 5 新增）：`url` 必须是 B 站图床地址（否则 21037「图片格式不合法，不要调戏接口啦」），`width`/`height` 必带（缺了接口不报错、客户端显示异常），`size` 单位是 **KB**。组装一律走纯函数 `PrivateMsgApi.buildImageContent(...)`，别手拼 JSON（详见 `docs/architecture-map.md` §7.14）。
- **`model/Dynamic` 的 `stats` 可空，用前必须判空**（26.10.05 线上崩溃）：`DynamicApi.analyzeDynamic` 在 `DYNAMIC_TYPE_NONE`（动态已删除/被屏蔽）时会**在填充 `module_stat` 之前提前 return**，部分动态类型也没有 `module_stat`，所以 `stats` 会是 null（`userInfo` 则无条件赋值，非空）。渲染卡片时 `DynamicHolder` 自己判空，详情页这类「直接取字段」的消费点要调 `dynamic.ensureDetailFields()`（只补 null、不覆盖已有值）。**别在 `analyzeDynamic` 里统一补空 `Stats`**——`DynamicHolder` 正是靠 `stats == null` 来隐藏点赞区的。
- **通知 ID 不要重号**（26.10.04 批次 5 起有四处）：`DownloadService` = 1027、`PlaybackService` = 1028、`util/MsgNotifier.kt` 新消息 = 1029、同文件追番更新 = 1030。**新消息提醒只在"未读变多"时弹一次**（`MsgNotifier.shouldNotify`，纯函数有单测），触发点是 `BiliTerminal` 既有的未读检查，项目**没有也不需要 WorkManager/AlarmManager**；Android 13+ 的 `POST_NOTIFICATIONS` 在消息页申请，未授权时通知静默不弹（不是 bug）（详见 `docs/architecture-map.md` §7.15）。
- **追番更新提醒靠"最新集 id 快照"判据**（26.10.04 批次 5 的 C16）：只看同一部番的 `new_ep.id` 是否变化（不用总集数、更不用服务端的 `is_new`），快照是 `SharedPreferencesUtil.BANGUMI_UPDATE_SNAPSHOT` 里 `{"media_id": new_ep_id, …}` 的整份 JSON；**首次检查只写快照不提醒、这次新追的番不算更新、拉取失败绝不覆盖快照**（改这三条语义前先看 `util/BangumiUpdateChecker.kt` 与 `docs/architecture-map.md` §7.16）。拉取上限 10 页 × 30 条，`ps` 定义域就是 1-30。
- **`model/` 里 `VideoFolder`/`VideoMeta`/`LocalVideo` 实现 `Parcelable`，且字段名就是磁盘 JSON 存储格式**：删改字段必须两端同步。
- **`DownloadService` 已按关注点拆分**（26.10.04 批次 8 的 E2）：实现分居 `service/download/`——`DownloadPathSpec`/`DownloadProgressMath`（纯函数）、`DownloadProgressStore`（进程级状态，`internal object`）、`DownloadRepository`（DB）、`DownloadNotifier`（通知，普通 class 持 Service 引用），对外 `@JvmStatic` API 一字未改。改下载代码前记住三条：**`start(Long)` 的 `@JvmStatic @Synchronized`（锁 Companion）与 `DownloadProgressStore.speedLock` 是两把不得互换/合并的锁**；`pausedMap` 必须仍是同一个 `ConcurrentHashMap` 实例（`DownloadListActivity` 直接对它 add/remove）；`speedStr`/`isSpeedMode`/`activeDownloadsCount` 刻意保持**非 volatile**（既有数据竞争，别顺手加）（详见 `docs/architecture-map.md` §7.27）。
- `player/` 包不是公共层：`VideoPlayerCore.kt` 已是 `IjkPlayerBridge` 的功能超集，但**还不能直接替换**——`PlayerState`/`IjkOption` 仍定义在 `IjkPlayerBridge.kt:17-36`（删旧类前先搬家），且 `VideoPlayerCore.release()` 有一次性 `released` 标志，`onViewRecycled` 后复用会**泄漏 native 播放器**。动手前先决定它的去留，含糊着抽公共层会造出第 3 套实现。
- **手表右滑返回由 `android:windowSwipeToDismiss` 控制**，它在窗口层直接 `finish()`，**不走 `onBackPressed`**，所以只 override `onBackPressed` 拦不住。且 `onCreate` 里的 `setTheme(ColorScheme.themeResId(theme))` 会覆盖清单上声明的 `Theme.NoSwipe*` —— 要真正禁用它，必须调 `ColorScheme.themeResId(theme, noSwipe = true)` 换用 `Theme.*.NoSwipe.AppCompat`（issue #1 的根因）。
- **原生库加载一律走 `player/IjkNativeLibs.ensureLoaded(context)` 并判返回值**（26.10.19 线上崩溃 #48）：`loadLibrariesOnce` / `IjkMediaPlayer()` 失败抛的是 `UnsatisfiedLinkError`（`Error`，`catch (Exception)` 兜不住），且构造内部会二次加载库。该类已做「失败即锁死 + 诊断写 `Logu` + 按隐私开关上报」，`PlayerActivity` / `IjkPlayerBridge` / `VideoPlayerCore` / `ShortVideoPlayerActivity` 均已接线，**新代码不要再裸调 `loadLibrariesOnce` / `IjkMediaPlayer()`**（详见 `docs/architecture-map.md` §7.44）。
- **弹幕 XML 解析是懒执行的**（26.10.20 修）：交给 `DanmakuManager.createParser` 的流不能在该方法返回后关掉——真解析在 `DanmakuView` 渲染线程（`DrawTask.java:283`），`BiliDanmukuParser.parse()` 读到已关闭流会 `return null`，弹幕**静默全空**（26.10.02 的 `use{}` 就这样弄空了所有本地缓存视频的弹幕）。要传流就传 `ByteArrayInputStream`（close 是空操作），或先把内容读进内存（详见 `docs/architecture-map.md` §7.46）。
- **后台请求回主线程时，判活要写在 `runOnUiThread` 的 Runnable 内部**，且 fragment 侧要自己补 `isAdded && view != null`（`BaseFragment.runOnUiThread` / `SearchFragment.runOnUiThread` 自 26.10.20 起已在 Runnable 内部统一判活，但**直接 `CenterThreadPool.runOnUiThread { }` 的地方仍要自己补**，别指望投递时判活）。这是线上 NPE #43–#49 与 ISE #62 的形态：手机/手表都会中，与设备无关。**fragment 里取界面文案优先 `getAppContext().getString(...)`**：detached fragment 上调 `Fragment.getString(...)` 会抛 `IllegalStateException: Fragment … not attached to a context.`（#62 的栈就是 `getString→getResources→requireContext`），详见 `docs/architecture-map.md` §7.44。

## 原生库

`app/libs/{arm64-v8a,armeabi-v7a,mips,x86}/` 是已提交的 `.so`，日常别动；重建需 NDK r21e（见 `rebuild_all.sh` 等脚本）。
