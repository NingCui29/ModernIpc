// 顶层构建文件：仅声明插件（版本统一由 gradle/libs.versions.toml 管理），不直接应用到任何模块。
// 各模块通过应用约定插件（convention.*）获得具体配置。
plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library)  apply false
    alias(libs.plugins.kotlin.android)   apply false
    alias(libs.plugins.kotlin.jvm)       apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.ksp)              apply false
}

// Host state-machine checks use the production sources with host-only doubles.
// These checks make no assertion about Android Binder or device performance.
tasks.named("check") {
    dependsOn(":ipc-host-tests:check")
}
