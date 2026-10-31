package com.mian.three.xposed

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * vivo 主题 App（com.bbk.theme）的 3D 空间壁纸解锁。
 *
 * ============================ 需求 ============================
 * 让「主题」App 里出现并使用 3D 壁纸入口（空间场景 / SpatialScene）。
 *
 * ============================ 为什么本机本来能用（静态分析结论）============================
 * 主题 App 自带完整 3D 能力，不缺渲染实现：
 *   · com.vivo.themeprocess.plugins.component.Space3dComponent  （3D 组件）
 *   · com.vivo.themeprocess.vag.VAGPlayer                       （渲染播放器）
 *   · lib/arm64/libThemeProcess.so                              （OpenGL ES 引擎）
 *
 * libThemeProcess.so 符号表实证（3139 个符号）：
 *   EGL：  eglGetDisplay / eglInitialize / eglCreateContext / eglMakeCurrent
 *   GL：   glCreateShader / glCompileShader / glLinkProgram
 *          glUniform1f~4f / glUniformMatrix4fv   ← 4x4 矩阵，3D 变换
 *          glGenBuffers / glDrawArrays / glDrawElements
 *          glGenFramebuffers / glFramebufferTexture2D / glReadPixels
 *   硬件： AHardwareBuffer_allocate / AHardwareBuffer_lockPlanes（零拷贝纹理）
 *   质感： CColorTranslucentToImage::setBlurGlassRenderParams
 *
 * 所以缺的不是能力，而是【入口被三层判断挡住】。
 *
 * ============================ 判断链（反编译主题 23.8.5.1）============================
 *   ① com.bbk.theme.utils.gg.isSupport3DWallpaper()Z
 *        → return l6.getBooleanValue("3D_Support", false)
 *        纯本地开关，默认 false → 没有入口
 *
 *   ② com.bbk.theme.utils.ThemeUtils.isSupportCloudMesh3D()Z
 *        → if (!isLiveWallpaperSupportCloudMesh()) return false;
 *           return ReflectionUnit.isFtFeatureSupport("vivo.software.spatialscene");
 *
 *      isLiveWallpaperSupportCloudMesh():
 *        · isPad() || isFlip() → false
 *        · 读 com.vivo.livewallpaper.box 的 manifest meta-data
 *            support_cloud_render = 1（折叠屏还要 support_cloud_render_fold = 1）
 *
 *   ③ android.util.FtFeature.isFeatureSupport("vivo.software.spatialscene")
 *        系统级 feature flag，本机 false
 *
 * ============================ hook 策略 ============================
 * 五个点，任一为 false 都强制 true：
 *   ① gg.isSupport3DWallpaper()Z                      → true
 *   ② ThemeUtils.isSupportCloudMesh3D()Z              → true
 *   ②b ThemeUtils.isLiveWallpaperSupportCloudMesh()Z  → true
 *   ③ ReflectionUnit.isFtFeatureSupport(String)Z      → 仅 spatialscene 放行
 *   ④ l6.getBooleanValue(String, boolean)Z            → 仅 "3D_Support" 放行
 *
 * ★ ③ 为什么 hook 主题 App 自己的包装方法，而不是 android.util.FtFeature：
 *     后者在 boot classpath，hook 它会影响整个进程的所有 feature 查询；
 *     前者只作用于主题 App，副作用最小。
 * ★ ③④ 都做精确 key 匹配，其它 feature / 配置原样返回。
 */
internal object Three3DHooker {

    private const val TAG = "Mian3D"

    /** 主题 App */
    const val PKG_THEME = "com.bbk.theme"

    // ---------------- 目标类 / 方法 ----------------

    private const val CLS_GG = "com.bbk.theme.utils.gg"
    private const val M_IS_SUPPORT_3D = "isSupport3DWallpaper"

    private const val CLS_THEME_UTILS = "com.bbk.theme.utils.ThemeUtils"
    private const val M_IS_SUPPORT_CLOUD_MESH = "isSupportCloudMesh3D"
    private const val M_IS_LIVE_WALLPAPER_CLOUD_MESH = "isLiveWallpaperSupportCloudMesh"

    private const val CLS_REFLECTION_UNIT = "com.bbk.theme.os.utils.ReflectionUnit"
    private const val M_IS_FT_FEATURE_SUPPORT = "isFtFeatureSupport"

    private const val CLS_L6 = "com.bbk.theme.utils.l6"
    private const val M_GET_BOOLEAN = "getBooleanValue"

    /** ④ 需要放行的配置 key */
    private const val KEY_3D_SUPPORT = "3D_Support"

    /** ③ 需要放行的 feature 名 */
    private const val FEATURE_SPATIAL_SCENE = "vivo.software.spatialscene"

    // ---------------- 状态 ----------------

    /**
     * 总开关。
     *
     * ★ 初始值必须与 UI 默认值一致（true）。
     *   否则会出现：UI 里开关是开着的，但 hook 侧因为
     *   `syncFromPrefs` 拿不到 remote prefs 而直接 return，
     *   enabled 停留在 false —— 表现就是「明明开了却没效果」。
     */
    @Volatile var enabled: Boolean = true

    @Volatile private var installed: Boolean = false

    /** 各挂点生效次数（诊断） */
    @Volatile var hitCount3D: Int = 0
        private set
    @Volatile var hitCountCloudMesh: Int = 0
        private set
    @Volatile var hitCountFeature: Int = 0
        private set
    @Volatile var hitCountBool: Int = 0
        private set

    // ==================== 安装 ====================

    fun install(
        module: ThreeHookEntry,
        cl: ClassLoader,
        pkg: String,
    ): Boolean {
        if (installed) return true
        return runCatching {
            var hooked = 0
            val names = StringBuilder()

            Log3D.i("$TAG: 开始逐个挂载 hook 点...")

            // ---------- ① gg.isSupport3DWallpaper()Z → true ----------
            runCatching {
                val cls = cl.loadClass(CLS_GG)
                val m = cls.getDeclaredMethod(M_IS_SUPPORT_3D)
                m.isAccessible = true
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain: XposedInterface.Chain ->
                        val ret = chain.proceed()
                        if (!enabled) return@intercept ret
                        hitCount3D++
                        if (hitCount3D <= 3) {
                            Log3D.i("$TAG: ✅ isSupport3DWallpaper() 原=$ret → true（第 $hitCount3D 次）")
                        }
                        true
                    }
                hooked++
                names.append("isSupport3DWallpaper ")
                Log3D.i("$TAG: [1/5] ✅ $CLS_GG.$M_IS_SUPPORT_3D")
            }.onFailure {
                Log3D.e("$TAG: [1/5] ❌ $CLS_GG.$M_IS_SUPPORT_3D 失败: ${it.javaClass.simpleName} ${it.message}")
            }

            // ---------- ② ThemeUtils.isSupportCloudMesh3D()Z → true ----------
            runCatching {
                val cls = cl.loadClass(CLS_THEME_UTILS)
                val m = cls.getDeclaredMethod(M_IS_SUPPORT_CLOUD_MESH)
                m.isAccessible = true
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain: XposedInterface.Chain ->
                        val ret = chain.proceed()
                        if (!enabled) return@intercept ret
                        hitCountCloudMesh++
                        if (hitCountCloudMesh <= 3) {
                            Log3D.i("$TAG: ✅ isSupportCloudMesh3D() 原=$ret → true（第 $hitCountCloudMesh 次）")
                        }
                        true
                    }
                hooked++
                names.append("isSupportCloudMesh3D ")
                Log3D.i("$TAG: [2/5] ✅ $CLS_THEME_UTILS.$M_IS_SUPPORT_CLOUD_MESH")
            }.onFailure {
                Log3D.e("$TAG: [2/5] ❌ $CLS_THEME_UTILS.$M_IS_SUPPORT_CLOUD_MESH 失败: " +
                    "${it.javaClass.simpleName} ${it.message}")
            }

            // ---------- ②b isLiveWallpaperSupportCloudMesh()Z → true ----------
            runCatching {
                val cls = cl.loadClass(CLS_THEME_UTILS)
                val m = cls.getDeclaredMethod(M_IS_LIVE_WALLPAPER_CLOUD_MESH)
                m.isAccessible = true
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain: XposedInterface.Chain ->
                        val ret = chain.proceed()
                        if (!enabled) return@intercept ret
                        if (ret != true) {
                            Log3D.i("$TAG: isLiveWallpaperSupportCloudMesh() 原=$ret → true")
                        }
                        true
                    }
                hooked++
                names.append("isLiveWallpaperSupportCloudMesh ")
                Log3D.i("$TAG: [3/5] ✅ $CLS_THEME_UTILS.$M_IS_LIVE_WALLPAPER_CLOUD_MESH")
            }.onFailure {
                Log3D.e("$TAG: [3/5] ❌ $CLS_THEME_UTILS.$M_IS_LIVE_WALLPAPER_CLOUD_MESH 失败: " +
                    "${it.javaClass.simpleName} ${it.message}")
            }

            // ---------- ③ ReflectionUnit.isFtFeatureSupport(String)Z ----------
            runCatching {
                val cls = cl.loadClass(CLS_REFLECTION_UNIT)
                val m = cls.getDeclaredMethod(M_IS_FT_FEATURE_SUPPORT, String::class.java)
                m.isAccessible = true
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain: XposedInterface.Chain ->
                        val key = chain.args.getOrNull(0) as? String
                        val ret = chain.proceed()
                        if (!enabled) return@intercept ret
                        if (key == FEATURE_SPATIAL_SCENE) {
                            hitCountFeature++
                            if (hitCountFeature <= 3) {
                                Log3D.i("$TAG: ✅ isFtFeatureSupport(\"$key\") 原=$ret → true（第 $hitCountFeature 次）")
                            }
                            true
                        } else {
                            ret
                        }
                    }
                hooked++
                names.append("isFtFeatureSupport ")
                Log3D.i("$TAG: [4/5] ✅ $CLS_REFLECTION_UNIT.$M_IS_FT_FEATURE_SUPPORT")
            }.onFailure {
                Log3D.e("$TAG: [4/5] ❌ $CLS_REFLECTION_UNIT.$M_IS_FT_FEATURE_SUPPORT 失败: " +
                    "${it.javaClass.simpleName} ${it.message}")
            }

            // ---------- ④ l6.getBooleanValue(String, boolean)Z ----------
            runCatching {
                val cls = cl.loadClass(CLS_L6)
                cls.declaredMethods
                    .filter { it.name == M_GET_BOOLEAN && it.parameterTypes.size == 2 }
                    .forEach { m ->
                        m.isAccessible = true
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain: XposedInterface.Chain ->
                                val key = chain.args.getOrNull(0) as? String
                                val ret = chain.proceed()
                                if (!enabled) return@intercept ret
                                if (key == KEY_3D_SUPPORT) {
                                    hitCountBool++
                                    if (hitCountBool <= 3) {
                                        Log3D.i("$TAG: ✅ getBooleanValue(\"$key\") 原=$ret → true（第 $hitCountBool 次）")
                                    }
                                    true
                                } else {
                                    ret
                                }
                            }
                        hooked++
                    }
                names.append("getBooleanValue ")
                Log3D.i("$TAG: [5/5] ✅ $CLS_L6.$M_GET_BOOLEAN（$hooked 个重载累计）")
            }.onFailure {
                Log3D.e("$TAG: [5/5] ❌ $CLS_L6.$M_GET_BOOLEAN 失败: " +
                    "${it.javaClass.simpleName} ${it.message}")
            }

            installed = hooked > 0
            Log3D.i("$TAG: ========== 安装完成 ==========")
            Log3D.i("$TAG: 成功挂点数 = $hooked")
            Log3D.i("$TAG: 挂点清单 = $names")
            Log3D.i("$TAG: enabled = $enabled（false 时 hook 会原样放行，看起来就像没生效）")
            if (!installed) {
                Log3D.e("$TAG: 一个挂点都没成功！主题 App 的类名可能变了，请核对版本")
            }
            hooked > 0
        }.getOrElse {
            Log3D.w("$TAG: 安装失败：${it.javaClass.simpleName} ${it.message}")
            false
        }
    }

    // ==================== 配置 ====================

    /** 读取开关状态（module 侧 remote prefs） */
    fun syncFromPrefs(prefs: SharedPreferences?) {
        // ★ 拿不到 remote prefs 时【不改】enabled，
        //   让它保持初始值 true（UI 默认也是开），
        //   否则会变成「UI 显示已开、hook 侧却是关」的静默失效。
        if (prefs == null) {
            Log3D.w("$TAG: remote prefs 不可用，保持当前 enabled=$enabled")
            return
        }
        runCatching {
            val old = enabled
            enabled = prefs.getBoolean(ThreeHookEntry.KEY_3D_ENABLE, true)
            if (old != enabled) {
                Log3D.i("$TAG: 开关 $old -> $enabled")
                if (!enabled) {
                    hitCount3D = 0
                    hitCountCloudMesh = 0
                    hitCountFeature = 0
                    hitCountBool = 0
                }
            }
        }.onFailure {
            Log3D.w("$TAG: 读取配置失败：${it.message}")
        }
    }

    fun statusLine(): String =
        "$TAG enabled=$enabled installed=$installed " +
            "3D=$hitCount3D cloudMesh=$hitCountCloudMesh " +
            "feature=$hitCountFeature bool=$hitCountBool"
}
