"""Prepare or capture a short request trace on Xiaomi 925c23bb only.

Preparation is the default and never invokes ADB. Pass --execute only after the
functional regression gate. No APK installation or source modification occurs.
See https://perfetto.dev/docs/reference/perfetto-cli and
https://perfetto.dev/docs/data-sources/atrace for capture options.
"""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from qualify_foreground import qualify_foreground
from analyze_request_trace import validate_benchmark_artifacts


SERIAL = "925c23bb"
ROOT = Path(__file__).resolve().parents[3]
EVENTS = (
    "sched/sched_switch", "sched/sched_wakeup", "sched/sched_waking",
    "power/cpu_frequency", "power/cpu_idle",
    "binder/binder_transaction", "binder/binder_transaction_received",
)


def make_config(duration_ms: int, package: str, categories: tuple[str, ...]) -> str:
    events = "\n".join(f'      ftrace_events: "{event}"' for event in EVENTS)
    tags = "\n".join(f'      atrace_categories: "{category}"' for category in categories)
    return f'''buffers {{ size_kb: 32768 fill_policy: RING_BUFFER }}
duration_ms: {duration_ms}
flush_period_ms: 1000
data_sources {{
  config {{
    name: "linux.ftrace"
    ftrace_config {{
{events}
{tags}
      atrace_apps: "{package}"
    }}
  }}
}}
data_sources {{
  config {{
    name: "linux.process_stats"
    process_stats_config {{ scan_all_processes_on_start: true }}
  }}
}}
'''


def adb_command(adb: str, *arguments: str) -> list[str]:
    return [adb, "-s", SERIAL, *arguments]


def command(adb: str, *arguments: str, required: bool = True, timeout: int = 35) -> dict:
    argv = adb_command(adb, *arguments)
    result = subprocess.run(argv, capture_output=True, text=True, encoding="utf-8",
                            errors="replace", timeout=timeout)
    record = {"argv": argv, "returncode": result.returncode,
              "stdout": result.stdout, "stderr": result.stderr}
    if required and result.returncode:
        raise RuntimeError(f"ADB command failed: {argv}\n{result.stdout}\n{result.stderr}")
    return record


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def source_hashes() -> list[dict]:
    sources = sorted(path for path in ROOT.glob("*/src/main/**/*")
                     if path.is_file() and path.suffix in (".kt", ".aidl", ".xml"))
    return [dict(path=path.relative_to(ROOT).as_posix(), sha256=sha(path)) for path in sources]


def capture(args: argparse.Namespace) -> None:
    for name, value in (("run ID", args.run_id), ("package", args.package)):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", value):
            raise ValueError(f"Invalid {name}: {value}")
    if not args.run_id.startswith("trace") or len(args.run_id) > 80:
        raise ValueError("Request trace run ID must start with trace and contain at most 80 characters")
    if not 2 <= args.seconds <= 60:
        raise ValueError("Trace duration must be between 2 and 60 seconds")
    if args.output.exists() and any(args.output.iterdir()):
        existing = {path.name for path in args.output.iterdir()}
        prepared = args.output / "capture-metadata.json"
        metadata = json.loads(prepared.read_text(encoding="utf-8-sig")) if prepared.is_file() else {}
        if (existing != {"request-trace.pbtxt", "capture-metadata.json"} or
                not metadata.get("preparedOnly") or metadata.get("serial") != SERIAL or
                metadata.get("runId") != args.run_id or metadata.get("package") != args.package):
            raise ValueError("Use an empty or matching preparation-only output directory to preserve evidence")
    args.output.mkdir(parents=True, exist_ok=True)
    config_path = args.output / "request-trace.pbtxt"
    trace_path = args.output / "request-trace.perfetto-trace"
    log_path = args.output / "logcat.txt"
    config_path.write_text(make_config(args.seconds * 1000, args.package,
                                      ("binder_driver", "dalvik")), encoding="utf-8")
    token = f"modernipc-{args.run_id}-{uuid.uuid4().hex[:8]}"
    remote_config = f"/data/misc/perfetto-configs/{token}.pbtxt"
    remote_trace = f"/data/misc/perfetto-traces/{token}.perfetto-trace"
    start = ["shell", "perfetto", "--txt", "-c", remote_config, "-o", remote_trace,
             "--background-wait"]
    trigger = ["shell", "am", "start", "-W", "-n", f"{args.package}/.MainActivity",
               "--es", "ipc_benchmark", args.run_id]
    trace_enabled = True
    for extra in args.extra_string:
        key, separator, value = extra.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z0-9_.-]+", key) or not re.fullmatch(r"[A-Za-z0-9_.:+/-]+", value):
            raise ValueError(f"Invalid string extra (use safe key=value): {extra}")
        if key == "ipc_request_trace":
            raise ValueError("ipc_request_trace must be supplied as --extra-bool")
        trigger.extend(("--es", key, value))
    for extra in args.extra_bool:
        key, separator, value = extra.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z0-9_.-]+", key) or value not in ("true", "false"):
            raise ValueError(f"Invalid Boolean extra (use key=true|false): {extra}")
        trigger.extend(("--ez", key, value))
        if key == "ipc_request_trace":
            trace_enabled = value == "true"
    metadata = {"serial": SERIAL, "package": args.package, "runId": args.run_id,
                "preparedOnly": not args.execute, "durationSeconds": args.seconds,
                "traceEnabled": trace_enabled, "captureValid": False,
                "createdUtc": datetime.now(timezone.utc).isoformat(),
                "notes": ["Diagnostic trace; compare performance separately with tracing disabled.",
                          "android.log is omitted; external Logcat is retained on Android user builds.",
                          "The two per-process event files are authoritative; Logcat EVT is never merged.",
                          "GC slices require supported dalvik atrace category; absence is unavailable."],
                "plan": [adb_command(args.adb, "push", str(config_path), remote_config),
                         adb_command(args.adb, *start), adb_command(args.adb, *trigger),
                         adb_command(args.adb, "pull", remote_trace, str(trace_path))]}
    metadata_path = args.output / "capture-metadata.json"
    write_json(metadata_path, metadata)
    if not args.execute:
        print(f"Prepared {config_path}; no device commands executed. Serial is fixed to {SERIAL}.")
        return
    records = metadata["commands"] = []
    adb = shutil.which(args.adb)
    log_process = None
    log_file = None
    perfetto_pid = None
    launched = False
    failure = None
    event_names = {side: f"request-events-{args.run_id}-{side}.log" for side in ("client", "server")}
    artifact_names = (f"foreground-{args.run_id}.csv", f"trace-rtt-{args.run_id}.csv", f"trace-stats-{args.run_id}.csv")
    if trace_enabled:
        artifact_names += tuple(event_names.values())

    def issue(*arguments: str, required: bool = True, output: str | None = None) -> dict:
        try:
            record = command(adb, *arguments, required=False)
        except subprocess.TimeoutExpired as error:
            def decoded(value: str | bytes | None) -> str:
                return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value or ""
            record = dict(argv=adb_command(adb, *arguments), returncode=None, timedOut=True,
                          stdout=decoded(error.stdout), stderr=decoded(error.stderr))
            records.append(record)
            if output is not None:
                (args.output / output).write_text(record["stdout"] + record["stderr"], encoding="utf-8")
            raise
        records.append(record)
        if output is not None:
            (args.output / output).write_text(record["stdout"] + record["stderr"], encoding="utf-8")
        if required and record["returncode"]:
            raise RuntimeError(f"ADB command failed: {record}")
        return record

    def pull_artifact(name: str, required: bool = True) -> None:
        path = args.output / name
        if path.exists():
            return
        argv = adb_command(adb, "exec-out", "run-as", args.package, "cat", f"files/{name}")
        try:
            result = subprocess.run(argv, capture_output=True, timeout=15)
        except subprocess.TimeoutExpired as error:
            partial = error.stdout or b""
            if partial:
                path.write_bytes(partial)
            records.append(dict(argv=argv, returncode=None, timedOut=True, stdoutBytes=len(partial),
                                stderr=(error.stderr or b"").decode("utf-8", errors="replace")))
            raise
        records.append(dict(argv=argv, returncode=result.returncode, stdoutBytes=len(result.stdout),
                            stderr=result.stderr.decode("utf-8", errors="replace")))
        if result.stdout:
            path.write_bytes(result.stdout)
        if required and (result.returncode or not result.stdout):
            raise RuntimeError(f"Cannot pull complete artifact {name}: {result.stderr!r}")

    try:
        if adb is None:
            raise ValueError(f"ADB executable not found: {args.adb}")
        issue("get-state")
        issue("shell", "getprop", output="getprop.txt")
        issue("shell", "dumpsys", "battery", output="battery-before.txt")
        issue("shell", "dumpsys", "thermalservice", required=False, output="thermal-before.txt")
        issue("shell", "dumpsys", "power", output="power-before.txt")
        issue("shell", "dumpsys", "activity", "activities", output="activities-before.txt")
        issue("shell", "perfetto", "--version", output="device-perfetto-version.txt")
        categories = issue("shell", "atrace", "--list_categories", required=False, output="atrace-categories.txt")
        allowed = tuple(category for category in ("binder_driver", "dalvik")
                        if re.search(rf"(?m)^\s*{category}\s+-", categories["stdout"]))
        config_path.write_text(make_config(args.seconds * 1000, args.package, allowed), encoding="utf-8")
        metadata["enabledAtraceCategories"] = allowed
        issue("shell", "cat", "/sys/kernel/tracing/available_events", required=False, output="available-ftrace-events.txt")
        apk = ROOT / "demo-app/build/outputs/apk/debug/demo-app-debug.apk"
        built = args.output / "built-snapshot.apk"
        shutil.copyfile(apk, built)
        metadata["apkSha256"] = sha(built)
        apk_paths = issue("shell", "pm", "path", args.package)
        remote_paths = [line.removeprefix("package:").strip() for line in apk_paths["stdout"].splitlines()]
        base = [path for path in remote_paths if path.endswith("/base.apk") and
                re.fullmatch(r"/data/app/[A-Za-z0-9_./+=~-]+\.apk", path)]
        if len(base) != 1:
            raise RuntimeError(f"Cannot identify installed base APK: {remote_paths}")
        installed = args.output / "installed-snapshot.apk"
        issue("pull", base[0], str(installed))
        metadata["installedSha256"] = sha(installed)
        if metadata["installedSha256"] != metadata["apkSha256"]:
            raise RuntimeError("Installed APK differs from the built APK")
        metadata["sourceHashes"] = source_hashes()
        if args.force_stop:
            issue("shell", "am", "force-stop", args.package)
        log_file = log_path.open("wb")
        log_process = subprocess.Popen(adb_command(adb, "logcat", "-v", "threadtime", "-T", "1",
                                                   "IpcReqTrace:V", "IpcTraceBench:V", "IpcForeground:V", "IpcEchoBench:I",
                                                   "perfetto:V", "traced_probes:V", "AndroidRuntime:E", "*:S"),
                                       stdout=log_file, stderr=subprocess.STDOUT)
        issue("push", str(config_path), remote_config)
        trace_start = issue(*start)
        pid_lines = re.findall(r"(?m)^\s*(\d+)\s*$", trace_start["stdout"])
        perfetto_pid = pid_lines[-1] if pid_lines else None
        metadata["captureStartedUtc"] = datetime.now(timezone.utc).isoformat()
        deadline = time.monotonic() + args.seconds + 2
        launched = True
        issue(*trigger)
        completed = False
        while time.monotonic() < deadline:
            observed = log_path.read_text(encoding="utf-8-sig", errors="replace")
            if "FAILED" in observed or "FATAL EXCEPTION" in observed:
                raise RuntimeError("Benchmark failure in live Logcat")
            completed = (f"DONE run={args.run_id} passed=true" in observed and
                         f"END run={args.run_id} started=true completed=true valid=true" in observed)
            time.sleep(min(0.5, max(0, deadline - time.monotonic())))
        if not completed:
            raise TimeoutError("Benchmark did not complete inside the request trace capture window")
        issue("pull", remote_trace, str(trace_path))
        if trace_path.stat().st_size == 0:
            raise RuntimeError("Pulled trace is empty")
        metadata["traceBytes"] = trace_path.stat().st_size
        metadata["traceSha256"] = hashlib.sha256(trace_path.read_bytes()).hexdigest()
        for name in artifact_names:
            pull_artifact(name)
        if metadata["sourceHashes"] != source_hashes() or metadata["apkSha256"] != sha(apk):
            raise RuntimeError("Source or built APK changed during trace capture")
        metadata["captureFinishedUtc"] = datetime.now(timezone.utc).isoformat()
    except Exception as error:
        failure = error
        metadata["error"] = str(error)
        if perfetto_pid:
            try:
                issue("shell", "kill", "-INT", perfetto_pid, required=False)
            except Exception as cleanup_error:
                metadata.setdefault("cleanupErrors", []).append(str(cleanup_error))
    finally:
        if launched and adb is not None:
            for name in artifact_names:
                try:
                    pull_artifact(name, required=False)
                except Exception as cleanup_error:
                    metadata.setdefault("cleanupErrors", []).append(str(cleanup_error))
            if not trace_path.exists():
                try:
                    issue("pull", remote_trace, str(trace_path), required=False)
                except Exception as cleanup_error:
                    metadata.setdefault("cleanupErrors", []).append(str(cleanup_error))
        if adb is not None:
            for name, arguments in (("power-after.txt", ("shell", "dumpsys", "power")),
                                    ("activities-after.txt", ("shell", "dumpsys", "activity", "activities")),
                                    ("battery-after.txt", ("shell", "dumpsys", "battery")),
                                    ("thermal-after.txt", ("shell", "dumpsys", "thermalservice")),
                                    ("processes-after.txt", ("shell", "ps", "-A"))):
                try:
                    after = issue(*arguments, required=False, output=name)
                    if name in ("power-after.txt", "activities-after.txt") and (after["returncode"] or not after["stdout"].strip()):
                        raise RuntimeError(f"Required after-capture snapshot failed: {name}")
                except Exception as cleanup_error:
                    metadata.setdefault("cleanupErrors", []).append(str(cleanup_error))
                    if name in ("power-after.txt", "activities-after.txt") and failure is None:
                        failure = cleanup_error
                        metadata["error"] = str(cleanup_error)
        if log_process is not None:
            try:
                log_process.terminate()
                try:
                    log_process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    log_process.kill()
                    log_process.wait(timeout=3)
            except Exception as cleanup_error:
                metadata.setdefault("cleanupErrors", []).append(str(cleanup_error))
                if failure is None:
                    failure = cleanup_error
                    metadata["error"] = str(cleanup_error)
        if log_file is not None:
            try:
                log_file.close()
            except Exception as cleanup_error:
                metadata.setdefault("cleanupErrors", []).append(str(cleanup_error))
                if failure is None:
                    failure = cleanup_error
                    metadata["error"] = str(cleanup_error)
        metadata["eventFiles"] = [dict(side=side, path=name,
                                            bytes=(args.output / name).stat().st_size,
                                            sha256=sha(args.output / name))
                                  for side, name in event_names.items() if (args.output / name).is_file()]
        qualification = qualify_foreground(args.output, args.run_id,
                                            [f"Trace capture failed: {failure}"] if failure else None)
        if failure is None:
            try:
                if not qualification["valid"]:
                    raise ValueError("Foreground coverage failed: " + "; ".join(qualification["errors"]))
                metadata["benchmark"] = validate_benchmark_artifacts(args.output, args.run_id, trace_enabled)
                metadata["captureValid"] = True
            except Exception as error:
                failure = error
                metadata["error"] = str(error)
                qualification = qualify_foreground(args.output, args.run_id, [f"Trace capture qualification failed: {error}"])
        metadata["foregroundQualification"] = qualification
        write_json(metadata_path, metadata)
    if failure is not None:
        raise failure
    print(f"Captured Xiaomi trace and Logcat in {args.output}; no APK installed.")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--package", default="com.cn.ipc.demo")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--seconds", type=int, default=15)
    parser.add_argument("--extra-string", action="append", default=[])
    parser.add_argument("--extra-bool", action="append", default=[])
    parser.add_argument("--force-stop", action="store_true")
    parser.add_argument("--execute", action="store_true", help="Run capture after functional regression passes")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        capture(arguments())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        raise SystemExit(f"ERROR: {error}") from error
