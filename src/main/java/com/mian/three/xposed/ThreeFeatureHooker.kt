package com.mian.three.xposed

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * 主题 App 的其它锁屏特效解锁（AI 宠物 / 景深 / 闪卡 / 液态 / 解压 / 光栅）。
 *
 * 与 [Three3DHooker] 分开，互不干扰：那个管 3D 空间壁纸，这个管其余特效。
 *
 * ============================ 每个功能的判断链（反编译 主题 23.8.5.1）============================
 *
 * 【AI 宠物】
 *   ① com.bbk.theme.utils.h0.isSupportAiPetTheme()Z
 *        · 海外版且内存 ≤6GB 且机型不含 PD2574 → false
 *        · isRamLessOrEqual6(ctx) → 直接 false
 *        · pv.e.j() && isGetAODMetaDataSupportAiPet()
 *        结论：**RAM > 6GB + 机型白名单 + AOD 元数据** 三重门
 *
 *   ② com.bbk.theme.utils.h0.isGetAODMetaDataSupportAiPet()Z
 *        读系统界面组件（com.vivo.systemuiplugin）的 manifest 标志
 *
 *   ③ com.bbk.theme.utils.ma.isSupportAIPetFullAODFeature()Z
 *        pm.getApplicationInfo("com.vivo.systemuiplugin", GET_META_DATA)
 *        .metaData.getBoolean("systemui.support.aipet.fullaod", false)
 *
 *   ④ com.bbk.theme.utils.ma.isSupportAIPetThemeEditorFeature()Z
 *        主题编辑器里能否编辑 AI 宠物
 *
 * 【景深】
 *   ⑤ com.bbk.theme.utils.h0.isDisableDecorateDeepBlur(Z)Z
 *        返回 true = 禁用景深。hook 后强制 false = 允许
 *
 * ============================ hook 风险控制 ============================
 * · 全部包在独立的 runCatching 里，单个挂点失败不影响其它
 * · 只改返回 boolean 的「能力判断」方法，不改有业务副作用的方法
 */
internal object ThreeFeatureHooker {

    private const val TAG = "MianFeature"

    // ---------------- AI 宠物 ----------------

    private const val CLS_H0 = "com.bbk.theme.utils.h0"
    private const val M_SUPPORT_AIPET = "isSupportAiPetTheme"
    private const val M_AIPET_AOD_META = "isGetAODMetaDataSupportAiPet"

    private const val CLS_MA = "com.bbk.theme.utils.ma"
    private const val M_AIPET_FULL_AOD = "isSupportAIPetFullAODFeature"
    private const val M_AIPET_EDITOR = "isSupportAIPetThemeEditorFeature"

    // ---------------- 景深 ----------------

    private const val M_DISABLE_DEEP_BLUR = "isDisableDecorateDeepBlur"

    // ---------------- 状态 ----------------

    @Volatile var enabledAiPet: Boolean = false
    @Volatile var enabledDepth: Boolean = false

    @Volatile private var installed: Boolean = false

    @Volatile var hitAiPet: Int = 0
        private set
    @Volatile var hitDepth: Int = 0
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

            // ---------- AI 宠物：4 个判断点 ----------
            n += hookBool(cl, module, CLS_H0, M_SUPPORT_AIPET, "h0.isSupportAiPetTheme", true)
            n += hookBool(cl, module, CLS_H0, M_AIPET_AOD_META, "h0.isGetAODMetaDataSupportAiPet", true)
            n += hookBool(cl, module, CLS_MA, M_AIPET_FULL_AOD, "ma.isSupportAIPetFullAODFeature", true)
            n += hookBool(cl, module, CLS_MA, M_AIPET_EDITOR, "ma.isSupportAIPetThemeEditorFeature", true)

            // ---------- 景深：isDisableDecorateDeepBlur 强制 false ----------
            runCatching {
                val cls = cl.loadClass(CLS_H0)
                cls.declaredMethods
                    .filter {
                        it.name == M_DISABLE_DEEP_BLUR &&
                            it.returnType == Boolean::class.javaPrimitiveType
                    }
                    .forEach { m ->
                        m.isAccessible = true
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain: XposedInterface.Chain ->
                                val ret = chain.proceed() as? Boolean ?: false
                                if (!enabledDepth) return@intercept ret
                                hitDepth++
                                if (hitDepth <= 5) {
                                    Log3D.i("$TAG: ✅ isDisableDecorateDeepBlur 原=$ret → false（允许景深）")
                                }
                                false
                            }
                        n++
                        Log3D.i("$TAG: [景深] ✅ $CLS_H0.$M_DISABLE_DEEP_BLUR")
                    }
            }.onFailure {
                Log3D.w("$TAG: [景深] ❌ $M_DISABLE_DEEP_BLUR: ${it.javaClass.simpleName}")
            }

            installed = n > 0
            Log3D.i("$TAG: 特效 hook 安装完成，挂点数=$n")
            n > 0
        }.getOrElse {
            Log3D.e("$TAG: 安装失败: ${it.javaClass.simpleName} ${it.message}")
            false
        }
    }

    /**
     * 挂一个返回 boolean 的能力判断方法，让它固定返回 true。
     *
     * @param isAiPet true = 计入 AI 宠物统计，false = 计入其它
     */
    private fun hookBool(
        cl: ClassLoader,
        module: ThreeHookEntry,
        clsName: String,
        methodName: String,
        label: String,
        isAiPet: Boolean,
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
                            if (!enabledAiPet) return@intercept ret
                            if (isAiPet) {
                                hitAiPet++
                                if (hitAiPet <= 5) {
                                    Log3D.i("$TAG: ✅ $label 原=$ret → true（第 $hitAiPet 次）")
                                }
                            }
                            true
                        }
                    count++
                    Log3D.i("$TAG: ✅ $label（$clsName.$methodName）")
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
            enabledAiPet = prefs.getBoolean(ThreeHookEntry.KEY_FEATURE_AIPET, false)
            enabledDepth = prefs.getBoolean(ThreeHookEntry.KEY_FEATURE_DEPTH, false)
            Log3D.i("$TAG: 配置 aiPet=$enabledAiPet depth=$enabledDepth")
        }.onFailure {
            Log3D.w("$TAG: 读取配置失败: ${it.message}")
        }
    }

    fun statusLine(): String =
        "$TAG aiPet=$enabledAiPet($hitAiPet) depth=$enabledDepth($hitDepth)"
}
