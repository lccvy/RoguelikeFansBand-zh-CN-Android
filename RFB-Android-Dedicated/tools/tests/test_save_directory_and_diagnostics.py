#!/usr/bin/env python3
"""Contracts for the actionable save directory and quiet, bounded clean exits."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/org/roguelikefansband/android"


def main() -> int:
    activity = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
    diagnostics = (JAVA / "StartupDiagnostics.java").read_text(encoding="utf-8")

    save_start = activity.index("private void showSaveManager()")
    save_end = activity.index("private List<String> discoverSaveSlots()", save_start)
    save_block = activity[save_start:save_end]
    for token in (
        'setTitle("存档目录")', 'setView(scroll)', 'addSaveSlotRow',
        '"打开"', '"新游戏"', 'promptNewSlot()',
    ):
        assert token in save_block
    assert ".setItems(" not in save_block
    assert ".setMessage(" not in save_block

    prompt_start = activity.index("private void promptNewSlot()")
    prompt_end = activity.index("private void confirmSwitchSlot", prompt_start)
    prompt_block = activity[prompt_start:prompt_end]
    assert 'setPositiveButton("开始新游戏"' in prompt_block
    assert "requestSessionSwitch(slot, true)" in prompt_block

    assert "boolean normalExit = result == 0" in activity
    assert "StartupDiagnostics.clearAfterNormalExit()" in activity
    normal_ui_start = activity.index("if (normalExit) {")
    normal_ui_end = activity.index("StringBuilder message", normal_ui_start)
    normal_ui = activity[normal_ui_start:normal_ui_end]
    assert "游戏已保存并正常退出" in normal_ui
    assert "诊断日志" not in normal_ui

    for token in (
        "MAX_LOG_BYTES = 256L * 1024L", "clearAfterNormalExit",
        "deleteOrTruncateLocked", "rotateOversizedLogLocked(bytes.length)",
        "rfb-startup.previous.log", "boundedUtf8", "trimOversizedPreviousLocked",
    ):
        assert token in diagnostics

    print("save directory / quiet diagnostics tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
