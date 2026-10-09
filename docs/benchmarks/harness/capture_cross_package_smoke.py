"""Prepare or capture the four-package cold UI smoke on Xiaomi 925c23bb only.

Default preparation never invokes ADB. Root installs the rebuilt APKs separately;
--execute only force-stops/launches the four fixed packages and collects evidence.
No data clear, clicks, swipes, screenshots, unlock, settings or APK installation.
The existing UI logs are authoritative for registration/subscription events:
BaseClientActivity.log and MessageHubServiceImpl.notifyUi do not emit Logcat tags.
"""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path


SERIAL = "925c23bb"
CAPTURE_DAY = "2026-10-09"
ROOT = Path(__file__).resolve().parents[3]
SECONDS_PER_CLIENT = 5
PACKAGES = (
    {"module": "app-server", "package": "com.cn.ipc.server.app", "activity": ".ServerMainActivity"},
    *({"module": f"app-client{index}", "package": f"com.cn.ipc.client{index}",
       "activity": f".Client{index}Activity", "clientId": f"client_{index}"} for index in range(1, 4)),
)
SOURCE_FILES = (
    "demo-client-common/src/main/kotlin/com/cn/ipc/client/common/BaseClientActivity.kt",
    "app-server/src/main/kotlin/com/cn/ipc/server/app/MessageHubServiceImpl.kt",
    "app-server/src/main/kotlin/com/cn/ipc/server/app/ServerBrokerService.kt",
    *(f"{item['module']}/src/main/AndroidManifest.xml" for item in PACKAGES),
    *(f"{item['module']}/build.gradle.kts" for item in PACKAGES),
    *(f"app-client{index}/src/main/kotlin/com/cn/ipc/client{index}/Client{index}Activity.kt" for index in range(1, 4)),
)


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def save_text(path: Path, value: str) -> None:
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(value)


def save_json(path: Path, value: dict) -> None:
    save_text(path, json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def source_hashes() -> list[dict]:
    paths = set(ROOT / name for name in SOURCE_FILES)
    paths.update(path for path in ROOT.glob("*/src/main/**/*")
                 if path.is_file() and path.suffix in (".kt", ".aidl", ".xml"))
    paths.add(Path(__file__).resolve())
    return [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path)} for path in sorted(paths)]


def apk_path(item: dict) -> Path:
    return ROOT / item["module"] / "build/outputs/apk/debug" / f"{item['module']}-debug.apk"


def ui_text(xml: str, package: str) -> tuple[str, bool]:
    hierarchy = ET.fromstring(xml)
    nodes = [node for node in hierarchy.iter("node") if node.get("package") == package]
    return "\n".join(node.get("text", "") + " " + node.get("content-desc", "") for node in nodes), bool(nodes)


def state_gate(power: str, activities: str, package: str | None = None) -> dict:
    resumed = re.findall(r"(?m)^.*(?:topResumedActivity|mResumedActivity|mTopResumedActivity)=.*$", activities)
    unlocked = bool(re.search(r"mKeyguardShowing=false\b", activities)) and not re.search(r"mKeyguardShowing=true\b", activities)
    result = {"awake": bool(re.search(r"mWakefulness=Awake\b", power)), "keyguardNotShowing": bool(unlocked)}
    if package:
        result["requestedPackageResumed"] = any(package + "/" in line for line in resumed)
    result["valid"] = all(result.values())
    return result


class Capture:
    def __init__(self, directory: Path, adb: str):
        self.directory = directory
        self.adb = adb
        self.journal = (directory / "commands.jsonl").open("x", encoding="utf-8")

    def record(self, value: dict) -> dict:
        self.journal.write(json.dumps(value, ensure_ascii=False) + "\n")
        self.journal.flush()
        return value

    def argv(self, *arguments: str) -> list[str]:
        return [self.adb, "-s", SERIAL, *arguments]

    def issue(self, *arguments: str, output: str | None = None, required: bool = True,
              timeout: int = 25, binary: bool = False) -> dict:
        argv = self.argv(*arguments)
        started = datetime.now(timezone.utc).isoformat()
        try:
            response = subprocess.run(argv, capture_output=True, timeout=timeout)
            stdout, stderr, returncode = response.stdout, response.stderr, response.returncode
        except subprocess.TimeoutExpired as error:
            stdout, stderr, returncode = error.stdout or b"", error.stderr or b"", None
        text = stdout.decode("utf-8", errors="replace") if not binary else None
        record = {"argv": argv, "startedUtc": started, "returncode": returncode,
                  "stderr": stderr.decode("utf-8", errors="replace")}
        record.update({"stdoutBytes": len(stdout)} if binary else {"stdout": text})
        if returncode is None:
            record["timedOut"] = True
        if output is not None:
            destination = self.directory / output
            with destination.open("xb") as stream:
                stream.write(stdout)
            record["output"] = output
        self.record(record)
        if required and returncode != 0:
            raise RuntimeError(f"ADB command failed or timed out: {argv}; see commands.jsonl")
        return record

    def snapshot(self, prefix: str) -> tuple[str, str]:
        power = self.issue("shell", "dumpsys", "power", output=f"{prefix}-power.txt")["stdout"]
        activities = self.issue("shell", "dumpsys", "activity", "activities", output=f"{prefix}-activities.txt")["stdout"]
        self.issue("shell", "dumpsys", "window", "windows", output=f"{prefix}-windows.txt", required=False)
        return power, activities

    def xml(self, label: str, run_id: str) -> str:
        remote = f"/data/local/tmp/modernipc-{run_id}-{label}-{uuid.uuid4().hex[:8]}.xml"
        self.issue("shell", "uiautomator", "dump", "--compressed", remote,
                   output=f"{label}-uiautomator.txt", timeout=20)
        self.issue("exec-out", "cat", remote, output=f"{label}-ui.xml", binary=True)
        return (self.directory / f"{label}-ui.xml").read_text(encoding="utf-8-sig")

    def force_stop(self, item: dict, user: str, label: str) -> None:
        self.issue("shell", "am", "force-stop", "--user", user, item["package"], output=f"{label}-force-stop.txt")
        stopped = self.issue("shell", "pidof", item["package"], required=False)
        if stopped["returncode"] not in (0, 1) or stopped["stdout"].strip():
            raise RuntimeError(f"Package still has a process after force-stop: {item['package']}")

    def launch(self, item: dict, user: str, label: str, reuse: bool = False) -> None:
        flags = ("--activity-reorder-to-front", "--activity-single-top") if reuse else ()
        record = self.issue("shell", "am", "start", "--user", user, "-W", *flags, "-n",
                            f"{item['package']}/{item['activity']}", output=f"{label}-launch.txt")
        output = record["stdout"] + record["stderr"]
        statuses = re.findall(r"(?m)^\s*Status:\s*(\S+)", output)
        if re.search(r"(?m)^\s*(?:Error:|Exception:)", output) or any(status != "ok" for status in statuses):
            raise RuntimeError(f"Activity launch failed: {item['package']}")

    def pid(self, package: str) -> str:
        value = self.issue("shell", "pidof", package)["stdout"].strip()
        if not re.fullmatch(r"\d+", value):
            raise RuntimeError(f"Expected one process for {package}, got {value!r}")
        return value


def validate_source_contract() -> None:
    for item in PACKAGES:
        gradle = (ROOT / item["module"] / "build.gradle.kts").read_text(encoding="utf-8-sig")
        manifest = (ROOT / item["module"] / "src/main/AndroidManifest.xml").read_text(encoding="utf-8-sig")
        if not re.search(rf'applicationId\s*=\s*"{re.escape(item["package"])}"', gradle):
            raise ValueError(f"Package contract changed: {item['module']}")
        if f'android:name="{item["activity"]}"' not in manifest:
            raise ValueError(f"Activity contract changed: {item['module']}")
    base = (ROOT / SOURCE_FILES[0]).read_text(encoding="utf-8-sig")
    hub = (ROOT / SOURCE_FILES[1]).read_text(encoding="utf-8-sig")
    for expected in ("暂停订阅", "已启动消息订阅", "向服务端登记为"):
        if expected not in base:
            raise ValueError(f"Client UI evidence contract changed: {expected}")
    for expected in ("客户端上线:", "开启了消息长连接监听"):
        if expected not in hub:
            raise ValueError(f"Server UI evidence contract changed: {expected}")


def execute(args: argparse.Namespace, directory: Path, prepared: dict) -> dict:
    adb = shutil.which(args.adb)
    if adb is None:
        raise ValueError(f"ADB executable not found: {args.adb}")
    capture = Capture(directory, adb)
    result = {"serial": SERIAL, "runId": args.run_id, "preparedOnly": False, "valid": False,
              "packages": [], "clients": [], "limits": prepared["limits"]}
    failure = None
    try:
        if capture.issue("get-state")["stdout"].strip() != "device":
            raise RuntimeError("The fixed Xiaomi serial is not ready")
        capture.issue("shell", "getprop", output="getprop.txt")
        capture.issue("shell", "dumpsys", "battery", output="battery-before.txt")
        capture.issue("shell", "dumpsys", "thermalservice", output="thermal-before.txt", required=False)
        before_power, before_activities = capture.snapshot("before")
        result["beforeState"] = state_gate(before_power, before_activities)
        if not result["beforeState"]["valid"]:
            raise RuntimeError("Device must already be Awake and unlocked; no wake/unlock action is performed")
        user = capture.issue("shell", "am", "get-current-user")["stdout"].strip()
        if not re.fullmatch(r"\d+", user):
            raise RuntimeError(f"Cannot identify current Android user: {user!r}")
        result["androidUser"] = int(user)
        result["sourceHashes"] = source_hashes()
        for item in PACKAGES:
            apk = apk_path(item)
            if not apk.is_file():
                raise FileNotFoundError(f"Root must build/install the APK before --execute: {apk}")
            built = directory / f"{item['module']}-built.apk"
            with apk.open("rb") as source, built.open("xb") as destination:
                shutil.copyfileobj(source, destination)
            local_hash = sha(built)
            if local_hash != sha(apk):
                raise RuntimeError(f"Built APK changed while copying: {apk}")
            package_record = dict(item, builtSha256=local_hash, builtSnapshot=built.name,
                                  originalApk=str(apk), originalMtimeNs=apk.stat().st_mtime_ns)
            result["packages"].append(package_record)
            capture.issue("shell", "dumpsys", "package", item["package"], output=f"{item['module']}-package.txt")
            uid_record = capture.issue("shell", "pm", "list", "packages", "-U", "--user", user, item["package"])
            uid = re.search(rf"(?m)^package:{re.escape(item['package'])}\s+uid:(\d+)\s*$", uid_record["stdout"])
            if uid is None:
                raise RuntimeError(f"Cannot identify installed UID: {item['package']}")
            package_record["uid"] = int(uid.group(1))
            paths = capture.issue("shell", "pm", "path", "--user", user, item["package"])["stdout"]
            remote = [line.removeprefix("package:").strip() for line in paths.splitlines() if line.startswith("package:")]
            if len(remote) != 1 or not re.fullmatch(r"/data/app/[A-Za-z0-9_./+=~-]+/base\.apk", remote[0]):
                raise RuntimeError(f"Expected one installed debug base APK: {item['package']} {remote}")
            installed = directory / f"{item['module']}-installed.apk"
            capture.issue("pull", remote[0], str(installed))
            package_record["installedSha256"] = sha(installed)
            package_record["installedSnapshot"] = installed.name
            if package_record["installedSha256"] != local_hash:
                raise RuntimeError(f"Installed APK differs from local built APK: {item['package']}")
        if len({item["uid"] for item in result["packages"]}) != len(PACKAGES):
            raise RuntimeError("The four packages must have distinct UIDs for this cross-package gate")

        # Stop all old clients before resetting the server, preserving application data.
        for item in PACKAGES[1:]:
            capture.force_stop(item, user, f"initial-{item['module']}")
        capture.force_stop(PACKAGES[0], user, "initial-server")
        capture.launch(PACKAGES[0], user, "server-start")
        server_pid = capture.pid(PACKAGES[0]["package"])
        result["serverPid"] = int(server_pid)
        capture.xml("server-before", args.run_id)
        all_uids = ",".join(str(item["uid"]) for item in result["packages"])
        for item in PACKAGES[1:]:
            label = item["module"]
            client_result = dict(package=item["package"], clientId=item["clientId"], valid=False)
            result["clients"].append(client_result)
            capture.force_stop(item, user, label)
            log_file = (directory / f"{label}-logcat.txt").open("xb")
            process = None
            try:
                argv = capture.argv("logcat", "-b", "main", "-b", "system", "-b", "crash", "-v", "threadtime",
                                    "-T", "1", f"--uid={all_uids}")
                capture.record({"argv": argv, "streamOutput": f"{label}-logcat.txt", "startedUtc": datetime.now(timezone.utc).isoformat()})
                process = subprocess.Popen(argv, stdout=log_file, stderr=subprocess.STDOUT)
                capture.launch(item, user, label)
                client_result["pid"] = int(capture.pid(item["package"]))
                start_power, start_activities = capture.snapshot(f"{label}-start")
                client_result["startState"] = state_gate(start_power, start_activities, item["package"])
                started = time.monotonic()
                while time.monotonic() - started < SECONDS_PER_CLIENT:
                    if process.poll() is not None:
                        raise RuntimeError(f"Live Logcat exited early: {label}")
                    time.sleep(0.1)
                client_result["foregroundCollectionSeconds"] = time.monotonic() - started
                end_power, end_activities = capture.snapshot(f"{label}-end")
                client_result["endState"] = state_gate(end_power, end_activities, item["package"])
                xml = capture.xml(label, args.run_id)
                text, owns_ui = ui_text(xml, item["package"])
                client_result.update(uiOwnPackage=owns_ui, uiConnected="已连接 (代次:" in text,
                                     # UI request evidence only, not proof of a remote active collector.
                                     uiSubscriptionActive="暂停订阅" in text or "已启动消息订阅" in text,
                                     uiRegistrationTextPresent="向服务端登记为" in text,
                                     uiExplicitError=any(word in text for word in ("注册失败:", "消息流中断:", "订阅异常")))
                capture.issue("shell", "dumpsys", "activity", "services", PACKAGES[0]["package"], output=f"{label}-bindings.txt")
                processes = capture.issue("shell", "ps", "-A", "-o", "UID,PID,PPID,NAME", output=f"{label}-processes.txt")["stdout"]
                process_uid = [fields[0] for line in processes.splitlines() if len(fields := line.split()) == 4
                               and fields[1] == str(client_result["pid"]) and fields[3] == item["package"]]
                expected_uid = next(record["uid"] for record in result["packages"] if record["package"] == item["package"])
                client_result["processUid"] = int(process_uid[0]) if len(process_uid) == 1 and process_uid[0].isdigit() else None
                client_result["processUidMatchesInstalled"] = client_result["processUid"] == expected_uid
                if capture.pid(item["package"]) != str(client_result["pid"]) or capture.pid(PACKAGES[0]["package"]) != server_pid:
                    raise RuntimeError("Client/server process restarted during foreground collection")
                client_result["logcatStayedRunning"] = process.poll() is None
            finally:
                if process is not None:
                    process.terminate()
                    try:
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=3)
                    capture.record({"streamOutput": f"{label}-logcat.txt", "endedUtc": datetime.now(timezone.utc).isoformat(),
                                    "returncodeAfterHostTermination": process.returncode})
                log_file.close()
            log = (directory / f"{label}-logcat.txt").read_text(encoding="utf-8", errors="replace")
            client_result["fatalLogcat"] = "FATAL EXCEPTION" in log or "Fatal signal" in log
            client_result["valid"] = all(client_result[key] for key in ("uiOwnPackage", "uiConnected", "uiSubscriptionActive", "processUidMatchesInstalled", "logcatStayedRunning")) and \
                client_result["startState"]["valid"] and client_result["endState"]["valid"] and \
                not client_result["uiExplicitError"] and not client_result["fatalLogcat"]
            print(f"Collected {item['package']}: client UI/state valid={client_result['valid']}; remote confirmation pending", flush=True)

        # Re-open, rather than restart, the existing server Activity to inspect actual application-side events.
        capture.launch(PACKAGES[0], user, "server-return", reuse=True)
        server_power, server_activities = capture.snapshot("server-return")
        result["serverReturnState"] = state_gate(server_power, server_activities, PACKAGES[0]["package"])
        server_xml = capture.xml("server-after", args.run_id)
        server_text, server_owns_ui = ui_text(server_xml, PACKAGES[0]["package"])
        bindings = capture.issue("shell", "dumpsys", "activity", "services", PACKAGES[0]["package"], output="bindings-after.txt")["stdout"]
        result["serverUiOwnPackage"] = server_owns_ui
        result["serverPidStable"] = capture.pid(PACKAGES[0]["package"]) == server_pid
        for client in result["clients"]:
            identity = re.escape(client["clientId"])
            client["serverRegistrationEvent"] = bool(re.search(rf"客户端上线:[^\n]*\[{identity}\]", server_text))
            client["serverSubscriptionEvent"] = bool(re.search(rf"客户端\s*\[{identity}\]\s*开启了消息长连接监听", server_text))
            client["activityManagerBindingPresent"] = client["package"] in bindings
            client["valid"] = client["valid"] and client["serverRegistrationEvent"] and client["serverSubscriptionEvent"] and client["activityManagerBindingPresent"]
        result["sourcesUnchanged"] = result["sourceHashes"] == source_hashes()
        result["builtApksUnchanged"] = all(sha(apk_path(item)) == item["builtSha256"] for item in result["packages"])
        result["valid"] = result["sourcesUnchanged"] and result["builtApksUnchanged"] and result["serverUiOwnPackage"] and \
            result["serverPidStable"] and result["serverReturnState"]["valid"] and all(client["valid"] for client in result["clients"])
        if not result["valid"]:
            raise RuntimeError("Cross-package smoke evidence did not satisfy all gates; raw failures are retained")
    except Exception as error:
        failure = error
        result["error"] = f"{type(error).__name__}: {error}"
    finally:
        for name, arguments in (("battery-after.txt", ("shell", "dumpsys", "battery")),
                                ("thermal-after.txt", ("shell", "dumpsys", "thermalservice")),
                                ("power-after.txt", ("shell", "dumpsys", "power")),
                                ("activities-after.txt", ("shell", "dumpsys", "activity", "activities")),
                                ("bindings-final.txt", ("shell", "dumpsys", "activity", "services", PACKAGES[0]["package"])),
                                ("processes-after.txt", ("shell", "ps", "-A", "-o", "UID,PID,PPID,NAME"))):
            try:
                capture.issue(*arguments, output=name, required=False)
            except Exception as cleanup_error:
                result.setdefault("captureErrors", []).append(f"{name}: {cleanup_error}")
        capture.journal.close()
        result["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        result["artifacts"] = [{"path": path.name, "bytes": path.stat().st_size, "sha256": sha(path)}
                               for path in sorted(directory.iterdir()) if path.is_file()]
        save_json(directory / "capture-result.json", result)
    if failure is not None:
        raise failure
    return result


def main(args: argparse.Namespace) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", args.run_id):
        raise ValueError("Run ID must use 1-64 letters, digits, dot, underscore or hyphen")
    validate_source_contract()
    directory = ROOT / f"docs/benchmarks/runs/{CAPTURE_DAY}-xiaomi14ultra" / f"cross-package-smoke-{args.run_id}"
    directory.mkdir(parents=True, exist_ok=False)
    prepared = {"serial": SERIAL, "captureDay": CAPTURE_DAY, "runId": args.run_id, "preparedOnly": not args.execute,
                "createdUtc": datetime.now(timezone.utc).isoformat(), "secondsPerClient": SECONDS_PER_CLIENT,
                "packages": [dict(item, builtApk=str(apk_path(item)), localApkAvailable=apk_path(item).is_file(),
                                  localSha256=sha(apk_path(item)) if apk_path(item).is_file() else None) for item in PACKAGES],
                "sourceHashes": source_hashes(),
                "plan": ["Verify fixed device state, four installed UIDs and installed-vs-built APK SHA256 before launching.",
                         "Force-stop all old clients and server without clearing data; launch server control UI.",
                         "Cold-launch each client in order; retain at least 5 seconds of UID-filtered live Logcat, states and UI XML.",
                         "Return to existing server UI; require actual registration/subscription records for all three client IDs.",
                         "Keep every failure, package/binding UID mapping, source/APK snapshot and device state; never overwrite a run."],
                "limits": ["Functional cross-package cold-process smoke; not an RTT benchmark or a new installation/data-reset test.",
                           "State is sampled at each collection boundary; this does not prove continuous foreground/focus coverage.",
                           "UI subscription text alone is insufficient: server-side observeMessages and registration UI records are required.",
                           "Existing source has no lifecycle Logcat tags; an empty UID-filtered Logcat is not proof of success or failure.",
                           "A server subscription-factory event does not prove a Flow item was delivered or any routing-isolation/performance claim.",
                           "Package UID and ActivityManager bindings are retained; no per-transaction Binder calling-UID trace is generated.",
                           "No auto-scroll/click is performed: off-screen/missing server records cause an unqualified result, not assumed success."]}
    save_json(directory / "capture-plan.json", prepared)
    if not args.execute:
        print(f"Prepared {directory}; no ADB or device commands executed. Use a new --run-id with --execute after root installs the rebuilt APKs.")
        return
    result = execute(args, directory, prepared)
    print(f"PASS cross-package cold smoke serial={SERIAL} clients={len(result['clients'])} evidence={directory}")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", default=f"crosspkg-{datetime.now(timezone.utc):%H%M%S}-{uuid.uuid4().hex[:8]}")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--execute", action="store_true", help="Capture only after root rebuilds and installs the four apps")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        main(arguments())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, ET.ParseError) as error:
        raise SystemExit(f"ERROR: {error}") from error
