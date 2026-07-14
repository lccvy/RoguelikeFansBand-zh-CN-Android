#!/usr/bin/env python3
"""Compile RfbKeyEncoder against a tiny KeyEvent stub and verify trigger bytes."""
from __future__ import annotations

import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/org/roguelikefansband/android/RfbKeyEncoder.java"


def main() -> int:
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        raise SystemExit("javac/java not found; JDK 17+ is required for this regression test.")

    source = SOURCE.read_text(encoding="utf-8")
    keys: list[str] = []
    for key in re.findall(r"KeyEvent\.(KEYCODE_[A-Z0-9_]+)", source):
        if key not in keys:
            keys.append(key)

    with tempfile.TemporaryDirectory(prefix="rfb-key-test-") as temp_name:
        temp = Path(temp_name)
        stub = temp / "android/view/KeyEvent.java"
        pkg = temp / "org/roguelikefansband/android"
        stub.parent.mkdir(parents=True)
        pkg.mkdir(parents=True)

        lines = [
            "package android.view;",
            "public class KeyEvent {",
            "  public static final int ACTION_DOWN=0;",
        ]
        for i, key in enumerate(keys, 1):
            lines.append(f"  public static final int {key}={i};")
        lines.extend([
            "  private final int action,keyCode; private final boolean c,s,a;",
            "  public KeyEvent(int action,int keyCode){this(action,keyCode,false,false,false);}",
            "  public KeyEvent(int action,int keyCode,boolean c,boolean s,boolean a){this.action=action;this.keyCode=keyCode;this.c=c;this.s=s;this.a=a;}",
            "  public int getAction(){return action;} public int getKeyCode(){return keyCode;}",
            "  public boolean isCtrlPressed(){return c;} public boolean isShiftPressed(){return s;} public boolean isAltPressed(){return a;}",
            "}",
        ])
        stub.write_text("\n".join(lines), encoding="utf-8")
        (pkg / "RfbKeyEncoder.java").write_text(source, encoding="utf-8")
        (pkg / "RfbKeyEncoderTest.java").write_text(r'''
package org.roguelikefansband.android;
import android.view.KeyEvent;
public final class RfbKeyEncoderTest {
  private static String visible(byte[] b) {
    StringBuilder s=new StringBuilder();
    for(byte v:b){ int x=v&255; if(x==0x1F)s.append("<US>"); else if(x==0x0D)s.append("<CR>"); else s.append((char)x); }
    return s.toString();
  }
  private static void eq(String want, byte[] got) {
    String x=visible(got); if(!want.equals(x)) throw new AssertionError(want+" != "+x);
  }
  public static void main(String[] args) {
    eq("<US>x48<CR>", RfbKeyEncoder.encode(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)));
    eq("<US>CSAxK4F<CR>", RfbKeyEncoder.encode(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_1,true,true,true)));
    eq("<US>x3A<CR>", RfbKeyEncoder.encode(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAPS_LOCK)));
    eq("<US>CxK47<CR>", RfbKeyEncoder.encode(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_7,true,false,false)));
    if(RfbKeyEncoder.encode(new KeyEvent(KeyEvent.ACTION_DOWN, 99999)) != null) throw new AssertionError("unknown key must fall through");
    System.out.println("RfbKeyEncoder protocol tests passed");
  }
}
''', encoding="utf-8")

        classes = temp / "classes"
        java_files = [str(p) for p in temp.rglob("*.java")]
        subprocess.run([javac, "-d", str(classes), *java_files], check=True)
        subprocess.run([java, "-cp", str(classes), "org.roguelikefansband.android.RfbKeyEncoderTest"], check=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
