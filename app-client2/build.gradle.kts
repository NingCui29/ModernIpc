plugins {
    id("convention.android-application")
}

android {
    namespace = "com.cn.ipc.client2"

    defaultConfig {
        applicationId = "com.cn.ipc.client2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":demo-client-common"))
    implementation(project(":ipc-api"))
    implementation(project(":ipc-runtime-client"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
}
