"""Run and freeze one Xiaomi-only guard A/B, including foreground qualification."""
import subprocess
import sys
import time

from capture_ordered import ROOT, SERIAL, CAPTURE_DAY, adb, main as capture
from analyze_guard import main as analyze
from qualify_foreground import qualify_foreground


def main(stage: str, run: str, tag: str = "IpcGuardBench", passes: int = 6) -> None:
    directory = ROOT / f"docs/benchmarks/runs/{CAPTURE_DAY}-xiaomi14ultra" / f"ordered-{stage}"
    assert not directory.exists(), "Choose a fresh stage to preserve prior evidence"
    directory.mkdir(parents=True)
    try:
        run_in_directory(directory, stage, run, tag, passes)
    except Exception as error:
        foreground_path = directory / f"foreground-{run}.csv"
        if not foreground_path.exists():
            try:
                data = subprocess.check_output(["adb", "-s", SERIAL, "exec-out", "run-as", "com.cn.ipc.demo", "cat",
                                                f"files/foreground-{run}.csv"], timeout=10)
                foreground_path.write_bytes(data)
            except (OSError, subprocess.SubprocessError):
                pass
        qualify_foreground(directory, run, [f"Run capture failed: {type(error).__name__}: {error}"])
        raise


def run_in_directory(directory, stage: str, run: str, tag: str, passes: int) -> None:
    for name, arguments in (
        ("battery-before", ("shell", "dumpsys", "battery")),
        ("thermal-before", ("shell", "dumpsys", "thermalservice")),
        ("power-before", ("shell", "dumpsys", "power")),
        ("activities-before", ("shell", "dumpsys", "activity", "activities")),
    ):
        (directory / f"{name}.txt").write_text(adb(*arguments), encoding="utf-8")
    adb("shell", "am", "force-stop", "com.cn.ipc.demo")
    adb("logcat", "-c")
    print(adb("shell", "am", "start", "-n", "com.cn.ipc.demo/.MainActivity", "--es", "ipc_benchmark", run), flush=True)
    deadline = time.monotonic() + 145
    observed_begin = False
    while time.monotonic() < deadline:
        log = adb("logcat", "-d", "-v", "threadtime", "-s", f"{tag}:I", "IpcForeground:I", "IpcEchoBench:E", "AndroidRuntime:E")
        if not observed_begin and f"STATE run={run} event=BEGIN" in log:
            observed_begin = True
            for name, args in (("power-begin", ("shell", "dumpsys", "power")),
                               ("activities-begin", ("shell", "dumpsys", "activity", "activities"))):
                (directory / f"{name}.txt").write_text(adb(*args), encoding="utf-8")
            print(f"Foreground BEGIN observed: {run}", flush=True)
        if "FAILED" in log or "FATAL EXCEPTION" in log:
            (directory / "failed-logcat.txt").write_text(log, encoding="utf-8")
            raise RuntimeError("Benchmark failed; preserved failed-logcat.txt")
        if f"DONE run={run} passed=true" in log and f"END run={run} started=true completed=true valid=true" in log:
            break
        if any(f"END run={run} " in row and "valid=false" in row for row in log.splitlines()):
            (directory / "invalid-logcat.txt").write_text(log, encoding="utf-8")
            raise RuntimeError("Foreground conditions lost; preserved invalid-logcat.txt")
        time.sleep(1)
    else:
        (directory / "incomplete-logcat.txt").write_text(log, encoding="utf-8")
        raise TimeoutError("No complete valid benchmark; preserved incomplete-logcat.txt")
    capture(stage, run, tag, passes)
    foreground_name = f"foreground-{run}.csv"
    foreground = subprocess.check_output(["adb", "-s", SERIAL, "exec-out", "run-as", "com.cn.ipc.demo", "cat", f"files/{foreground_name}"])
    (directory / foreground_name).write_bytes(foreground)
    qualification = qualify_foreground(directory, run)
    if not qualification["valid"]:
        raise RuntimeError("Foreground qualification failed: " + "; ".join(qualification["errors"]))
    names = []
    if tag == "IpcGuardBench":
        names += [f"guard-local-{run}.csv"]
        names += [f"guard-ipc-{run}-r{round_number}-{mode}-{size}.csv"
                  for round_number in range(1, 5) for mode in ("lookup", "guard") for size in (16, 1024)]
    for name in names:
        data = subprocess.check_output(["adb", "-s", SERIAL, "exec-out", "run-as", "com.cn.ipc.demo", "cat", f"files/{name}"])
        (directory / name).write_bytes(data)
    if tag == "IpcGuardBench":
        analyze(directory, run)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "IpcGuardBench",
         int(sys.argv[4]) if len(sys.argv) > 4 else 6)
