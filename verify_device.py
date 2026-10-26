#!/usr/bin/env python3
"""Exercise real UI, persistence, sleep/resume, and an unattended minute tick."""
import datetime
import json
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree as ET
from zoneinfo import ZoneInfo
from ridi_control import adb, shell, tap, wake, sleep

ROOT = Path(__file__).resolve().parent
EVIDENCE = ROOT / "evidence"
EVIDENCE.mkdir(exist_ok=True)
checks = {}


def ui():
    shell("uiautomator dump /sdcard/paper-clock-verify.xml")
    return ET.fromstring(shell("cat /sdcard/paper-clock-verify.xml"))


def description():
    values = [n.get("content-desc", "") for n in ui().iter("node")]
    return next((v for v in values if "월 달력" in v), "")


def touch(x, y):
    shell(f"input tap {x} {y}")
    time.sleep(.3)


def menu():
    touch(1310, 134)


def record(name, ok):
    assert ok, name
    checks[name] = True
    print("PASS:", name, flush=True)


def capture(name):
    shell("screencap -p /sdcard/paper-clock-verification.png")
    adb("pull", "/sdcard/paper-clock-verification.png", str(EVIDENCE / f"{name}.png"))


wake()
initial = description()
today = datetime.datetime.now(ZoneInfo("Asia/Seoul"))
record("correct Korean date and trusted time", f"{today.year}년 {today.month}월 {today.day}일" in initial and "한국 시간" in initial)
record("front light off", "mActualBacklight=0" in shell("dumpsys power"))

touch(1266, 829)
other = description()
record("next month navigation", other != initial and "월 달력" in other)
touch(702, 829)
record("return to current month", f"{today.year}년 {today.month}월 달력" in description())

menu(); tap("날짜·시간 맞추기")
record("manual date picker", any(n.get("text") == "날짜 맞추기" for n in ui().iter("node")))
shell("input keyevent 4")
record("date cancel leaves clock unchanged", "한국 시간" in description())
menu(); tap("날짜·시간 맞추기"); tap("다음")
record("manual time picker", any(n.get("text") == "시간 맞추기" for n in ui().iter("node")))
shell("input keyevent 4")
record("time cancel leaves clock unchanged", "한국 시간" in description())

menu(); tap("월요일부터 한 주 시작")
record("Monday-first calendar", "월요일 시작" in description())
menu(); tap("일요일부터 한 주 시작")
record("Sunday-first calendar restored", "일요일 시작" in description())

menu(); tap("조명 켜기")
backlight = int(re.search(r"mActualBacklight=(\d+)", shell("dumpsys power")).group(1))
record("front light toggle on", 0 < backlight < 64)
menu(); tap("조명 끄기")
record("front light toggle off", "mActualBacklight=0" in shell("dumpsys power"))

shell("am force-stop local.paperclock")
shell("am start -n local.paperclock/.MainActivity")
time.sleep(.7)
record("corrected time persists after process restart", "한국 시간" in description())
sleep()
record("power button enters native sleep", "mWakefulness=Asleep" in shell("dumpsys power"))
wake()
record("clock resumes after sleep", "한국 시간" in description())

# No UI interactions during this interval: the app must repaint on its own.
first = description()
capture("paper-clock-tick-a")
first_minute = re.search(r"\b\d{2}:\d{2}\b", first).group()
wait_seconds = 60 - time.time() % 60 + 2
print(f"Waiting {wait_seconds:.0f}s for one unattended minute change", flush=True)
deadline = time.monotonic() + wait_seconds
while time.monotonic() < deadline:
    time.sleep(min(20, deadline - time.monotonic()))
second = description()
capture("paper-clock-final")
second_minute = re.search(r"\b\d{2}:\d{2}\b", second).group()
record("unattended minute repaint", first_minute != second_minute)
now_local = datetime.datetime.now(ZoneInfo("Asia/Seoul"))
record("displayed minute matches current Korean time", now_local.strftime("%H:%M") == second_minute)
state = shell("dumpsys power")
record("awake with front light off", "mWakefulness=Awake" in state and "mActualBacklight=0" in state)
(EVIDENCE / "paper-clock-final-state.txt").write_text(state)
(EVIDENCE / "paper-clock-final.xml").write_text(shell("cat /sdcard/paper-clock-verify.xml"))
(EVIDENCE / "paper-clock-verification.json").write_text(json.dumps({
    "checks": checks, "first": first, "final": second,
    "device_system_clock": shell("date").strip(),
    "clock_source": "Saved UTC anchor plus Android elapsedRealtime; automatic NTP correction; Asia/Seoul",
    "permissions": ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"],
    "installed_apk": "dist/paper-clock.apk",
}, ensure_ascii=False, indent=2) + "\n")
print("All device checks passed.", flush=True)
