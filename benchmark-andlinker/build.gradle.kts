plugins {
    id("convention.android-application")
}

android {
    namespace = "com.cn.ipc.benchmark.andlinker"
    defaultConfig {
        applicationId = "com.cn.ipc.benchmark.andlinker"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}
