#!/usr/bin/env python3
"""Source-level contracts for the touch HUD, repeat input and clean slot restart."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/org/roguelikefansband/android"


def main() -> int:
    activity = (JAVA / "MainActivity.java").read_text(encoding="utf-8")
    keyboard = (JAVA / "GameKeyboardDialog.java").read_text(encoding="utf-8")
    hud = (JAVA / "CustomHudOverlay.java").read_text(encoding="utf-8")
    store = (JAVA / "VirtualKeyStore.java").read_text(encoding="utf-8")
    repeat = (JAVA / "PressRepeater.java").read_text(encoding="utf-8")
    feedback = (JAVA / "TouchFeedbackButton.java").read_text(encoding="utf-8")

    for token in (
        "new GameKeyboardDialog(this, termContainer", "CustomHudOverlay",
        "PendingIntent.getActivity", "Process.killProcess", "PREF_FORCE_NEW_ONCE",
        "restartForSession(replacement)", "slotFromSaveFile", "panelSpacer",
        "rootLayout.addView(hudOverlay", "buildHudEditToolbar",
        "hudOverlay.setGameAreaKeysHidden(true)",
        "hudOverlay.setGameAreaKeysHidden(false)",
        "currentHudCoordinateDp", "maximumHudCoordinateDp",
        "normalizedHudCoordinate", "精确坐标（dp，屏幕左上角为 0,0）",
        "addSaveSlotRow", "＋ 新建存档并开始新游戏",
        "requestSessionSwitch(slot, true)", "StartupDiagnostics.clearAfterNormalExit()",
    ):
        assert token in activity
    assert "hudOverlay.setVisibility(View.INVISIBLE)" not in activity
    assert "hudCountView" not in activity and "controlPanel" not in activity
    assert "extends Dialog" not in keyboard
    assert "addDirectionRow()" in keyboard
    assert "PressRepeater.bind(button, repeat" in keyboard
    assert "return editing;" in hud
    assert "listener.onPositionChanged()" in hud
    for token in ("bindPanelDrag", "bindPanelResize", "panel.dockRight",
                  "listener.onPanelChanged()", "setGameAreaKeysHidden",
                  "updateGameAreaKeyVisibility", "insideOperationStrip",
                  "child.setEnabled(show)"):
        assert token in hud
    assert "MAX_KEYS = 64" in store
    for field in ("widthDp", "heightDp", "shape", "opacity", "color", "repeat"):
        assert field in store
    for token in ('{"西北 7", "7"}', '{"东南 3", "3"}', "HudPanelSpec",
                  "PREF_UNIFIED_MIGRATION", "dockRight"):
        assert token in store
    assert "START_DELAY_MS" in repeat and "INTERVAL_MS" in repeat
    assert "HapticFeedbackConstants.KEYBOARD_TAP" in feedback
    assert "state_pressed" in feedback
    print("touch HUD / repeat / slot restart tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
