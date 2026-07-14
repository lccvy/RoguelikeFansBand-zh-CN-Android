#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CMAKE = ROOT / "app/src/main/cpp/CMakeLists.txt"
COMPAT = ROOT / "app/src/main/cpp/config/rfb_android_compat.h"
PREP = ROOT / "tools/prepare_source.py"


def main() -> int:
    cmake = CMAKE.read_text(encoding="utf-8")
    compat = COMPAT.read_text(encoding="utf-8")
    prep = PREP.read_text(encoding="utf-8")

    assert "set(BUILD_EXAMPLES OFF" in cmake
    assert "rfb_android_compat.h" in cmake
    assert "-fno-trigraphs" in cmake
    for token in ("#include <unistd.h>", "#define _read read", "#define _write write", "#define O_BINARY 0"):
        assert token in compat
    assert "def patch_corny_format_security()" in prep
    assert 'sprintf(tmp_val, "%s", format("(Claimable: %d) ", _allowable()));' in prep
    assert "def patch_semantic_warning_fixes()" in prep
    assert "(p_ptr->lev < 1) || (p_ptr->lev > 50)" in prep
    assert "(!(get_race()->flags & RACE_IS_NONLIVING))" in prep
    assert "def patch_cmd4_trigraph_source()" in prep
    assert "def patch_wizard1_int_vector_comparator()" in prep
    assert "def verify_known_warning_fixes()" in prep
    for flag in (
        "-Werror=format-security",
        "-Werror=implicit-function-declaration",
        "-Werror=return-type",
        "-Werror=incompatible-pointer-types",
        "-Werror=int-conversion",
        "-Werror=uninitialized",
        "-Werror=array-bounds",
        "-Werror=logical-not-parentheses",
        "-Werror=tautological-overlap-compare",
        "-Werror=cast-function-type-mismatch",
        "-Werror=trigraphs",
    ):
        assert flag in cmake
    print("native portability sanity tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
