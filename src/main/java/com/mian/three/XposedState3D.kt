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

    /** 目标：vivo 主题 App */
    private const val PKG_THEME = "com.bbk.theme"

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
     * 读取 LSPosed 侧诊断状态。
     *
     * ============================ 之前为什么不准确 ============================
     * 旧版只看 `service.scope`（模块声明的目标列表），就下结论说"作用域正确"。
     * 但 scope 只是【声明】，不代表模块真的被注入到那个进程里。
     *
     * 准确的判据是 `service.runningTargets`：
     * 它返回【当前真正被 hook 注入的进程】，只有进程真实存在且注入成功才会有。
     *
     * 所以现在分三层报：
     *   ① 服务是否连上（LSPosed 里模块启用了吗）
     *   ② 声明的作用域里有没有 com.bbk.theme
     *   ③ runningTargets 里有没有 com.bbk.theme —— ★ 这才是"真的注入成功"
     */
    fun diagnose(context: Context): String {
        val sb = StringBuilder()
        val s = service
        if (s == null) {
            sb.append("LSPosed 服务：❌ 未连接\n")
            sb.append("→ 请确认 LSPosed 已安装并启用，且本模块在其模块列表中是【开启】状态")
            return sb.toString()
        }

        sb.append("LSPosed 服务：✅ 已连接\n")

        // ---- 框架信息 ----
        runCatching {
            sb.append("框架：").append(s.frameworkName)
                .append(" ").append(s.frameworkVersion)
                .append("（API ").append(s.apiVersion).append("）\n")
        }

        // ---- ② 声明的作用域 ----
        runCatching {
            val scope = s.scope.orEmpty()
            sb.append("\n② 声明作用域（").append(scope.size).append(" 个）：")
            if (scope.isEmpty()) {
                sb.append("❌ 空\n")
                sb.append("→ LSPosed → 模块 → 米安3D壁纸 → 勾选「主题」")
            } else {
                sb.append('\n')
                scope.forEach { p ->
                    sb.append(if (p == PKG_THEME) "  ✅ " else "  ·  ").append(p).append('\n')
                }
                if (!scope.contains(PKG_THEME)) {
                    sb.append("  ⚠ 里面没有 com.bbk.theme！\n")
                    sb.append("  → 去 LSPosed 勾选「主题」")
                }
            }
        }.onFailure {
            sb.append("\n② 读取作用域失败：").append(it.javaClass.simpleName)
                .append(' ').append(it.message)
        }

        // ---- ③ 真实注入状态（关键）----
        runCatching {
            val targets = s.getRunningTargets()
            sb.append("\n③ 已注入进程（").append(targets.size).append(" 个）：")
            if (targets.isEmpty()) {
                sb.append("❌ 无\n")
                sb.append("→ 模块还没被任何进程加载。\n")
                sb.append("→ 改变作用域后必须【强行停止主题 App】再重开")
            } else {
                sb.append('\n')
                val names = targets.mapNotNull { it.processName }.distinct()
                names.forEach { p ->
                    sb.append(if (p.startsWith(PKG_THEME)) "  ✅ " else "  ·  ")
                        .append(p).append('\n')
                }

                val hit = names.any { it.startsWith(PKG_THEME) }
                if (hit) {
                    sb.append("\n✅ 主题进程已注入，hook 应该已在工作")
                } else {
                    sb.append("\n⚠ 主题进程【没有】被注入！")
                    sb.append("\n→ 作用域虽勾了，但进程还没重载：")
                    sb.append("\n   设置 → 应用 → 主题 → 强行停止 → 重新打开")
                }
            }
        }.onFailure {
            sb.append("\n③ 读取运行目标失败：").append(it.javaClass.simpleName)
                .append(' ').append(it.message)
                .append("\n（该框架版本可能不支持 runningTargets）")
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

        // ---- ② Documents（用户实测 hook 日志就落在这里）----
        sb.append("\n---------- [2] hook 日志（Documents/Mian3D）----------\n")
        sb.append(readHookLogDir())

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

    /**
     * 读取 hook 写的日志目录（Documents/Mian3D）。
     *
     * ============================ 为什么要 root 兜底 ============================
     * hook 跑在主题 App 进程（com.bbk.theme 的 UID），它把日志写到
     *   /storage/emulated/0/Documents/Mian3D/module_<进程名>.log
     *
     * 但本 App 是 targetSdk 35，受 scoped storage 限制，
     * 通常【读不到】公共 Documents 下的文件（FUSE 权限按 UID 区分）。
     *
     * 所以策略是：
     *   ① 先直接读（部分 ROM 放行）
     *   ② 读不到且有 root → su -c "cat <file>" 读出来
     *   ③ 还是不行 → 提示用户用 root 拉取
     */
    private fun readHookLogDir(): String {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS,
            ),
            "Mian3D",
        )
        val sb = StringBuilder()

        // ① 直接读
        val direct = readDir(dir)
        if (!direct.contains("目录不存在") && !direct.contains("没有 .log 文件")) {
            return direct
        }

        sb.append("（直接读取失败：").append(dir.absolutePath).append("）\n")

        // ② root 兜底
        val su = locateSu()
        if (su == null) {
            sb.append("（无 root，无法读取；可在文件管理器手动查看该目录）\n")
            return sb.toString()
        }

        runCatching {
            // 列出文件
            val listOut = execSu(su, "ls -1 $dir 2>/dev/null")
            val names = listOut.lines().map { it.trim() }.filter { it.endsWith(".log") }

            if (names.isEmpty()) {
                sb.append("（root 也找不到 .log 文件，目录可能为空）\n")
                return sb.toString()
            }

            names.forEach { name ->
                sb.append(">>> $name（root 读取）\n")
                val content = execSu(su, "cat $dir/$name 2>/dev/null")
                sb.append(content.takeLast(30_000)).append('\n')
            }
        }.onFailure {
            sb.append("root 读取失败：${it.javaClass.simpleName} ${it.message}\n")
        }

        return sb.toString()
    }

    /** 用 su 执行一条命令，返回 stdout */
    private fun execSu(su: String, cmd: String): String {
        return runCatching {
            val p = ProcessBuilder(su, "-c", cmd)
                .redirectErrorStream(true)
                .start()
            p.outputStream.close()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text
        }.getOrDefault("")
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
