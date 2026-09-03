import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 built-in Kotlin：不应用 org.jetbrains.kotlin.android；
    // Compose 编译器插件（版本与 Kotlin 2.4.10 一致）。
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zglinus.bluelink"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zglinus.bluelink"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.5.10"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // v0.5.10：开启 buildConfig 供 About 页引用 BuildConfig.VERSION_NAME（AGP9 默认关闭）
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    // v0.5.13 md3-audit-2 K4/T2/N1：Material Icons——字形当图标（☰/×/›）换矢量 Icons + 抽屉 4 项图标
    // （Menu/Close/KeyboardArrowRight/History/Palette/Settings/Info）。无版本号 → 由上方 composeBom
    // platform 约束（BOM 2026.08.00 映射 material-icons-extended 1.7.8，同 ui/material3 走 BOM 风格，
    // 不写入 libs.versions.toml——避免与并行组冲突）。
    implementation("androidx.compose.material:material-icons-extended")
    // v0.5.14 合并编译修复：Menu 基础图标（☰）在 material-icons-core（extended 1.7.8 只含 MenuBook/
    // MenuOpen，无 Menu）；同走上方 composeBom platform 约束（BOM 2026.08.00 → core 1.7.8），无版本号。
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
