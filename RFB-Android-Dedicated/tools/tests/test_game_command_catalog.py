#!/usr/bin/env python3
"""Compile and execute the game-aware key-purpose catalog."""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/java/org/roguelikefansband/android/GameCommandCatalog.java"


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
    with tempfile.TemporaryDirectory(prefix="rfb-command-catalog-") as temp_name:
        temp = Path(temp_name)
        pkg = temp / "org/roguelikefansband/android"
        pkg.mkdir(parents=True)
        (pkg / SOURCE.name).write_text(SOURCE.read_text(encoding="utf-8"), encoding="utf-8")
        (pkg / "GameCommandCatalogTest.java").write_text(r'''
package org.roguelikefansband.android;
public final class GameCommandCatalogTest {
  private static void eq(String want, String got) {
    if (!want.equals(got)) throw new AssertionError(want + " != " + got);
  }
  public static void main(String[] args) {
    eq("瞄准魔杖", GameCommandCatalog.label('a', false));
    eq("激活物品", GameCommandCatalog.label('A', false));
    eq("调试模式", GameCommandCatalog.label('a', true));
    eq("保存游戏", GameCommandCatalog.label('s', true));
    eq("取消/返回（Esc）", GameCommandCatalog.label('[', true));
    System.out.println("GameCommandCatalog tests passed");
  }
}
''', encoding="utf-8")
        classes = temp / "classes"
        subprocess.run([javac, "-encoding", "UTF-8", "-d", str(classes),
                        *map(str, temp.rglob("*.java"))], check=True)
        subprocess.run([java, "-cp", str(classes),
                        "org.roguelikefansband.android.GameCommandCatalogTest"], check=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
