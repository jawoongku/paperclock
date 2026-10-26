#!/usr/bin/env python3
"""Switch this PAPER PRO between a custom sleep image and the installed clock."""
import argparse
import os
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree as ET

SERIAL = os.environ.get("ADB_SERIAL")


def adb(*args):
    command = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    p = subprocess.run(command, check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return p.stdout.decode("utf-8", "replace")


def shell(command):
    return adb("shell", command)


def wakefulness():
    return re.search(r"mWakefulness=(\w+)", shell("dumpsys power")).group(1)


def wake():
    if wakefulness() == "Asleep":
        shell("input keyevent 26")
        time.sleep(1)


def sleep():
    if wakefulness() != "Asleep":
        shell("input keyevent 26")
        time.sleep(2)


def nodes():
    shell("uiautomator dump /sdcard/ridi-control.xml")
    return ET.fromstring(shell("cat /sdcard/ridi-control.xml")).iter("node")


def tap(label):
    for n in nodes():
        if label in (n.get("text"), n.get("content-desc")):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            shell(f"input tap {(x1+x2)//2} {(y1+y2)//2}")
            time.sleep(1)
            return
    raise RuntimeError(f"Menu not found: {label}")


def clock():
    wake()
    shell("am start -n local.paperclock/.MainActivity")
    print("페이퍼 시계·달력을 실행했습니다. 오른쪽 위 메뉴에서 설정할 수 있습니다.")


def image(path):
    path = Path(path).resolve(strict=True)
    wake()
    # Use a stable ASCII name so the Android 4.4 image picker is predictable.
    target = "codex-custom" + path.suffix.lower()
    adb("push", str(path), "/sdcard/Sleepingscreen/" + target)
    shell("am start -n com.android.settings/.standby.StandbySettingsActivity")
    time.sleep(1)
    tap("사용자 이미지 선택")
    tap(target)
    sleep()
    print("Selected sleep image:", shell("settings get system sleepscreen_file_path").strip())


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("mode", choices=["clock", "image", "sleep", "wake", "status"])
    p.add_argument("path", nargs="?")
    args = p.parse_args()
    if args.mode == "image":
        if not args.path:
            p.error("image requires an image file path")
        image(args.path)
    elif args.mode == "clock":
        clock()
    elif args.mode == "sleep":
        sleep()
    elif args.mode == "wake":
        wake()
    else:
        print("Power:", wakefulness())
        print("Device time:", shell("date").strip())
        print("Sleep image:", shell("settings get system sleepscreen_file_path").strip())


if __name__ == "__main__":
    main()
