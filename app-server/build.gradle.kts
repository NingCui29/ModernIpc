plugins {
    id("convention.android-application")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.cn.ipc.server.app"

    defaultConfig {
        applicationId = "com.cn.ipc.server.app"
        versionCode = 201
        versionName = "2.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":ipc-api"))
    implementation(project(":ipc-runtime-server"))
    ksp(project(":ipc-compiler"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}
