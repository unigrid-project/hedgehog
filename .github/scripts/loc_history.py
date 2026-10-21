#!/usr/bin/env python3
"""Draws the lines added and removed per month, and the running total, as an SVG."""
import subprocess
import sys
from collections import defaultdict
from datetime import date

import matplotlib
matplotlib.use("svg")
import matplotlib.dates as mdates
import matplotlib.pyplot as plt

SOURCE_FILES = ["*.java", "*.xml", "*.yml", "*.yaml", "*.sh", "*.md"]
ADDED, REMOVED, TOTAL = "#2da44e", "#cf222e", "#0969da"


def monthly_changes():
    log = subprocess.run(
        ["git", "log", "--numstat", "--format=%ad", "--date=format:%Y-%m", "--"] + SOURCE_FILES,
        check=True, capture_output=True, text=True).stdout
    changes, month = defaultdict(lambda: [0, 0]), None
    for line in log.splitlines():
        if "\t" not in line:
            month = line or month
            continue
        added, removed, _ = line.split("\t", 2)
        if added != "-":
            changes[month][0] += int(added)
            changes[month][1] += int(removed)
    return changes


def every_month(first, last):
    year, month = map(int, first.split("-"))
    while (year, month) <= tuple(map(int, last.split("-"))):
        yield date(year, month, 1)
        year, month = (year + 1, 1) if month == 12 else (year, month + 1)


def draw(changes, target):
    months = list(every_month(min(changes), max(changes)))
    added = [changes[m.strftime("%Y-%m")][0] for m in months]
    removed = [-changes[m.strftime("%Y-%m")][1] for m in months]
    total = [sum(a + r for a, r in zip(added[:i + 1], removed[:i + 1])) for i in range(len(months))]

    figure, bars = plt.subplots(figsize=(10, 4), facecolor="white")
    bars.bar(months, added, width=25, color=ADDED, label="Lines added")
    bars.bar(months, removed, width=25, color=REMOVED, label="Lines removed")
    line = bars.twinx()
    line.plot(months, total, color=TOTAL, linewidth=2, label="Total lines")
    line.set_ylim(bottom=0)

    bars.xaxis.set_major_locator(mdates.YearLocator())
    bars.xaxis.set_major_formatter(mdates.DateFormatter("%Y"))
    bars.set_ylabel("Lines changed per month")
    line.set_ylabel("Total lines")
    bars.spines[["top"]].set_visible(False)
    line.spines[["top"]].set_visible(False)
    handles = bars.get_legend_handles_labels()[0] + line.get_legend_handles_labels()[0]
    bars.legend(handles=handles, loc="upper left", frameon=False)
    figure.tight_layout()
    figure.savefig(target, format="svg", metadata={"Date": None})


if __name__ == "__main__":
    plt.rcParams["svg.hashsalt"] = "loc-history"
    draw(monthly_changes(), sys.argv[1])
