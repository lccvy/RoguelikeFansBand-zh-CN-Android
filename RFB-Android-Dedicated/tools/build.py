#!/usr/bin/env python3
"""Prepare RFB and build the dedicated Android APK with one command.

The script can reuse Android Studio's SDK, install missing SDK/NDK/CMake packages
through sdkmanager, and bootstrap a local Gradle distribution. It never needs a
pre-existing angbandroid checkout.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(os.environ.get("RFB_PROJECT_ROOT", str(Path(__file__).absolute().parents[1]))).absolute()
PORTABLE = ROOT / "portable_workspace"
TOOLING = PORTABLE / "runtime"
DIST = ROOT / "dist"
GRADLE_VERSION = "9.4.1"
GRADLE_URL = f"https://services.gradle.org/distributions/gradle-{GRADLE_VERSION}-bin.zip"
GRADLE_SHA256 = "2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"
REQUIRED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
SDK_PACKAGES = [
    "platforms;android-36",
    "build-tools;36.0.0",
    "ndk;29.0.14206865",
    "cmake;3.22.1",
]


def emit_stage(key: str, label: str) -> None:
    """Report progress to CLI users and emit an optional machine-readable GUI marker."""
    if os.environ.get("RFB_GUI") == "1":
        print(f"@@RFB_STAGE@@{key}|{label}", flush=True)
    print(f"[阶段] {label}", flush=True)


def run(cmd: list[str], cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    print("+", " ".join(str(x) for x in cmd))
    subprocess.run(cmd, cwd=str(cwd or ROOT), env=env, check=True)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_sdk(explicit: str | None) -> Path:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    candidates.append(PORTABLE / "runtime" / "android-sdk")
    for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(name)
        if value:
            candidates.append(Path(value))

    for candidate in candidates:
        if candidate.is_dir():
            return candidate.absolute()
    raise SystemExit(
        "错误：便携 Android SDK 尚未准备。请先运行图形化构建器或 tools/portable_bootstrap.ps1。")


def _java_properties_escape(value: str) -> str:
    out: list[str] = []
    for ch in value.replace("\\", "/"):
        code = ord(ch)
        if ch in " :=#!":
            out.append("\\" + ch)
        elif 0x20 <= code <= 0x7E:
            out.append(ch)
        elif code <= 0xFFFF:
            out.append(f"\\u{code:04x}")
        else:
            code -= 0x10000
            high = 0xD800 + (code >> 10)
            low = 0xDC00 + (code & 0x3FF)
            out.append(f"\\u{high:04x}\\u{low:04x}")
    return "".join(out)


def write_local_properties(sdk: Path) -> None:
    text = "sdk.dir=" + _java_properties_escape(str(sdk)) + "\n"
    (ROOT / "local.properties").write_text(text, encoding="ascii", newline="\n")


def find_sdkmanager(sdk: Path) -> Path | None:
    suffix = ".bat" if platform.system() == "Windows" else ""
    candidates = [
        sdk / "cmdline-tools" / "latest" / "bin" / f"sdkmanager{suffix}",
        sdk / "tools" / "bin" / f"sdkmanager{suffix}",
    ]
    cmdline = sdk / "cmdline-tools"
    if cmdline.is_dir():
        for child in sorted(cmdline.iterdir(), reverse=True):
            candidates.append(child / "bin" / f"sdkmanager{suffix}")
    return next((p for p in candidates if p.is_file()), None)


def ensure_sdk_packages(sdk: Path) -> None:
    manager = find_sdkmanager(sdk)
    if manager is None:
        print("警告：未找到 sdkmanager；跳过自动安装 SDK 组件，由 Gradle 检查现有环境。")
        return
    env = dict(os.environ)
    env.setdefault("ANDROID_SDK_ROOT", str(sdk))
    run([str(manager), *SDK_PACKAGES], env=env)


def java_major() -> int | None:
    explicit_java = os.environ.get("RFB_JAVA_EXE")
    java_home = os.environ.get("JAVA_HOME")
    if explicit_java:
        java = explicit_java
    elif java_home:
        java = str(Path(java_home) / "bin" / ("java.exe" if platform.system() == "Windows" else "java"))
    else:
        java = shutil.which("java")
    if not java or not Path(java).is_file():
        return None
    proc = subprocess.run([java, "-version"], capture_output=True, text=True)
    text = proc.stderr + proc.stdout
    match = re.search(r'version "(\d+)(?:\.(\d+))?', text)
    if not match:
        return None
    first = int(match.group(1))
    second = int(match.group(2) or 0)
    return second if first == 1 else first


def check_java() -> None:
    major = java_major()
    if major is None:
        raise SystemExit("错误：未找到 Java。Android Gradle Plugin 9.2 需要 JDK 17 或更高版本。")
    if major < 17:
        raise SystemExit(f"错误：当前 Java 主版本为 {major}，需要 JDK 17 或更高版本。")
    print(f"Java 环境检查通过：主版本 {major}")


def gradle_executable() -> Path:
    name = "gradle.bat" if platform.system() == "Windows" else "gradle"
    local = TOOLING / f"gradle-{GRADLE_VERSION}" / "bin" / name
    if local.is_file():
        return local

    TOOLING.mkdir(parents=True, exist_ok=True)
    archive = TOOLING / f"gradle-{GRADLE_VERSION}-bin.zip"
    def digest(path: Path) -> str:
        h = hashlib.sha256()
        with path.open("rb") as fh:
            for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                h.update(chunk)
        return h.hexdigest()

    if archive.is_file() and digest(archive) != GRADLE_SHA256:
        print("Gradle 下载缓存校验失败，删除后重新下载。")
        archive.unlink()
    if not archive.is_file():
        print(f"下载 Gradle {GRADLE_VERSION}……")
        temp = archive.with_suffix(archive.suffix + ".part")
        temp.unlink(missing_ok=True)
        with urllib.request.urlopen(GRADLE_URL) as response, temp.open("wb") as out:
            shutil.copyfileobj(response, out)
        actual = digest(temp)
        if actual != GRADLE_SHA256:
            temp.unlink(missing_ok=True)
            raise SystemExit(f"错误：Gradle SHA-256 不匹配。期望 {GRADLE_SHA256}，实际 {actual}")
        temp.replace(archive)
    print("解压 Gradle……")
    with zipfile.ZipFile(archive) as zf:
        zf.extractall(TOOLING)
    if not local.is_file():
        raise SystemExit("错误：Gradle 解压后未找到可执行文件。")
    if platform.system() != "Windows":
        local.chmod(local.stat().st_mode | 0o111)
    return local


def prepare_source(source_dir: str | None, revision: str, offline: bool) -> None:
    cmd = [sys.executable, str(ROOT / "tools" / "prepare_source.py"), "--revision", revision]
    if source_dir:
        cmd.extend(["--source-dir", source_dir])
    if offline:
        cmd.append("--offline")
    run(cmd)


def build(variant: str, gradle: Path) -> Path:
    task = ":app:assembleDebug" if variant == "debug" else ":app:assembleRelease"
    run([str(gradle), "--no-daemon", "--stacktrace", task])
    output_dir = ROOT / "app" / "build" / "outputs" / "apk" / variant
    candidates = sorted(output_dir.glob("*.apk")) if output_dir.is_dir() else []
    if not candidates:
        raise SystemExit(f"错误：Gradle 返回成功，但 {output_dir} 中没有 APK")
    apk = candidates[0]
    DIST.mkdir(parents=True, exist_ok=True)
    # Every accepted artifact contains all REQUIRED_ABIS; keep that guarantee
    # visible in the filename used by the GUI and migration helper.
    suffix = "universal-debug" if variant == "debug" else "universal-release-unsigned"
    revision_file = ROOT / "generated" / "rfb_revision.txt"
    revision = revision_file.read_text(encoding="utf-8").strip() if revision_file.is_file() else "unknown"
    safe_revision = re.sub(r"[^A-Za-z0-9._-]+", "_", revision)
    port_version = generated_build_property("rfbAndroidPortVersion")
    safe_port_version = re.sub(r"[^A-Za-z0-9._-]+", "_", port_version)
    out = DIST / (
        f"RoguelikeFansBand-Android-{safe_revision}-v{safe_port_version}-{suffix}.apk")
    shutil.copy2(apk, out)
    return out


def find_build_tool(sdk: Path, name: str) -> Path:
    suffix = ".bat" if platform.system() == "Windows" and name == "apksigner" else (
        ".exe" if platform.system() == "Windows" else "")
    preferred = sdk / "build-tools" / "36.0.0" / (name + suffix)
    if preferred.is_file():
        return preferred
    root = sdk / "build-tools"
    candidates = sorted(root.glob(f"*/{name}{suffix}"), reverse=True) if root.is_dir() else []
    if not candidates:
        raise SystemExit(f"错误：未找到 Android Build Tools 中的 {name}{suffix}")
    return candidates[0]


def generated_build_property(name: str) -> str:
    path = ROOT / "generated" / "rfb_build.properties"
    if not path.is_file():
        raise SystemExit("错误：缺少 generated/rfb_build.properties，无法验收 APK 版本。")
    for line in path.read_text(encoding="ascii").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    raise SystemExit(f"错误：rfb_build.properties 缺少 {name}。")


def generated_version_name() -> str:
    return generated_build_property("rfbVersionName")


def validate_apk(apk: Path, sdk: Path, variant: str) -> str:
    """Reject incomplete or mislabeled APKs before the GUI reports success."""
    try:
        with zipfile.ZipFile(apk) as zf:
            broken = zf.testzip()
            if broken:
                raise SystemExit(f"错误：APK 中的 ZIP 条目损坏：{broken}")
            names = set(zf.namelist())
            required = {
                "AndroidManifest.xml", "classes.dex",
                "assets/rfb-data.zip", "assets/rfb-build-meta.json",
            }
            required.update(f"lib/{abi}/librfb_android.so" for abi in REQUIRED_ABIS)
            missing = sorted(required - names)
            if missing:
                raise SystemExit("错误：APK 缺少必要文件：\n" + "\n".join(missing))
            for abi in REQUIRED_ABIS:
                info = zf.getinfo(f"lib/{abi}/librfb_android.so")
                if info.file_size < 500_000:
                    raise SystemExit(f"错误：{abi} 原生库尺寸异常：{info.file_size}")
    except zipfile.BadZipFile as exc:
        raise SystemExit(f"错误：输出不是有效 APK/ZIP：{apk}") from exc

    aapt2 = find_build_tool(sdk, "aapt2")
    badging = subprocess.run(
        [str(aapt2), "dump", "badging", str(apk)],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    if badging.returncode != 0:
        raise SystemExit("错误：aapt2 无法读取 APK Manifest：\n" + badging.stderr)
    expected_version = generated_version_name()
    if "package: name='org.roguelikefansband.android'" not in badging.stdout:
        raise SystemExit("错误：APK applicationId 与专用工程不一致。")
    if f"versionName='{expected_version}'" not in badging.stdout:
        raise SystemExit(
            f"错误：APK versionName 不是本次解析出的 {expected_version}。")
    for abi in REQUIRED_ABIS:
        if f"'{abi}'" not in badging.stdout:
            raise SystemExit(f"错误：APK badging 未报告 ABI {abi}。")

    if variant == "debug":
        apksigner = find_build_tool(sdk, "apksigner")
        verify = subprocess.run([str(apksigner), "verify", "--verbose", str(apk)])
        if verify.returncode != 0:
            raise SystemExit("错误：Debug APK 签名验收失败。")
    else:
        print("Release APK 按设计保持未签名；发布前请使用自己的正式密钥签名。")

    digest = sha256_file(apk)
    checksum = apk.with_suffix(apk.suffix + ".sha256")
    checksum.write_text(f"{digest}  {apk.name}\n", encoding="ascii", newline="\n")
    print(f"APK 验收通过：三种 ABI、Manifest、资源、版本和 ZIP 完整性均正确。")
    print(f"SHA-256：{digest}")
    return digest


def configure_portable_environment() -> None:
    workspace = PORTABLE
    # AGP 9.x rejects a conflicting legacy ANDROID_SDK_HOME.
    os.environ.pop("ANDROID_SDK_HOME", None)
    env_paths = {
        "GRADLE_USER_HOME": workspace / "gradle-home",
        "ANDROID_USER_HOME": workspace / "android-user-home",
        "HOME": workspace / "home",
        "TEMP": workspace / "temp",
        "TMP": workspace / "temp",
    }
    for name, path in env_paths.items():
        path.mkdir(parents=True, exist_ok=True)
        os.environ[name] = str(path)
    os.environ.setdefault("RFB_PORTABLE_MODE", "1")


def main() -> int:
    configure_portable_environment()
    if os.environ.get("RFB_PORTABLE_MODE") == "1" and os.environ.get("RFB_PORTABLE_BOOTSTRAPPED") != "1":
        raise SystemExit("错误：便携构建核心被绕过引导程序直接启动。请运行“打开图形化构建器.bat”或 build_debug.bat。")
    parser = argparse.ArgumentParser(description="Build dedicated RoguelikeFansBand Android APK")
    parser.add_argument("--variant", choices=("debug", "release"), default="debug")
    parser.add_argument("--source-dir", help="Use an existing local RFB source tree")
    parser.add_argument(
        "--revision", default="latest",
        help="Upstream release tag, or latest (default) for the newest stable release")
    parser.add_argument(
        "--offline", action="store_true",
        help="Use the last successfully resolved latest release without network access")
    parser.add_argument("--sdk", help="Explicit Android SDK path")
    parser.add_argument("--skip-sdk-install", action="store_true")
    args = parser.parse_args()

    emit_stage("java", "检查 Java 环境")
    check_java()
    emit_stage("sdk", "定位并配置 Android SDK")
    sdk = find_sdk(args.sdk)
    print(f"Android SDK：{sdk}")
    write_local_properties(sdk)
    if not args.skip_sdk_install:
        emit_stage("sdk_packages", "检查并安装 Android SDK / NDK / CMake 组件")
        ensure_sdk_packages(sdk)
    else:
        print("已跳过 SDK 组件自动安装。", flush=True)

    emit_stage("source", "准备 RoguelikeFansBand 源码、资源和原生依赖")
    prepare_source(args.source_dir, args.revision, args.offline)
    emit_stage("gradle", f"准备 Gradle {GRADLE_VERSION}")
    gradle = gradle_executable()
    emit_stage("build", f"执行 {args.variant.capitalize()} APK 构建")
    apk = build(args.variant, gradle)
    emit_stage("apk", "验收 APK 结构、版本、ABI 与签名")
    validate_apk(apk, sdk, args.variant)
    print("\n构建完成：", apk, flush=True)
    emit_stage("done", "构建完成")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        print(f"命令执行失败，退出码 {exc.returncode}", file=sys.stderr)
        raise SystemExit(exc.returncode)
