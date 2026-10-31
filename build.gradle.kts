@file:Suppress("UnstableApiUsage")

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.lsplugin.apksign)
}

// ==================== 全局配置 ====================
// ★ targetSdk 必须与 douyin-liquid-glass-lite 一致（35），不能是 37。
//   原因：Android 的 seapp_contexts 按 targetSdk 给 App 分配不同的 SELinux 域，
//   越高越严；KernelSU/Magisk 的 su 策略只覆盖到它编译时对齐的 SDK 级别。
//   targetSdk=37 会落到未覆盖的新域 -> 执行 su 报 EACCES(13)。
extra["androidMinSdkVersion"] = 28
extra["androidTargetSdkVersion"] = 35
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "37.0.0"
extra["androidSourceCompatibility"] = JavaVersion.VERSION_17
extra["androidTargetCompatibility"] = JavaVersion.VERSION_17

// 版本号统一在 version.properties 维护
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { input ->
        load(input)
    }
}
extra["threeVersionCode"] = versionProps.getProperty("versionCode", "1").toInt()
extra["threeVersionName"] = versionProps.getProperty("versionName", "1.0.0")

val androidCompileSdkVersion = rootProject.extra["androidCompileSdkVersion"] as Int
val androidCompileSdkVersionMinor = rootProject.extra["androidCompileSdkVersionMinor"] as Int
val androidBuildToolsVersion = rootProject.extra["androidBuildToolsVersion"] as String
val androidMinSdkVersion = rootProject.extra["androidMinSdkVersion"] as Int
val androidTargetSdkVersion = rootProject.extra["androidTargetSdkVersion"] as Int
val androidSourceCompatibility = rootProject.extra["androidSourceCompatibility"] as JavaVersion
val androidTargetCompatibility = rootProject.extra["androidTargetCompatibility"] as JavaVersion
val threeVersionCode = rootProject.extra["threeVersionCode"] as Int
val threeVersionName = rootProject.extra["threeVersionName"] as String

// CI 可用 -PKSU_PACKAGE_NAME / -PKSU_NAME 覆盖
val threePackageName = project.findProperty("KSU_PACKAGE_NAME")?.toString() ?: "com.mian.three"

apksign {
    storeFileProperty = "KEYSTORE_FILE"
    storePasswordProperty = "KEYSTORE_PASSWORD"
    keyAliasProperty = "KEY_ALIAS"
    keyPasswordProperty = "KEY_PASSWORD"
}

android {
    namespace = "com.mian.three"

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        // ★ resValue("string", "app_name", ...) 依赖这个开关
        resValues = true
    }

    packaging {
        dex {
            useLegacyPackaging = true
        }
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileSdk {
        version =
            release(androidCompileSdkVersion) {
                minorApiLevel = androidCompileSdkVersionMinor
            }
    }
    buildToolsVersion = androidBuildToolsVersion

    defaultConfig {
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = threeVersionCode
        versionName = threeVersionName
        applicationId = threePackageName

        buildConfigField(
            "String",
            "BUILD_TIME",
            "\"${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}\"",
        )
        resValue("string", "app_name", "米安3D壁纸")
    }

    compileOptions {
        sourceCompatibility = androidSourceCompatibility
        targetCompatibility = androidTargetCompatibility
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    // ★★★ 必须 compileOnly，不能 implementation ★★★
    //   Xposed API 由 LSPosed 框架在运行时提供。
    //   打成 implementation 会把 XposedModule 等类复制进 APK，
    //   运行时出现「框架提供的类」与「APK 内的同名类」两份，
    //   类型校验失败 → 模块加载被跳过 → 表现是装上完全没反应、一条日志都没有。
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
}

// ★ META-INF/xposed/* 必须保留在 APK 里，否则 LSPosed 认不出模块。
androidComponents {
    onVariants(selector().withBuildType("release")) {
        it.packaging.resources.excludes.addAll(
            listOf("META-INF/*.kotlin_module", "kotlin/**", "**.bin"),
        )
    }
}

// ★ 与主模块一致：Compose 的 Experimental API 需要显式 opt-in
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}
