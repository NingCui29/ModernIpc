"""Recompute same-APK pending state A/B results, retaining each block and tail."""
import csv
import json
import statistics
import sys
from pathlib import Path


def main(directory: Path, run: str) -> None:
    log = (directory / "logcat.txt").read_text(encoding="utf-8-sig")
    assert "FAILED" not in log and "FATAL EXCEPTION" not in log
    assert sum(f"PASS run={run} " in row for row in log.splitlines()) == 12
    assert sum(f"RESULT run={run} " in row for row in log.splitlines()) == 16
    assert f"DONE run={run} passed=true" in log
    with (directory / f"pending-local-{run}.csv").open(encoding="utf-8-sig", newline="") as stream:
        local = list(csv.DictReader(stream))
    assert len(local) == 16
    for row in local:
        assert row["kind"] in ("TIME", "ALLOC") and row["mode"] in ("legacy", "optimized")
        assert int(row["count"]) == 50_000
        if row["kind"] == "TIME":
            row["cpuNsPerCall"] = int(row["cpuNs"]) / int(row["count"])
            row["wallNsPerCall"] = int(row["wallNs"]) / int(row["count"])
        elif row["bytes"]:
            assert int(row["afterBytes"]) - int(row["beforeBytes"]) == int(row["bytes"])
            row["processBytesPerCall"] = int(row["bytes"]) / int(row["count"])
    local_summary = []
    for scenario in ("immediate", "parcel"):
        for kind, metric in (("TIME", "cpuNsPerCall"), ("ALLOC", "processBytesPerCall")):
            medians = {}
            for mode in ("legacy", "optimized"):
                selected = [row for row in local if row["scenario"] == scenario and row["kind"] == kind and row["mode"] == mode]
                assert len(selected) == 2
                values = [row[metric] for row in selected if metric in row]
                medians[mode] = statistics.median(values) if values else None
            before, after = medians["legacy"], medians["optimized"]
            local_summary.append(dict(scenario=scenario, metric=metric, **medians,
                                      changePercent=(after / before - 1) * 100 if before and after is not None else None))
    ipc = []
    for round_number in range(1, 5):
        for size in (16, 1024):
            for mode in ("legacy", "optimized"):
                path = directory / f"pending-ipc-{run}-r{round_number}-{mode}-{size}.csv"
                values = sorted(int(row) for row in path.read_text().splitlines())
                assert len(values) == 1000 and values[0] > 0
                ipc.append(dict(round=round_number, size=size, mode=mode, count=1000,
                                p50Ms=values[499] / 1e6, p90Ms=values[899] / 1e6,
                                p99Ms=values[989] / 1e6, maxMs=values[-1] / 1e6,
                                meanMs=statistics.mean(values) / 1e6))
    ipc_summary = []
    for size in (16, 1024):
        medians = {mode: statistics.median(row["p50Ms"] for row in ipc if row["size"] == size and row["mode"] == mode)
                   for mode in ("legacy", "optimized")}
        changes = []
        for round_number in range(1, 5):
            pair = {row["mode"]: row["p50Ms"] for row in ipc if row["size"] == size and row["round"] == round_number}
            changes.append((pair["optimized"] / pair["legacy"] - 1) * 100)
        ipc_summary.append(dict(size=size, **medians, changePercent=(medians["optimized"] / medians["legacy"] - 1) * 100,
                                pairedChangePercent=changes))
    result = dict(run=run, local=local, localSummary=local_summary, ipc=ipc, ipcSummary=ipc_summary)
    (directory / "analysis.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(localSummary=local_summary, ipcSummary=ipc_summary), indent=2))
    print("\n| Round | Characters | Mode | P50 ms | P99 ms | Max ms |")
    print("| --- | ---: | --- | ---: | ---: | ---: |")
    for row in ipc:
        print(f"| {row['round']} | {row['size']} | {row['mode']} | {row['p50Ms']:.3f} | {row['p99Ms']:.3f} | {row['maxMs']:.3f} |")


if __name__ == "__main__":
    main(Path(sys.argv[1]), sys.argv[2])
