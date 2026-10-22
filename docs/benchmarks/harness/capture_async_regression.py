"""Prepare/capture Async semantic regressions on Xiaomi 925c23bb only.

Preparation is the default and never calls ADB. --execute captures six probes
(73 PASS / 6 DONE), separate authmicro, and separate dedicated Async scope
(4 PASS / 1 DONE). --include-generated-terminals adds 9 generated-Stub
business cancellation/timeout terminal checks. Supplying --apk
installs only that APK with `install -r`; existing app data is never cleared.
No comparison timing or real Binder result is inferred from preparation.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid

from qualify_foreground import qualify_foreground


ROOT = Path(__file__).resolve().parents[3]
SERIAL = "925c23bb"
PACKAGE = "com.cn.ipc.demo"
GROUPS = (
    ("lifecycle", "IpcLifecycleProbe", 22),
    ("terminal", "IpcTerminalProbe", 8),
    ("compat", "IpcCompatibilityProbe", 16),
    ("fault", "IpcFaultProbe", 6),
    ("cold", "IpcColdProbe", 12),
    ("handshake", "IpcHandshakeProbe", 9),
    ("authmicro", "IpcAuthMicro", 0),
    ("asyncscope", "IpcAsyncScopeProbe", 4),
)
REGRESSION_GROUPS = frozenset(("lifecycle", "terminal", "compat", "fault", "cold", "handshake"))
EXPECTED_HARDWARE = {"ro.serialno": SERIAL, "ro.product.manufacturer": "Xiaomi",
                     "ro.product.model": "24031PN0DC", "ro.product.device": "aurora"}
LOG_LINE = re.compile(r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+([^:]+?)\s*:\s?(.*)$")


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def source_hashes() -> list[dict]:
    paths = {path for path in ROOT.glob("*/src/main/**/*")
             if path.is_file() and path.suffix in (".kt", ".java", ".aidl", ".xml")}
    paths.update(ROOT.glob("*/build.gradle.kts"))
    paths.update(ROOT / name for name in ("settings.gradle.kts", "build.gradle.kts", "gradle.properties",
                                         "gradle/libs.versions.toml"))
    paths.update((Path(__file__).resolve(), Path(__file__).with_name("qualify_foreground.py").resolve()))
    return [dict(path=path.relative_to(ROOT).as_posix(), sha256=sha(path))
            for path in sorted(paths) if path.is_file()]


def hardware_identity(getprop: str) -> dict:
    values = dict(re.findall(r"(?m)^\[([^\]]+)\]: \[([^\]]*)\]\s*$", getprop))
    identity = {key: values.get(key) for key in EXPECTED_HARDWARE}
    identity["fingerprint"] = values.get("ro.build.fingerprint")
    identity["valid"] = all(identity[key] == expected for key, expected in EXPECTED_HARDWARE.items())
    if not identity["valid"]:
        raise RuntimeError(f"Refusing hardware other than fixed Xiaomi serial/model: {identity}")
    return identity


def inspect_log(text: str, run_id: str, tag: str, expected_passes: int) -> dict:
    # Ignore an incomplete final line and stale -T 1 rows before this run's BEGIN.
    complete_text = text[:text.rfind("\n") + 1]
    rows = [(match[1].strip(), match[2]) for line in complete_text.splitlines()
            if (match := LOG_LINE.match(line))]
    token = re.compile(rf"\brun={re.escape(run_id)}(?:\s|$)")
    failures = [message for row_tag, message in rows
                if (token.search(message) and "FAILED" in message) or
                (row_tag == "AndroidRuntime" and "FATAL EXCEPTION" in message)]
    begin_indices = [index for index, (row_tag, message) in enumerate(rows)
                     if row_tag == "IpcForeground" and token.search(message) and
                     message.startswith("STATE ") and " event=BEGIN " in message]
    result = dict(runId=run_id, tag=tag, passCount=0, doneCount=0, foregroundEndCount=0,
                  expectedPasses=expected_passes, complete=False, valid=False, errors=failures)
    if not begin_indices:
        return result
    measuring = rows[begin_indices[0]:]
    ends = [message for row_tag, message in measuring
            if row_tag == "IpcForeground" and message.startswith("END ") and token.search(message)]
    passes = [message for row_tag, message in measuring
              if row_tag == tag and message.startswith("PASS ") and token.search(message)]
    if tag == "IpcAuthMicro":
        done = [message for row_tag, message in measuring
                if row_tag == tag and message.startswith("DONE ")]
        timings = [message for row_tag, message in measuring if row_tag == tag and message.startswith("TIME ")]
        allocations = [message for row_tag, message in measuring if row_tag == tag and message.startswith("ALLOC ")]
        result["authProbe"] = dict(timeCount=len(timings), allocationCount=len(allocations),
                                   doneCount=len(done), timings=timings, allocations=allocations, done=done)
    else:
        done = [message for row_tag, message in measuring
                if row_tag == tag and message.startswith("DONE ") and token.search(message)]
    result.update(passCount=len(passes), doneCount=len(done), foregroundEndCount=len(ends),
                  passes=passes, done=done, foregroundEnd=ends, complete=bool(done and ends))
    if not result["complete"]:
        return result
    errors = result["errors"]
    if len(begin_indices) != 1 or len(ends) != 1:
        errors.append("Expected exactly one foreground BEGIN and END")
    if any(field not in ends[0].split() for field in ("started=true", "completed=true", "valid=true")):
        errors.append("Foreground END did not declare started/completed/valid=true")
    if len(done) != 1 or len(passes) != expected_passes or len(set(passes)) != len(passes):
        errors.append(f"Expected {expected_passes} unique PASS and one DONE")
    if tag == "IpcAuthMicro":
        if len(timings) != 4 or len(allocations) != 4 or "sameUid=true" not in done[0].split():
            errors.append("Expected separate authmicro 4 TIME / 4 ALLOC / 1 same-UID DONE")
        for messages in (timings, allocations):
            expected = {(str(index), mode, "50000") for index, mode in enumerate(("legacy", "optimized", "optimized", "legacy"))}
            actual = [dict(re.findall(r"(\w+)=([^\s]+)", message)) for message in messages]
            if {(item.get("batch"), item.get("mode"), item.get("count")) for item in actual} != expected:
                errors.append("Authmicro batch/mode/count contract differs")
    elif any("passed=true" not in message.split() for message in done):
        errors.append("Probe DONE did not declare passed=true")
    result["valid"] = not errors
    return result


class Capture:
    def __init__(self, adb: str, output: Path):
        self.adb, self.output = adb, output
        self.records: list[dict] = []

    def argv(self, *arguments: str) -> list[str]:
        return [self.adb, "-s", SERIAL, *arguments]

    def issue(self, *arguments: str, directory: Path | None = None, output: str | None = None,
              required: bool = True, timeout: int = 35, binary: bool = False) -> dict:
        argv = self.argv(*arguments)
        try:
            process = subprocess.run(argv, capture_output=True, timeout=timeout)
            stdout, stderr = process.stdout, process.stderr
            record = dict(argv=argv, returncode=process.returncode,
                          stdoutBytes=len(stdout), stderr=stderr.decode("utf-8", errors="replace"))
        except subprocess.TimeoutExpired as error:
            stdout, stderr = error.stdout or b"", error.stderr or b""
            record = dict(argv=argv, returncode=None, timedOut=True, stdoutBytes=len(stdout),
                          stderr=stderr.decode("utf-8", errors="replace"))
        if not binary:
            record["stdout"] = stdout.decode("utf-8", errors="replace")
        self.records.append(record)
        if output:
            path = (directory or self.output) / output
            path.write_bytes(stdout if binary else stdout + stderr)
        write_json(self.output / "commands.json", {"commands": self.records})
        if required and record["returncode"] != 0:
            raise RuntimeError(f"ADB command failed/timed out: {argv}; stderr={record['stderr']}")
        return record

    def snapshots(self, directory: Path, stage: str) -> None:
        for name, arguments in (("battery", ("dumpsys", "battery")), ("power", ("dumpsys", "power")),
                                ("activities", ("dumpsys", "activity", "activities")),
                                ("thermal", ("dumpsys", "thermalservice"))):
            self.issue("shell", *arguments, directory=directory, output=f"{name}-{stage}.txt", required=False)

    def installed(self, name: str) -> dict:
        paths = self.issue("shell", "pm", "path", PACKAGE)["stdout"].splitlines()
        bases = [line[8:].strip() for line in paths if line.startswith("package:")]
        if len(bases) != 1 or not re.fullmatch(r"/data/app/[A-Za-z0-9_./+=~-]+/base\.apk", bases[0]):
            raise RuntimeError(f"Expected one unsplit installed demo base APK: {paths}")
        destination = self.output / name
        self.issue("pull", bases[0], str(destination), timeout=90)
        self.issue("shell", "dumpsys", "package", PACKAGE, output=name.removesuffix(".apk") + "-package.txt")
        return dict(path=destination.name, remotePath=bases[0], sha256=sha(destination), bytes=destination.stat().st_size)

    def group(self, item: dict, timeout: int) -> dict:
        directory = self.output / item["group"]
        directory.mkdir()
        run_id, tag = item["runId"], item["tag"]
        result = dict(item, status="RUNNING", valid=False)
        process = None
        launched = False
        failure = None
        self.snapshots(directory, "before")
        try:
            self.issue("shell", "am", "force-stop", PACKAGE)
            argv = self.argv("logcat", "-v", "threadtime", "-T", "1", "-s", f"{tag}:I",
                             "IpcAuthProbe:I", "IpcForeground:I", "IpcEchoBench:E", "AndroidRuntime:E", "*:S")
            self.records.append(dict(argv=argv, streaming=True, output=f"{item['group']}/logcat.txt"))
            with (directory / "logcat.txt").open("wb") as log, (directory / "logcat-stderr.txt").open("wb") as err:
                process = subprocess.Popen(argv, stdout=log, stderr=err)
                time.sleep(0.2)
                if process.poll() is not None:
                    raise RuntimeError("Streaming Logcat exited before launch")
                launch = self.issue("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity",
                                    "--es", "ipc_benchmark", run_id, directory=directory, output="launch.txt")
                launched = True
                if "Error:" in launch["stdout"] or "Exception" in launch["stdout"]:
                    raise RuntimeError("Activity launch reported an error")
                deadline = time.monotonic() + timeout
                begin_snapshot = False
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError("Streaming Logcat exited during capture")
                    text = (directory / "logcat.txt").read_text(encoding="utf-8", errors="replace")
                    observed = inspect_log(text, run_id, tag, item["expectedPasses"])
                    if observed["errors"]:
                        raise RuntimeError("Probe failure: " + "; ".join(observed["errors"]))
                    if not begin_snapshot and f"STATE run={run_id} event=BEGIN " in text:
                        begin_snapshot = True
                        self.snapshots(directory, "begin")
                    if observed["complete"]:
                        result.update(observed)
                        if not observed["valid"]:
                            raise RuntimeError("Probe count or completion validation failed")
                        break
                    time.sleep(0.25)
                else:
                    raise TimeoutError(f"No matching DONE plus foreground END after {timeout}s")
        except Exception as error:
            failure = error
            result.update(status="FAILED", error=f"{type(error).__name__}: {error}")
        finally:
            if process is not None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            self.snapshots(directory, "after")
            if launched:
                self.issue("exec-out", "run-as", PACKAGE, "cat", f"files/foreground-{run_id}.csv",
                           directory=directory, output=f"foreground-{run_id}.csv", binary=True, required=False, timeout=15)
                if item["group"] in ("cold", "handshake"):
                    self.issue("exec-out", "run-as", PACKAGE, "cat", f"files/{item['group']}-{run_id}.csv",
                               directory=directory, output=f"{item['group']}-{run_id}.csv", binary=True, required=False, timeout=15)
            qualification = qualify_foreground(directory, run_id,
                                                [result["error"]] if failure else None)
            result["foregroundQualification"] = qualification
            if not qualification["valid"] and failure is None:
                failure = RuntimeError("Foreground CSV qualification failed")
                result["error"] = str(failure)
            log_path = directory / "logcat.txt"
            if log_path.exists():
                result["observed"] = inspect_log(log_path.read_text(encoding="utf-8", errors="replace"),
                                                 run_id, tag, item["expectedPasses"])
            result.update(status="FAILED" if failure else "PASSED", valid=failure is None)
            write_json(directory / "result.json", result)
        if failure:
            raise RuntimeError(f"{item['group']} failed; evidence and app state were preserved: {failure}") from failure
        print(f"PASS {item['group']} run={run_id} passes={result['passCount']} done={result['doneCount']}", flush=True)
        return result


def run(args: argparse.Namespace) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,55}", args.run_id):
        raise ValueError("--run-id needs 1..55 ASCII letters/digits/_.-")
    if not 30 <= args.timeout <= 600:
        raise ValueError("--timeout must be 30..600 seconds per group")
    expected = args.expect_apk_sha256.upper() if args.expect_apk_sha256 else None
    if expected and not re.fullmatch(r"[A-F0-9]{64}", expected):
        raise ValueError("--expect-apk-sha256 must contain 64 hex digits")
    apk = args.apk.resolve() if args.apk else None
    candidate = dict(path=str(apk), sha256=sha(apk), bytes=apk.stat().st_size) if apk else None
    if candidate and expected and candidate["sha256"] != expected:
        raise ValueError("Candidate APK differs from --expect-apk-sha256")
    groups = list(GROUPS)
    if args.include_generated_terminals:
        groups.append(("generatedterminal", "IpcGeneratedTerminalProbe", 9))
    plan = dict(serial=SERIAL, package=PACKAGE, runId=args.run_id,
                preparedOnly=True, deviceValidation="NOT_RUN", expectedPasses=73, expectedDones=6,
                expectedDedicatedScopePasses=4, expectedDedicatedScopeDones=1,
                candidateApk=candidate, expectedInstalledSha256=expected,
                hardwareGate=EXPECTED_HARDWARE, sourceHashes=source_hashes(),
                groups=[dict(group=group, tag=tag, expectedPasses=count, runId=f"{group}-{args.run_id}")
                        for group, tag, count in groups],
                installation="install -r only when --apk supplied; never uninstall or clear data",
                createdUtc=datetime.now(timezone.utc).isoformat())
    if args.include_generated_terminals:
        plan.update(expectedGeneratedTerminalPasses=9, expectedGeneratedTerminalDones=1)
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        if {path.name for path in output.iterdir()} != {"plan.json"}:
            raise ValueError("Use a new output directory or matching preparation-only directory")
        previous = json.loads((output / "plan.json").read_text(encoding="utf-8"))
        for key in ("serial", "package", "runId", "candidateApk", "expectedInstalledSha256", "groups"):
            if previous.get(key) != plan[key]:
                raise ValueError(f"Existing preparation does not match {key}")
        plan["preparedSourceHashes"] = previous["sourceHashes"]
    output.mkdir(parents=True, exist_ok=True)
    write_json(output / "plan.json", plan)
    if not args.execute:
        print(f"Prepared {output / 'plan.json'}; no ADB commands executed. Fixed serial={SERIAL}; "
              "73 PASS/6 DONE plus separate authmicro and dedicated scope 4 PASS/1 DONE; "
              f"generated terminal checks={'9 PASS/1 DONE' if args.include_generated_terminals else 'not requested'}.")
        return
    adb = shutil.which(args.adb)
    if not adb:
        raise ValueError(f"ADB executable not found: {args.adb}")
    capture = Capture(adb, output)
    summary = dict(plan, preparedOnly=False, valid=False, status="RUNNING", groups=[])
    try:
        capture.issue("get-state")
        props = capture.issue("shell", "getprop", output="getprop.txt")
        summary["hardwareIdentity"] = hardware_identity(props["stdout"])
        capture.snapshots(output, "before")
        if apk:
            frozen = output / "candidate.apk"
            shutil.copyfile(apk, frozen)
            if sha(frozen) != candidate["sha256"]:
                raise RuntimeError("Candidate changed while freezing")
            install = capture.issue("install", "-r", str(frozen), output="install.txt", timeout=120)
            if "Success" not in install["stdout"].splitlines():
                raise RuntimeError("install -r did not report Success; no uninstall fallback is permitted")
        summary["installedBefore"] = capture.installed("installed-before.apk")
        if summary["installedBefore"]["sha256"] != (candidate["sha256"] if candidate else expected or summary["installedBefore"]["sha256"]):
            raise RuntimeError("Installed APK hash differs from the declared candidate")
        for item in plan["groups"]:
            summary["groups"].append(capture.group(item, args.timeout))
            write_json(output / "summary.json", summary)
        summary["installedAfter"] = capture.installed("installed-after.apk")
        if summary["installedAfter"]["sha256"] != summary["installedBefore"]["sha256"]:
            raise RuntimeError("Installed APK changed during regression capture")
        probes = [item for item in summary["groups"] if item["group"] in REGRESSION_GROUPS]
        summary["probePassCount"] = sum(item["passCount"] for item in probes)
        summary["probeDoneCount"] = sum(item["doneCount"] for item in probes)
        summary["authProbe"] = next(item for item in summary["groups"] if item["group"] == "authmicro")["authProbe"]
        scope_probe = next(item for item in summary["groups"] if item["group"] == "asyncscope")
        summary["dedicatedScopePassCount"] = scope_probe["passCount"]
        summary["dedicatedScopeDoneCount"] = scope_probe["doneCount"]
        if (summary["probePassCount"], summary["probeDoneCount"]) != (73, 6):
            raise RuntimeError("Aggregate regression count differs from 73 PASS / 6 DONE")
        if (summary["dedicatedScopePassCount"], summary["dedicatedScopeDoneCount"]) != (4, 1):
            raise RuntimeError("Dedicated Async scope count differs from 4 PASS / 1 DONE")
        if args.include_generated_terminals:
            generated = next(item for item in summary["groups"] if item["group"] == "generatedterminal")
            summary["generatedTerminalPassCount"] = generated["passCount"]
            summary["generatedTerminalDoneCount"] = generated["doneCount"]
            if (generated["passCount"], generated["doneCount"]) != (9, 1):
                raise RuntimeError("Generated business terminal count differs from 9 PASS / 1 DONE")
        summary.update(valid=True, status="PASSED", deviceValidation="PASSED")
    except Exception as error:
        summary.update(status="FAILED", deviceValidation="FAILED", error=f"{type(error).__name__}: {error}")
        raise
    finally:
        summary["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        write_json(output / "summary.json", summary)
        write_json(output / "commands.json", {"commands": capture.records})
    print("PASS 73 probe checks / 6 probe DONE; authmicro separately 4 TIME / 4 ALLOC / 1 DONE; "
          "dedicated scope separately 4 PASS / 1 DONE; "
          f"generated terminals={'9 PASS / 1 DONE' if args.include_generated_terminals else 'not requested'}. Evidence: {output}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--run-id", default=uuid.uuid4().hex[:12], help="Unique suffix; each group receives its own prefix")
    parser.add_argument("--apk", type=Path, help="Optional candidate APK; --execute installs it with -r before capture")
    parser.add_argument("--expect-apk-sha256", help="Expected candidate/installed APK hash")
    parser.add_argument("--adb", default="adb", help="ADB executable; serial cannot be changed")
    parser.add_argument("--timeout", type=int, default=150, help="Per-group completion timeout in seconds")
    parser.add_argument("--include-generated-terminals", action="store_true",
                        help="Require the new APK's nine business cancellation/timeout terminal checks")
    parser.add_argument("--execute", action="store_true", help="Explicitly operate only the gated Xiaomi 925c23bb")
    run(parser.parse_args())


if __name__ == "__main__":
    main()
