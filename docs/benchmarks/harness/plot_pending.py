"""Plot the complete local path and each paired IPC round from analyze_pending output."""
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / ".gradle/plot-deps"))
os.environ["MPLCONFIGDIR"] = str(ROOT / ".gradle/plot-config")
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def main(directory: Path) -> None:
    data = json.loads((directory / "analysis.json").read_text())
    colors = {"legacy": "#687788", "optimized": "#007fbc"}
    labels = {"legacy": "Baseline (3 atomics)", "optimized": "Optimized (1 atomic)"}
    fig, axes = plt.subplots(2, 2, figsize=(11, 7), layout="constrained")
    for ax, metric, scale, title, unit in (
        (axes[0, 0], "cpuNsPerCall", 1000, "Complete local registry: thread CPU", "Microseconds / call"),
        (axes[0, 1], "processBytesPerCall", 1, "Local batch: process allocation estimate", "Bytes / call"),
    ):
        for offset, mode in ((-0.18, "legacy"), (0.18, "optimized")):
            rows = [next(row for row in data["localSummary"] if row["scenario"] == scenario and row["metric"] == metric)
                    for scenario in ("immediate", "parcel")]
            values = [row[mode] / scale for row in rows]
            bars = ax.bar([offset, 1 + offset], values, 0.35, color=colors[mode], label=labels[mode])
            ax.bar_label(bars, fmt="%.2f", padding=3, fontsize=9)
        ax.set(xticks=[0, 1], xticklabels=["Immediate completion", "Parcel completion"], ylabel=unit, title=title)
        ax.set_ylim(0, ax.get_ylim()[1] * 1.15)
        ax.grid(axis="y", alpha=0.2)
    for ax, size in ((axes[1, 0], 16), (axes[1, 1], 1024)):
        for mode in ("legacy", "optimized"):
            rows = [row for row in data["ipc"] if row["size"] == size and row["mode"] == mode]
            ax.plot([row["round"] for row in rows], [row["p50Ms"] for row in rows], "o-", color=colors[mode], label=labels[mode])
        ax.set(title=f"Same-wire Async IPC: {size} characters", xlabel="Paired round (ABBA order)", ylabel="P50 milliseconds", xticks=[1, 2, 3, 4])
        ax.grid(alpha=0.2)
    axes[0, 0].legend(fontsize=9)
    fig.suptitle("Xiaomi 14 Ultra | Same APK | CPU, allocation and RTT are separate measurements", fontsize=12)
    fig.savefig(directory / "pending-performance.png", dpi=180)
    plt.close(fig)


if __name__ == "__main__":
    main(Path(sys.argv[1]))
