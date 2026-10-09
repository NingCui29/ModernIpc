"""Read-only validation and block-level comparison of two Xiaomi dispatch captures.

Usage: python analyze_dispatch.py <directory1> <run1> <directory2> <run2>
       [--json-out <new-report.json>] [--markdown-out <new-summary.md>] [--print-pull]

No ADB is executed and no captured artifact is modified. Without --json-out,
JSON and Markdown are printed; with it, JSON is saved once and Markdown printed.
"""

import argparse
import csv
import hashlib
import json
import math
import re
import statistics
import sys
from pathlib import Path

sys.dont_write_bytecode = True
from qualify_foreground import qualify_rows


SERIAL = "925c23bb"
ROOT = Path(__file__).resolve().parents[3]
METRICS = ("p50Ms", "p99Ms", "meanMs", "qps")
COLUMNS = ("phase", "round", "mode", "payloadChars", "concurrency", "index", "rttNs", "blockNs")
LIMITS = (
    "Each request is a correlated observation inside a block, not an independent experiment; no pooled-request confidence interval or significance test is computed.",
    "Lane summaries are medians across block metrics. Paired changes compare modes in the same run/round/payload/phase; separate captures remain separate.",
    "Default versus dedicated is the same Async wire/business/caller path with application server-scope configuration changed. Direct is a synchronous semantic reference, not an equivalent Async optimization.",
    "QPS is completed requests divided by measured block duration, including the harness envelope; concurrent QPS is not the reciprocal of mean RTT.",
    "Serial count/blockNs is a serial completion rate, not concurrent throughput; concurrent phase results are reported separately.",
    "Concurrent sample indices group each of 16 workers' 64 serial requests; they are not global request-completion order or aligned pairs across modes.",
    "The serial dedicated lane is always the middle lane while default/direct reverse their positions across rounds; balanced order does not eliminate period drift or carry-over.",
    "Foreground qualification covers app events and bounded periodic samples, not uninterrupted CPU availability, equal frequency/load, GC/JIT state, or scheduler causality.",
    "Trace-off and resource-cleanup claims require the benchmark's explicit PASS assertions; filtered Logcat silence is not used as proof.",
    "Same APK/source does not establish equal session load. End-of-capture process inventories show presence, not CPU utilization; a cross-capture difference is not attributed to stopping those processes.",
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def direction(value: float) -> int:
    return (value > 0) - (value < 0)


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    require(isinstance(value, dict), f"Expected JSON object: {path}")
    return value


def expected_keys() -> list[tuple]:
    keys = []
    for round_number in range(1, 5):
        modes = ("default", "dedicated", "direct") if round_number in (1, 4) else ("direct", "dedicated", "default")
        sizes = (16, 1024) if round_number % 2 else (1024, 16)
        keys.extend(("serial", round_number, mode, size, 1) for size in sizes for mode in modes)
    for round_number in range(1, 3):
        modes = ("default", "dedicated") if round_number == 1 else ("dedicated", "default")
        keys.extend(("concurrent", round_number, mode, 16, 16) for mode in modes)
    return keys


def metric_summary(values: list[int], block_ns: int) -> dict:
    ordered = sorted(values)
    count = len(ordered)
    return {"p50Ms": ordered[math.ceil(count * 0.50) - 1] / 1e6,
            "p99Ms": ordered[math.ceil(count * 0.99) - 1] / 1e6,
            "meanMs": statistics.fmean(values) / 1e6, "maxMs": ordered[-1] / 1e6,
            "qps": count * 1e9 / block_ns}


def read_blocks(path: Path) -> list[dict]:
    groups = {}
    order = []
    last_key = None
    total = 0
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        require(tuple(reader.fieldnames or ()) == COLUMNS, f"Unexpected dispatch CSV columns: {reader.fieldnames}")
        for line, row in enumerate(reader, 2):
            require(None not in row and all(row.get(column) is not None for column in COLUMNS), f"Malformed CSV row {line}")
            try:
                key = (row["phase"], int(row["round"]), row["mode"], int(row["payloadChars"]), int(row["concurrency"]))
                index, rtt_ns, block_ns = (int(row[column]) for column in ("index", "rttNs", "blockNs"))
            except (ValueError, TypeError) as error:
                raise ValueError(f"Non-integer dispatch value at CSV row {line}") from error
            require(rtt_ns > 0 and block_ns > 0, f"Nonpositive timing at CSV row {line}")
            if key != last_key:
                require(key not in groups, f"Repeated/noncontiguous block at CSV row {line}: {key}")
                groups[key] = {"values": [], "blockNs": block_ns}
                order.append(key)
                last_key = key
            group = groups[key]
            require(index == len(group["values"]) + 1, f"Missing/duplicate/out-of-order sample index at CSV row {line}: {key}")
            require(block_ns == group["blockNs"], f"Inconsistent blockNs at CSV row {line}: {key}")
            group["values"].append(rtt_ns)
            total += 1
    require(order == expected_keys(), "CSV block keys/order differ from all 24 serial + 4 concurrent protocol blocks")
    require(total == 28096, f"Expected 28096 data rows, got {total}")
    blocks = []
    for key in order:
        phase, round_number, mode, size, concurrency = key
        values, block_ns = groups[key]["values"], groups[key]["blockNs"]
        expected_count = 1000 if phase == "serial" else 1024
        require(len(values) == expected_count, f"Wrong sample count for {key}: {len(values)}")
        require(max(values) <= block_ns and sum(values) <= concurrency * block_ns,
                f"RTT/block duration inconsistent with {concurrency} sequential worker(s): {key}")
        blocks.append(dict(phase=phase, round=round_number, mode=mode, payloadChars=size,
                           concurrency=concurrency, count=len(values), blockNs=block_ns,
                           blockSeconds=block_ns / 1e9,
                           rateMeaning="serial_completion_per_second" if phase == "serial" else "concurrent_requests_per_second",
                           **metric_summary(values, block_ns)))
    return blocks


def log_fields(line: str) -> dict:
    return dict(re.findall(r"\b([A-Za-z][A-Za-z0-9]*)=([^\s]+)", line))


def validate_log(log: str, run: str, blocks: list[dict]) -> dict:
    require("FAILED" not in log and "FATAL EXCEPTION" not in log, "Failure/crash marker in Logcat")
    marker = f"run={run} "
    passes = [line for line in log.splitlines() if "IpcDispatchBench" in line and "PASS " + marker in line]
    results = [line for line in log.splitlines() if "IpcDispatchBench" in line and "RESULT " + marker in line]
    done = [line for line in log.splitlines() if "IpcDispatchBench" in line and "DONE " + marker in line]
    require(len(passes) == 4 and len(results) == 28 and len(done) == 1, "Expected exactly 4 PASS, 28 RESULT and 1 DONE lines")
    expected_pass = (
        {"traceDisabled": "true"},
        {"serialBlocks": "24", "count": "24000", "failures": "0"},
        {"concurrentBlocks": "4", "count": "4096", "failures": "0"},
        {"defaultRestored": "true", "pending": "0", "requests": "0", "subscriptions": "0"},
    )
    for line, expected in zip(passes, expected_pass):
        fields = log_fields(line)
        require(all(fields.get(key) == value for key, value in expected.items()), f"Missing PASS gate: {expected}")
    done_fields = log_fields(done[0])
    require(all(done_fields.get(key) == value for key, value in
                {"passed": "true", "checks": "4", "serialBlocks": "24", "concurrentBlocks": "4"}.items()), "Incomplete DONE gate")
    require(f"END run={run} started=true completed=true valid=true" in log, "No valid foreground END line")
    for line, block in zip(results, blocks):
        fields = log_fields(line)
        common = {"phase": block["phase"], "round": str(block["round"]), "mode": block["mode"],
                  "count": str(block["count"]), "failures": "0"}
        require(all(fields.get(key) == value for key, value in common.items()), f"RESULT block missing/wrong/order differs: {common}")
        if block["phase"] == "serial":
            require(fields.get("size") == str(block["payloadChars"]), f"Wrong RESULT payload size: {line}")
            for metric in ("p50Ms", "p99Ms"):
                require(metric in fields and math.isclose(float(fields[metric]), block[metric], rel_tol=1e-12, abs_tol=1e-9),
                        f"Log/CSV {metric} mismatch: {line}")
        else:
            require(fields.get("blockNs") == str(block["blockNs"]), f"Log/CSV concurrent blockNs mismatch: {line}")
    return {"passes": len(passes), "resultBlocks": len(results), "done": len(done),
            "traceDisabled": True, "defaultRestored": True, "pending": 0, "requests": 0, "subscriptions": 0}


def source_fingerprint(evidence: dict) -> str:
    sources = evidence.get("sourceHashes")
    require(isinstance(sources, list) and bool(sources), "Missing captured source hashes")
    require(all(isinstance(item, dict) and isinstance(item.get("path"), str) and
                re.fullmatch(r"[A-Fa-f0-9]{64}", item.get("sha256", "")) for item in sources), "Malformed source hash records")
    require(len({item["path"] for item in sources}) == len(sources), "Duplicate source hash paths")
    canonical = sorted((item["path"], item["sha256"].upper()) for item in sources)
    return hashlib.sha256(json.dumps(canonical, separators=(",", ":")).encode()).hexdigest().upper()


def paired_rows(blocks: list[dict]) -> list[dict]:
    pairs = []
    for block in blocks:
        if block["mode"] == "default":
            continue
        baseline = next(candidate for candidate in blocks if candidate["phase"] == block["phase"] and
                        candidate["round"] == block["round"] and candidate["payloadChars"] == block["payloadChars"] and
                        candidate["mode"] == "default")
        pairs.append({"phase": block["phase"], "round": block["round"], "payloadChars": block["payloadChars"],
                      "baseline": "default", "target": block["mode"], "baselineMetrics": {key: baseline[key] for key in METRICS},
                      "targetMetrics": {key: block[key] for key in METRICS},
                      "changePercent": {key: (block[key] / baseline[key] - 1) * 100 for key in METRICS}})
    return sorted(pairs, key=lambda row: (row["phase"], row["payloadChars"], row["target"], row["round"]))


def lane_summaries(blocks: list[dict]) -> list[dict]:
    keys = sorted({(row["phase"], row["payloadChars"], row["mode"]) for row in blocks})
    summaries = []
    for phase, size, mode in keys:
        selected = [row for row in blocks if (row["phase"], row["payloadChars"], row["mode"]) == (phase, size, mode)]
        summaries.append({"phase": phase, "payloadChars": size, "mode": mode, "blockCount": len(selected),
                          "medianBlockMetrics": {key: statistics.median(row[key] for row in selected) for key in METRICS},
                          "blockMetricRanges": {key: [min(row[key] for row in selected), max(row[key] for row in selected)] for key in METRICS}})
    return summaries


def pair_summaries(pairs: list[dict]) -> list[dict]:
    summaries = []
    for phase, size, target in sorted({(row["phase"], row["payloadChars"], row["target"]) for row in pairs}):
        selected = [row for row in pairs if (row["phase"], row["payloadChars"], row["target"]) == (phase, size, target)]
        metrics = {}
        for key in METRICS:
            values = [row["changePercent"][key] for row in selected]
            metrics[key] = {"roundChangesPercent": values, "medianChangePercent": statistics.median(values),
                            "rangePercent": [min(values), max(values)],
                            "favorablePairs": sum(value > 0 if key == "qps" else value < 0 for value in values)}
        summaries.append(dict(phase=phase, payloadChars=size, baseline="default", target=target,
                              pairCount=len(selected), metrics=metrics))
    return summaries


def process_inventory(directory: Path) -> dict:
    path = directory / "processes.txt"
    inventory = {"file": path.name, "available": path.is_file(), "observedTestAppProcesses": [],
                 "scope": "end-of-capture process presence only; not continuous residency or utilization"}
    if path.is_file():
        inventory["sha256"] = sha(path)
        packages = {"com.cn.ipc.server.app", *(f"com.cn.ipc.client{index}" for index in range(1, 4))}
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            fields = line.split()
            if len(fields) > 2 and fields[-1] in packages:
                inventory["observedTestAppProcesses"].append(
                    {"package": fields[-1], "pid": int(fields[1]) if fields[1].isdigit() else None, "rawLine": line})
    return inventory


def analyze_run(directory: Path, run: str) -> dict:
    require(re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", run) is not None, "Unsafe run ID")
    paths = {"evidence": directory / "evidence.json", "qualification": directory / "qualification.json",
             "foreground": directory / f"foreground-{run}.csv", "logcat": directory / "logcat.txt",
             "samples": directory / f"dispatch-{run}.csv"}
    evidence, stored = read_json(paths["evidence"]), read_json(paths["qualification"])
    require(evidence.get("device") == SERIAL and evidence.get("run") == run and evidence.get("expectedPass") == 4,
            "Captured serial/run/expectedPass differs from the dispatch protocol")
    apk_hash = evidence.get("apkSha256", "").upper()
    require(re.fullmatch(r"[A-F0-9]{64}", apk_hash) is not None and apk_hash == evidence.get("installedSha256", "").upper(),
            "Invalid/mismatched captured built and installed APK hashes")
    with paths["foreground"].open(encoding="utf-8-sig", newline="") as stream:
        computed = qualify_rows(list(csv.DictReader(stream)))
    require(computed["valid"], "Foreground rows fail qualification: " + "; ".join(computed["errors"]))
    require(stored.get("valid") is True and stored.get("run") == run and stored.get("csv") == paths["foreground"].name,
            "Missing or invalid captured foreground qualification")
    require(stored.get("sourceSha256", "").upper() == sha(paths["foreground"]), "Foreground qualification hash mismatch")
    require(all(stored.get(key) == value for key, value in computed.items()), "Stored foreground qualification differs from read-only recomputation")
    blocks = read_blocks(paths["samples"])
    checks = validate_log(paths["logcat"].read_text(encoding="utf-8-sig"), run, blocks)
    snapshot_audit = []
    stage = evidence.get("stage", "")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+", stage) is not None, "Invalid captured stage name")
    for suffix in ("", "-installed"):
        path = ROOT / ".gradle" / f"ordered-{stage}{suffix}.apk"
        present = path.is_file()
        actual = sha(path) if present else None
        require(not present or actual == apk_hash, f"Retained APK snapshot hash mismatch: {path}")
        snapshot_audit.append({"path": str(path), "available": present, "sha256": actual})
    pairs = paired_rows(blocks)
    return {"run": run, "directory": str(directory), "valid": True, "serial": SERIAL, "rows": 28096,
            "serialBlocks": 24, "concurrentBlocks": 4, "apkSha256": apk_hash,
            "sourceFingerprint": source_fingerprint(evidence), "apkSnapshotAudit": snapshot_audit,
            "artifactHashes": {key: sha(path) for key, path in paths.items()}, "checks": checks,
            "foreground": computed, "sessionProcessInventory": process_inventory(directory),
            "blocks": blocks, "laneSummaries": lane_summaries(blocks),
            "pairedRounds": pairs, "pairSummaries": pair_summaries(pairs)}


def analyze_two(inputs: list[tuple[Path, str]]) -> dict:
    reports = []
    errors = []
    for directory, run in inputs:
        try:
            reports.append(analyze_run(directory, run))
        except (OSError, ValueError, TypeError, KeyError, StopIteration, csv.Error) as error:
            reports.append({"run": run, "directory": str(directory), "valid": False, "error": f"{type(error).__name__}: {error}"})
            errors.append(f"{run}: {error}")
    if not errors:
        require(inputs[0][1] != inputs[1][1] and inputs[0][0].resolve() != inputs[1][0].resolve(), "Two different capture runs are required")
        if reports[0]["apkSha256"] != reports[1]["apkSha256"]:
            errors.append("The two captures used different APK hashes; they cannot form this same-APK comparison")
        if reports[0]["sourceFingerprint"] != reports[1]["sourceFingerprint"]:
            errors.append("The two captures have different source snapshots")
    comparison = []
    if not errors:
        for first, second in zip(reports[0]["pairSummaries"], reports[1]["pairSummaries"]):
            comparison.append({"phase": first["phase"], "payloadChars": first["payloadChars"], "target": first["target"],
                               "captures": [{"run": report["run"], "pairCount": summary["pairCount"], "metrics": summary["metrics"]}
                                            for report, summary in zip(reports, (first, second))],
                               "sameMedianPairDirection": {key: direction(first["metrics"][key]["medianChangePercent"]) ==
                                                                  direction(second["metrics"][key]["medianChangePercent"])
                                                           for key in METRICS}})
    return {"valid": not errors, "serial": SERIAL, "captureCount": 2, "errors": errors, "analyzerSha256": sha(Path(__file__)),
            "quantileRule": "nearest rank: sorted[ceil(p*n)-1]; matches serial Kotlin indices 499/989",
            "analysisUnit": "block and within-run paired round; request RTTs are not independent experiments",
            "limits": list(LIMITS), "runs": reports, "crossCaptureComparison": comparison}


def markdown(report: dict) -> str:
    lines = ["# Dispatch experiment", "", f"Validation: **{'PASS' if report['valid'] else 'FAIL'}**; Xiaomi `{SERIAL}`; captures=2.", ""]
    if report["errors"]:
        lines.extend(f"- {error}" for error in report["errors"])
        lines.append("")
    for run in report["runs"]:
        lines.extend([f"## {run['run']}", ""])
        if not run["valid"]:
            lines.extend([run["error"], ""])
            continue
        lines.extend([f"28,096 rows; 24 serial / 4 concurrent blocks; 4 PASS / 28 RESULT / DONE; foreground max gap {run['foreground']['maxGapMs']} ms.",
                      f"APK SHA256: `{run['apkSha256']}`", ""])
        inventory = run["sessionProcessInventory"]
        observed = ", ".join(f"`{item['package']}` (PID {item['pid']})" for item in inventory["observedTestAppProcesses"]) or "none observed"
        lines.extend([f"Four-package smoke apps in end snapshot: {observed}. This is process presence, not measured load.", ""])
        for phase, label, rate in (("serial", "Serial", "Completion/s"), ("concurrent", "Concurrent (16 workers)", "Throughput req/s")):
            lines.extend([f"### {label}: median block metrics", "",
                          f"| Characters | Mode | Blocks | P50 ms | P99 ms | Mean ms | {rate} |",
                          "| ---: | --- | ---: | ---: | ---: | ---: | ---: |"])
            for row in run["laneSummaries"]:
                if row["phase"] != phase:
                    continue
                metrics = row["medianBlockMetrics"]
                lines.append(f"| {row['payloadChars']} | {row['mode']} | {row['blockCount']} | " +
                             " | ".join(f"{metrics[key]:.6f}" if key != "qps" else f"{metrics[key]:.2f}" for key in METRICS) + " |")
            if phase == "serial":
                lines.extend(["", "Direct is a synchronous semantic reference; its per-block and paired results remain in JSON."])
            lines.extend(["", f"### {label}: every dedicated/default paired round", "",
                          "Latency: negative is lower. Completion/throughput rate: positive is higher.", "",
                          f"| Round | Characters | Default P50 ms | Dedicated P50 ms | dP50 % | dP99 % | dMean % | d{rate} % |",
                          "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"])
            for row in run["pairedRounds"]:
                if row["phase"] != phase or row["target"] != "dedicated":
                    continue
                lines.append(f"| {row['round']} | {row['payloadChars']} | {row['baselineMetrics']['p50Ms']:.6f} | {row['targetMetrics']['p50Ms']:.6f} | " +
                             " | ".join(f"{row['changePercent'][key]:+.6f}" for key in METRICS) + " |")
            lines.append("")
    if report["valid"]:
        lines.extend(["## Dedicated/default within-capture paired medians", "",
                      "The two sessions have different background process context and are not pooled as equal-load independent experiments.", ""])
        for phase, rate in (("serial", "Completion/s"), ("concurrent", "Throughput req/s")):
            lines.extend([f"### {phase}", "",
                          f"| Run | Characters | Pairs | dP50 % | dP99 % | dMean % | d{rate} % |",
                          "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"])
            for item in report["crossCaptureComparison"]:
                if item["phase"] != phase or item["target"] != "dedicated":
                    continue
                for capture in item["captures"]:
                    lines.append(f"| {capture['run']} | {item['payloadChars']} | {capture['pairCount']} | " +
                                 " | ".join(f"{capture['metrics'][key]['medianChangePercent']:+.6f}" for key in METRICS) + " |")
            lines.append("")
    lines.extend(["## Interpretation limits", ""] + [f"- {limit}" for limit in LIMITS])
    return "\n".join(lines) + "\n"


def main(args: argparse.Namespace) -> None:
    inputs = [(args.directory1, args.run1), (args.directory2, args.run2)]
    if args.print_pull:
        for directory, run in inputs:
            require(re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", run) is not None, "Unsafe run ID")
            print(json.dumps({"argv": ["adb", "-s", SERIAL, "exec-out", "run-as", "com.cn.ipc.demo", "cat", f"files/dispatch-{run}.csv"],
                              "destination": str(directory / f"dispatch-{run}.csv"), "writeMode": "xb"}))
        return
    report = analyze_two(inputs)
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    rendered = markdown(report)
    for path in (args.json_out, args.markdown_out):
        require(path is None or not path.exists(), f"Report path already exists: {path}")
    require(args.json_out is None or args.markdown_out is None or args.json_out.resolve() != args.markdown_out.resolve(),
            "JSON and Markdown output paths must differ")
    if args.json_out:
        with args.json_out.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(encoded)
    else:
        print("## JSON\n\n```json\n" + encoded.rstrip() + "\n```\n")
    if args.markdown_out:
        with args.markdown_out.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(rendered)
    print(rendered, end="")
    if not report["valid"]:
        raise SystemExit(1)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory1", type=Path)
    parser.add_argument("run1")
    parser.add_argument("directory2", type=Path)
    parser.add_argument("run2")
    parser.add_argument("--json-out", type=Path, help="Create a new JSON report; existing files are never overwritten")
    parser.add_argument("--markdown-out", type=Path, help="Create a new Markdown summary with all 16 dedicated/default serial pairs")
    parser.add_argument("--print-pull", action="store_true", help="Print fixed-Xiaomi argv/destination records only; never execute ADB")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8")
        main(arguments())
    except (OSError, ValueError, TypeError, KeyError, csv.Error) as error:
        raise SystemExit(f"ERROR: {error}") from error
