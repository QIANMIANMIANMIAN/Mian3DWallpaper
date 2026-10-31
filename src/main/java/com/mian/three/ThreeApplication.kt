package com.mian.three

import android.app.Application
import com.mian.three.data.ConfigBridge
import com.mian.three.xposed.ThreeHookEntry

/**
 * 应用入口：连 LSPosed 服务 + 初始化全局 Context。
 *
 * 必须尽早注册监听，否则 UI 打开时 service 可能还没连上，
 * 导致写配置落到本地 prefs（hook 侧读不到）。
 */
class ThreeApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        AppHolder.ctx = applicationContext

        runCatching { XposedState3D.init() }
        Log3DApp.i("App 启动，等待 XposedService 连接")

        // ★ 把当前配置同步一份到文件桥，保证 hook 侧首次启动就能读到
        runCatching {
            val ctx = applicationContext
            val prefs = XposedState3D.prefs(ctx)
            val map = mapOf(
                ThreeHookEntry.KEY_3D_ENABLE to
                    prefs.getBoolean(ThreeHookEntry.KEY_3D_ENABLE, true),
                ThreeHookEntry.KEY_FEATURE_AIPET to
                    prefs.getBoolean(ThreeHookEntry.KEY_FEATURE_AIPET, false),
                ThreeHookEntry.KEY_FEATURE_DEPTH to
                    prefs.getBoolean(ThreeHookEntry.KEY_FEATURE_DEPTH, false),
                ThreeHookEntry.KEY_FEATURE_DEPTH_MUTE to
                    prefs.getBoolean(ThreeHookEntry.KEY_FEATURE_DEPTH_MUTE, true),
                ThreeHookEntry.KEY_FEATURE_EFFECTS to
                    prefs.getBoolean(ThreeHookEntry.KEY_FEATURE_EFFECTS, false),
                ThreeHookEntry.KEY_FEATURE_EFFECTS_DIAG to
                    prefs.getBoolean(ThreeHookEntry.KEY_FEATURE_EFFECTS_DIAG, true),
            )
            ConfigBridge.write(ctx, map)
            Log3DApp.i("配置已同步到文件桥: ${ConfigBridge.ABS_PATH}")
        }
    }
}
