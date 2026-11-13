package io.github.shaonialife.carptocar.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.shaonialife.carptocar.SessionController
import io.github.shaonialife.carptocar.video.VideoCanvasMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出：把运行日志写成 `下载/CarPToCarL/CarPToCarL-log-<时间>.txt`，普通文件管理器可见。
 *
 * 为什么落 MediaStore.Downloads 而不是应用私有目录：
 *  - `filesDir` / `getExternalFilesDir` 普通用户在文件管理器里找不到（Android 11+
 *    系统文件管理器直接屏蔽 `Android/data`），日志导出等于没导；
 *  - MediaStore.Downloads 无需任何存储权限，落盘即出现在系统「下载」目录，
 *    微信/QQ/邮件发送附件也能直接选到。
 *
 * 运行日志、完整可用的控制/event RTSP、当前与近期会话证据写入同一文件。
 * 默认携带容量限制内的跨进程历史，保留重启前失败；长按导出可仅取本次进程。
 */
object LogExporter {
    /** 默认保留重启前的失败证据，各日志存储仍按原容量轮转。 */
    enum class Scope(
        val label: String,
        val fileNameTag: String,
    ) {
        CURRENT_SESSION("仅本次启动", ""),
        ALL("近期保留日志（跨进程）", "-all"),
    }

    data class Result(
        val uri: Uri,
        val fileName: String,
        /** 给用户看的绝对路径（文件管理器里按这个找）。 */
        val displayPath: String,
        val bytes: Long,
        val scope: Scope,
    )

    /** 导出目录（相对外部存储根）：下载/CarPToCarL。 */
    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/CarPToCarL"

    /** 文件管理器里展示的绝对路径前缀（主用户外部存储；分区存储下即 /sdcard）。 */
    private const val DISPLAY_ROOT = "/storage/emulated/0"

    /** 导出并返回结果；失败返回 null（调用方提示用户）。任意线程可调（含 IO）。 */
    fun export(
        context: Context,
        fallbackText: String,
        scope: Scope = Scope.ALL,
    ): Result? {
        // This method is called on Bg; stream bounded retained evidence rather than duplicating
        // tens of megabytes into one String and then another UTF-8 byte array.
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "CarPToCarL-log-$stamp${scope.fileNameTag}.txt"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                // 先标 pending 再写：避免文件管理器/相册扫描到半截文件
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            var byteCount = 0L
            val stream = resolver.openOutputStream(uri) ?: throw IllegalStateException("openOutputStream 返回 null")
            val counted = object : java.io.FilterOutputStream(stream) {
                override fun write(value: Int) {
                    out.write(value)
                    byteCount++
                }

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    out.write(buffer, offset, length)
                    byteCount += length
                }
            }
            counted.bufferedWriter(Charsets.UTF_8).use { writeContent(it, context, fallbackText, scope) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Result(uri, fileName, "$DISPLAY_ROOT/$RELATIVE_DIR/$fileName", byteCount, scope)
        } catch (_: Exception) {
            // 半截文件（IS_PENDING=1）对用户不可见，但会占空间——清掉
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    internal fun writeContent(
        out: Appendable,
        context: Context,
        fallbackText: String,
        scope: Scope,
    ) {
        with(out) {
            appendLine("CarPToCarL 运行日志")
            appendLine("导出时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("应用版本: " + AppVersion.nameWithTag(context))
            appendLine("版本说明: 上述为当前安装版本；历史记录以各进程启动版本为准。")
            val install = runCatching {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                "versionCode=${info.longVersionCode} lastUpdateTime=${info.lastUpdateTime}"
            }.getOrElse { "unavailable(${it.javaClass.simpleName}: ${it.message})" }
            appendLine("安装信息: $install")
            appendLine("诊断能力: deferred-invite; requestUI-resume; independent-screen-audio; transport-health")
            appendLine("导出时会话快照（非失败时快照）: ${SessionController.diagnosticSnapshot()}")
            val network = runCatching {
                val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
                if (manager == null) "service-unavailable" else {
                    val active = manager.activeNetwork
                    "uid=${android.os.Process.myUid()} active=$active bound=${manager.boundNetworkForProcess} " +
                        "activeCaps=${active?.let(manager::getNetworkCapabilities)}"
                }
            }.getOrElse { "unavailable(${it.javaClass.simpleName}: ${it.message})" }
            appendLine("导出时应用可见网络（非全局 VPN 状态）: $network")
            appendLine("导出范围: " + scope.label)
            appendLine(
                "设备: ${Build.MANUFACTURER} ${Build.MODEL} | " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("敏感诊断：包含现有协议原文（设备标识、网络信息等），请仅与信任的人分享。")
            appendLine("RTSP capture enabled=${RtspDump.enabled}; media packets excluded")
            val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            // Allowlist only: never dump pairing identities, keys, SSIDs or arbitrary preferences.
            val configKeys = listOf(
                "logEnabled", "bt_identity_probe", "carlife_hijack_enabled",
                "carlife_fps", "carlife_connect_type", "carlife_ordered_init", "carlife_settle_reinit",
            )
            val configured = settings.all
            appendLine("配置（未设置=应用默认；不读取或修改偏好）:")
            for (key in configKeys) appendLine("  $key=${configured[key] ?: "未设置"}")
            appendLine("当前视频声明: ${VideoCanvasMode.mode} (${VideoCanvasMode.describe()})")
            appendLine("视频停摆策略: 仅观测（上游 1.2.34），不自动干预")
            appendLine("==================================================")
            if (scope == Scope.CURRENT_SESSION) {
                appendLine("（仅本次进程启动之后的日志；需要历史请点按导出）")
            } else {
                appendLine("（近期文件日志；最多 8 段 × 2 MiB = 16 MiB，按时间顺序保留跨进程历史）")
            }
            if (!DebugFileLog.appendAvailable(this, context.filesDir, currentSessionOnly = scope == Scope.CURRENT_SESSION)) {
                // A startup marker alone is not useful evidence; retain the UI fallback.
                appendLine("（文件日志不可用——导出界面缓冲，可能不完整）")
                append(fallbackText)
            }
            appendLine()
            appendLine("============ 完整可用 RTSP（control / event）============")
            appendLine("范围: ${scope.label}；每条含时间、方向、通道、完整头部和 payload hex。")
            RtspDump.appendAvailable(this, context.filesDir, currentProcessOnly = scope == Scope.CURRENT_SESSION)
            appendLine()
            appendLine("============ 当前 / 近期会话与用户观察 ============")
            appendLine("范围: ${scope.label}；建链成功不再丢弃，导出不依赖断链回调。")
            SessionTrace.appendAvailable(this, context, currentProcessOnly = scope == Scope.CURRENT_SESSION)
        }
    }
}
