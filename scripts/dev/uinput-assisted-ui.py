#!/usr/bin/env python3
"""Kernel-level input driver for boundingbox-warp-mirror assisted UI validation.

Why: the host runs niri (Wayland) -> xwayland-satellite -> Xwayland :0 -> Wine.
XTEST/xdotool clicks and in-JVM Robot events never reach the Wine client on
this stack (pointer MOTION tracks fine, button/key events die). /dev/uinput
injects real evdev events — indistinguishable from hardware input — which niri
routes to the focused surface like a physical mouse.

Protocol (markers stream live into turboism-home/logs/runtime/*/turboism-*.log):
  WARP_MIRROR_UI_CLICK_TARGET seq=N x=X y=Y mod=none|ctrl
      AWT-space click target (JTable row centre / overlay-button candidate).
      Each unseen seq is clicked in order so plain+Ctrl sequences are kept.
  WARP_MIRROR_UI_MAINWIN x=.. y=.. w=.. h=..
      Largest non-probe window's AWT bounds; the driver scans the real pointer
      (xdotool getmouselocation, which does track kernel-injected motion) to
      locate the Cubism X window and derives the AWT->X offset.
  WARP_MIRROR_PROBE_RESULT / WARP_MIRROR_PROBE_JVM_EXIT
      Terminal markers.

Usage: uinput-assisted-ui.py <queue-dir>
"""
import glob
import os
import re
import subprocess
import sys
import time

import evdev

QUEUE = sys.argv[1]
SCREEN_W, SCREEN_H = 1920, 1080
DEADLINE = time.time() + 4200

ui = evdev.UInput(
    {
        evdev.ecodes.EV_ABS: {
            evdev.ecodes.ABS_X: evdev.AbsInfo(0, 0, SCREEN_W - 1, 0, 0, 0),
            evdev.ecodes.ABS_Y: evdev.AbsInfo(0, 0, SCREEN_H - 1, 0, 0, 0),
        },
        evdev.ecodes.EV_REL: [
            evdev.ecodes.REL_X,
            evdev.ecodes.REL_Y,
        ],
        evdev.ecodes.EV_KEY: [
            evdev.ecodes.BTN_LEFT,
            evdev.ecodes.BTN_RIGHT,
            evdev.ecodes.KEY_LEFTCTRL,
        ],
    },
    name="warp-mirror-assisted-driver",
    vendor=0x1,
    product=0x1,
)
time.sleep(0.5)  # let niri/Xwayland adopt the virtual device


def log(msg):
    print(f"[udrive {time.strftime('%H:%M:%S')}] {msg}", flush=True)


def find_log():
    files = glob.glob(
        os.path.join(QUEUE, "turboism-home/logs/runtime/*/turboism-*.log"))
    return max(files, key=os.path.getmtime) if files else None


def _rel(dx, dy):
    if dx:
        ui.write(evdev.ecodes.EV_REL, evdev.ecodes.REL_X, dx)
    if dy:
        ui.write(evdev.ecodes.EV_REL, evdev.ecodes.REL_Y, dy)
    ui.syn()


def _abs(x, y):
    ui.write(evdev.ecodes.EV_ABS, evdev.ecodes.ABS_X, max(0, min(x, SCREEN_W - 1)))
    ui.write(evdev.ecodes.EV_ABS, evdev.ecodes.ABS_Y, max(0, min(y, SCREEN_H - 1)))
    ui.syn()


def pointer_pos():
    try:
        out = subprocess.run(
            ["xdotool", "getmouselocation", "--shell"],
            capture_output=True, text=True, timeout=3,
            env={**os.environ, "DISPLAY": ":0"}).stdout
        mx = re.search(r"^X=(\d+)", out, re.M)
        my = re.search(r"^Y=(\d+)", out, re.M)
        if mx and my:
            return int(mx.group(1)), int(my.group(1))
    except Exception:
        pass
    return None


def move(x, y):
    """Closed-loop move: emit ABS then correct with REL until the real pointer
    lands within 3px — robust to any compositor device-mapping distortion."""
    _abs(x, y)
    time.sleep(0.08)
    for _ in range(6):
        pos = pointer_pos()
        if pos is None:
            return
        dx, dy = x - pos[0], y - pos[1]
        if abs(dx) <= 3 and abs(dy) <= 3:
            return
        step_x = max(-60, min(60, dx))
        step_y = max(-60, min(60, dy))
        _rel(step_x, step_y)
        time.sleep(0.06)


def click(x, y, ctrl=False):
    move(x, y)
    time.sleep(0.06)
    if ctrl:
        ui.write(evdev.ecodes.EV_KEY, evdev.ecodes.KEY_LEFTCTRL, 1)
        ui.syn()
        time.sleep(0.06)
    ui.write(evdev.ecodes.EV_KEY, evdev.ecodes.BTN_LEFT, 1)
    ui.syn()
    time.sleep(0.07)
    ui.write(evdev.ecodes.EV_KEY, evdev.ecodes.BTN_LEFT, 0)
    ui.syn()
    if ctrl:
        time.sleep(0.05)
        ui.write(evdev.ecodes.EV_KEY, evdev.ecodes.KEY_LEFTCTRL, 0)
        ui.syn()


def window_at(x, y):
    """X window id under the pointer via Xwayland (motion is kernel-real)."""
    move(x, y)
    time.sleep(0.05)
    pos_w = None
    try:
        out = subprocess.run(
            ["xdotool", "getmouselocation", "--shell"],
            capture_output=True, text=True, timeout=3,
            env={**os.environ, "DISPLAY": ":0"}).stdout
        m = re.search(r"^WINDOW=(\d+)", out, re.M)
        pos_w = int(m.group(1)) if m else None
    except Exception:
        pass
    return pos_w


def cubism_window():
    try:
        out = subprocess.run(
            ["xdotool", "search", "--name", "Live2D Cubism Editor"],
            capture_output=True, text=True, timeout=3,
            env={**os.environ, "DISPLAY": ":0"}).stdout.strip().split()
        return int(out[0]) if out else None
    except Exception:
        return None


def activate(wid):
    if wid:
        subprocess.run(["xdotool", "windowactivate", "--sync", str(wid)],
                       capture_output=True, timeout=4,
                       env={**os.environ, "DISPLAY": ":0"})


OFF_X, OFF_Y = 4, 10  # fallback until calibrated
calibrated = False
last_seq = 0


def calibrate(log_path, cw):
    """Locate the Cubism X window's real bounds via pointer-hit scan and derive
    OFF = X_pos - AWT_pos from the WARP_MIRROR_UI_MAINWIN marker."""
    global OFF_X, OFF_Y, calibrated
    line = None
    try:
        with open(log_path, errors="replace") as fh:
            for l in fh:
                if "WARP_MIRROR_UI_MAINWIN" in l:
                    line = l
    except OSError:
        return
    if not line:
        return
    m = re.search(r"x=(-?\d+) y=(-?\d+)", line)
    if not m:
        return
    ax, ay = int(m.group(1)), int(m.group(2))
    minx, miny, maxx, maxy = 10**9, 10**9, -1, -1
    for y in range(0, SCREEN_H, 120):
        for x in range(0, SCREEN_W, 120):
            if window_at(x, y) == cw:
                minx, miny = min(minx, x), min(miny, y)
                maxx, maxy = max(maxx, x), max(maxy, y)
    if maxx < 0:
        log("calibration: cubism window not hit")
        return
    for bx in range(max(0, minx - 120), minx + 1, 8):
        if window_at(bx, miny) == cw:
            minx = bx
            break
    for by in range(max(0, miny - 120), miny + 1, 8):
        if window_at(minx, by) == cw:
            miny = by
            break
    OFF_X, OFF_Y = minx - ax, miny - ay
    calibrated = True
    log(f"calibrated: X rect {minx},{miny}..{maxx},{maxy} "
        f"awt={ax},{ay} off={OFF_X},{OFF_Y}")


log(f"driver armed: queue={QUEUE} dev={ui.device.path}")

while time.time() < DEADLINE:
    path = find_log()
    if not path:
        time.sleep(2)
        continue
    try:
        with open(path, errors="replace") as fh:
            content = fh.read()
    except OSError:
        time.sleep(1)
        continue
    if "WARP_MIRROR_PROBE_JVM_EXIT" in content or \
       "WARP_MIRROR_PROBE_RESULT" in content:
        log("probe finished — driver exit")
        break

    cw = cubism_window()
    if cw and not calibrated:
        calibrate(path, cw)

    targets = []
    for m in re.finditer(
            r"WARP_MIRROR_UI_CLICK_TARGET seq=(\d+) x=(-?\d+) y=(-?\d+) mod=(\w+)",
            content):
        seq = int(m.group(1))
        if seq > last_seq:
            targets.append((seq, int(m.group(2)), int(m.group(3)), m.group(4)))
    for seq, tx, ty, mod in sorted(targets):
        last_seq = seq
        px, py = tx + OFF_X, ty + OFF_Y
        activate(cw)
        click(px, py, ctrl=(mod == "ctrl"))
        log(f"click seq={seq} awt={tx},{ty} -> X={px},{py} mod={mod}")
        time.sleep(0.25)
    time.sleep(0.35)

ui.close()
log("driver exit")
