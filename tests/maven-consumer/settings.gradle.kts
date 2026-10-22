pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "IsolatedModernIpc"
                    url = uri(providers.gradleProperty("ipcRepository").get())
                    if (providers.gradleProperty("metadataMode").get() == "pom") {
                        metadataSources {
                            mavenPom()
                            ignoreGradleMetadataRedirection()
                        }
                    }
                }
            }
            filter {
                includeGroup("com.modernipc")
                includeGroup("com.modernipc.consumer.fixture")
            }
        }
        google()
        mavenCentral()
    }
}

// This is a separate build: no main-repository project or composite dependency substitution.
rootProject.name = "ModernIpcPublishedConsumers"
include(":api-consumer", ":custom-contract", ":contract-consumer")
