package com.mian.three.xposed

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块侧日志：logcat + 文件 + remote prefs 三路输出。
 *
 * ============================ 落盘方案完全照搬米安助手的 ModuleLog ============================
 * 米安助手在主题进程里能正常写出日志（用户实测 module_com.bbk.theme.log 有内容），
 * 它的做法有两个关键点，本类 1:1 复刻：
 *
 *   ① 用 Environment.getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS)
 *      而不是硬编码 "/storage/emulated/0/Documents"。
 *      后者在部分 ROM / targetSdk 组合下会被 scoped storage 拦掉，
 *      而前者走的是 legacy 公共目录 API，权限判定路径不同。
 *
 *   ② ★ 每个进程写各自的文件：module_<进程名>.log
 *      原因（米安助手源码原注释）：
 *        /storage/emulated/0 是 per-app FUSE 视图 ——
 *        若两个不同 UID 的进程共写同一个文件，文件归属会变成先创建者的 uid，
 *        另一个进程反而打不开（实测 EACCES）。
 *      分开写既避开冲突，也便于分辨"这行来自哪个进程"。
 *
 * 之前独立版写的 /storage/emulated/0/Android/media/com.mian.three/ 失败，
 * 就是因为那是【本模块 App】的 media 目录，主题进程（com.bbk.theme 的 UID）无权写。
 *
 * 另加两路保险：
 *   ③ logcat（tag=Mian3D）—— 永远可用
 *   ④ remote prefs 的 diag_log 键 —— App 界面直接显示，无需找文件
 */
internal object Log3D {

    private const val TAG = "Mian3D"

    /** 诊断信息在 remote prefs 里的键 */
    const val KEY_DIAG = "diag_log"

    private const val DIAG_MAX_CHARS = 6000

    private const val MAX_BYTES = 1024 * 1024L

    @Volatile private var logFile: File? = null

    @Volatile private var diagPrefs: SharedPreferences? = null

    private val buf = StringBuilder()

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // ==================== 初始化 ====================

    fun bindPrefs(prefs: SharedPreferences?) {
        diagPrefs = prefs
    }

    /**
     * 初始化文件日志。
     *
     * 与米安助手一致：
     *   · 目录 = getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS)/Mian3D
     *   · 文件名 = module_<进程名>.log（每进程一份，避开 FUSE 归属冲突）
     * 失败则退化为只写 logcat + prefs。
     */
    fun install() {
        if (logFile != null) return
        synchronized(this) {
            if (logFile != null) return

            runCatching {
                val base = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS,
                )
                val proc = runCatching { android.app.Application.getProcessName() }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
                val safe = proc.replace(Regex("[^A-Za-z0-9._]"), "_")

                val dir = File(base, "Mian3D")
                val file = File(dir, "module_$safe.log")
                dir.mkdirs()

                // 真实写一次确认权限
                file.appendText("")
                logFile = file

                Log.i(TAG, "文件日志 -> ${file.absolutePath}")
            }.onFailure {
                Log.w(TAG, "文件日志不可用：${it.javaClass.simpleName} ${it.message}")
            }
        }
    }

    /** 当前日志文件路径（null = 未启用） */
    fun logPath(): String? = logFile?.absolutePath

    // ==================== 输出 ====================

    fun i(msg: String) = out("INFO", msg)

    fun w(msg: String) = out("WARN", msg)

    fun e(msg: String) = out("ERROR", msg)

    private fun out(level: String, msg: String) {
        // ① logcat
        runCatching {
            if (level == "ERROR") Log.e(TAG, msg) else Log.i(TAG, msg)
        }

        val line = "${sdf.format(Date())} $level $msg"

        // ② 文件
        logFile?.let { f ->
            runCatching {
                if (f.exists() && f.length() > MAX_BYTES) {
                    f.delete()
                    f.appendText("---- 超过 1MB，已截断 ----\n")
                }
                f.appendText("$line\n")
            }
        }

        // ③ remote prefs（App 界面直读）
        synchronized(buf) {
            buf.append(line).append('\n')
            if (buf.length > DIAG_MAX_CHARS) {
                buf.delete(0, buf.length - DIAG_MAX_CHARS)
            }
            diagPrefs?.let { p ->
                runCatching { p.edit().putString(KEY_DIAG, buf.toString()).commit() }
            }
        }
    }

    fun clear() {
        synchronized(buf) { buf.setLength(0) }
    }
}
