"""Plot all within-run serial dispatch pairs from the validated offline report."""
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / ".gradle/plot-deps"))
os.environ.setdefault("MPLCONFIGDIR", str(ROOT / ".gradle/plot-config"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def main():
    directory = ROOT / "docs/benchmarks/runs/2026-10-09-xiaomi14ultra/dispatch-analysis"
    report = json.loads((directory / "dispatch-analysis.json").read_text(encoding="utf-8"))
    assert report["valid"] and len(report["runs"]) == 2
    fig, axes = plt.subplots(1, 2, figsize=(11.5, 4.7), sharey=True)
    for panel, (axis, run) in enumerate(zip(axes, report["runs"])):
        for size, color in ((16, "#2681a8"), (1024, "#b6791f")):
            pairs = sorted((row for row in run["pairedRounds"] if row["phase"] == "serial"
                            and row["target"] == "dedicated" and row["payloadChars"] == size), key=lambda row: row["round"])
            assert len(pairs) == 4
            values = [row["changePercent"]["p50Ms"] for row in pairs]
            axis.plot(range(1, 5), values, "o-", color=color, label=f"{size} characters")
            for index, value in enumerate(values, 1):
                axis.annotate(f"{value:+.1f}%", (index, value), xytext=(4, 6 if size == 16 else -13), textcoords="offset points", fontsize=9)
        axis.axhline(0, color="#6a6a6a", linewidth=1)
        axis.set_title(run["run"])
        axis.set_xticks(range(1, 5))
        axis.set_xlabel("Paired round (separate measurement blocks)")
        axis.set_xlim(.7, 4.55)
        axis.set_ylim(-48, 32)
        axis.grid(alpha=.2)
        axis.legend(loc="upper right" if panel == 0 else "lower left")
    axes[0].set_ylabel("Dedicated P50 change vs Default (%)")
    fig.suptitle("Xiaomi 14 Ultra | same APK | every serial paired round", fontsize=14)
    fig.text(.5, .025, "Negative: lower RTT. Run 2 small-payload direction reverses twice. Captures have different background process presence; not pooled.", ha="center", fontsize=8.5)
    fig.tight_layout(rect=(0, .06, 1, .93))
    fig.savefig(directory / "paired-p50.png", dpi=160)
    print(directory / "paired-p50.png")


if __name__ == "__main__":
    main()
