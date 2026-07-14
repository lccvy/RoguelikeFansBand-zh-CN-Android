#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import tempfile

ROOT = Path(__file__).absolute().parents[2]


def load_build():
    spec = importlib.util.spec_from_file_location("rfb_build", ROOT / "tools/build.py")
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    m = load_build()
    escaped = m._java_properties_escape(r"D:\中文 目录\RFB:Android\portable_workspace\runtime\android-sdk")
    assert "中文" not in escaped
    assert "\\u4e2d\\u6587" in escaped
    assert "\\ " in escaped
    assert "\\:" in escaped

    with tempfile.TemporaryDirectory(prefix="rfb portable test ") as td:
        old_portable = m.PORTABLE
        old_env = dict(os.environ)
        try:
            m.PORTABLE = Path(td) / "portable_workspace"
            m.configure_portable_environment()
            for key in ("GRADLE_USER_HOME", "ANDROID_USER_HOME", "HOME", "TEMP", "TMP"):
                assert Path(os.environ[key]).is_relative_to(m.PORTABLE)
            assert "ANDROID_SDK_HOME" not in os.environ
        finally:
            m.PORTABLE = old_portable
            os.environ.clear()
            os.environ.update(old_env)

    prep = (ROOT / "tools/prepare_source.py").read_text(encoding="utf-8")
    assert 'shutil.which("git")' not in prep
    assert "archive/refs/tags" in prep

    builder = (ROOT / "tools/build.py").read_text(encoding="utf-8")
    for token in ("validate_apk", "zf.testzip()", "apksigner", "rfbVersionName"):
        assert token in builder

    app_gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
    assert "rfb-local-debug.keystore" in app_gradle
    assert "signingConfig signingConfigs.localDebug" in app_gradle

    portable_build = (ROOT / "tools/portable_build.ps1").read_text(encoding="utf-16")
    assert "RFB_PORTABLE_BOOTSTRAPPED" in portable_build
    assert "RFB_JAVA_EXE" in portable_build
    assert "bin\\java.exe" in portable_build
    assert "Get-JavaVersionLine" in portable_build
    assert "$env:JAVA_TOOL_OPTIONS =" not in portable_build
    assert "FinalExitCode" in portable_build
    assert "Remove-Item Env:ANDROID_SDK_HOME" in portable_build
    assert "portable-build-exit-code.txt" in portable_build

    bootstrap = (ROOT / "tools/portable_bootstrap.ps1").read_text(encoding="utf-16")
    for token in ("GRADLE_USER_HOME", "ANDROID_USER_HOME", "ANDROID_SDK_ROOT", "$env:TEMP", "$env:TMP"):
        assert token in bootstrap
    assert "Get-JavaVersionLine" in bootstrap
    assert "$env:JAVA_TOOL_OPTIONS =" not in bootstrap
    assert "Remove-Item Env:ANDROID_SDK_HOME" in bootstrap

    gui = (ROOT / "tools/portable_gui.ps1").read_text(encoding="utf-16")
    assert "构建进程返回退出码 0，但 dist 中没有本次构建生成的 APK" in gui
    assert "Get-ChildItem -LiteralPath $Dist -Filter '*.apk'" in gui
    assert "portable-build-exit-code.txt" in gui

    print("portable isolation tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
