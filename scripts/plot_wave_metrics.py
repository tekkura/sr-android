#!/usr/bin/env python3
import argparse
import re
import subprocess
import time
from collections import deque

import matplotlib.animation as animation
import matplotlib.pyplot as plt


METRIC_RE = re.compile(r"waveMetrics (?P<body>.*)$")


def parse_metrics(line):
    match = METRIC_RE.search(line)
    if not match:
        return None
    values = {}
    for item in match.group("body").split():
        if "=" not in item:
            continue
        key, raw_value = item.split("=", 1)
        if raw_value in ("true", "false"):
            values[key] = 1.0 if raw_value == "true" else 0.0
        else:
            try:
                values[key] = float(raw_value)
            except ValueError:
                pass
    return values


def main():
    parser = argparse.ArgumentParser(description="Live plot kidsFaceDemo wave metrics from logcat.")
    parser.add_argument("--seconds", type=float, default=20.0, help="Visible plot history.")
    args = parser.parse_args()

    proc = subprocess.Popen(
        ["adb", "logcat", "-s", "KidsFaceDemo:I"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )

    start = time.monotonic()
    points = deque()

    fig, axes = plt.subplots(3, 1, sharex=True)
    fig.suptitle("kidsFaceDemo wave metrics")
    lines = {
        "score": axes[0].plot([], [], label="score")[0],
        "normalizedMotion": axes[0].plot([], [], label="normalizedMotion")[0],
        "leftMotion": axes[1].plot([], [], label="leftMotion")[0],
        "rightMotion": axes[1].plot([], [], label="rightMotion")[0],
        "shoulderWidth": axes[1].plot([], [], label="shoulderWidth")[0],
        "leftRaised": axes[2].plot([], [], label="leftRaised")[0],
        "rightRaised": axes[2].plot([], [], label="rightRaised")[0],
        "person": axes[2].plot([], [], label="person")[0],
    }
    for axis in axes:
        axis.legend(loc="upper right")
        axis.grid(True)
    axes[0].set_ylim(-0.05, 1.2)
    axes[2].set_ylim(-0.1, 1.1)

    def update(_frame):
        if proc.stdout is None:
            return lines.values()
        while True:
            line = proc.stdout.readline()
            if not line:
                break
            parsed = parse_metrics(line)
            if parsed is not None:
                points.append((time.monotonic() - start, parsed))
        cutoff = time.monotonic() - start - args.seconds
        while points and points[0][0] < cutoff:
            points.popleft()

        xs = [point[0] for point in points]
        for key, line in lines.items():
            line.set_data(xs, [point[1].get(key, 0.0) for point in points])
        if xs:
            axes[-1].set_xlim(max(0.0, xs[-1] - args.seconds), xs[-1] + 0.5)
        return lines.values()

    try:
        animation.FuncAnimation(fig, update, interval=100, cache_frame_data=False)
        plt.show()
    finally:
        proc.terminate()


if __name__ == "__main__":
    main()
