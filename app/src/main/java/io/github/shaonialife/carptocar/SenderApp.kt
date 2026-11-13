package io.github.shaonialife.carptocar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.shaonialife.carptocar.carlife.CarGpsOption
import io.github.shaonialife.carptocar.launcher.VirtualLauncher
import io.github.shaonialife.carptocar.util.DebugFileLog
import io.github.shaonialife.carptocar.util.SessionTrace
import io.github.shaonialife.carptocar.video.FirstFramePolicy
import io.github.shaonialife.carptocar.video.VideoCanvasMode

/**
 * 进程级安全网：任一线程的未捕获异常只记录并让该线程结束，
 * 不再走默认处理器杀死整个进程（连接会话横跨多个后台线程，
 * 一个套接字异常不应毁掉全部链路与前台服务）。
 * 各线程自身仍应尽量用 try/catch 精确处理；这里是最后防线。
 */
class SenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 进程标记与实际安装版本一起写入，跨重启导出才能区分旧构建的失败。
        DebugFileLog.markSessionStart(this)
        DebugFileLog.append(
            this,
            "BUILD: ${io.github.shaonialife.carptocar.util.AppVersion.nameWithTag(this)} " +
                "pid=${android.os.Process.myPid()} startupEpochMs=${System.currentTimeMillis()} " +
                "upstream=7c55ac0 diagnostics=session10",
        )
        // VideoConfig 画布声明模式（车机画布 / 码流尺寸）——会话建立时读一次
        VideoCanvasMode.load(this)
        // 首帧策略（缓存关键帧立即起播 / 等引擎当期 IDR）——attach 时读一次
        FirstFramePolicy.load(this)
        // 「使用车机 GPS」开关（默认开）——iAP2 收帧线程每秒会读，启动时载入一次即可
        CarGpsOption.load(this)
        // 会话控制面录制（files/session_trace_<process>_<seq>.log），保留建链后断开的现场。
        // 在业务启动前 attach；生命周期快照后台写入，运行过程另记 main_debug.log。
        SessionTrace.attach(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "未捕获异常（线程 ${thread.name}，已吞掉保进程）", throwable)
            DebugFileLog.append(
                this,
                "UNCAUGHT: thread=${thread.name} ${throwable.stackTraceToString().take(8192)}",
            )
            // 保持现有的线程异常策略；额外写入文件供离车导出诊断。
        }
        // 应用安装/卸载/更新 → 桌面与车机设置的共享应用清单缓存失效
        val packageFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    VirtualLauncher
                        .invalidateAppsCache()
                    Log.i(TAG, "应用清单缓存已失效（包变化）")
                }
            },
            packageFilter,
        )
    }

    companion object {
        private const val TAG = "CarPlaySender"
    }
}
