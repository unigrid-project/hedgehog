#!/usr/bin/env python3
"""Draws the lines of today's code by the month they were written, and their running total, as an SVG."""
import subprocess
import sys
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timezone

import matplotlib
matplotlib.use("svg")
import matplotlib.dates as mdates
import matplotlib.pyplot as plt

SOURCE_FILES = ["*.java", "*.xml", "*.yml", "*.yaml", "*.sh", "*.md"]
WRITTEN, TOTAL = "#2da44e", "#0969da"


def git(*arguments):
    return subprocess.run(["git", *arguments], check=True, capture_output=True, text=True).stdout


def months_written(path):
    blame = git("blame", "--line-porcelain", "HEAD", "--", path)
    return Counter(
        datetime.fromtimestamp(int(line.split()[1]), timezone.utc).strftime("%Y-%m")
        for line in blame.splitlines() if line.startswith("author-time "))


def lines_per_month():
    with ThreadPoolExecutor() as pool:
        return sum(pool.map(months_written, git("ls-files", "--", *SOURCE_FILES).splitlines()), Counter())


def every_month(first, last):
    year, month = map(int, first.split("-"))
    while (year, month) <= tuple(map(int, last.split("-"))):
        yield date(year, month, 1)
        year, month = (year + 1, 1) if month == 12 else (year, month + 1)


def draw(lines, target):
    months = list(every_month(min(lines), max(lines)))
    written = [lines[m.strftime("%Y-%m")] for m in months]
    total = [sum(written[:i + 1]) for i in range(len(months))]

    figure, bars = plt.subplots(figsize=(10, 4), facecolor="white")
    bars.bar(months, written, width=25, color=WRITTEN, label="Lines written that month")
    line = bars.twinx()
    line.plot(months, total, color=TOTAL, linewidth=2, label="Total lines")
    line.set_ylim(bottom=0)

    bars.xaxis.set_major_locator(mdates.YearLocator())
    bars.xaxis.set_major_formatter(mdates.DateFormatter("%Y"))
    bars.set_ylabel("Lines written per month")
    line.set_ylabel("Total lines")
    bars.spines[["top"]].set_visible(False)
    line.spines[["top"]].set_visible(False)
    handles = bars.get_legend_handles_labels()[0] + line.get_legend_handles_labels()[0]
    bars.legend(handles=handles, loc="upper left", frameon=False)
    figure.tight_layout()
    figure.savefig(target, format="svg", metadata={"Date": None})


if __name__ == "__main__":
    plt.rcParams["svg.hashsalt"] = "loc-history"
    draw(lines_per_month(), sys.argv[1])
