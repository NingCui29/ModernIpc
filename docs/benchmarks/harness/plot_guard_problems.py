"""Visualize saved RTT drift and paired block medians; no new device measurements."""
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


def main() -> None:
    directory = ROOT / "docs/benchmarks/runs/2026-10-08-xiaomi14ultra/analysis-current-problems"
    data = json.loads((directory / "problem-analysis.json").read_text())
    fig, axes = plt.subplots(2, 2, figsize=(13.5, 8.5))
    for column, run in enumerate(data["runs"]):
        axis = axes[0, column]
        # R4/16 is an explicitly selected drift example. The JSON retains every block.
        for mode, color in (("lookup", "#677889"), ("guard", "#007fb5")):
            block = next(row for row in run["blocks"] if row["round"] == 4 and row["size"] == 16 and row["mode"] == mode)
            samples = list(map(int, (ROOT / block["csv"]).read_text().splitlines()))
            centers = [start + 25.5 for start in range(0, 1000, 50)]
            medians = [statistics.median(samples[start:start + 50]) / 1e6 for start in range(0, 1000, 50)]
            axis.plot(centers, medians, "o-", color=color, markersize=3,
                      label=f"{mode}: lag-1 r = {block['lagOnePearson']:.3f}")
        axis.set_title(f"{run['run']}: R4 / 16 characters (drift example)")
        axis.set_xlabel("Sample index within each separate block")
        axis.set_ylabel("Median RTT per 50 consecutive samples (ms)")
        axis.set_xlim(0, 1000)
        axis.set_ylim(bottom=0)
        axis.legend(fontsize=9)
        axis.grid(alpha=0.2)

        axis = axes[1, column]
        for item, color in zip(run["comparisons"], ("#c27200", "#7b55a3")):
            axis.plot([row["round"] for row in item["pairs"]], [row["deltaUs"] for row in item["pairs"]],
                      "o-", color=color, label=f"{item['size']} characters")
        axis.axhline(0, color="#555555", linewidth=1)
        axis.set_title(f"{run['run']}: all four paired rounds")
        axis.set_xlabel("Paired round (ABBA mode order)")
        axis.set_ylabel("Guard P50 - lookup P50 (microseconds)")
        axis.set_xticks((1, 2, 3, 4))
        axis.legend(fontsize=9)
        axis.grid(alpha=0.2)

    fig.suptitle("Xiaomi 14 Ultra | Same APK | Saved qualified runs: drift and sign changes", fontsize=15)
    fig.text(0.5, 0.019, "Top: separate measurement blocks, not simultaneous samples; examples only. Bottom: negative means lower guard RTT.",
             ha="center", fontsize=9)
    fig.text(0.5, 0.003, "Local pre-send CPU saving is about 0.60 microseconds; these full RTT deltas do not identify its causal effect.",
             ha="center", fontsize=9)
    fig.tight_layout(rect=(0, 0.05, 1, 0.955))
    output = directory / "guard-problems.png"
    fig.savefig(output, dpi=150)
    plt.close(fig)
    print(output)


if __name__ == "__main__":
    main()
