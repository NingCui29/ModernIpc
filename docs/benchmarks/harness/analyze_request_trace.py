"""Analyze diagnostic request stages and optionally export Perfetto context.

Buffered log contract (extra stage pairs can be added using --stage-pair):
IpcReqTrace: EVT run=trace1 side=client stage=client_entry ns=123 pid=456
tid=457 requestId=1 generation=1 callback=789

Callback tokens are process-local. --single-client permits requestId correlation
only for one client callback/generation and one server callback in a run. It is
intended for controlled suspend-RPC diagnostics, not direct RPC or multi-client
traffic. No wire protocol change is assumed. Diagnostic timings are not evidence
of tracing-disabled performance.
"""

import argparse
import csv
import re
import json
import hashlib
import subprocess
from collections import defaultdict
from pathlib import Path
from qualify_foreground import qualify_foreground


PAIRS = {
    "client_resolve": ("client_entry", "client_resolved"),
    "client_register": ("client_resolved", "client_registered"),
    "client_encode": ("client_registered", "client_send"),
    "request_transport": ("client_send", "server_receive"),
    "server_admission": ("server_receive", "server_enqueued"),
    "server_queue": ("server_enqueued", "server_job_start"),
    "server_dispatch": ("server_receive", "server_job_start"),
    "server_business": ("server_job_start", "server_business_done"),
    "server_reply_encode": ("server_business_done", "server_reply"),
    "reply_transport": ("server_reply", "client_reply"),
    "client_resume": ("client_reply", "client_resume"),
    "total": ("client_entry", "client_resume"),
}
CLIENT_STAGES = {"client_entry", "client_resolved", "client_registered", "client_send", "client_reply", "client_resume"}
SERVER_STAGES = {"server_receive", "server_enqueued", "server_job_start", "server_business_done", "server_reply"}


def message_fields(line: str) -> dict[str, str]:
    return dict(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)=([^\s]+)", line))


def validate_benchmark_artifacts(directory: Path, run: str, enabled: bool | None = None) -> dict:
    """Require complete benchmark, RTT, buffer and cross-process evidence; never alter raw files."""
    log = (directory / "logcat.txt").read_text(encoding="utf-8-sig")
    if "FAILED" in log or "FATAL EXCEPTION" in log:
        raise ValueError("Failure in Logcat; reject the request trace")
    selected = [line for line in log.splitlines() if "IpcTraceBench" in line and f"run={run} " in line]
    passes = [line for line in selected if re.search(r"\bPASS\s+run=", line)]
    done = [message_fields(line) for line in selected if re.search(r"\bDONE\s+run=", line)]
    if len(passes) != 2 or len(done) != 1:
        raise ValueError(f"Expected 2 PASS and 1 DONE, found {len(passes)} and {len(done)}")
    completed = done[0]
    if any(completed.get(key) != value for key, value in
           (("passed", "true"), ("checks", "2"), ("count", "1000"), ("dropped", "0"))):
        raise ValueError(f"Invalid DONE fields: {completed}")
    if completed.get("trace") not in ("true", "false"):
        raise ValueError("Missing trace-enabled flag in DONE")
    measured_enabled = completed["trace"] == "true"
    if enabled is not None and enabled != measured_enabled:
        raise ValueError("Intent trace mode and measured trace mode differ")
    serial = [message_fields(line) for line in passes if " serialEcho " in line]
    buffers = [message_fields(line) for line in passes if " traceBuffers " in line]
    if len(serial) != 1 or len(buffers) != 1:
        raise ValueError("Missing serialEcho/traceBuffers PASS")
    if any(serial[0].get(key) != value for key, value in
           (("count", "1000"), ("failures", "0"), ("pending", "0"), ("requests", "0"),
            ("trace", str(measured_enabled).lower()))):
        raise ValueError("Serial echo did not finish with zero failures/resources")
    with (directory / f"trace-stats-{run}.csv").open(encoding="utf-8-sig", newline="") as stream:
        stats_rows = list(csv.DictReader(stream))
    if len(stats_rows) != 2 or {row.get("side") for row in stats_rows} != {"client", "server"}:
        raise ValueError("Expected exactly one client/server trace-stats row")
    counts = {}
    for row in stats_rows:
        side = row["side"]
        expected = (len(CLIENT_STAGES) if side == "client" else len(SERVER_STAGES)) * 1000 if measured_enabled else 0
        if row.get("enabled") != str(measured_enabled).lower() or int(row["dropped"]) != 0 or int(row["events"]) != expected:
            raise ValueError(f"Invalid trace buffer statistics: {row}")
        counts[side] = expected
        if completed.get(f"{side}Events") != str(expected) or buffers[0].get(f"{side}Events") != str(expected) or buffers[0].get(f"{side}Dropped") != "0":
            raise ValueError(f"Log/CSV trace buffer statistics mismatch for {side}")
    with (directory / f"trace-rtt-{run}.csv").open(encoding="utf-8-sig", newline="") as stream:
        rtt = list(csv.DictReader(stream))
    if len(rtt) != 1000:
        raise ValueError(f"Expected 1000 RTT rows, found {len(rtt)}")
    values = []
    for index, row in enumerate(rtt, 1):
        if int(row["requestIndex"]) != index or int(row["payloadChars"]) != 16 or int(row["rttNs"]) <= 0:
            raise ValueError(f"Invalid RTT row {index}: {row}")
        values.append(int(row["rttNs"]))
    event_lines = [line for line in log.splitlines() if "IpcReqTrace" in line and
                   re.search(r"\bEVT\s+", line) and message_fields(line).get("run") == run]
    sources = []
    if measured_enabled:
        # Logcat can drop diagnostic bursts. The two files are authoritative;
        # never mix Logcat EVT records into them, even when they look complete.
        events, file_ends, sources = read_event_files(directory, run)
        if len(events) != sum(counts.values()) or any(
                sum(event["side"] == side for event in events) != count for side, count in counts.items()):
            raise ValueError("Buffered event CSV counts and reliable event-file counts differ")
        requests, unjoined = request_groups(events, True)
        if len(requests) != 1000 or unjoined != 0:
            raise ValueError(f"Expected 1000 uniquely matched requests, got {len(requests)}, unjoined={unjoined}")
        chain = ("client_entry", "client_resolved", "client_registered", "client_send", "server_receive",
                 "server_enqueued", "server_job_start", "server_business_done", "server_reply", "client_reply", "client_resume")
        ordered = sorted(requests, key=lambda request: request["stages"].get("client_entry", {}).get("ns", -1))
        for index, request in enumerate(ordered):
            stages = request["stages"]
            if set(stages) != CLIENT_STAGES | SERVER_STAGES:
                raise ValueError(f"Incomplete/unknown request stages: {request['key']}")
            if any(stages[after]["ns"] < stages[before]["ns"] for before, after in zip(chain, chain[1:])):
                raise ValueError(f"Non-monotonic request stages: {request['key']}")
            if stages["client_resume"]["ns"] - stages["client_entry"]["ns"] > values[index]:
                raise ValueError(f"Request stage span exceeds external RTT: {request['key']}")
        flushed_lines = [line for line in log.splitlines() if "IpcReqTrace" in line and
                   re.search(r"\bEND\s+run=", line) and message_fields(line).get("run") == run]
        if len(flushed_lines) != 2:
            raise ValueError("Expected exactly two client/server Logcat END flush records")
        session_pids = {side: record["pid"] for side, record in file_ends.items()}
        if session_pids["client"] == session_pids["server"]:
            raise ValueError("Client and server PID must differ for a cross-process request trace")
        seen = set()
        for line in flushed_lines:
            fields = message_fields(line)
            try:
                pid = int(fields["pid"])
            except (KeyError, ValueError) as error:
                raise ValueError(f"Invalid Logcat END PID: {line}") from error
            sides = [side for side, expected_pid in session_pids.items() if expected_pid == pid]
            if len(sides) != 1 or sides[0] in seen:
                raise ValueError(f"Logcat END does not identify one unique process session: {line}")
            side = sides[0]
            parsed = read_flush_record(line, run, pid, counts[side], f"Logcat {side}")
            if parsed != file_ends[side]:
                raise ValueError(f"Logcat and event-file END differ for {side}")
            emitter = re.match(r"^\S+\s+\S+\s+(\d+)\s+\d+\s+[VDIWEF]\s+IpcReqTrace\s*:", line)
            if emitter and int(emitter[1]) != pid:
                raise ValueError(f"Logcat END emitter PID differs from its process session: {line}")
            seen.add(side)
    elif event_lines or any((directory / f"request-events-{run}-{side}.log").is_file() for side in ("client", "server")):
        raise ValueError("EVT records or event files found during tracing-disabled run")
    return dict(valid=True, run=run, traceEnabled=measured_enabled, count=1000, clientEvents=counts["client"],
                serverEvents=counts["server"], dropped=0, p50Ms=percentile(values, 0.5),
                p90Ms=percentile(values, 0.9), p99Ms=percentile(values, 0.99), maxMs=max(values) / 1_000_000,
                meanMs=sum(values) / len(values) / 1_000_000, eventFiles=sources)


def percentile(values: list[int], rank: float) -> float:
    ordered = sorted(values)
    return ordered[int((len(ordered) - 1) * rank)] / 1_000_000


def read_events(text: str, tag: str, selected_run: str | None) -> list[dict]:
    events = []
    for number, line in enumerate(text.splitlines(), 1):
        if tag not in line:
            continue
        match = re.search(r"\bEVT\s+(.*)", line)
        if not match:
            continue
        event = dict(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)=([^\s]+)", match.group(1)))
        if selected_run and event.get("run") != selected_run:
            continue
        try:
            for key in ("ns", "pid", "tid", "requestId", "generation", "callback"):
                event[key] = int(event[key])
            if event["side"] not in ("client", "server") or not event["stage"]:
                raise ValueError("Invalid side/stage")
            if event["ns"] < 0 or event["pid"] <= 0 or event["tid"] <= 0 or event["requestId"] <= 0 or event["generation"] < 0:
                raise ValueError("Invalid timestamp/PID/TID/requestId")
            event["run"]
        except (KeyError, ValueError) as error:
            raise ValueError(f"Invalid EVT at line {number}: {line}") from error
        events.append(event)
    if not events:
        raise ValueError("No matching EVT records; confirm the diagnostic log contract and run ID")
    return events


def read_flush_record(line: str, run: str, pid: int, count: int, label: str) -> dict:
    fields = message_fields(line)
    try:
        record = dict(run=fields["run"], pid=int(fields["pid"]),
                      events=int(fields["events"]), dropped=int(fields["dropped"]))
    except (KeyError, ValueError) as error:
        raise ValueError(f"Malformed {label} END: {line}") from error
    if record != dict(run=run, pid=pid, events=count, dropped=0):
        raise ValueError(f"{label} END does not match its process session/count/drop gate: {line}")
    return record


def read_event_files(directory: Path, run: str, tag: str = "IpcReqTrace") -> tuple[list[dict], dict, list[dict]]:
    """Read both authoritative process files; a missing/partial file never falls back to Logcat."""
    combined = []
    ends = {}
    sources = []
    for side, stage_count in (("client", len(CLIENT_STAGES)), ("server", len(SERVER_STAGES))):
        name = f"request-events-{run}-{side}.log"
        path = directory / name
        if not path.is_file():
            raise ValueError(f"Required reliable event file missing: {name}")
        content = path.read_bytes()
        text = content.decode("utf-8-sig")
        lines = [line for line in text.splitlines() if line.strip()]
        end_lines = []
        for line in lines:
            match = re.match(rf"^{re.escape(tag)}:\s+(EVT|END)\s+", line)
            if not match or message_fields(line).get("run") != run:
                raise ValueError(f"Unexpected record/run in {name}: {line}")
            if match[1] == "END":
                end_lines.append(line)
        if len(end_lines) != 1 or lines[-1] != end_lines[0]:
            raise ValueError(f"Expected exactly one final END record in {name}")
        events = read_events(text, tag, run)
        if len(events) != stage_count * 1000 or any(event["side"] != side for event in events):
            raise ValueError(f"Wrong event count/side in {name}")
        sessions = {(event["pid"], event["callback"], event["generation"]) for event in events}
        if len(sessions) != 1:
            raise ValueError(f"Expected one PID/callback/generation session in {name}")
        pid, callback, generation = next(iter(sessions))
        ends[side] = read_flush_record(end_lines[0], run, pid, len(events), name)
        combined.extend(events)
        sources.append(dict(side=side, path=name, bytes=len(content),
                            sha256=hashlib.sha256(content).hexdigest().upper(),
                            pid=pid, callback=callback, generation=generation))
    if ends["client"]["pid"] == ends["server"]["pid"]:
        raise ValueError("Client and server PID must differ for a cross-process request trace")
    return combined, ends, sources


def request_groups(events: list[dict], single_client: bool) -> tuple[list[dict], int]:
    clients = defaultdict(dict)
    servers = defaultdict(dict)
    for event in events:
        # Never compare Binder/BinderProxy identity hashes across processes.
        key = (event["run"], event["pid"], event["callback"],
               event["generation"], event["requestId"])
        groups = clients if event["side"] == "client" else servers
        if event["stage"] in groups[key]:
            raise ValueError(f"Duplicate stage {event['stage']} for {key}; identity is ambiguous")
        groups[key][event["stage"]] = event
    if not clients:
        raise ValueError("No client request groups found")
    server_by_id = defaultdict(list)
    for key, stages in servers.items():
        server_by_id[key[0], key[4]].append((key, stages))
    if single_client:
        client_sessions = defaultdict(set)
        server_sessions = defaultdict(set)
        for key in clients:
            client_sessions[key[0]].add(key[1:4])
        for key in servers:
            server_sessions[key[0]].add(key[1:3])
        if any(len(sessions) != 1 for sessions in client_sessions.values()) or any(
                len(sessions) != 1 for sessions in server_sessions.values()):
            raise ValueError("--single-client requires one client PID/callback/generation and one server PID/callback per run")
    requests = []
    joined = set()
    for key, client_stages in clients.items():
        stages = dict(client_stages)
        if single_client:
            matches = server_by_id[key[0], key[4]]
            if len(matches) > 1:
                raise ValueError(f"Ambiguous server requestId: {key[0]} {key[4]}")
            if matches:
                server_key, server_stages = matches[0]
                if set(stages).intersection(server_stages):
                    raise ValueError(f"Client/server stage names overlap for {key}")
                stages.update(server_stages)
                joined.add(server_key)
        requests.append({"key": key, "stages": stages})
    return requests, len(servers) - len(joined)


def summarize(requests: list[dict], pairs: dict, output: Path) -> None:
    durations = defaultdict(list)
    missing = defaultdict(int)
    invalid = defaultdict(int)
    rows = []
    for request in requests:
        run, pid, callback, generation, request_id = request["key"]
        stages = request["stages"]
        row = {"run": run, "clientPid": pid, "callback": callback,
               "generation": generation, "requestId": request_id}
        for name, (start, end) in pairs.items():
            if start not in stages or end not in stages:
                missing[name] += 1
                row[f"{name}Ns"] = ""
                continue
            duration = stages[end]["ns"] - stages[start]["ns"]
            if duration < 0:
                invalid[name] += 1
                row[f"{name}Ns"] = ""
                continue
            row[f"{name}Ns"] = duration
            durations[name].append(duration)
        rows.append(row)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", newline="", encoding="utf-8") as destination:
        writer = csv.DictWriter(destination, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print("| Stage interval | Valid | Missing | Negative | P50 ms | P90 ms | P99 ms | Max ms |")
    print("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    for name in pairs:
        values = durations[name]
        metrics = [f"{percentile(values, rank):.6f}" for rank in (0.5, 0.9, 0.99)] if values else ["unavailable"] * 3
        maximum = f"{max(values) / 1_000_000:.6f}" if values else "unavailable"
        print(f"| {name} | {len(values)} | {missing[name]} | {invalid[name]} | " +
              " | ".join((*metrics, maximum)) + " |")
    print(f"\nPer-request durations: {output}")
    print("Server dispatch = admission + queue; it overlaps those intervals and must not be summed with them.")
    print("Missing/negative intervals are excluded, never replaced with zero.")


def perfetto_queries(package: str) -> dict[str, str]:
    escaped = package.replace("'", "''")
    processes = f"(p.name = '{escaped}' OR p.name GLOB '{escaped}:*')"
    return {
        "trace-stats": "SELECT name, severity, source, value FROM stats WHERE value != 0 ORDER BY severity, name;",
        "thread-cpu": f"""SELECT p.pid, t.tid, p.name AS process, t.name AS thread,
COUNT(*) AS sched_slices, SUM(s.dur)/1000000.0 AS cpu_ms FROM sched s
JOIN thread t ON s.utid=t.utid JOIN process p ON t.upid=p.upid
WHERE s.dur > 0 AND {processes} GROUP BY p.pid,t.tid,p.name,t.name ORDER BY cpu_ms DESC;""",
        "thread-states": f"""SELECT p.pid,t.tid,t.name,st.state,COUNT(*) AS slices,
SUM(st.dur)/1000000.0 AS duration_ms FROM thread_state st JOIN thread t ON st.utid=t.utid
JOIN process p ON t.upid=p.upid WHERE st.dur > 0 AND {processes}
GROUP BY p.pid,t.tid,t.name,st.state ORDER BY duration_ms DESC;""",
        "cpu-frequency": """SELECT c.ts,t.cpu,t.name,c.value FROM counter c
JOIN cpu_counter_track t ON c.track_id=t.id WHERE t.name='cpufreq' ORDER BY c.ts,t.cpu;""",
        "binder-events": """SELECT r.ts,t.tid,p.pid,r.name,a.key,a.int_value,a.string_value
FROM raw r JOIN thread t ON r.utid=t.utid LEFT JOIN process p ON t.upid=p.upid
LEFT JOIN args a ON r.arg_set_id=a.arg_set_id
WHERE r.name IN ('binder_transaction','binder_transaction_received') ORDER BY r.ts,a.key;""",
        "app-gc-slices": f"""SELECT s.ts,s.dur,p.pid,t.tid,t.name,s.name FROM slice s
JOIN thread_track tt ON s.track_id=tt.id JOIN thread t ON tt.utid=t.utid
JOIN process p ON t.upid=p.upid WHERE {processes}
AND (s.name GLOB 'MIPC:*' OR s.name GLOB 'Ipc*' OR s.name GLOB '*GC*' OR s.name GLOB '*gc*') ORDER BY s.ts;""",
    }


def export_perfetto(trace: Path, processor: Path | None, package: str, directory: Path) -> None:
    queries = perfetto_queries(package)
    directory.mkdir(parents=True, exist_ok=True)
    for name, sql in queries.items():
        (directory / f"{name}.sql").write_text(sql + "\n", encoding="utf-8")
    if processor is None:
        print(f"Perfetto SQL prepared at {directory}; no processor invoked.")
        return
    if not trace.is_file() or not processor.is_file():
        raise ValueError("Trace and trace_processor_shell paths must exist")
    for name, sql in queries.items():
        # Classic -Q remains supported by native trace_processor_shell builds.
        result = subprocess.run([str(processor), "-Q", sql, str(trace)], capture_output=True,
                                text=True, encoding="utf-8", errors="replace", timeout=60)
        (directory / f"{name}.stderr.txt").write_text(result.stderr, encoding="utf-8")
        if result.returncode:
            raise ValueError(f"Perfetto query {name} failed; inspect its stderr and SQL schema")
        (directory / f"{name}.csv").write_text(result.stdout, encoding="utf-8")
    print(f"Perfetto context exported at {directory}; inspect loss statistics before attributing delay.")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Capture directory containing logcat.txt")
    parser.add_argument("--run-id")
    parser.add_argument("--tag", default="IpcReqTrace")
    parser.add_argument("--single-client", action="store_true")
    parser.add_argument("--stage-pair", action="append", default=[], metavar="NAME=START:END")
    parser.add_argument("--package", default="com.cn.ipc.demo")
    parser.add_argument("--trace-processor", type=Path)
    return parser.parse_args()


def main(args: argparse.Namespace) -> None:
    pairs = dict(PAIRS)
    for definition in args.stage_pair:
        name, separator, stages = definition.partition("=")
        start, colon, end = stages.partition(":")
        if not separator or not colon or not name or not start or not end:
            raise ValueError(f"Invalid stage pair: {definition}")
        pairs[name] = (start, end)
    if not args.run_id:
        raise ValueError("--run-id is required for benchmark qualification")
    qualification = qualify_foreground(args.directory, args.run_id)
    try:
        if not qualification["valid"]:
            raise ValueError("Foreground coverage failed: " + "; ".join(qualification["errors"]))
        metadata_path = args.directory / "capture-metadata.json"
        metadata = json.loads(metadata_path.read_text(encoding="utf-8-sig")) if metadata_path.is_file() else None
        if metadata is not None and (metadata.get("captureValid") is not True or
                                     metadata.get("runId") != args.run_id or
                                     metadata.get("serial") != "925c23bb"):
            raise ValueError("Capture provenance/qualification failed: " + str(metadata.get("error", metadata)))
        summary = validate_benchmark_artifacts(args.directory, args.run_id)
        if metadata is not None and metadata.get("traceEnabled") != summary["traceEnabled"]:
            raise ValueError("Capture metadata trace mode and benchmark trace mode differ")
        if metadata is not None and metadata.get("eventFiles", []) != [
                {key: source[key] for key in ("side", "path", "bytes", "sha256")} for source in summary["eventFiles"]]:
            raise ValueError("Reliable event files differ from the captured hashes/sizes")
        print(json.dumps(summary, indent=2))
        if summary["traceEnabled"]:
            events, _, _ = read_event_files(args.directory, args.run_id, args.tag)
            requests, unjoined = request_groups(events, args.single_client)
            print(f"Events={len(events)} clientRequests={len(requests)} unjoinedServerRequests={unjoined}")
            print("Correlation: " + ("explicit single-client requestId scope" if args.single_client else "client-local only"))
            summarize(requests, pairs, args.directory / "request-stages.csv")
        export_perfetto(args.directory / "request-trace.perfetto-trace", args.trace_processor,
                        args.package, args.directory / "perfetto-analysis")
        (args.directory / "request-trace-analysis.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    except Exception as error:
        qualify_foreground(args.directory, args.run_id, [f"Request trace analysis failed: {type(error).__name__}: {error}"])
        raise


if __name__ == "__main__":
    try:
        main(arguments())
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit(f"ERROR: {error}") from error
