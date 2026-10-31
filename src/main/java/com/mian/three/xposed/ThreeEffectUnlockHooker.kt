package com.mian.three.xposed

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field

/**
 * 解锁主题 App 的全部壁纸特效（闪卡 / 解压 / 液态 / 光栅 / 烟花 / 山海 / 抽象光影…）。
 *
 * ============================ 核心发现（反编译 主题 23.8.5.1）============================
 *
 * 所有特效的开关**不是**「设备能力标志」，而是【主题包元数据的数值阈值】。
 * 关键字段：`ThemeWallpaperInfo.customConvertInfo.customSubType`
 *
 * 各模板编号（TEMPLE_TYPE_SUBTYPE）：
 *     0x1    = 1     静态
 *     0x2    = 2     动态
 *     0x3    = 3     阅图
 *     0x3e9  = 1001  多彩
 *     0x3ea  = 1002  实景
 *     0x3eb  = 1003  双重曝光
 *     0x3ec  = 1004  双重电影
 *     0x3ed  = 1005  胶片滤镜
 *     0x3ef  = 1007  文字贴纸
 *     0x7d1  = 2001  山海
 *     0xbb9  = 3001  烟花
 *     0xfa1  = 4001  万能图形
 *     0x1389 = 5001  ★ 光栅（用户有，能用）
 *     0x1771 = 6001  抽象光影
 *     0x1b59 = 7001  写实
 *     0x1f41 = 8001  文字宣言
 *     0x2329 = 9001  ★ 解压（捏泡泡）
 *     0x2711 = 10001 ★ 闪卡
 *     0x272f = 10031 贴纸主题
 *     0x2739 = 10041 ★ AI 宠物
 *
 * ============================ 三条实际的门槛（这才是限制所在）============================
 *
 * ① b1.initLoadTemplateType(wallpaperInfo, previewType, previewTypeEnum, editThemeInfo, slot)
 *    · previewType == 3 时：customSubType >= 0x3e9 (1001) 才走模板
 *    · previewType == 4 时：customSubType >= 0x7d1 (2001) 才走模板
 *    · AI 宠物(0x2739) 有单独分支，无条件走
 *    → 返回：1/2/3/4 = 各种模板类型；其它 = 不渲染
 *
 * ② tc.w0.getWallpaperCanPlugin(wallpaperInfo)
 *    条件： (wallpaperCustomProperty & 0x8) == 0x8
 *       或  customSubType == 0x1f41 (8001)
 *    → true 才能用插件化渲染
 *
 * ③ p0.isNonGratingSupportLiveResDir(descInfo, wallpaperInfo)
 *    判断"非光栅的实况资源目录是否支持"
 *    光栅走专门路径，其它特效走这条
 *
 * ============================ hook 策略 ============================
 * 三层都放行，让任意主题都能进入插件化渲染：
 *   ① b1.initLoadTemplateType(...)  → 强制返回合适的模板类型（按 customSubType 映射）
 *   ② tc.w0.getWallpaperCanPlugin(...) → 强制 true
 *   ③ p0.isNonGratingSupportLiveResDir(...) → 强制 true
 *   ④ b1.isRaster(...) → 保持原样（光栅有独立路径，不干扰）
 *
 * 另外提供诊断：把每次调用时实际的 subType / customSubType / previewType
 * 打到日志，方便对照到底是哪一步被挡住。
 *
 * ============================ 风险说明 ============================
 * · 这只能让代码"走模板渲染分支"
 * · VAG 引擎仍需要主题包里真的存在对应模板资源（.json + 图层）
 * · 若主题包缺资源，可能黑屏而非崩溃（引擎对缺失资源一般有兜底）
 * · 所有 hook 包 runCatching，单点失败不影响其它
 */
internal object ThreeEffectUnlockHooker {

    private const val TAG = "MianEffect"

    // ---------------- 目标类 ----------------

    private const val CLS_B1 = "com.bbk.theme.themeEditer.utils.b1"
    private const val CLS_W0 = "tc.w0"
    private const val CLS_P0 = "com.bbk.theme.themeEditer.utils.p0"

    private const val M_INIT_LOAD_TEMPLATE = "initLoadTemplateType"
    private const val M_CAN_PLUGIN = "getWallpaperCanPlugin"
    private const val M_NON_GRATING_LIVE = "isNonGratingSupportLiveResDir"

    /** 需要读写的字段名 */
    private const val F_CUSTOM_CONVERT_INFO = "customConvertInfo"
    private const val F_CUSTOM_SUB_TYPE = "customSubType"
    private const val F_SUB_TYPE = "subType"
    private const val F_WALLPAPER_CUSTOM_PROPERTY = "wallpaperCustomProperty"

    // ---------------- 状态 ----------------

    @Volatile var enabled: Boolean = false

    /** 是否输出诊断日志（打印实际 subType 等） */
    @Volatile var diag: Boolean = true

    @Volatile private var installed: Boolean = false

    @Volatile var hitTemplate: Int = 0
        private set
    @Volatile var hitPlugin: Int = 0
        private set
    @Volatile var hitLiveRes: Int = 0
        private set

    private var fConvertInfo: Field? = null
    private var fCustomSubType: Field? = null
    private var fSubType: Field? = null
    private var fCustomProp: Field? = null

    // ==================== 安装 ====================

    fun install(
        module: ThreeHookEntry,
        cl: ClassLoader,
        pkg: String,
    ): Boolean {
        if (installed) return true
        return runCatching {
            var n = 0

            // 预先取字段（后面读 customSubType 用）
            runCatching {
                val c = cl.loadClass("com.bbk.theme.wallpaper.bean.ThemeWallpaperInfo")
                fConvertInfo = c.getField(F_CUSTOM_CONVERT_INFO).apply { isAccessible = true }
                fSubType = c.getField(F_SUB_TYPE).apply { isAccessible = true }
                fCustomProp = c.getField(F_WALLPAPER_CUSTOM_PROPERTY).apply { isAccessible = true }
                val ci = cl.loadClass(
                    "com.bbk.theme.wallpaper.bean.ThemeWallpaperInfo\$CustomConvertInfo",
                )
                fCustomSubType = ci.getField(F_CUSTOM_SUB_TYPE).apply { isAccessible = true }
            }.onFailure {
                Log3D.w("$TAG: 反射字段失败（诊断功能受限）: ${it.javaClass.simpleName}")
            }

            // ---------- ① b1.initLoadTemplateType → 强制走模板 ----------
            runCatching {
                val cls = cl.loadClass(CLS_B1)
                cls.declaredMethods
                    .filter {
                        it.name == M_INIT_LOAD_TEMPLATE &&
                            it.returnType == Int::class.javaPrimitiveType
                    }
                    .forEach { m ->
                        m.isAccessible = true
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain: XposedInterface.Chain ->
                                val ret = chain.proceed() as? Int ?: 0
                                if (!enabled) return@intercept ret

                                val info = chain.args.getOrNull(0)
                                val previewType = (chain.args.getOrNull(1) as? Int) ?: -1
                                val subType = readInt(info, fSubType) ?: -1
                                val customSub = readCustomSubType(info) ?: -1

                                if (diag && hitTemplate < 20) {
                                    hitTemplate++
                                    Log3D.i(
                                        "$TAG: [诊断] initLoadTemplateType " +
                                            "previewType=$previewType subType=$subType " +
                                            "customSubType=$customSub → 原返回=$ret",
                                    )
                                }

                                // 原来返回 0 = 不走模板；强制给一个有效模板类型
                                if (ret == 0) {
                                    val forced = forceTemplateType(previewType, customSub, subType)
                                    hitTemplate++
                                    if (hitTemplate <= 5 || hitTemplate % 50 == 0) {
                                        Log3D.i(
                                            "$TAG: ✅ 强制模板 previewType=$previewType " +
                                                "customSub=$customSub → $forced（第 $hitTemplate 次）",
                                        )
                                    }
                                    return@intercept forced
                                }
                                ret
                            }
                        n++
                        Log3D.i("$TAG: ✅ $CLS_B1.$M_INIT_LOAD_TEMPLATE")
                    }
            }.onFailure {
                Log3D.w("$TAG: ❌ initLoadTemplateType: ${it.javaClass.simpleName} ${it.message}")
            }

            // ---------- ② tc.w0.getWallpaperCanPlugin → true ----------
            runCatching {
                val cls = cl.loadClass(CLS_W0)
                cls.declaredMethods
                    .filter {
                        it.name == M_CAN_PLUGIN &&
                            it.returnType == Boolean::class.javaPrimitiveType
                    }
                    .forEach { m ->
                        m.isAccessible = true
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain: XposedInterface.Chain ->
                                val ret = chain.proceed() as? Boolean ?: false
                                if (!enabled) return@intercept ret
                                val info = chain.args.getOrNull(0)
                                val customSub = readCustomSubType(info) ?: -1
                                val prop = readInt(info, fCustomProp) ?: -1
                                hitPlugin++
                                if (hitPlugin <= 5) {
                                    Log3D.i(
                                        "$TAG: ✅ getWallpaperCanPlugin 原=$ret → true " +
                                            "(customSub=$customSub prop=$prop)",
                                    )
                                }
                                true
                            }
                        n++
                        Log3D.i("$TAG: ✅ $CLS_W0.$M_CAN_PLUGIN")
                    }
            }.onFailure {
                Log3D.w("$TAG: ❌ getWallpaperCanPlugin: ${it.javaClass.simpleName} ${it.message}")
            }

            // ---------- ③ p0.isNonGratingSupportLiveResDir → true ----------
            runCatching {
                val cls = cl.loadClass(CLS_P0)
                cls.declaredMethods
                    .filter {
                        it.name == M_NON_GRATING_LIVE &&
                            it.returnType == Boolean::class.javaPrimitiveType
                    }
                    .forEach { m ->
                        m.isAccessible = true
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain: XposedInterface.Chain ->
                                val ret = chain.proceed() as? Boolean ?: false
                                if (!enabled) return@intercept ret
                                hitLiveRes++
                                if (hitLiveRes <= 3) {
                                    Log3D.i("$TAG: ✅ isNonGratingSupportLiveResDir 原=$ret → true")
                                }
                                true
                            }
                        n++
                        Log3D.i("$TAG: ✅ $CLS_P0.$M_NON_GRATING_LIVE")
                    }
            }.onFailure {
                Log3D.w("$TAG: ❌ isNonGratingSupportLiveResDir: ${it.javaClass.simpleName} ${it.message}")
            }

            installed = n > 0
            Log3D.i("$TAG: 特效解锁安装完成，挂点数=$n")
            n > 0
        }.getOrElse {
            Log3D.e("$TAG: 安装失败: ${it.javaClass.simpleName} ${it.message}")
            false
        }
    }

    // ==================== 辅助 ====================

    /**
     * 给定 previewType / customSubType / subType，算出一个有效的模板类型返回值。
     *
     * 从 initLoadTemplateType 的原始逻辑反推：
     *   返回 1 = 普通模板
     *   返回 2 = loadThemeByBitmap（视频景深外侧）
     *   返回 3 = 光栅/高级模板
     *   返回 4 = 另一条高级路径
     * 具体值对渲染结果影响不大 —— 关键是**不能是 0**，否则整个模板加载被跳过。
     * 所以这里按 previewType 给一个最稳妥的非零值。
     */
    private fun forceTemplateType(previewType: Int, customSub: Int, subType: Int): Int {
        // AI 宠物单独分支，原逻辑里它无条件走
        if (subType == 0x2739 || customSub == 0x2739) return 1

        return when (previewType) {
            1 -> 1
            2 -> 1
            3 -> 1   // 高级模板（customSubType >= 0x3e9 的路径）
            4 -> 1   // 另一条高级路径
            else -> 1
        }
    }

    private fun readCustomSubType(info: Any?): Int? {
        val ci = readObj(info, fConvertInfo) ?: return null
        return readInt(ci, fCustomSubType)
    }

    private fun readInt(target: Any?, field: Field?): Int? {
        if (target == null || field == null) return null
        return runCatching { field.getInt(target) }.getOrNull()
    }

    private fun readObj(target: Any?, field: Field?): Any? {
        if (target == null || field == null) return null
        return runCatching { field.get(target) }.getOrNull()
    }

    // ==================== 配置 ====================

    fun syncFromPrefs(
        prefs: SharedPreferences?,
        fileBool: ((String, Boolean) -> Boolean)? = null,
    ) {
        runCatching {
            fun bool(key: String, def: Boolean): Boolean =
                fileBool?.invoke(key, def) ?: prefs?.getBoolean(key, def) ?: def

            enabled = bool(ThreeHookEntry.KEY_FEATURE_EFFECTS, false)
            diag = bool(ThreeHookEntry.KEY_FEATURE_EFFECTS_DIAG, true)
            Log3D.i("$TAG: 配置 enabled=$enabled diag=$diag")
        }.onFailure {
            Log3D.w("$TAG: 读取配置失败: ${it.message}")
        }
    }

    fun statusLine(): String =
        "$TAG enabled=$enabled template=$hitTemplate plugin=$hitPlugin liveRes=$hitLiveRes"
}
