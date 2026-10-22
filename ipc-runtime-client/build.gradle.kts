// 应用 Android 库约定插件，获得 compileSdk / minSdk / jvmTarget 等通用配置
plugins {
    id("convention.android-library")
}

android {
    namespace = "com.cn.ipc.runtime.client"
}

dependencies {
    // api 传递导出：使用方模块无需再重复声明 ipc-contract
    api(project(":ipc-contract"))
    implementation(libs.androidx.core.ktx)
    // CoroutineScope, Flow and coroutine handles are part of the public API.
    api(libs.kotlinx.coroutines.core)
}
