package com.mian.three

import android.app.Application

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
    }
}
