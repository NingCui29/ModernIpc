"""Compile the real pending-call registry against host doubles and cached Gradle dependencies.

No Gradle mutation, dependency download, ADB, Android Binder or timing claims.
Requires Java and Kotlin/coroutines dependencies already present in Gradle cache.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import tomllib


def cached_jar(cache: Path, group: str, artifact: str, version: str) -> Path:
    candidates = list((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
    if len(candidates) != 1:
        raise SystemExit(f"Expected one cached {artifact}:{version}; found {len(candidates)}. Resolve project dependencies first.")
    return candidates[0]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Alternative registry source for baseline regression checks")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    root = next(path for path in here.parents if (path / "settings.gradle.kts").is_file())
    source = args.source or root / "ipc-runtime-client/src/main/kotlin/com/cn/ipc/client/PendingCallRegistry.kt"
    versions = tomllib.loads((root / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]
    kotlin = versions["kotlin"]
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    compiler = cached_jar(cache, "org.jetbrains.kotlin", "kotlin-compiler-embeddable", kotlin)
    stdlib = cached_jar(cache, "org.jetbrains.kotlin", "kotlin-stdlib", kotlin)
    script = cached_jar(cache, "org.jetbrains.kotlin", "kotlin-script-runtime", kotlin)
    reflect = cached_jar(cache, "org.jetbrains.kotlin", "kotlin-reflect", kotlin)
    trove = cached_jar(cache, "org.jetbrains.intellij.deps", "trove4j", "1.0.20200330")
    annotations = cached_jar(cache, "org.jetbrains", "annotations", "13.0")
    coroutines = cached_jar(cache, "org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", versions["coroutines"])
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else shutil.which("java")
    if not java:
        raise SystemExit("Java executable not found; set JAVA_HOME")
    compiler_cp = os.pathsep.join(map(str, [compiler, stdlib, script, reflect, trove, annotations]))
    runtime_cp = os.pathsep.join(map(str, [stdlib, coroutines, annotations]))
    with tempfile.TemporaryDirectory(prefix="modernipc-pending-host-") as temporary:
        jar = Path(temporary) / "pending-host.jar"
        subprocess.run([
            java, "-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "1.8", "-classpath", runtime_cp,
            "-d", str(jar), str(source.resolve()), *(str(path) for path in sorted(here.glob("*.kt"))),
        ], check=True)
        command = [java, "-cp", str(jar) + os.pathsep + runtime_cp, "com.cn.ipc.client.host.PendingCallRegistryHostTestKt"]
        subprocess.run(command, check=True)


if __name__ == "__main__":
    main()
