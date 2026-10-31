package com.mian.three.xposed

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * 强制景深：解除「遮挡过多」导致景深按钮变灰的限制。
 *
 * ============================ 主题 App 的景深判断链（反编译 23.8.5.1）============================
 *
 *  UI 层
 *    EditorTipsDialogUtils.showDepthSuggest(Context)
 *      ← 弹「将壁纸主体物与时钟重叠以体验"景深"效果。当时钟遮挡过多时无法生效。」
 *      调用方：b7.b（编辑页）、LivePhotoSwitchVideoView
 *
 *  开关状态层（★ 真正决定按钮灰不灰）
 *    tc.w0.getDeepSwitchStateFromWallpaper(ThemeWallpaperInfo, EditThemeInfo): Boolean
 *      · wallpaperInfo == null → false
 *      · getEditThemeInfoByWallpaper().isDeepOpened()（默认 1）
 *      · wallpaperCustomProperty & 2 == 2 → true
 *      · type==9 && subType==2 → 走特殊分支
 *      · readDeepProperty(wallpaperInfo, type) 读深度属性
 *      · customConvertInfo.customSubType >= 0x3e9 → 用 isDeepSwitchState()
 *      · ★ 最后：if (result && wallpaperInfo.isLivePhoto()) return false
 *        —— 实况照片强制禁用景深
 *
 *    oc.j.getDeepSwitchState(ThemeWallpaperInfo, EditThemeInfo): Boolean
 *      → isVideoDepthSupported() && (
 *            isRasterVideoTemplateType() ? isRasterVideoDeepSwitchState()
 *                                        : isDeepSwitchState() )
 *
 *    oc.j.getStaticDeepSwitchState(...): Boolean
 *      → DeepOpenedCanSwapState.getStaticSwitchState()
 *        再叠加 isAIPetWallpaper() 等判断
 *
 *    oc.j.getVideoDeepSwitchState(...): Boolean
 *      → DeepOpenedCanSwapState.getLiveSwitchState()
 *
 *    oc.j.isVideoDepthAvailable(): Boolean
 *      → isVideoDepthWallpaperType() && isVideoDepthSupported()
 *        （折叠屏还有 isFoldVideoSame 限制）
 *
 * ============================ 「遮挡过多」到底是什么 ============================
 * 它不是上面任何一个方法的返回值，而是**渲染期**判定：
 *   · VAG 引擎加载深度图（depth mask）后，检测「时钟/组件覆盖区域」是否过大
 *   · 覆盖过大 → 判定景深无法生效 → 回调把按钮置灰 + 弹提示
 *
 * 所以强制策略分两层：
 *   ① **开关状态层**：让所有 get*SwitchState / is*Available 返回 true
 *      → 按钮不再变灰（主修复）
 *   ② **弹窗层**：让 showDepthSuggest 不再弹（避免打扰）
 *
 * ============================ 为什么不 hook VAG native ============================
 * 遮挡判定在 libThemeProcess.so 内部，改 native 风险极高（崩溃/黑屏），
 * 而 UI 层的按钮灰化完全由 Java 层返回值驱动。
 * 只要这些方法恒返回 true，按钮就一直可用 —— 至于是不是真的"遮挡过多"，
 * 交给用户自己判断（这正是需求：想随意开景深）。
 */
internal object ThreeDepthHooker {

    private const val TAG = "MianDepth"

    // ---------------- 目标类 ----------------

    /** 开关状态汇总（static / video / 通用） */
    private const val CLS_J = "oc.j"

    /** 壁纸属性 → 开关状态 */
    private const val CLS_W0 = "tc.w0"

    /** 弹窗工具 */
    private const val CLS_TIPS = "com.bbk.theme.themeEditer.view.EditorTipsDialogUtils"

    // ---------------- 目标方法 ----------------

    private const val M_GET_DEEP_SWITCH = "getDeepSwitchState"
    private const val M_GET_STATIC_SWITCH = "getStaticDeepSwitchState"
    private const val M_GET_VIDEO_SWITCH = "getVideoDeepSwitchState"
    private const val M_IS_AVAILABLE = "isVideoDepthAvailable"
    private const val M_FROM_WALLPAPER = "getDeepSwitchStateFromWallpaper"
    private const val M_SHOW_DEPTH_SUGGEST = "showDepthSuggest"

    // ---------------- 状态 ----------------

    @Volatile var enabled: Boolean = false

    /** 是否同时屏蔽「遮挡过多」弹窗 */
    @Volatile var muteDialog: Boolean = true

    @Volatile private var installed: Boolean = false

    @Volatile var hitSwitch: Int = 0
        private set
    @Volatile var hitDialog: Int = 0
        private set

    // ==================== 安装 ====================

    fun install(
        module: ThreeHookEntry,
        cl: ClassLoader,
        pkg: String,
    ): Boolean {
        if (installed) return true
        return runCatching {
            var n = 0

            // ---------- ① oc.j 里的四个开关/可用性方法 ----------
            n += hookTrue(cl, module, CLS_J, M_GET_DEEP_SWITCH, "oc.j.getDeepSwitchState")
            n += hookTrue(cl, module, CLS_J, M_GET_STATIC_SWITCH, "oc.j.getStaticDeepSwitchState")
            n += hookTrue(cl, module, CLS_J, M_GET_VIDEO_SWITCH, "oc.j.getVideoDeepSwitchState")
            n += hookTrue(cl, module, CLS_J, M_IS_AVAILABLE, "oc.j.isVideoDepthAvailable")

            // ---------- ② tc.w0.getDeepSwitchStateFromWallpaper ----------
            n += hookTrue(cl, module, CLS_W0, M_FROM_WALLPAPER, "tc.w0.getDeepSwitchStateFromWallpaper")

            // ---------- ③ 屏蔽「遮挡过多」弹窗 ----------
            runCatching {
                val cls = cl.loadClass(CLS_TIPS)
                cls.declaredMethods
                    .filter { it.name == M_SHOW_DEPTH_SUGGEST }
                    .forEach { m ->
                        m.isAccessible = true
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain: XposedInterface.Chain ->
                                if (!enabled || !muteDialog) return@intercept chain.proceed()
                                hitDialog++
                                if (hitDialog <= 3) {
                                    Log3D.i("$TAG: ✅ 已拦截「遮挡过多」弹窗（第 $hitDialog 次）")
                                }
                                // 直接吞掉，不弹
                                null
                            }
                        n++
                        Log3D.i("$TAG: ✅ 拦截 $CLS_TIPS.$M_SHOW_DEPTH_SUGGEST")
                    }
            }.onFailure {
                Log3D.w("$TAG: ❌ 拦截弹窗失败: ${it.javaClass.simpleName} ${it.message}")
            }

            installed = n > 0
            Log3D.i("$TAG: 景深强制安装完成，挂点数=$n")
            n > 0
        }.getOrElse {
            Log3D.e("$TAG: 安装失败: ${it.javaClass.simpleName} ${it.message}")
            false
        }
    }

    /** 挂一个返回 boolean 的方法，强制 true */
    private fun hookTrue(
        cl: ClassLoader,
        module: ThreeHookEntry,
        clsName: String,
        methodName: String,
        label: String,
    ): Int {
        var count = 0
        runCatching {
            val cls = cl.loadClass(clsName)
            cls.declaredMethods
                .filter {
                    it.name == methodName && it.returnType == Boolean::class.javaPrimitiveType
                }
                .forEach { m ->
                    m.isAccessible = true
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain: XposedInterface.Chain ->
                            val ret = chain.proceed() as? Boolean ?: false
                            if (!enabled) return@intercept ret
                            hitSwitch++
                            if (hitSwitch <= 8) {
                                Log3D.i(
                                    "$TAG: ✅ $label 原=$ret → true（第 $hitSwitch 次）",
                                )
                            }
                            true
                        }
                    count++
                    Log3D.i("$TAG: ✅ $label（$clsName.$methodName，$cls.declaredMethods.size 个方法）")
                }
        }.onFailure {
            Log3D.w("$TAG: ❌ $label 挂载失败: ${it.javaClass.simpleName} ${it.message}")
        }
        return count
    }

    // ==================== 配置 ====================

    fun syncFromPrefs(prefs: SharedPreferences?) {
        if (prefs == null) return
        runCatching {
            enabled = prefs.getBoolean(ThreeHookEntry.KEY_FEATURE_DEPTH, false)
            muteDialog = prefs.getBoolean(ThreeHookEntry.KEY_FEATURE_DEPTH_MUTE, true)
            Log3D.i("$TAG: 配置 enabled=$enabled muteDialog=$muteDialog")
        }.onFailure {
            Log3D.w("$TAG: 读取配置失败: ${it.message}")
        }
    }

    fun statusLine(): String =
        "$TAG enabled=$enabled mute=$muteDialog switch=$hitSwitch dialog=$hitDialog"
}
