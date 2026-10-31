# B 站接口接入常识与风控要点（长期备忘）

> 来源：`docs/archive/watch-optimization-research.md` §10（26.10.04 一手核对），2026-10-10 抽出为独立活文档。
> 用途：改 `api/` 下任何接口前查这里，尤其**错误码语义、风控处置、取流有效期、弹幕协议取舍**。
> 接口字段级快照在 `bilibili-API/`；网络层实现与坑见 `docs/architecture-map.md` §6 与 §7.35。

## 1. 来源与可信度

### 1.1 上游接口字典已永久关停 ⚠️

- `SocialSisterYi/bilibili-API-collect`（20,191★）README 已改为 `# Deprecated` / "本仓库停止维护并永久关停"，并附**律师函**措辞（指控"对非公开 API 及其调用逻辑、参数结构、访问控制及安全认证机制进行系统性收集并传播"），落款 **2026-01-28**；官方文档站 `socialsisteryi.github.io/bilibili-API-collect/` 现返回 404。
  来源：<https://github.com/SocialSisterYi/bilibili-API-collect>、<https://raw.githubusercontent.com/SocialSisterYi/bilibili-API-collect/master/README.md>
- **本项目已经在仓库根目录自带快照 `bilibili-API/`（197 个文件，`docs/` 下 195 篇 md）**，它是关停前的版本。另有贡献者复刻仓库 <https://github.com/pskdje/bilibili-API-collect>（master 同步至 2026-01-25），本文接口路径以这两份快照为准。
- **战略含义**：不要再把"抄现成端点清单"当长期模式。应把本项目**实际依赖的端点、参数、错误码固化为仓库内自有契约文档**，并建立"上游变更 → 快速自检"的机制。另外注意：该文档集为 **CC BY-NC 4.0**，且上游已收到律师函，仓库内自带快照的**合规风险**需要在发布前评估。

### 1.2 来源清单

**接口字典（本文结论的直接依据）**

- bilibili-API-collect（**已永久关停，2026-01-28**）— https://github.com/SocialSisterYi/bilibili-API-collect ；关停说明 https://raw.githubusercontent.com/SocialSisterYi/bilibili-API-collect/master/README.md
- bilibili-API-collect 贡献者复刻（快照 2026-01-25，用于本次接口核对）— https://github.com/pskdje/bilibili-API-collect
- 本仓库自带快照 `bilibili-API/` （关停前版本，197 文件 / 195 篇 md）

**纠偏 / 旁证来源**

- SponsorBlock 官方 README（用于纠偏"是否支持 B 站"）— https://raw.githubusercontent.com/ajayyy/SponsorBlock/master/README.md
- BiliRoaming（已 archived，收到律师函）— https://github.com/yujincheng08/BiliRoaming ；https://m.ithome.com/html/973202.htm
- B 站官方口径（正文可读的少数来源）：官方下载中心 https://app.bilibili.com/ ；腾讯应用宝 https://sj.qq.com/appdetail/tv.danmaku.bili ；小米应用商店 https://app.mi.com/details?id=tv.danmaku.bili
- 说明：`openhome.bilibili.com/doc`、`open-live.bilibili.com/document/`、`www.bilibili.com/blackboard/help.html` 等官方页均为 JS 渲染空壳，**只能读到标题，读不到正文**。
- 本仓库实测：其余结论均标注了 `文件:行号`，可直接核对。（归档文档另收有 Wear OS 官方文档与竞品仓库清单，与接口无关，不在此列出。）
- **B 站官方 Android 客户端静态分析（本地逆向资料，不入库）**：对 `tv.danmaku.bili` 9.15.0 / 9.16.1 的 APK 做 apktool + jadx 反编译后抽取的接口表（574 个 Retrofit 接口类 / 1738 方法、93 个 gRPC 服务 / 743 方法）。产物在本地 `biliapp/`（已被 `.gitignore` 忽略，仓库里看不到），下文凡标「9.16.1 静态分析」的结论都出自它——**是官方实现的事实，不是实测响应**。

### 1.3 可信度分级：哪些是一手实测、哪些是二手/待核实

**一手实测（可直接依赖）**

- §3 第 1~4、8、9 条：全部标注了 `bilibili-API/...md:行号` 原文，可直接核对。
- §3 第 5 条：搜索风控原文（快照 `docs/search/search_request.md:3`）**另加本仓库实测**——`search.bilibili.com/all?keyword=...` 直接返回 `<title>验证码_哔哩哔哩</title>`，确认 Web 页面模拟已不可用。
- §3 第 6 条：快照 `docs/misc/errcode.md` 有更长列表，本文**只逐字核对了 §3 提到的这几条**。
- §3 第 11 条：SponsorBlock 官方 README 全文抓取成功，确认其未提及 B 站。
- §2 速查表三列（登录 / wbi / csrf）：按快照文档逐端点核对。
- §8 举报：四个接口的匿名可达性是**本仓库直接打请求实测**的（`-352` / `-101` / `-400` / `-3` 四个返回码都是实测值）；理由码 0~8 与官方客户端字符串的对应关系来自 9.16.1 静态分析 + 快照文档双向核对。

**二手 / 待核实（用到时须再验）**

- GitHub `RobinNotBad/BiliClient` 返回 404（上游主仓只在 Gitee）；Gitee 无公开 API，`71★ / 1322 commits / 状态「暂停」` 取自网页。
- `Darock-Studio/Darock-Bili`（Apple Watch 版）因匿名 API 限流（HTTP 403）**未能核实**。
- `qingyiwebt/Biliw`、`nonomal/bilibili-for-AppleWatch` 仅见搜索结果中的个位数 star，**未核实**。
- Wear OS 是否存在其他仍在维护的 B 站客户端：**未查到**。
- **「Android 9+ 默认禁止明文 HTTP」未取到可引用的官方原文**：`developer.android.com/privacy-and-security/security-config` 与 `/about/versions/pie/android-9.0-changes-all` 均只读到导航或被截断（入口存在）。本仓库相关的依据只有 `bilibili-API` 快照 README 的"强制使用 https 协议"。
- **SponsorBlock API 正文与"B 站是否被其服务端收录"**：`wiki.sponsor.ajay.app` 抓取失败；其 README 全文未提 B 站 → 只能确认"官方目标平台是 YouTube"，不能确认"B 站被收录"。
- **官方 App 底部 Tab 的具体数量与顺序**：官方页面全是 JS 空壳，商店页仅能确认存在"我的"Tab；归档文档 §7.4 的模块划分是按官方子站与接口域反推的**模块划分**，不是 Tab 顺序断言。
- **动态发布的完整参数取值表**（定时 / 可见范围的字段枚举）：只读到接口与部分字段名，未逐字段核对 `bilibili-API/docs/dynamic/publish.md` 全文。
- **`-352` / `-412` / `-111` 之外的错误码语义**：见上，未逐条核对。
- **标「9.16.1 静态分析」的接口事实**（§3 第 15 条、§6 末尾两条、§8 的理由码表）：来自官方 APK 反编译出的 Retrofit 注解与 Moss 服务桩，**能证明客户端在发什么请求，不能证明服务端对这些请求的响应**。其中 §6 的 `x-bili-gaia-vtoken` 是「官方客户端会带这个头」的事实，本项目发 `batch-deal` 时偶发失败是否真由它引起，**尚未实测复现**——按「用到时须再验」对待。

## 2. 接口速查表（登录 / wbi / csrf 三列）

三列含义：**登录 = 需 SESSDATA；wbi = 需 `w_rid`+`wts`；csrf = 需 `bili_jct`**。

**播放 / 字幕**

| 端点 | 用途 | 登录 | wbi | csrf |
| --- | --- | --- | --- | --- |
| `x/player/wbi/playurl` | 取流（**现行唯一 Web 取流**） | 可选 | 是 | 否 |
| `x/player/wbi/v2` | 字幕 / 章节看点 / 播放器元数据 | 字幕需登录 | 是 | 否 |
| `x/web-interface/view/conclusion/get` | AI 总结 / AI 字幕（`part_subtitle[].timestamp/content` 带时间戳） | — | — | 否 |
| `bvc.bilivideo.com/pbp/data` | 高能进度条（`step_sec` + `events.default[]`） | 否 | 否 | 否 |
| `x/v2/history/report` | 观看进度上报 | 是 | 否 | 否 |
| `x/web-interface/archive/like`、`coin/add`、`like/triple` | 点赞 / 投币 / 一键三连 | 是（**且需 buvid3**） | 否 | **是** |
| `x/v3/fav/resource/batch-deal` | 收藏 / 取消收藏（`resources` + `add_media_ids` / `del_media_ids`，26.10.23 起唯一写入口） | 是 | 否 | **是** |

**列表 / 内容**

| 端点 | 用途 | 登录 | wbi | csrf |
| --- | --- | --- | --- | --- |
| `x/v2/history/toview`(+`/add` `/del` `/clear`) | 稍后再看 | 是 | 否 | 写操作**是** |
| `x/web-interface/history/cursor`、`x/v2/history` | 历史（游标翻页） | 是 | 否 | 否 |
| `x/web-interface/wbi/index/top/feed/rcmd` | 首页推荐流 | 否 | 是 | 否 |
| `polymer/web-dynamic/v1/feed/all` | 关注动态流 | 是 | 否 | 否 |
| `x/v2/reply/wbi/main` | 新版评论主楼（旧 `x/v2/reply/main` 已废弃） | 是 | **是** | 否 |
| `x/v2/reply/reply`、`x/v2/reply/dialog/cursor` | 楼中楼 / 游标翻页 | 是 | 否 | 否 |
| `x/v2/reply/add`、`/action`、`/hate`、`/report`、`/del`、`/top` | 评论写操作 | 是 | 否 | **是** |
| `pgc/web/timeline`、`pgc/review/user`、`pgc/web/season/section` | 番剧时间表 / 我的追番 / 分集 | 是 | 否 | 否 |
| `pgc/player/web/playurl` | PGC 取流（**必须带 Referer `https://www.bilibili.com`**） | 大会员决定清晰度 | — | — |
| `x/web-interface/wbi/search/all/v2`、`search/type`、`search/square` | 搜索 / 热搜 | 是（Cookies 需足量） | **是** | 否 |
| `s.search.bilibili.com/main/suggest` | 搜索建议（**根字段是 `result.tag[]`，不是 `data`**） | 否 | 否 | 否 |
| `live.bilibili.com/room/v1/Room/get_status_info_by_uids` | 按 uid 查开播状态（开播提醒首选） | 否 | 否 | 否 |
| `live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo` | 直播弹幕 WS 地址 + token | 否 | 否 | 否 |
| `x/report/click/now` | 服务器时间（`data.now` 秒）；**明文 http 可用**（26.10.27 实测 200），给「设备时间不对」提示用，请求不带 Cookie | 否 | 否 | 否 |

## 3. 必须知道的硬约束

1. **`playurl` 取回的 url 有效期 120 分钟**（`bilibili-API/docs/video/videostream_url.md:102`）→ 手表端必须"播放前校验 + 失败重取"，**不能把 url 缓存进离线队列复用**。
2. **FLV 已下线**（同上 `:104`），**分 P 视频只返回单 P url**（`:106`），换 P 必须带对应 `cid` 重新取。
3. **`qn` 在 DASH 格式下无效**（`:117`）；未登录默认 `qn=32`（480P）、登录 `qn=64`（720P）；720P 以上需登录，1080P60/HDR/杜比/会员内容需大会员。
   → **手表端策略**：`fnval=16`（DASH）只取**音频轨 + 低清视频轨**；音频轨最高 192K 正好适合弱网省电；UI 上直说"登录可解锁 720P"。
4. **字幕需要登录**：`x/player/wbi/v2` 的 `data.subtitle.subtitles[]` 在未登录时为空数组，`subtitle_url` 指向 `//aisubtitle.hdslb.com/...`。→ 想把"听视频 + AI 字幕"做成核心体验，**登录是前置条件**。
5. **搜索风控很凶**：`bilibili-API/docs/search/search_request.md:3` 原文——"B站于2022年8月24日更新了搜索api……如果Cookies不足会返回 `-412` 搜索被拦截。**如果没有cookies的话，请在搜索之前先GET一遍 `https://bilibili.com` 以获取cookies**"。
   → 可直接照抄的低成本缓解：**搜索/空间类接口前先"暖一次首页"**。另外实测 `search.bilibili.com/all?keyword=...` 直接返回 `<title>验证码_哔哩哔哩</title>` → 手表端**绝不要走 Web 页面模拟**，并把"需要人工过验证码"当成**不可自动恢复的失败态**，直接给用户提示。
6. **错误码语义（不要硬编码直觉映射）**：`-352` = 风控校验失败（UA 或 wbi 参数不合法）；`-412` = 客户端 IP 被风控拦截；`-111` = csrf 校验失败；`-101` = 账号未登录；**`-403` 在评论场景是"Wbi 签名错误"而非无权限**（`bilibili-API/docs/comment/list.md:867` 原文："Wbi 签名错误时返回 -403 而非 -352"）。
7. **`-352` 与设备指纹是同一件事的两面**：`-352` 时响应体带 `v_voucher`、响应头带 `x-bili-gaia-vvoucher`，处置链路是 `x/gaia-vgate/v1/register` → `/validate` 换 `gaia_vtoken`。**持久化 buvid3/buvid4/UA、不随机换指纹**是根本解法（本项目 §2.4 已做到）。
8. **弹幕协议取舍**：protobuf 版 `x/v2/dm/web/seg.so`（及 wbi 变体、BFS 直链 `i0.hdslb.com/bfs/dm/{data}.bin`）字段丰富但**必须引入 protobuf 运行时并维护 `.proto`**；xml 版 `x/v1/dm/list.so`（**deflate 压缩，必须解压**）与 `comment.bilibili.com/{cid}.xml` 简单。
   → **手表端一屏只显示 3~5 行滚动弹幕，用 xml 即可**，不要为弹幕引入 protobuf 编解码链路；只有要做彩色/高级弹幕/按类型过滤/弹幕点赞数时才值得上 protobuf。
9. **弹幕池是栈语义**：`bilibili-API/docs/danmaku/danmaku_xml.md:3` 原文——"实时弹幕池容量有限（根据视频类型 500-8000 条不等），占满后再发送会使实时弹幕池底部的弹幕压入历史弹幕池（类似于堆栈）"；弹幕池类型 `0 普通 / 1 字幕 / 2 特殊(代码/BAS) / 3 互动池`。
10. **登录方式**：官方扫码登录（Web）为 `passport-login/web/qrcode/generate` + `/poll`；**TV 端另有独立链路** `x/passport-tv-login/qrcode/auth_code` + `/poll`（备用域 `passport.snm0516.aisee.tv`）。→ 手表**无键盘，密码/短信登录不可行**；**TV 端扫码比 Web 端扫码更贴合"无键盘设备授权"的心智模型**，建议手表端引导用户用 TV 端链路的二维码。密码登录还需先取 `passport-login/web/key`（返回 hash 盐 + 公钥，**有效期仅 20 秒**）。
11. **反向纠偏：SponsorBlock 不是 B 站的方案。** SponsorBlock 官方 README 原文定义其目标是"skip sponsor segments in **YouTube** videos"，支持 Invidious，**未提及 bilibili**。→ 本项目的"空降/跳过片头片尾"应走 `view_points` / `pbp/data` / 自建众包，不要引入 SponsorBlock 依赖。
12. **`fnval=1`（非 DASH）取回的是「音视频合一」的 MP4，不是纯视频；它的清晰度天花板就是 720P。**（26.10.13 匿名实测，稿件 `av170001` / `cid=279786`）
    - **实测**：`qn=64&fnval=1&platform=pc` → `durl[0].url` 下回来是 `format=hdmp4`，容器内 **`trak=2`**（`hdlr` 里同时有 `vide` 与 `soun`，且 `mp4a`+`esds` 存在）→ **视频轨 + 音频轨两条**。
    - **对照**：`fnval=16` 的 `dash.video[].baseUrl` 是 `trak=1` 的**纯视频**；`dash.audio[].baseUrl` 是 `trak=1` 的**纯音频**。
    - **降级行为**：用 `fnval=1` 请求 `qn=80` 会被**降级返回 `quality=64`**——1080P 及以上只有 DASH 拿得到。这正是"720P 及以下走非 DASH 老接口"这个分界的来源。
    - **这条实测是匿名口径（26.10.31 §3.88 补记）**：登录态/大会员下 `fnval=1&qn=80` 是否仍被降级**没有实测过**。倍速换流现在按用户档位如实请求（不再自己压到 720P），并用新增的 `PlayerApi.applyActualQuality()` 把响应里的 `quality` 回填成真实档位——服务端给到哪档就播哪档，真降级了播放器会提示「清晰度暂降到 X」。`quality` 在登录态下是否真回 80 待登录设备复核。
    - **对代码的含义**：`DownloadService` 里「`qn<=64` → `getVideo`（`fnval=1`）取单文件、`useDash=false`」以及「MP4 分支下载完要删掉同目录残留的 `audio.m4a`」都是**正确**的；**反过来把这条流当成"纯视频"再挂一路外部音轨，就会两轨同时出声**（即 §3.13 修的音频重复）。
    - **26.10.25 起下载侧把 DASH 双文件 remux 成单文件**：`util/MediaMerger.kt` 用 `MediaExtractor`（支持分片 MP4 的 `moof`）+ `MediaMuxer` 重封装，产物就是和 `fnval=1` 同形态的双轨 MP4；这不是「把 `fnval=16` 的纯视频当合一文件」，而是容器层真正写入了音频轨。播放侧的外部音轨路径保留给老缓存。
    - **未覆盖**：`platform=html5`（播放侧 mtvPlayer 分支）实测同样是 `trak=2`，与 `pc` 一致；但只测了 1 个稿件、未登录，大会员内容与其它稿件未复验。
    - **播放侧的含义（26.10.30 修「播放器内切不到 1080P」时确认，同日两轮）**：直播之外的播放链路**一律先试 DASH**（`getVideoDash` / `getBangumiDash`，播放器另起一路外部音频），DASH 失败/缺音轨才回退 `fnval=1` 的合一 MP4。低档位也走 DASH 的原因不是「低档只能 DASH 拿」，而是清晰度面板需要 DASH 的完整档位清单：`fnval=1` 会按请求档位裁剪清单，720P 请求只回 `accept_quality:[64,16]`，1080P60/4K 直接消失。**不能只靠"列表里有 1080P 就以为能拿 1080P"**：`accept_quality` 与 `fnval` 无关，`fnval=1` 时它照样列 1080P，只是 `quality` 被降到 64。判据与取流入口见 `player/PlayerStreamPolicy.kt`、`player/QualityAccessPolicy.kt` 与 `PlayerApi.getPlaybackVideo/getPlaybackBangumi`（`docs/architecture-map.md` §7.57）。
    - **`qn` 仍然决定「清单宽度」（26.10.30 实测）**：`qn` 在 DASH 下不决定返回哪条流，但决定服务端把哪些档位写进 `accept_quality` / `dash` 流列表。`qn=64` 请求只回 `[64,16]`，`qn=127` 才回完整档位；所以播放侧取流固定用 `qn=127`（`PlayerApi.QN_REQUEST_MAX`），真正播哪档由 `DashData.getVideoStream(playerData.qn)` 选。下载侧 `QualityChooserActivity` 早就是这个做法。
    - **4K（120）是否下发由大会员权限决定**：匿名请求 `qn=127&fnval=4048&fourk=1` 实测只回 `accept_quality:[116,80,64,32,16]`，**响应里连 120 都没有**（`fnval=4048` 已含 128/4K 位、`fourk=1` 也带了）。即「列表里没有 4K」不是参数错误，而是账号权限过滤；播放器对没权限账号补一个锁定提示位（见 §7.57），**4K 流本身只能用大会员账号验证**。
13. **搜索排序只长在「分类搜索」上，聚合搜索没有**（26.10.24 加搜索排序时核实）：`wbi/search/all/v2` 只接受 `keyword`（`bilibili-API/docs/search/search_request.md:21-27`），`order` 是 `wbi/search/type` 的参数——视频/专栏 `totalrank`（默认）/ `click` / `pubdate` / `dm` / `stow` / `scores`（专栏另有 `attention`），直播间 `online` / `live_time`，用户 `0` / `fans` / `level`（`:598`）。
    - **没有「最多点赞」**：视频搜索不支持按点赞数排序，别照用户口吻去拼一个不存在的值。
    - **要排序就得换接口**：本项目视频分类选「综合」时仍走 `all/v2`（它会把番剧结果混进第一页），选其余档位改走 `search/type?search_type=video&order=…`（不混番剧；条目字段与聚合结果同构，解析共用 `SearchApi.parseVideoEntry`）。
    - **`seid` 两条接口都会回**：`search/type` 的响应同样带 `data.seid`，`SearchApi.searchType` 已写回缓存，不会因为不再调 `all/v2` 而丢 `seid`。
    - **风控降级的形态要认出来**（26.10.24 实测）：降级时 `code` 仍是 **0**，`data` 里**既没有 `result` 也没有 `seid`**，只有一个 `v_voucher`——`{"code":0,"message":"OK","data":{"v_voucher":"voucher_…"}}`。**别把它当成"没有搜索结果"**：以前 `SearchApi` 返回 null、页面只剩空列表，看着像排序参数拼错了（用户就是这么报的）。判据只认 `v_voucher`——**真没结果的搜索照样带 `result`**（数组可能为空），不能因为 `result` 为空就报风控。遇到它时走 §3.14 的旧接口回退。
14. **搜索被风控时，旧接口（不带 wbi 签名）仍然可用——这是本仓库的一条实测逃生路**（26.10.24 验证）：
    - **现象**：短时间里连着切排序/换关键词，`wbi/search/type` 会开始回 §3.13 那种 `{"code":0,"data":{"v_voucher":"voucher_…"}}` 空壳（`code` 是 **0**，不是 `-412`）。同一条 URL 形状在冷却几十秒后自己恢复正常，所以**先别怀疑参数拼错**。
    - **逃生路**：把同一个请求打到**旧接口**（文档里标着"旧链接"的那条）——`https://api.bilibili.com/x/web-interface/search/type`（聚合搜索是 `.../search/all/v2`），**不带 `w_rid`/`wts` 签名、不带 `seid`**，实测照样 200：
      - `search_type=video&keyword=linux&order=click&page=1` → 20 条，`play` 严格降序（7972105 → 5017479 → 4438614 → …），说明**旧接口同样吃 `order`**；
      - 换成之前被风控挡下的中文长关键词（`linux操作系统零基础到入门` + `order=click`）→ 同样 20 条、同样降序。
    - **客户端做法**（`SearchApi.search` / `searchType`）：先走 wbi；落在**三种风控形态**任一上才退到旧接口再打一次——`v_voucher` 空壳、`code` 是 `-412`/`-352`、或请求层直接失败（风控回 HTML 拦截页 / 非 JSON 内容）；旧接口**也**拿不到才抛 `SearchApi.RiskControlException`（真断网时上抛原来的网络异常，不诬陷成风控），页面据此把排序按钮画成「因B站风控不可用」、点击提示「请稍候再试」并真的重试一次（见 `docs/review/fix-progress.md` §3.62）。
    - **别把它当长期方案**：旧接口是上游标了"旧链接"的地址，随时可能下线；回退只用来兜住"这一下搜不出来"，不要为了绕风控把主链路整体切过去。
    - **回退只发一次，不走任何重试**：`NetWorkUtil.getJson` 对 `-352`/`-412` 会重试到 `api_retry_max_times`（默认 5 次），`get()` 对 `<!doctype html>` 也会重试同样次数——而风控页恰恰常是 HTML。回退请求再走一遍就是「一次搜索最多 10 个请求」，只会把限流拖得更久（26.10.24 审查指出）。所以回退用新增的 `NetWorkUtil.getJsonOnce()`：它**直连一次**，连 doctype 重试都跳过；主链路的重试行为不变。
    - **旧接口的其它错误码不能当成"没有结果"**：`-400` 之类的非零 `code` 现在会抛 `SearchApi.SearchException`（带服务端错误码，`ApiErrorText.opFailed`），页面弹出来；只有 `code == 0` 且确实没有 `result` 才算"搜不到"。

15. **官方客户端的搜索主链路已经不在 REST 上了**（B 站 9.16.1 客户端静态分析，26.10.25 记录）：`grpc.biliapi.net` 上的 `bilibili.polymer.app.search.v1.Search` 提供 `SearchAll` / `SearchByType` / `SearchOGV` / `SearchComic` / `SearchReserve` / `SearchAddReserve` / `SearchDelReserve` / `SearchReserveCancel` 八个方法，`bilibili.app.interfaces.v1.Search` 另有 `Search` / `DefaultWords` / `Suggest3`；而官方 APK 的 574 个 Retrofit 接口类里**一条 `search/type` 都没有**。
    - **对本项目的含义**：我们（和所有第三方）还在用的 `wbi/search/{all/v2,type}` 是官方**已经弃用**的路径，风控压这条上并不奇怪——§3.13/§3.14 记的「短时间连切排序就被拦」正是这个背景。
    - **所以「退旧接口」只是兜底，不是长期方案**：真要摆脱风控得走 gRPC，但那要引入 moss/protobuf 运行时，与 `AGENTS.md`「不轻易引入新第三方库」冲突，暂不做。
    - **搜索建议同代际**：本项目走的是 `s.search.bilibili.com/main/suggest`（老独立域名），官方对应的是 gRPC `Search/Suggest3` 与 `Search/DefaultWords`。它目前仍然可用，没有风控表现，所以不急着换。

## 4. 按接口可行性收敛的「值得做 / 不值得做」

> 下表是 **26.10.04 的接口可行性判断**，不是最终产品裁决。用户裁决与此表有三处相反，以归档文档 `docs/archive/watch-optimization-research.md` §12 台账为准：① 「关注主播开播提醒」裁为**无计划**；② 漫画裁为**不做**（追漫列表无接口）；③ 「收藏夹批量整理」「发布动态/评论/弹幕」裁为**想要实现**（"需要输入"不构成否决理由），其中多项已落地。

**值得做（且本项目的接口基础已经具备）：**

| 方向 | 为什么 | 本项目现状 |
| --- | --- | --- |
| 稍后再看队列 | 本质是离线播放队列，无键盘 + 弱网点播 + 碎片时间三件事同时满足 | ✅ 列表/增删已有（`api/WatchLaterApi.java:23,48,59`），仅缺"未看完"分组 |
| 历史续播 | "打开就接着看/听" | ✅ 已有（`api/HistoryApi.java:37,174,257` + PGC 专用心跳） |
| 后台听视频 + AI 字幕 | 圆屏天生适合"一屏一句"；音频轨省流省电 | ✅ 听视频、字幕、AI 字幕已有；**缺的是二者组合的"车载/播客式"体验打磨** |
| 私信通知 + 速回 | 手表最强场景 | ✅ 文本收发已有；缺通知栏速回、发图、折叠消息 |
| 关注主播开播提醒 | 接口免登录、一屏一条 | ❌ 未做，**高价值低成本** |
| 收藏/投币/一键三连快捷操作 | 播放中一个手势完成 | ✅ 已有（注意 §5.1 的 csrf 问题） |
| 榜单（热门/排行/每周必看） | 有限枚举、无输入需求 | ✅ 已有 |
| 追番更新提醒 | "今天更了什么"一屏可读完 | ✅ 时间表已有，缺提醒 |

**明确不值得做**：创作中心、会员购、漫画、课堂、发布动态/评论/弹幕（需输入）、礼物面板/舰长榜（涉消费决策）、收藏夹批量整理、关注/粉丝列表管理、登录密码/短信、风纪委员与入站考试。

**一个额外结论**：从接口可行性看，**腕上场景的终点不是"把 B 站功能搬过来"，而是"把 B 站内容变成一条可离线消费的队列"**——稍后再看 + 历史续播 + 音频轨 + AI 字幕，这四样凑齐就是手表上最完整的产品形态，其余都是锦上添花。

## 5. UGC 合集（`ugc_season`）与收藏夹的归属探测（26.10.15 实测）

**问题**：在收藏夹里怎么知道"这条视频属于某个 UP 的合集"？结论是**列表接口不给，只能逐条反查**。

- `x/v3/fav/resource/list` 的单条 media 字段（实测全集）：`attr / bv_id / bvid / cnt_info / cover / ctime / duration / fav_time / id / intro / link / media_list_link / ogv / page / pubtime / season / title / type / ugc / upper`。
  - **没有 `ugc_season`**；`season` 字段存在但**恒为 null**（那是 OGV/番剧用的，不是 UGC 合集）。
  - 顺带两个**目前没解析、但有用**的字段：`ugc.first_cid`（该视频首个 cid）、`page`（分P 数）。解析 `ugc.first_cid` 可以让"收藏夹虚拟合集"切集时省掉一次 `view?aid=` 反查。
- 只能对每条调 `x/web-interface/view?aid=` 看 `data.ugc_season` 是否存在。**命中率不低**：默认收藏夹第 1 页 20 条里 **6 条**在合集里（30%）。
- `view?aid=` 的 `ugc_season` **带完整剧集**（实测 `ep_count=336`，`sections[].episodes[]` 也正好 336 条，分 9 节），且每条 episode 带 `aid`/`cid`/`title` —— 所以**一次请求就能拿到整个合集的官方顺序与真 cid**，足以直接构造播放列表。
- **但响应体很大**：上述 336 集那个 `view` 响应 **494 KB**。→ 收藏夹列表层逐条预取合集归属不可行（20 条最坏 ~10MB，手表弱网不能接受）。要探测就**按需探测**（用户主动要，或只对当前可见的几条懒加载）。
- 本项目已有的解析可直接复用：`api/VideoInfoApi.java` 的 `analyzeUgcSeason` → `model/Collection`（`sections[].episodes[]`，`Collection.Episode` 有 `aid`/`cid`/`title`）。合集详情页入口见 `docs/architecture-map.md` §7.38。

## 6. 收藏夹：写接口代际、上限字段与默认夹识别（26.10.16 实测 / 26.10.23 迁移）

**代际结论（26.10.23）**：收藏夹读写已整体迁到 `x/v3/fav/*`，老接口全部不再调用。

- 读收藏夹列表：`GET x/v3/fav/folder/created/list?up_mid=&pn=&ps=100`（自己/他人同一接口，按 `has_more` 分页）。每项 `id` 就是 media_id、`fid` 只是展示用的旧 id、`attr` bit0=1 私密 / bit1=0 默认、`cover` 直接给。
- 读收藏夹内容：`GET x/v3/fav/resource/list?media_id=&platform=web&pn=&ps=20&order=`（`order` 取 `mtime`/`view`/`pubtime`）。
- 写收藏/取消/多选删除：统一 `POST x/v3/fav/resource/batch-deal`，form `resources`（`{内容id}:{内容类型}` 逗号串）+ `add_media_ids` + `del_media_ids` + `csrf`（可同时加/删）。
- **`resources` 的类型位不只是 `2`**（26.10.29 核实 `bilibili-API/docs/fav/action.md`）：`2`=视频稿件（id 用 avid）、`12`=音频（auid）、`21`=视频合集（合集 id）。`x/v3/fav/resource/list` 每条 media 的 `type` 字段就是它，所以删除音频/合集收藏必须按真实类型拼，不能一律写 `:2`。本项目 `FavoriteApi.normalizeFavType()` 只放行 2/12/21，其余回落 2；`VideoCard.favType` 承载该值（`buildResources` 按卡片类型拼串）。
- 清空夹内失效：`POST x/v3/fav/resource/clean`，form 只有 `media_id` + `csrf`，成功 `{code:0,message:"0",ttl:1,data:0}`——**`data` 是 0 而不是清理条数**，服务端不告诉清了几条；本项目的接口快照里**没有** `folder/clean-state` 章节，所以不做「可清理数量」预检（26.10.29 已接入，见 `FavoriteApi.cleanInvalidResources`）。tab 级全量清理是 `x/v3/fav/tab/clean_invalid`，仍未接。
- 已弃用（本项目不再调用）：`space.bilibili.com/ajax/fav/getBoxList`、`x/space/fav/arc`、`medialist/gateway/coll/resource/deal`、`medialist/gateway/coll/resource/batch/del`、`x/v3/fav/resource/batch-del`、`x/v3/fav/resource/deal`。**注意**：官方文档里专门有一条「批量删除内容」`x/v3/fav/resource/batch-del`（form `resources`+`media_id`+`platform=web`+`csrf`），能力与 `batch-deal` 的删除半边重复，本项目继续走 `batch-deal`，不引入它。
- 图文收藏：`x/polymer/web-dynamic/v1/opus/feed/fav`（`page_size=20&page=`，`items[].cover` 是对象 `{url}`），旧 `x/polymer/web-dynamic/v1/opus/favlist` 仅兜底。

**上限字段的坑（26.10.16 历史，迁移后已不存在）**：老 `getBoxList` 对自建收藏夹恒返回 `max_count = 999`（实际上限 1000），「我的收藏夹」页因此显示过 `1000/999`；v3 系（`created/list`、`list-all`）**完全不返回上限**。现在上限按服务端约定本地算：默认收藏夹 50000、自建 1000（`FavoriteApi.maxCountOf()`）；默认收藏夹用 `attr & 2 == 0` 判断（不再用 `index == 0`，`index` 判据只对已删除的 `getBoxList` 成立）。详见 `docs/architecture-map.md` §7.34。

**已知但未接的收藏接口**（逐条能力表见 `docs/FEATURES.md` §8.5）：`x/v3/fav/resource/sort`（夹内手动排序）、`x/v3/fav/folder/sort`（收藏夹本身排序）、`x/v3/fav/tab/clean_invalid`（清理所有夹的失效）、`x/v3/fav/thump/up`、`x/v3/fav/tab/report`、`resource/infos|ids`、`folder/space|space/v2|whitelist`、`x/v3/fav/tab` 族、`x/v3/fav/season/unfav`。前两个需 APP 签名（`appkey` + `ts` + `sign = md5(query + appsec)`）。

**批量写接口要带 `x-bili-gaia-vtoken`**（9.16.1 客户端静态分析）：`POST x/v3/fav/resource/batch-deal` 的请求头里有一个人机校验 token（`BiliApiException.mCode == 11211` 时官方客户端会单独处理、不弹通用错误）。这个 token 由风控链路下发，第三方拿不到——所以本项目走 `batch-deal` 时**偶发失败要优先怀疑它**，退路是逐条打 `x/v3/fav/resource/deal`（官方也保留着这条）。单条 `deal` 不需要该头。

## 7. 评论区翻译：gRPC `TranslateReply`（26.10.26 实测）

**结论**：这是目前唯一能“按需翻译单条评论”的接口，游客可用、不需要签名/登录；本项目实现见 `api/ReplyTranslateApi.java`，UI 见 `adapter/ReplyAdapter.kt`（每条评论操作行的「文A」按钮）。

- **调用**：`POST https://grpc.biliapi.net/bilibili.main.community.reply.v1.Reply/TranslateReply`，`content-type: application/grpc`、`te: trailers`、`grpc-accept-encoding: identity`。
- **请求 protobuf**：`TranslateReplyReq { int64 type=1; int64 oid=2; repeated int64 rpids=3 (packed) }`。`type` 用 `ContentType` 的 typeCode（视频=1、动态=17、专栏=12、音频=14、课程=33…），`oid` 是对应的 aid/动态 id。一次 1~20 条。
- **响应 protobuf**：`TranslateReplyResp { map<int64, ReplyInfo> translated_replies=1 }`；`ReplyInfo` 里取 `translated_content=17`（`Content`）的 `message=1`。**成功判据只看 `translated_content` 是否有非空文本**。
- **目标语言**由请求头 `x-bili-locale-bin` 决定（base64 的 `Locale` protobuf）：`Locale { LocaleIds c_locale=1 { language=1; script=2; region=3 }; s_locale=2; ... }`。最小可用只写 `c_locale.language`，实测 en=`CgQKAmVu`、ja=`CgQKAmph`、zh=`CgQKAnpo`。**不发该头必然没有译文**。
- **gRPC 帧**：1 字节 `0x00` + 4 字节大端长度 + protobuf body。
- **错误形态**：HTTP 仍是 200。成功时 `grpc-status: 0`/`bili-status-code: 0` 在 trailers；`trailers-only` 业务错误时这两个头直接出现在响应头里（如 `bili-status-code=12002`「当前页面评论功能已关闭」）。OkHttp 4.12 的 `Response.trailers()` 可用，但保险做法是**先查响应头、body 读尽后再 best-effort 查 trailers**。
- **空 map 不是错误**：评论已经是目标语言、或服务端判定不可译时都会返回空 map；`reply_control.translation_switch`（`ReplyInfo` field 37）只有 0/1/2/3 四态，且翻译成功时恒为 3（SHOW_ORIGIN），没有区分度。
- **无法预判能否翻译**：REST `/x/v2/reply` 返回的 `translation_switch` 恒为 1（UNSUPPORTED 语义之外的占位），只有带 locale 头的 gRPC `MainList` 才会直接带译文。本项目列表走 REST，所以只能**点按钮时再调** `TranslateReply`。
- **本仓库的取舍**：按条懒翻译（不预取整页、不批量）、目标语言**固定为简体中文 `zh`**（`ReplyTranslateApi.TARGET_LOCALE`；App 只有中文界面，不跟随系统语言）、不重试、失败只提示一次「翻译失败，请稍后再试」。
- **实测边界**：`zh` 目标头只验证过「请求被接受、中文源评论返回空译文」（源=目标），**外语评论翻成中文尚未实测**（当时没有可用的外语评论样本）；en/ja 目标头有真实译文。
- **当前 UI 状态**：入口暂时隐藏（`ReplyTranslateApi.UI_ENABLED = false`，26.10.26），接口实现与单测保留；恢复显示只需把开关改回 `true`。
- **已知限制**：`grpc.biliapi.net` 只支持 HTTP/2；minSdk 19/20 的旧设备没有 ALPN，OkHttp 协商不出 h2 会直接抛 `IOException`（不会自动降级），UI 侧按普通网络失败提示。

## 8. 举报：接口可达性与取舍（26.10.27 实测 + 反编译核证）

**结论**：只有**评论举报**落到了本项目（`api/ReportApi.java` + `adapter/ReplyAdapter.kt` 长按操作面板），视频举报与弹幕举报都**没做**，原因如下。

| 用途 | 接口 | 匿名实测 | 结论 |
| --- | --- | --- | --- |
| 评论举报（提交） | `POST api.bilibili.com/x/v2/reply/report` | `-352`（风控，只差登录） | ✅ 已实现 |
| 评论举报（理由表） | `GET api.bilibili.com/x/v2/reply/report/metadata` | `-101`（需登录） | ✅ 已实现（带静态兜底） |
| 视频举报（提交） | `POST app.bilibili.com/x/v2/view/report/add` | `-101`（账号未登录） | ❌ 缺理由表 |
| 视频举报（理由列表） | `GET app.bilibili.com/x/v2/view/report` | `-400` / `-3`（签名错误） | ❌ 不可达 |
| 弹幕举报（提交） | `POST api.bilibili.com/x/dm/report/add` | `-101` | ❌ 未做 |

- **参数**（评论举报）：`access_key`（或 Cookie 鉴权）/ `oid` / `type` / `rpid` / `reason` / `content`，写操作另需 `csrf`。本项目沿用全仓惯例——**Cookie 鉴权 + `csrf=NetWorkUtil.currentCsrf()`**，不显式传 `access_key`。
- **`type` 用的是评论区类型码，不是稿件类型**：视频=1、动态=17、专栏=12 等，与 `/x/v2/reply/action` 同一套。传错会被服务端拒（动态/专栏的评论传 1 会被拒），所以 UI 里必须传 `replyType` 而不是视频的 `type`。
- **理由码：官方现行是 18 个非连续码，不是 0~17**（26.10.27 完成反编译 + H5 核证后定论）：
  - 官方客户端**没有原生评论举报界面**：它把举报交给 H5 页面（`ConfigManager.get("comment.report_url", "https://www.bilibili.com/h5/comment/report")`，见 `RouterReducer$handleToUserReport$1`、`comment2/comments/viewmodel/d`）。所以 **33 个 dex 里既没有理由码表、也没有任何 `comment_global_string_*` 的资源 id 引用**（全量扫过：资源名字面量只出现在一张 488 条的历史名单数组里，id 字节引用为 0）——第一版据此得出的「无法核实」是**反编译不完整造成的误判**。
  - H5 页面（`comment-h5` bundle，chunk `919`）先 `GET /x/v2/reply/report/metadata` 取**服务端理由表**，失败才用静态兜底表。静态兜底表的原文（分组顺序即页面顺序）：

    | 分组 | 码与文案 |
    | --- | --- |
    | 违反法律法规 | 9 违法违规、2 色情、10 低俗、12 赌博诈骗、23 违法信息外链 |
    | 谣言类不实信息 | 19 涉政谣言、22 虚假不实信息、20 涉社会事件谣言 |
    | 侵犯个人权益 | 7 人身攻击、15 侵犯隐私 |
    | 有害社区环境 | 1 垃圾广告、4 引战、5 剧透、3 刷屏、8 视频不相关、18 违规抽奖、17 青少年不良信息、0 其他 |

  - **旧快照那张 0~17 表是错的**：官方**没有** 6（政治）、11、13、14、16、21；8 的文案是「视频不相关」而不是「内容不相关」；「传播不实信息」实际是 22「虚假不实信息」。第一版按快照发过 `reason=6`，属于会撞空的码。
  - 本项目实现：`ReportApi.cachedOrDefaultReasons()`（服务端缓存优先、否则用上面这份静态兜底表），`fetchReasons(oid, type)` 后台预热缓存；`content_required` 逐项来自服务端，不再假设「只有其他要正文」。
- **正文长度是 2~200 字符**，不是 3：H5 校验为 `if (otherContent && otherContent.length < 2)`，文案 `reportOtherLengthTip`「自定义举报内容长度为2-200字符」。APK 里那条「举报理由不能少于3个字」（`comment_global_string_171`）属于**更老的客户端**，与现行 H5 不符。
- **服务端下发格式**（`/x/v2/reply/report/metadata`，需登录，匿名实测 `-101 账号未登录`）两种都出现过：
  - 新：`data.reason_list[{ tag_id, tag_name, content_required }]`
  - 旧：`data.report_groups[{ name, report_options[{ value, label }] }]`
  - 另有 `data.can_delete` 决定是否显示「删除评论/拉黑」选项（本项目不做这两项）。
- **H5 实际提交的字段**（照抄备查）：`oid / type / rpid / reason / platform / build / scene / add_blacklist / ordering / buvid / scm_action_id / spmid / from_spmid / gaia_source=main_h5`，`reason` 为需正文项时附 `content`。本项目只发 `type/oid/rpid/reason/(content)/csrf`——实测服务端对这套最小字段能正常进业务（回 `-352` 说明不是参数错误）。
- **错误码**（快照 json 回复段 + 客户端文案）：`-101` 未登录 / `-102` 账号封停 / `-111` csrf 失效 / `-400` 请求错误 / `-403` 权限不足 / `-404` 无此项 / `-500` 服务器错误 / `-509` 请求过于频繁 / `12002` 评论区已关闭 / `12006` 没有该评论 / `12008` **已经举报过了** / `12009` 评论主体 type 不合法 / `12019` 举报过于频繁 / `12077` **其他理由过长或过短**；`12026` 来自客户端原文「CD时间未到不能举报」。全部已映射成具体中文提示（`ReportApi.errorMsg`），**但除 `-352` 外没有一条在真机上撞过**，属「文档 + 客户端文案」推导。
- **`content` 只在「需正文」的那一档才带**：静态兜底表里只有 `reason=0`（其他）；服务端标了 `content_required` 的项同样要带。长度 2~200 字符（见上面「正文长度」），不合格只提示不提交。
- **`12026` 对应客户端原文「CD时间未到不能举报」**，是同一评论/同一用户的冷却拒绝，提示为「刚举报过，冷却中，请稍后再试」（与 12019「举报太频繁了」分开措辞，方便看日志区分）。
- **视频举报为什么不做**：**提交**接口其实可用（匿名只回 -101），但**拉理由列表**的 `GET app.bilibili.com/x/v2/view/report` 走 `DefaultRequestInterceptor`，要求 `appkey` + `ts` + `sign`，而 `appsec` 在官方包的 native 里，第三方算不出来（实测：不带 appkey → `-400 请求错误`；带假签名 → `-3 签名错误`）。理由的 `type` / `reason` 都是**服务端下发的字符串**（`BiliVideoAppeal` 只有 `type` / `reason` / `desc` / `allow_add` 四个字段），APK 里没有内置表（`dd.json` 里那份是**直播**的 `live.report_reasons`），本地硬编码必然与线上分类错位，所以宁可不做。
- **弹幕举报**入口在播放器内，改动面比评论大，本轮不做；接口本身可用（匿名 `-101`），参数为 `access_key` / `cid` / `dmid` / `reason` / `block` / `spmid` / `from_spmid` / `action_id`。
