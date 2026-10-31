package com.mian.three.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mian.three.BuildConfig
import com.mian.three.EnvCheck
import com.mian.three.Log3DApp
import com.mian.three.R
import com.mian.three.XposedState3D
import com.mian.three.xposed.ThreeHookEntry
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 米安3D壁纸 —— 单页设置。
 *
 * 结构：
 *   ① 开关
 *   ② 环境检测（6 类条件逐项列出 OK / WARN / FAIL + 修复建议）
 *   ③ 运行日志（hook 侧写入，支持一键导出）
 *   ④ 使用步骤
 *   ⑤ 关于
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { enableEdgeToEdge() }
        setContent {
            MiuixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                    ) {
                        Page()
                    }
                }
            }
        }
    }
}

@Composable
private fun Page() {
    val context = LocalContext.current
    // ★ 用 runCatching 包住：首次组合时 LSPosed service 可能还没就绪，
    //   prefs() 内部会走 Binder，异常会直接把 Activity 干掉（表现为闪退）。
    val prefs = remember {
        runCatching { XposedState3D.prefs(context) }
            .getOrElse { context.getSharedPreferences(ThreeHookEntry.PREFS_NAME, Context.MODE_PRIVATE) }
    }
    val remote = remember { runCatching { XposedState3D.usingRemote() }.getOrDefault(false) }

    var enabled by remember {
        mutableStateOf(
            runCatching { prefs.getBoolean(ThreeHookEntry.KEY_3D_ENABLE, true) }
                .getOrDefault(true),
        )
    }

    var aiPetOn by remember {
        mutableStateOf(
            runCatching { prefs.getBoolean(ThreeHookEntry.KEY_FEATURE_AIPET, false) }
                .getOrDefault(false),
        )
    }
    var depthOn by remember {
        mutableStateOf(
            runCatching { prefs.getBoolean(ThreeHookEntry.KEY_FEATURE_DEPTH, false) }
                .getOrDefault(false),
        )
    }

    var report by remember { mutableStateOf<EnvCheck.Report?>(null) }
    var diag by remember { mutableStateOf("") }

    val refresh: () -> Unit = {
        report = runCatching { EnvCheck.run(context) }.getOrNull()
        diag = runCatching {
            XposedState3D.prefs(context).getString("diag_log", "").orEmpty()
        }.getOrDefault("")
    }

    LaunchedEffect(Unit) { refresh() }

    // ==================== ① 开关 ====================
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Text(
            text = stringResource(R.string.group_title),
            fontSize = 14.sp,
            color = colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
        )
        Text(
            text = stringResource(R.string.group_caption),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 6.dp),
        )
        SwitchPreference(
            title = stringResource(R.string.enable_title),
            summary = stringResource(
                if (remote) R.string.enable_summary else R.string.enable_summary_local,
            ),
            checked = enabled,
            onCheckedChange = { v ->
                enabled = v
                runCatching {
                    prefs.edit().putBoolean(ThreeHookEntry.KEY_3D_ENABLE, v).commit()
                }.onFailure {
                    Toast.makeText(context, "保存失败：${it.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    // ==================== ② 其它锁屏特效 ====================
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Text(
            text = stringResource(R.string.feature_group_title),
            fontSize = 14.sp,
            color = colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
        )
        Text(
            text = stringResource(R.string.feature_group_caption),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 6.dp),
        )

        SwitchPreference(
            title = stringResource(R.string.feature_aipet_title),
            summary = stringResource(R.string.feature_aipet_summary),
            checked = aiPetOn,
            onCheckedChange = { v ->
                aiPetOn = v
                runCatching {
                    prefs.edit().putBoolean(ThreeHookEntry.KEY_FEATURE_AIPET, v).commit()
                }
            },
        )
        SwitchPreference(
            title = stringResource(R.string.feature_depth_title),
            summary = stringResource(R.string.feature_depth_summary),
            checked = depthOn,
            onCheckedChange = { v ->
                depthOn = v
                runCatching {
                    prefs.edit().putBoolean(ThreeHookEntry.KEY_FEATURE_DEPTH, v).commit()
                }
            },
        )
    }

    // ==================== ③ 环境检测 ====================
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Text(
            text = stringResource(R.string.check_title),
            fontSize = 14.sp,
            color = colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
        )
        Text(
            text = stringResource(R.string.check_caption),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 6.dp),
        )

        TextButton(
            text = stringResource(R.string.check_refresh),
            onClick = refresh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        val r = report
        if (r == null) {
            Text(
                text = stringResource(R.string.check_loading),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            // 总结
            Text(
                text = if (r.canWork) {
                    stringResource(R.string.check_all_ok)
                } else {
                    stringResource(R.string.check_has_fail)
                },
                fontSize = 13.sp,
                color = if (r.canWork) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            r.items.forEach { item ->
                CheckRow(item)
            }
        }
    }

    // ==================== ③ 运行日志 ====================
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Text(
            text = stringResource(R.string.diag_title),
            fontSize = 14.sp,
            color = colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
        )
        Text(
            text = stringResource(R.string.diag_caption),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 6.dp),
        )

        TextButton(
            text = stringResource(R.string.diag_refresh),
            onClick = refresh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        TextButton(
            text = stringResource(R.string.diag_export),
            onClick = { exportReport(context) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (Log3DApp.hasRoot()) {
            TextButton(
                text = stringResource(R.string.diag_pull_root),
                onClick = {
                    val got = Log3DApp.pullLogsWithRoot()
                    Toast.makeText(
                        context,
                        if (got.isEmpty()) {
                            context.getString(R.string.diag_pull_fail)
                        } else {
                            context.getString(R.string.diag_pull_ok, got.size)
                        },
                        Toast.LENGTH_LONG,
                    ).show()
                    refresh()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Text(
            text = diag.ifEmpty { stringResource(R.string.diag_empty) },
            fontSize = 11.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    // ==================== ④ 使用步骤 ====================
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Text(
            text = stringResource(R.string.usage_title),
            fontSize = 14.sp,
            color = colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
        )
        Text(
            text = stringResource(R.string.usage_body),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    // ==================== ⑤ 关于 ====================
    Card(modifier = Modifier.padding(top = 12.dp, bottom = 24.dp).fillMaxWidth()) {
        Text(
            text = stringResource(R.string.about_title),
            fontSize = 14.sp,
            color = colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
        )
        Text(
            text = stringResource(R.string.about_body),
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        TextButton(
            text = stringResource(R.string.about_author),
            onClick = { openAuthor(context) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Text(
            text = stringResource(R.string.about_version_fmt, BuildConfig.VERSION_NAME),
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** 单条检测结果 */
@Composable
private fun CheckRow(item: EnvCheck.Item) {
    val icon = when (item.level) {
        EnvCheck.Level.OK -> "✅"
        EnvCheck.Level.WARN -> "⚠️"
        EnvCheck.Level.FAIL -> "❌"
        EnvCheck.Level.INFO -> "ℹ️"
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Text(
            text = "$icon ${item.title}",
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = item.detail,
            fontSize = 11.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
        item.hint?.let { h ->
            Text(
                text = "→ $h",
                fontSize = 11.sp,
                color = colorScheme.primary,
            )
        }
    }
}

// ==================== 工具 ====================

private const val AUTHOR_URL = "https://www.coolapk.com/u/28778575"
private const val COOLAPK_PKG = "com.coolapk.market"

private fun openAuthor(context: Context) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse(AUTHOR_URL)).setPackage(COOLAPK_PKG)
    if (runCatching { context.startActivity(app); true }.getOrDefault(false)) return

    val web = Intent(Intent.ACTION_VIEW, Uri.parse(AUTHOR_URL))
    runCatching { context.startActivity(web) }.onFailure {
        Toast.makeText(context, R.string.about_author_fail, Toast.LENGTH_SHORT).show()
    }
}

/** 生成诊断报告并拉起分享面板 */
private fun exportReport(context: Context) {
    Toast.makeText(context, R.string.diag_exporting, Toast.LENGTH_SHORT).show()
    Thread {
        val text = runCatching { Log3DApp.collectAll() }.getOrElse { "收集失败：$it" }

        // ★ 必须落在 FileProvider 已声明的目录里。
        //   之前写 getExternalFilesDir(null) 根目录，而 file_paths.xml 只声明了
        //   logs/ 与 pulled/ → getUriForFile 抛 IllegalArgumentException → 闪退。
        //   现在写到 external-files/logs/（file_paths.xml 里已有 ext_logs）。
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = java.io.File(baseDir, "logs").apply { mkdirs() }
        val f = java.io.File(dir, "Mian3D_diag_${System.currentTimeMillis()}.txt")

        val wrote = runCatching { f.writeText(text); f.exists() }.getOrDefault(false)
        if (!wrote) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, R.string.diag_export_fail, Toast.LENGTH_LONG).show()
            }
            return@Thread
        }

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // ★ getUriForFile 也可能抛，全部包住，绝不让它冒到 UI 线程
            val uri = runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    f,
                )
            }.getOrNull()

            if (uri == null) {
                Toast.makeText(context, R.string.diag_export_fail, Toast.LENGTH_LONG).show()
                return@post
            }

            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "米安3D壁纸诊断报告")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val ok = runCatching {
                context.startActivity(
                    Intent.createChooser(share, context.getString(R.string.diag_export_share)),
                )
                true
            }.getOrDefault(false)

            if (!ok) {
                Toast.makeText(context, R.string.diag_export_fail, Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}
