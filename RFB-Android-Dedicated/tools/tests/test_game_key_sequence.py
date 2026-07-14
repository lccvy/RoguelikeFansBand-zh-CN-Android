#!/usr/bin/env python3
"""Compile and execute the pure-Java editable action encoder."""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/org/roguelikefansband/android/GameKeySequence.java"


def find_tool(name: str) -> str | None:
    found = shutil.which(name)
    if found:
        return found
    suffix = ".exe" if os.name == "nt" else ""
    local = ROOT / "portable_workspace/runtime/jdk/bin" / (name + suffix)
    return str(local) if local.is_file() else None


def main() -> int:
    javac = find_tool("javac")
    java = find_tool("java")
    if not javac or not java:
        raise SystemExit("javac/java not found; prepare the portable JDK first")
    with tempfile.TemporaryDirectory(prefix="rfb-sequence-test-") as temp_name:
        temp = Path(temp_name)
        pkg = temp / "org/roguelikefansband/android"
        pkg.mkdir(parents=True)
        (pkg / SOURCE.name).write_text(SOURCE.read_text(encoding="utf-8"), encoding="utf-8")
        (pkg / "GameKeySequenceTest.java").write_text(r'''
package org.roguelikefansband.android;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
public final class GameKeySequenceTest {
  private static void eq(byte[] want, byte[] got) {
    if (!Arrays.equals(want, got)) throw new AssertionError(Arrays.toString(want)+" != "+Arrays.toString(got));
  }
  public static void main(String[] args) {
    eq("g".getBytes(StandardCharsets.UTF_8), GameKeySequence.encode("g"));
    eq(new byte[]{27,13,19,24}, GameKeySequence.encode("{ESC}{ENTER}{CTRL-S}{CTRL-X}"));
    eq("8".getBytes(StandardCharsets.UTF_8), GameKeySequence.encode("{UP}"));
    eq("789456123".getBytes(StandardCharsets.UTF_8),
       GameKeySequence.encode("{NW}{UP}{NE}{LEFT}{WAIT}{RIGHT}{SW}{DOWN}{SE}"));
    eq("{abc".getBytes(StandardCharsets.UTF_8), GameKeySequence.encode("{abc"));
    boolean failed=false;
    try { GameKeySequence.encode("{UNKNOWN}"); } catch (IllegalArgumentException expected) { failed=true; }
    if(!failed) throw new AssertionError("unknown token accepted");
    System.out.println("GameKeySequence tests passed");
  }
}
''', encoding="utf-8")
        classes = temp / "classes"
        subprocess.run([javac, "-d", str(classes), *map(str, temp.rglob("*.java"))], check=True)
        subprocess.run([java, "-cp", str(classes),
                        "org.roguelikefansband.android.GameKeySequenceTest"], check=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
