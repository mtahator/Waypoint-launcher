#!/usr/bin/env python3
"""Tiny adb UI driver for the CI smoke test: find a view by text (scrolling if needed) and tap it."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args, check=True):
    return subprocess.run(["adb", *args], check=check, capture_output=True, text=True).stdout


def screen_size():
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    return int(m.group(1)), int(m.group(2))


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
    try:
        return ET.fromstring(xml[xml.index("<"):])
    except (ValueError, ET.ParseError):
        return None


def find(text, exact=False):
    root = dump()
    if root is None:
        return None
    for node in root.iter("node"):
        if node.get("class", "").endswith("EditText"):
            continue  # never "tap" a text field because it contains the text we're looking for
        t = node.get("text", "") or node.get("content-desc", "")
        if (t == text) if exact else (text.lower() in t.lower()):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def tap_text(text, scrolls=8, exact=False):
    w, h = screen_size()
    for _ in range(scrolls + 1):
        pos = find(text, exact)
        if pos:
            adb("shell", "input", "tap", str(pos[0]), str(pos[1]))
            time.sleep(1.5)
            return True
        # Scroll in the upper half so a visible keyboard never receives the swipe (gesture typing)
        adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.45)), str(w // 2), str(int(h * 0.2)), "300")
        time.sleep(0.8)
    print(f"!! not found: {text}", file=sys.stderr)
    return False


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "tap":
        sys.exit(0 if tap_text(sys.argv[2], exact=len(sys.argv) > 3) else 1)
    if cmd == "size":
        print(*screen_size())
