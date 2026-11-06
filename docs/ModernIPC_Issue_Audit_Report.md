# ModernIPC 项目问题审查与排查报告

> 报告生成时间：2026-11-03\
> 审查范围：项目构建环境、Git 工作区变更（`release/v3.0.0-rc.2`）、编译器生成逻辑及运行时核心状态机并发设计。

---

## 状态汇总表（最新复核结果）

| 问题项 | 严重级别 | 复核状态 | 修复与验证情况 |
| :--- | :---: | :---: | :--- |
| **JDK 24 导致单元测试崩溃** | 严重 | **已修复** | `settings.gradle.kts` 已增加 JDK 17 版本校验阻断；在 JDK 17 下 `:check` 全部通过。 |
| **`awaitConnected` 缺失快速失败** | 中等 | **已修复** | 引入 `connectionAttempt`，在 `Disconnected`/关闭时立即抛出异常，消除 5 秒超时挂起。 |
| **`IpcSubscriptionManager` 持锁 unlink** | 中等 | **已修复** | 重构 `register()`，旧实例及异常解绑均移至 `finally` 锁外执行。 |
| **`broadcast` 异常隔离机制** | 轻微 | **已确认** | 规范了设计约定，注释明确了除 `RemoteException` 外不隐式吞没业务逻辑错误。 |
| **2026-10-30 未发布补丁** | 改善 | **已验证** | 8 组 Robolectric 测试通过，小米真机回归记录归档至 `2026-11-03-xiaomi14ultra`。 |

---

## 一、 构建与测试环境问题

### 1.1 JDK 24 导致的 Robolectric 单元测试崩溃

- **问题现象**：\
  在宿主直接执行 `./gradlew test` 或 `./gradlew check` 时，`:demo-app:testDebugUnitTest` 发生 **8 项用例全部失败**（`Unsupported class file major version 68`）。
- **原因剖析**：\
  宿主操作系统环境默认 `JAVA_HOME` 指向了 **JDK 24**。Robolectric 4.13 的 ASM 插桩引擎不支持 Java 24 字节码。
- **修复与复核结果**：
  1. [`settings.gradle.kts`](file:///d:/Developer/WorkSpace/ModernIpc/settings.gradle.kts#L36) 已添加强校验：
     ```kotlin
     check(JavaVersion.current() == JavaVersion.VERSION_17) {
         "ModernIpc requires JDK 17. Set JAVA_HOME or Android Studio Gradle JDK to Java 17."
     }
     ```
     在非 JDK 17 环境下会第一时间阻断并给出明确提示，不再产生晦涩的字节码报错。
  2. 使用 JDK 17 验证 `:check`，所有单元测试与 27 项 Host 校验 **全部 100% 通过**（耗时 3 秒）。

---

## 二、 核心运行时代码缺陷与并发隐患

### 2.1 `IpcConnectionController.awaitConnected` 缺少对 `Disconnected` 的快速失败机制

- **涉及源码**：[`ipc-runtime-client/.../IpcConnectionController.kt`](file:///d:/Developer/WorkSpace/ModernIpc/ipc-runtime-client/src/main/kotlin/com/cn/ipc/client/IpcConnectionController.kt#L263-L283)
- **修复情况**：
  - 新增 `connectionAttempt: CompletableDeferred<IpcClientState.Connected>?`。
  - 在 `handleBindFailureLocked` 切入终态 `Disconnected` 时，主动调用 `connectionAttempt?.completeExceptionally(cause)`。
  - 主动 `close()` 或 `dispose()` 时同样完成异常。
  - `awaitConnected()` 绑定至 `attempt.await()`，彻底解决了原有等待 5 秒超时假死的问题。
  - [`IpcHandshakeProbe.kt`](file:///d:/Developer/WorkSpace/ModernIpc/demo-app/src/main/kotlin/com/cn/ipc/demo/IpcHandshakeProbe.kt#L55-L131) 中已增加 `awaitAttemptVerified=true` 探针校验并全部跑通。

---

### 2.2 `IpcSubscriptionManager.register` 在持锁状态下调用 Binder `unlinkToDeath`

- **涉及源码**：[`ipc-runtime-server/.../IpcSubscriptionManager.kt`](file:///d:/Developer/WorkSpace/ModernIpc/ipc-runtime-server/src/main/kotlin/com/cn/ipc/server/IpcSubscriptionManager.kt#L35-L54)
- **修复情况**：
  重构了 `register` 方法，引入局部变量与 `finally` 结构：
  ```kotlin
  fun register(observer: T): Long {
      var cleanup: Entry? = null
      try {
          return synchronized(lock) {
              check(!killed) { "IPC subscription manager is closed" }
              val binder = observer.asBinder()
              val entry = Entry(nextId++, observer, binder)
              cleanup = entry
              binder.linkToDeath(entry.recipient, 0)
              if (entry.dead || !binder.isBinderAlive) throw RemoteException("IPC observer is dead")
              val previous = binders.put(binder, entry)
              entries[entry.id] = entry
              if (previous != null) entries.remove(previous.id)
              cleanup = previous
              entry.id
          }
      } finally {
          cleanup?.unlink() // 锁外解绑旧实例或失败实例
      }
  }
  ```
  旧实例或注册失败实例的 `unlinkToDeath` 完全移出 `synchronized(lock)`，消除了持锁跨进程调用的死锁/锁争用风险。

---

### 2.3 `IpcSubscriptionManager.broadcast` 异常隔离机制

- **涉及源码**：[`ipc-runtime-server/.../IpcSubscriptionManager.kt`](file:///d:/Developer/WorkSpace/ModernIpc/ipc-runtime-server/src/main/kotlin/com/cn/ipc/server/IpcSubscriptionManager.kt#L65-L81)
- **结论**：
  经代码注释澄清：设计上故意仅吞没传输层 `RemoteException`，其他业务运行时异常向外抛出以暴露编程错误，避免静默掩盖 bug。调用方如需逐个隔离应在回调 action 内部捕获。

---

## 三、 验证记录

- **Host 门禁**：通过命令 `JAVA_HOME="<jdk17>" ./gradlew :check` 验证，117 项任务成功，耗时 3 秒。
- **真机回归**：小米 14 Ultra 真机验收数据已记录至 `docs/benchmarks/runs/2026-11-03-xiaomi14ultra/`，生命周期、快速失败与连接恢复指标均符合预期。
