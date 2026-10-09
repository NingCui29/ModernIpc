"""Prepare/capture three real four-APK business scenarios on Xiaomi 925c23bb.

Preparation is the default and performs no ADB command. --execute requires the
four rebuilt debug APKs to be installed and the fixed Xiaomi awake/unlocked.
Actions enter the existing Activities through DEBUG-only Intent extras and use
the same business handlers as their visible buttons. Work-order, page-settings
and manually supplied temperature sample events are qualified against real
package/PID identities and one explicitly selected business TextView per app.
This is functional evidence, not a performance test or real sensor sampling.
"""

import argparse
import json
import re
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

from capture_cross_package_smoke import (
    PACKAGES, ROOT, SERIAL, apk_path, save_json, sha, source_hashes,
    state_gate, ui_text,
)
from capture_multi_app_case import MeetingCapture, package_snapshots


TAG = "IPC_SCENARIO_CASE"
WAIT_SECONDS = 15
ABSENCE_SECONDS = 3
SCENARIOS = {"work-order", "settings", "telemetry"}
EXPECTED_HARDWARE = {"ro.serialno": SERIAL, "ro.product.manufacturer": "Xiaomi",
                     "ro.product.brand": "Xiaomi", "ro.product.device": "aurora", "ro.product.model": "24031PN0DC"}
KINDS = {"order.assign", "order.accepted", "order.completed", "settings.apply",
         "settings.applied", "telemetry.sample", "telemetry.alert"}
SCENARIO_KINDS = {"work-order": {"order.assign", "order.accepted", "order.completed"},
                  "settings": {"settings.apply", "settings.applied"},
                  "telemetry": {"telemetry.sample", "telemetry.alert"}}
EVENTS = {"send", "route_result", "received", "applied", "acknowledged", "completed",
          "sample_visible", "alert_visible", "stale_ignored", "rejected", "error", "connection_lost"}
DELIVERY_EVENTS = {"received", "applied", "acknowledged", "completed", "sample_visible", "alert_visible"}
EVENT_LINE = re.compile(
    r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+(\d+)\s+"
    r"[VDIWEF]\s+IPC_SCENARIO_CASE\s*:\s*(\{.*\})\s*$"
)


def current_source_hashes() -> list[dict]:
    values = {item["path"]: item for item in source_hashes()}
    for filename in (__file__, str(Path(__file__).with_name("capture_multi_app_case.py"))):
        path = Path(filename).resolve()
        name = path.relative_to(ROOT).as_posix()
        values[name] = {"path": name, "sha256": sha(path)}
    return [values[name] for name in sorted(values)]


def validate_contract() -> None:
    # Do not inherit the older cold-smoke check's subscription-success wording:
    # this harness qualifies real business receipt, not a subscription log row.
    for item in PACKAGES:
        gradle = (ROOT / item["module"] / "build.gradle.kts").read_text(encoding="utf-8-sig")
        manifest = (ROOT / item["module"] / "src/main/AndroidManifest.xml").read_text(encoding="utf-8-sig")
        if not re.search(rf'applicationId\s*=\s*"{re.escape(item["package"])}"', gradle):
            raise ValueError(f"Package contract changed: {item['module']}")
        if f'android:name="{item["activity"]}"' not in manifest:
            raise ValueError(f"Activity contract changed: {item['module']}")
    kotlin = "\n".join(path.read_text(encoding="utf-8-sig")
                       for path in ROOT.glob("*/src/main/**/*.kt"))
    for expected in (TAG, "multi-app-scenarios", *SCENARIOS, *KINDS,
                     "demo_scenario", "demo_scenario_action", "demo_scenario_command_id",
                     "demo_scenario_title", "demo_scenario_detail", "demo_scenario_revision", "demo_scenario_value"):
        if expected not in kotlin:
            raise ValueError(f"Scenario payload/evidence contract missing: {expected}")


def hardware_identity(getprop: str) -> dict:
    values = dict(re.findall(r"(?m)^\[([^\]]+)\]: \[([^\]]*)\]\s*$", getprop))
    result = {key: values.get(key) for key in EXPECTED_HARDWARE}
    result["fingerprint"] = values.get("ro.build.fingerprint")
    result["valid"] = all(result[key] == expected for key, expected in EXPECTED_HARDWARE.items())
    if not result["valid"]:
        raise RuntimeError(f"Fixed Xiaomi hardware identity differs from expected serial/model: {result}")
    return result


def parse_events(raw: str, identities: list[dict]) -> list[dict]:
    """Keep only complete JSON lines; never infer an application's PID/clientId."""
    records = []
    for number, line in enumerate(raw.splitlines(keepends=True), 1):
        if not line.endswith("\n"):
            continue  # A live writer may still be writing the final line.
        match = EVENT_LINE.match(line.rstrip("\r\n"))
        if match is None:
            continue
        try:
            data = json.loads(match.group(3))
        except json.JSONDecodeError as error:
            raise RuntimeError(f"Malformed {TAG} JSON at case-logcat.txt:{number}: {error}") from error
        if not isinstance(data, dict) or not all(isinstance(data.get(key), str) for key in
                                                ("event", "clientId", "scenario", "commandId", "kind", "target", "detail")):
            raise RuntimeError(f"Malformed {TAG} string fields at case-logcat.txt:{number}")
        if data["scenario"] not in SCENARIOS or data["event"] not in EVENTS:
            raise RuntimeError(f"Unknown {TAG} scenario/event at case-logcat.txt:{number}")
        if type(data.get("revision")) is not int or type(data.get("value")) is not int or \
                not -(2 ** 31) <= data["value"] < 2 ** 31 or not 0 <= data["revision"] < 2 ** 63:
            raise RuntimeError(f"Malformed {TAG} integer fields at case-logcat.txt:{number}")
        if data["event"] not in {"connection_lost", "error", "rejected"} and (
                data["kind"] not in SCENARIO_KINDS[data["scenario"]] or data["revision"] <= 0 or
                data["target"] not in {"ALL", "client_1", "client_2", "client_3"} or
                not re.fullmatch(r"[A-Za-z0-9_-]{1,96}", data["commandId"])):
            raise RuntimeError(f"Malformed {TAG} business fields at case-logcat.txt:{number}")
        pid = int(match.group(1))
        identity = next((item for item in identities if item.get("pid") == pid), None)
        if identity is not None and data["clientId"] != identity.get("clientId"):
            raise RuntimeError(f"{TAG} clientId differs from captured process at case-logcat.txt:{number}")
        records.append(dict(data, _line=number, _pid=pid, _tid=int(match.group(2))))
    return records


class ScenarioCapture(MeetingCapture):
    def events(self) -> list[dict]:
        path = self.directory / "case-logcat.txt"
        return parse_events(path.read_text(encoding="utf-8", errors="replace"), self.identities) if path.is_file() else []

    def action(self, index: int, scenario: str, action: str, command_id: str,
               title: str, detail: str, revision: int, value: int) -> None:
        item = self.identities[index]
        label = self.label(f"{scenario}-{action}-{item['module']}")
        response = self.issue("shell", "am", "start", "--user", self.user, "-W",
                              "--activity-reorder-to-front", "--activity-single-top", "-n",
                              f"{item['package']}/{item['activity']}",
                              "--es", "demo_scenario", scenario,
                              "--es", "demo_scenario_action", action,
                              "--es", "demo_scenario_command_id", command_id,
                              "--es", "demo_scenario_title", title,
                              "--es", "demo_scenario_detail", detail,
                              "--el", "demo_scenario_revision", str(revision),
                              "--ei", "demo_scenario_value", str(value), output=f"{label}-intent.txt")
        output = response["stdout"] + response["stderr"]
        statuses = re.findall(r"(?m)^\s*Status:\s*(\S+)", output)
        if re.search(r"(?m)^\s*(?:Error:|Exception:)", output) or any(status != "ok" for status in statuses):
            raise RuntimeError(f"Activity scenario action failed: {item['package']} {scenario}/{action}")
        power, activities = self.snapshot(label)
        if not state_gate(power, activities, item["package"])["valid"]:
            raise RuntimeError(f"Scenario Activity is not awake/unlocked/resumed: {item['package']}")
        self.stable_pids()

    def require_event(self, command_id: str, scenario: str, event: str, index: int,
                      kind: str, target: str, revision: int, value: int,
                      detail_contains: str | None = None, after_line: int = 0) -> dict:
        started = time.monotonic()
        while time.monotonic() - started < WAIT_SECONDS:
            self.log_alive()
            self.stable_pids()
            records = [item for item in self.selected(command_id, event, index, kind)
                       if item["scenario"] == scenario and item["target"] == target and item["_line"] > after_line
                       and (detail_contains is None or detail_contains in item["detail"])]
            if records:
                record = records[-1]
                if record["revision"] != revision or record["value"] != value:
                    raise RuntimeError(f"Scenario event payload differs from sent payload: {record}")
                return record
            errors = [item for item in self.selected(command_id) if item["event"] in ("error", "rejected")]
            if errors:
                raise RuntimeError(f"Scenario action failed for {command_id}: {errors}")
            time.sleep(0.1)
        raise RuntimeError(f"Missing {scenario}/{event}/{kind} from {self.identities[index]['package']} for {command_id}")

    def scenario_ui(self, index: int, scenario: str, stage: str, expected: tuple[str, ...],
                    forbidden: tuple[str, ...] = ()) -> dict:
        # Switching the visible card is a local selection only. It must not
        # resubmit a command or let a generic log row stand in for business UI.
        self.action(index, scenario, "select", f"{self.run_id}-view{self.sequence}",
                    "ScenarioView", "SelectionOnly", 1, 0)
        return self.ui(index, stage, expected, forbidden, field=f"scenario-{scenario}-status")

    def no_delivery(self, command_id: str, scenario: str, indices: tuple[int, ...], kind: str,
                    also_no_send: bool = False) -> dict:
        started = time.monotonic()
        while time.monotonic() - started < ABSENCE_SECONDS:
            self.log_alive()
            self.stable_pids()
            unexpected = [item for index in indices for item in self.selected(command_id, client_index=index, kind=kind)
                          if item["scenario"] == scenario and
                          (item["event"] in DELIVERY_EVENTS or also_no_send and item["event"] == "send")]
            if unexpected:
                raise RuntimeError(f"Unexpected {scenario}/{kind} delivery: {unexpected}")
            time.sleep(0.1)
        return {"commandId": command_id, "scenario": scenario, "kind": kind,
                "clientIds": [self.identities[index]["clientId"] for index in indices],
                "observedSeconds": time.monotonic() - started, "unexpectedDeliveryEvents": 0,
                "alsoNoSendEvent": also_no_send,
                "limit": "Only this bounded observation window; not a permanent non-delivery guarantee"}


def qualify_log(capture: ScenarioCapture, result: dict) -> None:
    raw = (capture.directory / "case-logcat.txt").read_text(encoding="utf-8", errors="replace")
    captured_pids = {item["pid"] for item in result["packages"] if "pid" in item}
    result["fatalLogcat"] = "FATAL EXCEPTION" in raw or "Fatal signal" in raw
    result["logcatLossReported"] = bool(re.search(r"(?i)(?:dropped\s+\d+\s+(?:lines|messages)|chatty.*expire)", raw))
    result["businessErrors"] = [item for item in capture.events() if item["_pid"] in captured_pids and
                                (item["event"] == "connection_lost" or
                                 (item["commandId"].startswith(capture.run_id + "-") and item["event"] in ("error", "rejected")))]
    if result["fatalLogcat"] or result["logcatLossReported"] or result["businessErrors"]:
        raise RuntimeError("Crash, reported Logcat loss or scenario business-error gate failed")


def execute(args: argparse.Namespace, directory: Path, plan: dict) -> dict:
    capture = ScenarioCapture(directory, args.adb, args.run_id, "", [])
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
        result["hardwareIdentity"] = hardware_identity(capture.issue("shell", "getprop", output="getprop.txt")["stdout"])
        capture.issue("shell", "dumpsys", "battery", output="battery-before.txt")
        capture.issue("shell", "dumpsys", "thermalservice", output="thermal-before.txt", required=False)
        power, activities = capture.snapshot("before")
        result["beforeState"] = state_gate(power, activities)
        if not result["beforeState"]["valid"]:
            raise RuntimeError("Xiaomi must already be Awake and unlocked; no wake/unlock is performed")
        user = capture.issue("shell", "am", "get-current-user")["stdout"].strip()
        if not re.fullmatch(r"\d+", user):
            raise RuntimeError(f"Cannot identify current Android user: {user!r}")
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
            label = capture.label(f"initial-ui-{item['module']}")
            power, activities = capture.snapshot(label)
            text, own = ui_text(capture.xml(label, args.run_id), item["package"])
            if not state_gate(power, activities, item["package"])["valid"] or not own:
                raise RuntimeError(f"Initial Activity UI/state is invalid: {item['package']}")
            if "clientId" in item and "已连接 (代次:" not in text:
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
                raise RuntimeError(f"Process UID differs from installed package: {item['package']}")
        capture.issue("shell", "dumpsys", "activity", "services", identities[0]["package"], output="bindings-start.txt")
        time.sleep(1)  # Receipt events below, not this delay, qualify active subscribers.

        def stage(name: str, scenario: str, command_id: str) -> dict:
            value = {"name": name, "scenario": scenario, "commandId": command_id, "valid": False, "events": [], "ui": []}
            result["stages"].append(value)
            return value

        order_id, order_title, order_detail = f"{args.run_id}-order", "RepairDisplay", "ReplaceCable"
        value = stage("work-order-accepted", "work-order", order_id)
        capture.action(1, "work-order", "assign", order_id, order_title, order_detail, 1, 0)
        value["events"].append(capture.require_event(order_id, "work-order", "received", 2, "order.assign", "client_2", 1, 0))
        value["events"].append(capture.require_event(order_id, "work-order", "applied", 2, "order.assign", "client_2", 1, 0))
        for index in (1, 3):
            value["events"].append(capture.require_event(order_id, "work-order", "acknowledged", index, "order.accepted", "ALL", 1, 0))
        for index, label in ((1, "执行屏已接受工单"), (2, "工单已接受"), (3, "已观测工单接受")):
            value["ui"].append(capture.scenario_ui(index, "work-order", value["name"],
                                                  (order_id, order_title, order_detail, label, "revision：1", "value：0")))
        value["targetIsolation"] = capture.no_delivery(order_id, "work-order", (1, 3), "order.assign")
        value["valid"] = True
        print("PASS work-order-accepted: target business UI and two acceptance observers", flush=True)

        value = stage("work-order-completed", "work-order", order_id)
        capture.action(2, "work-order", "complete", order_id, order_title, order_detail, 1, 0)
        for index in (2, 1, 3):
            value["events"].append(capture.require_event(order_id, "work-order", "completed", index, "order.completed", "ALL", 1, 0))
        for index, label in ((1, "执行屏已完成工单"), (2, "工单已完成"), (3, "已观测工单完成")):
            value["ui"].append(capture.scenario_ui(index, "work-order", value["name"],
                                                  (order_id, order_title, order_detail, label, "revision：1", "value：0")))
        value["valid"] = True
        print("PASS work-order-completed: all three business UIs show the same completed order", flush=True)

        value = stage("work-order-completed-repeated-assignment", "work-order", order_id)
        before_replay = max((item["_line"] for item in capture.events()), default=0)
        value["eventsAfterLine"] = before_replay
        capture.action(1, "work-order", "assign", order_id, order_title, order_detail, 1, 0)
        value["events"].append(capture.require_event(order_id, "work-order", "received", 2, "order.assign", "client_2", 1, 0,
                                                     after_line=before_replay))
        value["events"].append(capture.require_event(order_id, "work-order", "send", 2, "order.completed", "ALL", 1, 0,
                                                     after_line=before_replay))
        for index in (1, 3):
            value["events"].append(capture.require_event(order_id, "work-order", "completed", index, "order.completed", "ALL", 1, 0,
                                                         after_line=before_replay))
        for index, label in ((1, "执行屏已完成工单"), (2, "工单已完成"), (3, "已观测工单完成")):
            value["ui"].append(capture.scenario_ui(index, "work-order", value["name"],
                                                  (order_id, order_title, order_detail, label, "revision：1", "value：0")))
        value["valid"] = True
        print("PASS work-order-completed-repeated-assignment: a repeated same-payload assignment returns completion without UI regression", flush=True)

        for revision, theme, size in ((1, "light", 14), (2, "dark", 18)):
            command_id = f"{args.run_id}-settings{revision}"
            value = stage(f"settings-revision-{revision}", "settings", command_id)
            capture.action(1, "settings", "apply", command_id, "CardAppearance", theme, revision, size)
            for index in (2, 3):
                value["events"].append(capture.require_event(command_id, "settings", "applied", index, "settings.apply", "ALL", revision, size,
                                                             f"theme={theme}; size={size}"))
                value["events"].append(capture.require_event(command_id, "settings", "send", index, "settings.applied", "client_1", revision, size))
                value["events"].append(capture.require_event(command_id, "settings", "acknowledged", 1, "settings.applied", "client_1", revision, size,
                                                             f"from=client_{index}"))
                value["ui"].append(capture.scenario_ui(index, "settings", value["name"],
                                                      (command_id, "CardAppearance", theme, "配置已应用（本页面）",
                                                       f"revision：{revision}", f"value：{size}")))
            value["ui"].append(capture.scenario_ui(1, "settings", value["name"],
                                                  (command_id, "配置确认完成：client_2、client_3", f"revision：{revision}", f"value：{size}")))
            value["valid"] = True
            print(f"PASS settings-revision-{revision}: two independent page applications and two source-qualified acknowledgements", flush=True)

        for action, temperature, revision in (("normal", 25, 1), ("hot", 35, 2)):
            command_id = f"{args.run_id}-temperature{temperature}"
            value = stage(f"telemetry-{action}", "telemetry", command_id)
            capture.action(2, "telemetry", action, command_id, "DemoTemperature", "PresetButtonSample", revision, temperature)
            value["events"].append(capture.require_event(command_id, "telemetry", "sample_visible", 3, "telemetry.sample", "client_3", revision, temperature))
            value["ui"].append(capture.scenario_ui(3, "telemetry", value["name"],
                                                  (command_id, "DemoTemperature", f"最近样本：{temperature}°C（按钮演示值）",
                                                   f"revision：{revision}", f"value：{temperature}")))
            value["sampleIsolation"] = capture.no_delivery(command_id, "telemetry", (1, 2), "telemetry.sample")
            if action == "hot":
                value["events"].append(capture.require_event(command_id, "telemetry", "send", 3, "telemetry.alert", "ALL", revision, temperature))
                for index in (1, 2):
                    value["events"].append(capture.require_event(command_id, "telemetry", "alert_visible", index, "telemetry.alert", "ALL", revision, temperature))
                    value["ui"].append(capture.scenario_ui(index, "telemetry", value["name"],
                                                          (command_id, "DemoTemperature", "高温告警：35°C（阈值30°C）",
                                                           f"revision：{revision}", f"value：{temperature}")))
            else:
                value["noNormalAlert"] = capture.no_delivery(command_id, "telemetry", (1, 2, 3), "telemetry.alert", also_no_send=True)
            value["valid"] = True
            print(f"PASS telemetry-{action}: manually supplied sample UI" + (" and two alert UIs" if action == "hot" else "; no alert in observation"), flush=True)

        value = stage("completed-order-retained-after-scenario-navigation", "work-order", order_id)
        for index, label in ((1, "执行屏已完成工单"), (2, "工单已完成"), (3, "已观测工单完成")):
            value["ui"].append(capture.scenario_ui(index, "work-order", value["name"], (order_id, label)))
        value["valid"] = True
        print("PASS completed-order-retained-after-scenario-navigation: completion is preserved in this session", flush=True)

        result["sourceUnchanged"] = result["sourceHashes"] == current_source_hashes()
        result["builtApksUnchanged"] = all(sha(apk_path(item)) == item["builtSha256"] for item in identities)
        capture.stable_pids()
        capture.log_alive()
        result["logcatStayedRunning"] = True
        qualify_log(capture, result)
        if not result["sourceUnchanged"] or not result["builtApksUnchanged"]:
            raise RuntimeError("Source/APK identity changed during scenario capture")
        result["valid"], result["status"] = True, "PASS"
    except Exception as error:
        failure = error
        result["error"] = f"{type(error).__name__}: {error}"
    finally:
        try:
            capture.stop_log()
            result["caseEvents"] = capture.events()
            if result["valid"]:
                qualify_log(capture, result)  # Include any late errors in the final closed stream.
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
                result["error"] = f"{type(cleanup_error).__name__}: {cleanup_error}"
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
    directory = ROOT / f"docs/benchmarks/runs/{args.date}-xiaomi14ultra" / f"multi-app-scenarios-{args.run_id}"
    directory.mkdir(parents=True, exist_ok=False)
    plan = {"serial": SERIAL, "expectedHardware": EXPECTED_HARDWARE, "captureDay": args.date, "runId": args.run_id,
            "preparedOnly": not args.execute, "status": "PREPARED" if not args.execute else "PENDING",
            "createdUtc": datetime.now(timezone.utc).isoformat(), "sourceHashes": current_source_hashes(),
            "packages": [dict(item, builtApk=str(apk_path(item)), localApkAvailable=apk_path(item).is_file(),
                              localSha256=sha(apk_path(item)) if apk_path(item).is_file() else None) for item in PACKAGES],
            "actions": {"extras": ["demo_scenario", "demo_scenario_action", "demo_scenario_command_id",
                                    "demo_scenario_title", "demo_scenario_detail", "demo_scenario_revision", "demo_scenario_value"],
                        "values": {"work-order": ["assign", "complete"], "settings": ["apply"], "telemetry": ["normal", "hot"]},
                        "select": "Select a visible scenario only; no business message is sent", "debugOnly": True},
            "plan": ["Require fixed Xiaomi awake/unlocked, four distinct installed UIDs, built/installed APK hash equality.",
                     "Cold-launch server, executor/settings/sampler, observer/settings/alert app, then dispatcher; capture real PID/UID mappings.",
                     "Work order: targeted assignment, receiver UI application, two acceptance observers, then same-command completion in all three UIs.",
                     "Replay the same completed assignment ID/payload/revision; require newly emitted completion receipts and no business UI regression.",
                     "Settings: light/14 revision 1 then dark/18 revision 2, each with two receiving page applications and two distinct source-qualified ACKs.",
                     "Telemetry: button demo sample 25 shows in observer with no alert in observation; 35 produces observer routing plus alerts in dispatcher and sampler.",
                     "Select the work-order card again and require the prior completion state to remain after scenario navigation.",
                     "Reject source/APK/PID changes, any connection loss/business error/crash or reported Logcat loss; retain failed runs."],
            "limits": ["Functional cross-package scenarios, not RTT/performance comparison or real hardware/temperature sensor integration.",
                       "Temperature 25/35 is supplied by demo button input; no sensor reading or actual device thermal condition is claimed.",
                       "Application events/status mean in-memory business TextView assignment and callback receipt, not rendered-frame verification.",
                       "Settings events qualify the page's own theme/font application handlers; UI XML cannot verify rendered colors/font sizes or system-wide settings.",
                       "Every receiver UI gate selects exactly one scenario-<scenario>-status TextView; generic logs cannot qualify a business update.",
                       "Route result strings do not substitute for receiving UI or application acknowledgement events.",
                       "Negative isolation/no-alert results apply only to each stated 3-second observation window and command ID.",
                       "State snapshots are capture boundaries; simultaneous/continuous foreground is not claimed.",
                       "No durable work order, process-restart recovery, offline delivery, exactly-once effects or acknowledgement persistence is tested.",
                       "Completion is checked after one identical assignment replay and scenario navigation; arbitrary reordering and stale configuration rejection are not exercised.",
                       "No install, clear-data, unlock, settings mutation, direct Binder call or synthetic callback is performed."]}
    save_json(directory / "capture-plan.json", plan)
    if not args.execute:
        print(f"PREPARED {directory}; no ADB/device commands executed. Rebuild/install, then use a new --run-id with --execute.")
        return
    result = execute(args, directory, plan)
    print(f"PASS multi-app scenarios serial={SERIAL} stages={len(result['stages'])} evidence={directory}")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", default=f"scenarios-{datetime.now():%H%M%S}-{uuid.uuid4().hex[:8]}")
    parser.add_argument("--date", default=f"{datetime.now():%Y-%m-%d}", help="Evidence directory date (default: local current date)")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--execute", action="store_true", help="Capture already installed debug APKs on Xiaomi 925c23bb only")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        main(arguments())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, ET.ParseError) as error:
        raise SystemExit(f"ERROR: {error}") from error
