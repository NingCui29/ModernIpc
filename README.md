# ModernIPC public Maven repository

This branch contains immutable Maven artifacts copied from verified GitHub Releases.
Consumers do not need a GitHub account or token.

```kotlin
maven("https://raw.githubusercontent.com/NingCui29/ModernIpc/maven") {
    content { includeGroup("com.modernipc") }
}
```

Use `com.modernipc:<module>:<version>`. All six SDK modules and `demo-client-common` are published together.
The `receipts/` directory records the original Release archive and every Maven file hash.
Source and usage: https://github.com/NingCui29/ModernIpc
