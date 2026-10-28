# 下载引擎优化方案（对标 PiliPlus / bilimiao2 / BBDown / DownKyi）

> **【已归档 2026-10-27】** §3.4「不做合并」已被 `docs/superpowers/specs/2026-10-25-dash-merge-single-file.md` 反转；分片看门狗 / Range 校验已于 26.10.25 落地（fix-progress §3.63）。仍未做的断点续传、仅 WiFi、磁盘预检、弹幕分段已回迁 `docs/review/fix-progress.md` §2.2。

- **调研日期**：2026-10-13
- **范围**：ReBiliClient 视频缓存链路（`service/DownloadService.kt` + `service/download/*` + `api/PlayerApi.java`）的**下载引擎**层面缺陷盘点，与同类 B 站客户端/下载器做对照，给出分优先级改造方案。
- **不在范围**：UI 交互、本地播放器解码、番剧/直播业务逻辑、APK 更新下载链路（`util/UpdateManager.kt`，与视频缓存无关）。
- **来源分级**：
  - **[一手源码]**：本仓库源码行号（实际读到的行）；`bggRGjQaUbCoE/PiliPlus`、`10miaomiao/bilimiao2` 的源码行（`raw.githubusercontent.com` 本地下载后读取）。
  - **[官方文档]**：B 站开放接口 / Android 官方文档。
  - **[二手]**：博客、CSDN 等，仅作"有人这么做"的佐证，不作结论依据。
- **本文只做调研与方案设计，不改动任何代码。**

---

## §1 结论速览

1. **本项目最缺的不是"并发"，而是"断点续传"。** 现有实现里**没有任何一处**把已下载字节当续传基准：`downFileNormal` / `downFileSpeedSingle` 用 `FileOutputStream(file)` 截断重写，`downFileSpeedSeg` 用 `raf.setLength(totalSize)` 预分配、`downFileSpeed` 入口还先 `resetFile(file)` 删文件。**暂停、杀进程、断网、切清晰度重下，一律从 0 开始**。[一手源码] `DownloadService.kt:945/970`、`:1071/1090`、`:1038`、`:1133/1137`。

2. **分片下载是"看起来高级、实际更脆"**：片数 = `min(aria2_split, 总大小/2MB)`，默认 `aria2_split=5` → 一个 100MB 文件只有 5 片、每片 20MB；**并行度被片数卡死在 5**；任一片失败即 `addDownloadedBytes(-segBytes)` 后**整文件单线程从头重下**。[一手源码] `DownloadProgressMath.kt:30-34`、`DownloadService.kt:1195-1207`。

3. **对标产品（PiliPlus / bilimiao2）反而是"单连接 + 真续传"**：都只开一条连接/流，但请求头带 `Range: bytes=$received-`、以追加模式写文件，天然支持跨会话续传；PiliPlus 额外把**视频流与音频流拆成两条并发**。[一手源码] `PiliPlus/lib/services/download/download_manager.dart:31-101`、`bilimiao2/.../download/DownloadManager.kt:63-115`。

4. **失败代价过大**：下载中一次网络抖动 → `runDownloadSection` 把任务置 `error`（终态，不被 `getFirst` 拾取，需用户手动点），且半成品被删。[一手源码] `DownloadService.kt:1026-1028`、`:519-531`、`DownloadRepository.kt:50`。

5. **失败语义前后不一致**：取流阶段 `IOException` 置回 `none`（可自动重试），下载阶段 `IOException` 置 `error`（终态）。同一类错误两种归宿。[一手源码] `DownloadService.kt:595-599` vs `:771-775`。

6. **完全没有网络感知**：全工程无 `NetworkCapabilities` / `TRANSPORT_WIFI` / `getUsableSpace` / `StatFs` 命中 → **没有"仅 WiFi 下载"，没有磁盘空间预检**。[一手源码] 全仓 grep 零命中。

7. **弹幕走单文件 XML**：`comment.bilibili.com/{cid}.xml`，B 站对 XML 已做限制（大视频弹幕不全）；PiliPlus 走 gRPC `dmSegMobile` 分段 + 4 路并发。[一手源码] `PlayerApi.java:112`、`PiliPlus/download_service.dart:308-354`。

8. **双真相**：`download` 表（SQLite）与目录内 `.DOWNLOADING`/`.quality`/`.video_meta.json` 两套状态可能不一致。PiliPlus/bilimiao2 是「文件系统即数据库」单一真相。[一手源码] `DownloadSqlHelper.kt` + `DownloadRepository.kt` vs `PiliPlus/download_service.dart:73-114`。

9. **DASH 进度语义错**：视频 0→100% 后再音频 0→100%，进度条"满→归零→再满"；且视频/音频**串行**下载，总耗时 = 两者之和而非最大值。[一手源码] `DownloadService.kt:645-665`。

10. **`downFileSpeed` 用 HEAD 探 `Content-Length`**：多一次往返，且部分 CDN 对 HEAD 返回 405。可用 `GET + Range: bytes=0-0` 替代。[一手源码] `DownloadService.kt:1044-1061`。

11. **值得保留的现状**：`safeReplaceTemp` 的"临时文件→备份→替换"原子性是**正确的**，改造时不能丢；`.DOWNLOADING` 标记 + `cleanDownloadTempFiles` 的"绝不递归删任务目录"是**修过的真实数据丢失 bug**，续传改造要沿用该安全边界。[一手源码] `DownloadService.kt:786-817`、`FileUtil.java:75-88`。

12. **优先级建议**：**P0 = 续传 + 分片健壮化 + 失败语义统一**（这三项决定"能不能用"）；P1 = 网络感知 / 磁盘预检 / 弹幕分段 / 音视频并行 / 进度语义；P2 = 单真相重构 / 边下边播 / 限速。

---

## §2 现状盘点：ReBiliClient 下载引擎的具体问题

链路与职责划分见 `docs/architecture-map.md` §7.27，此处只列**引擎层**问题，均带源码行号。

### 2.1 断点续传：完全没有

| 路径 | 写法 | 续传 |
| --- | --- | --- |
| `downFileNormal`（`:945`） | `FileOutputStream(file)`（`:970`）——截断重写 | ❌ |
| `downFileSpeedSingle`（`:1071`） | `FileOutputStream(file)`（`:1090`）——截断重写 | ❌ |
| `downFileSpeedSeg`（`:1133`） | 入口 `resetFile(file)`（`:1038`）+ `raf.setLength(totalSize)`（`:1137`）——清空重来 | ❌ |

`recoverStuckSections()`（`:535`）只把遗留 `downloading` 改回 `none`，**不保留任何已下载字节**；`pauseDownload`（`DownloadProgressStore.kt:67`）也只置标志让循环退出，半成品被 `:1026-1028` 删除。

**后果**：一个 500MB 的视频下到 90% 时切后台被系统回收 → 下次从 0 开始。

### 2.2 分片：粒度随文件线性增长，失败即全废

```text
segmentCount = min(maxSplit, max(1, totalSize / 2MB))   // DownloadProgressMath.kt:31
```

- `maxSplit` 默认 5（`Aria2Util.getSplit()`）。
- 10MB 文件 → 5 片（每片 2MB）；100MB 文件 → 5 片（每片 **20MB**）；1GB 文件 → 5 片（每片 **200MB**）。
- 每片一个裸 `Thread`（`:1155`），并发度 = 片数，**固定 5**，无法单独调。
- 任一片 3 次重试后仍失败 → `anyFailed` → 回滚字节 → `downFileSpeedSingle` 从头重下（`:1195-1207`）。
- 分片阶段不落盘独立文件，直接 `RandomAccessFile.seek(start)` 写最终文件 → 无法"只重下失败片"。

### 2.3 失败处理与重试

- 网络类失败（HTTP 非 2xx、下载不完整、IO 异常）在下载阶段一律置 `error`（`:771-775`、`:519-531`），**无自动重试**，且半成品被删。
- 取流阶段 `IOException` 却置 `none`（`:595-599`）→ 下次批次自动重试。**同类错误两种归宿**。
- `error` 不被 `getFirst`（只取 `state="none"`）拾取（`DownloadRepository.kt:50`），必须用户在下载列表手动点（`DownloadListActivity.kt:174-177`）。
- 分片有 3 次重试（`:1226`），但整批失败仍回退重下。

### 2.4 其他

- **无网络类型判断**：移动网络下也全速跑，无"仅 WiFi"。
- **无磁盘空间预检**：`raf.setLength(totalSize)` 在空间不足时抛 `IOException`，任务直接 `error`，用户只看到"下载失败"。
- **弹幕单文件 XML**（`PlayerApi.java:112`），无分段。
- **视频/音频串行**（`:645-665`），DASH 下总耗时翻倍。
- **进度语义**：`downFile` 的 `baseProgress/endProgress` 在 DASH 下视频与音频各自 0→1，进度条来回跳。
- **HEAD 探测**（`:1044-1051`）：多一次往返；`Content-Length` 缺失或 HEAD 被拒即退化为单线程。
- **`getFirst` 只取一条**：`parallelDownload` 用 `Semaphore` 限流，但每次 `getFirst` 都是新查询，中途新增任务能被拾取（这是好的），但 `state` 更新与拾取之间存在 TOCTOU 窗口，靠 `:470` 的"立即标记 downloading"缓解。

---

## §3 同类实现对照

### 3.1 对标产品做法

| 维度 | **ReBiliClient** | **PiliPlus**（Flutter） | **bilimiao2**（KMP/Compose） | **BBDown / DownKyi**（桌面） |
| --- | --- | --- | --- | --- |
| 任务调度 | 串行 或 并行（Semaphore，1–10） | **串行队列**（`curDownload` + `waitDownloadQueue`） | 单任务 | 多任务并发 |
| 单流断点续传 | ❌ 截断重写 | ✅ `Range: bytes=$received-` + `writeOnlyAppend` | ✅ `RANGE: bytes=$len-$size` + `FileOutputStream(file, true)` | ✅ aria2 原生 |
| 续传时的 URL | — | **每次重新取流**（`getVideoUrl`）后 Range 续 | 每次重新取流 | 重新取流 |
| 分片并行 | ✅ 但 ≤5 片、失败整批回退 | ❌ 单连接 | ❌ 单连接 | ✅ aria2 多线程 |
| 音视频并行 | ❌ 串行 | ✅ `_downloadManager` + `_audioDownloadManager` | ✅ | ✅ |
| 弹幕 | XML 单文件 | **gRPC protobuf 分段，4 路并发** | — | XML / protobuf |
| 元数据 | SQLite + 目录标记（**双真相**） | `entry.json` / `index.json`（**文件系统即库**） | JSON entry | JSON |
| 状态机 | `none/downloading/paused/error` | `wait/getDanmaku/getPlayUrl/downloading/audioDownloading/completed/pause/failDownload/failDanmaku/failPlayUrl` | `STATUS_*` 枚举 | 多状态 |
| 失败保留断点 | ❌ 删半成品 | ✅ 保留文件 | ✅ 保留（`file.length()` 即进度） | ✅ |
| 自动重试 | 仅分片 3 次 | ❌（用户手动） | ❌ | ✅ |
| 仅 WiFi | ❌ | ✅（`Pref`） | — | ✅ |
| 磁盘预检 | ❌ | ✅（`guessedTotalBytes`） | — | ✅ |
| 边下边播 | ❌ | ✅（`canPlayInAdvance`） | — | — |
| 进度节流 | 通知 1s / 列表 400ms | `onReceiveProgress` 每秒 | `throttleFirst(200ms)` | — |

### 3.2 PiliPlus 的续传写法（可直接借鉴）

```dart
// lib/services/download/download_manager.dart:31-101（节选）
final file = File(path);
int received = file.existsSync() ? await file.length() : 0;      // ① 以文件长度为准
final sink = file.openWrite(
  mode: received == 0 ? FileMode.writeOnly : FileMode.writeOnlyAppend,  // ② 追加
);
final response = await dio.get(url, options: Options(
  headers: {'range': 'bytes=$received-'},                         // ③ Range 续传
  validateStatus: (s) => s == 416 || (s >= 200 && s < 300),       // ④ 416 = 已完整
));
final contentLength = data.contentLength + received;              // ⑤ 总长 = 已收 + 本次
```

**关键点**：① 进度真相在**文件长度**上，不在内存/DB；④ `416` 是"已经下完"而非错误；⑤ 总长要加回 `received`。整个引擎 112 行，没有分片。

### 3.3 bilimiao2 的续传写法

```kotlin
// bilimiao-download/src/commonMain/.../download/DownloadManager.kt:63-115（节选）
var downloadLength = info.progress                       // 已下载长度
if (downloadLength > 0 && info.size != 0L) {
    if (info.size == downloadLength) { status = COMPLETED; return@flow }  // 已完成
    request.addHeader("RANGE", "bytes=$downloadLength-${info.size}")      // 续传
}
fileOutputStream = FileOutputStream(file, true)          // 追加
```

同样以"已下载长度"为续传基准，且**已完成判定 = 长度相等**。

### 3.4 桌面下载器（BBDown / DownKyi）的差异

桌面端普遍**内置 aria2 多线程 + ffmpeg 合并**：分片由 aria2 管理（含独立分片文件与断点），音视频下载完用 ffmpeg 合并为单 MP4。本项目已**明确删除** `util/MediaMerger.kt`、不做合并（保留 `video.mp4` + `audio.m4a` 双文件直供播放器），所以"多线程 + 合并"这条路线**不应照搬**——照搬会引入 ffmpeg 依赖与合并耗时，与手表端资源约束冲突。

**可借鉴的只有**：分片独立落盘（可单独重试/续传）、失败只重下失败片。

---

## §4 关键差距分析

**本项目引擎的能力错位**：唯一比对标产品"先进"的是分片并发，但这恰恰是最脆的一环；而所有对标产品都有的**续传**，本项目为零。即：

```text
本项目：  分片并发 ✅   +  续传 ❌  +  失败保留 ❌  →  看着快，实际一断全废
PiliPlus：分片并发 ❌   +  续传 ✅  +  失败保留 ✅  →  看着朴素，但断了能接着下
```

对"手表 + 移动网络 + 易被系统回收"的场景，**续传的价值远高于并发**。因此改造顺序必须是先补续传，再谈并发质量。

---

## §5 优化方案

### P0-1 引入统一的断点续传写入器（最高优先）

**目标**：任何中断（暂停 / 杀进程 / 断网 / 切清晰度重下）后，已下载字节不丢。

**设计**：

1. 新增 `service/download/RangeResumableWriter.kt`（纯逻辑 + IO，可单测部分抽纯函数）：
   - `already = file.length()`；
   - 发请求带 `Range: bytes=$already-`，`validateStatus` 接受 `200/206/416`；
   - `206` → `RandomAccessFile(file,"rw").seek(already)` 追加；
   - `200`（服务端忽略 Range，如部分 CDN）→ **必须截断重写**（此时 `already` 作废，进度归零）；
   - `416` → 视为"文件已完整"，直接返回成功；
   - 总长 = `Content-Range` 解析出的 total，或 `body.contentLength() + already`。
2. **恢复时必须重新取流**：B 站流 URL 有效期约 120 分钟（`docs/bilibili-api-notes.md`），过期即 403，不能复用库里/上次的 URL。现有 `processDownloadSection` 每次执行都重新 `getVideoDash`/`getVideo`，天然满足；只需保证"续传发生在取流之后"。
3. **进度落盘**：在 `.video_meta.json`（或新增 `.download_state.json`）里记 `downloadedBytes`/`totalBytes`，供下载列表显示断点位置；但**续传真相以 `file.length()` 为准**（与 PiliPlus 一致），元数据仅用于展示。
4. `downFileNormal` / `downFileSpeedSingle` / `downFileSpeedSeg` 三处统一改用该 writer。

**验收**：下到 50% 杀进程 → 重进列表点继续 → 日志显示 `Range: bytes=<非0>-`，最终文件完整可播。

**风险**：部分 B 站 CDN 节点对 `Range` 支持不稳定（需真机验证 `Accept-Ranges`）；服务端返回 `200` 时必须正确回退到截断重写，否则文件会损坏（前半段旧数据 + 后半段新数据拼接）。

### P0-2 分片健壮化：独立落盘 + 只重下失败片

**目标**：分片失败不再"整批回退重下"。

**设计**（两档，建议先做 A）：

- **A（最小改动）**：分片失败时**只重试该片**（提高重试次数到 5 次、指数退避），全部片成功后才算完成；仍失败则**保留已成功片**，下次续传时跳过已完成的片。
  - 需要每片记录"已完成"标志 → 新增分片状态文件 `.segments.json`（`[{i, start, end, done}]`）。
- **B（更彻底）**：每片写独立 `.part{i}` 文件，全成功后按序拼接成 `video_new.mp4`；失败只重下对应 `.part{i}`。
  - 代价：拼接需一次额外磁盘读写（手表端谨慎）。

**注意**：现有 `:1196-1201` 的 `addDownloadedBytes(-segBytes)` 字节回滚逻辑在 A 档下**仍然需要**（只重试单片时不要把失败片的字节计入全局统计）。

### P0-3 分片粒度固定 + 并发独立可控

**目标**：大文件切成足够多的小片，失败代价可控；并发度可单独设置。

**设计**：

- `segmentCount` 改为**固定片大小**（建议 4MB，可设 `download_segment_size`），片数 = `ceil(totalSize / 4MB)`，硬上限（如 64）防爆；
- **并发**用独立设置 `download_concurrency`（默认 4，范围 1–8），以 `Semaphore` 限流，而非"片数=线程数"；
- 小文件（≤ 一个片大小）直接单线程，不分片。

**风险**：B 站 CDN 对单 IP 并发连接有限制，并发过高可能被限速/403。**默认值必须保守**，且真机实测后再调。

### P0-4 失败语义统一 + 自动重试

**设计**：

- 定义 `DownloadError` 分类：
  - **可重试**：连接超时、读超时、5xx、连接中断、下载不完整 → 自动退避重试（3 次，间隔 2s/5s/10s），**保留断点**，期间状态 `retrying`；
  - **终态**：403 防盗链（URL 过期 → 重新取流后重试一次）、404/-404（视频不存在）、磁盘满（ENOSPC）、文件系统错误 → 置 `error` + 中文原因。
- 统一两处 `IOException` 归宿（`:595-599` 与 `:771-775`），按分类而非按阶段决定。
- `error` 保持终态，但列表页明确提供"重试"入口（已有，补文案）。

### P1-1 网络感知：仅 WiFi 下载

- 新增设置 `download_wifi_only`（默认 false，手表用户流量敏感可开）。
- 下载前 `ConnectivityManager` + `NetworkCapabilities.hasTransport(TRANSPORT_WIFI || TRANSPORT_ETHERNET)` 判断；不满足 → 状态置 `waiting_wifi` 挂起（不失败），网络恢复后自动继续。
- 需注册 `NetworkCallback` 监听网络变化触发恢复。
- 设置项按 AGENTS.md 改三处：`SettingsKeys.kt`、设置页 `SettingSection`、`SettingsIndex.kt`。

### P1-2 磁盘空间预检

- 取流后已知 `Content-Length`（视频 + 音频 + 附件），或退化为"预估大小"（PiliPlus 的 `guessedTotalBytes` 思路：按码率 × 时长估算）。
- `File.getUsableSpace()` 判断，不足 → 直接终态失败并提示"存储空间不足，需约 XXX MB"。
- 应在 `raf.setLength(totalSize)` 之前判断。

### P1-3 弹幕改分段拉取

- 优先走 protobuf 分段接口 `https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid={cid}&segment_index={n}`（每段约 6 分钟），多段并发拉取后合并。
- 项目已有 `brotlij`（brotli 解压）与 `DanmakuFlameMaster`（解析），复用即可。
- 保留现有 XML 作为兜底（接口失败时回退）。

### P1-4 DASH 进度语义修正

- 按字节加权：`overall = (videoDone + audioDone) / (videoTotal + audioTotal)`。
- `downFile` 的 `baseProgress/endProgress` 改为按"当前阶段在总字节中的占比"计算，而非视频/音频各自 0→1。

### P1-5 视频/音频并行下载

- DASH 下开两个并发流（视频 + 音频），总耗时从 `video + audio` 降为 `max(video, audio)`。
- 依赖 P0-1 的 writer 可复用（两条流各写各的文件，互不干扰）。
- 进度聚合依赖 P1-4。

### P2-1 单一真相：元数据收敛（较大重构）

- 向 PiliPlus 学习：`entry.json` 承载 `state`/`downloadedBytes`/`totalBytes`/`isCompleted`，启动扫目录重建列表，**删掉 SQLite `download` 表**。
- 收益：消除双真相不一致、跨设备可移植。
- 代价：现有 `download` 表迁移、`DownloadSqlHelper` 删除、多处调用点改造。
- **建议独立排期**，不与 P0 混做。

### P2-2 其他加分项

- **边下边播**（PiliPlus `canPlayInAdvance`）：下载中可播放已下部分，需播放器支持本地部分文件 + Range 读取。
- **限速**：令牌桶，省流量场景。
- **批量/番剧整季下载**。
- **失败任务批量重试**入口。

### 明确不做

- **不做 ffmpeg 音视频合并**：`util/MediaMerger.kt` 已删，双文件直供播放器是既有取舍；引入 ffmpeg 与手表端资源约束冲突（`docs/architecture-map.md` §7.35）。
- **不做外部 aria2（JSON-RPC）**：`Aria2Util` 已剥离外部对接，只保留参数读取，不回退。

---

## §6 落地顺序（小步提交）

按 AGENTS.md「小步提交、每步可独立验证」：

| 步骤 | 内容 | 验证 |
| --- | --- | --- |
| 1 | P0-1 续传写入器（先只接 `downFileNormal`） | `assembleDebug` + 单测（`Range` 头构造、416/200/206 分支的纯函数）+ 真机杀进程续传 |
| 2 | P0-1 接入 `downFileSpeedSingle` | 同上 |
| 3 | P0-4 失败分类与自动重试 | 单测（错误码 → 可重试/终态映射）+ 真机断网重试 |
| 4 | P0-3 分片粒度固定 + 并发独立设置 | 单测（`segmentCount` 新语义）+ 真机大文件 |
| 5 | P0-2 分片独立落盘 / 只重下失败片 | 单测 + 真机断网分片恢复 |
| 6 | P1-2 磁盘预检 | 单测（空间判断）+ 真机塞满磁盘 |
| 7 | P1-1 仅 WiFi | 真机切网 |
| 8 | P1-4 + P1-5 DASH 进度与音视频并行 | 真机观察进度条 |
| 9 | P1-3 弹幕分段 | 真机比对弹幕完整性 |
| 10 | P2-1 单真相重构（独立排期） | 迁移测试 |

**每一步都要同步**：`docs/architecture-map.md` §7.27（锁与状态契约变了必须更新）、`docs/FEATURES.md`（新增用户可见开关）、`docs/bilibili-api-notes.md`（新接口结论）。

---

## §7 一句话总结

**本项目下载引擎把力气花在了"分片并发"上，却漏掉了所有对标产品都有的"断点续传"——先补续传（P0-1）和失败健壮性（P0-2/3/4），再谈并发质量（P1），才是正确的投入顺序。**
