pluginManagement {
    // 将 build-logic 作为本地插件构建，使约定插件对所有子模块可见
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ModernIpc"
include(":ipc-contract")
include(":ipc-api")
include(":ipc-runtime-client")
include(":ipc-runtime-server")
include(":ipc-annotations")
include(":ipc-compiler")

include(":demo-app")
include(":demo-client-common")
include(":app-server")
include(":app-client1")
include(":app-client2")
include(":app-client3")
