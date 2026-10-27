# 归档文档索引

> 这里放的**全部是历史资料**：已经被后续提交推翻、已经落地完毕、或者只承载「当时的过程」的文档。
>
> **看架构、看现状、看待办，不要从这里开始** —— 请读：
> - `AGENTS.md`（每次都要遵守的约定 + 改前必查的坑）
> - `docs/architecture-map.md`（架构地图 + §7 已知坑清单）
> - `docs/FEATURES.md`（用户可见功能总表）
> - `docs/review/fix-progress.md`（当前状态 + 未完成待办）
> - `docs/README.md`（文档总索引）
>
> 归档只做「搬家」，正文一字未改（少数文件在开头加了一行存档说明）。文中写的事实以**当时**为准，**不要当作现状引用**；引用的路径若指向本目录，说明它本身就是历史。

## 归档时间：2026-10-27（全量文档审查）

每份文件开头都加了一行「【已归档 2026-10-27】」说明，正文未改。仍开放的待办已回迁 `docs/review/fix-progress.md` §2.2。

| 文件 | 内容 | 为什么归档 |
| --- | --- | --- |
| [superpowers/specs/2026-09-10-player-core-merge.md](superpowers/specs/2026-09-10-player-core-merge.md) | 播放内核合并方案 S1–S6 | 状态「进行中」与现状不符：S1–S3 已完成，S6 的大半已删；剩余 S4 / S5 后半 / `PlayerControlDelegate` 去留已回迁 |
| [superpowers/specs/2026-10-11-player-network-resilience.md](superpowers/specs/2026-10-11-player-network-resilience.md) | 播放器弱网 / 直播 / 长视频调研（ijkplayer 选项语义、FLV vs HLS、§2.4 `.so` 选项实测） | 文首「不改动任何代码」已不成立，P0-1/P0-3/P1-1/P1-2/P2-1 已实施，行号全部漂移；未做项已回迁。选项语义与 `.so` 实测仍可在这里查 |
| [superpowers/specs/2026-10-13-download-bug-diagnosis.md](superpowers/specs/2026-10-13-download-bug-diagnosis.md) | 下载链路缺陷 C3 / B1 / A1–A6 / B2 / B3 排查 | 速览表多条已修（C3、B1 播放侧、A1/A2、A5），且 26.10.25 起 DASH 合并单文件改变了前提；剩余项已回迁 |
| [superpowers/specs/2026-10-13-download-engine-optimization.md](superpowers/specs/2026-10-13-download-engine-optimization.md) | 下载引擎对标 PiliPlus / bilimiao2 的改造方案 | 「不做合并」结论已被 `docs/superpowers/specs/2026-10-25-dash-merge-single-file.md` 反转；分片看门狗已落地；续传 / 仅 WiFi / 磁盘预检 / 弹幕分段已回迁 |
| [superpowers/specs/2026-10-20-player-touch-lock.md](superpowers/specs/2026-10-20-player-touch-lock.md) | 播放页触摸锁定需求与验收 | 状态写「待签字后实施」，实际已实现（`PlayerLockPolicy.kt` + 单测）；用户口径在 `docs/FEATURES.md` |
| [review/device-test-checklist-261019.md](review/device-test-checklist-261019.md) | 26.10.16 → `9f644c2` 那一版的真机节点清单 | 一次性清单，该版已发布 |
| [review/device-test-plan.md](review/device-test-plan.md) | T0–T15 + D0–D8 真机测试计划 | 基线 26.09.24，97 条（文中写 88）一条未执行、前提多已过时；发版回归统一走 `docs/review/real-device-regression-checklist.md` |
| [review/fix-progress-snapshots-261009-261021.md](review/fix-progress-snapshots-261009-261021.md) | `fix-progress.md` §3.0~§3.51 状态快照（26.10.09~26.10.21） | 活台账超过 1700 行；编号不变，活台账里引用的 §3.0~§3.51 都在这里 |

**未归档但需知道**：`docs/superpowers/specs/2026-10-14-console-v2-design.md` 被 `.gitignore` 排除（随 `server/` 一起移出版本库），不在本仓库的归档范围内。

## 归档时间：2026-10-10（本轮整理）

### review/ —— 审计与过程流水

| 文件 | 内容 | 为什么归档 |
| --- | --- | --- |
| [audit-2026-09-24.md](review/audit-2026-09-24.md) | 本机深度审查（P/S/E 系列）+ 2026-10-02 复查，「六、修复轮次后的状态」与两条结论更正 | 一次性审查，修复轮次已全部落地；仍未修项（M6/M9）与两条结论更正（S3 前提、`IjkPlayerBridge.release()`）已回迁 |
| [upstream-fork-audit.md](review/upstream-fork-audit.md) | 对上游分叉 `Re-BiliTerminal` 的逐条比对快照，§四 处置表 | §四 处置表已被后续提交推翻（F3-e/f/j/k 大半实现）；真缺的 F3-a/(f-a)/k/d 已抽出 |
| [fork-fix-worklog.md](review/fork-fix-worklog.md) | Wave 1~4 修复过程流水（提交号、编译/单测数字） | 纯过程记录；唯一未勾项实测已完成，真机复验清单由 `device-test-plan.md` 承接 |
| [dialog-redesign-progress.md](review/dialog-redesign-progress.md) | 弹窗改版（终端列表方案 B）专项中间进度，26.10.05 | 已收官：技术结论并入 `architecture-map.md` §8.7.0 与 `AGENTS.md` 弹窗硬约定；未闭合项（真机目视、对比度重测）已抽出 |
| [fix-progress-history.md](review/fix-progress-history.md) | `fix-progress.md` 的完整历史正文（§一 ~ §四十三 的逐批次记录，4777 行）（含 §3.1 自建反馈/统计的「§四十一」全文；耐久结论已回迁 `architecture-map.md` §6.5 / §8.7.0 与 `server/README.md`） | 逐批次流水，最新状态已由 `docs/review/fix-progress.md` 承接 |

### 调研报告

| 文件 | 内容 | 为什么归档 |
| --- | --- | --- |
| [watch-optimization-research.md](watch-optimization-research.md) | 手表端优化调研：竞品对比 / 性能体检 / 改造清单 / §10 接口与风控 / §12 决策台账（26.10.04） | 改造项已逐条拍板并落地；长期有效的接口与风控硬约束、仍未落地项已回迁 |
| [visual-experience-report.md](visual-experience-report.md) | 视觉体验报告：批次 0~4、附录 E 不要回退清单、附录 H 真机验证清单 | 批次已实施完毕；不要回退项已回迁，真机清单由 `review/real-device-regression-checklist.md` 承接 |
| [dash-download-research.md](dash-download-research.md) | DASH 视频下载调研（`fnval` 位表、WBI 签名、防盗链、URL 有效期、`&` 转义） | **已实质过期**：文中 `util/MediaMerger.kt` 合并层已被删除，音频选流策略亦已反转（见 `DashData.java`） |
| [bluetooth-av-sync-report.md](bluetooth-av-sync-report.md) | 蓝牙音画同步补偿可行性报告（方案 A–D 对比与推荐） | 未排期、零实现（全工程无 `audioDelay`/`syncOffset`，清单无蓝牙权限）；保留为将来动手时的参考 |

### design/ —— 一次性设计稿与事故现场

| 文件 | 内容 | 为什么归档 |
| --- | --- | --- |
| [design/dialog-redesign-v1.html](design/dialog-redesign-v1.html) | 弹窗重设计稿 v1（候选方案对比） | 候选方案一条未采纳，最终路线见 v2 根因 + v3 还原稿 |
| [design/dialog-redesign-v2.html](design/dialog-redesign-v2.html) | 弹窗重设计 v2（根因：7 族主题缺 `alertDialogTheme`/`colorOnSurface`/`colorError`） | 根因已双份沉淀进 `architecture-map.md` 与 `AGENTS.md`，实现已完成 |
| [design/dialog-implemented-v3.html](design/dialog-implemented-v3.html) | 弹窗重设计 v3（实现还原稿 + 逐项差异表） | 实现本体是 `util/TerminalDialog.kt`，差异表与圆角决策已进进度文档 |
| [design/shell-failure-2026-02.md](design/shell-failure-2026-02.md) | DSH 子进程启动失败现场（`0xC0000142 STATUS_DLL_INIT_FAILED`） | 一次性主机环境事故，已自愈 |

### superpowers/ —— 已执行完毕的计划与汇总

| 文件 | 内容 | 为什么归档 |
| --- | --- | --- |
| [superpowers/plans/2026-08-16-fav-choose-count.md](superpowers/plans/2026-08-16-fav-choose-count.md) | 选择收藏夹页显示「数量/上限」计划 | 已 100% 落地（`FavoriteApi.parseFavoriteState`、`cell_folder_choose.xml`、3 条单测）；上限规则（50000/1000/`index == 0`）已回迁 |
| [superpowers/specs/2026-09-08-implemented-features-summary.md](superpowers/specs/2026-09-08-implemented-features-summary.md) | 四份已实现功能汇总（投票 / 收藏夹上限 / 菜单设置 / 热搜与隐私模式 + `MySpaceConfig` 配置化） | 全部已落地；文中「没有 MVVM 新层」一句当时即已过时 |

## 未归档（仍在 `docs/`，因为还承载未完成工作；26.10.27 复核）

- `docs/review/real-device-regression-checklist.md` —— 发版前真机回归清单（249 条），仍在用。
- `docs/review/fix-progress.md` —— 唯一活台账（当前状态 + 未完成待办 + 26.10.22 起的快照）；完整历史正文与 §3.0~§3.51 快照分别归档为本目录的 `review/fix-progress-history.md`、`review/fix-progress-snapshots-261009-261021.md`。
- `docs/superpowers/plans/2026-08-27-new-features-roadmap.md` —— 直播签到、大会员签到、勋章佩戴、直播发弹幕、发起充电、青少年模式等**仍未实现**（图文动态与笔记查看已做，漫画已裁为不做，均已在文中标注），且含实测过的接口地址与「无 Cookie 返 `-101` 即接口有效」判据。
- `docs/tutorial-system-redesign.md` —— 教程系统重做进行中（旧链路未删完）。
