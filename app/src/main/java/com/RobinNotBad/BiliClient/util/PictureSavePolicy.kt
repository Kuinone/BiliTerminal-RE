package com.RobinNotBad.BiliClient.util

/**
 * 图片保存目录策略（安全审计 P2-2）。
 *
 * 背景：默认图片下载目录原来是公共 `Pictures/哔哩终端/`，而应用 targetSdk 34 在
 * Android 10+ 上受分区存储约束，用裸 `File` 写公共目录必须申请
 * `MANAGE_EXTERNAL_STORAGE`（所有文件访问）——为了存一张图要用户交出全盘权限，
 * 代价明显不成比例。视频/缓存默认目录本来就走 `Android/media/<包名>`，
 * 不需要这枚权限，只有图片这条默认链路成了「全盘权限」的唯一理由。
 *
 * 现在分成三种情况：
 * - 用户自己配置了路径：原样尊重（如果是公共目录，仍需要 MANAGE，设置页有授权入口）；
 * - 没配置 + Android 10（API 29）及以上：默认落应用专属目录，免权限；
 *   下载完成后再把成品**发布进 MediaStore**（`Pictures/哔哩终端/`），相册仍可见；
 * - 没配置 + Android 9（API 28）及以下：沿用公共 `Pictures/哔哩终端/`
 *   （旧系统的 WRITE_EXTERNAL_STORAGE 是安装即授予，不出现全盘权限问题）。
 *
 * 本类只放**纯规则**（不碰 `android.*`、不碰 `Environment`），方便 JVM 单测钉住
 * 分档边界与「空配置等于没配置」的语义；真实路径拼装留在 [FileUtil]。
 */
object PictureSavePolicy {

    /** 公共图片目录下的子目录名（相册里显示为 `Pictures/哔哩终端`）。 */
    const val PUBLIC_PICTURE_SUBDIR = "哔哩终端"

    /**
     * 默认目录是否应使用应用专属目录。
     *
     * 边界定在 API 29：Android 10 起即使 targetSdk 低于 29 也只在 10 上兼容
     * `requestLegacyExternalStorage`，而本应用 targetSdk 34，10 上同样受分区存储约束；
     * Android 9 及以下公共目录仍可免 MANAGE 写入。
     */
    @JvmStatic
    fun usesAppPrivateDefault(sdkInt: Int): Boolean = sdkInt >= 29

    /**
     * 规范化用户配置的目录：空串 / 全空白等同于「没配置」，返回 null。
     *
     * 为什么需要：设置页输入框保存空串时，旧代码会拿 `""` 去 `new File("")`，
     * 得到的相对路径再被 `canWriteDirectory` 反复判断失败后才降级，日志里全是噪音；
     * 现在明确把「空」当成未配置，直接走默认目录。
     */
    @JvmStatic
    fun cleanConfiguredPath(raw: String?): String? {
        val trimmed = raw?.trim()
        return if (trimmed.isNullOrEmpty()) null else trimmed
    }
}
