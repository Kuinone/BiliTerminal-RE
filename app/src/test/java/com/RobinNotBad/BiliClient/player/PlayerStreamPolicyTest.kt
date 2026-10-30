package com.RobinNotBad.BiliClient.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放取流判据的单测。
 *
 * 这些判据决定「走 DASH 双播放器」还是「走合一 MP4」：写反了要么让 1080P 静默退化成 720P，
 * 要么让所有播放都变成双路（无音轨时直接无声）。真机验证 1080P 需要登录态，这里先钉死纯逻辑。
 */
class PlayerStreamPolicyTest {

    @Test
    fun `720P及以下不需要DASH`() {
        assertFalse(PlayerStreamPolicy.requiresDash(16))  // 360P
        assertFalse(PlayerStreamPolicy.requiresDash(32))  // 480P
        assertFalse(PlayerStreamPolicy.requiresDash(64))  // 720P
    }

    @Test
    fun `1080P及以上必须走DASH`() {
        assertTrue(PlayerStreamPolicy.requiresDash(80))   // 1080P 高清
        assertTrue(PlayerStreamPolicy.requiresDash(112))  // 1080P+ 高码率
        assertTrue(PlayerStreamPolicy.requiresDash(116))  // 1080P60
        assertTrue(PlayerStreamPolicy.requiresDash(120))  // 4K
    }

    @Test
    fun `DASH缺少音轨不可起播`() {
        // 只有视频没有音轨时硬播 = 无声，必须回退合一 MP4
        assertFalse(PlayerStreamPolicy.isPlayableDash("https://v", null))
        assertFalse(PlayerStreamPolicy.isPlayableDash("https://v", ""))
        assertFalse(PlayerStreamPolicy.isPlayableDash("https://v", "  "))
        assertFalse(PlayerStreamPolicy.isPlayableDash(null, "https://a"))
        assertFalse(PlayerStreamPolicy.isPlayableDash("", "https://a"))
    }

    @Test
    fun `视频与音轨齐全时DASH可起播`() {
        assertTrue(PlayerStreamPolicy.isPlayableDash("https://v", "https://a"))
    }

    @Test
    fun `在线音轨按网络流识别`() {
        assertTrue(PlayerStreamPolicy.isRemoteAudioTrack("https://upos-sz-mirror.bilivideo.com/x.m4s"))
        assertTrue(PlayerStreamPolicy.isRemoteAudioTrack("http://x/y"))
        assertTrue(PlayerStreamPolicy.isRemoteAudioTrack("  HTTPS://x/y  "))
    }

    @Test
    fun `本地音轨不按网络流识别`() {
        assertFalse(PlayerStreamPolicy.isRemoteAudioTrack("/storage/emulated/0/Download/a.m4a"))
        assertFalse(PlayerStreamPolicy.isRemoteAudioTrack("file:///sdcard/a.m4a"))
        assertFalse(PlayerStreamPolicy.isRemoteAudioTrack(null))
        assertFalse(PlayerStreamPolicy.isRemoteAudioTrack(""))
    }
}
