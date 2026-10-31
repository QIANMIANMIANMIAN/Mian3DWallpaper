# ============================================================================
# LSPosed / libxposed 模块混淆规则
#
# ★ 踩过的坑（米安助手源码里也有同样注释）：
#   java_init.list 里写的是【字面类名】com.mian.three.xposed.ThreeHookEntry，
#   LSPosed 运行期按这个名字反射加载。
#   一旦入口类被 R8 改名 → 加载失败 → 表现是「模块装上完全没反应」，
#   而且编译期一点错都不报，极难排查。
#
#   同理，XposedModule / XposedInterface 这些接口的方法名若是被混淆，
#   hook 框架回调（onPackageLoaded / onPackageReady / onHotReloaded）
#   就无法被正确调用。
# ============================================================================

# ---- 入口类：保住类名与构造函数 ----
-keep class com.mian.three.xposed.ThreeHookEntry { <init>(); }

# ---- 框架回调必须保名，否则 onPackageLoaded 等永远不会被调用 ----
-keepclassmembers class com.mian.three.xposed.ThreeHookEntry {
    public *;
    protected *;
}

# ---- libxposed API 整体保名（与主模块一致）----
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

# ---- 被 hook 的目标通过反射查找，保留注解与签名信息 ----
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ---- 保留 lambda / 内部类的名称（hook 回调里用到了）----
-keepclassmembers class com.mian.three.xposed.** {
    <methods>;
}

-dontwarn org.jetbrains.annotations.**
