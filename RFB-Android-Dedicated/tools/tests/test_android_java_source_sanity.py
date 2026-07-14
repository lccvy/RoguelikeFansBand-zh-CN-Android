#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TERM = ROOT / "app/src/main/java/org/roguelikefansband/android/RfbTermView.java"
DIAGNOSTICS = ROOT / "app/src/main/java/org/roguelikefansband/android/StartupDiagnostics.java"
PREP = ROOT / "tools/prepare_source.py"
MAIN = ROOT / "app/src/main/java/org/roguelikefansband/android/MainActivity.java"
KEYBOARD = ROOT / "app/src/main/java/org/roguelikefansband/android/GameKeyboardDialog.java"
SEQUENCE = ROOT / "app/src/main/java/org/roguelikefansband/android/GameKeySequence.java"

def main() -> int:
    term = TERM.read_text(encoding="utf-8")
    assert "new Paint(Paint.Style.FILL)" not in term
    assert "private final Paint backgroundPaint = new Paint();" in term
    assert "backgroundPaint.setStyle(Paint.Style.FILL);" in term
    prep = PREP.read_text(encoding="utf-8")
    assert 'tf.extractall(temp_root, filter="data")' in prep
    diagnostics = DIAGNOSTICS.read_text(encoding="utf-8")
    assert "chooseWritableLogDirectory(context)" in diagnostics
    assert "external.canWrite()" in diagnostics
    assert "context.getFilesDir()" in diagnostics
    main = MAIN.read_text(encoding="utf-8")
    keyboard = KEYBOARD.read_text(encoding="utf-8")
    sequence = SEQUENCE.read_text(encoding="utf-8")
    for token in ("showSaveManager", "showQuickKeyEditor", "showDisplaySettings", "forceNew"):
        assert token in main
    for token in (
        "Ctrl 下一键", "Ctrl 锁定", "GameCommandCatalog.label", "sendCharacter",
        "FrameLayout host", "host.addView(rootLayout)", "WRAP_CONTENT", "PREF_SIZE",
        "PREF_WIDTH", "PREF_OPACITY", "PressRepeater.bind", "addDirectionRow",
        "键帽与整体高度", "键盘不透明度", "makePanelBackground",
    ):
        assert token in keyboard
    assert "Theme_Material_NoActionBar_Fullscreen" not in keyboard
    assert "extends Dialog" not in keyboard
    assert "MATCH_PARENT, 0, 1f" not in keyboard
    for token in ("{CTRL-S}", "CTRL-", 'case "NW"', "return one('8')"):
        assert token in sequence
    print("Android Java source sanity tests passed")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
