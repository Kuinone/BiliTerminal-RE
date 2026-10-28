# 播放器弱网 / 直播 / 长视频：同类产品做法调研

> **【已归档 2026-10-27】** 文首「不改动任何代码」已不成立：P0-1、P0-3、P1-1、P1-2、P2-1 与半个 P2-3 已于 26.10.11 实施；未做项与 `.so` 选项实测结论已回迁 `docs/review/fix-progress.md` §2.2。文中行号均为当时位置。

- **调研日期**：2026-10-11
- **范围**：① ijkplayer（B站官方 fork）网络/缓冲类选项的权威语义与弱网、直播配法；② HTTP-FLV vs HLS 在丢包与半死连接下的行为差异，B站直播平台侧公开材料与第三方开源客户端做法；③ Android 官方对播放位置保存/恢复、后台回来重挂 Surface 的推荐，以及 Media3/ExoPlayer 的弱网与直播策略作为业界对照。
- **不在范围**：解码性能、弹幕渲染、下载链路、UI。本文只做调研记录，**不改动任何代码**。
- **来源可信度分级**（全文按此标注）：
  - **[官方源码]**：`bilibili/ijkplayer`、`Bilibili/FFmpeg`（ijkplayer 实际编译进去的 fork）、`androidx/media` 的源码行。
  - **[官方文档]**：Android/AndroidX 官方文档、RFC、Apple 官方 HLS 页面、FFmpeg 官方文档、CDN 厂商官方文档。
  - **[一手开源代码]**：第三方开源客户端/工具的源码行（可复核，但不是"官方推荐"）。
  - **[二手]**：GitHub issue、博客、知乎/CSDN 等。仅作"有人这么做"的佐证，不作为结论依据。
  - **[未证实]**：本轮没有拿到一手来源，或只能推断。

> 方法说明：网络请求层存在"把大文件读进上下文"的效率问题，本次对源码类来源统一采用**本地下载后再 grep** 的方式（`raw.githubusercontent.com` → 本地文件 → 精确取行），因此下文给出的行号均为**实际读到的行**。所有抓取到的网页内容一律按"数据"对待。

---

## §1 结论速览

1. **`timeout` / `rw_timeout` / `addrinfo_timeout` 对普通 `https://` 直播地址是生效的，不需要 `ijkio:` 包装**；但 ijkplayer 会在 rtmp/rtsp 场景**主动删掉 `timeout`**，所以通用做法是优先用 `rw_timeout`。[官方源码] `ff_ffplay.c:3105-3109`（`av_dict_set(&ffp->format_opts, "timeout", NULL, 0);`）、`Bilibili/FFmpeg` `avio.c:59`、`tcp.c:71/80`。
2. **没有超时 = 可以永久阻塞，而且不会进 error 回调**。`rw_timeout` 默认 0（不限）、tcp 的 `timeout` 默认 −1（不限），读操作会一直等在 `ff_network_wait_fd_timeout` 里；FFmpeg 官方文档明确说这种情况"要么设 `rw_timeout`，要么用 interrupt callback"。[官方源码] `avio.c:59`、`tcp.c:71`；[官方文档] `doc/protocols.texi`；[二手佐证] ijkplayer issue #2764「因为在buffering的时候，如果一直没速度，onErro不会走」。
3. **只设 `reconnect=1` 救不了直播**：直播响应是 `Transfer-Encoding: chunked`，`is_streamed=1`，而 `http_read_stream` 里第一道判定就是 `if (h->is_streamed && !s->reconnect_streamed) break;`——**必须同时开 `reconnect_streamed=1`**；要覆盖"服务端正常 EOF"还需 `reconnect_at_eof=1`。[官方源码] `http.c:1476-1516`、`http.c:997-1000`（chunked → `filesize=UINT64_MAX`）。
4. **`reconnect` 也救不了"连接没断但一个字节都不来"**：重连循环只在某次 `read` 返回负值时才进入；没有读超时就不会返回负值。必须 `rw_timeout` + `reconnect*` 一起用。这正是本项目现状 A 缺的那一半。[官方源码] `http.c:1476-1516`。
5. **`infbuf=1` 能换启动速度，但代价是内存无上限**。它短路掉的正是 `max-buffer-size`（`dcc.max_buffer_size` 全文件**只在这一处**被消费）与 `min-frames` 两道闸门，直播永不结束 → 内存一直涨。[官方源码] `ff_ffplay_options.h:79-80`（"useful with realtime streams"）、`ff_ffplay.c:3466-3474`；[二手佐证] issue #3788/#3792。
6. **`max_cached_duration` 不是 ijkplayer 的选项**（现仓库 `ShortVideoPlayerActivity.kt:1011` 设的这个值很可能是空转），它在 `bilibili/ijkplayer` master 与 `k0.8.8` 的 `.c/.h/.java` 里**零命中**；只有只读属性 `FFP_PROP_INT64_VIDEO_CACHED_DURATION=20005`。[官方源码] `IjkMediaPlayer.java:123-124`。
7. **`dns_cache_clear=1` 在默认配置下是空转**：`tcp.c` 里的逻辑是 `if (s->dns_cache_timeout > 0) { if (s->dns_cache_clear) remove_dns_cache_entry(uri); } else { get_dns_cache_reference(uri); }`，而 `dns_cache_timeout` 默认 0 ⇒ 根本没建缓存，也就无从清除（现状 A 的 `PlayerActivity.kt:892` 属此类）。[官方源码] `tcp.c:81-82`、`tcp.c:405-411`。
8. **ijkplayer 没有任何"位置 N 秒不前进就动作"的内置看门狗**，这个必须由 App 自己做；业界开源实现普遍是"chunk 级空闲超时/读超时"流（biliup 30s、bililive-go 60s、BilibiliLiveRecorder 120s、blivedm 心跳+5s）。[官方源码] 对 `watchdog|no_progress|stall_timeout|last_position` 的 grep 零命中；[一手开源代码] `biliup/crates/biliup/src/downloader/httpflv.rs:368` 等。
9. **弱网韧性 HLS > HTTP-FLV，差别是结构性的**：HLS 每个 segment/part 都是一个恢复检查点（失败检测上界 ≈ 一个 target duration、可幂等重试、可在段边界切档），HTTP-FLV 是"一整个永不结束的 chunked 响应"，失败即无边界的沉默；且**CDN 可能把它当成"传完了"直接静默截断**。[官方文档] RFC 8216、Apple LL-HLS、RFC 9112 §8/§9.5、RFC 1122 §4.2.3.6、Fastly `between_bytes_timeout` 文档。
10. **`stream_ttl` 的语义未证实**：接口文档快照里该行说明是空的（`bilibili-API/docs/live/info.md:911`），样例值为 3600，而 bililive-go / biliup / yt-dlp 三份实现**没有一处消费它**；能证实的只是播放 URL 的 `extra` 里带 `expires=`/`deadline=` 签名时效 ⇒ **重连必须重新调 `getRoomPlayInfo`**，但"到期时长 = stream_ttl"未证实。[一手接口文档快照] + [一手开源代码]。

---

## §2 ijkplayer 选项权威语义表

### 2.1 前置事实：选项分两层

`ff_ffplay_options.h` 里的 `ffp_context_options[]` 是 **PLAYER 层**；`timeout`/`rw_timeout`/`reconnect*`/`dns_cache_clear`/`fflags`/`probesize`/`analyzeduration`/`user_agent` 都**不在**这张表里，它们是 `OPT_CATEGORY_FORMAT` 走的 **AVFormat/URLContext 层**。

透传链（源码级）：`IjkMediaPlayer.setOption(OPT_CATEGORY_FORMAT, …)` → `ff_ffplay.c:4043-4047`（`case FFP_OPT_CATEGORY_FORMAT: return &ffp->format_opts;`）→ `ff_ffplay.c:3122` `avformat_open_input(&ic, is->filename, is->iformat, &ffp->format_opts)` → `utils.c:555-574`（`av_dict_copy` + `av_opt_set_dict(s,&tmp)` 只吃掉 AVFormatContext 的键，剩下的继续下传）→ `utils.c:438` `s->io_open(...options)` → `avio.c:320/323`（URLContext 类 → 协议私有类）→ `http.c:246-248` `ffurl_open_whitelist(..., options, ...)` → 内层 `tcp`/`tls`。**所以 FORMAT 类选项对一个普通 `https://` 地址同样落地，无需 `ijkio:`。** [官方源码]

另外两条必须记住的边界：

- **rtmp/rtsp 会被删掉 `timeout`**：`ff_ffplay.c:3105-3109`（`:4268-4270` 重复一次）注释 `There is total different meaning for 'timeout' option in rtmp` 后 `av_dict_set(&ffp->format_opts, "timeout", NULL, 0);`。[官方源码]（[二手印证] issue #837：必须先注释掉这行才能用 timeout）
- **ijk 的 `ijkio:`/httphook 链（秒开、缓存、`httphookReconnect()`）对不改写 URL 的普通 https 地址完全不参与**：`IjkMediaPlayer.java:405-409` 的 `setDataSource(String)` 只调 `_setDataSource(path,null,null)`，不改写 URL；`:421-432` 仅在 headers 非空时追加 `headers`+`protocol_whitelist`（白名单里没有 `ijkio`）；`:953-959` 的 `httphookReconnect()` 置 `FFP_PROP_INT64_IMMEDIATE_RECONNECT=20211`，只被 ijkio/httphook 的读循环（`ijkiourlhook.c:321-362`、`ijkiomanager.c:360-366`）读取。[官方源码]

### 2.2 表

| 选项 | 类别 | 默认值 | 语义 | 弱网 / 直播该怎么配 | 来源 |
| --- | --- | --- | --- | --- | --- |
| `timeout` | FORMAT（tcp 私有；`OFFSET(rw_timeout)`） | `-1`（不限），单位**微秒** | socket I/O 超时 | **rtmp/rtsp 下会被 ijk 删掉**，故不推荐依赖；http(s) 下可用（如 5–30s） | [官方源码] `tcp.c:71`；`ff_ffplay.c:3105-3109` |
| `rw_timeout` | FORMAT（URLContext，**任意协议可见**） | `0`（不限），单位**微秒** | 网络读写等待超时 | **弱网核心项**。直播建议 `5_000_000`（5s）或 `10_000_000`；没有它，"半死连接"永远不返回错误 | [官方源码] `avio.c:59`、`avio.c:386-389`；[官方文档] `doc/protocols.texi` |
| `addrinfo_timeout` | FORMAT（tcp，**B站 fork 新增**） | `-1`，微秒 | `getaddrinfo()` 超时 | DNS 卡死场景建议 `5_000_000`（本项目短视频播放器已这么设） | [官方源码] `tcp.c:80`、`tcp.c:416` |
| `connect_timeout` | FORMAT（tcp） | `-1`，微秒 | 建连超时 | 可选，建议 `5_000_000` | [官方源码] `tcp.c:72` |
| `dns_cache_timeout` | FORMAT（tcp，B站 fork 新增） | `0`（=不缓存），微秒 | DNS 缓存 TTL | 换网场景可设正值与 `dns_cache_clear` 配合 | [官方源码] `tcp.c:81` |
| `dns_cache_clear` | FORMAT（tcp，B站 fork 新增） | `0` | 清除该 URI 的 DNS 缓存条目 | **仅当 `dns_cache_timeout>0` 才有效**；默认 0 时是空转。ijk 内部 httphook 重连会自己带上它 | [官方源码] `tcp.c:82`、`tcp.c:405-411`、`ijkurlhook.c:243`、`ijkiourlhook.c:253-254` |
| `reconnect` | FORMAT（http） | `0` | `auto reconnect after disconnect before EOF` | 直播需开，但**单独开没用** | [官方源码] `http.c:167`、`http.c:1504-1505` |
| `reconnect_streamed` | FORMAT（http） | `0` | 允许对**不可 seek 的流**重连 | **直播（chunked）必须 =1**，否则 `http.c:1501` 直接 break | [官方源码] `http.c:169`、`http.c:1501`、`http.c:542-545` |
| `reconnect_at_eof` | FORMAT（http） | `0` | 把 EOF 也当错误并重连（无尽流用） | 直播建议 =1（服务端/中间设备正常收尾时也能重连） | [官方源码] `http.c:168`、`http.c:1504-1505` |
| `reconnect_on_network_error` | FORMAT（http） | **本 fork 中不存在**（本轮已在 `http.c` 选项表逐行核对） | — | 不需要配；上游新版 FFmpeg 才有 | [官方源码] `http.c:140-175` |
| `reconnect_delay_max` | FORMAT（http） | `120`（**秒**） | 重连退避上限，超过则 `return AVERROR(EIO)` | 直播建议压到 `5`–`10` 秒，避免长时间黑屏 | [官方源码] `http.c:170`、`http.c:1508` |
| `infbuf` | PLAYER | `0`（范围 0..1） | `don't limit the input buffer size (useful with realtime streams)` | **取舍项**：=1 降启动/追帧延迟，但**同时废掉 `max-buffer-size` 与 `min-frames` 两道闸门 → 内存无上限增长**（直播永不结束）。手表内存紧张，建议**保持 0**，改为调小 `min-frames`/`max-buffer-size` | [官方源码] `ff_ffplay_options.h:79-80`、`ff_ffplay.c:3466-3474`；[二手] #3788/#3792 |
| `packet-buffering` | PLAYER | `1` | `pause output until enough packets have been read after stalling`；是 buffering 机制的**总开关**（起止回调 + 50/500ms 检测） | 直播低延迟可 =0（直接把已有包喂出去），代价是卡顿时不出缓冲回调、停顿更可感知；常规观看建议保持 1 | [官方源码] `ff_ffplay_options.h:144-145`、`ff_ffplay.c:339`、`:3611-3625`、`:4578-4580` |
| `max-buffer-size` | PLAYER | `15728640`（`MAX_QUEUE_SIZE`），范围 0..15728640 | a/v/s 三个 PacketQueue 字节数之和的预读上限 | 直播可降到 `2–4MB` 以控制内存（**前提 `infbuf=0`**） | [官方源码] `ff_ffplay_options.h:124-125`、`ff_ffplay.c:3467` |
| `min-frames` | PLAYER | `50000`（`DEFAULT_MIN_FRAMES`），范围 2..50000 | `minimal frames to stop pre-reading`：每路 `nb_packets > min_frames` 即停读 | **默认 5 万帧对直播极其保守**，是启动慢的主因之一。直播可降到 `5`–`50`（本项目短视频播放器用 5） | [官方源码] `ff_ffplay_options.h:126-127`、`ff_ffplay.c:3034`、`:3469-3471` |
| `max_cached_duration` | — | **不是 ijkplayer 选项（本仓库/上游均无）** | 社区里的写法，来源不明 | **不要写**；现有 `ShortVideoPlayerActivity.kt:1011` 应删 | [官方源码] 对 master 与 `k0.8.8` 的 `.c/.h/.java` grep 零命中 |
| `framedrop` | PLAYER | `0`（范围 −1..120） | 0=关；>0=开早丢+迟丢，且 N 兼作"连续早丢 N 次后强制放行一帧"阈值；<0=仅当主时钟不是视频时开 | 慢机/弱网追帧可设 `1`–`5`（本项目主播放器 4、短视频 5、VideoPlayerCore 1）。**"−1=丢所有非参考帧"在源码中找不到依据** | [官方源码] `ff_ffplay_options.h:81-82`、`ff_ffplay.c:1373`、`:1705`、`:1713-1722` |
| `probesize` | FORMAT（AVFormatContext） | `5000000` 字节（范围 32..INT64_MAX） | 探测字节数 | 直接决定首帧时间；调小可加快起播，但**过小会导致识别不出/认错流**（FFmpeg 自身有告警）。直播设极小值属社区做法，**官方无背书** | [官方源码] `options_table.h:39`、`utils.c:4149` |
| `analyzeduration` | FORMAT（AVFormatContext） | `0`（微秒），**但 0 会被当成 5 秒** | 探测时长 | 同上。ffmpeg 源码 `if (!max_analyze_duration) max_analyze_duration = 5*AV_TIME_BASE;` | [官方源码] `options_table.h:61`、`utils.c:3642-3644` |
| `enable-accurate-seek` | PLAYER | `0`（范围 0..1） | 精准 seek | 长视频拖进度条可开（`VideoPlayerCore.kt:133` 已开）；直播不需要 | [官方源码] `ff_ffplay_options.h`（`:152-217` 段），配套 `accurate-seek-timeout` 默认 `MAX_ACCURATE_SEEK_TIMEOUT=5000`（`ff_ffplay_def.h`） |
| `fflags` | FORMAT（AVFormatContext） | `AVFMT_FLAG_AUTO_BSF`；含常量 `nobuffer`（`reduce the latency introduced by optional buffering`） | 格式层标志 | 直播低延迟常配 `nobuffer`；本项目配的是 `flush_packets`（主）/`fastseek`（短视频 & VideoPlayerCore）。**具体 demuxer 是否真的使用 `nobuffer` 本轮未逐一核实** | [官方源码] `options_table.h:42`、`:56` |
| `start-on-prepared` | PLAYER | `1` | prepare 完自动起播 | 保持 1；需要自己控制起播时机时设 0（短视频播放器与 VideoPlayerCore 都设 0） | [官方源码] `ff_ffplay_options.h:116-117` |
| `first/next/last-high-water-mark-ms` | PLAYER | `100` / `1000` / `5000` | 唤醒 read_thread 的三级缓冲门限 | 直播可整体调小以更早出画面 | [官方源码] `ff_ffplay_options.h:128-142` |
| `multiple_requests` | FORMAT（http） | `0` | `use persistent connections`（keep-alive） | 长连接流保持默认即可 | [官方源码] `http.c:150` |
| `user_agent` | FORMAT（http） | `Lavf/<version>` | UA | 本项目已设（`PlayerActivity.kt:899`、`VideoPlayerCore.kt:138`） | [官方源码] `http.c:145` |

### 2.3 官方"示例参数集"（唯一可称官方推荐的出处）

**iOS `IJKFFOptions.m:37-55` 的 `+optionsByDefault`** 是仓库里唯一一份成体系的参数集：`max-fps=30`、`framedrop=0`、`video-pictq-size=3`、FORMAT `reconnect=1`、FORMAT `timeout=30*1000*1000`（30 秒）、FORMAT `user-agent=ijkplayer`。[官方源码]

维护者对"如何设置网络超时"的唯一公开答复就是指向它的 **"See demo."**（issue #2102，bbcallen）；关于重连，他 2014 年回复"我不知道有没有现成的重连机制"，并把 `rtsp 重连` 标为 `not-on-plan`（issue #62）。[二手（含官方 contributor 发言）]

**`README.md` 与 `doc/` 里没有任何弱网/直播/低延迟的参数建议**——README 全文只讲编译与许可，`doc/` 目录下只有 `preflight_checklist.md`（发版检查清单）。[官方源码]

### 2.4 本工程预编译 `.so` 的选项存在性实测（26.10.11，`findstr /C:"<opt>" app/libs/arm64-v8a/*.so` 精确串）

> 本节直接回答 §5.A.1 / §5.A.7 那个"若 `.so` 与 fork tag 不一致则结论要重核"的疑问：实测对象是**仓库里真正打进 APK 的二进制**。凡下表"不存在"的选项，本工程设了也不会报错，但**完全是空转**。（裸 `timeout` / `reconnect` 这类短串会被 `rw_timeout`/`addrinfo_timeout`/`reconnect_at_eof` 命中，故只对完整选项名下结论。）

| 层 | 实测命中（= 真实存在） | 实测缺失（= 空转） |
| --- | --- | --- |
| PLAYER 层（`libijkplayer.so`） | `infbuf`、`packet-buffering`、`max-buffer-size`、`min-frames`、`framedrop`、`max-fps`、`enable-accurate-seek`、`accurate-seek-timeout`、`seek-at-start`、`start-on-prepared`、`soundtouch`、`async-init-decoder`、`mediacodec-sync`、`render-wait-start`、`first/next/last-high-water-mark-ms`、`find_stream_info`、`user_agent`、`probesize`、`analyzeduration`、`dns_cache_clear`、`seekable` | **`max_cached_duration`** |
| FORMAT / URLContext 层（`libijkffmpeg.so`） | `rw_timeout`、`addrinfo_timeout`、`reconnect_delay_max`、`reconnect_at_eof`、`reconnect_streamed`、`multiple_requests`、`tcp_nodelay`、`fflags`、`max_analyze_duration`、`skip_loop_filter`、`skip_frame`、`user_agent`、`probesize`、`analyzeduration`、`dns_cache_clear`、`seekable` | **`reconnect_on_network_error`**、**`http-detect-range-support`**（连字符与下划线两种写法都试过） |

三点直接结论：

1. **§1 第 6 条（`max_cached_duration` 不是 ijkplayer 选项）与 §5.A.7（本 fork 无 `reconnect_on_network_error`）在本仓库的二进制上得到确认**，不必再依赖"源码 tag 可能不一致"的假设。
2. **本轮新增一处同类空转**：`app/src/main/java/com/RobinNotBad/BiliClient/player/VideoPlayerCore.kt` 里的 `FORMAT http-detect-range-support=0`，两个 `.so` 里都不存在。
3. **主播放器"在线视频"分支的三条选项里有两条等于没设**：`max-buffer-size=15*1024*1024` 的默认值就是 `MAX_QUEUE_SIZE`（`15*1024*1024`，见 `ff_ffplay_def.h`），`packet-buffering=1` 也是默认值 ⇒ 该分支真正生效的只有 `user_agent`（`PlayerActivity.kt:892-899`）。P0-1 要补的 `rw_timeout` 等则**确实存在于本二进制**，属于真能生效的改动。

---

## §3 三个问题的逐项对照

### 3.1 问题一：ijkplayer 的弱网/直播参数怎么配

| 议题 | 本仓库现状 | 业界/官方做法 | 依据来源 |
| --- | --- | --- | --- |
| 读超时 | **主播放器（含直播）完全没设** `timeout`/`rw_timeout`/`addrinfo_timeout`；短视频播放器设了 `timeout=10s`、`addrinfo_timeout=5s` | 官方示例给的是 FORMAT `timeout=30s`；FFmpeg 官方文档要求"设 `rw_timeout` 或用 interrupt callback"，否则读可以永久阻塞 | 现状：`PlayerActivity.kt:875-899`（无该类项）、`ShortVideoPlayerActivity.kt:1006-1007`；[官方源码] `IJKFFOptions.m:37-55`；[官方文档] `doc/protocols.texi` |
| 重连 | 只设了 `reconnect=1`（`:894`）：**对 chunked 直播不够**（缺 `reconnect_streamed=1`），且无读超时 ⇒ 触发不了 | 直播要 `reconnect=1` + `reconnect_streamed=1`（+ `reconnect_at_eof=1`）；`reconnect_delay_max` 默认 120s 需压低 | [官方源码] `http.c:1501`、`:1504-1505`、`:1508` |
| 内存与缓冲 | `packet-buffering=1` + `max-buffer-size=15MB`，**没设 `min-frames`**（=默认 5 万帧） | `infbuf` 官方描述"useful with realtime streams"但会取消内存上限；控制内存应保持 `infbuf=0` 并调小 `min-frames`/`max-buffer-size` | [官方源码] `ff_ffplay_options.h:79-80`、`:124-127`、`ff_ffplay.c:3466-3474` |
| 无效/空转选项 | `dns_cache_clear=1`（`:892`）在 `dns_cache_timeout` 默认 0 下**空转**；短视频播放器的 `max_cached_duration=3000`（`:1011`）**不是 ijkplayer 选项** | —（这是本轮新发现的源码事实） | [官方源码] `tcp.c:405-411`；`ff_ffplay_options.h`/`ff_ffplay_def.h` 全表核对 |
| "连接没断但没数据" | 无任何处理；`onPause` 只上报进度，不检测停滞 | 必须 App 层做：ijkplayer **没有**位置停滞看门狗；业界普遍做 chunk/读超时看门狗 | 见 §3.2 |
| 播放中重连的现成实现 | 无（仓库里没有"无数据 N 秒→重连"） | ijkplayer 唯一的"立刻重连"入口 `httphookReconnect()` 只对 `ijkio:`/httphook 链有效，普通 https 地址无效 ⇒ 实际做法是**重建 IjkMediaPlayer 实例** | [官方源码] `IjkMediaPlayer.java:953-959`、`ff_ffplay.c:5029`；本项目已有重建范式：`PlayerActivity.kt:1637-1645` |

### 3.2 问题二：直播链路抗弱网（FLV vs HLS + 平台侧）

**（a）协议行为差异（一手依据）**

| 维度 | HLS（fMP4/CMAF） | HLS（MPEG-TS） | HTTP-FLV |
| --- | --- | --- | --- |
| 有规范吗 | 有：RFC 8216，及已并入规范的 LL-HLS | 同左 | **没有任何 RFC/IETF 规范**：Adobe FLV 规范只定义容器且官方 PDF 抓取失败（403），IANA media-types 无 `video/x-flv` 条目 ⇒ **事实约定（de-facto）** |
| 恢复检查点 | 每个 segment（LL-HLS 为每个 part，`PART-TARGET=0.33334`） | 每个 segment | **无**（整条是一个永不结束的 chunked 响应） |
| 失败检测上界 | ≈ 一个 target duration（LL-HLS ≈ 0.33s + `PART-HOLD-BACK=1.0`） | 同左 | 只能靠应用层读超时；TCP keepalive **默认关闭、默认间隔 ≥2 小时**，且规范**禁止**把单次探测无响应当作死连接 |
| 重试粒度 | 重取该 segment/part（幂等 GET） | 同左 | 只能整条重连，且**无法定位断点** |
| 码率自适应 | `EXT-X-STREAM-INF` 多档，段边界切档 | 同左 | 换档 = 重连换 URL |
| CDN 缓存 | segment 是独立对象，可缓存/预取 | 同左 | 长连接不可缓存 |
| 半死连接 | 下一个 segment GET 超时 → 重试/换档/换 CDN | 同左 | 数据流静默停滞；**CDN 可能判定为"响应已结束"** |
| 初始化/重试成本 | fMP4 段依赖 `EXT-X-MAP` 指向的 init 段（RFC 8216 §4.3.2.5；L408「Each fMP4 Segment in a Media Playlist MUST have an EXT-X-MAP tag」），init 失败会连带后续段；`EXT-X-BYTERANGE` 把多段塞进一个资源 ⇒ 请求数少但单一 URL 故障影响面大 | TS 段自包含，重试最"笨但最稳"，故障域最小 | — |

来源：[官方文档] RFC 8216（§4.3.2.5、§4.3.2.2、`EXT-X-ENDLIST`）、Apple LL-HLS 页面（样例含 `#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK=1.0`、`#EXT-X-PART-INF:PART-TARGET=0.33334`、`#EXT-X-PRELOAD-HINT:TYPE=PART`、请求侧 `_HLS_msn=…&_HLS_part=…`，并明确"服务端不支持时回退到普通 HLS"）、RFC 9112 §8/§9.5（`A client, server, or proxy MAY close the transport connection at any time.`；分帧无法区分"响应未完成"与"正常结束"）、RFC 9110 §15.5.9/§9.2.2、RFC 1122 §4.2.3.6（keepalive 默认 off、默认 ≥2 小时、`MUST NOT interpret failure to respond to any specific probe as a dead connection`）、Fastly 后端文档（`between_bytes_timeout`：超时后"the response received so far will be considered complete and the fetch will end"）、Cloudflare 524 文档（读超时 **125 秒**、写超时 30 秒）、CloudFront 源站文档（连接超时 1–10 秒，默认 10 秒；另有 Response/Response completion/Keep-alive 三重超时）。

**结论**：弱网韧性上 **HLS 结构性优于 HTTP-FLV**（有检查点）。FLV 的价值在单连接、低延迟、起播快、实现简单。**给 FLV 补韧性的工程手段**：(a) chunk 级空闲看门狗（10–30s）；(b) 断开后指数退避重连，**且重连必须重新调 `getRoomPlayInfo`**（换签名 URL，必要时换 CDN 线路）；(c) HLS/fMP4 作为兜底。

**（b）B站平台侧公开材料**

- B站官方公开的只有**代码级事实**（`github.com/bilibili` 官方仓库）：`flv.js`（HTTP-FLV over MSE，23.2k★）、`ijkplayer`、`DanmakuFlameMaster`、**`bbip`（High Performance IP Resolve Library，与选线/DNS 选路直接相关）**、`discovery`（failover registry）、`quiche`（Cloudflare quiche 的 fork）、`nginx_quic_module`/`nginx_quic_stack`（边缘 QUIC）、`mahimahi`（网络仿真）、`biliobs`/`StarLiveSDK`（推流 SDK）、`flvbind`。[官方源码]
- **B站从未公开描述"直播拉流协议栈 / CDN 选线算法 / `stream_ttl` 语义"的规范**：开放平台与直播开放平台的文档页本轮抓到的是 JS 壳（`openlive.html` 仅 1467 B），无法确认；「LSS / BLSS」**没有找到任何官方定义 ⇒ 未证实，不应作为官方协议名使用**；B站技术公众号的 QUIC 文章本轮未取到可引用正文。[未证实]

**（c）`getRoomPlayInfo` 参数与 `stream_ttl`**

| 项 | 事实 | 来源 |
| --- | --- | --- |
| 接口 | `https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo` | [一手接口文档快照] `bilibili-API/docs/live/info.md:798-1103`；[一手开源代码] `bililive-go/src/live/bilibili.go:26` |
| `protocol` | `0`=http_stream(FLV)，`1`=http_hls；可逗号多选 | 快照 `:811` |
| `format` | `0`=flv，`1`=ts，`2`=fmp4；可逗号多选 | 快照 `:812` |
| 响应结构 | `playurl_info.playurl.stream[].format[].codec[].url_info[]`；最终 URL = **`host` + `codec.base_url` + `url_info[].extra`**（bililive-go / biliup / yt-dlp 三份独立实现完全一致） | 快照 `:850`/`:882`/`:905-911`/`:1003`/`:1032`/`:1059`；[一手开源代码] `biliup` `live/bilibili.rs:678-695`；[第三方实测] yt-dlp `bilibili.py:2631` |
| `stream_ttl` | 字段存在（num），样例 `3600`（快照 `:1020`/`:1049`/`:1073`/`:1078`）；**该行说明为空**（`:911`）；**三份开源实现无一消费它** ⇒ 单位与语义**未证实**；"过期导致中途卡顿"的因果链**未证实** | 快照 `:911`；开源实现代码 |
| 可证实的相关事实 | 播放 URL 的 `extra` 带 `expires=`/`deadline=` 签名 ⇒ **重连必须重新取流**（这条足以支撑工程做法） | 快照响应样例；[一手开源代码] biliup 从 `extra` 正则抽 `cdn=` |

**（d）第三方开源客户端的具体做法（一手开源代码）**

| 项目 | 机制 | 关键行 |
| --- | --- | --- |
| **biliup**（Rust，最系统） | CDN 线路故障切换；**FLV 空闲看门狗 30s**；指数退避重试 | `live/bilibili.rs:551-573`（`:562-563` 可关；`:565-572` 先 `check_url_healthy`，失败则遍历同画质组所有 CDN；`:573` 全挂报错）；**`httpflv.rs:368` `match timeout(Duration::from_secs(30), self.resp.chunk()).await? {`**；`client.rs:28` `ExponentialBackoff::builder().build_with_max_retries(5)`、`:25` `connect_timeout(60s)`、`:24` 全局超时**故意注释掉**（直播是长连接）；`hls.rs:24-30` `playlist_poll_interval = max(target_duration, MIN_PLAYLIST_POLL_INTERVAL)`、`playlist_should_refresh = !end_list` |
| **bililive-go**（Go） | **看门狗 = ffmpeg `-rw_timeout`**；失败退避分级；协议/线路可回退 | `ffmpeg.go:356-359` `-rw_timeout <timeoutInUs> -i <url>`；默认 **60s**（`config.go:1130` `TimeoutInUs: 60000000`）；`recorder.go:1154-1166` `shift := min(consecutiveFastFailures-1, 4)` / `interval := minRecordRetryInterval << shift` / `return min(interval, maxInterval)`；常量 `recorder.go:54-64`：`fastFailThreshold=5s`、`minRecordRetryInterval=5s`、`maxRecordRetryIntervalNeverHeld=60s`、`maxRecordRetryIntervalAfterHold=15s`（**从没拉到流→最慢 60s；拉到过流→只拉长到 15s，优先保内容连续性**）；`bilibili.go:162` 一次请求同时要 `protocol=0,1&format=0,1,2&codec=0,1`，`:307-311` 选 `stream.1.format.1.codec.1`（hevc m3u8）或 `stream.0.format.0.codec.0`（avc flv） |
| **BilibiliLiveRecorder**（Java） | 流下载读超时 120s；主机解析失败重试 10 次；HLS 并发下分片 | `HttpRequestUtil.java:85-86` connect/read 20s（接口），`:179-180` connect 20s + **read 120s（流下载）**，`:235` 置 FAIL；`:334-348` `connectWithHostKnown` 递归（`hostUnknownCnt<=10`，sleep 1s）；`ThRecord.java:53` `while(true)`、`:85-87` 失败计数、`:67-68` `retryAfterMinutes` 等待；`M3u8Downloader.java:21` 3 线程、`:87-88` 失败计数；`FlvChecker.java` FLV 完整性校验 |
| **blivedm**（Python，弹幕长连接，方法论同源） | **心跳 + 5s 的接收超时**，超时即断线 + 退避重连，成功一条即重置重试计数 | `clients/ws_base.py:265-269` `receive_timeout=self._heartbeat_interval + 5`（心跳 30 → 35s）；`:280` `except (aiohttp.ClientConnectionError, asyncio.TimeoutError)`；`:298` `asyncio.sleep(self._get_reconnect_interval(...))`；`:278` 成功后 `retry_count = 0` |
| **yt-dlp** | **没有任何看门狗/重连**——一次性取流工具，不能作为弱网韧性参照 | `bilibili.py:2646-2655`（参数）、`:2625-2636`（`if codec.get('current_qn') != qn: continue`）、`:2607-2615`（qn 档位表） |

**（e）本仓库现状对照**

| 现状 | 位置 | 问题 |
| --- | --- | --- |
| 请求 `protocol="0,1"`、`format="0,1,2"`、`codec="0,1,2"`（**候选拿得很全**） | `LiveApi.java:107-109` | 解析没问题，问题在消费端 |
| 硬取 `stream[0].format[0].codec[0]` | `LiveInfoActivity.kt:142-143`、`:180-181`、`:240-241` | **永远走 FLV 单长连接**，HLS/fMP4 那几路拿回来不用，没有 FLV→HLS 兜底 |
| 默认取 `url_info[0]`，只能手点"路线N" | `LiveInfoActivity.kt:148-149`、`:244-247` | `url_info[]` 多条 CDN 线路已解析在手，**但没有自动 failover** |
| 初始 `qn=80`（流畅） | `TerminalContext.java:259` | 清晰度偏低 |
| 取流接口已有 30s 超时 + cancel | `TerminalContext.java:273`、`:275` | **这一层做得对**；缺的是播放器数据流层 |
| 播放器数据流层无 chunk 级看门狗 | `app/src/main/java` 内未发现"无数据 N 秒→重连" | 要补 |

### 3.3 问题三：长视频播放/切后台的正确做法

**（a）官方对"播放位置保存与恢复"的推荐** [官方文档]

- **状态保存的官方口径**（`saving-states`）：`Don't use saved state to store large amounts of data, such as bitmaps, nor complex data structures that require lengthy serialization or deserialization. Instead, store only primitive types and simple, small objects such as String.`；对比表明确：**ViewModel** 能扛配置变更、**不能**扛系统发起的进程死亡；**Saved state** 两者都能扛，但只适合原始类型/小对象且较慢；`Saved state APIs only save data written to it when the Activity is stopped.`；官方推荐"ViewModel + saved state + 本地持久化"三层分工。**"当前播放位置"正属于官方举例的"依赖用户输入或导航的瞬时状态"（如列表滚动位置）** ⇒ 该存进 saved state **或**（更好）放到持有播放器的 Service 里。
- **播放位置到底该由谁持有**（`bg-playback`）：`To enable background playback, you should contain the Player and MediaSession inside a separate Service.` 即播放器本体在 Service 里，**UI 层不持有位置**，因此 Media3 架构下 `onSaveInstanceState` 对"位置"基本不再是必须项。官方同时指出：Service 被系统回收后恢复播放需要声明 `androidx.media3.session.MediaButtonReceiver` 并实现 `onPlaybackResumption`，而 **"Your app is responsible for storing the playlist, metadata … of the current item and the start position to use here."** ⇒ **位置的持久化与续播仍是 App 的责任**。
- 前台服务相关的官方约束：需 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限与 `android:foregroundServiceType="mediaPlayback"`；`If the player is paused, stopped or failed for more than 10 minutes without further user interactions, the service is automatically transitioned out of the foreground service state so it can be destroyed by the system.`（这条对本项目"常驻通知"设计有直接影响：暂停 10 分钟会被降级）。
- **续播位置 API**：`Player.seekToDefaultPosition()` 的官方语义是 `Seeks to the default position associated with the current MediaItem. The position can depend on the type of media being played. For live streams it will typically be the live edge. For other streams it will typically be the start.` [官方源码] `Player.java`；`MediaItem.java:1872-1879` 里 `Builder setStartPositionMs(long)` 的 javadoc 为 `Sets the optional start position in milliseconds which must be a value larger than or equal to zero (Default: 0).`——**但这段 javadoc 位于 `ClippingConfiguration.Builder`（类声明 `MediaItem.java:1844`/`:1850`）内**（当前 release 的 `MediaItem.Builder` 区间 `:69-638` 内没有 `StartPosition` 成员），所以 `MediaItem.Builder.setStartPositionMs` 的确切语义、以及"start position 是否在每次 seek-to-default-position 时被重新应用"**本轮未取到一手表述 ⇒ 未证实**。

**（b）后台回来重挂 Surface：官方说法** [官方文档]

- `SurfaceView`：`The Surface will be created for you while the SurfaceView's window is visible; you should implement SurfaceHolder.Callback.surfaceCreated and SurfaceHolder.Callback.surfaceDestroyed to discover when the Surface is created and destroyed as the window is shown and hidden.` 且 API 34 新增常量把这件事说死了：`SURFACE_LIFECYCLE_FOLLOWS_VISIBILITY`（值 1）= `The Surface lifecycle is tied to SurfaceView visibility. The Surface is created when the SurfaceView becomes visible, and is destroyed when the SurfaceView is no longer visible.`；`SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT`（值 2）= 附着时才创建、**detach 才销毁**；配套新 API `void setSurfaceLifecycle(int lifecycleStrategy)`（API 34，`Controls the lifecycle of the Surface owned by this SurfaceView.`）。
  ⇒ **在 API 34 之前的默认语义下，"不可见"就会销毁 Surface**，切后台/被遮挡后回来必然重走一次 `surfaceCreated`。这正是本项目 `PlayerSurfaceBinder.onSurfaceReattached` 存在的理由。
- `TextureView`：`Unlike SurfaceView, TextureView does not create a separate window but behaves as a regular View. This key difference allows a TextureView to have translucency, arbitrary rotations, and complex clipping.`；且 `TextureView can only be used in a hardware accelerated window.`
- **Media3 的 `PlayerView` 自己不实现 `SurfaceHolder.Callback`**（`ui/PlayerView.java:191` `public class PlayerView extends FrameLayout implements AdViewProvider`），而是把内部的 SurfaceView/TextureView 交给播放器（`app:surface_type`，`:157`；常量 `SURFACE_TYPE_NONE/SURFACE_VIEW/TEXTURE_VIEW/SPHERICAL_GL/VIDEO_DECODER_GL` 在 `:292-296`），并在 `setPlayer`（`:655-689`）里用 `clearVideoTextureView/clearVideoSurfaceView` + `setVideoTextureView/setVideoSurfaceView` 完成换绑。**用 PlayerView 时 App 不需要自己调 `setSurface`**——这是 Media3 与"裸 IjkMediaPlayer + 自己管 Surface"最大的架构差异。[官方源码]
- `Player.clearVideoSurface()` 的官方说法是"清掉当前挂在 player 上的 Surface/SurfaceHolder/SurfaceView/TextureView"。[官方源码] `Player.java`

**（c）Media3/ExoPlayer 的弱网与直播策略（业界标准对照，默认值均为实读源码）** [官方源码]（`androidx/media` `release` 分支）

| 项 | 默认值 / 行为 |
| --- | --- |
| `DefaultLoadControl` | `DEFAULT_MIN_BUFFER_MS = 50_000`；`DEFAULT_MAX_BUFFER_MS = 50_000`；`DEFAULT_BUFFER_FOR_PLAYBACK_MS = 1000`；`DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2000`；`DEFAULT_TARGET_BUFFER_BYTES = C.LENGTH_UNSET`；`DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS = false`；`DEFAULT_BACK_BUFFER_DURATION_MS = 0` |
| `DefaultLivePlaybackSpeedControl` | `DEFAULT_FALLBACK_MIN_PLAYBACK_SPEED = 0.97f`；`DEFAULT_FALLBACK_MAX_PLAYBACK_SPEED = 1.03f`；`DEFAULT_MIN_UPDATE_INTERVAL_MS = 1_000`；`DEFAULT_PROPORTIONAL_CONTROL_FACTOR = 0.1f`；`DEFAULT_TARGET_LIVE_OFFSET_INCREMENT_ON_REBUFFER_MS = 500`；`setLiveConfiguration`（`:310-327`）会把 targetOffsetMs/minOffsetMs/maxOffsetMs 与 min/maxPlaybackSpeed 映射进来，**若 min/max 速度都被设成 1f 则 targetOffset 被强制为 `TIME_UNSET`**；`notifyRebuffer`（`:336-344`）每次重缓冲把 target offset 加大 `…RebufferDeltaUs`，上限为 maxTargetLiveOffset |
| `MediaItem.LiveConfiguration` | `MediaItem.java:1322`；builder（`:1325`）的默认是 `targetOffsetMs/minOffsetMs/maxOffsetMs = C.TIME_UNSET`、`minPlaybackSpeed/maxPlaybackSpeed = C.RATE_UNSET`（**该类里没有 `DEFAULT_TARGET_OFFSET_MS` 这类常量**） |
| 直播是否必须自己装 LivePlaybackSpeedControl | **不需要**：`ExoPlayer.java:520` `livePlaybackSpeedControl = new DefaultLivePlaybackSpeedControl.Builder().build();`，且 `:327` 列出默认即 `DefaultLivePlaybackSpeedControl` ⇒ **当前 Media3 默认已装好**（"必须手动设置否则直播播不了"的说法已过时） |
| 重试退避 | `DefaultLoadErrorHandlingPolicy`：`DEFAULT_MIN_LOADABLE_RETRY_COUNT = 3`；`DEFAULT_MIN_LOADABLE_RETRY_COUNT_PROGRESSIVE_LIVE = 6`；退避公式 `min((loadErrorInfo.errorCount - 1) * 1000, 5000)`（即 1s 线性递增，封顶 5s）；`getMinimumLoadableRetryCount` 对 `C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE` 返回 6、其余返回 3。**重试耗尽后才会向上报错** |
| HTTP 超时 | `DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS = 8*1000`；`DEFAULT_READ_TIMEOUT_MILLIS = 8*1000`（**注意：ExoPlayer 默认就有 8 秒读超时，这正是本仓库主播放器缺的那一层**） |
| HTTP-FLV 直播支持 | Media3 有 `libraries/extractor/src/main/java/androidx/media3/extractor/flv/FlvExtractor.java`（release 分支实际存在），并可通过 `ProgressiveMediaSource`（其默认 error policy 即 `DefaultLoadErrorHandlingPolicy`）喂入，且 progressive live 的 data type 有独立的 6 次重试额度 ⇒ **Media3 对 HTTP-FLV 直播在"取流+重试"层面是有支持的**；但**"ProgressiveMediaSource + FlvExtractor 播 chunked 直播时的实际行为（能否正确重连、seek 语义）"本轮未做实测 ⇒ 未证实** |

**（d）哔哩哔哩官方客户端的续播/进度上报机制**

- 仓库内可核实的只有"**服务端记录 `last_play_time` + 客户端在切后台时上报**"这一条链路：`PlayerActivity.kt` 用 `reportProgressNow(...)`（`:1249-1263`）在 `onPause`（`:1832-1836`，`reason="切后台"`）、`onStop`（`:1854-1858`）、`onDestroy`（`:1861`，`reason="销毁兜底"`）、退出前（`:3611`）上报，并用"同一秒已报过就跳过"（`:1257`、`:1263`）做跨路径去重；恢复时读 Intent 里的 `progress`（服务端 `last_play_time`），沿用 `:3555` `ijkPlayer!!.seekTo(progress_history)`。**注意**：这意味着本项目**已经把"位置"持久化到服务端了**，只是没有做 Android 本地状态保存；且 `reportProgressNow` 有意**只读内存里的 `video_now`**（`:1252-1254` 注释说明是为了不在主线程摸 native 锁）。
- **"哔哩哔哩官方客户端"内部的续播实现无法从一手来源证实**（官方客户端闭源）⇒ **未证实**。可用的对照只有上面的官方文档口径（位置应由持有 Player 的 Service 拥有，App 负责持久化续播位置）与 Media3 的 `onPlaybackResumption` 责任划分。

---

## §4 建议清单（按优先级）

> 说明：以下"改哪里"均给仓库相对路径 + 行号。**实施状态见各项标题后的括注（26.10.11 更新）。**每一项都标了依据，便于否决。

### P0-1 给直播（与所有在线播放）补读超时与重连参数 —— 已实施（26.10.11，未含 reconnect_at_eof）

- **改哪里**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/player/PlayerActivity.kt` 的 `setDisplay()` 内选项块（`:875-899`，现有 `reconnect=1` 在 `:894`）。
- **怎么改**：在 FORMAT 类补 `rw_timeout=5_000_000`（或 10s）、`reconnect_streamed=1`、`reconnect_at_eof=1`、`reconnect_delay_max=5`；rtmp/rtsp 场景（若将来引入）改用 `rw_timeout` 而非 `timeout`。若担心影响长视频 seek，可只在直播分支追加。
- **期望效果**：① 半死连接（TCP 在、无字节）可在 5–10s 内返回读错误，不再无限期黑屏；② chunked 直播在断流/服务端收尾时能自动重连（这是当前 `reconnect=1` 单独做不到的）；③ 退避上限从默认 120s 压到 5s，缩短黑屏。
- **依据**：[官方源码] `Bilibili/FFmpeg` `avio.c:59`（`rw_timeout` 默认 0）、`http.c:1501`（`if (h->is_streamed && !s->reconnect_streamed) break;`）、`http.c:1504-1505`、`http.c:1508`、`http.c:170`；[官方文档] `doc/protocols.texi`（没有读超时会永久阻塞）。

### P0-2 取流级自动 failover：失败/停顿即重新 `getRoomPlayInfo` 并换线路 —— 未做（只做了已到手候选之间的轮换）

- **改哪里**：`app/src/main/java/com/RobinNotBad/BiliClient/activity/live/LiveInfoActivity.kt:142-149`（这里已经拿到 `codec.url_info[]` 全部线路，`:244-247` 还把它们做成了"路线N"）；取流入口 `app/src/main/java/com/RobinNotBad/BiliClient/util/TerminalContext.java:259`。
- **怎么改**：把"选第 0 条 URL"改成"选一条候选 + 失败时轮换"。两件事必须一起做：(a) CDN 线路轮换——`selectedHost` 失败后自动试下一条 `url_info[i]`；(b) **重连前重新调 `getRoomPlayInfo`**（`extra` 带 `expires`/`deadline`，旧 URL 可能已失效）；(c) 保留手动"路线N"作为覆盖手段。
- **期望效果**：单条 CDN 或单条 URL 失效时用户无感恢复，而不是黑屏到手点"路线2"。
- **依据**：[一手开源代码] biliup `crates/biliup/src/downloader/live/bilibili.rs:551-573`（`check_url_healthy` → 遍历同画质组所有 CDN → 全挂才报错）；[一手接口文档快照] `bilibili-API/docs/live/info.md:905-911`（`url_info[]` 即线路列表）、响应样例里 `extra` 的 `expires=`/`deadline=`。

### P0-3 加 App 层"播放位置停滞"看门狗 —— 已实施（26.10.11）

- **改哪里**：`PlayerActivity.kt` 的 `progressChange` 定时器（每 250ms 刷新 `video_now`，见 `:1172`、`:1183-1190`）附近；重建范式可直接复用 `:1637-1645`（`retryAfterPlayerError`）。
- **怎么改**：定时比较"当前位置是否前进"或"缓存是否增长"，若 N 秒（建议 15–30s）无变化且处于播放态，则走"重取流 + 重建 IjkMediaPlayer"。
- **期望效果**：覆盖"没有报错但也没数据"这一类当前完全无法自愈的场景。
- **依据**：[官方源码] ijkplayer 内对 `watchdog|no_progress|position_no|last_position|stall_timeout` 的 grep **零命中**（无内置看门狗）；[一手开源代码] biliup `httpflv.rs:368`（30s 无 chunk 即跳出）、bililive-go（`-rw_timeout` 60s）、BilibiliLiveRecorder `HttpRequestUtil.java:179-180`（read 120s）、blivedm `ws_base.py:265-269`（心跳+5s）；[二手佐证] ijkplayer issue #2764「如果一直没速度，onErro不会走」。

### P0-4 直播清晰度默认值 —— 不采纳（弱网可看优先，低码率是刻意取舍）

- **改哪里**：`TerminalContext.java:259`（写死 `qn=80`）。
- **怎么改**：与设置项里的清晰度联动（或至少提到 150/250）。
- **期望效果**：默认画质符合预期，减少"手动切清晰度"。
- **依据**：现状代码；[第三方实测] yt-dlp `bilibili.py:2607-2615` 的 qn 档位表（80/150/250/400/10000/…）。
- **⚠ 不要直接把设置项里的 `SettingsKeys.PLAY_QN` 接过来**：**直播与点播的 qn 不是同一套命名空间**——点播的设置页 `activity/settings/SettingQualityActivity.kt:19-23` 是 `360P→16 / 720P→64 / 1080P→80`（80 表示 1080P），而直播里 `80` 是"流畅"、`150` 才是"高清"。直接联动会把"用户选了 1080P"变成直播最低画质。需要一张显式的直播档位映射表（对应 `accept_qn` 的实际取值）。

### P1-1 主播放器补 Android 状态保存（进程被杀后能回到原位置） —— 已实施（26.10.11）

- **改哪里**：`PlayerActivity.kt`（当前**没有** `onSaveInstanceState`/`onRestoreInstanceState`，`onCreate` 在 `:410`、`onStart` 在 `:2584`）。
- **怎么改**：`onSaveInstanceState` 里只存原始类型（`video_now` 的秒数、`p` 索引、可选 `currentQn`），`onRestoreInstanceState`/`onCreate` 里优先用 saved state 覆盖 Intent 里的服务端进度。
- **期望效果**：低内存被杀、旋转/配置变更后回到原位置，而不是退回服务端 `last_play_time`（可能落后几十秒）。
- **依据**：[官方文档] `saving-states`——只存"primitive types and simple, small objects"、"依赖用户输入或导航的瞬时状态（如列表滚动位置）"属于 saved state 的典型用途、且 saved state 只在 Activity 停止时落盘；本项目已有 `reportProgressNow` 上报链路（`PlayerActivity.kt:1249-1263`）可复用同一数据源。

### P1-2 重挂 Surface 时用 `video_now` 而不是进度条的值 —— 已实施（26.10.11）

- **改哪里**：`PlayerActivity.kt:2127-2134`（`onSurfaceReattached` 内 `ijkPlayer?.seekTo(seekbar_progress.progress.toLong())`，`:2132`；`:2129` 注释写着"SurfaceView 还需要 seek 回当前位置强制出画面"）。
- **怎么改**：把 seek 的目标从 `seekbar_progress.progress` 换成 `video_now`（`:200`，由 250ms 定时器维护），与同一文件里其它重建路径（`:1645`、`:2496`、`:2902`）保持同一口径。
- **期望效果**：用户正在拖进度条时切后台再回前台，不会 seek 到"拖动中的位置"而跳错；取值也不再依赖 UI 控件状态。
- **依据**：[官方文档] SurfaceView 参考页（`SURFACE_LIFECYCLE_FOLLOWS_VISIBILITY`：不可见即销毁 Surface ⇒ 回来必须重挂且需要一次 seek 才能出画面）；[官方源码] `ui/PlayerView.java`（Media3 由播放器自己管 surface 生命周期，App 不该用 UI 控件状态代替播放位置）。
- **补充（26.10.11 本地核实，解释了用户报的"位置会出错"的量级）**：`enable-accurate-seek` 的默认值是 **0**（`ff_ffplay_def.h` 的 `ffp_reset_internal()` 里 `ffp->enable_accurate_seek = 0;`），主播放器没有设置它 ⇒ 这里的 `seekTo()` 是**不精确 seek**，会落到目标点**之前**最近的关键帧。于是"切后台回来重挂 Surface 就 seek 一次"的真实效果是**位置反复往回跳一个关键帧间隔（实际几秒到十几秒）**，而不是只差定时器那 250ms；连续切几次后台会累积。这比原报告的表述更贴近用户的症状，也是"修 P1-2 比调定时器更要紧"的原因。若还想把跳变压到最小，可评估 `enable-accurate-seek=1`（`player/VideoPlayerCore.kt:133` 已开）。

### P1-3 后台播放的保活与音频焦点 —— 未做

- **改哪里**：`app/src/main/java/com/RobinNotBad/BiliClient/service/PlaybackService.kt`（`:60` 起服务、`:104-109` `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`、`:129` `onDestroy`、`:164` `servicePositionMs`、`:191-197` 通知更新）。当前**没有 WakeLock、没有音频焦点处理**。
- **怎么改**：评估在后台播放期间持 `PARTIAL_WAKE_LOCK`；接入 `AudioManager` 音频焦点（被其它应用抢占时暂停/降音量）；并注意官方点名的"暂停/停止/失败超过 10 分钟会被移出前台服务状态"这一行为，决定是否需要在通知交互上做补偿。
- **期望效果**：后台长时间播放不被 CPU 休眠掐断；被来电/其它播放器抢占时行为可预期。
- **依据**：[官方文档] `bg-playback`——播放器与 MediaSession 应放在 Service 内、需 `foregroundServiceType="mediaPlayback"`、以及"暂停/停止/失败超过 10 分钟会被自动移出前台服务状态"。
- **备注**：**该项需要真机验证**（手表端 Doze/熄屏行为与手机差异大），见 §5。

### P2-1 删掉"无效选项"（零风险清理） —— 部分实施（26.10.11 只删了 max_cached_duration）

- **改哪里**：
  1. `app/src/main/java/com/RobinNotBad/BiliClient/activity/video/ShortVideoPlayerActivity.kt:1011` 的 `max_cached_duration=3000` —— **不是 ijkplayer 选项**，应删（或换成真实存在的替代项）。
  2. `PlayerActivity.kt:892` 的 `dns_cache_clear=1` —— 在 `dns_cache_timeout` 默认 0 下**空转**；要么一并设 `dns_cache_timeout`（比如 60s）使其真正生效，要么删掉以免误导。
- **期望效果**：去掉"看起来配了其实没配"的假安全感；减少未来排查时的干扰。
- **依据**：[官方源码] `bilibili/ijkplayer` master 与 `k0.8.8` 的 `.c/.h/.java` 对 `max_cached_duration` grep 零命中（只有只读属性 `FFP_PROP_INT64_VIDEO_CACHED_DURATION=20005`，`IjkMediaPlayer.java:123-124`）；`Bilibili/FFmpeg` `tcp.c:405-411`（`if (s->dns_cache_timeout > 0) { if (s->dns_cache_clear) … }`）与 `tcp.c:81`（默认 0）。

### P2-2 重新评估短视频播放器的激进参数 —— 未做

- **改哪里**：`ShortVideoPlayerActivity.kt:993-1012`（`:1002` `analyzeduration=1`、`:1003` `probesize=1024`、`:1008` `packet-buffering=0`、`:1012` `infbuf=1`、`:1009` `max-buffer-size=8MB`、`:1010` `min-frames=5`）。
- **怎么改**：`infbuf=1` 会让 `max-buffer-size`（`:1009`）**完全失效**，两者同时设置属于自相矛盾——要么去掉 `infbuf`，要么明确接受内存增长。`analyzeduration=1`/`probesize=1024` 属于社区做法，可能在部分流上探测失败，建议做 A/B 或至少把 `probesize` 提到 16KB 量级。
- **期望效果**：内存行为可解释；减少"个别视频黑屏/无画面"类问题。
- **依据**：[官方源码] `ff_ffplay.c:3466-3474`（`infbuf` 短路 → `dcc.max_buffer_size` 只在那一处被消费，`:3467`）、`options_table.h:39`/`:61`、`utils.c:3642-3644`（`analyzeduration=0` 实为 5s）与 `utils.c:4149`（探测不足的官方告警）；[二手佐证] issue #3788/#3792（`infbuf=1` 时内存不断上升）。

### P2-3 直播协议层的中期选项：把 HLS 当成兜底而不是丢弃 —— 部分实施（26.10.11 只做了候选排序与运行时轮换）

- **改哪里**：`LiveInfoActivity.kt:142-143`（当前硬取 `stream[0].format[0].codec[0]`）。
- **怎么改**：保留 FLV 为主（低延迟），但在 FLV 连续失败 N 次后**切到 `protocol_name == "http_hls"` 的那一路**（请求里 `format=0,1,2` 已经把 fmp4/ts 拿回来了，只是没用）。
- **期望效果**：获得 3.2 表里 HLS 的"分段级恢复检查点"，作为 FLV 补不动的最后一道兜底。
- **依据**：[官方文档] RFC 8216（segment 级重试、`EXT-X-MAP`、`EXT-X-ENDLIST`）、Apple LL-HLS、RFC 9112 §9.5、RFC 1122 §4.2.3.6、Fastly `between_bytes_timeout`；[一手开源代码] bililive-go `src/live/bilibili.go:227`/`:307-311`（同一份响应里同时保留 flv 与 m3u8 两条路）。

---

## §5 未证实 / 存疑 / 需要真机验证的点

**A. 本轮明确"未证实"的事实**

1. **`max_cached_duration`**：在 `bilibili/ijkplayer` master 与 `k0.8.8` 内 grep 零命中 ⇒ 判定"不是 ijkplayer 选项"，但**不能排除某些第三方 fork 自定义了它**；若本项目链接的 `.so` 来自某个非官方 fork，则结论需要重核。**26.10.11 收口**：§2.4 已用本仓库 `app/libs/arm64-v8a/*.so` 精确串实测，该选项名在两个库里都 0 命中 ⇒ **本条已证实，"第三方 fork 自定义"这个风险已排除**。
2. **`stream_ttl` 的单位与语义**：文档快照该行说明为空（`bilibili-API/docs/live/info.md:911`），三份主流开源实现均未消费；"URL 在 stream_ttl 秒后失效导致中途卡顿"的因果链**未证实**（可证实的只是播放 URL 带 `expires`/`deadline` 签名）。
3. **B站的拉流协议栈 / CDN 选线算法 / 低延迟方案（含 LSS、BLSS 这类称呼）**：没有取得任何官方规范或官方技术文章；"LSS/BLSS"**不应作为官方协议名**写进任何文档。
4. **Adobe FLV/F4V 规范正文**：官方 PDF 抓取失败（403）、镜像不可达 ⇒ "Adobe 规范只定义容器、不定义 HTTP 传输"这一点**没有亲眼确认**（但 IANA media-types 里查不到 `video/x-flv`，可支持"HTTP-FLV 无标准、属事实约定"的结论）。
5. **`framedrop=-1` 的民间解释**（"丢所有非参考帧"）：在源码里找不到任何依据；源码里的 `-1` 语义是"仅当主时钟不是视频时才丢"，且早丢路径因 `continuous_frame_drops_early > -1` 恒真而实际不丢。
6. **`fflags=nobuffer` 对 FLV/HLS 直播的实际效果**：选项存在且有官方描述（`reduce the latency introduced by optional buffering`），但**具体 demuxer 是否使用该标志本轮未逐一核实**。
7. **`reconnect_on_network_error`**：在本 fork 的 `http.c` 选项表里**不存在**（上游新版 FFmpeg 才有）；如果本项目 `.so` 内的 FFmpeg 版本与 fork tag 不一致，需要重核。**26.10.11 收口**：§2.4 的 `.so` 精确串实测，`libijkffmpeg.so` 与 `libijkplayer.so` 里都 0 命中 ⇒ **本条已证实**（P0-1 因此不该写这个选项）。
8. **Media3 `MediaItem.Builder.setStartPositionMs` 的确切语义**：本轮读到的同名 javadoc 位于 `ClippingConfiguration.Builder` 内（`MediaItem.java:1844`/`:1850`/`:1872-1879`），`MediaItem.Builder`（`:69-638`）区间内没有 `StartPosition` 成员；**"start position 是否在每次 seek-to-default-position 时被重新应用"未证实**。
9. **Media3 播 HTTP-FLV 直播的实际行为**：`FlvExtractor.java` 在 release 分支确实存在，`ProgressiveMediaSource` 的默认 error policy 是 `DefaultLoadErrorHandlingPolicy`，且 `C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE` 有 6 次重试额度；但**"chunked 直播下 ProgressiveMediaSource 能否正确重连/表现如何"未做实测**。
10. **Apple HLS Authoring Specification 的具体阈值数字**：页面与 JSON 都取到了，但**未逐条提取到可引用的阈值**；同样地，DASH-IF ingest 文档、Akamai 与 CloudFront 的部分超时默认值**只确认了页面存在，未取到数值**。
11. **CloudFront / Akamai 的读超时默认值**：未证实（CloudFlare 的 125s/30s 与 Fastly 的语义是取到的一手文本）。
12. **`infbuf` 内部"自动开启"分支可达性**：`ff_ffplay.c:3323-3324`（`if (ffp->infinite_buffer < 0 && is->realtime) …`）按源码推断是死代码（`ffp_reset_internal` 之后 `av_opt_set_defaults` 会把默认值 0 覆盖上去），但这是**源码级推断**，未做运行时验证。

**B. 需要真机 / 实测才能定的**

1. **`rw_timeout`（FORMAT）在本项目实际链接的 `.so` 上是否真的到达 tcp 层**：透传链已按源码完整证明，但**没有抓包/日志验证**。验证方法：设 5s 后对一个被防火墙丢包的地址建连，看是否 5s 内 `onError`。**26.10.11 补充**：§2.4 已证实 `rw_timeout` / `addrinfo_timeout` / `reconnect_streamed` 这些**选项名确实存在于本仓库的 `libijkffmpeg.so`** ⇒ "选项名根本不存在"这一风险已排除；剩下要验的只是"落到 socket 层后的实际行为"，本条仍需真机。
2. **B站直播响应是否真的走 chunked（`Transfer-Encoding: chunked`）**：这直接决定 `reconnect_streamed=1` 是否必需。建议抓一次响应头确认。
3. **`reconnect` 在真实 CDN 上的触发条件**：需要构造"服务端不响应"和"服务端正常 EOF"两种故障各验一次。
4. **`infbuf=1` 在手表端的内存曲线**：issue 里的报告是 iOS/手机环境，手表 RAM 更小，必须实测（否则 P2-2 的建议只是方向性的）。
5. **`probesize=1024` / `analyzeduration=1` 的失败率**：需要在一批短视频上统计"探测失败/黑屏"的比例。
6. **`dns_cache_clear` 在 `dns_cache_timeout=0` 下确实无效**：源码逻辑清楚，但没跑过；验证方法是加日志或对比换网行为。
7. **后台播放是否需要 WakeLock**：取决于手表端熄屏/Doze 策略，需真机长测（≥30 分钟后台播放）。
8. **API 34 的 `setSurfaceLifecycle`**：本项目 `compileSdk 34`、`minSdk 19`，若想避免"被遮挡即销毁 Surface"，可评估 `SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT`，但**手表端系统版本分布未知，需确认设备 API 等级**。
9. **Media3 相关默认值是否与本项目实际会用到的版本一致**：本轮读的是 `androidx/media` `release` 分支（当前时点），**若将来引入 Media3，需按锁定的版本号重核**（尤其 `ExoPlayer.java:520` 那句"默认已装 LivePlaybackSpeedControl"）。

**C. 方法论上的存疑**

1. 本报告大量依赖"官方源码"作为唯一权威，但 **ijkplayer 官方对超时/重连/低延迟从未给出成体系方案**（README 与 `doc/` 都没有），因此 §4 里 P0-1/P0-3 的"怎么改"是基于**源码语义 + 第三方开源实现**推出的工程建议，**不是官方推荐**。
2. 第三方开源客户端的机制（biliup/bililive-go/BilibiliLiveRecorder/blivedm）都是**录播器或弹幕客户端**，与"边下边播的播放器"在缓冲策略上不完全可类比；引用时已按 [一手开源代码] 标注，请勿当成"官方做法"。

---

## §6 来源清单

### 官方源码（bilibili/ijkplayer，均为 `master`）

1. https://raw.githubusercontent.com/bilibili/ijkplayer/master/ijkmedia/ijkplayer/ff_ffplay_options.h — `ffp_context_options[]` 全表（实读 `:60-151`）｜官方源码
2. https://raw.githubusercontent.com/bilibili/ijkplayer/master/ijkmedia/ijkplayer/ff_ffplay_def.h — `MAX_QUEUE_SIZE`/`DEFAULT_MIN_FRAMES`/`MIN_MIN_FRAMES`/`MAX_ACCURATE_SEEK_TIMEOUT`/`DEFAULT_*_HIGH_WATER_MARK_IN_MS`｜官方源码
3. https://raw.githubusercontent.com/bilibili/ijkplayer/master/ijkmedia/ijkplayer/ff_ffplay.c — `:339`/`:1373`/`:1705`/`:3034`/`:3105-3109`/`:3122`/`:3323`/`:3466-3474`/`:3611-3625`/`:3994-3998`/`:4043-4047`/`:4578-4580`/`:4664`/`:5029`｜官方源码
4. https://raw.githubusercontent.com/bilibili/ijkplayer/master/android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IjkMediaPlayer.java — `:123-124`/`:405-432`/`:906-917`/`:953-959`｜官方源码
5. https://raw.githubusercontent.com/bilibili/ijkplayer/master/init-android.sh — `IJK_FFMPEG_UPSTREAM=https://github.com/Bilibili/FFmpeg.git`、`IJK_FFMPEG_COMMIT=ff4.0--ijk0.8.8--20210426--001`｜官方源码
6. https://raw.githubusercontent.com/bilibili/ijkplayer/master/ios/IJKMediaPlayer/IJKMediaPlayer/IJKFFOptions.m — `+optionsByDefault`（`:37-55`）｜官方源码
7. https://raw.githubusercontent.com/bilibili/ijkplayer/master/ios/IJKMediaDemo/IJKMediaDemo/IJKMoviePlayerViewController.m — 直播示例（`iformat=ijklas`、`find_stream_info=0`）｜官方源码
8. https://raw.githubusercontent.com/bilibili/ijkplayer/master/README.md — 无参数建议｜官方源码
9. https://github.com/bilibili/ijkplayer/tree/master/doc — 只有 `preflight_checklist.md`｜官方源码
10. ijkio 协议族（同目录）：`ijkio.c`、`ijkioffio.c`、`ijkavformat.h`、`ijkioprotocol.c`、`ijkiomanager.c`（`:360-366`）、`ijkiourlhook.c`（`:253-254`、`:321-362`）、`ijkurlhook.c`（`:243`）｜官方源码

### 一手开源代码（Bilibili/FFmpeg @ `ff4.0--ijk0.8.8--20210426-001`）

11. `libavformat/avio.c` — `:59`（rw_timeout 默认 0）、`:307-353`、`:386-389`｜一手开源代码
12. `libavformat/tcp.c` — `:69-85`（timeout −1 / connect_timeout −1 / addrinfo_timeout −1 / dns_cache_timeout 0 / dns_cache_clear 0）、`:405-421`、`:757`、`:779`｜一手开源代码
13. `libavformat/http.c` — `:140-175`（reconnect 系列默认值，确认无 `reconnect_on_network_error`）、`:195-251`、`:536-548`、`:988-1000`、`:1476-1516`｜一手开源代码
14. `libavformat/options_table.h` — `:37-69`（probesize 5000000 / analyzeduration 0 / fflags nobuffer）｜一手开源代码
15. `libavformat/utils.c` — `:416-438`、`:537-574`、`:3642-3644`、`:4149`｜一手开源代码
16. 基址：https://raw.githubusercontent.com/Bilibili/FFmpeg/ff4.0--ijk0.8.8--20210426-001/libavformat/ ｜一手开源代码

### 官方文档（Android / AndroidX / RFC / Apple / FFmpeg / CDN）

17. https://developer.android.com/topic/libraries/architecture/saving-states ｜官方文档
18. https://developer.android.com/media/media3/session/background-playback ｜官方文档
19. https://developer.android.com/reference/android/view/SurfaceView ｜官方文档
20. https://developer.android.com/reference/android/view/TextureView ｜官方文档
21. https://www.rfc-editor.org/rfc/rfc8216.txt （HTTP Live Streaming）｜官方文档
22. https://developer.apple.com/documentation/http-live-streaming/enabling-low-latency-http-live-streaming-hls ｜官方文档
23. https://www.rfc-editor.org/rfc/rfc9112.txt 、https://www.rfc-editor.org/rfc/rfc9110.txt 、https://www.rfc-editor.org/rfc/rfc1122.txt （§4.2.3.6 TCP Keep-Alives）｜官方文档
24. https://raw.githubusercontent.com/Bilibili/FFmpeg/ff4.0--ijk0.8.8--20210426-001/doc/protocols.texi （rw_timeout / http / reconnect）｜官方文档
25. https://www.fastly.com/documentation/reference/api/services/backend/ （`between_bytes_timeout` 语义）｜官方文档
26. https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-5xx-errors/error-524/ （读超时 125s / 写超时 30s）｜官方文档
27. https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/DownloadDistValuesOrigin.html ｜官方文档

### 官方源码（androidx/media `release` 分支）

28. `libraries/exoplayer/.../DefaultLoadControl.java`（`:64`/`:76`/`:90`/`:102`/`:115`/`:118`/`:130`）｜官方源码
29. `libraries/exoplayer/.../DefaultLivePlaybackSpeedControl.java`（`:58`/`:64`/`:70`/`:76`/`:82`/`:310-327`/`:336-344`）｜官方源码
30. `libraries/exoplayer/.../ExoPlayer.java`（`:327`/`:520`/`:1202-1216`）｜官方源码
31. `libraries/exoplayer/.../upstream/DefaultLoadErrorHandlingPolicy.java`（`:37`/`:43`/`:128-132`/`:139-147`）｜官方源码
32. `libraries/datasource/.../DefaultHttpDataSource.java`（`:242`/`:245`）｜官方源码
33. `libraries/common/.../MediaItem.java`（`:1322`/`:1325`/`:1844`/`:1850`/`:1872-1879`/`:1983`/`:2009`/`:2021`）｜官方源码
34. `libraries/common/.../Player.java`（`seekToDefaultPosition()` / `clearVideoSurface()` javadoc）｜官方源码
35. `libraries/ui/.../PlayerView.java`（`:157`/`:191`/`:292-296`/`:655-689`）｜官方源码
36. `libraries/extractor/src/main/java/androidx/media3/extractor/flv/FlvExtractor.java`（存在性）｜官方源码
37. `libraries/exoplayer/.../source/ProgressiveMediaSource.java`（默认 `DefaultLoadErrorHandlingPolicy`）｜官方源码
38. 基址：https://raw.githubusercontent.com/androidx/media/release/libraries/ ｜官方源码

### 一手开源代码（第三方客户端/工具）

39. https://raw.githubusercontent.com/biliup/biliup/master/crates/biliup/src/downloader/live/bilibili.rs （`:551-573`、`:678-695`）｜一手开源代码
40. https://raw.githubusercontent.com/biliup/biliup/master/crates/biliup/src/downloader/httpflv.rs （`:368`、`:375-377`）｜一手开源代码
41. https://raw.githubusercontent.com/biliup/biliup/master/crates/biliup/src/downloader/client.rs （`:24`/`:25`/`:28`/`:31`/`:40`）｜一手开源代码
42. https://raw.githubusercontent.com/biliup/biliup/master/crates/biliup/src/downloader/hls.rs （`:24-30`/`:129-136`）｜一手开源代码
43. https://raw.githubusercontent.com/hr3lxphr6j/bililive-go/master/src/live/bilibili.go （`:26`/`:162`/`:207-227`/`:307-311`）｜一手开源代码
44. https://raw.githubusercontent.com/hr3lxphr6j/bililive-go/master/src/recorders/ffmpeg.go （`:356-359`）｜一手开源代码
45. https://raw.githubusercontent.com/hr3lxphr6j/bililive-go/master/src/recorders/recorder.go （`:54-64`/`:68-70`/`:1104-1130`/`:1154-1166`）｜一手开源代码
46. https://raw.githubusercontent.com/hr3lxphr6j/bililive-go/master/src/config.go （`:1130` `TimeoutInUs: 60000000`）｜一手开源代码
47. https://raw.githubusercontent.com/nICEnnnnnnnLee/BilibiliLiveRecorder/master/src/main/java/nicelee/bilibili/… （`HttpRequestUtil.java:85-86`/`:179-180`/`:235`/`:334-348`、`ThRecord.java:53`/`:67-68`/`:85-87`、`M3u8Downloader.java:21`/`:25`/`:87-88`、`FlvChecker.java`）｜一手开源代码
48. https://raw.githubusercontent.com/xfgryujk/blivedm/master/blivedm/clients/ws_base.py （`:265-269`/`:278`/`:280`/`:298`）｜一手开源代码
49. https://github.com/bilibili/flv.js ｜官方源码（B站官方 HTTP-FLV 播放器实现）
50. https://github.com/bilibili/bbip ｜官方源码（高性能 IP 解析/选路）
51. https://github.com/bilibili/quiche 、https://github.com/bilibili/nginx_quic_module 、https://github.com/bilibili/nginx_quic_stack ｜官方源码（B站 QUIC 边缘的代码级证据）
52. https://github.com/bilibili/discovery 、https://github.com/bilibili/mahimahi ｜官方源码

### 一手文档快照 / 第三方实测

53. `bilibili-API/docs/live/info.md`（仓库内快照）`:798-1103`、`:811`、`:812`、`:850`、`:882`、`:905-911`、`:1003`、`:1020`、`:1032`、`:1049`、`:1059`、`:1073`、`:1078` ｜一手接口文档快照（第三方抓取整理，已停止维护，仅作历史快照）
54. https://raw.githubusercontent.com/yt-dlp/yt-dlp/master/yt_dlp/extractor/bilibili.py （`:2607-2615`/`:2625-2636`/`:2646-2655`）｜第三方实测

### 二手（仅作旁证）

55. https://github.com/bilibili/ijkplayer/issues/2102 —「How to set network timeout」维护者 bbcallen 回复 **"See demo."**｜二手（含官方 contributor 发言）
56. https://github.com/bilibili/ijkplayer/issues/62 —「rtsp 无重连机制」，label `not-on-plan`，bbcallen：「我不知道有没有现成的重连机制。」｜二手
57. https://github.com/bilibili/ijkplayer/issues/837 — 必须注释掉 `av_dict_set(...,"timeout",NULL,0)` 才能让 timeout 生效（与源码互相印证）｜二手
58. https://github.com/bilibili/ijkplayer/issues/2764 —「因为在buffering的时候，如果一直没速度，onErro不会走」｜二手
59. https://github.com/bilibili/ijkplayer/issues/1886 —「OPT_CATEGORY_FORMAT 的 timeout 参数如果不设置的话，默认是多少？」（至今 open，官方从未回答）｜二手
60. https://github.com/bilibili/ijkplayer/issues/3788 、https://github.com/bilibili/ijkplayer/issues/3792 —「infbuf 设置为无限读…内存不断上升」｜二手
61. https://github.com/bilibili/ijkplayer/issues/4320 、https://github.com/bilibili/ijkplayer/issues/5595 、https://github.com/bilibili/ijkplayer/issues/4437 、https://github.com/bilibili/ijkplayer/issues/3254 、https://github.com/bilibili/ijkplayer/issues/2091 、https://github.com/bilibili/ijkplayer/issues/908 、https://github.com/bilibili/ijkplayer/issues/143 、https://github.com/bilibili/ijkplayer/issues/5073 — 社区配置片段与低延迟求助｜二手
