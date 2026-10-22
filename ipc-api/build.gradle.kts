plugins {
    id("convention.android-library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.cn.ipc.api"
}

dependencies {
    // Generated Adapters, Stubs and schemas expose these types in their public ABI.
    api(project(":ipc-contract"))
    api(project(":ipc-annotations"))
    api(project(":ipc-runtime-client"))
    api(project(":ipc-runtime-server"))
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.core)
    
    ksp(project(":ipc-compiler"))
}
