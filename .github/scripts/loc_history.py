#!/usr/bin/env python3
"""Draws the lines of today's code by the month they were written, and their running total, as an SVG."""
import re
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
# Blank lines and bare braces exist in every file, so they keep the date of their own commit
WORD_CHARACTER = re.compile(r"\w")


def git(*arguments):
    return subprocess.run(["git", *arguments], check=True, capture_output=True, text=True, errors="replace").stdout


def blamed_lines(path):
    lines = []
    # Follows lines moved or copied between files and commits, so relocated code keeps its first date
    for line in git("blame", "-w", "-M", "-C", "-C", "-C", "--line-porcelain", "HEAD", "--", path).splitlines():
        if line.startswith("author-time "):
            written = int(line.split()[1])
        elif line.startswith("\t"):
            lines.append((written, line.strip()))
    return lines


def first_seen_on_any_branch():
    history = subprocess.Popen(
        ["git", "log", "--branches", "--remotes", "--full-history", "--no-merges", "-p", "-U0", "--format=date:%at", "--"] + SOURCE_FILES,
        stdout=subprocess.PIPE, text=True, errors="replace")
    first_seen, written, in_header = {}, 0, True
    for line in history.stdout:
        if line.startswith("date:"):
            written = int(line[5:])
        elif line.startswith("diff "):
            in_header = True
        elif line.startswith("@@"):
            in_header = False
        elif line.startswith("+") and not in_header:
            text = line[1:].strip()
            if WORD_CHARACTER.search(text):
                first_seen[text] = min(first_seen.get(text, written), written)
    return first_seen


def lines_per_month():
    with ThreadPoolExecutor() as pool:
        files = pool.map(blamed_lines, git("ls-files", "--", *SOURCE_FILES).splitlines())
        lines = [line for file in files for line in file]
    first_seen = first_seen_on_any_branch()
    return Counter(
        datetime.fromtimestamp(min(written, first_seen.get(text, written)), timezone.utc).strftime("%Y-%m")
        for written, text in lines)


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
