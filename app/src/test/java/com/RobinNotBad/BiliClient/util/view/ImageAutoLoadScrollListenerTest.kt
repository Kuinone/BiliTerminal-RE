package com.RobinNotBad.BiliClient.util.view

import androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING
import androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
import androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_SETTLING
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「翻动时不加载图片」的暂停判据（26.10.27）：只有甩动的惯性阶段暂停，表冠滚动不暂停。
 */
class ImageAutoLoadScrollListenerTest {

    @Test
    fun flingAfterDrag_pauses() {
        assertTrue(ImageAutoLoadScrollListener.shouldPause(SCROLL_STATE_DRAGGING, SCROLL_STATE_SETTLING))
    }

    @Test
    fun rotaryCrownSmoothScroll_doesNotPause() {
        // 表冠走 smoothScrollBy：IDLE 直接进 SETTLING
        assertFalse(ImageAutoLoadScrollListener.shouldPause(SCROLL_STATE_IDLE, SCROLL_STATE_SETTLING))
        // 连续转动时 SETTLING 之间不会经过 DRAGGING
        assertFalse(ImageAutoLoadScrollListener.shouldPause(SCROLL_STATE_SETTLING, SCROLL_STATE_SETTLING))
    }

    @Test
    fun draggingAndIdle_doNotPause() {
        assertFalse(ImageAutoLoadScrollListener.shouldPause(SCROLL_STATE_IDLE, SCROLL_STATE_DRAGGING))
        assertFalse(ImageAutoLoadScrollListener.shouldPause(SCROLL_STATE_SETTLING, SCROLL_STATE_IDLE))
        assertFalse(ImageAutoLoadScrollListener.shouldPause(SCROLL_STATE_DRAGGING, SCROLL_STATE_IDLE))
    }
}
