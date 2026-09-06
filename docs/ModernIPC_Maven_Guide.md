# Modern IPC Maven 依赖与打包发布指南

Modern IPC 项目内部已在 `build-logic` 约定插件（`convention.publish.gradle.kts`）中配置了标准化的 `maven-publish` 逻辑。所有的 Library 模块都自动继承了发布与打包能力。

## 一、 Maven 坐标 (GAV)

该 SDK 发布的全局标识如下：
- **GroupId**: `com.modernipc`
- **Version**: `2.0.1`
- **ArtifactId**: 与各个子模块的名称保持一致（如 `ipc-runtime-client`、`ipc-annotations` 等）。

## 二、 SDK 打包与提取方式 (AAR / JAR)

如果您不需要使用 Maven 仓库，只是想手动提取编译好的 AAR/JAR 文件包提供给外部或第三方使用，请按照以下步骤操作：

1. **执行构建命令**：
   在项目根目录运行以下命令，构建所有模块的产物：
   ```bash
   # 编译所有的 Android 库模块生成 AAR
   ./gradlew assembleRelease
   
   # 编译所有的纯 Kotlin/JVM 模块生成 JAR
   ./gradlew jar
   ```

2. **获取产物位置**：
   - **Android 库**（如 `ipc-runtime-client`, `ipc-runtime-server`, `ipc-contract`）：
     产物位于 `[模块名]/build/outputs/aar/` 目录下（例如：`ipc-runtime-client-release.aar`）。
   - **纯 JVM 库**（如 `ipc-annotations`, `ipc-compiler`）：
     产物位于 `[模块名]/build/libs/` 目录下（例如：`ipc-annotations.jar`）。

> **💡 提示**：项目约定插件中已包含 `withSourcesJar()`，因此还会自动生成包含源码的 `*-sources.jar` 文件，十分方便接入方查阅源码。

## 三、 接入 GitHub Packages 远程 Maven 仓库 (推荐)

Modern IPC 的全量 Release 构件（`2.0.1`）均托管在 GitHub Packages 远程 Maven 仓库，外部工程无需下载源码或 AAR，直接通过 Gradle 远程拉取即可。

### 1. 配置安全鉴权凭据
由于 GitHub Packages 的安全限制，**即使拉取公开开源库，也必须提供 GitHub 凭证**。为了防止凭据被提交到 Git 仓库泄露，请在电脑本机全局配置文件 `~/.gradle/gradle.properties`（Windows 为 `C:\Users\<用户名>\.gradle\gradle.properties`）中配置：

```properties
# 你的真实 GitHub 用户名
gpr.user=YourGithubUsername
# 你的 GitHub Personal Access Token (PAT，需勾选 read:packages 权限)
gpr.key=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

> **🔑 如何获取 GitHub PAT**：
> 前往 GitHub -> **Settings** -> **Developer settings** -> **Personal access tokens (classic)** -> **Generate new token (classic)**，填写 Note 并勾选 **`read:packages`** 权限生成即可。

### 2. 宿主工程配置远程源 (`settings.gradle.kts`)

在宿主工程的根目录 `settings.gradle.kts` 中添加远程仓库：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        
        // Modern IPC GitHub Packages 远程源
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Cuinings/ModernIpc")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR") ?: ""
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
```

*若为 Groovy DSL（`settings.gradle`），将 `credentials` 部分改为：*
```groovy
credentials {
    username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("GITHUB_ACTOR") ?: "")
    password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("GITHUB_TOKEN") ?: "")
}
```

### 3. 宿主模块引入依赖 (`build.gradle.kts`)

在具体应用模块的 `build.gradle.kts` 中配置：

```kotlin
plugins {
    // 启用 KSP 符号处理器（插件版本与 Kotlin 版本对应）
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

dependencies {
    // 1. 契约定义与 KSP 编译器（Client 与 Server 端项目均必须配置）
    implementation("com.modernipc:ipc-annotations:2.0.1")
    ksp("com.modernipc:ipc-compiler:2.0.1")

    // 2. Client 端进程依赖（在只作为客户端的 UI 层项目引入）
    implementation("com.modernipc:ipc-runtime-client:2.0.1")

    // 3. Server 端进程依赖（在作为服务端的独立进程项目引入）
    implementation("com.modernipc:ipc-runtime-server:2.0.1")
}
```

---

## 四、 远程打包发布到 GitHub Packages

如果你需要向本官方仓库（需协作者权限）或自己的 Fork 仓库发布自定义产物：

### 1. 自动化 CI/CD 流水线发布
向 GitHub 远程仓库推送版本 Tag（如 `v2.0.1`），GitHub Actions 将会自动打包、发布 Maven 包并创建 GitHub Release。
```bash
git tag v2.0.1
git push origin v2.0.1
```

### 2. 本地手动发布到 GitHub 远程仓库
确保全局 `~/.gradle/gradle.properties` 中已配置 `gpr.user`、`gpr.key`（需带有 `write:packages` 权限）与目标仓库 `gpr.repo`：
```properties
gpr.user=YourGithubUsername
gpr.key=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
gpr.repo=Cuinings/ModernIpc
```
在工程根目录运行：
```bash
# 仅发布到 GitHub Packages 远程仓库
./gradlew publishAllPublicationsToGitHubPackagesRepository

# 或同时发布到 local-maven 与 GitHub Packages
./gradlew publish
```

---

## 五、 本地工程开发发布 (local-maven)

如果仅用于本机离线开发或快速联调测试，项目支持发布到工程根目录下的 `local-maven/` 文件夹：

```bash
# 发布到项目根目录的 local-maven 仓库
./gradlew publish
```

**外部项目引入 local-maven**：
在外部项目的 `settings.gradle.kts` 中添加路径：
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("D:/Developer/WorkSpace/ModernIpc/local-maven") } 
    }
}
```
