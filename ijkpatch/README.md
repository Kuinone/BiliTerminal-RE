# ijkplayer 视频倍速补丁（未编译验证）

这个目录放「让 ijkplayer 对**无音轨的纯视频流**也真倍速」的内核补丁。它对应本项目
`PlayerSpeedStreamPolicy` 的长期替代方案：补上之后，倍速播放可以继续用 DASH 的
1080P 纯视频流 + 外部音轨，不必回落到 720P 的合一 MP4。

**状态：补丁按 bilibili/ijkplayer `30eb944`（2021-04-26）的源码生成，尚未编译、尚未实测。**

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

在 `compute_target_delay()` 里，**只在「无音轨（视频为主时钟）+ 倍速 != 1」时**
把帧间隔除以 `pf_playback_rate`：

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

## 怎么用

1. 准备构建环境（见仓库根 `rebuild_all.sh` / `setup_ndk.sh` / `compile_ijk.sh`）：
   NDK **r21e**、Linux 或 WSL、`init-android-openssl.sh` → `compile-openssl.sh <abi>` →
   `compile-ffmpeg.sh <abi>` → `android/compile-ijk.sh <abi>`。
2. 在 ijkplayer 源码树里打补丁：

   ```bash
   git -C ~/ijkplayer-build/ijkplayer apply /path/to/0001-ffplay-video-playback-rate.patch
   ```

3. 重编后把 `libijkplayer.so`（`ff_ffplay.c` 属于这个模块；`libijkffmpeg.so` /
   `libijksdl.so` 不变）覆盖到 `app/libs/{arm64-v8a,armeabi-v7a,x86}/`。
   - 若只换 `libijkplayer.so`，link 阶段仍要求
     `android/contrib/build/ffmpeg-<abi>/output/{include,lib}` 存在，需预置头文件与
     `libijkffmpeg.so`（版本/ABI 必须与已提交的 .so 一致），否则就完整编一次 ffmpeg。
4. 三个 ABI 都要出（MuMu / x86 模拟器测试要 x86，手表主力是 arm64-v8a）。
5. 应用侧的配套改动（换 `.so` 之后再动，否则行为倒退）：
   - `PlayerActivity.applyAudioSpeed()` 恢复「跟随倍速」而不是恒设 1x；
   - `syncStreamForSpeed()` 不再为倍速换合一 MP4（或保留作老 `.so` 的兜底，
     用版本号 / 能力探测开关区分）。

## 验证清单（换 .so 后必跑）

- 纯视频 DASH（如 1080P）2x / 3x：`Playback rate: 2.000000` 后墙钟推进 ≈ 2x，
  且 `音频轨道重新同步` 计数保持 0；
- 合一 MP4（带音轨）任意倍速：与现在一致（本补丁不碰该路径）；
- seek / 暂停 / 切集 / 自动连播：位置与弹幕对齐；
- 外部音轨 2x 与视频 2x 的漂移：应明显小于现在的「每 ~1.3 秒回拉一次」；
- 低倍速 0.5x / 0.75x：画面应真的变慢，不是卡住。
