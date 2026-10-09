import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<ApplicationExtension> {
    compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
        targetSdk = libs.findVersion("targetSdk").get().requiredVersion.toInt()
        versionName = providers.gradleProperty("VERSION_NAME").get()
        versionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
    }

    compileOptions {
        val target = JavaVersion.toVersion(libs.findVersion("jvmTarget").get().requiredVersion)
        sourceCompatibility = target
        targetCompatibility = target
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = libs.findVersion("jvmTarget").get().requiredVersion
    }
}
