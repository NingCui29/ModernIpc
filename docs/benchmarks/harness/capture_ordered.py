"""Freeze a successful ordered probe's Xiaomi-only source/APK/log evidence."""
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from datetime import date

ROOT = Path(__file__).resolve().parents[3]
SERIAL = "925c23bb"
CAPTURE_DAY = date.fromisoformat(os.environ.get("IPC_BENCHMARK_DATE", "2026-10-08")).isoformat()


def adb(*args: str) -> str:
    return subprocess.check_output(["adb", "-s", SERIAL, *args], encoding="utf-8", errors="replace")


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def main(stage: str, run: str, tag: str, passes: int) -> None:
    directory = ROOT / f"docs/benchmarks/runs/{CAPTURE_DAY}-xiaomi14ultra" / f"ordered-{stage}"
    directory.mkdir(parents=True, exist_ok=True)
    log = adb("logcat", "-d", "-v", "threadtime", "-s", f"{tag}:I", "IpcForeground:I", "IpcEchoBench:E", "AndroidRuntime:E")
    rows = [line for line in log.splitlines() if f"run={run} " in line]
    assert sum("PASS run=" in line for line in rows) == passes, rows
    assert sum("DONE run=" in line and "passed=true" in line for line in rows) == 1, rows
    assert "FAILED" not in log and "FATAL EXCEPTION" not in log, log
    (directory / "logcat.txt").write_text(log, encoding="utf-8")
    for name, arguments in (
        ("processes", ("shell", "ps", "-A")),
        ("battery", ("shell", "dumpsys", "battery")),
        ("thermal", ("shell", "dumpsys", "thermalservice")),
        ("power", ("shell", "dumpsys", "power")),
        ("activities", ("shell", "dumpsys", "activity", "activities")),
    ):
        (directory / f"{name}.txt").write_text(adb(*arguments), encoding="utf-8")
    apk = ROOT / "demo-app/build/outputs/apk/debug/demo-app-debug.apk"
    snapshot = ROOT / f".gradle/ordered-{stage}.apk"
    shutil.copyfile(apk, snapshot)
    installed = ROOT / f".gradle/ordered-{stage}-installed.apk"
    remote = adb("shell", "pm", "path", "com.cn.ipc.demo").strip().removeprefix("package:")
    adb("pull", remote, str(installed))
    assert sha(installed) == sha(apk), "Installed APK differs from built APK"
    sources = sorted(p for p in ROOT.glob("*/src/main/**/*") if p.is_file() and p.suffix in (".kt", ".aidl", ".xml"))
    evidence = dict(stage=stage, run=run, device=SERIAL, expectedPass=passes,
                    apkSha256=sha(apk), installedSha256=sha(installed),
                    sourceHashes=[dict(path=p.relative_to(ROOT).as_posix(), sha256=sha(p)) for p in sources])
    (directory / "evidence.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in evidence.items() if key != "sourceHashes"}))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]))
