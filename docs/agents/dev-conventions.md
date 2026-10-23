# 开发硬约定（界面 / 菜单 / 设置 / 弹窗 / 文案）

> 本册是写界面与功能时的实现细则；`AGENTS.md` 只放每次都要遵守的最短约束。
> **做到哪节先读哪节**，不要凭记忆。改任何非平凡代码前还要读 `docs/architecture-map.md`。

## 列表页

- 列表页继承 `RefreshMainActivity`（菜单入口页）或 `RefreshListActivity`（返回式）；加载完必须 `setRefreshing(false)`，否则翻页永久卡死。`onLoad(page)` 的 page 已自增。
- `activity_simple_refresh.xml` 里的 `filterBar`/`sortBar`/`manageBar`/`groupBar` 都是列表的**兄弟节点**（不是列表项），不会随列表滚走。想让某条**滚动时自动收回**，在子类里调 `setupAutoHideBars(条…)`（`RefreshListActivity`，**默认不启用**）；**别把「可见性在运行时才变化」的条放进去**（如收藏夹的多选条 `manageBar`）——展开时会把它强行点亮。判据抽在 `util/view/ScrollRetractDecider.kt`（纯函数，有单测）：**收回随时可以，展开只允许在列表到顶（`!canScrollUp`）时**——条的高度动画会顶动列表内容，中途展开会和滑动手势打架（真机反馈过，别把「向上滚就展开」的旧口径改回去）。

## 新增菜单页

- 改三处：`MenuActivity.btnNames`、`MenuConfig.ALL_ITEMS`、`AndroidManifest.xml`。
- **新菜单 key 对老用户不会自动出现**——`MenuConfig.loadEnabled` 对已存的 `menu_enabled` 直接返回，只能靠「菜单设置」手动开。
- 想让所有人都能立刻用到入口，别加菜单项，挂到既有页面（如动态页动作卡片）。

## 竖向列表里的横滑翻页

- **别让外层 `ViewPager2` 自己抢手势**：它判横向的门槛是 `dx > dy`（1:1），竖向滑动稍一倾斜就被它 intercept 成翻页（短视频页真机实测「几乎每次滑动都翻页」）。
- 正确做法是 `pager.isUserInputEnabled = false`，每页根布局用 `ui/widget/SwipePageLayout`（`|dx| > |dy| * 2` 才算横滑，之后 `beginFakeDrag`/`fakeDragBy` 跟手、松手 `endFakeDrag` 吸附；超过 18% 屏宽还没吸附则补 `setCurrentItem`）。
- 反编译结论：`beginFakeDrag()` 不受 `isUserInputEnabled` 影响；`fakeDragBy(f)` 内部是 `mRequestedDragDistance -= f` 再 `RecyclerView.scrollBy(...)`，**传负值 = 向后一页**（手指左移时 `ev.x - lastX` 正好为负，直接用这个差值即可）。`endFakeDrag()` 的吸附是异步的，松手后立刻读 `currentItem` 还是旧页。细节见 `docs/review/fix-progress.md` §3.48 / §3.49。

## 新增设置项与设置子页面

- 新增设置项改三处：`util/SettingsKeys.kt`、设置页 `SettingSection`、`activity/settings/SettingsIndex.kt`。
- 新增设置子页面有两种形态，**先想清楚用哪种**：
  1. **独立 Activity**（如「菜单设置」「我的页面设置」）——有自定义交互/独立布局时用，改三处：`SettingGroupActivity` 对应分组加 `nav`、`SettingsIndex` 加可搜索条目、`AndroidManifest.xml` 注册。
  2. **本页的另一个 `group_type` 分组**——只是若干列表项、无自定义交互时用（如 `GROUP_APPEARANCE`「外观设置」），只需：`buildContent` 加分支 + 写一个 `buildXxxGroup()` + 在上级分组加 `nav` + `SettingsIndex` 加条目。**不需要新 Activity、manifest、布局**。对用户同样是独立一屏。

## 外观设置

- 外观设置（配色 / 卡片圆角 / 字体）走 `ui/appearance/` 三模块：**模块只放候选值与纯函数，写入一律走 `AppearanceManager`**（它负责递增外观版本号）；模块里别做几何计算，也别在热路径上缓存。细则见 `docs/architecture-map.md` §8.7。

## 弹窗

**统一走 `util/TerminalDialog.kt`，禁止在业务代码里裸写 `AlertDialog.Builder`。**（26.10.05 起完成迁移，见 `docs/archive/review/dialog-redesign-progress.md`；技术结论已并进 `docs/architecture-map.md` §8.7.0）

- **禁止的写法**：`AlertDialog.Builder(context).setItems(...)` / `.setSingleChoiceItems(...)` / `.setMultiChoiceItems(...)` / `.setMessage(...).setPositiveButton(...)`，以及任何自己 `inflate` 一个带选项列表的弹窗布局。全工程现有 17 处此类调用已在 26.10.05 全部迁移，`app/src/main/java` 下除 `TerminalDialog.kt` 自身外**不应再出现 `AlertDialog` 字样**（用 `grep -r "AlertDialog" app/src/main/java` 自检）。
- **为什么**：弹窗的花色不只取决于调用点，更取决于主题层。7 族主题此前都没声明 `alertDialogTheme`，于是弹窗走 MaterialComponents 自带的 alert overlay，正文色取自全工程 0 处声明的 `colorOnSurface`（回退近白 → 用户看到的「灰底白字」），破坏性按钮缺 `colorError` 语义，而**唯一跟随主题的 `colorAccent` 恰好是 `#FF6699` 荧光粉**（→「粉按钮」），背景又是方角无描边（→「像安卓原生的一样」）。裸 Builder 无论怎么写都会踩这一套。
- **正确写法**：菜单型 `TerminalDialog.menu(context, title, items, danger)`、单选型 `TerminalDialog.singleChoice(context, title, items, checked)`、确认型 `TerminalDialog.confirm(context, title, message, confirmText, cancelText, confirmIsDanger)`、纯提示 `TerminalDialog.alert(context, title, message)`。破坏性项（删除/取消收藏之类）**必须**进 `danger`，别让用户靠文案猜。
- **给新主题补 overlay**：`ThemeOverlay.<X>.Dialog` 里 `colorSurface` / `colorOnSurface` / `colorError` / `android:background=@drawable/dialog_background` 四项缺一不可，并在该主题里加 `<item name="alertDialogTheme">`。漏写不会崩（`TerminalDialog` 取属性带兜底默认值），但弹窗会退回 Material 默认配色。
- 颜色**只读 `?attr/`**（`colorPrimary` / `colorOnSurface` / `colorError`），`TerminalDialog.kt` 内不写任何色值常量。

## 页面级排序 / 分区配置

- 沿两个既有范式：`MenuConfig`（启用/未启用）与 `MySpaceConfig`（主列表/更多列表）——都是纯 Kotlin 单一数据源 + JVM 单测 + 复用 `MenuSettingAdapter` 式双分区拖拽写法。

## 请求与解析分离

- 请求与解析分离，纯解析抽成 `static`/`object` 函数并补 JVM 单测（参考 `HotSearchApi.parseHotSearch`；需要 SharedPreferences 时注入 `SharedPreferencesUtil.sharedPreferences = FakeSharedPreferences()`——该假实现是共享助手 `app/src/test/…/util/FakeSharedPreferences.kt`，**别再抄一份**）。

## 用户可见文案

- **用户可见文案一律进 `app/src/main/res/values/strings.xml`**（26.10.19 起从「只有设置页用」改为全仓迁移，台账与规范见 `docs/copy-inventory/STRINGS-MIGRATION.md`）。命名沿用既有前缀：`pagename_` 页面标题 / `btn_` 按钮 / `text_` 界面文本 / `desc_` 设置说明 / `msg_` 一次性提示。**新代码不要再硬编码中文**；迁移未覆盖的老代码可以留着，但改到哪块顺手迁哪块，别新增第二份。写法：Kotlin `getString(R.string.x)`；`companion object` / `object` 里用 `BiliTerminal.context!!.getString(...)`；Java 用 `BiliTerminal.context.getString(...)`（别忘了 import `R` 与 `BiliTerminal`）。
- **唯一例外：会被 JVM 单测直接调用的纯函数不要读资源**。本仓库不用 Robolectric，单测里没有 Context，`getString` 必然空指针（26.10.19 B6 一次挂掉 6 个用例）。这类「服务端错误码 → 人话」的映射统一放 `api/ApiErrorText.kt`（`object` + `@JvmStatic`，同包 Java 直接调），要改那几句改它一处。
