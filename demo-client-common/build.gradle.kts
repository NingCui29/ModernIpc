plugins {
    id("convention.android-library")
}

android {
    namespace = "com.cn.ipc.client.common"
}

dependencies {
    implementation(project(":ipc-api"))
    implementation(project(":ipc-runtime-client"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
}
