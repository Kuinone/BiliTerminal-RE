# AGENTS.md

本文件是**每次会话都该读的最小约束与索引**。细则按领域拆到 `docs/agents/`：**做到哪块就读哪份**，别在这里重复展开。

## 语言约定

**与用户交流、代码注释、文档一律中文。**

## 先读架构地图

改任何非平凡代码前先读 **`docs/architecture-map.md`**：真实分层、基类体系、API 层映射表、UI 模板、逐条坑点。其他参考：**`docs/README.md`（文档总索引）**、`bilibili-API/`（B 站接口文档快照）、`docs/bilibili-api-notes.md`（接口与风控常识，改 API 前读）、`docs/FEATURES.md`（用户可见功能总表）、`docs/review/agent-test-guide.md`（**用 adb / agent 驱动 Debug 包测试的手册**，加调试能力前先读）、`docs/superpowers/specs/`（功能设计文档，写新功能前读）、`docs/tutorial-system-redesign.md`（教程系统重做中——**改教程前先读它**）。**`docs/archive/` 里全是历史资料，记录的是当时、不是现状**，别照着它改代码。

## 分册索引（按"我正在做什么"读）

| 你正在做… | 读这份 |
| --- | --- |
| 写界面 / 列表 / 菜单 / 设置 / 弹窗 / 文案 | `docs/agents/dev-conventions.md` |
| 动清单、网络域名、备份、调试入口 | `docs/agents/security.md` |
| 加/改「不进公开仓库」的功能（`private/`、`OptionalFeatures` 门面、发版注入） | `docs/optional-features.md` |
| 构建 / 单测 / 装包 / 遇到构建怪错误 | `docs/agents/build.md` |
| 发版 / 更新日志 / Release 说明 | `docs/agents/release.md` |
| 准备提交、准备说"应该没问题" | `docs/agents/workflow.md` |
| 改 API 解析 / 播放器 / 弹幕 / 下载等易踩区 | `docs/agents/pitfalls.md` |
| 收尾（改文档、跑 markdownlint） | `docs/agents/docs.md` |

## 项目概况

- RE:哔哩终端（ReBiliClient），第三方 B 站**手表**安卓客户端，`main` 分支。
- 典型 vibe coding 产物：代码大量由 AI 生成。**改动前先读源码核实，注释和文档都可能是错的。**
- 版本号按 YY.MM.DD（`app/build.gradle`）。

## 架构（三条与直觉相反的）

1. **入口是 `BiliTerminal.kt`**（26.09.10 由 `BiliTerminal.java` 迁移而来）。原 `BiliTerminalApp.kt` 已于 26.10.02 整体删除——它从未被实例化，只因 `SplashActivity` 的 UETool 逻辑引用其静态方法而残留；那些方法与常量已移入 `BiliTerminal.kt` 伴生对象。全局 Context 取 `BiliTerminal.context`——它在 companion 里用 `@JvmField` 暴露成**静态字段**，所以 Java 侧仍是 `BiliTerminal.context` 的字段读法，Kotlin 侧按需 `!!`。
2. **没有 DI / Retrofit / ViewModel**——26.09.11 死代码清理后这已是**事实**而非"死依赖残留"：Hilt、Retrofit、kotlinx-serialization、Jetpack Navigation、protobuf-javalite、geetest sensebot、asynclayoutinflater、cardview、lifecycle-viewmodel-ktx 等**已全部从 `app/build.gradle` 移除**，`ksp` 与 `kotlin.plugin.serialization` 两个插件也一并去掉。原先 24 个空目录（`di/`、`network/`、`data/`、`ui/base` 等）已删除。新功能写进 `api/` + `activity/`，沿用静态方法 + `org.json`。
3. **只有一条链**：`activity/` → `api/`（全同步阻塞）→ `util/` → `model/`。导航由 `MenuActivity.btnNames` + `util/MenuConfig.kt` 决定。

## 每个改动都守

- 网络请求包在 `CenterThreadPool.run { }` 里。
- **小步提交**：改动切成能独立验证的小步（`assembleDebug` + 相关单测通过）再做下一步，别一次堆大量改动。**做完自己 commit + push 到 `main`**，不等催；commit message 与 co-author 格式见 `docs/agents/workflow.md`。
- **改完必须自测，不许只说"应该没问题"**：涉及播放器 / 网络 / UI 时序的改动装到 MuMu 模拟器（`adb connect 127.0.0.1:7555`）跑一遍，把实测结论写进 `docs/review/fix-progress.md`；纯文案 / 单文件小逻辑跑通 `assembleDebug` + 单测即可（判据见 `docs/agents/workflow.md`）。
- 用户可见文案一律进 `app/src/main/res/values/strings.xml`（写法与纯函数例外见 `docs/agents/dev-conventions.md`）。
- 不轻易引入新第三方库；**一切功能优先考虑手表端**，手机等设备只是顺便适配。
- **改完同步文档**：修 bug / 关待办 → `docs/review/fix-progress.md`；改架构 / 基建 → `docs/architecture-map.md`；新增用户可见功能 → `docs/FEATURES.md`；接口 / 风控结论 → `docs/bilibili-api-notes.md`；本文件描述的内容变了 → 改对应分册。改任何 `.md` 后跑 `npx --yes markdownlint-cli2 "AGENTS.md" "readme.md" "docs/**/*.md" "!docs/archive/**"`，**要求 0 错误**（规则、豁免与逐字素材示例见 `docs/agents/docs.md`）。

## 安全红线（常驻摘要，细则见 `docs/agents/security.md`）

- 新增 Activity 一律 `android:exported="false"`；Release 包仅 `SplashActivity` 与 `GetIntentActivity` 导出。
- 只走 https；明文域名白名单只放行 `bilibili.com` / `hdslb.com` / `bilivideo.com` / `afdiancdn.com`，加域名要写原因。
- `android:allowBackup` 保持 `false`，并保留主清单的 `tools:replace="android:allowBackup"`。
- 调试页只进 Debug 包，入口用编译期常量 `BuildConfig.DEBUG` 包住；Debug 包的外部可达组件必须挂 `android.permission.DUMP`。
- 调试开关必须"改完即生效"（统一走 `BiliTerminal.applyLogSwitches()`）。

## 私有功能仓库（不进开源）

本机还有一份**不公开**的私有功能源码仓库 `zisekongling/BiliTerminal-RE-private`（private）。
它不属于 GPL 发布内容，也不随公开仓库分发；公开仓库里只有反射门面和条件挂点。

- **本地位置**：公开仓库根目录下的 `private/`（已 gitignore）。没同步时构建出来就是公开版。
- **同步**：本机 `python build_tui.py --sync-private`（TUI 里 `P`，行式菜单 `[p]`）；CI 由
  `.github/workflows/build-release.yml` 的「注入可选私有功能源码」一步用 Secrets 拉取。
- **机制**（构建期注入点、门面规则、挂点写法）：`docs/optional-features.md`。
- **硬要求**：没有 `private/` 时公开仓库必须能独立编译、单测全绿；私有功能整个缺失也不许崩。

### 对 agent 的态度

1. 私有代码按「可以读、可以改、**绝不能外传**」对待：不要贴进公开仓库、issue、PR、评论、
   文档、回复或任何外部服务（含截图与日志）。
2. 公开侧只保留「门面 + 条件挂点」（如 `util/OptionalFeatures.kt`）；算法、交互流程、数据格式、
   文案细节一律留在 `private/`。**「换个说法提一句」同样禁止**——改名、拆片段、塞进注释/测试/
   截图都是变相公开。
3. 改私有功能就改私有仓库，与公开仓库分开提交；commit 格式与公开仓库一致
   （见 `docs/agents/workflow.md`），一条提交说清一件事。
4. 不许 `git add -f` 绕过 `.gitignore`；新增文件先判断它属于公开还是私有。
5. 私有功能的实测记录写私有仓库的 `README.md`，**不写**公开的 `docs/review/fix-progress.md`。
6. 拿不准某个改动该进哪一边时，**先问用户**，不要自行决定。

### 私有仓库的规矩

- 仓库根目录就是 `private/` 的内容：`manifest/`、`res/`、`src/main/java/…`、`keep.pro`、`README.md`。
- **只放源码**：截图、日志、APK、临时产物一律不进（放 `outputs/`，已 gitignore）。
- 默认分支 `main`。本机 `private/` 保留 `.git` 可以继续提交；CI 检出后立刻删 `.git`
  （token 不许留在磁盘上）。
- 同步只需要只读 PAT（细粒度，Contents: Read）；写权限只在用户自己的机器上。
- 硬约束：包名固定 `com.RobinNotBad.BiliClient.feature`；Kotlin 不自动解析父包的 `R`，
  用到资源必须显式 `import com.RobinNotBad.BiliClient.R`。
- 私有 Activity 同样遵守安全红线：`android:exported="false"` + `android:screenOrientation="locked"`。
- 私有类只被反射调用，R8 保留规则写在私有仓库的 `keep.pro` 里。

## 构建

```bash
./gradlew.bat :app:assembleDebug                 # 编译验证（无 CI，以此为准；两个 flavor 都编）
./gradlew.bat :app:testStandardDebugUnitTest     # 纯 JVM 单测（普通版）
./gradlew.bat :app:testCoexistDebugUnitTest      # 纯 JVM 单测（共存版，同源码）
./gradlew.bat :app:assembleRelease               # 正式包（R8 + ABI 分包，两个 flavor 都出）
```

单测任务名必须带 flavor 前缀（`testDebugUnitTest` 已不存在）；交互式构建 / 装包用 `python build_tui.py`；环境坑、缓存回放、增量编译坏掉、量体积与 APK 构成见 `docs/agents/build.md`。

## 原生库

`app/libs/{arm64-v8a,armeabi-v7a,x86}/` 是已提交的 `.so`，日常别动；重建需 NDK r21e（见 `rebuild_all.sh` 等脚本）。
