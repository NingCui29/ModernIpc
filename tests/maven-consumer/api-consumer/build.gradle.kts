plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    // Deliberately the only direct dependency. No runtime, contract or coroutine workaround.
    implementation("com.modernipc:ipc-api:${providers.gradleProperty("ipcVersion").get()}")
}
