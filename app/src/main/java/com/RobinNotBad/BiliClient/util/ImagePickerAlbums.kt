package com.RobinNotBad.BiliClient.util

/**
 * 内置选图页（`activity/ImagePickerActivity`，反馈 #53）的纯逻辑：相册分组与读图权限名。
 *
 * 不碰 Android 类型，方便 JVM 单测；查询 MediaStore、拼 content Uri 都在页面里做。
 */
object ImagePickerAlbums {

    /** 一张图：MediaStore 的 `_ID` 与所在相册（`BUCKET_DISPLAY_NAME`，可能为空）。 */
    data class Photo(val id: Long, val bucket: String)

    /** 一个相册；[name] 为 null 表示「全部」。 */
    data class Album(val name: String?, val count: Int)

    /**
     * 按相册分组。第一项永远是「全部」；其余按**最新一张图**出现的先后排
     * （[photos] 已按时间倒序，所以最近用过的相册排在前面，手表上少翻几下）。
     * 相册名为空的图归进 [unnamedBucket]，不单独丢掉。
     */
    fun group(photos: List<Photo>, unnamedBucket: String): List<Album> {
        val counts = LinkedHashMap<String, Int>()
        for (p in photos) {
            val key = p.bucket.ifBlank { unnamedBucket }
            counts[key] = (counts[key] ?: 0) + 1
        }
        val albums = ArrayList<Album>(counts.size + 1)
        albums.add(Album(null, photos.size))
        counts.forEach { (name, count) -> albums.add(Album(name, count)) }
        return albums
    }

    /** 选中某个相册后要显示的图；[album] 为 null 即全部。 */
    fun filter(photos: List<Photo>, album: String?, unnamedBucket: String): List<Photo> =
        if (album == null) photos else photos.filter { it.bucket.ifBlank { unnamedBucket } == album }

    /**
     * 读相册要申请的权限：Android 13（API 33）起拆成按媒体类型的 `READ_MEDIA_IMAGES`，
     * 之前是 `READ_EXTERNAL_STORAGE`。Android 6.0 以下安装时即授予，页面不会走到申请。
     */
    fun readPermission(sdkInt: Int): String =
        if (sdkInt >= 33) "android.permission.READ_MEDIA_IMAGES"
        else "android.permission.READ_EXTERNAL_STORAGE"
}
