package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.util.ImagePickerAlbums.Album
import com.RobinNotBad.BiliClient.util.ImagePickerAlbums.Photo
import org.junit.Assert.assertEquals
import org.junit.Test

/** 内置选图页的相册分组 / 过滤 / 权限名（反馈 #53）。 */
class ImagePickerAlbumsTest {

    private val other = "其他"

    // 已按时间倒序：最新的是截图
    private val photos = listOf(
        Photo(5, "Screenshots"),
        Photo(4, "Camera"),
        Photo(3, "Screenshots"),
        Photo(2, ""),
        Photo(1, "Camera"),
    )

    @Test
    fun group_第一项是全部_其余按最近使用排() {
        assertEquals(
            listOf(Album(null, 5), Album("Screenshots", 2), Album("Camera", 2), Album(other, 1)),
            ImagePickerAlbums.group(photos, other)
        )
    }

    @Test
    fun group_没有图_只剩全部() {
        assertEquals(listOf(Album(null, 0)), ImagePickerAlbums.group(emptyList(), other))
    }

    @Test
    fun filter_全部原样返回_相册名为空的归进其他() {
        assertEquals(photos, ImagePickerAlbums.filter(photos, null, other))
        assertEquals(listOf(5L, 3L), ImagePickerAlbums.filter(photos, "Screenshots", other).map { it.id })
        assertEquals(listOf(2L), ImagePickerAlbums.filter(photos, other, other).map { it.id })
    }

    @Test
    fun readPermission_按系统版本() {
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", ImagePickerAlbums.readPermission(28))
        assertEquals("android.permission.READ_EXTERNAL_STORAGE", ImagePickerAlbums.readPermission(32))
        assertEquals("android.permission.READ_MEDIA_IMAGES", ImagePickerAlbums.readPermission(33))
        assertEquals("android.permission.READ_MEDIA_IMAGES", ImagePickerAlbums.readPermission(34))
    }
}
