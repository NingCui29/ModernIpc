plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    // A Maven coordinate, not project(":custom-contract"). No direct ipc-api dependency.
    implementation("com.modernipc.consumer.fixture:custom-contract:${providers.gradleProperty("ipcVersion").get()}")
}
