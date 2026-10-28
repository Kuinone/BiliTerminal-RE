# 下载链路缺陷逐一排查（进度不准 / 音频重复 / 完整性）

> **【已归档 2026-10-27】** C3、B1 播放侧、A1/A2（DASH 分段进度常量 + 分片实时计数，§3.36）、A5（按任务取阶段文案）已修；26.10.25 起 DASH 下载合并成单文件，B1/B2/B3 只在旧缓存或合并失败的双文件目录上仍可达。剩余项已回迁 `docs/review/fix-progress.md` §2.2。

- **排查日期**：2026-10-13
- **触发**：用户反馈「下载进度不准确」「替换后音频重复」等多项问题。
- **范围**：`service/DownloadService.kt`、`service/download/*`、`activity/video/local/*`、`adapter/video/CacheListAdapter.kt`、`activity/player/PlayerActivity.kt`（外部音轨部分）。
- **性质**：排查记录与后续方案。**C3 完整性与 B1 播放侧防重已完成代码修复**，现状与验证以 `docs/review/fix-progress.md` §3.12/§3.13 为准；B1 下载事务及其余条目尚未修复。引擎层面的架构优化另见 `docs/superpowers/specs/2026-10-13-download-engine-optimization.md`。
- **来源**：源码行号为排查时位置，修复后会偏移。条目描述的是可达的代码风险，音频重复等具体真机症状仍需复现，不能据此认为全部因果关系已证实。

---

## §1 结论速览

| # | 现象 | 根因（一句话） | 严重度 |
| --- | --- | --- | --- |
| **C3（已修）** | 下载"成功"但文件是半截 | 已补高速单连接流写入与 EOF/字节完整性检查，详见 `docs/review/fix-progress.md` §3.12 | 数据完整性 |
| **B1** | 播放时音频重复/回声 | 靠"audio.m4a 是否存在"猜 `video.mp4` 有无内嵌音轨；猜错就 ijkplayer 内嵌音轨 + 独立 MediaPlayer 双路同时出声 | 🔴 高 |
| **A1** | 进度条来回跳 3 次 | DASH 下"附件 / 视频 / 音频"三段**各自 0→100%** | 🟠 高 |
| **A2** | 进度条长时间不动后突跳、剩余时间虚高 | 分片模式 `downloadedBytes` **只在整片完成后才累加** | 🟠 高 |
| **A5** | 并行下载时阶段文案串味 | 每个任务的阶段文案读的是 Service 的**全局 `state` 字段** | 🟡 中 |
| **A6** | 通知栏"总进度"会回退 | DASH 任务进音频阶段时 `progress` 从 1.0 归零，总进度把它当"进度减少" | 🟡 中 |
| **A3** | 分片失败时进度先满后退 | 回退重下时 `setDownloadProgress(..., baseProgress, ..., 0L, ...)` | 🟡 中 |
| **B2** | 多P 删除删错文件 | `videoFileList.removeAt(position)` 未考虑 DASH 双文件占 2 个元素 | 🟡 中 |
| **B3** | 虚拟合集播放无声 | 虚拟合集只取 `.mp4`，丢弃 `audio.m4a`，而 DASH 的 `video.mp4` 无音轨 | 🟡 中 |
| **A4** | 未知大小时进度卡 90% 后突跳 100% | 伪进度封顶 0.9，成功后直接置 `endProgress` | 🟢 低 |

**处理优先级建议**：C3（数据完整性）→ B1（用户直接可感）→ A1/A2（用户直接可感）→ 其余。

---

## §2 缺陷 C3：下载"成功"但文件不完整（最严重）

### 现象

网络中途被 CDN 静默截断（连接正常 EOF、但字节数不足）时，任务被标记为**下载成功**，`.DOWNLOADING` 被摘掉，本地列表出现一个播到中途就卡的坏文件。

### 根因

`downFileNormal` 有完整性校验（`DownloadService.kt:1009-1012`）：

```kotlin
} else if (TotalFileSize > 0 && totalDown < TotalFileSize) {
    fileIncomplete = true
    result = DownloadPathSpec.ERR_NETWORK
}
```

但 **`downFileSpeedSingle` 完全没有这个校验**（`:1122-1124`）：

```kotlin
val singleResult = if (isPaused(sectionId)) DownloadPathSpec.ERR_PAUSED
    else if (started) DownloadPathSpec.NORMAL
    else DownloadPathSpec.ERR_UNKNOWN
```

只要循环正常结束、没暂停、`started` 仍为 true，就返回 `NORMAL` —— **不比对 `downloaded` 与 `effectiveTotalSize`**。

而 `Aria2Util.isEnabled()` 默认 `true`（`Aria2Util.java:19`），所以**默认走的正是这条没有校验的路径**；`downFileSpeedSeg` 的完整性校验（`:1195`）也只在"总大小已知且支持 Range"时才生效，小文件/不支持 Range 时仍落到 `downFileSpeedSingle`。

### 处理办法

在 `downFileSpeedSingle` 返回前补上与 `downFileNormal` 对称的校验：

```kotlin
val singleResult = when {
    isPaused(sectionId) -> DownloadPathSpec.ERR_PAUSED
    !started -> DownloadPathSpec.ERR_UNKNOWN
    effectiveTotalSize > 1 && downloaded < effectiveTotalSize -> DownloadPathSpec.ERR_NETWORK  // 新增
    else -> DownloadPathSpec.NORMAL
}
```

并在失败时删除半成品（`downFileNormal` 的 `:1026-1028` 逻辑要同样补上，`downFileSpeedSingle` 当前**没有**失败清理）。

**验证**：单测覆盖"读到的字节数 < Content-Length → 返回 ERR_NETWORK"；真机用限速/断网制造截断，确认任务被标记为失败而非成功。

---

## §3 缺陷 B1：替换后音频重复

### 现象

本地缓存视频播放时**两个音轨同时出声**（重复/回声），常见于"重新下载 / 切换清晰度"之后。

### 机制链（已逐行确认）

1. `LocalListActivity.scanVideos()`（`:150-175`）：

   ```kotlin
   val videoFile = File(video, "video.mp4")
   val audioFile = File(video, "audio.m4a")
   ...
   // :173-175
   if (videoFile.exists() && audioFile.exists() && mediaFile == videoFile) {
       localVideo.videoFileList.add(audioFile.toString())   // ← 两个都塞进列表
   }
   ```

   **判据只有"两个文件是否都存在"**，无法区分 `video.mp4` 是"DASH 无音轨视频流"还是"含音轨的完整 MP4"。

2. `CacheListAdapter.playVideo()`（`:451-465`）见 `size == 2` 即认定 DASH 双文件：

   ```kotlin
   val videoUrl = ...firstOrNull { it.endsWith(".mp4") }...
   val audioUrl = ...firstOrNull { it.endsWith(".m4a") } ?: ""
   playerData.videoUrl = videoUrl
   ...
   player.putExtra("audio_track_url", audioUrl)     // ← 当作外部音轨
   ```

3. `PlayerActivity`（`:3804` → `initAudioTrack():2670-2695`）用**独立的 Android `MediaPlayer`** 播放外部音轨，**与 ijkplayer 并行**：

   ```kotlin
   audioPlayer = android.media.MediaPlayer().apply {
       setDataSource(url); prepare(); seekTo(video_now); start()
   }
   ```

   ijkplayer 继续播 `video.mp4`。

**结论**：若 `video.mp4` **自带内嵌音轨**，则 ijkplayer 播内嵌音轨 + MediaPlayer 播 `audio.m4a` → **双路同时出声**。

> **26.10.13 已实测证实（不再是推断）**：`qn=64`（720P）走 `fnval=1` 取回的 `durl` MP4 容器内 `trak=2`（`vide`+`soun`、`mp4a`+`esds` 齐全）= **音视频合一**；而 `fnval=16` 的 `dash.video` 才是 `trak=1` 的纯视频。详见 `docs/bilibili-api-notes.md` §3 第 12 条。

### 产生"含音轨 video.mp4 + audio.m4a"组合的路径

| 路径 | 说明 | 位置 |
| --- | --- | --- |
| a | MP4 下载完成后删除 stale audio **失败**（`delete()` 返回值未检查） | `:680-681`、`:753-754` |
| b | **`audio_only` 下载只写 `audio.m4a`，不删旧 `video.mp4`** | `:634-644`、`:707-717` |
| c | DASH 下视频替换成功、音频替换失败 → **新纯视频** + 旧音频（不同步风险，不直接证明双声） | `:664-665`、`:737-738` |
| d | 历史遗留：`MediaMerger` 时代合并出的 `video.mp4` 含音轨，`audio.m4a` 残留 | 已删模块的产物 |

### 处理办法（三层，建议全做）

**第 1 层 · 播放侧（已修，真机未验）**
实际采用已 prepared 的 IJK `trackInfo`，不再另开 MediaExtractor 扫描。内嵌音轨优先；确实有视频轨且无音轨才启外部音频；解析失败/空列表是 UNKNOWN 而不是 false，避免误启动第二路。**播放时绝不删除文件**。判据和验证见 `docs/architecture-map.md` §7.4、`docs/review/fix-progress.md` §3.13。

同轮核实并修复另外两条重复音频来源：旧外部播放器在重载时未释放便被覆盖引用；新对象 prepare 失败时尚未保存引用导致泄漏。现在重载/切听视频/重试/出错/切页/销毁统一清理，新对象创建后即保存引用，迟到回调只认当前实例。

**第 2 层 · 下载侧**

- MP4 路径删除 stale audio 时**检查返回值**，失败则重试一次并记日志；
- `audio_only` 路径补删同目录 `video.mp4`；
- DASH 替换保证原子性：video 替换成功但 audio 失败时，**回滚 video**，不留"新视频+旧音频"的不一致组合。

**第 3 层 · 元数据侧**
在 `.video_meta.json`（`model/VideoMeta.java`）新增 `hasEmbeddedAudio` 字段，下载时写入真相；播放侧优先读它，**不再靠文件存在性猜**。这是根治——因为"猜"本身就是错的。

---

## §4 缺陷 A1：DASH 进度条来回跳 3 次

### 根因

`processDownloadSection` 的 DASH 分支（`:645-666`）给视频和音频**各自**用了完整区间 `0f → 1.0f`：

```kotlin
result = downFile(url_video, videoTmp, downloadSection.id, 0f, 1.0f)   // 0 → 100%
...
setDownloadProgress(downloadSection.id, 0f, "下载音频")                 // 归零
result = downFile(url_audio, audioTmp, downloadSection.id, 0f, 1.0f)   // 0 → 100%
```

`downloadAttachments` 的 DASH 分支（`:848-872`）同样是 0 → 0.4（封面）→ 0.6（字幕）→ 0.95 → 1.0（弹幕）。

**完整序列**：`0→100%（附件）` → `0→100%（视频）` → `0→100%（音频）`。进度条来回满三次，这就是用户看到的"进度不准确"。

对照 MP4 路径（`:671`、`:863`、`:868`）反而是对的：附件只占 0.05→0.20，视频 0.20→1.0。

### 处理办法

给 DASH 三个阶段分配**固定权重区间**，而不是各自走满：

- 附件 `0.00 → 0.10`
- 视频 `0.10 → 0.55`
- 音频 `0.55 → 1.00`

更精确的做法是**按字节加权**：附件先按"预估大小"占 10%，剩余 90% 按 `videoContentLength : audioContentLength` 分配（两者都能从 HEAD/响应头拿到，见 `:1052-1058`）。这样进度单调递增且与实际字节成比例。

**验证**：真机下载一个 1080P 视频，确认进度条从 0 单调走到 100%，中途不回落。

---

## §5 缺陷 A2：分片模式进度滞后 + 剩余时间虚高

### 根因

`downFileSpeedSeg` 里两个计数器**语义不一致**：

- `addDownloadedBytes(read)` —— 每读 64KB **实时**累加（`:1249`，用于速度采样）；
- `totalDownloaded.addAndGet(written)` —— **只在整片下载成功后**才累加（`:1259`）。

而写入进度映射的是后者（`:1164-1169`）：

```kotlin
percent = DownloadProgressMath.progressForBytes(totalDownloaded.get(), totalSize, baseProgress, endProgress)
setDownloadProgress(sectionId, actualProgress, ..., totalDownloaded.get(), totalSize)
```

**后果**：

- 5 片各 20MB 时，进度条会"20MB 一跳"，中间长时间不动；
- `DownloadProgressStore.getActiveRemainingBytes()`（`DownloadProgressStore.kt:136-145`）用 `totalBytes - downloadedBytes` 算剩余，`downloadedBytes` 滞后 → **剩余时间严重虚高**；
- 列表底栏的 ETA（`DownloadListActivity.kt:296-303`）同样偏大。

### 处理办法

每个分片维护一个 `AtomicLong` 记录"该片已写入字节"，进度用 **Σ(已完成片) + Σ(进行中片已写)** 聚合，而不是只算完成片。或简单些：在 `downloadSegment` 里把每 64KB 的写入同时累加到一个共享的 `liveDownloaded` 计数，进度读它，完整性校验仍用 `totalDownloaded`。

---

## §6 缺陷 A5：并行下载时阶段文案串味

### 根因

`setDownloadProgress` 的阶段文案参数读的是 **Service 的全局 `state` 字段**（`:997`、`:1114`、`:1169` 都是 `state ?: "下载中"`），而 `state` 是**单例字段**（`:69`），并行模式下多个任务同时写它（`:610`、`:637`、`:650`、`:723`、`:730`…）。

于是并行下 A 任务可能显示成 B 任务的"下载音频"。

### 处理办法

把 phase 作为**参数**逐层传进 `downFile`，`setDownloadProgress` 用传入值，不再读全局 `state`。全局 `state`/`percent` 字段（`:68-69`）在列表侧已不再使用（`DownloadAdapter:63-80` 用的是 per-id 的 `progressInfo`），可只保留给通知栏"最近活动任务"用，或直接删除。

---

## §7 缺陷 A6：通知栏总进度回退

### 根因

`DownloadProgressStore.computeOverallProgress`（`:119-133`）把每个活跃任务的 `info.progress` **直接求和**：

```kotlin
activeProgressSum += info.progress.coerceIn(0f, 1f)
```

DASH 任务在进入音频阶段时把 `progress` 从 1.0 重置为 0（见 A1），总进度因此**回退**。同理 `getActiveRemainingBytes` 用 `totalBytes - downloadedBytes`，而 DASH 下 `totalBytes` 会被覆盖成"当前文件"的大小 → ETA 跳变。

### 处理办法

- 先修 A1（让单任务进度单调），总进度自然单调；
- `DownloadProgressInfo.totalBytes` 改为记录**整任务总字节**（附件+视频+音频），而不是当前文件的 `Content-Length`；
- `getActiveRemainingBytes` 相应改为用整任务口径。

---

## §8 缺陷 A3 / A4

- **A3 分片失败回退时进度先满后退**（`:1204-1206`）：`setDownloadProgress(sectionId, baseProgress, ..., 0L, totalSize)` 直接把进度打回阶段起点。**根治依赖断点续传**（见优化文档 P0-1）：有续传后，回退不再需要"从头重下"，进度也就不该归零。
- **A4 未知大小时伪进度卡 90%**（`DownloadProgressMath.kt:21` `PSEUDO_MAX = 0.9f` + `DownloadService.kt:1001-1002`）：伪进度封顶 0.9，成功后直接置 `endProgress`。改法：封顶提到 0.98，或让伪进度渐进逼近 1.0。

---

## §9 缺陷 B2 / B3（本地播放侧）

### B2 多P 删除索引错位

`LocalPageChooseActivity`（`:84-86`）：

```kotlin
pageList.removeAt(position)
videoFileList.removeAt(position)   // ← DASH 下每页占 2 个元素
danmakuFileList.removeAt(position)
```

DASH 多P 时 `videoFileList` 每个分P 占 **2 个元素**（video + audio，见 `LocalListActivity:169` 与 `:174`），按页索引 `removeAt` 会删错元素并可能越界。
**处理办法**：删除时按 pageName 重新扫盘过滤，或按"每页元素数"成组移除。
（注：`buildPageMediaFiles`（`:113-144`）是**重新扫盘**的，所以播放配对本身没错，错的只是删除。）

### B3 虚拟合集播放无声

`LocalListActivity.playVirtualCollection()`（`:493-501`）只取 `.mp4`：

```kotlin
val videoFile = v.videoFileList!!.firstOrNull { it.endsWith(".mp4") } ?: v.videoFileList!![0]
videoUrlList.add(videoFile)     // ← audio.m4a 被丢弃
```

而 DASH 的 `video.mp4` **无音轨** → 虚拟合集里 DASH 视频**全程无声**。
**处理办法**：虚拟合集也传外部音轨列表（`PlayerData` 增 `audioTrackUrlList` + 分页切换时同步），或在合集场景直接跳过无音轨文件并提示。

---

## §10 处理顺序建议（小步提交）

| 步 | 内容 | 验证 |
| --- | --- | --- |
| 1 | **C3** `downFileSpeedSingle` 补完整性校验 + 失败清理 | 单测（字节不足 → ERR_NETWORK）+ 真机断网 |
| 2 | **B1 第 1 层** 播放侧 `MediaExtractor` 判定内嵌音轨 | 真机：含音轨 MP4 + audio.m4a 不再双声 |
| 3 | **B1 第 2 层** 下载侧清理逻辑（删 audio 查返回值 / audio_only 删 video / DASH 替换回滚） | 真机切清晰度反复验证 |
| 4 | **A1 + A2** 进度按字节加权 + 分片实时聚合 | 真机观察进度条单调 |
| 5 | **A5 + A6** phase 参数化 + 总字节口径 | 并行下多任务文案正确 |
| 6 | **B2 + B3** 多P 删除 + 虚拟合集音轨 | 真机多P 与合集播放 |
| 7 | **B1 第 3 层** 元数据 `hasEmbeddedAudio`（根治） | 迁移 + 单测 |
| 8 | **A3 + A4** 配合续传一并处理 | 见优化文档 |

每步完成后同步 `docs/architecture-map.md` §7.27（若触及锁/状态契约）与 `docs/review/fix-progress.md`（活台账）。
