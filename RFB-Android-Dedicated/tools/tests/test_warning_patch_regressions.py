#!/usr/bin/env python3
"""Host-side smoke tests for warning fixes that do not require Android NDK."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path


def compile_c(source: str, *flags: str) -> None:
    clang = shutil.which("clang")
    if not clang:
        print("clang not found; warning patch compile smoke tests skipped")
        return
    with tempfile.TemporaryDirectory(prefix="rfb-warning-smoke-") as td:
        root = Path(td)
        src = root / "test.c"
        out = root / "test.o"
        src.write_text(source, encoding="utf-8")
        subprocess.run(
            [clang, "-std=c11", "-Wall", "-Wextra", *flags, "-c", str(src), "-o", str(out)],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )


def main() -> int:
    # ABI-correct comparator adapter: no incompatible function pointer cast.
    compile_c(
        r'''
#include <stdint.h>
typedef int (*vec_cmp_f)(const void *, const void *);
static int _cmp_class_name(int c1, int c2) { return (c1 > c2) - (c1 < c2); }
static int _cmp_class_name_vec(const void *left, const void *right)
{
    return _cmp_class_name((int)(intptr_t)left, (int)(intptr_t)right);
}
static int call_cmp(vec_cmp_f f) { return f((void *)(intptr_t)2, (void *)(intptr_t)1); }
int f(void) { return call_cmp(_cmp_class_name_vec); }
''',
        "-Werror=cast-function-type-mismatch",
    )

    # Adjacent literals preserve runtime ??? while avoiding the source token ??<.
    compile_c(
        r'''
static const char *s = "<color:y>??" "?</color>";
int f(void) { return s[0]; }
''',
        "-Werror=trigraphs",
    )

    print("warning patch regression compile smoke tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
