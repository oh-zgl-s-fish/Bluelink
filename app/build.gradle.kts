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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
