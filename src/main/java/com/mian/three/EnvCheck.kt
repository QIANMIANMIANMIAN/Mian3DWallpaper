package com.mian.three

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * 3D 空间壁纸的完整环境检测。
 *
 * ============================ 需要哪些条件 ============================
 * 从主题 App 23.8.5.1 反编译 + 实测得出，缺任何一项都开不起来：
 *
 *  ┌─ ① 硬件/机型 ────────────────────────────────────────────
 *  │   · 不能是平板（ThemeUtils.isPad()）
 *  │   · 不能是折叠屏（ThemeUtils.isFlip()）
 *  │     → 这两个条件在 isLiveWallpaperSupportCloudMesh() 里直接 return false
 *  │
 *  ├─ ② 主题 App ────────────────────────────────────────────
 *  │   · com.bbk.theme 已安装
 *  │   · 必须自带 lib/arm64/libThemeProcess.so（VAG 渲染引擎）
 *  │     → 没有这个 so，即使 hook 全过也渲染不出东西
 *  │   · 版本建议 23.8.x 及以上（类名/方法名在该版本实测可用）
 *  │
 *  ├─ ③ 动态壁纸盒子 ────────────────────────────────────────
 *  │   · com.vivo.livewallpaper.box 已安装
 *  │   · 其 manifest 必须有：
 *  │       <meta-data android:name="support_cloud_render" android:value="1"/>
 *  │       <meta-data android:name="support_cloud_render_fold" android:value="1"/>
 *  │     → 这是 isLiveWallpaperSupportCloudMesh() 的判据
 *  │
 *  ├─ ④ 传感器权限 ──────────────────────────────────────────
 *  │   · 「设备运动与方向」（陀螺仪）
 *  │     → checkMask3DCache() 命中缓存后，还要 hasSensorPermission()
 *  │     → 没有的话会弹「请求传感器权限」对话框
 *  │
 *  ├─ ⑤ 模块注入 ────────────────────────────────────────────
 *  │   · LSPosed 已安装并激活
 *  │   · 本模块在 LSPosed 里已启用
 *  │   · 作用域勾选「主题」（com.bbk.theme）
 *  │   · 改完作用域后必须强行停止主题 App
 *  │
 *  └─ ⑥ 网络（仅首次生成需要）────────────────────────────────
 *      · 需要上传图片到 vivo 服务器生成 3D 数据
 *      · 需要 vivo 账号配额
 *      · 已有缓存时不联网也能开
 *
 * ============================ 检测结果的分级 ============================
 *   OK    —— 满足
 *   WARN  —— 可能影响，但不一定致命
 *   FAIL  —— 一定开不起来
 *   INFO  —— 仅展示信息
 */
object EnvCheck {

    enum class Level { OK, WARN, FAIL, INFO }

    data class Item(
        val title: String,
        val level: Level,
        val detail: String,
        /** 修复建议；null = 无需操作 */
        val hint: String? = null,
    )

    data class Report(
        val items: List<Item>,
        val canWork: Boolean,
        val hasFatal: Boolean,
    )

    // ==================== 包名常量 ====================

    private const val PKG_THEME = "com.bbk.theme"
    private const val PKG_BOX = "com.vivo.livewallpaper.box"
    private const val PKG_BOX_CONFIG = "com.vivo.livewallpaper.boxconfig"
    private const val PKG_LSPOSED = "org.lsposed.manager"
    private const val PKG_LSPOSED_ALT = "org.lsposed.manager.debug"

    // ==================== 主检测 ====================

    fun run(context: Context): Report {
        val items = mutableListOf<Item>()

        items += checkDevice(context)
        items += checkThemeApp(context)
        items += checkThemeRenderer(context)
        items += checkLiveWallpaperBox(context)
        items += checkSensor(context)
        items += checkLsposed(context)
        items += checkModuleScope()
        items += checkHookLog()

        val hasFatal = items.any { it.level == Level.FAIL }
        return Report(
            items = items,
            canWork = !hasFatal,
            hasFatal = hasFatal,
        )
    }

    // ==================== ① 硬件 ====================

    private fun checkDevice(ctx: Context): Item {
        val isTablet = runCatching {
            val smallest = ctx.resources.configuration.smallestScreenWidthDp
            smallest >= 600
        }.getOrDefault(false)

        val brand = Build.BRAND ?: ""
        val model = Build.MODEL ?: ""
        val device = Build.DEVICE ?: ""

        // 折叠屏判定：vivo 常见代号含 flip/fold
        val looksFold = listOf(device, model).any {
            val s = it.lowercase()
            s.contains("flip") || s.contains("fold")
        }

        return when {
            isTablet -> Item(
                "机型检查", Level.FAIL,
                "检测到平板（smallestWidth=${ctx.resources.configuration.smallestScreenWidthDp}dp）。" +
                    "主题 App 的 isPad() 会直接拒绝 3D 功能。",
                "平板无法启用，这是主题 App 的硬性限制",
            )
            looksFold -> Item(
                "机型检查", Level.FAIL,
                "检测到折叠屏设备（$brand $model / $device）。" +
                    "isLiveWallpaperSupportCloudMesh() 对 isFlip() 直接返回 false。",
                "折叠屏无法启用",
            )
            else -> Item(
                "机型检查", Level.OK,
                "$brand $model（$device），非平板 / 非折叠屏",
            )
        }
    }

    // ==================== ② 主题 App ====================

    private fun checkThemeApp(context: Context): Item {
        val info = runCatching {
            context.packageManager.getPackageInfo(PKG_THEME, 0)
        }.getOrNull()

        if (info == null) {
            return Item(
                "主题 App", Level.FAIL,
                "未安装 com.bbk.theme",
                "需要安装 vivo「主题」App（建议 23.8.x 及以上）",
            )
        }

        val ver = info.versionName ?: "?"
        val major = ver.substringBefore('.').toIntOrNull() ?: 0

        return if (major < 23) {
            Item(
                "主题 App", Level.WARN,
                "版本 $ver 偏低。本模块的 hook 点在 23.8.5.1 上实测通过，" +
                    "旧版本类名可能不同。",
                "建议升级到 23.8.x 及以上",
            )
        } else {
            Item("主题 App", Level.OK, "版本 $ver")
        }
    }

    // ==================== ②b 渲染引擎 so ====================

    private fun checkThemeRenderer(context: Context): Item {
        val info = runCatching {
            context.packageManager.getApplicationInfo(PKG_THEME, 0)
        }.getOrNull() ?: return Item(
            "渲染引擎", Level.FAIL, "主题 App 未安装，无法检查", null,
        )

        val libDir = info.nativeLibraryDir
        val so = File(libDir, "libThemeProcess.so")

        return when {
            so.exists() && so.length() > 100_000 -> Item(
                "渲染引擎", Level.OK,
                "libThemeProcess.so 存在（${so.length() / 1024 / 1024}MB）",
            )
            so.exists() -> Item(
                "渲染引擎", Level.WARN,
                "libThemeProcess.so 存在但只有 ${so.length()} 字节，可能被裁剪",
                "建议重新安装完整版主题 App",
            )
            else -> Item(
                "渲染引擎", Level.FAIL,
                "主题 App 的 lib 目录里没有 libThemeProcess.so。\n" +
                    "路径：$libDir",
                "该 ROM 的主题 App 被裁剪了渲染引擎，无法启用 3D",
            )
        }
    }

    // ==================== ③ 动态壁纸盒子 ====================

    private fun checkLiveWallpaperBox(context: Context): Item {
        val pm = context.packageManager
        val info = runCatching {
            pm.getPackageInfo(PKG_BOX, PackageManager.GET_META_DATA)
        }.getOrNull()

        if (info == null) {
            return Item(
                "动态壁纸盒子", Level.FAIL,
                "未安装 com.vivo.livewallpaper.box",
                "需要安装「动态壁纸盒子」",
            )
        }

        // ★ metaData.getInt 在值不是 int 类型时会抛 ClassCastException，
        //   这里用 get() 取原始对象再自行解析，任何异常都降级为 0。
        val meta = runCatching { info.applicationInfo?.metaData }.getOrNull()

        fun metaInt(key: String): Int = runCatching {
            when (val v = meta?.get(key)) {
                null -> 0
                is Int -> v
                is String -> v.trim().toIntOrNull() ?: 0
                is Boolean -> if (v) 1 else 0
                else -> 0
            }
        }.getOrDefault(0)

        val cloudRender = metaInt("support_cloud_render")
        val cloudFold = metaInt("support_cloud_render_fold")
        val ver = runCatching { info.versionName }.getOrNull() ?: "?"

        return when {
            cloudRender == 1 -> Item(
                "动态壁纸盒子", Level.OK,
                "版本 $ver，support_cloud_render=1" +
                    if (cloudFold == 1) "，support_cloud_render_fold=1" else "",
            )
            else -> Item(
                "动态壁纸盒子", Level.FAIL,
                "版本 $ver，但 manifest 里 support_cloud_render=$cloudRender（需要 =1）。\n" +
                    "这是 isLiveWallpaperSupportCloudMesh() 的判据。",
                "需要支持 cloud render 的版本（实测 6.0.8.01 满足）",
            )
        }
    }

    // ==================== ④ 传感器权限 ====================

    private fun checkSensor(context: Context): Item {
        // 「设备运动与方向」不是 Android 标准权限名，而是 vivo 权限管理里的分组名，
        // 实际对应的是【传感器访问】本身。所以这里只判断硬件能力，
        // 不误报权限状态（主题 App 首次开启时会自己弹授权框）。
        val hasGyro = runCatching {
            val sm = context.getSystemService(Context.SENSOR_SERVICE)
                as android.hardware.SensorManager
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null
        }.getOrDefault(false)

        val hasAccel = runCatching {
            val sm = context.getSystemService(Context.SENSOR_SERVICE)
                as android.hardware.SensorManager
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null
        }.getOrDefault(false)

        return when {
            !hasGyro -> Item(
                "运动传感器", Level.FAIL,
                "设备没有陀螺仪。3D 视差依赖陀螺仪驱动，无法替代。",
                "硬件限制，无法启用",
            )
            !hasAccel -> Item(
                "运动传感器", Level.WARN,
                "有陀螺仪但没有加速度计，视差效果可能不完整",
                null,
            )
            else -> Item(
                "运动传感器", Level.OK,
                "陀螺仪 + 加速度计均可用。" +
                    "首次开启时主题 App 会请求「设备运动与方向」授权，允许即可。",
            )
        }
    }

    // ==================== ⑤ LSPosed ====================

    private fun checkLsposed(context: Context): Item {
        val pm = context.packageManager
        val has = listOf(PKG_LSPOSED, PKG_LSPOSED_ALT).any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        }

        if (!has) {
            return Item(
                "LSPosed", Level.WARN,
                "未检测到 LSPosed 管理器包（可能用的是其它管理器，或未安装）",
                "需要 LSPosed 框架才能注入主题 App",
            )
        }

        return if (XposedState3D.isConnected()) {
            Item("LSPosed", Level.OK, "已连接 LSPosed 服务")
        } else {
            Item(
                "LSPosed", Level.FAIL,
                "检测到 LSPosed，但本模块的服务未连接。\n" +
                    "说明模块在 LSPosed 里未启用。",
                "打开 LSPosed → 模块 → 启用「米安3D壁纸」",
            )
        }
    }

    // ==================== ⑤b 作用域 ====================

    private fun checkModuleScope(): Item {
        val s = XposedState3D.serviceOrNull() ?: return Item(
            "模块作用域", Level.FAIL, "LSPosed 服务未连接，无法读取作用域", null,
        )

        val scope = runCatching { s.scope.orEmpty() }.getOrDefault(emptyList())

        // ★ 关键：scope 只是【声明】，runningTargets 才是【真的注入】
        val targets = runCatching {
            s.getRunningTargets().mapNotNull { it.processName }.distinct()
        }.getOrDefault(emptyList())
        val injected = targets.any { it.startsWith(PKG_THEME) }

        return when {
            scope.isEmpty() -> Item(
                "模块作用域", Level.FAIL,
                "声明作用域为空",
                "LSPosed → 模块 → 米安3D壁纸 → 勾选「主题」",
            )
            !scope.contains(PKG_THEME) -> Item(
                "模块作用域", Level.FAIL,
                "声明作用域里没有 com.bbk.theme\n当前：${scope.joinToString()}",
                "LSPosed → 模块 → 米安3D壁纸 → 勾选「主题」→ 强行停止主题 App 重开",
            )
            !injected -> Item(
                "模块注入", Level.FAIL,
                "作用域已声明，但主题进程还没被注入。\n" +
                    "当前已注入的进程：${if (targets.isEmpty()) "（无）" else targets.joinToString()}",
                "设置 → 应用 → 主题 → 强行停止 → 重新打开",
            )
            else -> Item(
                "模块注入", Level.OK,
                "✅ 主题进程已注入（作用域 ${scope.size} 项，已注入 ${targets.size} 个进程）",
            )
        }
    }

    // ==================== ⑤c hook 是否真的跑了 ====================

    private fun checkHookLog(): Item {
        val diag = runCatching {
            val ctx = AppHolder.get() ?: return@runCatching ""
            XposedState3D.prefs(ctx).getString("diag_log", "").orEmpty()
        }.getOrDefault("")

        return when {
            diag.isEmpty() -> Item(
                "Hook 注入", Level.FAIL,
                "没有任何 hook 日志。说明模块根本没进入主题 App 进程。",
                "确认作用域已勾选 → 强行停止主题 App → 重新打开",
            )
            diag.contains("安装完成") && diag.contains("成功挂点数 = 0") -> Item(
                "Hook 注入", Level.FAIL,
                "hook 跑了但一个挂点都没成功，主题 App 版本可能不匹配",
                "把「运行日志」内容反馈给作者",
            )
            else -> Item(
                "Hook 注入", Level.OK,
                "已注入并挂载成功",
            )
        }
    }
}

/**
 * 全局 Application Context 持有者。
 *
 * ★ 用 nullable + 判空，不要用 `null!!`：
 *   `null!!` 在赋值前被访问会直接抛 NullPointerException，
 *   而 Application.onCreate 里的调用顺序一旦变化（或某些 ROM 提前触发
 *   ContentProvider / 静态初始化），就会变成启动即闪退。
 */
object AppHolder {
    @Volatile
    var ctx: Context? = null

    /** 拿不到时返回 null，调用方自行降级 */
    fun get(): Context? = ctx
}
