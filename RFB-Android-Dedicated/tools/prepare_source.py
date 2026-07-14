#!/usr/bin/env python3
"""Prepare the exact RoguelikeFansBand source and runtime data for Android.

This script deliberately does NOT import angbandroid or any of its source.
It pins the upstream Chinese RFB source, generates one Android-specific main
translation unit, prepares a CMake source list, and byte-copies lib resources
into an APK asset ZIP without decoding/re-encoding text files.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
import urllib.parse
import zipfile

ROOT = Path(os.environ.get("RFB_PROJECT_ROOT", str(Path(__file__).absolute().parents[1]))).absolute()
VENDOR = ROOT / "vendor" / "rfb"
GENERATED = ROOT / "generated"
ASSETS = ROOT / "app" / "src" / "main" / "assets"
THIRD_PARTY = ROOT / "vendor" / "third_party"
DOWNLOAD_CACHE = ROOT / "portable_workspace" / "cache" / "downloads"

CURL_VERSION = "8.21.0"
CURL_URL = f"https://curl.se/download/curl-{CURL_VERSION}.tar.xz"
CURL_SHA256 = "aa1b66a70eace83dc624508745646c08ae561de512ab403adffb93ac87fc72e6"
MBEDTLS_VERSION = "3.6.6"
MBEDTLS_URL = (
    "https://github.com/Mbed-TLS/mbedtls/releases/download/"
    f"mbedtls-{MBEDTLS_VERSION}/mbedtls-{MBEDTLS_VERSION}.tar.bz2"
)
MBEDTLS_SHA256 = "8fb65fae8dcae5840f793c0a334860a411f884cc537ea290ce1c52bb64ca007a"
CA_BUNDLE_URL = "https://curl.se/ca/cacert.pem"

REPO_URL = "https://github.com/UncleFvcker/RoguelikeFansBand-zh-CN.git"
GITHUB_API_BASE = "https://api.github.com/repos/UncleFvcker/RoguelikeFansBand-zh-CN"
DEFAULT_REVISION = "latest"
ANDROID_PORT_VERSION = "7.5"
ANDROID_PORT_REVISION = 6

WRITABLE_DIRS = {"save", "user", "apex", "bone", "data"}
FONT_SUFFIXES = {".ttf", ".otf", ".ttc", ".fon"}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _validate_release_tag(tag: str) -> str:
    """Accept only a single, URL-safe release tag.

    Besides preventing path tricks, this deliberately rejects branch names:
    the Android builder consumes immutable release tags only.
    """
    value = str(tag).strip()
    if not value or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", value):
        raise SystemExit(f"错误：无效的 RFB 发布标签：{tag!r}")
    return value


def _read_latest_cache() -> dict[str, object] | None:
    path = DOWNLOAD_CACHE / "rfb-latest-stable-release.json"
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        tag = _validate_release_tag(str(data.get("tag_name", "")))
        if data.get("draft") or data.get("prerelease"):
            return None
        data["tag_name"] = tag
        return data
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        return None


def resolve_source_revision(requested: str, offline: bool = False) -> tuple[str, dict[str, object]]:
    """Resolve `latest` to GitHub's latest non-prerelease release.

    Git tags in this project span several historical numbering schemes, so
    sorting all tags would select the wrong game line. GitHub's latest-release
    endpoint is the upstream maintainer's explicit stable-release pointer.
    """
    raw = str(requested or DEFAULT_REVISION).strip()
    aliases = {"latest", "stable", "最新版", "最新"}
    if raw.lower() not in aliases and raw not in aliases:
        tag = _validate_release_tag(raw)
        return tag, {"mode": "pinned", "requested": raw, "tag_name": tag}

    cached = _read_latest_cache()
    if offline:
        if cached is None:
            raise SystemExit("错误：离线模式没有可用的最新版缓存；请先联网成功构建一次。")
        print(f"离线模式复用最新版缓存：{cached['tag_name']}")
        return str(cached["tag_name"]), {**cached, "mode": "latest-cache", "requested": raw}

    url = GITHUB_API_BASE + "/releases/latest"
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": f"RFB-Android-Builder/{ANDROID_PORT_VERSION}",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    last_error: Exception | None = None
    for attempt in range(1, 4):
        try:
            print(f"查询上游最新稳定版（第 {attempt}/3 次）……")
            with urllib.request.urlopen(
                    urllib.request.Request(url, headers=headers), timeout=30) as response:
                data = json.load(response)
            tag = _validate_release_tag(str(data.get("tag_name", "")))
            if data.get("draft") or data.get("prerelease"):
                raise ValueError("latest endpoint returned a draft/prerelease")
            selected = {
                "tag_name": tag,
                "name": data.get("name"),
                "html_url": data.get("html_url"),
                "published_at": data.get("published_at"),
                "draft": False,
                "prerelease": False,
            }
            DOWNLOAD_CACHE.mkdir(parents=True, exist_ok=True)
            (DOWNLOAD_CACHE / "rfb-latest-stable-release.json").write_text(
                json.dumps(selected, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"上游最新稳定版：{tag}")
            return tag, {**selected, "mode": "latest-online", "requested": raw}
        except (OSError, ValueError, TypeError, urllib.error.URLError,
                urllib.error.HTTPError, json.JSONDecodeError) as exc:
            last_error = exc
            if attempt < 3:
                print(f"最新版查询失败，将重试：{exc}")
                time.sleep(attempt * 2)

    if cached is not None:
        print(f"警告：无法联网查询最新版，复用上次成功缓存 {cached['tag_name']}：{last_error}")
        return str(cached["tag_name"]), {**cached, "mode": "latest-cache-fallback", "requested": raw}
    raise SystemExit(f"错误：无法查询上游最新稳定版，且没有本地缓存：{last_error}")


def download_verified(url: str, destination: Path, expected_sha256: str | None = None) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file():
        if expected_sha256 is None or sha256_file(destination) == expected_sha256:
            print(f"复用下载缓存：{destination.name}")
            return destination
        destination.unlink()

    temp = destination.with_suffix(destination.suffix + ".part")
    headers = {"User-Agent": "RFB-Android-Builder/1.0"}
    last_error: Exception | None = None
    for attempt in range(1, 4):
        request = urllib.request.Request(url, headers=headers)
        print(f"下载：{url}（第 {attempt}/3 次）")
        temp.unlink(missing_ok=True)
        try:
            with urllib.request.urlopen(request, timeout=90) as response, temp.open("wb") as out:
                expected_length = response.headers.get("Content-Length")
                shutil.copyfileobj(response, out, length=1024 * 1024)
            if expected_length is not None and temp.stat().st_size != int(expected_length):
                raise IOError(
                    f"下载长度不完整：实际 {temp.stat().st_size}，服务器声明 {expected_length}")

            if expected_sha256 is not None:
                actual = sha256_file(temp)
                if actual != expected_sha256:
                    raise IOError(
                        f"{destination.name} SHA-256 不匹配\n"
                        f"期望：{expected_sha256}\n实际：{actual}")
            temp.replace(destination)
            return destination
        except Exception as exc:
            last_error = exc
            temp.unlink(missing_ok=True)
            if attempt < 3:
                print(f"下载失败，将重试：{exc}")
                time.sleep(attempt * 2)

    raise SystemExit(f"错误：下载 {url} 失败：{last_error}")


def safe_extract_tar(archive: Path, destination: Path, expected_top: str) -> None:
    temp_root = destination.parent / (destination.name + ".extracting")
    shutil.rmtree(temp_root, ignore_errors=True)
    temp_root.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:*") as tf:
        root = temp_root.resolve()
        for member in tf.getmembers():
            target = (temp_root / member.name).resolve()
            try:
                target.relative_to(root)
            except ValueError as exc:
                raise SystemExit(f"错误：压缩包包含越界路径：{member.name}") from exc
        tf.extractall(temp_root, filter="data")

    extracted = temp_root / expected_top
    if not extracted.is_dir():
        shutil.rmtree(temp_root, ignore_errors=True)
        raise SystemExit(f"错误：{archive.name} 缺少预期目录 {expected_top}")
    shutil.rmtree(destination, ignore_errors=True)
    extracted.replace(destination)
    shutil.rmtree(temp_root, ignore_errors=True)


def prepare_third_party() -> None:
    THIRD_PARTY.mkdir(parents=True, exist_ok=True)

    curl_archive = download_verified(
        CURL_URL, DOWNLOAD_CACHE / f"curl-{CURL_VERSION}.tar.xz", CURL_SHA256)
    mbed_archive = download_verified(
        MBEDTLS_URL, DOWNLOAD_CACHE / f"mbedtls-{MBEDTLS_VERSION}.tar.bz2", MBEDTLS_SHA256)

    curl_dir = THIRD_PARTY / "curl"
    mbed_dir = THIRD_PARTY / "mbedtls"
    curl_stamp = curl_dir / ".rfb-version"
    mbed_stamp = mbed_dir / ".rfb-version"

    if not curl_stamp.is_file() or curl_stamp.read_text(encoding="utf-8").strip() != CURL_VERSION:
        safe_extract_tar(curl_archive, curl_dir, f"curl-{CURL_VERSION}")
        curl_stamp.write_text(CURL_VERSION + "\n", encoding="utf-8")
        print(f"准备 libcurl {CURL_VERSION}")
    if not mbed_stamp.is_file() or mbed_stamp.read_text(encoding="utf-8").strip() != MBEDTLS_VERSION:
        safe_extract_tar(mbed_archive, mbed_dir, f"mbedtls-{MBEDTLS_VERSION}")
        mbed_stamp.write_text(MBEDTLS_VERSION + "\n", encoding="utf-8")
        print(f"准备 Mbed TLS {MBEDTLS_VERSION}")


def patch_http_curl_ca() -> None:
    path = VENDOR / "src" / "http.c"
    text = path.read_text(encoding="utf-8")
    sentinel = "RFB_ANDROID_CURL_CA_BUNDLE"
    include = "#include <curl/curl.h>"
    if sentinel not in text:
        if include not in text:
            raise SystemExit("错误：http.c 中未找到 curl/curl.h，无法安全补 CA 证书配置。")
        occurrences = text.count("    if (curl) {")
        if occurrences != 2:
            raise SystemExit(
                f"错误：http.c 的 curl handle 分支数量为 {occurrences}，预期为 2；拒绝盲目补丁。")

        helper = r'''

/* RFB_ANDROID_CURL_CA_BUNDLE: Android's native curl build uses Mbed TLS and
 * an explicit CA bundle installed with the game data. Verification remains
 * enabled; only the trust-anchor location is supplied here. */
static void _rfb_android_apply_ca_bundle(CURL *curl)
{
    const char *ca_bundle = getenv("RFB_CURL_CA_BUNDLE");
    if (ca_bundle && ca_bundle[0])
        curl_easy_setopt(curl, CURLOPT_CAINFO, ca_bundle);
}
'''
        text = text.replace(include, include + helper, 1)
        text = text.replace(
            "    if (curl) {",
            "    if (curl) {\n        _rfb_android_apply_ca_bundle(curl);",
        )

    # RFB's z-form.h defines format(...) as a function-like macro. Without a
    # scoped undef it corrupts libcurl's __attribute__((format(printf,...)))
    # declarations and silently removes useful compiler checking.
    macro_guard = '''/* RFB_ANDROID_CURL_FORMAT_MACRO_GUARD */
#ifdef format
# pragma push_macro("format")
# undef format
# define RFB_ANDROID_RESTORE_FORMAT_MACRO
#endif
#include <curl/curl.h>
#ifdef RFB_ANDROID_RESTORE_FORMAT_MACRO
# pragma pop_macro("format")
# undef RFB_ANDROID_RESTORE_FORMAT_MACRO
#endif'''
    if "RFB_ANDROID_CURL_FORMAT_MACRO_GUARD" not in text:
        count = text.count(include)
        if count != 1:
            raise SystemExit(
                f"错误：http.c 的 curl 头文件引用数量为 {count}，预期为 1；拒绝盲目补丁。")
        text = text.replace(include, macro_guard, 1)

    old_timeout = "curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30);"
    new_timeout = "curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30L);"
    old_count = text.count(old_timeout)
    new_count = text.count(new_timeout)
    if old_count == 2 and new_count == 0:
        text = text.replace(old_timeout, new_timeout)
    elif old_count != 0 or new_count != 2:
        raise SystemExit(
            "错误：http.c 的 CURLOPT_TIMEOUT 调用结构异常；拒绝盲目补丁。")

    path.write_text(text, encoding="utf-8", newline="\n")
    print("已给 RFB http.c 接入 CA 证书束并隔离 curl/format 宏。")


def install_ca_bundle() -> None:
    cache_path = DOWNLOAD_CACHE / "cacert.pem"
    for attempt in range(2):
        cache = download_verified(CA_BUNDLE_URL, cache_path)
        data = cache.read_bytes()
        if b"-----BEGIN CERTIFICATE-----" in data and len(data) >= 100_000:
            target = VENDOR / "lib" / "xtra" / "curl" / "cacert.pem"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            print(f"安装 CA 证书束：{target.relative_to(ROOT)}")
            return
        cache_path.unlink(missing_ok=True)
        if attempt == 0:
            print("CA 证书束缓存内容异常，删除后重新下载。")
    raise SystemExit("错误：下载的 cacert.pem 内容不像有效 CA 证书束。")


def run(cmd: list[str], cwd: Path | None = None) -> None:
    print("+", " ".join(cmd))
    subprocess.run(cmd, cwd=str(cwd) if cwd else None, check=True)


def safe_extract_zip(archive: Path, destination: Path) -> None:
    temp_root = destination.parent / (destination.name + ".extracting")
    shutil.rmtree(temp_root, ignore_errors=True)
    temp_root.mkdir(parents=True, exist_ok=True)
    root = temp_root.resolve()
    with zipfile.ZipFile(archive) as zf:
        for info in zf.infolist():
            target = (temp_root / info.filename).resolve()
            try:
                target.relative_to(root)
            except ValueError as exc:
                raise SystemExit(f"错误：ZIP 包含越界路径：{info.filename}") from exc
        zf.extractall(temp_root)
    dirs = [p for p in temp_root.iterdir() if p.is_dir()]
    if len(dirs) != 1:
        raise SystemExit(f"错误：RFB 源码归档顶层目录数量为 {len(dirs)}，预期为 1。")
    shutil.rmtree(destination, ignore_errors=True)
    dirs[0].replace(destination)
    shutil.rmtree(temp_root, ignore_errors=True)


def checkout_source(revision: str, source_dir: Path | None) -> None:
    VENDOR.parent.mkdir(parents=True, exist_ok=True)

    if source_dir:
        source_dir = source_dir.absolute()
        if not (source_dir / "src" / "main.c").is_file():
            raise SystemExit(f"错误：{source_dir} 不是可识别的 RFB 源码目录。")
        if VENDOR.exists():
            shutil.rmtree(VENDOR)
        print(f"复制本地 RFB 源码：{source_dir} -> {VENDOR}")
        shutil.copytree(source_dir, VENDOR, ignore=shutil.ignore_patterns(".git"))
        return

    safe_revision = revision.replace("/", "_").replace("\\", "_")
    encoded_revision = urllib.parse.quote(revision, safe="")
    archive = DOWNLOAD_CACHE / f"rfb-{safe_revision}.zip"
    url = f"https://github.com/UncleFvcker/RoguelikeFansBand-zh-CN/archive/refs/tags/{encoded_revision}.zip"
    download_verified(url, archive)
    print(f"准备 RFB 发布版源码归档：{revision}")
    safe_extract_zip(archive, VENDOR)
    if not (VENDOR / "src" / "main.c").is_file() or not (VENDOR / "lib").is_dir():
        raise SystemExit("错误：下载的 RFB 源码归档缺少 src/main.c 或 lib/。")


def patch_h_config() -> None:
    path = VENDOR / "src" / "h-config.h"
    text = path.read_text(encoding="utf-8")
    sentinel = "RFB_ANDROID_NO_SET_UID"
    if sentinel in text:
        return

    marker = "/*\n * OPTION: Set \"USG\" for \"System V\" versions of Unix"
    pos = text.find(marker)
    if pos < 0:
        raise SystemExit("错误：无法在 h-config.h 中定位 SET_UID 区段边界；上游源码结构可能变化。")

    injection = (
        "/* RFB_ANDROID_NO_SET_UID: Android apps are sandboxed processes, not setuid installs. */\n"
        "#ifdef __ANDROID__\n"
        "# undef SET_UID\n"
        "#endif\n\n"
    )
    path.write_text(text[:pos] + injection + text[pos:], encoding="utf-8", newline="\n")
    print("已应用 Android 沙箱补丁：h-config.h 不启用 SET_UID 路径。")



def patch_corny_format_security() -> None:
    """Fix one upstream nested-format call rejected by Android's hardening flags.

    format(...) already returns the finished text. Passing that result back as
    sprintf's format string is both unnecessary and rejected by
    -Werror=format-security. Preserve the exact text by copying it through a
    literal "%s" format instead.
    """
    path = VENDOR / "src" / "corny.c"
    text = path.read_text(encoding="utf-8")
    old = 'sprintf(tmp_val, format("(Claimable: %d) ", _allowable()));'
    new = 'sprintf(tmp_val, "%s", format("(Claimable: %d) ", _allowable()));'
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"错误：corny.c 的 Claimable 格式调用数量为 {count}，预期为 1；拒绝盲目补丁。")
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")
    print("已修正 corny.c 的 Android format-security 兼容问题。")



def _replace_exact_patch(path: Path, old: str, new: str, label: str) -> None:
    """Apply one exact source patch and verify it survived post-write.

    Idempotence is decided from the exact old/new target pair, not from a
    short replacement fragment that may legitimately occur elsewhere in the
    same source file. This matters for dungeon.c, which contains other correct
    NONLIVING tests besides the one buggy allergy condition.
    """
    text = path.read_text(encoding="utf-8")
    old_count = text.count(old)
    new_count = text.count(new)

    if old_count == 1:
        text = text.replace(old, new, 1)
        path.write_text(text, encoding="utf-8", newline="\n")
    elif old_count == 0 and new_count == 1:
        return
    else:
        raise SystemExit(
            f"错误：{label} 的旧模式数量为 {old_count}、新模式数量为 {new_count}；"
            "无法唯一确认目标，拒绝盲目补丁。"
        )

    check = path.read_text(encoding="utf-8")
    if old in check or check.count(new) != 1:
        raise SystemExit(f"错误：{label} 写入后的校验失败。")
    print(f"已修正：{label}。")


def patch_semantic_warning_fixes() -> None:
    """Apply narrowly-scoped semantic fixes discovered by the NDK audit."""
    _replace_exact_patch(
        VENDOR / "src" / "personality.c",
        'if ((p_ptr->lev < 1) && (p_ptr->lev > 50)) return;',
        'if ((p_ptr->lev < 1) || (p_ptr->lev > 50)) return;',
        "personality.c 混沌性格等级边界条件（不可能的 && 改为 ||）",
    )

    # Match the whole allergy condition. v4.8 used a short replacement fragment
    # and skipped the buggy line whenever another correct NONLIVING test already
    # existed elsewhere in dungeon.c.
    old_allergy = (
        'if ((p_ptr->cursed & OFC_ALLERGY) && (!p_ptr->unwell) '
        '&& (one_in_(888)) && (!get_race()->flags & RACE_IS_NONLIVING))'
    )
    new_allergy = (
        'if ((p_ptr->cursed & OFC_ALLERGY) && (!p_ptr->unwell) '
        '&& (one_in_(888)) && (!(get_race()->flags & RACE_IS_NONLIVING)))'
    )
    _replace_exact_patch(
        VENDOR / "src" / "dungeon.c",
        old_allergy,
        new_allergy,
        "dungeon.c 过敏事件的 NONLIVING 标志判断优先级",
    )


def patch_cmd4_trigraph_source() -> None:
    """Preserve visible '???' without forming the C trigraph source token ??<."""
    path = VENDOR / "src" / "cmd4.c"
    old = '<color:y>???</color>'
    new = '<color:y>??" "?</color>'
    _replace_exact_patch(
        path,
        old,
        new,
        "cmd4.c 生命评级问号文本的 trigraph 源码规避",
    )


def patch_wizard1_int_vector_comparator() -> None:
    """Replace an incompatible function-pointer cast with an ABI-correct adapter.

    c-vec stores integer values as (void *)(intptr_t)value and passes those
    stored values directly to vec_cmp_f. The original comparator takes int,int;
    calling it through a const-void* function type is undefined behavior on
    ABIs where pointer and int argument widths differ. The adapter performs the
    intended inverse conversion explicitly.
    """
    path = VENDOR / "src" / "wizard1.c"
    text = path.read_text(encoding="utf-8")
    call_old = 'vec_sort(vec, (vec_cmp_f)_cmp_class_name);'
    call_new = 'vec_sort(vec, _cmp_class_name_vec);'
    adapter_marker = 'static int _cmp_class_name_vec(const void *left, const void *right)'

    old_calls = text.count(call_old)
    new_calls = text.count(call_new)

    if adapter_marker in text:
        if old_calls != 0 or new_calls != 3:
            raise SystemExit(
                "错误：wizard1.c 比较器适配器已存在，但调用点状态与预期不一致。"
            )
        return

    if old_calls != 3 or new_calls != 0:
        raise SystemExit(
            f"错误：wizard1.c 的 _cmp_class_name 旧调用点为 {old_calls}、"
            f"新调用点为 {new_calls}，预期 3/0；拒绝盲目补丁。"
        )

    definition = re.search(
        r'(?m)^static\s+int\s+_cmp_class_name\s*\(\s*int\s+\w+\s*,\s*int\s+\w+\s*\)',
        text,
    )
    if not definition:
        raise SystemExit("错误：wizard1.c 中无法唯一定位 _cmp_class_name(int,int) 定义。")

    adapter = (
        "static int _cmp_class_name(int, int);\n\n"
        "static int _cmp_class_name_vec(const void *left, const void *right)\n"
        "{\n"
        "    return _cmp_class_name((int)(intptr_t)left, (int)(intptr_t)right);\n"
        "}\n\n"
    )

    text = text[:definition.start()] + adapter + text[definition.start():]
    text = text.replace(call_old, call_new)
    path.write_text(text, encoding="utf-8", newline="\n")

    check = path.read_text(encoding="utf-8")
    if check.count(adapter_marker) != 1 or check.count(call_new) != 3 or call_old in check:
        raise SystemExit("错误：wizard1.c 整数向量比较器适配补丁校验失败。")
    print("已修正：wizard1.c 整数向量比较器使用类型正确的 vec_cmp_f 适配器。")


def verify_known_warning_fixes() -> None:
    """Fail before Gradle if any known dangerous source pattern survives."""
    checks = [
        (
            VENDOR / "src" / "personality.c",
            'if ((p_ptr->lev < 1) && (p_ptr->lev > 50)) return;',
            False,
            "personality.c 不可能等级条件仍存在",
        ),
        (
            VENDOR / "src" / "dungeon.c",
            '&& (!get_race()->flags & RACE_IS_NONLIVING))',
            False,
            "dungeon.c 错误的 logical-not/bitmask 优先级仍存在",
        ),
        (
            VENDOR / "src" / "cmd4.c",
            '<color:y>???</color>',
            False,
            "cmd4.c trigraph 源序列仍存在",
        ),
        (
            VENDOR / "src" / "wizard1.c",
            'vec_sort(vec, (vec_cmp_f)_cmp_class_name);',
            False,
            "wizard1.c 不兼容函数指针强转仍存在",
        ),
        (
            VENDOR / "src" / "wizard1.c",
            'static int _cmp_class_name_vec(const void *left, const void *right)',
            True,
            "wizard1.c 比较器适配器缺失",
        ),
    ]
    for path, token, must_exist, message in checks:
        text = path.read_text(encoding="utf-8")
        exists = token in text
        if exists != must_exist:
            raise SystemExit(f"错误：补丁后源代码审计失败：{message}。")
    print("补丁后源代码审计通过：已知高风险模式均已消除。")

def write_warning_audit_report() -> None:
    """Write a machine-readable build artifact describing warning policy."""
    GENERATED.mkdir(parents=True, exist_ok=True)
    report = GENERATED / "warning-audit.txt"
    report.write_text(
        """RoguelikeFansBand Android warning audit\n
"""
        "Semantic fixes applied:\n"
        "- personality.c: invalid level guard uses OR, preventing out-of-range level indexing.\n"
        "- dungeon.c: the allergy-event NONLIVING test applies ! to the masked flag result.\n"
        "- cmd4.c: source token is split across adjacent string literals so visible ??? text is preserved without forming a trigraph.\n"
        "- wizard1.c: int-vector class sorting uses a correctly typed vec_cmp_f adapter.\n"
        "\nCompiler policy:\n"
        "- High-risk diagnostics are promoted to errors (format security, implicit declarations, return type, pointer/int conversion, uninitialized use, array bounds, logical-not precedence, impossible overlap comparisons).\n"
        "- Style/maintainability diagnostics such as misleading indentation and unused variables remain visible warnings; they are not silently suppressed or blindly behavior-patched.\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"生成警告审计报告：{report.relative_to(ROOT)}")

def generate_android_main() -> None:
    src = VENDOR / "src" / "main.c"
    out = GENERATED / "rfb_main_android.c"
    text = src.read_text(encoding="utf-8")

    signature = "int main(int argc, char *argv[])"
    if text.count(signature) != 1:
        raise SystemExit("错误：main.c 的 main() 签名与预期不一致，拒绝盲目修改。")
    text = text.replace(signature, "int rfb_core_main(int argc, char *argv[])", 1)

    marker = "/* Make sure we have a display! */"
    pos = text.find(marker)
    if pos < 0:
        raise SystemExit("错误：main.c 中找不到显示模块检查标记，拒绝盲目插桩。")

    android_block = r'''
#ifdef USE_ANDROID
    /* Dedicated RoguelikeFansBand Android frontend. This is not angbandroid's
     * loader/plugin ABI: it is linked directly into this one RFB shared lib. */
    if (!done && (!mstr || streq(mstr, "android")))
    {
        extern errr init_android(int, char **);
        if (0 == init_android(argc, argv))
        {
            ANGBAND_SYS = "android";
            done = TRUE;
        }
    }
#endif

'''
    text = text[:pos] + android_block + text[pos:]
    GENERATED.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8", newline="\n")
    print(f"生成 Android 专用入口：{out.relative_to(ROOT)}")


def parse_make_var(text: str, name: str) -> list[str]:
    lines = text.splitlines()
    collecting = False
    buf: list[str] = []
    assign_re = re.compile(rf"^{re.escape(name)}\s*=\s*(.*)$")

    for line in lines:
        if not collecting:
            m = assign_re.match(line)
            if not m:
                continue
            value = m.group(1).strip()
            collecting = value.endswith("\\")
            if collecting:
                value = value[:-1]
            buf.append(value)
            if not collecting:
                break
        else:
            value = line.strip()
            collecting = value.endswith("\\")
            if collecting:
                value = value[:-1]
            buf.append(value)
            if not collecting:
                break

    if not buf:
        raise SystemExit(f"错误：Makefile.src 中找不到变量 {name}。")
    return " ".join(buf).split()


def generate_source_list() -> None:
    makefile = VENDOR / "src" / "Makefile.src"
    text = makefile.read_text(encoding="utf-8-sig")
    objects: list[str] = []
    for name in ("CFILES", "ZFILES", "ANGFILES"):
        objects.extend(parse_make_var(text, name))

    sources: list[str] = []
    missing: list[str] = []
    for obj in objects:
        if not obj.endswith(".o"):
            continue
        src_name = obj[:-2] + ".c"
        if not (VENDOR / "src" / src_name).is_file():
            missing.append(src_name)
        sources.append(src_name)

    if missing:
        raise SystemExit("错误：Makefile.src 引用了不存在的源码：\n" + "\n".join(missing))

    out = GENERATED / "rfb_sources.cmake"
    lines = ["# Generated by tools/prepare_source.py; do not edit.", "set(RFB_GAME_SOURCES"]
    lines.extend(f'    "${{RFB_SRC}}/{name}"' for name in sources)
    lines.append(")")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"生成 CMake 源码清单：{len(sources)} 个游戏核心 C 文件。")


def should_skip_resource(rel: Path) -> bool:
    parts = rel.parts
    if not parts:
        return False
    if parts[0] in WRITABLE_DIRS:
        return True
    if rel.suffix.lower() in FONT_SUFFIXES:
        return True
    return False


def write_zip_file_deterministically(zf: zipfile.ZipFile, path: Path, arcname: str) -> None:
    """Write one file with stable metadata so identical inputs make identical APK assets."""
    info = zipfile.ZipInfo(arcname, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    with path.open("rb") as source, zf.open(info, "w", force_zip64=True) as target:
        shutil.copyfileobj(source, target, length=1024 * 1024)


def prepare_assets(revision: str, resolution: dict[str, object]) -> None:
    lib = VENDOR / "lib"
    if not lib.is_dir():
        raise SystemExit(f"错误：源码目录中没有 {lib}。")

    ASSETS.mkdir(parents=True, exist_ok=True)
    zip_path = ASSETS / "rfb-data.zip"
    manifest_path = ASSETS / "rfb-build-meta.json"

    entries: list[dict[str, object]] = []
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for path in sorted(p for p in lib.rglob("*") if p.is_file()):
            rel = path.relative_to(lib)
            if should_skip_resource(rel):
                continue
            arc = (Path("lib") / rel).as_posix()
            # zipfile.write copies bytes; it does not decode or transcode mixed
            # UTF-8/EUC-JP/other legacy text resources.
            write_zip_file_deterministically(zf, path, arc)
            digest = sha256_file(path)
            entries.append({"path": arc, "size": path.stat().st_size, "sha256": digest})

        for writable in sorted(WRITABLE_DIRS):
            info = zipfile.ZipInfo(f"lib/{writable}/", date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = (0o40755 << 16) | 0x10
            zf.writestr(info, b"")

    fingerprint_payload = json.dumps(
        {"schema": 2, "source_revision": revision, "resources": entries},
        ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")
    resource_revision = "rfb-res-v2-" + hashlib.sha256(fingerprint_payload).hexdigest()

    meta = {
        "source_repository": REPO_URL,
        "source_revision": revision,
        "source_resolution": resolution,
        "android_port_version": ANDROID_PORT_VERSION,
        "resource_revision": resource_revision,
        "resource_file_count": len(entries),
        "resources": entries,
        "notes": [
            "Runtime resource bytes are preserved exactly; no text transcoding is performed.",
            "Writable save/user/apex/bone/data directories are created empty at install time.",
            "Font binaries are intentionally not bundled; Android system font fallback is used.",
        ],
    }
    manifest_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"打包运行资源：{zip_path.relative_to(ROOT)}（{len(entries)} 个文件）")


def android_version_values(revision: str) -> tuple[int, str]:
    """Map a four-part upstream release to a monotonic Android version code."""
    match = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)\.(\d+)", revision)
    if match:
        major, minor, patch, build = (int(value) for value in match.groups())
        if major > 20 or minor > 99 or patch > 99 or build > 999:
            raise SystemExit(f"错误：发布版本超出 Android versionCode 编码范围：{revision}")
        code = (major * 100_000_000 + minor * 1_000_000
                + patch * 10_000 + build * 10 + ANDROID_PORT_REVISION)
    else:
        # Local/custom source trees are deliberately non-release builds.
        code = 700_000_000 + ANDROID_PORT_REVISION
    if code <= 0 or code > 2_100_000_000:
        raise SystemExit(f"错误：生成的 Android versionCode 非法：{code}")
    clean_revision = revision[1:] if revision.startswith("v") else revision
    return code, f"{clean_revision}-android-v{ANDROID_PORT_VERSION}"


def write_revision_stamp(revision: str, resolution: dict[str, object]) -> None:
    GENERATED.mkdir(parents=True, exist_ok=True)
    (GENERATED / "rfb_revision.txt").write_text(revision + "\n", encoding="utf-8")
    version_code, version_name = android_version_values(revision)
    properties = (
        f"rfbRevision={revision}\n"
        f"rfbVersionCode={version_code}\n"
        f"rfbVersionName={version_name}\n"
        f"rfbAndroidPortVersion={ANDROID_PORT_VERSION}\n"
    )
    (GENERATED / "rfb_build.properties").write_text(
        properties, encoding="ascii", newline="\n")
    compatibility = {
        "schema": 1,
        "source_repository": REPO_URL,
        "source_revision": revision,
        "source_resolution": resolution,
        "android_port_version": ANDROID_PORT_VERSION,
        "version_code": version_code,
        "version_name": version_name,
        "patch_chain_completed": True,
    }
    (GENERATED / "upstream-compatibility.json").write_text(
        json.dumps(compatibility, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Android 动态版本：versionCode={version_code}，versionName={version_name}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--revision", default=DEFAULT_REVISION,
        help="Release tag, or 'latest' for the upstream latest stable release")
    parser.add_argument("--source-dir", type=Path, help="Use a local RFB source tree instead of downloading the tagged archive")
    parser.add_argument("--offline", action="store_true", help="Resolve latest only from the last successful cache")
    args = parser.parse_args()

    if args.source_dir and str(args.revision).strip().lower() in {"latest", "stable"}:
        revision = "local-source"
        resolution: dict[str, object] = {
            "mode": "local-source", "requested": args.revision,
            "source_dir": str(args.source_dir.absolute()), "tag_name": revision,
        }
    else:
        revision, resolution = resolve_source_revision(args.revision, args.offline)

    checkout_source(revision, args.source_dir)
    patch_h_config()
    patch_corny_format_security()
    patch_semantic_warning_fixes()
    patch_cmd4_trigraph_source()
    patch_wizard1_int_vector_comparator()
    verify_known_warning_fixes()
    patch_http_curl_ca()
    install_ca_bundle()
    prepare_third_party()
    generate_android_main()
    generate_source_list()
    write_warning_audit_report()
    prepare_assets(revision, resolution)
    write_revision_stamp(revision, resolution)
    print("RFB Android 源码准备完成。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        print(f"命令执行失败，退出码 {exc.returncode}", file=sys.stderr)
        raise SystemExit(exc.returncode)
