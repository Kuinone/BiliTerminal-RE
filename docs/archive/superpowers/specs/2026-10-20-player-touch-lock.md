# 播放页触摸锁定（防误触）

> **【已归档 2026-10-27】** 文首「待用户签字后实施」已过时：功能已实现（`activity/player/PlayerLockPolicy.kt` + `PlayerLockPolicyTest`），用户口径见 `docs/FEATURES.md`。

- **日期**：2026-10-20
- **状态**：需求已与用户逐条确认（见 §8 答复原文 + 2026-10-20 修订），**待用户签字后实施**
- **涉及文件（预测）**：`res/layout/activity_player.xml`、`activity/player/PlayerActivity.kt`、`activity/player/PlayerLockPolicy.kt`（新增，纯函数）、`res/drawable/icon_player_lock.xml` / `icon_player_unlock.xml`（新增）、`res/values/strings.xml`、`util/SettingsKeys.kt`、`test/.../player/PlayerLockPolicyTest.kt`（新增）、`docs/FEATURES.md`

## 1. 目标

手表端播放视频时，手背/衣袖/手腕转动很容易误触屏幕：暂停、调进度、退出播放页。加一个**播放页内**的「锁定」开关，锁定后播放器不响应任何播放操作输入，必须通过**同一个锁定按钮**解锁。

## 2. 需求（与用户确认后的最终口径）

### 2.1 锁定按钮（同一个按钮既是锁定入口也是解锁入口）

- 播放页控制栏**左侧**新增竖排按钮组（与右侧「弹幕/旋转/更多」竖排对称），组内一个 28dp 的锁定图标按钮，竖直居中贴左。
- 未锁定时：该按钮随控制栏一起显示/自动隐藏（4 秒）；点击 = 进入锁定。
- 已锁定时：控制栏全部隐藏；**点击画面任意位置** → 只有这个锁定按钮（在原左侧位置、原尺寸）浮现出来；**再点这个按钮 = 解除锁定**。
- 不新增设置页开关、不新增菜单项。

### 2.2 锁定

- 点击锁定按钮**立即进入锁定状态**，不弹确认框。
- 进入锁定时立即隐藏：顶部标题/时间/电量、底部进度条与按钮组、右侧竖排与横向按钮组、倍速提示、字幕提示等**所有**控制元素（整个 `control_layout` 隐藏）。
- 画面与声音继续正常播放；进度上报、自动连播、弹幕显示等既有行为不变。

### 2.3 锁定期间的输入屏蔽

锁定后以下输入全部无效：

- 单击（不会显示控制栏，只会按 §2.4 浮现锁定按钮）、双击（不跳转进度/不还原缩放）；
- 长按（不进入 3x 倍速）；
- 左右滑动（不调进度）、捏合缩放、拖动画面；
- 点击任何控制按钮（此时它们已隐藏）；
- **播放器顶栏的退出功能**：顶栏左上角的返回/退出图标（`top` 里的 `arrow_back`）在锁定时随顶栏整体隐藏、不可见也不可点；即便有外部入口触发 `finish()`，也被 §4.4 的拦截兜底挡住，必须先解锁才能离开播放页；
- 表冠/方向键等播放器按键操作（`DPAD_LEFT/RIGHT/UP/DOWN`、`ENTER` 不再触发 seek/音量/暂停）★假设，待确认。

### 2.4 浮现锁定按钮与解锁

- 锁定状态下**点击画面任意位置** → 在左侧原位置浮现锁定按钮（图标切换为**开锁**样式，提示"再点一次即解锁"），其余控制元素不出现。
- 3 秒内无操作 → 按钮自动隐去，回到纯画面；再点画面可再次浮现。
- **点击浮现出的按钮 → 解锁**：按钮消失（或随控制栏恢复），控制栏恢复显示，并重新开始既有的 4 秒自动隐藏计时。
- 按钮浮现期间，点击按钮以外的区域无动作（不额外显示控制栏），等待自动隐去即可。

### 2.5 退出保护

- 锁定期间**不能离开播放页**：顶栏左上角的返回/退出图标（已随顶栏隐藏且禁用）、返回键、蓝牙/表冠返回、系统返回手势、手表右滑返回、任何外部 `finish()` 调用，全部不退出。
- 返回键/按键被拦截时，让锁定按钮浮现约 1.5 秒作为反馈（不弹 Toast），让用户知道"现在是锁着的、再点按钮可解锁"。
- 手表右滑返回在窗口层直接 `finish()`（不走 `onBackPressed`，`PlayerActivity.kt:560-569` 注释已说明），因此必须在 `finish()` 里拦截（见 §4.4）。

### 2.6 首次提示

- 首次进入锁定状态时显示一次提示文案（用户可见文案进 `strings.xml`）：「已锁定：点画面显示锁定按钮，再点按钮解锁」。
- 用 SharedPreferences 记录后不再显示（新键见 §4.6）。

### 2.7 生命周期

- 锁定状态**只属于本次播放页实例**：退出播放页自动解除，重新进入不锁定。
- 自动连播 / 换 P / 切清晰度会导致播放器重建（`onPrepared` 重新接线），此时**保持锁定**。
- 直播页（`isLiveMode`）同样支持锁定，行为一致。
- 与既有设置开关无关：`player_doubletap_seek`、`PLAYER_SCALE`、左右滑动调进度等开关保持各自语义，锁定时统一失效、解锁后恢复原行为。

## 3. 验收标准

功能验收（手表真机 / Debug 包优先；手机端不遮挡、不越界）：

1. 控制栏显示时，左侧出现锁定按钮；点它 → 顶栏、底部进度条与按钮、右侧按钮组、倍速/字幕提示全部立即消失，视频照常播放。
2. 锁定后单击画面：控制栏**不**出现，只在左侧原位置浮现锁定按钮（开锁图标）；3 秒不再操作后自动隐去。
3. 锁定后双击不跳转进度，长按不进入 3x 倍速，左右滑不调进度，捏合不缩放（画面尺寸不变）。
4. 锁定后顶栏左上角的返回/退出图标完全不显示、点不到；按返回键 / 蓝牙返回 / 手表右滑返回：均停留在播放页，且锁定按钮短暂浮现作为反馈。
5. 锁定后点击浮现出的锁定按钮：立即解锁，控制栏恢复显示，4 秒后按既有逻辑自动隐藏。
6. 首次锁定出现一次性提示；解锁后再锁定不再提示；杀进程重启后也不再提示。
7. 锁定后触发自动连播（或切清晰度导致重建）：仍保持锁定，控制栏不出现，仍需解锁。
8. 退出播放页后重新进入同一个视频：不处于锁定状态。
9. 直播页锁定/解锁流程与点播一致，不崩溃。
10. 圆形手表屏幕、方形手表屏幕、手机横竖屏下，锁定按钮与其浮现位置都在可视区域内，不压住进度条/按钮/倍速文字。
11. 未开启「左右滑动调节进度」「双击快进快退」时，锁定前后行为同样一致（锁定只做屏蔽，不改这些开关的语义）。
12. 锁定期间进度上报/观看历史正常（解锁退出后仍能续播）。

工程验收：

1. `./gradlew.bat :app:assembleDebug` 通过（standard / coexist 两个 flavor）。
2. `:app:testStandardDebugUnitTest` 与 `:app:testCoexistDebugUnitTest` 通过；新增 `PlayerLockPolicyTest` 覆盖状态机转移、按钮自动隐去判定、锁定期间输入屏蔽/退出拦截判定。
3. 用户可见文案全部进 `strings.xml`，无新增硬编码中文。
4. 新增图标为 vector drawable，不引入新第三方库、不新增 Activity/权限。
5. `docs/FEATURES.md` 同步该功能。

## 4. 设计要点

### 4.1 状态机（纯逻辑，抽 `PlayerLockPolicy`）

三态：

| 状态 | 含义 | 锁定按钮 |
| --- | --- | --- |
| `UNLOCKED` | 正常播放，控制栏/手势可用 | 随控制栏显示，闭锁图标 |
| `LOCKED_HIDDEN` | 已锁定，只有画面 | 隐藏 |
| `LOCKED_ICON_VISIBLE` | 已锁定，锁定按钮浮现中 | 显示，开锁图标 |

转移：

- `UNLOCKED` --点击锁定按钮--> `LOCKED_HIDDEN`（并触发首次提示判定）
- `LOCKED_HIDDEN` --点击画面--> `LOCKED_ICON_VISIBLE`（重启 3 秒计时）
- `LOCKED_ICON_VISIBLE` --3 秒超时--> `LOCKED_HIDDEN`
- `LOCKED_ICON_VISIBLE` --点击按钮--> `UNLOCKED`（控制栏恢复）
- 锁定期间 `onBackPressed` / `finish()` 被拦截时：`LOCKED_HIDDEN` → `LOCKED_ICON_VISIBLE`（1.5 秒后回 `LOCKED_HIDDEN`）；`LOCKED_ICON_VISIBLE` 保持并重置计时。

纯函数至少提供：`phaseAfterScreenTap(phase)`、`phaseAfterLockButtonClick(phase)`、`phaseAfterTimeout(phase)`、`phaseAfterExitAttempt(phase)`、`shouldBlockPlaybackInput(phase)`、`shouldSwallowExit(phase)`。计时数值（3s / 1.5s）用常量暴露，供单测断言。

### 4.2 视图

`activity_player.xml` 顶层 `root_layout` 内新增（**层级必须高于 `control_layout`**，且在 overlay 之后声明按钮，保证按钮画在 overlay 之上）：

- `control_layout`：沿用现有布局；锁定时整体 `GONE`。
- `lock_overlay`：全屏透明、`clickable=true`，默认 `gone`；锁定时 `visible`，拦截所有触摸；点击 → 按状态机浮现按钮。
- `left_control`（第四轮新增）：`LinearLayout` wrap×wrap、`alignParentLeft` + `centerVertical` + `marginStart 4dp`、`gravity=center`、`orientation=vertical`，**与 `right_control` 的竖排图标列对称**（右侧同 28dp 图标、同 4dp 边距、上下居中）；内部自上而下放 `lock_btn` 与 `text_speed`（第六轮：倍速文字从 `control_layout` 搬进来，改竖排「锁在上、倍数在下」）。今后再加左侧按钮直接往这个容器里塞。
  - **竖向中线对齐由代码补**（第五轮）：右列真正的上下锚点是 `top` 与 `bottom_control`——两者都在 `control_layout` 内部，位于 root 层的 `left_control` 在 XML 里引用不到，所以 `PlayerActivity.alignLeftControlToRight()` 在布局完成后量一次两列中线差、用 `translationY` 平移（解锁时重算一次）。不写死 dp，顶栏/底栏高度变了自动跟随。
- `lock_btn`：单个 `ImageButton`，28dp、`adjustViewBounds=true`、`scaleType="fitXY"`、`background=?android:attr/selectableItemBackground`（与右侧三个竖排按钮同规格），`src` 按状态切换闭锁/开锁图标；点击 → 按状态机锁定/解锁。它是**同一个按钮**，不复制第二份，只是换了容器。
- `text_speed` 的 `marginStart` 从 4dp 调到 36dp 避让（3x 倍速文字显示在左侧按钮列右侧；`btn_debug` 位置不变）。
- 两个图标（`res/drawable/icon_player_lock.xml` / `icon_player_unlock.xml`）是矢量图，路径**尽量铺满 960×960 视口**（锁体占宽 ~73%、锁梁顶到 y≈55）：同一 28dp 方框里，右侧 PNG 图标是满幅位图，矢量图若在视口内留白就会显得明显偏小（26.10.21 真机反馈后按视口修正）。
- **`text_subtitle` 从 `control_layout` 里搬出来**，改成 `root_layout` 的直接子节点（`alignParentBottom` + `centerHorizontal` + `marginBottom 76dp`＝底部控制栏 59dp + 进度文字约 17dp，视觉位置与原先「进度文字之上」等价）。原因：锁定时 `control_layout` 整体 `GONE` 会把字幕一起藏掉——这就是用户报的「锁定模式下不显示字幕」的根因；搬到外面后锁定/控制栏自动隐藏时字幕都照常显示。

### 4.3 触摸屏蔽

`lock_overlay` 覆盖在 `control_layout` 之上且锁定时 `control_layout` 为 `GONE`，`layout_control` 的单击/双击/长按/滑动/缩放手势与所有按钮点击天然收不到事件——不需要在 `setVideoGestures()` 里散落 `if (isLocked) return`。

### 4.4 退出拦截（本功能唯一技术风险点）

**三道防线，缺一不可**：① 锁定时 `control_layout` 整体 `GONE`，顶栏与左上角返回/退出图标不可达；② `onBackPressed()` 拦截按键/手势返回；③ `finish()` 拦截窗口层直接 finish（右滑返回、外部调用）→ 这三者共同保证「顶栏退出功能被禁用」的语义，而不只是把按钮藏起来。

- `control_layout` 置 `GONE`：顶栏（`top`）、底部按钮、右侧按钮组、进度条一并隐藏，顶栏返回/退出**按钮层面就点不到**。
- `onBackPressed()`（`PlayerActivity.kt:469`）：`isLocked` 时直接 `return`，并按 §4.1 让按钮浮现 1.5 秒。
- `finish()`（`PlayerActivity.kt:4284`）：锁定时置 `pendingFinish = true` 后 `return`（不调 `super.finish()`），**不要**直接丢弃：解锁时若 `pendingFinish` 为真，立刻走正常 `finish()` 流程（暂停 + 报进度 + 回传结果），避免系统原因触发的 finish 被永久吞掉。
- `onKeyDown()`（`PlayerActivity.kt:2654`）：锁定时跳过 DPAD/ENTER 分支，直接 `super.onKeyDown`。
- 预案：若真机发现右滑被拦后窗口出现退场动画/状态异常，退路是播放页**无条件**用 `ColorScheme.themeResId(theme, noSwipe = true)`（代价：播放页右滑返回永久失效），实施时先在真机验证再决定是否需要。

### 4.5 文案与图标

- 新增 `res/drawable/icon_player_lock.xml`（闭锁）、`icon_player_unlock.xml`（开锁）：vector，24dp、viewport 960、`fillColor="#ffffff"`，风格对齐现有 `icon_debug.xml`。
- **图标语义＝当前状态**（26.10.21 用户反馈原先把开启/关闭画反）：未锁定显示开锁图标（点它去锁），锁定后浮现的按钮显示闭锁图标（点它解锁）。`contentDescription` 仍按「点了会发生什么」写（未锁定＝"锁定播放画面（防止误触）"，锁定＝"点击解锁播放画面"）。
- `strings.xml` 新增：锁定按钮的无障碍描述（闭锁/开锁两种状态）、首次提示文案；命名沿用现有前缀（`desc_` / `msg_`，实现时对照既有 player 相关 key 确认）。

### 4.6 存储

| SP 键 | 类型 | 含义 |
| --- | --- | --- |
| `player_lock_hint_shown` | boolean | 首次锁定提示是否已展示，缺省 false |

键名一律走 `SettingsKeys` 常量，不写字面量。

### 4.7 不做的

- 不加设置页开关 / 菜单项 / 新 Activity / 新权限 / 新第三方库。
- 不做定时锁、密码锁、锁定时隐藏弹幕、锁定时熄屏。
- 不改播放/暂停/自动连播/进度上报语义。
- 不改既有双击、缩放、滑动调进度开关的默认值。

## 5. 真机验收步骤

```bash
./gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/standard/debug/app-standard-*-debug.apk
# 1) 播放任意视频 -> 控制栏出现 -> 点左侧锁定按钮
# 2) 点画面：左侧浮现开锁按钮；等 3 秒自动消失；再点画面再次浮现
# 3) 双击/长按/左右滑/捏合 -> 画面与播放进度均不响应
# 4) 返回键 + 手表右滑 -> 不退出播放页，按钮短暂浮现
# 5) 点浮现的按钮 -> 解锁，控制栏恢复，4 秒后自动隐藏
# 6) 首次锁定看提示；解锁后再锁看是否不再提示
# 7) 开自动连播，锁定后等切集 -> 仍锁定
# 8) 退出播放页再进入 -> 未锁定
adb shell run-as com.RobinNotBad.BiliClient cat shared_prefs/*.xml | grep player_lock_hint_shown
```

> 本机 `adb devices` 当前无设备；真机项需用户协助，模拟器上至少覆盖流程 1–5、8。

## 6. 验收风险登记

- 右滑返回拦截依赖 `finish()` 拦截（窗口层直接 finish），需要真机确认无退场动画残留 / 窗口状态异常；退路见 §4.4。
- 锁定后系统主动 `finish()`（如系统回收）会被延迟到解锁，属于已知取舍。

## 7. 用户答复原文（可追溯）

- 入口：`1，但是是左侧`（选右侧竖排按钮组方案，但改放左侧）
- 解锁（初答）：`锁定之后再次点击视频播放器，只会浮现出锁图标，点击后可以解锁`
- 退出保护：`全部屏蔽，必须先解锁才能离开播放页`
- 生命周期：`切集（自动连播/换 P/切清晰度）后保持锁定，退出播放页自动解除`
- 锁定后显示：`全部控制栏/顶栏/进度条隐藏，只留半透明锁图标 + 首次提示`
- **修订（2026-10-20 第二轮）**：`左侧锁定按钮在锁定后点击时显示，重新点击此按钮，即可解除锁定`
  → 取消"屏幕正中浮现独立锁图标"的方案，改为**同一个左侧锁定按钮**在锁定后被点出、再点即解锁；按钮位置固定在左侧，不居中。
- **修订（2026-10-20 第三轮）**：`锁定按钮同时禁用播放器顶栏的退出功能`
  → 锁定期间顶栏（含左上角返回/退出图标）整体隐藏、不可达，并且 `onBackPressed` + `finish()` 双重拦截，任何路径都无法在锁定时离开播放页（见 §2.3 / §2.5 / §4.4）。
- **修订（2026-10-20 第四轮，真机验收后）**：`你看看这右边是怎么放图标的，再看看你放的，另外修复所模式下不显示字幕的bug` → 追问「要竖着排」后选择 `左侧单独一列，与右侧按钮组对称`
  → ① 锁定按钮不再直接挂在根布局上，改成左侧竖排容器 `left_control`（对齐右侧 `right_control` 的竖列范式：28dp 图标、4dp 边距、上下居中、`fitXY`）；② 字幕 `text_subtitle` 搬出 `control_layout`，修掉「锁定后字幕消失」（见 §4.2）。
- **修订（2026-10-21 第五轮，真机截图批注）**：`看好我的标注`（截图上圈出「左列上方空白」与「x 1.0 倍速文字」两处）
  → 左列此前用 `centerVertical` 相对**整屏**居中，比右侧三图标的中线低约 35px（右列锚在顶栏与底部控制栏之间）；改为 `alignLeftControlToRight()` 运行时对齐右列中线，左锁图标与右侧三图标同高，倍速文字原位不动。
- **修订（2026-10-21 第六轮，真机截图批注）**：`没对齐` → `锁在上面，倍数在下面`；随后 `还有我感觉你锁的状态搞反了，就是开启和关闭的哪个`
  → ① 左侧列改为竖排两行：**锁按钮在上、倍速文字在下**（`text_speed` 从 `control_layout` 搬进 `left_control`，`btn_debug` 的 `layout_above` 改锚 `bottom_control`；锁定态单独把倍速文字 GONE，因为它已不随控制栏隐藏）；② 图标语义改为**当前状态**：未锁定＝开锁图标、锁定后浮现＝闭锁图标（此前按"点击后会发生什么"画，用户认为开启/关闭反了）。
- 仍待确认：§2.3 表冠/方向键是否也在锁定时屏蔽（当前按"屏蔽"写）；§2.4 浮现期间再点画面是否重置 3 秒计时（当前按"重置"写，即"每次点画面都刷新计时"）。
