// 应用 Android 库约定插件，获得 compileSdk / minSdk / jvmTarget 等通用配置
plugins {
    id("convention.android-library")
}

android {
    namespace = "com.cn.ipc.runtime.server"
}

dependencies {
    // api 传递导出：使用方模块无需再重复声明 ipc-contract
    api(project(":ipc-contract"))
    implementation(libs.androidx.core.ktx)
    // Service scopes and request jobs are part of the public API.
    api(libs.kotlinx.coroutines.core)
}
