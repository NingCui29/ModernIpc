# Modern IPC Maven 依赖与打包发布指南

Modern IPC 项目内部已在 `build-logic` 约定插件（`convention.publish.gradle.kts`）中配置了标准化的 `maven-publish` 逻辑。所有的 Library 模块都自动继承了发布与打包能力。

## 一、 Maven 坐标 (GAV)

该 SDK 发布的全局标识如下：
- **GroupId**: `com.modernipc`
- **Version**: `2.0.0`
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

## 三、 自动打包发布到本地工程仓库

为了不污染系统全局的 maven 仓库，项目已配置将产物发布到**当前工程根目录的 `local-maven/` 文件夹**下。要一键发布所有模块，请执行：

```bash
# 发布到项目根目录的 local-maven 仓库
./gradlew publish
```

**关于打包与发布顺序**：
得益于 Gradle 优秀的 Task 依赖图机制，您**完全不需要手动控制顺序**。
当执行上述命令时，Gradle 会自动解析依赖拓扑（通常是最底层的 `ipc-annotations` -> 接着是 `ipc-compiler` 与 `ipc-contract` -> 最后是依赖它们的 `ipc-runtime-*` 模块），以绝对正确的顺序并行/串行完成构建和发布。您只需一键执行即可。

## 四、 外部项目依赖方式

当产物发布到本工程的 `local-maven` 仓库后，其他项目如果想要引入，需要在该项目的 `settings.gradle.kts` 中添加此本地路径（或将生成的 `local-maven` 文件夹拷贝给外部使用）：

```kotlin
// 外部项目的 settings.gradle.kts 示例
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // 增加指向刚刚生成的本地仓库的路径
        maven { url = uri("D:/Developer/WorkSpace/ModernIpc/local-maven") } 
    }
}
```

然后在 `build.gradle.kts` 中引入依赖：

```kotlin
plugins {
    // 需配置 KSP 插件，版本号需与当前项目使用的 Kotlin 版本匹配
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

dependencies {
    // 1. 契约定义与 KSP 编译器（Client 与 Server 端项目均必须配置）
    implementation("com.modernipc:ipc-annotations:2.0.0")
    ksp("com.modernipc:ipc-compiler:2.0.0")

    // 2. Client 端进程依赖（在只作为客户端的 UI 层项目引入）
    implementation("com.modernipc:ipc-runtime-client:2.0.0")

    // 3. Server 端进程依赖（在作为服务端的独立进程项目引入）
    implementation("com.modernipc:ipc-runtime-server:2.0.0")
    
    // 提示：不要忘记依赖你自己定义的业务接口契约模块
}
```
