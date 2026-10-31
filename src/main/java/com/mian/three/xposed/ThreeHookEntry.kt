package com.mian.three.xposed

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 米安3D壁纸 —— Xposed 模块入口。
 *
 * 类名必须与 META-INF/xposed/java_init.list 中的声明完全一致。
 *
 * ============================ 配置同步（修过两次，别再踩）============================
 *
 * 【坑 1】prefs 缓存
 *   getRemotePreferences() 每次返回新实例；若缓存成字段，
 *   监听器挂到旧实例上，后续写入收不到通知。
 *   → 现在用 by lazy 缓存一份，监听与读取共用同一个。
 *
 * 【坑 2】监听器只认一个 key（本版修复）
 *   旧代码：
 *       if (key == KEY_3D_ENABLE) { Three3DHooker.syncFromPrefs(prefs) }
 *   只处理 3D 开关，AI宠物 / 景深 / 特效 这几个 key 改了
 *   完全不会触发同步 → 表现就是"开了没用"。
 *   → 现在改成【任何 key 变化都全量重新同步】。
 *
 * 【坑 3】remote prefs 的写入传播不可靠
 *   LSPosed 的 RemotePreferences 由服务端持有，
 *   客户端 commit() 能否立刻可见依赖实现版本。
 *   → 除了监听器，再加一层【定时轮询】（2 秒一次，只在前 10 分钟内），
 *     双保险。代价极小（一次 getBoolean），换来"改了就生效"。
 */
class ThreeHookEntry : XposedModule() {

    companion object {
        /** 模块自己的配置（UI 写、hook 侧经 getRemotePreferences 读） */
        const val PREFS_NAME = "three_settings"

        /** 3D 壁纸开关 key */
        const val KEY_3D_ENABLE = "mk_3d_enable"

        /** AI 宠物解锁 */
        const val KEY_FEATURE_AIPET = "mk_feature_aipet"

        /** 景深解锁 */
        const val KEY_FEATURE_DEPTH = "mk_feature_depth"

        /** 景深：是否屏蔽「遮挡过多」弹窗 */
        const val KEY_FEATURE_DEPTH_MUTE = "mk_feature_depth_mute"

        /** 解锁全部壁纸特效（闪卡/解压/液态/光栅等） */
        const val KEY_FEATURE_EFFECTS = "mk_feature_effects"

        /** 特效解锁：是否输出诊断日志 */
        const val KEY_FEATURE_EFFECTS_DIAG = "mk_feature_effects_diag"

        /** 文件配置桥的绝对路径（与 data/ConfigBridge 保持一致） */
        const val FILE_CONFIG =
            "/storage/emulated/0/Android/media/com.mian.three/config.txt"

        @Volatile
        var debugLoggingEnabled: Boolean = false
            private set

        private val listeners =
            CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()
    }


    // ==================== 配置文件桥（优先于 remote prefs）====================

    /**
     * 从 /storage/emulated/0/Android/media/com.mian.three/config.txt 读开关。
     *
     * ★ 为什么优先用文件：
     *   实测 remote prefs 在不同 key 上的可见性不一致
     *   （aiPet/depth 能读，effects 读不到）。
     *   media 目录双方都可访问，文件方案没有跨进程传播问题。
     */
    private fun fileBool(key: String, def: Boolean): Boolean {
        return runCatching {
            val f = java.io.File(FILE_CONFIG)
            if (!f.exists()) return@runCatching def
            f.readLines().forEach { line ->
                val i = line.indexOf('=')
                if (i > 0 && line.substring(0, i).trim() == key) {
                    val v = line.substring(i + 1).trim()
                    return@runCatching v.equals("true", ignoreCase = true)
                }
            }
            def
        }.getOrDefault(def)
    }

    /**
     * 配置门面（remote prefs）。
     *
     * ★ 必须缓存：getRemotePreferences 每次调用会返回新的 SharedPreferences 实例，
     *   如果每次都新建，registerOnSharedPreferenceChangeListener 挂上去的那个实例
     *   会被后续调用替换掉，导致监听永远不触发。
     */
    private val prefsCache: SharedPreferences? by lazy {
        runCatching { getRemotePreferences(PREFS_NAME) }.getOrNull()
    }

    val hookedPrefs: SharedPreferences?
        get() = prefsCache

    /** 轮询任务（双保险，防止监听器在部分框架下不触发） */
    private var pollRunnable: Runnable? = null

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // prefs 先绑定 —— Log3D 要靠它把日志回传给 App 界面
        Log3D.bindPrefs(hookedPrefs)

        // 再初始化文件日志
        Log3D.install()

        Log3D.i("==================================================")
        Log3D.i("onPackageLoaded pkg=${param.packageName} isFirst=${param.isFirstPackage}")
        Log3D.i("  本模块目标包 = ${Three3DHooker.PKG_THEME}")
        Log3D.i("  命中目标？  = ${param.packageName == Three3DHooker.PKG_THEME}")
        Log3D.i("  日志文件 = ${Log3D.logPath() ?: "（不可写，仅 logcat + 界面）"}")

        // 同步开关 + 装监听
        runCatching {
            val p = hookedPrefs
            Log3D.i("remote prefs 可用 = ${p != null}")
            syncAll(p)
            installPrefsListener()
            startPolling()
        }.onFailure { Log3D.w("同步配置失败: ${it.javaClass.simpleName} ${it.message}") }

        // 只对目标 App 生效
        if (param.packageName == Three3DHooker.PKG_THEME) {
            Log3D.i("开始安装 3D hook（主题 App）")
            runCatching {
                Three3DHooker.install(this, param.defaultClassLoader, param.packageName)
            }.onFailure {
                Log3D.e("Three3DHooker.install 抛异常: ${it.javaClass.name} ${it.message}")
            }

            // 其它锁屏特效（AI 宠物 / 景深）
            runCatching {
                ThreeFeatureHooker.install(this, param.defaultClassLoader, param.packageName)
            }.onFailure {
                Log3D.e("ThreeFeatureHooker.install 抛异常: ${it.javaClass.name} ${it.message}")
            }

            // 强制景深（解除「遮挡过多」置灰 + 拦截弹窗）
            runCatching {
                ThreeDepthHooker.install(this, param.defaultClassLoader, param.packageName)
            }.onFailure {
                Log3D.e("ThreeDepthHooker.install 抛异常: ${it.javaClass.name} ${it.message}")
            }

            // 解锁全部壁纸特效
            runCatching {
                ThreeEffectUnlockHooker.install(this, param.defaultClassLoader, param.packageName)
            }.onFailure {
                Log3D.e("ThreeEffectUnlockHooker.install 抛异常: ${it.javaClass.name} ${it.message}")
            }
        } else {
            Log3D.i("非目标包，跳过 hook 安装")
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        Log3D.i("package ready pkg=" + param.packageName)
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        for (handle in param.oldHookHandles) {
            runCatching { handle.unhook() }
        }
    }

    /** 全量重新同步所有开关（文件优先，remote prefs 兜底） */
    private fun syncAll(prefs: SharedPreferences?) {
        val reader: (String, Boolean) -> Boolean = { k, d -> fileBool(k, d) }
        runCatching { Three3DHooker.syncFromPrefs(prefs, reader) }
        runCatching { ThreeFeatureHooker.syncFromPrefs(prefs, reader) }
        runCatching { ThreeDepthHooker.syncFromPrefs(prefs, reader) }
        runCatching { ThreeEffectUnlockHooker.syncFromPrefs(prefs, reader) }
    }

    /**
     * 配置变更时立即重新同步。
     *
     * ★ 任何 key 变化都同步全部 —— 旧代码只判断 KEY_3D_ENABLE，
     *   导致 AI宠物/景深/特效 改了不生效。
     */
    private fun installPrefsListener() {
        val prefs = hookedPrefs ?: return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            Log3D.i("prefs 变化 key=$key → 全量重新同步")
            syncAll(prefs)
        }
        listeners += listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 定时轮询：每 2 秒读一次开关，持续 5 分钟。
     *
     * 为什么需要：LSPosed 的 RemotePreferences 写入传播
     * 在不同版本实现下可靠性不一，有些情况下监听器不触发。
     * 轮询只是几次 getBoolean，开销可忽略。
     */
    private fun startPolling() {
        if (pollRunnable != null) return
        val handler = Handler(Looper.getMainLooper())
        var ticks = 0
        val r = object : Runnable {
            override fun run() {
                ticks++
                runCatching { syncAll(hookedPrefs) }
                // 150 次 × 2s = 5 分钟
                if (ticks < 150) {
                    handler.postDelayed(this, 2000L)
                } else {
                    pollRunnable = null
                }
            }
        }
        pollRunnable = r
        handler.postDelayed(r, 2000L)
        Log3D.i("配置轮询已启动（2 秒一次，共 5 分钟）")
    }
}
