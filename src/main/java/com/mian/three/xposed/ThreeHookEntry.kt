package com.mian.three.xposed

import android.content.SharedPreferences
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 米安3D壁纸 —— Xposed 模块入口。
 *
 * 类名必须与 META-INF/xposed/java_init.list 中的声明完全一致。
 * 本模块只做一件事：解锁 vivo 主题 App 的 3D 空间壁纸入口。
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


        @Volatile
        var debugLoggingEnabled: Boolean = false
            private set

        private val listeners =
            CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()
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

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // prefs 先绑定 —— Log3D 要靠它把日志回传给 App 界面
        Log3D.bindPrefs(hookedPrefs)

        // 再初始化文件日志（多目录回退）
        Log3D.install()

        Log3D.i("==================================================")
        Log3D.i("onPackageLoaded pkg=${param.packageName} isFirst=${param.isFirstPackage}")
        Log3D.i("  本模块目标包 = ${Three3DHooker.PKG_THEME}")
        Log3D.i("  命中目标？  = ${param.packageName == Three3DHooker.PKG_THEME}")
        Log3D.i("  日志文件 = ${Log3D.logPath() ?: "（不可写，仅 logcat + 界面）"}")

        // 同步开关 + 装监听（改设置后无需重启）
        runCatching {
            val p = hookedPrefs
            Log3D.i("remote prefs 可用 = ${p != null}")
            Three3DHooker.syncFromPrefs(p)
            ThreeFeatureHooker.syncFromPrefs(p)
            ThreeDepthHooker.syncFromPrefs(p)
            installPrefsListener()
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

    /** 配置变更时立即重新同步 */
    private fun installPrefsListener() {
        val prefs = hookedPrefs ?: return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_3D_ENABLE) {
                runCatching { Three3DHooker.syncFromPrefs(prefs) }
            }
        }
        listeners += listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
}
