"""Recompute the captured-Binder guard A/B; retain all blocks, tails and foreground observations."""
import csv
import json
import statistics
import sys
from pathlib import Path
from qualify_foreground import qualify_foreground


def analyze_qualified(directory: Path, run: str, qualification: dict) -> None:
    log = (directory / "logcat.txt").read_text(encoding="utf-8-sig")
    assert "FAILED" not in log and "FATAL EXCEPTION" not in log
    assert sum(f"PASS run={run} " in row for row in log.splitlines()) == 6
    assert sum(f"RESULT run={run} " in row for row in log.splitlines()) == 16
    assert f"DONE run={run} passed=true" in log
    assert f"END run={run} started=true completed=true valid=true" in log
    with (directory / f"foreground-{run}.csv").open(encoding="utf-8-sig", newline="") as stream:
        states = list(csv.DictReader(stream))
    with (directory / f"guard-local-{run}.csv").open(encoding="utf-8-sig", newline="") as stream:
        local = list(csv.DictReader(stream))
    assert len(local) == 8
    for row in local:
        assert int(row["count"]) == 500_000 and row["mode"] in ("lookup", "guard")
        if row["kind"] == "TIME":
            row["threadCpuNsPerStep"] = int(row["cpuNs"]) / int(row["count"])
        else:
            assert row["kind"] == "ALLOC"
            if row["bytes"]:
                assert int(row["afterBytes"]) - int(row["beforeBytes"]) == int(row["bytes"])
                row["processBytesPerStep"] = int(row["bytes"]) / int(row["count"])
    local_summary = []
    for kind, metric in (("TIME", "threadCpuNsPerStep"), ("ALLOC", "processBytesPerStep")):
        medians = {}
        for mode in ("lookup", "guard"):
            selected = [row[metric] for row in local if row["kind"] == kind and row["mode"] == mode and metric in row]
            assert len(selected) == 2
            medians[mode] = statistics.median(selected)
        local_summary.append(dict(metric=metric, **medians,
                                  changePercent=(medians["guard"] / medians["lookup"] - 1) * 100))
    ipc = []
    for round_number in range(1, 5):
        for size in (16, 1024):
            for mode in ("lookup", "guard"):
                path = directory / f"guard-ipc-{run}-r{round_number}-{mode}-{size}.csv"
                values = sorted(int(row) for row in path.read_text().splitlines())
                assert len(values) == 1000 and values[0] > 0
                ipc.append(dict(round=round_number, size=size, mode=mode, count=1000,
                                p50Ms=values[499] / 1e6, p90Ms=values[899] / 1e6,
                                p99Ms=values[989] / 1e6, maxMs=values[-1] / 1e6,
                                meanMs=statistics.mean(values) / 1e6))
    summaries = []
    for size in (16, 1024):
        medians = {mode: statistics.median(row["p50Ms"] for row in ipc if row["size"] == size and row["mode"] == mode)
                   for mode in ("lookup", "guard")}
        changes = []
        for round_number in range(1, 5):
            pair = {row["mode"]: row["p50Ms"] for row in ipc if row["size"] == size and row["round"] == round_number}
            changes.append((pair["guard"] / pair["lookup"] - 1) * 100)
        summaries.append(dict(size=size, **medians, changePercent=(medians["guard"] / medians["lookup"] - 1) * 100,
                              pairedChangePercent=changes))
    result = dict(run=run, local=local, localSummary=local_summary, ipc=ipc, ipcSummary=summaries,
                  foreground=dict(**qualification, states=states))
    (directory / "analysis.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(run=run, localSummary=local_summary, ipcSummary=summaries,
                         foreground={key: value for key, value in result["foreground"].items() if key != "states"}), indent=2))


def main(directory: Path, run: str) -> None:
    qualification = qualify_foreground(directory, run)
    if not qualification["valid"]:
        raise ValueError("Foreground qualification failed: " + "; ".join(qualification["errors"]))
    try:
        analyze_qualified(directory, run, qualification)
    except Exception as error:
        qualify_foreground(directory, run, [f"Guard analysis failed: {type(error).__name__}: {error}"])
        raise


if __name__ == "__main__":
    main(Path(sys.argv[1]), sys.argv[2])
