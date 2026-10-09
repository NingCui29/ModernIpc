"""Diagnose ordering/drift in saved Xiaomi guard samples; no new device tests or gain inference."""
import json
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "docs/benchmarks/runs/2026-10-08-xiaomi14ultra"


def analyze(run_number: int) -> dict:
    directory = BASE / f"ordered-guardperf-{run_number}"
    source = json.loads((directory / "analysis.json").read_text())
    evidence = json.loads((directory / "evidence.json").read_text())
    assert source["foreground"]["valid"]
    blocks = []
    for round_number in range(1, 5):
        for size in (16, 1024):
            for mode in ("lookup", "guard"):
                path = directory / f"guard-ipc-guardperf{run_number}-r{round_number}-{mode}-{size}.csv"
                values = list(map(int, path.read_text().splitlines()))
                assert len(values) == 1000 and min(values) > 0
                # Preserve acquisition order. Correlation is descriptive; no stationary-process assumption is made.
                lag_one = statistics.correlation(values[:-1], values[1:])
                quarters = [statistics.median(values[start:start + 250]) / 1e6 for start in range(0, 1000, 250)]
                blocks.append(dict(round=round_number, size=size, mode=mode,
                                   p50Ms=sorted(values)[499] / 1e6, quartersMedianMs=quarters,
                                   lagOnePearson=lag_one, csv=path.relative_to(ROOT).as_posix()))
    comparisons = []
    for size in (16, 1024):
        pairs = []
        for round_number in range(1, 5):
            pair = {row["mode"]: row["p50Ms"] for row in blocks if row["size"] == size and row["round"] == round_number}
            pairs.append(dict(round=round_number, lookupMs=pair["lookup"], guardMs=pair["guard"],
                              deltaUs=(pair["guard"] - pair["lookup"]) * 1000,
                              changePercent=(pair["guard"] / pair["lookup"] - 1) * 100))
        ranges = {}
        for mode in ("lookup", "guard"):
            values = [row["p50Ms"] for row in blocks if row["size"] == size and row["mode"] == mode]
            ranges[mode] = dict(minMs=min(values), maxMs=max(values), rangeUs=(max(values) - min(values)) * 1000)
        comparisons.append(dict(size=size, pairs=pairs, ranges=ranges,
                                medianPairedDeltaUs=statistics.median(row["deltaUs"] for row in pairs),
                                medianPairedChangePercent=statistics.median(row["changePercent"] for row in pairs)))
    cpu = next(row for row in source["localSummary"] if row["metric"] == "threadCpuNsPerStep")
    return dict(run=source["run"], apkSha256=evidence["apkSha256"], blocks=blocks, comparisons=comparisons,
                localCpuDifferenceUs=(cpu["lookup"] - cpu["guard"]) / 1000,
                minimumLagOnePearson=min(row["lagOnePearson"] for row in blocks),
                maximumLagOnePearson=max(row["lagOnePearson"] for row in blocks))


def main() -> None:
    result = dict(scope="saved qualified Xiaomi runs; descriptive drift and serial-dependence checks only",
                  runs=[analyze(3), analyze(5)])
    output = BASE / "analysis-current-problems"
    output.mkdir(exist_ok=True)
    (output / "problem-analysis.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    for run in result["runs"]:
        print(run["run"], "local CPU difference us", run["localCpuDifferenceUs"],
              "lag1 range", run["minimumLagOnePearson"], run["maximumLagOnePearson"])
        for item in run["comparisons"]:
            print("  size", item["size"], "P50 ranges us", {mode: row["rangeUs"] for mode, row in item["ranges"].items()},
                  "median paired delta us", item["medianPairedDeltaUs"])
    print(output / "problem-analysis.json")


if __name__ == "__main__":
    main()
