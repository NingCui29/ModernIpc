import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    `maven-publish`
}

val fixtureVersion = providers.gradleProperty("ipcVersion").get()
dependencies {
    api("com.modernipc:ipc-api:$fixtureVersion")
    ksp("com.modernipc:ipc-compiler:$fixtureVersion")
}

android.publishing { singleVariant("release") }
publishing.repositories.maven {
    name = "Fixture"
    url = uri(providers.gradleProperty("ipcRepository").get())
}
afterEvaluate {
    publishing.publications.create<MavenPublication>("release") {
        from(components["release"])
        groupId = "com.modernipc.consumer.fixture"
        artifactId = "custom-contract"
        version = fixtureVersion
    }
}
