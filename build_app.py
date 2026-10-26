#!/usr/bin/env python3
"""Build the dependency-free API 19 app with the local official SDK and JDK."""
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
local_jdk = next((ROOT / "tools").glob("jdk-*/Contents/Home"), None)
jdk_path = os.environ.get("JAVA_HOME") or local_jdk
if not jdk_path:
    raise SystemExit("JDK 17을 설치하고 JAVA_HOME을 설정하세요. README.md의 빌드 방법을 참고하세요.")
JDK = Path(jdk_path).expanduser()
sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
SDK = Path(sdk_root) / "build-tools/37.0.0" if sdk_root else ROOT / "tools/android/android-37.0"
ANDROID = Path(sdk_root) / "platforms/android-19/android.jar" if sdk_root else ROOT / "tools/android/android-4.4.2/android.jar"
BUILD = ROOT / "build"
DIST = ROOT / "dist"
ENV = dict(os.environ, JAVA_HOME=str(JDK))


def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT, env=ENV)


def main():
    for required in [JDK / "bin/javac", SDK / "aapt", SDK / "lib/d8.jar", ANDROID]:
        if not required.exists():
            raise SystemExit(f"빌드 도구를 찾을 수 없습니다: {required}\nREADME.md의 환경 설정을 확인하세요.")
    for name in ["classes", "dex", "tests"]:
        if (BUILD / name).exists():
            shutil.rmtree(BUILD / name)
        (BUILD / name).mkdir(parents=True, exist_ok=True)
    DIST.mkdir(exist_ok=True)
    java_sources = sorted((ROOT / "app/src").rglob("*.java"))
    run(JDK / "bin/javac", "-source", "7", "-target", "7", "-bootclasspath", ANDROID,
        "-encoding", "UTF-8", "-d", BUILD / "classes", *java_sources)
    model_sources = [p for p in java_sources if p.name != "MainActivity.java"]
    run(JDK / "bin/javac", "-encoding", "UTF-8", "-d", BUILD / "tests",
        *model_sources, *sorted((ROOT / "app/tests").glob("*.java")))
    run(JDK / "bin/java", "-cp", BUILD / "tests", "local.paperclock.CalendarModelTest")
    run(JDK / "bin/java", "-cp", BUILD / "tests", "local.paperclock.NetworkTimeTest")
    run(JDK / "bin/jar", "cf", BUILD / "classes.jar", "-C", BUILD / "classes", ".")
    run(JDK / "bin/java", "-cp", SDK / "lib/d8.jar", "com.android.tools.r8.D8",
        "--min-api", "19", "--lib", ANDROID, "--output", BUILD / "dex", BUILD / "classes.jar")
    run(SDK / "aapt", "package", "-f", "-M", ROOT / "app/AndroidManifest.xml",
        "-I", ANDROID, "-F", BUILD / "unsigned.apk")
    with zipfile.ZipFile(BUILD / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as apk:
        apk.write(BUILD / "dex/classes.dex", "classes.dex")
    run(SDK / "zipalign", "-f", "4", BUILD / "unsigned.apk", BUILD / "aligned.apk")
    key = BUILD / "paperclock.keystore"
    if not key.exists():
        run(JDK / "bin/keytool", "-genkeypair", "-keystore", key, "-storepass", "paperclock-local",
            "-keypass", "paperclock-local", "-alias", "paperclock", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000", "-dname", "CN=Local Paper Clock")
    run(JDK / "bin/java", "-jar", SDK / "lib/apksigner.jar", "sign", "--ks", key,
        "--ks-pass", "pass:paperclock-local", "--min-sdk-version", "19",
        "--v1-signing-enabled", "true", "--v2-signing-enabled", "false",
        "--out", DIST / "paper-clock.apk", BUILD / "aligned.apk")
    run(JDK / "bin/java", "-jar", SDK / "lib/apksigner.jar", "verify", "--verbose",
        "--min-sdk-version", "19", DIST / "paper-clock.apk")
    print("APK:", DIST / "paper-clock.apk")


if __name__ == "__main__":
    main()
