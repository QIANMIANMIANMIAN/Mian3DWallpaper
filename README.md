# 米安3D壁纸（Mian3DWallpaper）

从 [MianAssistant](https://github.com/QIANMIANMIANMIAN/MianAssistant) 拆出的独立模块，
只做一件事：**解锁 vivo 主题 App 里被隐藏的壁纸特效入口**。

包名 `com.mian.three`，可与米安助手共存安装。

---

## 功能

| 开关 | 作用 | 需要的素材 |
|---|---|---|
| **解锁 3D 空间壁纸** | 强制 `isSupport3DWallpaper` / `isSupportCloudMesh3D` / `isFtFeatureSupport("vivo.software.spatialscene")` 通过 | 主题 App 自带 `libThemeProcess.so` |
| **解锁 AI 宠物** | 绕过内存（>6GB）、机型白名单、AOD 元数据三道判断 | 云端生成（需 vivo 账号） |
| **强制景深效果** | 反转 `isDisableDecorateDeepBlur`，让装饰/景深开关可用 | 壁纸模板需带深度数据 |

---

## 使用

1. 安装后，在 **LSPosed** 里启用本模块
2. 作用域勾选 **「主题」**（`com.bbk.theme`）
3. 打开 App，按需开启上面的开关
4. **强行停止主题 App**，再重新打开

---

## 环境检测

App 内置 6 类环境检测，每项给出 ✅ / ⚠️ / ❌ + 具体数值 + 修复建议：

1. **机型** —— 平板（`isPad()`）/ 折叠屏（`isFlip()`）会被主题 App 硬性拒绝
2. **主题 App** —— 是否安装、版本是否 ≥ 23.8
3. **渲染引擎** —— `libThemeProcess.so` 是否存在（ROM 可能裁掉）
4. **动态壁纸盒子** —— manifest 的 `support_cloud_render` / `support_cloud_render_fold` 是否为 1
5. **运动传感器** —— 陀螺仪 + 加速度计（3D 视差依赖它们）
6. **LSPosed / 作用域 / 注入** —— 分级定位

哪一项 ❌，按提示处理即可。

---

## 诊断

「运行日志」卡片直接显示 hook 在主题进程里写的日志，**不需要 adb、不需要 root**。

也可以点「导出诊断报告」，汇总五路日志：
1. remote prefs 的 `diag_log`
2. `Documents/Mian3D/*.log`
3. `/data/local/tmp/Mian3D/*.log`
4. `logcat -d -s Mian3D`（需 root）
5. App 自身日志

检测到 `su` 时会多出「用 root 拉取日志」按钮，用 root 绕过 scoped storage 复制日志。

---

## 重要说明

**本模块只解锁「入口显示」，不提供任何渲染实现，也不提供素材。**

- 3D 壁纸：渲染引擎在主题 App 里（`libThemeProcess.so`），本模块只是让判断通过
- AI 宠物：宠物资源需云端生成
- 景深：需要带深度数据的壁纸模板

**如果主题 App 的 `files/` 下没有 `themes_*_extracted/` 目录，说明还没下载过动态/特效主题——
入口会显示，但点进去是空的。这是素材问题，不是模块问题。**

---

## 关于作者

酷安：<https://www.coolapk.com/u/28778575>

---

## 构建

推送到 `main` 分支，GitHub Actions 自动构建并发布到 `nightly` Release。

本地构建（需自备 keystore）：

```sh
./gradlew assembleRelease \
  -PKEYSTORE_FILE=/path/to/release.keystore \
  -PKEYSTORE_PASSWORD=xxx \
  -PKEY_ALIAS=xxx \
  -PKEY_PASSWORD=xxx
```
