import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}

subprojects {
    layout.buildDirectory.set(file("${providers.gradleProperty("consumerBuildRoot").get()}/$name"))
    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            namespace = "com.modernipc.consumer.${project.name.replace('-', '.')}"
            compileSdk = 34
            defaultConfig { minSdk = 21 }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_1_8
                targetCompatibility = JavaVersion.VERSION_1_8
            }
        }
        tasks.register("verifyPublishedCompileGraph") {
            val compile = configurations.named("releaseCompileClasspath")
            val direct = configurations.named("implementation")
            val output = layout.buildDirectory.file("consumer-compile-graph.txt")
            outputs.file(output)
            doLast {
                val directEntries = direct.get().dependencies.map { "${it.group}:${it.name}:${it.version}" }
                val resolved = compile.get().resolvedConfiguration.resolvedArtifacts
                    .map { it.moduleVersion.id.toString() }.sorted()
                output.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText((directEntries.map { "direct=$it" } + resolved.map { "resolved=$it" })
                        .joinToString("\n", postfix = "\n"))
                }
            }
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "1.8"
    }
}
