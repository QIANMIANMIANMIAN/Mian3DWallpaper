package com.mian.three.data

import android.content.Context
import java.io.File

/**
 * App ↔ hook 的配置桥（文件方案）。
 *
 * ============================ 为什么不用 remote prefs ============================
 * 原本走 LSPosed 的 `XposedService.getRemotePreferences()`：
 *   App 侧写 → 框架同步给宿进程 → hook 侧 `getRemotePreferences()` 读。
 *
 * 实测问题（多轮日志验证）：
 *   · MianFeature 的 aiPet / depth 能读到 true
 *   · 但 MianEffect 的 effects 永远是 false
 *   · 同一个 remote prefs、同一时刻，一个能读一个不能读
 *   → 说明 App 侧的某些写入没有真正进入 remote prefs
 *     （XposedState3D.service 在 UI 进程可能为 null，
 *        prefs() 退回本地文件，而本地文件与 remote 不同步）
 *
 * ============================ 现在用文件 ============================
 * 把开关写成一行一行的 `key=value`，放在
 *   /storage/emulated/0/Android/media/com.mian.three/config.txt
 *
 * 为什么这个位置两边都能访问：
 *   · App（com.mian.three）对自己的 media 目录有完整读写权
 *   · media 目录（Android/media/<pkg>）对整个外部存储可读，
 *     主题 App 进程（com.bbk.theme）也能读 —— 这是 Android 的既定设计，
 *     与 scoped storage 无关
 *
 * 格式极简（不引 JSON，避免解析失败）：
 *   mk_3d_enable=true
 *   mk_feature_aipet=false
 *   mk_feature_depth=true
 *   mk_feature_depth_mute=true
 *   mk_feature_effects=true
 *   mk_feature_effects_diag=true
 *   ts=1793445000000
 *
 * hook 侧读这个文件（每 2 秒轮询，文件很小，开销可忽略）。
 */
object ConfigBridge {

    const val DIR_NAME = "com.mian.three"

    const val FILE_NAME = "config.txt"

    /** 供两边共用的绝对路径 */
    val ABS_PATH = "/storage/emulated/0/Android/media/$DIR_NAME/$FILE_NAME"

    /** App 侧：把当前配置写入文件 */
    fun write(context: Context, values: Map<String, Boolean>): Boolean = runCatching {
        val f = File(ABS_PATH)
        f.parentFile?.mkdirs()
        val text = buildString {
            values.forEach { (k, v) -> append(k).append('=').append(v).append('\n') }
            append("ts=").append(System.currentTimeMillis()).append('\n')
        }
        f.writeText(text)
        true
    }.getOrDefault(false)

    /** 读文件为 Map（App 侧和 hook 侧都能用） */
    fun read(): Map<String, String> = runCatching {
        val f = File(ABS_PATH)
        if (!f.exists()) return@runCatching emptyMap()
        f.readLines()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()
    }.getOrDefault(emptyMap())
}
