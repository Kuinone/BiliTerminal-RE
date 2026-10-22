package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.model.VideoMeta
import org.json.JSONObject
import java.io.File

/**
 * 视频元数据管理器，负责单个视频的 .video_meta.json 文件读写
 */
object VideoMetaManager {

    private const val META_FILE = ".video_meta.json"

    /**
     * 获取视频元数据文件
     */
    private fun getMetaFile(videoFolder: File): File {
        return File(videoFolder, META_FILE)
    }

    /**
     * 读取视频元数据
     */
    @JvmStatic
    fun readMeta(videoFolder: File): VideoMeta {
        val metaFile = getMetaFile(videoFolder)
        if (!metaFile.exists()) {
            // 尝试从 .quality 文件读取补充信息
            val meta = VideoMeta()
            val qualityFile = File(videoFolder, ".quality")
            if (qualityFile.exists()) {
                try {
                    val qnStr = qualityFile.readText().trim()
                    if (qnStr != "audio_only") {
                        meta.qn = qnStr.toIntOrNull() ?: 0
                    }
                } catch (_: Exception) {}
            }
            return meta
        }

        return try {
            val content = metaFile.readText()
            if (content.isBlank()) VideoMeta()
            else VideoMeta.fromJson(JSONObject(content))
        } catch (e: Exception) {
            VideoMeta()
        }
    }

    /**
     * 保存视频元数据
     */
    @JvmStatic
    @Synchronized
    fun saveMeta(videoFolder: File, meta: VideoMeta) {
        try {
            val metaFile = getMetaFile(videoFolder)
            metaFile.writeText(meta.toJson().toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 读取本地视频的续播位置（毫秒，已按 [LocalVideoProgress] 归一化）。0 表示从头播。
     *
     * 本地视频没有服务端历史可读，进度就存在每视频目录的 `.video_meta.json` 里，
     * 所以文件夹重命名/移动时进度会跟着走（见 `FolderManager`）。
     */
    @JvmStatic
    fun readResumeMs(videoFolder: File): Long {
        return try {
            val meta = readMeta(videoFolder)
            LocalVideoProgress.resumeTargetMs(meta.progressMs, meta.durationMs)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 落盘本地视频的播放位置。
     *
     * 与 [saveMeta] 一样是整份重写，但**必须先读旧 meta 再合并**：直接 new 一份会把
     * 画质列表、folderName、aid/cid 全冲掉。进度写 0 表示"从头/已看完"，
     * 同时把 durationMs 归零（没有可续播位置时留一个旧时长只会误导下次的判断）。
     */
    @JvmStatic
    @Synchronized
    fun saveProgress(videoFolder: File, positionMs: Long, durationMs: Long) {
        try {
            val target = LocalVideoProgress.progressToSave(positionMs, durationMs)
            val meta = readMeta(videoFolder)
            // 位置与时长都没变就不重复写盘（周期落盘会一直调到这里）
            if (meta.progressMs == target && (target == 0L || meta.durationMs == durationMs)) return
            meta.progressMs = target
            meta.durationMs = if (target > 0L) durationMs else 0L
            saveMeta(videoFolder, meta)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 更新视频的文件夹归属
     */
    @JvmStatic
    fun updateFolderName(videoDirName: String, folderName: String) {
        try {
            val videoFolder = File(FileUtil.getVideoDownloadPath(), videoDirName)
            if (!videoFolder.exists()) return

            val meta = readMeta(videoFolder)
            meta.folderName = folderName
            meta.title = videoDirName
            saveMeta(videoFolder, meta)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 更新视频的aid和cid（用于API调用）
     */
    @JvmStatic
    fun updateVideoIds(videoDirName: String, aid: Long, cid: Long) {
        try {
            val videoFolder = File(FileUtil.getVideoDownloadPath(), videoDirName)
            if (!videoFolder.exists()) return

            val meta = readMeta(videoFolder)
            meta.aid = aid
            meta.cid = cid
            saveMeta(videoFolder, meta)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 更新视频画质
     */
    @JvmStatic
    fun updateQuality(videoDirName: String, qn: Int) {
        try {
            val videoFolder = File(FileUtil.getVideoDownloadPath(), videoDirName)
            if (!videoFolder.exists()) return

            val meta = readMeta(videoFolder)
            meta.qn = qn
            saveMeta(videoFolder, meta)

            // 同时更新 .quality 文件以保持兼容性
            val qualityFile = File(videoFolder, ".quality")
            qualityFile.writeText(qn.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 获取视频所属文件夹名称
     */
    @JvmStatic
    fun getFolderName(videoDirName: String): String {
        try {
            val videoFolder = File(FileUtil.getVideoDownloadPath(), videoDirName)
            if (!videoFolder.exists()) return ""

            val meta = readMeta(videoFolder)
            return meta.folderName
        } catch (e: Exception) {
            return ""
        }
    }
}