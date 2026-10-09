"""Plot qualified saved request phases; no device access or overlapping phase sums."""
import csv
import json
import os
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / ".gradle/plot-deps"))
os.environ.setdefault("MPLCONFIGDIR", str(ROOT / ".gradle/plot-config"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def main():
    source = ROOT / "docs/benchmarks/runs/2026-10-09-xiaomi14ultra/ordered-trace-on4"
    metadata = json.loads((source / "capture-metadata.json").read_text(encoding="utf-8"))
    qualification = json.loads((source / "qualification.json").read_text(encoding="utf-8"))
    assert metadata["captureValid"] and qualification["valid"]
    with (source / "request-stages.csv").open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    assert len(rows) == 1000 and all(row["run"] == "traceon4" for row in rows)
    phases = ["client_resolve", "client_register", "client_encode", "request_transport",
              "server_admission", "server_queue", "server_business", "server_reply_encode",
              "reply_transport", "client_resume"]
    summaries = []
    for phase in phases + ["total"]:
        values = sorted(int(row[phase + "Ns"]) / 1000 for row in rows)
        summaries.append(dict(phase=phase, count=len(values), meanUs=statistics.mean(values),
                              p50Us=values[499], p90Us=values[899], p99Us=values[989]))
    assert abs(sum(row["meanUs"] for row in summaries[:-1]) - summaries[-1]["meanUs"]) < 1e-6
    output = source.parent / "cold-trace-analysis"
    output.mkdir(exist_ok=True)
    (output / "stage-summary.json").write_text(json.dumps(summaries, indent=2) + "\n", encoding="utf-8")
    fig, axes = plt.subplots(1, 2, figsize=(14, 6.5), gridspec_kw={"width_ratios": [1.35, 1]})
    means = [row["meanUs"] for row in summaries[:-1]]
    axes[0].barh([phase.replace("_", " ") for phase in phases], means, color="#2681a8")
    axes[0].invert_yaxis()
    for index, value in enumerate(means):
        axes[0].text(value + 1, index, f"{value:.1f}", va="center", fontsize=9)
    axes[0].set_xlim(0, max(means) * 1.2)
    axes[0].set_xlabel("Mean per request (microseconds)")
    axes[0].set_title(f"Non-overlapping phases | mean total {summaries[-1]['meanUs']:.1f} us")
    axes[0].grid(axis="x", alpha=.2)
    labels = ["Server admission\nBinder thread", "Server queue\ntarget worker", "Client resume\ntarget worker"]
    with (source / "correlated-analysis/request-thread-state-breakdown.csv").open(encoding="utf-8", newline="") as stream:
        intersections = list(csv.DictReader(stream))
    states = []
    for interval, role in (("server_admission", "receive_thread"), ("server_queue", "target_worker"),
                           ("client_resume", "target_worker")):
        selected = [row for row in intersections if row["interval"] == interval and row["role"] == role]
        assert len(selected) == 1000 and all(int(row["uncoveredNs"]) == 0 for row in selected)
        states.append(tuple(statistics.mean(int(row[key]) for row in selected) / 1000
                            for key in ("runningNs", "runnableNs", "sleepNs")))
    left = [0.] * 3
    for index, (name, color) in enumerate(zip(["Running", "Ready", "Sleep"], ["#2681a8", "#e7a13c", "#bdc9d0"])):
        values = [row[index] for row in states]
        axes[1].barh(labels, values, left=left, label=name, color=color)
        left = [a + b for a, b in zip(left, values)]
    axes[1].invert_yaxis()
    axes[1].set_xlabel("Mean thread residency in phase (microseconds)")
    axes[1].set_title("Scheduler state intersections | 1000 matched requests")
    axes[1].set_xlim(0, 250)
    axes[1].legend(loc="lower right")
    axes[1].grid(axis="x", alpha=.2)
    fig.suptitle("Xiaomi 14 Ultra | traceon4 | diagnostic tracing enabled", fontsize=15)
    fig.text(.5, .025, "Sleep includes dispatch/callback preparation. Thread windows overlap; do not sum the right-hand rows.", ha="center", fontsize=10)
    fig.text(.5, .005, "These instrumented phase costs are not tracing-disabled optimization gains. Original events, native trace and SQL are retained.", ha="center", fontsize=9)
    fig.tight_layout(rect=(0, .06, 1, .94))
    fig.savefig(output / "request-costs.png", dpi=160)
    print(output)


if __name__ == "__main__":
    main()
