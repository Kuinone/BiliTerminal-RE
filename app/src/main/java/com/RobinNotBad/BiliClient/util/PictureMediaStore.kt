package com.RobinNotBad.BiliClient.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把已经落盘的图片登记进系统媒体库（MediaStore）。
 *
 * 为什么非做这一步不可（26.10.21 实测复现的「图片下载器下载的图片找不到」）：
 * [com.RobinNotBad.BiliClient.activity.DownloadActivity] 下载图片走的是裸 `FileOutputStream`
 * 写公共 `Pictures/哔哩终端/`，全仓**没有任何 MediaStore 写入**。而 Android 10 起
 * （本应用 targetSdk 34）用文件路径写进公共目录的文件不会被媒体库自动索引，
 * 相册/图库、以及文件管理器的「图片/相册」分类**都只查 MediaStore** ——
 * 于是文件明明躺在磁盘上（用户也看到了「下载完成」），相册和图库里却一条都没有。
 *
 * 复现与反证（手表模拟器，Android 12 / SDK 32）：
 * - 下完一张图后 `ls` 能看到 `Pictures/哔哩终端/noface.jpg`（3719 字节，内容正确）；
 * - `content query --uri content://media/external/images/media` 里**没有**这一行；
 * - 手动补一次 `MEDIA_SCANNER_SCAN_FILE` 广播，2 秒后立刻出现
 *   `_display_name=noface.jpg, relative_path=Pictures/哔哩终端/`，相册随即可见。
 * 所以这里补的就是「写完登记」这一环，不是权限问题、也不是下完被 onDestroy 删掉。
 */
object PictureMediaStore {

    /** 相册里展示的目录（`Pictures/` 下的子目录名，安全审计 P2-2 后固定在这里）。 */
    const val PUBLIC_PICTURE_FOLDER = "Pictures/${PictureSavePolicy.PUBLIC_PICTURE_SUBDIR}"

    /**
     * 纯函数：按文件名后缀判图片 MIME。
     *
     * 认不出来就返回 null —— 调用方据此跳过登记，免得把非图片（比如下载失败留下的
     * 报错页）当图片塞进用户相册。
     */
    @JvmStatic
    fun mimeTypeOf(fileName: String): String? =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            else -> null
        }

    /**
     * 纯函数：把绝对目录裁成给人看的相对路径（toast 里用）。
     *
     * 手表屏幕窄，`/storage/emulated/0/Pictures/哔哩终端` 这种绝对路径 toast 里根本显示不全，
     * 而「`Pictures/哔哩终端`」正好是用户在文件管理器里能按图索骥的层级。
     * 不在外部存储根下（自定义路径跑到别处、或外部存储路径本身为空）就原样返回。
     */
    @JvmStatic
    fun displayFolder(folderPath: String, externalStorageRoot: String): String {
        if (externalStorageRoot.isEmpty()) return folderPath
        val root = if (externalStorageRoot.endsWith("/")) externalStorageRoot else "$externalStorageRoot/"
        return if (folderPath.startsWith(root)) folderPath.substring(root.length) else folderPath
    }

    /**
     * [displayFolder] 的便捷重载：取文件所在目录、用 `Environment` 反推外部存储根。
     */
    @Suppress("DEPRECATION")
    @JvmStatic
    fun displayFolder(file: File): String {
        val root = try {
            Environment.getExternalStorageDirectory()?.absolutePath ?: ""
        } catch (e: Throwable) {
            // 个别设备 ROM 在存储未挂载时取根路径会抛，只是给提示文案用，不能因此崩掉下载流程
            Logu.e("PictureMediaStore", "取外部存储根失败：$e")
            ""
        }
        return displayFolder(file.parent ?: file.absolutePath, root)
    }

    /**
     * 登记一张已经写完的图片。
     *
     * 只做「尽力而为」：文件已经在磁盘上、下载也确实成功了，扫描失败最坏是相册里晚一点
     * （或系统下次空闲扫描时）才出现，**不该反过来把下载判成失败**，所以这里吞掉异常、
     * 只落日志。扫描是异步的，回调里的 uri 为 null 说明媒体库拒收了这个文件。
     */
    @JvmStatic
    fun scan(context: Context, file: File) {
        val mimeType = mimeTypeOf(file.name) ?: return
        try {
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf(mimeType)
            ) { path, uri ->
                if (uri == null) Logu.e("PictureMediaStore", "媒体库登记失败：$path")
                else Logu.d("PictureMediaStore", "媒体库已登记：$path -> $uri")
            }
        } catch (e: Throwable) {
            Logu.e("PictureMediaStore", "媒体库登记异常：${file.absolutePath} $e")
        }
    }

    /**
     * 纯函数：文件是否已经躺在公共 `Pictures/哔哩终端/` 下。
     *
     * 是的话不需要再往 MediaStore 复制一份（那就是两份文件、用户删一份还有一份），
     * 只要 `scan` 登记即可。比较按目录前缀做，不解析 canonical path ——
     * 这里只用于「要不要复制」，判错最坏是多复制一份，不会丢文件。
     */
    @JvmStatic
    fun isUnderPublicPictures(file: File, externalStorageRoot: String): Boolean {
        if (externalStorageRoot.isEmpty()) return false
        val root = if (externalStorageRoot.endsWith("/")) externalStorageRoot else "$externalStorageRoot/"
        return file.absolutePath.startsWith("$root$PUBLIC_PICTURE_FOLDER/")
    }

    /**
     * 把下载完成的图片**发布进系统相册**，返回给 toast 用的展示目录；失败返回 null。
     *
     * 三种路径：
     * - 文件已经在公共 `Pictures/哔哩终端/`：只 [scan] 登记，返回固定展示目录；
     * - Android 9 及以下：同样只 scan（默认目录本来就写的公共 Pictures）；
     * - Android 10 及以上：默认目录是应用专属（免全盘权限），用 MediaStore `insert` +
     *   `IS_PENDING` 复制进 `Pictures/哔哩终端/`，成功后清 pending；失败删掉半成品行。
     *
     * 发布失败**不改变**「下载已成功」的事实：文件仍在应用专属目录里，最坏是相册里看不到，
     * 所以返回值只用于换提示文案，不抛给调用方。
     */
    @Suppress("DEPRECATION")
    @JvmStatic
    fun publishToGallery(context: Context, file: File): String? {
        val mimeType = mimeTypeOf(file.name) ?: run {
            Logu.w("PictureMediaStore", "非图片文件，跳过发布：${file.name}")
            return null
        }
        if (!file.isFile || file.length() <= 0L) {
            Logu.w("PictureMediaStore", "文件不存在或为空，跳过发布：${file.absolutePath}")
            return null
        }
        val externalRoot = try {
            Environment.getExternalStorageDirectory()?.absolutePath ?: ""
        } catch (e: Throwable) {
            Logu.e("PictureMediaStore", "取外部存储根失败：$e")
            ""
        }
        if (isUnderPublicPictures(file, externalRoot)) {
            scan(context, file)
            return PUBLIC_PICTURE_FOLDER
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // 旧系统的默认目录就是公共 Pictures/，能走到这里说明是用户自定义的应用专属路径，
            // 媒体扫描能登记就登记，登记不了也只是相册看不到，不影响下载结果。
            scan(context, file)
            return displayFolder(file)
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, PUBLIC_PICTURE_FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Throwable) {
            Logu.e("PictureMediaStore", "MediaStore insert 失败：${file.absolutePath} $e")
            null
        } ?: run {
            Logu.e("PictureMediaStore", "MediaStore 拒收：${file.absolutePath}")
            return null
        }
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("openOutputStream 返回 null")
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            Logu.d("PictureMediaStore", "已发布进相册：${file.name} -> $uri")
            PUBLIC_PICTURE_FOLDER
        } catch (e: Throwable) {
            Logu.e("PictureMediaStore", "发布进相册失败：${file.absolutePath} $e")
            // 删掉半成品行：留着会在相册里显示一张 0 字节/损坏的图
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Throwable) {
                // 删除失败也只能算了，下次下载同名文件会覆盖
            }
            null
        }
    }
}
