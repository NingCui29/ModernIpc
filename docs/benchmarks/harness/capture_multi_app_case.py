"""Prepare/capture the four-APK meeting-board use case on Xiaomi 925c23bb.

Preparation is the default and never invokes ADB. --execute requires the four
rebuilt debug APKs to be installed, and the fixed Xiaomi to be awake/unlocked.
The harness starts the real Activities and invokes their DEBUG-only intent
actions, which share the visible buttons' business/UI path. It retains states,
UI XML, package/UID/PID identities, source/APK hashes and a live Logcat stream.
No install, data clear, unlock, shell broadcast, direct Binder call or benchmark
is performed. Route return strings are recorded but never prove UI delivery.
"""

import argparse
import json
import re
import shutil
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET

from capture_cross_package_smoke import (
    Capture, PACKAGES, ROOT, SERIAL, apk_path, save_json, sha,
    source_hashes, state_gate, ui_text,
)


TAG = "IPC_MULTI_APP_CASE"
WAIT_SECONDS = 15
ABSENCE_SECONDS = 3
TITLE = "WeeklyPlanning"
ROOM = "RoomA301"
EVENT_LINE = re.compile(
    r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+(\d+)\s+"
    r"[VDIWEF]\s+IPC_MULTI_APP_CASE\s*:\s*(\{.*\})\s*$"
)
BUSINESS_KINDS = {"meeting.show", "meeting.displayed", "meeting.notice", "meeting.audit"}


def current_source_hashes() -> list[dict]:
    values = {item["path"]: item for item in source_hashes()}
    script = Path(__file__).resolve()
    name = script.relative_to(ROOT).as_posix()
    values[name] = {"path": name, "sha256": sha(script)}
    return [values[name] for name in sorted(values)]


def validate_contract() -> None:
    # The actual JSON/event codec may live in a shared helper, so validate it
    # through the source snapshot rather than demanding an inline implementation.
    kotlin = "\n".join(path.read_text(encoding="utf-8-sig")
                        for path in ROOT.glob("*/src/main/**/*.kt"))
    for value in (TAG, "meeting-board", "meeting.show", "meeting.displayed", "meeting.notice", "meeting.audit",
                  "demo_case_action", "demo_case_command_id", "demo_case_title", "demo_case_room",
                  "subscription_paused", "subscription_requested", "audit_received", "多 App 案例：会议通知"):
        if value not in kotlin:
            raise ValueError(f"Meeting payload/evidence contract missing: {value}")


class MeetingCapture(Capture):
    def __init__(self, directory: Path, adb: str, run_id: str, user: str, identities: list[dict]):
        super().__init__(directory, adb)
        self.run_id = run_id
        self.user = user
        self.identities = identities
        self.sequence = 0
        self.log_process = None
        self.log_stream = None

    def label(self, value: str) -> str:
        self.sequence += 1
        return f"{self.sequence:03d}-{value}"

    def start_log(self) -> None:
        self.log_stream = (self.directory / "case-logcat.txt").open("xb")
        uids = ",".join(str(item["uid"]) for item in self.identities)
        argv = self.argv("logcat", "-b", "main", "-b", "system", "-b", "crash",
                         "-v", "threadtime", "-T", "1", f"--uid={uids}")
        self.record({"argv": argv, "streamOutput": "case-logcat.txt",
                     "startedUtc": datetime.now(timezone.utc).isoformat()})
        self.log_process = subprocess.Popen(argv, stdout=self.log_stream, stderr=subprocess.STDOUT)

    def stop_log(self) -> None:
        if self.log_process is not None:
            self.log_process.terminate()
            try:
                self.log_process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.log_process.kill()
                self.log_process.wait(timeout=3)
            self.record({"streamOutput": "case-logcat.txt", "endedUtc": datetime.now(timezone.utc).isoformat(),
                         "returncodeAfterHostTermination": self.log_process.returncode})
        if self.log_stream is not None:
            self.log_stream.close()

    def log_alive(self) -> None:
        if self.log_process is None or self.log_process.poll() is not None:
            raise RuntimeError("Live UID-filtered Logcat is not running")

    def events(self) -> list[dict]:
        path = self.directory / "case-logcat.txt"
        if not path.is_file():
            return []
        raw = path.read_text(encoding="utf-8", errors="replace")
        records = []
        # A writer can be midway through the last line; only complete lines count.
        for number, line in enumerate(raw.splitlines(keepends=True), 1):
            if not line.endswith("\n"):
                continue
            match = EVENT_LINE.match(line.rstrip("\r\n"))
            if match is None:
                continue
            try:
                data = json.loads(match.group(3))
            except json.JSONDecodeError as error:
                raise RuntimeError(f"Malformed {TAG} JSON at case-logcat.txt:{number}: {error}") from error
            if not isinstance(data, dict) or not all(isinstance(data.get(key), str)
                                                   for key in ("event", "clientId", "commandId", "kind", "target", "detail")):
                raise RuntimeError(f"Malformed {TAG} event contract at case-logcat.txt:{number}")
            if data["event"] in ("send", "route_result", "received", "displayed", "acknowledged", "notice_visible", "audit_received") \
                    and data["kind"] not in BUSINESS_KINDS:
                raise RuntimeError(f"Malformed {TAG} business kind at case-logcat.txt:{number}")
            identity = next((item for item in self.identities if item.get("pid") == int(match.group(1))), None)
            if identity is not None and "clientId" in identity and data["clientId"] != identity["clientId"]:
                raise RuntimeError(f"{TAG} clientId does not match captured process at case-logcat.txt:{number}")
            records.append(dict(data, _line=number, _pid=int(match.group(1)), _tid=int(match.group(2))))
        return records

    def selected(self, command_id: str, event: str | None = None, client_index: int | None = None,
                 kind: str | None = None) -> list[dict]:
        records = [item for item in self.events() if item["commandId"] == command_id
                   and (event is None or item["event"] == event)
                   and (kind is None or item["kind"] == kind)]
        if client_index is not None:
            identity = self.identities[client_index]
            records = [item for item in records if item["_pid"] == identity["pid"]
                       and (client_index == 0 or item["clientId"] == identity["clientId"])]
        return records

    def wait_event(self, command_id: str, event: str, client_index: int, kind: str | None = None,
                   target: str | None = None) -> dict:
        started = time.monotonic()
        while time.monotonic() - started < WAIT_SECONDS:
            self.log_alive()
            records = [item for item in self.selected(command_id, event, client_index, kind)
                       if target is None or item["target"] == target]
            if records:
                return records[-1]
            errors = [item for item in self.selected(command_id) if item["event"] in ("error", "rejected")]
            if errors:
                raise RuntimeError(f"Business action rejected/failed for {command_id}: {errors}")
            time.sleep(0.1)
        raise RuntimeError(f"No {event}/{kind or '*'} event from {self.identities[client_index]['package']} "
                           f"for {command_id} within {WAIT_SECONDS}s")

    def observe_absence(self, command_id: str, client_indices: tuple[int, ...],
                        kind: str, seconds: int = ABSENCE_SECONDS) -> dict:
        started = time.monotonic()
        while time.monotonic() - started < seconds:
            self.log_alive()
            for index in client_indices:
                unexpected = [item for item in self.selected(command_id, client_index=index, kind=kind)
                              if item["event"] in ("received", "displayed", "acknowledged", "notice_visible")]
                if unexpected:
                    raise RuntimeError(f"Unexpected {kind} business delivery for {command_id}: {unexpected}")
            time.sleep(0.1)
        return {"commandId": command_id, "kind": kind, "observedSeconds": time.monotonic() - started,
                "clientIds": [self.identities[index]["clientId"] for index in client_indices],
                "unexpectedDeliveryEvents": 0,
                "limit": "Bounded observation in this capture, not a permanent non-delivery guarantee"}

    def action(self, index: int, action: str, command_id: str) -> None:
        item = self.identities[index]
        label = self.label(f"{action}-{item['module']}")
        response = self.issue("shell", "am", "start", "--user", self.user, "-W",
                              "--activity-reorder-to-front", "--activity-single-top", "-n",
                              f"{item['package']}/{item['activity']}",
                              "--es", "demo_case_action", action, "--es", "demo_case_command_id", command_id,
                              "--es", "demo_case_title", TITLE, "--es", "demo_case_room", ROOM,
                              output=f"{label}-intent.txt")
        value = response["stdout"] + response["stderr"]
        if re.search(r"(?m)^\s*(?:Error:|Exception:)", value):
            raise RuntimeError(f"Activity action failed: {item['package']} {action}")
        power, activities = self.snapshot(label)
        if not state_gate(power, activities, item["package"])["valid"]:
            raise RuntimeError(f"Action Activity is not awake/unlocked/resumed: {item['package']}")
        self.stable_pids()

    def stable_pids(self) -> None:
        for item in self.identities:
            if self.pid(item["package"]) != str(item["pid"]):
                raise RuntimeError(f"Process changed during business capture: {item['package']}")

    def ui(self, index: int, stage: str, expected: tuple[str, ...] = (),
           forbidden: tuple[str, ...] = (), field: str | None = None) -> dict:
        item = self.identities[index]
        label = self.label(f"{stage}-{item['module']}")
        self.launch(item, self.user, label, reuse=True)
        power, activities = self.snapshot(label)
        state = state_gate(power, activities, item["package"])
        xml = self.xml(label, self.run_id)
        text, own = ui_text(xml, item["package"])
        field_nodes = []
        if field is not None:
            field_nodes = [node for node in ET.fromstring(xml).iter("node")
                           if node.get("package") == item["package"] and node.get("content-desc") == field]
            if len(field_nodes) != 1:
                raise RuntimeError(f"Expected exactly one visible business field {field}, got {len(field_nodes)}; "
                                   f"see {label}-ui.xml")
            # A log can contain commandId/title/room even if the business view
            # never changed. Only this explicitly selected TextView qualifies.
            text = field_nodes[0].get("text", "")
        checks = {"ownPackageUi": own, "expectedTexts": {value: value in text for value in expected},
                  "forbiddenTextsAbsent": {value: value not in text for value in forbidden}}
        self.stable_pids()
        self.log_alive()
        if not state["valid"] or not own or not all(checks["expectedTexts"].values()) or not all(checks["forbiddenTextsAbsent"].values()):
            raise RuntimeError(f"Business UI/state evidence failed: {stage} {item['package']}; see {label}-ui.xml")
        return {"package": item["package"], "pid": item["pid"], "uiXml": f"{label}-ui.xml",
                "state": state, "businessField": field,
                "businessFieldText": text if field is not None else None, **checks}


def package_snapshots(capture: Capture, directory: Path, user: str, identities: list[dict]) -> list[dict]:
    for item in PACKAGES:
        apk = apk_path(item)
        if not apk.is_file():
            raise FileNotFoundError(f"Build/install the four debug APKs before --execute: {apk}")
        built = directory / f"{item['module']}-built.apk"
        with apk.open("rb") as source, built.open("xb") as destination:
            shutil.copyfileobj(source, destination)
        record = dict(item, builtSha256=sha(built), builtSnapshot=built.name,
                      originalApk=str(apk), originalMtimeNs=apk.stat().st_mtime_ns)
        identities.append(record)
        if record["builtSha256"] != sha(apk):
            raise RuntimeError(f"Built APK changed while copying: {apk}")
        capture.issue("shell", "dumpsys", "package", item["package"], output=f"{item['module']}-package.txt")
        value = capture.issue("shell", "pm", "list", "packages", "-U", "--user", user, item["package"])["stdout"]
        match = re.search(rf"(?m)^package:{re.escape(item['package'])}\s+uid:(\d+)\s*$", value)
        if match is None:
            raise RuntimeError(f"Cannot identify package UID: {item['package']}")
        record["uid"] = int(match.group(1))
        paths = capture.issue("shell", "pm", "path", "--user", user, item["package"])["stdout"]
        remote = [line.removeprefix("package:").strip() for line in paths.splitlines() if line.startswith("package:")]
        if len(remote) != 1 or not re.fullmatch(r"/data/app/[A-Za-z0-9_./+=~-]+/base\.apk", remote[0]):
            raise RuntimeError(f"Expected one installed debug APK: {item['package']} {remote}")
        installed = directory / f"{item['module']}-installed.apk"
        capture.issue("pull", remote[0], str(installed))
        record["installedSnapshot"] = installed.name
        record["installedSha256"] = sha(installed)
        if record["builtSha256"] != record["installedSha256"]:
            raise RuntimeError(f"Built and installed APK differ: {item['package']}")
    if len({item["uid"] for item in identities}) != len(PACKAGES):
        raise RuntimeError("Four independent packages must have four distinct UIDs")
    return identities


def execute(args: argparse.Namespace, directory: Path, plan: dict) -> dict:
    capture = MeetingCapture(directory, args.adb, args.run_id, "", [])
    result = {"serial": SERIAL, "runId": args.run_id, "preparedOnly": False, "valid": False,
              "status": "NOT_RUN", "packages": [], "stages": [], "limits": plan["limits"],
              "sourceHashes": current_source_hashes(), "startedUtc": datetime.now(timezone.utc).isoformat()}
    failure = None
    device_ready = False
    try:
        adb = shutil.which(args.adb)
        if adb is None:
            raise ValueError(f"ADB executable not found: {args.adb}")
        capture.adb = adb
        validate_contract()
        if capture.issue("get-state")["stdout"].strip() != "device":
            raise RuntimeError("The fixed Xiaomi serial 925c23bb is not ready")
        device_ready = True
        capture.issue("shell", "getprop", output="getprop.txt")
        capture.issue("shell", "dumpsys", "battery", output="battery-before.txt")
        capture.issue("shell", "dumpsys", "thermalservice", output="thermal-before.txt", required=False)
        power, activities = capture.snapshot("before")
        result["beforeState"] = state_gate(power, activities)
        if not result["beforeState"]["valid"]:
            raise RuntimeError("Xiaomi must already be Awake and unlocked; this harness does not wake or unlock")
        user = capture.issue("shell", "am", "get-current-user")["stdout"].strip()
        if not re.fullmatch(r"\d+", user):
            raise RuntimeError(f"Cannot identify Android user: {user!r}")
        result["androidUser"] = int(user)
        identities = package_snapshots(capture, directory, user, result["packages"])
        capture.user, capture.identities = user, identities
        result["status"] = "FAILED"
        for item in identities[1:]:
            capture.force_stop(item, user, capture.label(f"initial-{item['module']}"))
        capture.force_stop(identities[0], user, capture.label("initial-server"))
        capture.start_log()
        for index in (0, 2, 3, 1):
            item = identities[index]
            capture.launch(item, user, capture.label(f"launch-{item['module']}"))
            item["pid"] = int(capture.pid(item["package"]))
            # Initial launches populate PID identities incrementally; avoid the
            # all-process stability gate until all four processes are running.
            label = capture.label(f"initial-ui-{item['module']}")
            power, activities = capture.snapshot(label)
            text, own = ui_text(capture.xml(label, args.run_id), item["package"])
            if not state_gate(power, activities, item["package"])["valid"] or not own:
                raise RuntimeError(f"Initial Activity UI/state is not valid: {item['package']}")
            if "clientId" in item and "已连接 (代次:" not in text:
                # UI-dump latency normally permits connection. An additional
                # bounded wait/re-dump permits a slow legitimate cold handshake.
                time.sleep(2)
                label = capture.label(f"connected-ui-{item['module']}")
                text, own = ui_text(capture.xml(label, args.run_id), item["package"])
                if not own or "已连接 (代次:" not in text:
                    raise RuntimeError(f"Client never showed Connected: {item['package']}")
        capture.stable_pids()
        processes = capture.issue("shell", "ps", "-A", "-o", "UID,PID,PPID,NAME", output="processes-start.txt")["stdout"]
        for item in identities:
            rows = [fields for line in processes.splitlines() if len(fields := line.split()) == 4
                    and fields[1] == str(item["pid"]) and fields[3] == item["package"]]
            item["processUid"] = int(rows[0][0]) if len(rows) == 1 and rows[0][0].isdigit() else None
            if item["processUid"] != item["uid"]:
                raise RuntimeError(f"Process UID does not match installed package: {item['package']}")
        capture.issue("shell", "dumpsys", "activity", "services", identities[0]["package"], output="bindings-start.txt")
        # Registration/onSubscribe are asynchronous. Actual received events in
        # every subsequent stage, rather than this delay, qualify subscribers.
        time.sleep(1)

        def stage(name: str, command_suffix: str) -> tuple[dict, str]:
            command_id = f"{args.run_id}-{command_suffix}"
            value = {"name": name, "commandId": command_id, "valid": False, "ui": [], "events": []}
            result["stages"].append(value)
            return value, command_id

        def show_round(name: str, suffix: str) -> None:
            value, command_id = stage(name, suffix)
            capture.action(1, "show", command_id)
            value["events"].append(capture.wait_event(command_id, "received", 2, "meeting.show", "client_2"))
            value["events"].append(capture.wait_event(command_id, "displayed", 2))
            for index in (1, 3):
                value["events"].append(capture.wait_event(command_id, "acknowledged", index, "meeting.displayed", "ALL"))
            for index in (2, 1, 3):
                value["ui"].append(capture.ui(index, name, (command_id, TITLE, ROOM), field="meeting-case-status"))
            value["isolation"] = capture.observe_absence(command_id, (1, 3), "meeting.show")
            value["valid"] = True
            print(f"PASS {name}: target UI and two application acknowledgements", flush=True)

        show_round("targeted-meeting", "show1")

        value, command_id = stage("broadcast-notice", "notice1")
        capture.action(1, "notice", command_id)
        for index in (2, 3):
            value["events"].append(capture.wait_event(command_id, "notice_visible", index, "meeting.notice", "ALL"))
            value["ui"].append(capture.ui(index, value["name"], (command_id, TITLE, ROOM), field="meeting-case-notice"))
        value["senderExcluded"] = capture.observe_absence(command_id, (1,), "meeting.notice")
        value["valid"] = True
        print("PASS broadcast-notice: two receiver UIs, sender excluded in observation", flush=True)

        value, command_id = stage("server-only-audit", "audit1")
        capture.action(1, "audit", command_id)
        value["events"].append(capture.wait_event(command_id, "audit_received", 0, "meeting.audit", "SERVER_ONLY"))
        value["ui"].append(capture.ui(0, value["name"], (command_id,)))
        value["isolation"] = capture.observe_absence(command_id, (1, 2, 3), "meeting.audit")
        for index in (2, 3):
            value["ui"].append(capture.ui(index, value["name"], forbidden=(command_id,), field="meeting-case-notice"))
        value["valid"] = True
        print("PASS server-only-audit: server UI, no receiver delivery in observation", flush=True)

        value, pause_id = stage("pause-and-missed-notice", "pause3")
        capture.action(3, "pause", pause_id)
        value["events"].append(capture.wait_event(pause_id, "subscription_paused", 3))
        value["ui"].append(capture.ui(3, "paused", ("开启订阅",)))
        time.sleep(0.5)
        missed_id = f"{args.run_id}-noticeMiss"
        value["missedCommandId"] = missed_id
        capture.action(1, "notice", missed_id)
        value["events"].append(capture.wait_event(missed_id, "notice_visible", 2, "meeting.notice", "ALL"))
        value["ui"].append(capture.ui(2, "missed-notice-receiver", (missed_id, TITLE, ROOM), field="meeting-case-notice"))
        value["isolation"] = capture.observe_absence(missed_id, (3,), "meeting.notice")
        value["ui"].append(capture.ui(3, "missed-notice-paused", forbidden=(missed_id,), field="meeting-case-notice"))
        value["valid"] = True
        print("PASS pause-and-missed-notice: active receiver succeeds, paused receiver misses", flush=True)

        value, resume_id = stage("resume-and-new-notice", "resume3")
        capture.action(3, "resume", resume_id)
        value["events"].append(capture.wait_event(resume_id, "subscription_requested", 3))
        time.sleep(0.5)
        restored_id = f"{args.run_id}-notice2"
        value["restoredCommandId"] = restored_id
        capture.action(1, "notice", restored_id)
        for index in (2, 3):
            value["events"].append(capture.wait_event(restored_id, "notice_visible", index, "meeting.notice", "ALL"))
            value["ui"].append(capture.ui(index, value["name"], (restored_id, TITLE, ROOM), field="meeting-case-notice"))
        value["noReplay"] = capture.observe_absence(missed_id, (3,), "meeting.notice")
        value["ui"].append(capture.ui(3, "no-replay", (restored_id,), (missed_id,), field="meeting-case-notice"))
        value["valid"] = True
        print("PASS resume-and-new-notice: fresh delivery recovers, missed notice is not replayed", flush=True)

        show_round("recovered-meeting", "show2")
        result["sourceUnchanged"] = result["sourceHashes"] == current_source_hashes()
        result["builtApksUnchanged"] = all(sha(apk_path(item)) == item["builtSha256"] for item in identities)
        capture.stable_pids()
        capture.log_alive()
        result["logcatStayedRunning"] = True
        raw = (directory / "case-logcat.txt").read_text(encoding="utf-8", errors="replace")
        result["fatalLogcat"] = "FATAL EXCEPTION" in raw or "Fatal signal" in raw
        result["logcatLossReported"] = bool(re.search(r"(?i)(?:dropped\s+\d+\s+(?:lines|messages)|chatty.*expire)", raw))
        captured_pids = {item["pid"] for item in identities}
        result["businessErrors"] = [item for item in capture.events() if item["_pid"] in captured_pids and
                                    (item["event"] == "connection_lost" or
                                     (item["commandId"].startswith(args.run_id + "-") and item["event"] in ("error", "rejected")))]
        if not result["sourceUnchanged"] or not result["builtApksUnchanged"] or result["fatalLogcat"] or result["logcatLossReported"] or result["businessErrors"]:
            raise RuntimeError("Source/APK identity, crash, Logcat-loss or business-error gate failed")
        result["valid"], result["status"] = True, "PASS"
    except Exception as error:
        failure = error
        result["error"] = f"{type(error).__name__}: {error}"
    finally:
        try:
            capture.stop_log()
            result["caseEvents"] = capture.events()
            if result["valid"]:
                # Qualify the final, closed stream too: a late callback failure
                # must not be omitted between the last stage check and stop.
                captured_pids = {item["pid"] for item in result["packages"]}
                result["businessErrors"] = [item for item in result["caseEvents"] if item["_pid"] in captured_pids and
                                            (item["event"] == "connection_lost" or
                                             (item["commandId"].startswith(args.run_id + "-") and item["event"] in ("error", "rejected")))]
                raw = (directory / "case-logcat.txt").read_text(encoding="utf-8", errors="replace")
                result["fatalLogcat"] = "FATAL EXCEPTION" in raw or "Fatal signal" in raw
                result["logcatLossReported"] = bool(re.search(r"(?i)(?:dropped\s+\d+\s+(?:lines|messages)|chatty.*expire)", raw))
                if result["businessErrors"] or result["fatalLogcat"] or result["logcatLossReported"]:
                    result["valid"], result["status"] = False, "FAILED"
                    failure = RuntimeError("Closed Logcat stream contains a business error, crash or reported loss")
                    result["error"] = str(failure)
            for name, arguments in (
                ("battery-after.txt", ("shell", "dumpsys", "battery")),
                ("thermal-after.txt", ("shell", "dumpsys", "thermalservice")),
                ("power-after.txt", ("shell", "dumpsys", "power")),
                ("activities-after.txt", ("shell", "dumpsys", "activity", "activities")),
                ("bindings-final.txt", ("shell", "dumpsys", "activity", "services", PACKAGES[0]["package"])),
                ("processes-after.txt", ("shell", "ps", "-A", "-o", "UID,PID,PPID,NAME")),
            ):
                if not device_ready:
                    break
                try:
                    capture.issue(*arguments, output=name, required=False)
                except Exception as cleanup_error:
                    result.setdefault("captureErrors", []).append(f"{name}: {cleanup_error}")
        except Exception as cleanup_error:
            result.setdefault("captureErrors", []).append(str(cleanup_error))
            result["valid"], result["status"] = False, "FAILED"
            if failure is None:
                failure = cleanup_error
        capture.journal.close()
        result["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        result["artifacts"] = [{"path": path.name, "bytes": path.stat().st_size, "sha256": sha(path)}
                               for path in sorted(directory.iterdir()) if path.is_file()]
        save_json(directory / "capture-result.json", result)
    if failure is not None:
        raise failure
    return result


def main(args: argparse.Namespace) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.run_id):
        raise ValueError("Run ID must use 1-64 ASCII letters, digits, underscore or hyphen")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", args.date):
        raise ValueError("Date must be YYYY-MM-DD")
    datetime.strptime(args.date, "%Y-%m-%d")
    directory = ROOT / f"docs/benchmarks/runs/{args.date}-xiaomi14ultra" / f"multi-app-case-{args.run_id}"
    directory.mkdir(parents=True, exist_ok=False)
    plan = {"serial": SERIAL, "captureDay": args.date, "runId": args.run_id,
            "preparedOnly": not args.execute, "status": "PREPARED" if not args.execute else "PENDING",
            "createdUtc": datetime.now(timezone.utc).isoformat(), "sourceHashes": current_source_hashes(),
            "packages": [dict(item, builtApk=str(apk_path(item)), localApkAvailable=apk_path(item).is_file(),
                              localSha256=sha(apk_path(item)) if apk_path(item).is_file() else None) for item in PACKAGES],
            "actions": {"extra": "demo_case_action", "values": ["show", "notice", "audit", "pause", "resume"],
                        "commandIdExtra": "demo_case_command_id", "titleExtra": "demo_case_title", "roomExtra": "demo_case_room",
                        "title": TITLE, "room": ROOM, "debugOnly": True,
                        "path": "Existing Activity onNewIntent -> same business/UI function as visible button"},
            "plan": ["Require fixed Xiaomi awake/unlocked, four distinct installed UIDs, built/installed APK hash equality.",
                     "Cold-launch central server, meeting screen, operations screen, then dispatcher; capture UI and package/process identities.",
                     "Targeted show: require meeting-screen UI then acknowledged events/UI in dispatcher and operations apps.",
                     "Broadcast notice: require both receiving app UIs, observe no sender receive event for 3 seconds.",
                     "Server-only audit: require server audit event/UI, observe no client business receive event for 3 seconds.",
                     "Pause operations Flow, send notice: active meeting screen receives, paused operations app misses.",
                     "Resume operations Flow, send fresh notice: both apps receive; missed notice is not replayed in capture.",
                     "Run a second targeted meeting/acknowledgement round and reject process/source/APK changes or Logcat loss."],
            "limits": ["Functional use-case capture, not an RTT/performance comparison or hardware meeting-display test.",
                       "Business displayed/acknowledged means Activity TextView assignment and app receipt, not rendered-frame confirmation.",
                       "Show/notice UI checks select one visible meeting-case-status/meeting-case-notice TextView; generic log text cannot qualify delivery.",
                       "Route return strings never substitute for receiving UI/application events.",
                       "Negative delivery/isolation results apply only to the stated 3-second observation window and command IDs.",
                       "Resume is qualified by a fresh received notice, not subscription_requested alone.",
                       "No persistence, durable audit, offline delivery, exactly-once processing or receiver restart guarantee is tested.",
                       "Foreground/keyguard states are boundary snapshots; simultaneous/continuous foreground is not claimed.",
                       "DEBUG intent actions share visible UI business methods; no direct Binder or synthetic callback injection."]}
    save_json(directory / "capture-plan.json", plan)
    if not args.execute:
        print(f"PREPARED {directory}; no ADB/device commands executed. Rebuild/install, then use a new --run-id with --execute.")
        return
    result = execute(args, directory, plan)
    print(f"PASS multi-app meeting case serial={SERIAL} stages={len(result['stages'])} evidence={directory}")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", default=f"meeting-{datetime.now():%H%M%S}-{uuid.uuid4().hex[:8]}")
    parser.add_argument("--date", default=f"{datetime.now():%Y-%m-%d}", help="Evidence directory date (default: local current date)")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--execute", action="store_true", help="Run against the already installed debug APKs on Xiaomi 925c23bb only")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        main(arguments())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, ET.ParseError) as error:
        raise SystemExit(f"ERROR: {error}") from error
