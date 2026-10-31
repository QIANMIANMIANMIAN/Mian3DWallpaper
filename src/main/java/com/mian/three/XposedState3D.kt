package com.mian.three

import android.content.Context
import android.content.SharedPreferences
import com.mian.three.xposed.ThreeHookEntry
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.io.File

/**
 * 模块侧（Manager App）与 hook 侧的配置桥梁。
 *
 * ============================ 为什么需要这一层 ============================
 * hook 代码运行在【主题 App 进程】里，它读的配置来自
 * `XposedModule.getRemotePreferences(...)` —— 那是 LSPosed 提供的
 * 跨进程 SharedPreferences 镜像。
 *
 * 本 App 的 UI 如果只写自己的 `getSharedPreferences(...)`，
 * 那是【另一个文件】，hook 侧根本看不到。
 *
 * 正确做法：UI 通过 `XposedService.getRemotePreferences(...)`
 * 往同一个分组里写 —— LSPosed 会把变更同步给宿进程，
 * 宿进程里的 remote prefs 监听器随即触发。
 *
 * ============================ 降级策略 ============================
 * 如果 LSPosed 服务没连上（模块未启用 / 框架未激活），
 * 退回写本地 prefs。这样用户至少能看到自己改过什么，
 * 等启用模块后再次修改即可生效。
 */
object XposedState3D : XposedServiceHelper.OnServiceListener {

    private const val TAG = "Mian3D"

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    fun init() {
        runCatching { XposedServiceHelper.registerListener(this) }
    }

    override fun onServiceBind(service: XposedService) {
        this.service = service
        remotePrefs = runCatching { service.getRemotePreferences(ThreeHookEntry.PREFS_NAME) }.getOrNull()
        Log3DApp.i("$TAG: XposedService 已连接，remotePrefs=${remotePrefs != null}")
    }

    override fun onServiceDied(service: XposedService) {
        this.service = null
        remotePrefs = null
        Log3DApp.i("$TAG: XposedService 已断开")
    }

    /** 是否已连上框架 */
    fun isConnected(): Boolean = service != null

    /** 供诊断用：拿到 XposedService 实例 */
    fun serviceOrNull(): XposedService? = service

    /**
     * 取得可写的配置对象。
     *
     * 优先 remote（能让 hook 侧立即看到），否则退回本地。
     * 每次调用都重新尝试 getRemotePreferences —— 因为 App 刚启动时
     * service 可能还没连上，之后才连上；缓存住 null 会导致一直读本地。
     */
    fun prefs(context: Context): SharedPreferences {
        val s = service
        if (s != null) {
            val rp = runCatching { s.getRemotePreferences(ThreeHookEntry.PREFS_NAME) }.getOrNull()
            if (rp != null) {
                remotePrefs = rp
                return rp
            }
        }
        return context.getSharedPreferences(ThreeHookEntry.PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 当前是否走的 remote（用于 UI 提示） */
    fun usingRemote(): Boolean = remotePrefs != null

    /**
     * 读取 LSPosed 侧诊断状态：作用域列表 + 是否已激活。
     *
     * 用途：hook 日志为空时，直接判断是「作用域没勾」还是「模块没启用」，
     * 用户不用翻 LSPosed 界面猜。
     */
    fun diagnose(context: Context): String {
        val sb = StringBuilder()
        val s = service
        if (s == null) {
            sb.append("LSPosed 服务：未连接\n")
            sb.append("→ 请确认 LSPosed 已安装并启用，且本模块在其列表中是开启状态")
            return sb.toString()
        }

        sb.append("LSPosed 服务：已连接\n")

        runCatching {
            val scope = s.scope.orEmpty()
            sb.append("模块作用域（").append(scope.size).append(" 个）：\n")
            if (scope.isEmpty()) {
                sb.append("  （空！必须在 LSPosed 里勾选「主题」）\n")
            } else {
                scope.forEach { p ->
                    val mark = if (p == "com.bbk.theme") "  ✅ " else "  ·  "
                    sb.append(mark).append(p).append('\n')
                }
            }
            if (!scope.contains("com.bbk.theme")) {
                sb.append("\n⚠ 作用域里没有 com.bbk.theme！\n")
                sb.append("→ 打开 LSPosed → 模块 → 米安3D壁纸 → 勾选「主题」\n")
                sb.append("→ 然后强行停止主题 App 并重开")
            }
        }.onFailure {
            sb.append("读取作用域失败：").append(it.javaClass.simpleName)
                .append(' ').append(it.message).append('\n')
        }

        return sb.toString()
    }
}

/**
 * App 侧日志 + 模块日志读取。
 *
 * ============================ 为什么要读取多个位置 ============================
 * hook 侧（主题进程）写日志到 Documents/Mian3D/module_<进程名>.log，
 * 但部分 ROM 的 scoped storage 会让本 App 读不到那个文件。
 * 所以这里按优先级尝试多个来源：
 *
 *   ① remote prefs 的 diag_log      —— 最可靠（Binder 传输，不涉文件权限）
 *   ② Documents/Mian3D/ 下的 .log   —— 直接读，能读就带上下文
 *   ③ /data/local/tmp/Mian3D/ 下的 .log —— 有 root 时兜底
 *   ④ logcat 转储                    —— 有 root 时 `logcat -d -s Mian3D`
 *
 * 全部失败时返回已收集到的部分内容，并在头部说明缺了哪些。
 */
internal object Log3DApp {

    private const val TAG = "Mian3D"

    private const val MAX_BYTES = 256 * 1024

    /** App 自身日志（写 media 目录，本 App 有权限） */
    fun i(msg: String) = runCatching {
        val ctx = AppHolder.get() ?: return@runCatching
        val dir = File(
            ctx.getExternalFilesDir(null) ?: ctx.filesDir,
            "logs",
        )
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "app.log")
        if (f.exists() && f.length() > MAX_BYTES) f.delete()
        f.appendText("${stamp()} INFO $msg\n")
    }.let { }

    // ==================== 收集完整日志 ====================

    /**
     * 汇总所有能拿到的日志，返回可直接展示 / 分享的文本。
     */
    fun collectAll(): String {
        val sb = StringBuilder()

        sb.append("===== 米安3D壁纸 诊断报告 =====\n")
        sb.append("时间: ${stamp()}\n")
        sb.append("App 版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("设备: ${android.os.Build.BRAND} ${android.os.Build.MODEL} / ")
            .append("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("LSPosed 服务: ${if (XposedState3D.isConnected()) "已连接" else "未连接"}\n")
        sb.append('\n')

        // ---- ① remote prefs ----
        sb.append("---------- [1] hook 日志（remote prefs）----------\n")
        val diag = runCatching {
            val ctx = AppHolder.get() ?: return@runCatching ""
            XposedState3D.prefs(ctx).getString("diag_log", "").orEmpty()
        }.getOrDefault("")
        if (diag.isBlank()) {
            sb.append("（空 —— hook 从未进入主题 App 进程）\n")
        } else {
            sb.append(diag).append('\n')
        }

        // ---- ② Documents ----
        sb.append("\n---------- [2] hook 日志（Documents 文件）----------\n")
        val docDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS,
            ),
            "Mian3D",
        )
        sb.append(readDir(docDir))

        // ---- ③ /data/local/tmp ----
        sb.append("\n---------- [3] hook 日志（/data/local/tmp，需 root）----------\n")
        sb.append(readDir(File("/data/local/tmp/Mian3D")))

        // ---- ④ logcat ----
        sb.append("\n---------- [4] logcat（需 root）----------\n")
        sb.append(readLogcat())

        // ---- ⑤ App 自身日志 ----
        sb.append("\n---------- [5] App 日志 ----------\n")
        runCatching {
            val ctx = AppHolder.get() ?: return@runCatching
            val dir = File(
                ctx.getExternalFilesDir(null) ?: ctx.filesDir,
                "logs",
            )
            val f = File(dir, "app.log")
            if (f.exists()) {
                sb.append(f.readText().takeLast(20_000)).append('\n')
            } else {
                sb.append("（无）\n")
            }
        }.onFailure { sb.append("读取失败：${it.message}\n") }

        return sb.toString()
    }

    /** 读一个目录下所有 .log，按名称排序拼接 */
    private fun readDir(dir: java.io.File): String {
        val sb = StringBuilder()
        if (!dir.exists() || !dir.isDirectory) {
            sb.append("（目录不存在：${dir.absolutePath}）\n")
            return sb.toString()
        }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
            ?.sortedBy { it.name }
            .orEmpty()

        if (files.isEmpty()) {
            sb.append("（目录存在但没有 .log 文件：${dir.absolutePath}）\n")
            return sb.toString()
        }

        files.forEach { f ->
            sb.append(">>> ${f.name} (${f.length()} 字节)\n")
            runCatching {
                sb.append(f.readText().takeLast(30_000)).append('\n')
            }.onFailure {
                sb.append("读取失败：${it.javaClass.simpleName} ${it.message}\n")
            }
        }
        return sb.toString()
    }

    /** 有 root 时转储 logcat；没有则说明原因 */
    private fun readLogcat(): String {
        val out = StringBuilder()
        val su = locateSu()
        if (su == null) {
            out.append("（无 root，跳过）\n")
            return out.toString()
        }
        runCatching {
            val p = ProcessBuilder(su, "-c", "logcat -d -s Mian3D -t 500")
                .redirectErrorStream(true)
                .start()
            p.outputStream.close()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (text.isBlank()) {
                out.append("（logcat 里没有 Mian3D 标签的记录）\n")
            } else {
                out.append(text.takeLast(20_000))
            }
        }.onFailure {
            out.append("执行失败：${it.javaClass.simpleName} ${it.message}\n")
        }
        return out.toString()
    }

    /** 找 su 可执行文件 */
    fun locateSu(): String? = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/debug_ramdisk/bin/su",
    ).firstOrNull { File(it).exists() }

    /** 是否有 root */
    fun hasRoot(): Boolean = locateSu() != null

    /**
     * 尝试用 root 把 hook 日志从受限位置复制到 App 可读目录。
     * 返回复制成功的文件路径列表。
     */
    fun pullLogsWithRoot(): List<String> {
        val su = locateSu() ?: return emptyList()
        val ctx = AppHolder.get() ?: return emptyList()
        val dstDir = File(ctx.getExternalFilesDir(null), "pulled")
        dstDir.mkdirs()

        val sources = listOf(
            "/storage/emulated/0/Documents/Mian3D",
            "/data/local/tmp/Mian3D",
        )

        val done = mutableListOf<String>()
        sources.forEach { src ->
            runCatching {
                // cp -rf <src> <dst>，用 root 绕过 scoped storage
                val p = ProcessBuilder(su, "-c", "cp -rf $src $dstDir 2>&1")
                    .redirectErrorStream(true)
                    .start()
                p.outputStream.close()
                p.waitFor()
            }
            val copied = File(dstDir, File(src).name)
            if (copied.exists()) done += copied.absolutePath
        }
        return done
    }

    private fun stamp(): String = java.text.SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss.SSS",
        java.util.Locale.US,
    ).format(java.util.Date())
}
