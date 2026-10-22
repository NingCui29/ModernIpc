import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// This module is a verification harness, never an Android library or publication.
// Run Gradle itself with JDK 17 as well (Gradle 8.5 does not support running on JDK 24).
kotlin {
    jvmToolchain(17)
}

val clientHost by sourceSets.creating
val serverHost by sourceSets.creating

// Separate compilations keep each existing android.os.Parcel double isolated.
// Compile the real implementation, not a copied model of its state machine.
kotlin.sourceSets.named(clientHost.name) {
    kotlin.setSrcDirs(listOf(
        rootProject.file("ipc-runtime-client/src/main/kotlin"),
        rootProject.file("ipc-runtime-client/src/test/host"),
    ))
    kotlin.include("com/cn/ipc/client/PendingCallRegistry.kt", "*.kt")
}
kotlin.sourceSets.named(serverHost.name) {
    kotlin.setSrcDirs(listOf(
        rootProject.file("ipc-runtime-server/src/main/kotlin"),
        rootProject.file("ipc-runtime-server/src/test/host"),
    ))
    kotlin.include("com/cn/ipc/server/IpcRequestTracker.kt", "*.kt")
}

dependencies {
    add(clientHost.implementationConfigurationName, libs.kotlinx.coroutines.core)
    add(serverHost.implementationConfigurationName, libs.kotlinx.coroutines.core)
}

val hostJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}

val pendingCallRegistryHostTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run 12 pending-call registry host cases against the production implementation."
    dependsOn(clientHost.classesTaskName)
    classpath = clientHost.runtimeClasspath
    mainClass.set("com.cn.ipc.client.host.PendingCallRegistryHostTestKt")
    javaLauncher.set(hostJavaLauncher)
}

val requestTrackerHostTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Run all 15 request tracker host cases, including stale-death regression."
    dependsOn(serverHost.classesTaskName)
    classpath = serverHost.runtimeClasspath
    mainClass.set("com.cn.ipc.server.host.RequestTrackerHostTestKt")
    javaLauncher.set(hostJavaLauncher)
}

tasks.named("check") {
    dependsOn(pendingCallRegistryHostTest, requestTrackerHostTest)
}
