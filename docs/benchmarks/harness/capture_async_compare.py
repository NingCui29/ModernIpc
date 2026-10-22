"""Prepare/capture four balanced Async Echo lanes, exclusively on Xiaomi 925c23bb.

Default preparation never invokes ADB. --execute explicitly installs the supplied
Debug APKs without clearing data, verifies pulled installed bytes, and runs four
balanced rounds. Baseline/default versus candidate/default spans two APKs;
candidate/default versus candidate/dedicated uses the same APK. AndLinker enqueue
retains its upstream default main-thread callback. Direct/sync are references.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import statistics
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from qualify_foreground import qualify_foreground

ROOT = Path(__file__).resolve().parents[3]
SERIAL = "925c23bb"
MODERN = "com.cn.ipc.demo"
ANDLINKER = "com.cn.ipc.benchmark.andlinker"
LANES = ("baseline-default", "candidate-default", "candidate-dedicated", "andlinker")
# Every lane appears once at each period; reverse pairs balance immediate order.
ORDERS = ((0, 1, 3, 2), (1, 2, 0, 3), (2, 3, 1, 0), (3, 0, 2, 1))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest().upper()


def save(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def run(argv: list[str], timeout: int = 45) -> str:
    result = subprocess.run(argv, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    require(result.returncode == 0, f"Command failed: {argv}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def tools(args: argparse.Namespace) -> tuple[Path, Path]:
    sdk = args.android_sdk or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    require(bool(sdk), "Provide --android-sdk or ANDROID_SDK_ROOT")
    build_tools = Path(sdk) / "build-tools"
    suffix = ".exe" if os.name == "nt" else ""
    versions = sorted((path for path in build_tools.iterdir() if path.is_dir()),
                      key=lambda path: tuple(int(part) for part in re.findall(r"\d+", path.name)), reverse=True)
    for directory in versions:
        aapt, signer = directory / ("aapt" + suffix), directory / "lib/apksigner.jar"
        if aapt.is_file() and signer.is_file():
            return aapt, signer
    raise ValueError("No Android build-tools with aapt and apksigner.jar found")


def apk_identity(path: Path, package: str, aapt: Path, signer: Path) -> dict:
    require(path.is_file(), f"APK does not exist: {path}")
    badging = run([str(aapt), "dump", "badging", str(path)])
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
    minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    require(bool(match) and match[1] == package, f"Unexpected APK package: {path}")
    require(bool(target) and bool(minimum), f"Missing SDK identity: {path}")
    require("application-debuggable" in badging, f"An explicitly Debug APK is required: {path}")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else shutil.which("java")
    require(bool(java), "Set JAVA_HOME or put java on PATH")
    signature = run([java, "-jar", str(signer), "verify", "--print-certs", str(path)])
    certs = sorted(set(re.findall(r"certificate SHA-256 digest:\s*([A-Fa-f0-9]{64})", signature)))
    require(bool(certs), f"No verified APK certificate: {path}")
    return dict(path=str(path.resolve()), package=package, sha256=sha(path), bytes=path.stat().st_size,
                versionCode=int(match[2]), versionName=match[3], targetSdk=int(target[1]), minSdk=int(minimum[1]),
                debuggable=True, signerCertificateSha256=[value.upper() for value in certs])


class Device:
    def __init__(self, executable: str, directory: Path):
        self.executable, self.directory, self.records = executable, directory, []

    def argv(self, *args: str) -> list[str]:
        return [self.executable, "-s", SERIAL, *args]

    def issue(self, *args: str, timeout: int = 45, binary: bool = False, optional: bool = False) -> str | bytes:
        argv = self.argv(*args)
        result = subprocess.run(argv, capture_output=True, timeout=timeout)
        record = dict(argv=argv, returncode=result.returncode,
                      stderr=result.stderr.decode("utf-8", "replace"), stdoutBytes=len(result.stdout))
        if not binary:
            record["stdout"] = result.stdout.decode("utf-8", "replace")
        self.records.append(record)
        save(self.directory / "device-commands.json", {"commands": self.records})
        require(optional or result.returncode == 0, f"ADB command failed: {record}")
        return result.stdout if binary else result.stdout.decode("utf-8", "replace")

    def gate(self) -> dict:
        # These identity queries precede every mutation and never use a default device.
        require(self.issue("get-state").strip() == "device", "Fixed Xiaomi is not online")
        require(self.issue("get-serialno").strip() == SERIAL, "Selected serial differs from the allowlist")
        manufacturer = self.issue("shell", "getprop", "ro.product.manufacturer").strip()
        brand = self.issue("shell", "getprop", "ro.product.brand").strip()
        model = self.issue("shell", "getprop", "ro.product.model").strip()
        require(manufacturer.lower() in ("xiaomi", "redmi"), f"Refusing non-Xiaomi manufacturer: {manufacturer}")
        return dict(serial=SERIAL, manufacturer=manufacturer, brand=brand, model=model)

    def snapshots(self, directory: Path, suffix: str) -> None:
        for name, args in (("battery", ("shell", "dumpsys", "battery")),
                           ("thermal", ("shell", "dumpsys", "thermalservice")),
                           ("power", ("shell", "dumpsys", "power")),
                           ("activities", ("shell", "dumpsys", "activity", "activities")),
                           ("processes", ("shell", "ps", "-A"))):
            output = self.issue(*args, optional=name == "thermal")
            (directory / f"{name}-{suffix}.txt").write_text(output, encoding="utf-8")

    def installed(self, identity: dict, directory: Path) -> dict:
        paths = self.issue("shell", "pm", "path", identity["package"])
        values = [line.removeprefix("package:").strip() for line in paths.splitlines() if line.startswith("package:")]
        require(len(values) == 1 and re.fullmatch(r"/data/app/[A-Za-z0-9_./+=~-]+\.apk", values[0]) is not None,
                "Expected one installed base APK, with no split APKs")
        pulled = directory / "installed.apk"
        self.issue("pull", values[0], str(pulled))
        actual = sha(pulled)
        require(actual == identity["sha256"], "Installed base APK differs from the frozen local APK")
        return dict(apkSha256=actual, remotePath=values[0])


def stats(path: Path) -> dict:
    samples = [int(line) for line in path.read_text(encoding="utf-8-sig").splitlines()]
    require(len(samples) == 1000 and all(value > 0 for value in samples), f"Expected 1000 positive RTT samples: {path}")
    ordered = sorted(samples)
    return dict(count=1000, meanMs=statistics.fmean(samples) / 1e6, p50Ms=ordered[499] / 1e6,
                p90Ms=ordered[899] / 1e6, p99Ms=ordered[989] / 1e6, maxMs=ordered[-1] / 1e6,
                sha256=sha(path))


def validate(directory: Path, run_id: str, lane: str) -> dict:
    log = (directory / "logcat.txt").read_text(encoding="utf-8-sig", errors="replace")
    require("FAILED" not in log and "FATAL EXCEPTION" not in log, "Benchmark failure/crash in Logcat")
    qualification = qualify_foreground(directory, run_id)
    require(qualification["valid"], "Invalid foreground qualification: " + str(qualification["errors"]))
    require(f"END run={run_id} started=true completed=true valid=true" in log, "No successful foreground completion")
    if lane.startswith("candidate-"):
        dispatch = lane.removeprefix("candidate-")
        require(any("PASS " in line and f"run={run_id} " in line and f"asyncDispatch={dispatch}" in line
                    for line in log.splitlines()), "Candidate did not acknowledge the selected dispatcher")
        require(any("PASS " in line and f"run={run_id} " in line and "traceDisabled=true" in line
                    for line in log.splitlines()), "Candidate did not explicitly verify tracing disabled")
    if lane == "andlinker":
        require(f"DONE run={run_id} passed=true count=4000" in log, "Missing AndLinker completion gate")
        require("libraryLoggerDisabled=true upstreamUnmodified=true callbackExecutor=main" in log,
                "Missing upstream/default-callback gate")
        require(f"PASS run={run_id} uniqueCallbacks=2100 duplicateCallbacks=0" in log,
                "Missing unique callback count gate")
    rows = []
    prefixes = (("sync", "andlinker-"), ("async", "andlinker-async-")) if lane == "andlinker" else (
        ("suspend", "modern-"), ("direct", "modern-direct-"))
    for mode, prefix in prefixes:
        for size in (16, 1024):
            name = f"{prefix}{run_id}-{size}.csv"
            metrics = stats(directory / name)
            results = [line for line in log.splitlines() if "RESULT " in line and f"run={run_id} " in line
                       and f"mode={mode} " in line and f"size={size} " in line]
            require(len(results) == 1 and "count=1000 failures=0" in results[0].replace("warmup=50 ", ""),
                    f"Missing or duplicate RESULT for {mode}/{size}")
            fields = dict(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)=([^\s]+)", results[0]))
            for key in ("meanMs", "p50Ms", "p90Ms", "p99Ms"):
                require(math.isclose(float(fields[key]), metrics[key], rel_tol=1e-10, abs_tol=1e-9),
                        f"Log/CSV timing differs: {mode}/{size}/{key}")
            rows.append(dict(mode=mode, payloadChars=size, csv=name, **metrics))
    return dict(foregroundQualification=qualification, blocks=rows)


def capture_run(device: Device, identity: dict, lane: str, round_number: int, run_id: str,
                directory: Path, timeout: int) -> dict:
    directory.mkdir()
    result = dict(lane=lane, round=round_number, runId=run_id, valid=False, identity=identity)
    save(directory / "capture.json", result)
    device.gate()
    require(sha(Path(identity["path"])) == identity["sha256"], "Frozen input APK changed after preparation")
    # Only the two benchmark packages are stopped; installed data is retained.
    for package in (MODERN, ANDLINKER):
        device.issue("shell", "am", "force-stop", package)
    device.issue("install", "-r", "-t", identity["path"], timeout=120)
    result["installed"] = device.installed(identity, directory)
    device.snapshots(directory, "before")
    log_stream = (directory / "logcat.txt").open("xb")
    log_process = subprocess.Popen(device.argv("logcat", "-v", "threadtime", "-T", "1", "IpcEchoBench:V",
                                                "IpcForeground:V", "AndroidRuntime:E", "*:S"),
                                   stdout=log_stream, stderr=subprocess.STDOUT)
    try:
        activity = f"{identity['package']}/.MainActivity"
        trigger = ["shell", "am", "start", "-W", "-n", activity, "--es", "ipc_benchmark", run_id,
                   "--ei", "ipc_round", str(round_number)]
        if lane != "andlinker":
            trigger.extend(("--es", "ipc_async_dispatch", "dedicated" if lane.endswith("dedicated") else "default"))
        device.issue(*trigger)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            require(log_process.poll() is None, "Live Logcat process exited")
            log = (directory / "logcat.txt").read_text(encoding="utf-8", errors="replace")
            require("FAILED" not in log and "FATAL EXCEPTION" not in log, "Benchmark failed in live Logcat")
            if f"END run={run_id} started=true completed=true valid=true" in log:
                break
            time.sleep(0.25)
        else:
            raise TimeoutError(f"No qualified completion for {run_id}")
        prefixes = ("andlinker-", "andlinker-async-") if lane == "andlinker" else ("modern-", "modern-direct-")
        names = [f"{prefix}{run_id}-{size}.csv" for prefix in prefixes for size in (16, 1024)]
        names.append(f"foreground-{run_id}.csv")
        for name in names:
            data = device.issue("exec-out", "run-as", identity["package"], "cat", f"files/{name}", binary=True)
            require(bool(data), f"Empty benchmark artifact: {name}")
            (directory / name).write_bytes(data)
        result["installedAfter"] = device.installed(identity, directory)
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        log_process.terminate()
        try:
            log_process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            log_process.kill()
            log_process.wait(timeout=3)
        log_stream.close()
        device.snapshots(directory, "after")
        save(directory / "capture.json", result)
    result.update(validate(directory, run_id, lane))
    result["valid"] = True
    save(directory / "capture.json", result)
    return result


def main(args: argparse.Namespace) -> None:
    output = args.output.resolve()
    metadata_path = output / "comparison.json"
    existing = json.loads(metadata_path.read_text(encoding="utf-8-sig")) if metadata_path.is_file() else None
    require(not output.exists() or not any(output.iterdir()) or (existing and existing.get("preparedOnly") and
            {path.name for path in output.iterdir()} == {"comparison.json"}), "Use empty output or its unchanged prepare-only plan")
    run_id = args.run_id or (existing or {}).get("runId") or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    require(re.fullmatch(r"[A-Za-z0-9_.-]{1,32}", run_id) is not None, "Run ID must be safe and at most 32 characters")
    aapt, signer = tools(args)
    identities = dict(baseline=apk_identity(args.baseline_apk, MODERN, aapt, signer),
                      candidate=apk_identity(args.candidate_apk, MODERN, aapt, signer),
                      andlinker=apk_identity(args.andlinker_apk, ANDLINKER, aapt, signer))
    require(len({item["targetSdk"] for item in identities.values()}) == 1 and identities["candidate"]["targetSdk"] == 34,
            "All APKs must be Debug targetSdk 34; the old target26 AndLinker sample is rejected")
    require(identities["baseline"]["signerCertificateSha256"] == identities["candidate"]["signerCertificateSha256"],
            "Modern baseline/candidate signing certificates differ; no uninstall/data-clear fallback is permitted")
    require(identities["baseline"]["sha256"] != identities["candidate"]["sha256"], "Baseline and candidate must differ")
    if existing:
        require(existing["identities"] == identities and existing["runId"] == run_id, "Prepared APKs/run ID changed")
    output.mkdir(parents=True, exist_ok=True)
    schedule = [dict(round=round_number, lane=LANES[index],
                     runId=f"asynccompare-{run_id}-l{index}-r{round_number}")
                for round_number, order in enumerate(ORDERS, 1) for index in order]
    metadata = dict(serial=SERIAL, runId=run_id, preparedOnly=not args.execute, valid=False,
                    createdUtc=datetime.now(timezone.utc).isoformat(), identities=identities, schedule=schedule,
                    workload=dict(payloadChars=[16, 1024], warmupPerBlock=50, measuredPerBlock=1000, roundsPerLane=4,
                                  inFlightRequests=1, input="ASCII x repeated", resultCheck="exact String equality"),
                    limitations=["Baseline/default versus candidate/default is a controlled cross-APK comparison, not same-APK isolation.",
                                 "Candidate/default versus candidate/dedicated uses one APK but changes application server dispatch scope.",
                                 "AndLinker enqueue uses its default main-thread callback; Direct/sync have different cancellation/capacity semantics.",
                                 "Foreground coverage is bounded app observation, not equal CPU frequency, load, GC, JIT, or proof of scheduler causality.",
                                 "The pre-existing baseline does not acknowledge the new dispatcher extra; only baseline-default is allowed.",
                                 "RTT samples within a block are correlated; summary unit is the block, without pooled-request significance claims."])
    save(metadata_path, metadata)
    if not args.execute:
        print(json.dumps(dict(status="PREPARED", serial=SERIAL, runs=len(schedule), adbInvoked=False, output=str(output))))
        return
    executable = shutil.which(args.adb)
    require(bool(executable), f"ADB executable not found: {args.adb}")
    device = Device(executable, output)
    try:
        metadata["device"] = device.gate()
        metadata["runs"] = []
        for number, item in enumerate(schedule, 1):
            lane = item["lane"]
            identity = identities["andlinker" if lane == "andlinker" else "baseline" if lane.startswith("baseline") else "candidate"]
            directory = output / f"{number:02d}-{lane}-r{item['round']}"
            captured = capture_run(device, identity, lane, item["round"], item["runId"], directory, args.timeout)
            metadata["runs"].append(dict(directory=directory.name, **captured))
            save(metadata_path, metadata)
            print(f"PASS {number}/{len(schedule)} {lane} round={item['round']}", flush=True)
        summaries = []
        for lane in LANES:
            modes = ("async", "sync") if lane == "andlinker" else ("suspend", "direct")
            for mode in modes:
                for size in (16, 1024):
                    blocks = [block for captured in metadata["runs"] if captured["lane"] == lane
                              for block in captured["blocks"] if block["mode"] == mode and block["payloadChars"] == size]
                    require(len(blocks) == 4, "Four complete block observations are required for each lane/mode/size")
                    summaries.append(dict(lane=lane, mode=mode, payloadChars=size, blockCount=4,
                                          medianBlockMetrics={key: statistics.median(block[key] for block in blocks)
                                                              for key in ("meanMs", "p50Ms", "p90Ms", "p99Ms", "maxMs")}))
        metadata["summaries"] = summaries
        metadata["valid"] = True
    except Exception as error:
        metadata["error"] = str(error)
        raise
    finally:
        metadata["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        save(metadata_path, metadata)
    print(json.dumps(dict(status="CAPTURED", serial=SERIAL, runs=len(schedule), output=str(output))))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--baseline-apk", type=Path, required=True)
    parser.add_argument("--candidate-apk", type=Path, required=True)
    parser.add_argument("--andlinker-apk", type=Path, required=True)
    parser.add_argument("--android-sdk", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--execute", action="store_true")
    try:
        main(parser.parse_args())
    except (ValueError, OSError, KeyError, subprocess.SubprocessError, TimeoutError) as error:
        raise SystemExit(f"ERROR: {error}") from error
