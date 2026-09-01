// 根构建脚本：仅声明插件版本（apply false，不在根项目应用）。
//
// AGP 9.x 路线（built-in Kotlin）：
//   - AGP 9 已内置 Kotlin 支持（embedded Kotlin Gradle plugin），
//     因此【不】声明/应用 org.jetbrains.kotlin.android，
//     应用它会与 built-in Kotlin 冲突。
//   - Compose 编译器插件 org.jetbrains.kotlin.plugin.compose 仍需声明，
//     版本与项目 Kotlin 版本（2.4.10）一致。
// 对应的 gradle.properties 中已设置 android.builtInKotlin=true 予以显式确认。
plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
