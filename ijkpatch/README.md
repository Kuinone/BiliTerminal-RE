# ijkplayer 视频倍速补丁

这个目录放「让 ijkplayer 对**无音轨的纯视频流**也真倍速」的内核补丁。补上之后，倍速播放可以
继续用 DASH 的 1080P 纯视频流 + 外部音轨，不必再回落到 720P 的合一 MP4。

**状态：补丁已在 `bilibili/ijkplayer 30eb944`（2021-04-26）+ `Bilibili/FFmpeg
ff4.0--ijk0.8.8--20210426--001` 上编译并实测通过，`app/libs/{arm64-v8a,armeabi-v7a,x86}`
里的 `.so` 已换成打过补丁的版本（26.10.31）。**

**已知代价：`.so` 变大了**（arm64 `libijkffmpeg.so` 5.29MB → 7.22MB、`libijkplayer.so`
0.54→0.57MB、`libijksdl.so` 0.38→0.48MB；v7a/x86 同理各 +1~2MB）。原因是这次用的是
`module-lite-hevc` + 补齐解码器/解复用器的配置，编进去的 ffmpeg 组件比仓库原库多
（`config.h` 里 294 个 filter、20 decoder、19 demuxer；strip 已确认无符号可再剥）。
要压回去可以试 `--disable-filters --disable-avfilter`（本项目的播放链路没用到 avfilter：
`FFP_AVFILTER_PLAYBACK_RATE` 那几个宏在 `ff_ffplay_debug.h` 里是注释掉的），但需要重跑
一次三 ABI 构建 + 回归，未做。

## 为什么纯视频流不变速（源码结论）

`ff_ffplay.c` 里 `ffp_set_playback_rate()`（:4792-4800）只是打一行 `Playback rate: %f`
并写字段 `pf_playback_rate`；全仓消费这个字段的只有音频路径：

- `audio_decode_frame()` :2582-2598 —— `soundtouch` 按倍速拉伸音频；
- `sdl_audio_callback()` :2643-2652 —— 非 Android 平台用 `SDL_AoutSetPlaybackRate()`；
- `configure_video_filters()` :1857-1867 与 :1948-1957 的 `setpts`/`atempo` 被
  `#ifdef FFP_AVFILTER_PLAYBACK_RATE` 包住，而该宏在 `ff_ffplay_debug.h:49` 是注释掉的，
  编译不进去（本仓库提交的 `libijkplayer.so` 里也查不到 `avfilter_graph_alloc`）。

于是有音轨时：soundtouch 把音频压短 → `audclk` 每墙钟秒前进 rate 秒 → 视频是 slave，
被 `compute_target_delay()` 的 `diff = vidclk - master` 拉着追，所以 2x 正常。
没有音轨时：`read_thread` :3276-3281 把 `av_sync_type` 强制成 `AV_SYNC_VIDEO_MASTER`，
`compute_target_delay()` :1255 那段校正**整段跳过**，`video_refresh()` :1350-1361 的
`frame_timer += delay` 只按标称帧时长累加 —— 视频永远 1x，`setSpeed()` 石沉大海。

上游也有同样的问题：issue
[#5187「无音频的视频 无法倍速播放 倍速无效」](https://github.com/bilibili/ijkplayer/issues/5187)
（open），以及未合并的 PR
[#5381「处理无音轨的视频倍速播放」](https://github.com/bilibili/ijkplayer/pull/5381)
（改用 external clock + `set_clock_speed(rate)`）。

## 补丁做了什么

`0001-ffplay-video-playback-rate.patch`：在 `compute_target_delay()` 里，**只在「无音轨
（视频为主时钟）+ 倍速 != 1」时**把帧间隔除以 `pf_playback_rate`：

```c
} else if (ffp && ffp->pf_playback_rate > 0.0f && ffp->pf_playback_rate != 1.0f) {
    delay /= ffp->pf_playback_rate;
}
```

- 只落在无音轨分支：带音轨（音频为主时钟）的路径一行不动，回归面为零；
- PTS / 时钟不动，所以进度条、弹幕、`getCurrentPosition()` 自动正确（每墙钟秒前进
  `rate` 秒内容），seek / 暂停逻辑也不用改；
- `setSpeed()` 中途改速率下一帧即生效，不需要重开流；
- 解码跟不上时表现为「不到 2x」而不是花屏（`frame_timer` 落后超过
  `AV_SYNC_THRESHOLD_MAX` 会被 `video_refresh()` 重置；本项目 `framedrop=4` 会正常丢帧）。

比 PR #5381 的 external-clock 方案稳：不会出现「一批帧猛放 + 停顿」的微突发。

`0002-ffmpeg-hevc-termios-b0.patch`：**arm/arm64 编 ffmpeg 必须打**。NDK 的 `<termios.h>`
把波特率常量定义成 `B0/B50/B75…`，与 HEVC 的空间候选块名 `B0` 撞名
（`TAB_MVF_PU(B0)` 里 `x ## v` 粘成 `x0000000`，`libavcodec/hevc_mvs.c:207` 报
`undeclared`）。x86/x86_64 的 bionic 头没有这组宏，所以只有 arm 编不过。

## 怎么用（本次实际走过的路）

1. 构建环境：WSL Ubuntu 24.04 + **NDK r14b**（不是 r21e）。r14b 才有
   `build/tools/make-standalone-toolchain.sh`，`do-compile-ffmpeg.sh` 依赖它；r21e 会报
   `ERROR: Failed to create toolchain.`。另外 Ubuntu 24.04 只有 `python3`，而 NDK 脚本调
   `python` → 需要 `apt install python-is-python3`。
2. 源码树：`~/ijkplayer-build/ijkplayer`（从 `git clone https://github.com/bilibili/ijkplayer`
   拿 `30eb944`）+ `android/contrib/ffmpeg-{arm64,armv7a,x86,x86_64}`
   （`git clone -b ff4.0--ijk0.8.8--20210426--001 https://github.com/Bilibili/FFmpeg.git`）。
   - **Windows 克隆会毁掉两样东西**：`.sh/.mk` 变 CRLF（`sed -i 's/\r$//'`），以及仓库里的
     符号链接退化成「内容是目标路径的普通文件」（`jni/Android.mk` 等 24 个），后者会让
     `android/compile-ijk.sh` 报 `Android.mk:1: *** missing separator`。
3. 打补丁：

   ```bash
   git -C ~/ijkplayer-build/ijkplayer apply /path/to/0001-ffplay-video-playback-rate.patch
   cd ~/ijkplayer-build/ijkplayer/android/contrib/ffmpeg-<abi>
   git apply /path/to/0002-ffmpeg-hevc-termios-b0.patch
   ```

4. 配置：`config/module.sh` 用 `module-lite-hevc.sh` 的骨架，但要补上仓库原库已有的解码能力
   （`flac`/`vp8`/`vp9` 解码器、`ijk*`/`flac`/`webm_dash_manifest` 解复用器）。ffmpeg 4.0
   已移除的开关要注释掉，否则 configure 直接报 `Unknown option`：
   `--disable-ffserver`、`--disable-vda`；另外链接期 `eac3_core` 会因
   `ff_ac3_parse_header` 未定义失败 → 加 `--disable-bsf=eac3_core`。
   `Application.mk` 的 `NDK_TOOLCHAIN_VERSION` 要跟 ffmpeg 的 gcc standalone toolchain 对齐（`4.9`）。
5. 编译：`compile-openssl.sh <abi>` → `compile-ffmpeg.sh <abi>` → `android/compile-ijk.sh <abi>`。
   **改了 module 开关必须 `cd ffmpeg-<abi> && make distclean && rm -f config.h config.mak
   ffbuild/config.mak ffbuild/config.log` 再编**——`do-compile-ffmpeg.sh` 看到 `config.h`
   就打印 `reuse configure` 跳过 configure，旧 `.a` 里的东西会继续参与链接。
6. 产物：`android/ijkplayer/ijkplayer-<mod>/src/main/libs/<abi>/libijk{ffmpeg,player,sdl}.so`
   （`<mod>` 是 `arm64`/`armv7a`/`x86`/`x86_64`，`<abi>` 是 `arm64-v8a`/`armeabi-v7a`/`x86`/`x86_64`）。
   三个 ABI 的产物覆盖 `app/libs/{arm64-v8a,armeabi-v7a,x86}/`；`x86_64` 只给 x86_64 模拟器
   用，**不提交**。

## 验证清单（换 .so 后必跑）

- 纯视频 DASH（如 1080P）2x / 3x：`Playback rate: 2.000000` 后墙钟推进 ≈ 2x，
  且 `音频轨道重新同步` 计数保持 0；
- 合一 MP4（带音轨）任意倍速：与现在一致（本补丁不碰该路径）；
- seek / 暂停 / 切集 / 自动连播：位置与弹幕对齐；
- 外部音轨 2x 与视频 2x 的漂移：应明显小于「每 ~1.3 秒回拉一次」；
- 低倍速 0.5x / 0.75x：画面应真的变慢，不是卡住。

## 本次实测（26.10.31，emulator-5554 / wear5 / Android 14 x86_64）

- 纯视频流 2x（本地 DASH 夹具 `video.mp4` + 外挂 `audio.m4a`）：`本地进度` 每 15.1s 前进
  30.3s = **2.00x**；`dumpsys media.audio_flinger` 同刻只有 1 条活跃 AudioTrack；
  `音频轨道重新同步` 只在起播对齐 1 次（补丁前是每 ~1.3s 一次）。
- 纯视频 DASH 在线流 2x（HTTP 夹具，视频流走 ijk）：进度上报 15s→30s→45s 各用时 ≈7.5s = **2x**。
- 合一 MP4（带音轨）2x：与补丁前一致（≈2x），音频主时钟路径未被影响。
- 1x：`音频轨道重新同步` 为 0，位置每 15.2s 前进 15.2s = **1.0x**。

已知未验证项：X 模拟器上「在线 DASH 双流 + 外挂音轨」这个组合没跑通（宿主 HTTP 夹具只能
通过 `adb reverse` 给 ijk 用，媒体服务进程连不上；`10.0.2.2` 又被宿主防火墙挡住），
所以在线双流场景是用「本地双文件夹具」等价验证的；真机（arm64）尚未实测。
