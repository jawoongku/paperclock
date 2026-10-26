#!/usr/bin/env python3
"""Start the installed calendar and sync only its clock to this Mac's UTC time."""
import time
from ridi_control import adb, wake

wake()
print(adb("shell", "am", "start", "-n", "local.paperclock/.MainActivity",
          "--el", "utcMillis", str(time.time_ns() // 1_000_000)))
print("페이퍼 시계·달력을 실행하고 앱 시간을 한국 시간으로 맞췄습니다.")
