#!/usr/bin/env python3
"""Regression tests for CA patching, CA installation and safe tar extraction."""
from __future__ import annotations

import importlib.util
import io
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PREPARE = ROOT / "tools/prepare_source.py"


def load_prepare():
    spec = importlib.util.spec_from_file_location("rfb_prepare_source", PREPARE)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> int:
    m = load_prepare()
    with tempfile.TemporaryDirectory(prefix="rfb-prep-test-") as temp_name:
        root = Path(temp_name)
        m.ROOT = root
        m.VENDOR = root / "vendor/rfb"
        m.DOWNLOAD_CACHE = root / "downloads"
        (m.VENDOR / "src").mkdir(parents=True)
        (m.VENDOR / "lib").mkdir(parents=True)

        corny_path = m.VENDOR / "src/corny.c"
        corny_path.write_text(
            'void f(void){char tmp_val[128]; sprintf(tmp_val, format("(Claimable: %d) ", _allowable()));}\n',
            encoding="utf-8",
        )
        m.patch_corny_format_security()
        corny_first = corny_path.read_text(encoding="utf-8")
        assert 'sprintf(tmp_val, "%s", format("(Claimable: %d) ", _allowable()));' in corny_first
        m.patch_corny_format_security()
        assert corny_path.read_text(encoding="utf-8") == corny_first

        personality_path = m.VENDOR / "src/personality.c"
        personality_path.write_text(
            "void f(void){ if ((p_ptr->lev < 1) && (p_ptr->lev > 50)) return; }\n",
            encoding="utf-8",
        )
        dungeon_path = m.VENDOR / "src/dungeon.c"
        bad_allergy = (
            "if ((p_ptr->cursed & OFC_ALLERGY) && (!p_ptr->unwell) "
            "&& (one_in_(888)) && (!get_race()->flags & RACE_IS_NONLIVING))"
        )
        good_allergy = (
            "if ((p_ptr->cursed & OFC_ALLERGY) && (!p_ptr->unwell) "
            "&& (one_in_(888)) && (!(get_race()->flags & RACE_IS_NONLIVING)))"
        )
        # Reproduce the v4.8 regression: the same file already contains another
        # correct NONLIVING expression, which must NOT make the patcher skip the
        # distinct buggy allergy condition.
        dungeon_path.write_text(
            "void already_correct(void){ if (!(get_race()->flags & RACE_IS_NONLIVING)) return; }\n"
            f"void allergy(void){{ {bad_allergy} return; }}\n",
            encoding="utf-8",
        )
        m.patch_semantic_warning_fixes()
        assert "(p_ptr->lev < 1) || (p_ptr->lev > 50)" in personality_path.read_text(encoding="utf-8")
        dungeon_text = dungeon_path.read_text(encoding="utf-8")
        assert bad_allergy not in dungeon_text
        assert good_allergy in dungeon_text
        personality_first = personality_path.read_text(encoding="utf-8")
        dungeon_first = dungeon_path.read_text(encoding="utf-8")
        m.patch_semantic_warning_fixes()
        assert personality_path.read_text(encoding="utf-8") == personality_first
        assert dungeon_path.read_text(encoding="utf-8") == dungeon_first

        cmd4_path = m.VENDOR / "src/cmd4.c"
        cmd4_path.write_text(
            'void f(void){ doc_insert(doc, "<color:y>???</color>"); }\n',
            encoding="utf-8",
        )
        m.patch_cmd4_trigraph_source()
        cmd4_text = cmd4_path.read_text(encoding="utf-8")
        assert '<color:y>???</color>' not in cmd4_text
        assert '<color:y>??" "?</color>' in cmd4_text
        cmd4_first = cmd4_text
        m.patch_cmd4_trigraph_source()
        assert cmd4_path.read_text(encoding="utf-8") == cmd4_first

        wizard_path = m.VENDOR / "src/wizard1.c"
        wizard_path.write_text(
            "static int _cmp_class_name(int c1, int c2)\n"
            "{\n"
            "    return c1 - c2;\n"
            "}\n"
            "void f(vec_ptr vec)\n"
            "{\n"
            "    vec_sort(vec, (vec_cmp_f)_cmp_class_name);\n"
            "    vec_sort(vec, (vec_cmp_f)_cmp_class_name);\n"
            "    vec_sort(vec, (vec_cmp_f)_cmp_class_name);\n"
            "}\n",
            encoding="utf-8",
        )
        m.patch_wizard1_int_vector_comparator()
        wizard_text = wizard_path.read_text(encoding="utf-8")
        assert 'vec_sort(vec, (vec_cmp_f)_cmp_class_name);' not in wizard_text
        assert wizard_text.count('vec_sort(vec, _cmp_class_name_vec);') == 3
        assert 'static int _cmp_class_name_vec(const void *left, const void *right)' in wizard_text
        wizard_first = wizard_text
        m.patch_wizard1_int_vector_comparator()
        assert wizard_path.read_text(encoding="utf-8") == wizard_first

        m.verify_known_warning_fixes()

        m.GENERATED = root / "generated"
        m.write_warning_audit_report()
        audit = (m.GENERATED / "warning-audit.txt").read_text(encoding="utf-8")
        assert "personality.c" in audit and "dungeon.c" in audit

        http = """#include <curl/curl.h>\nvoid a(void){\n    CURL *curl=0;\n    if (curl) {\n        curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30);\n    }\n}\nvoid b(void){\n    CURL *curl=0;\n    if (curl) {\n        curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30);\n    }\n}\n"""
        http_path = m.VENDOR / "src/http.c"
        http_path.write_text(http, encoding="utf-8")
        m.patch_http_curl_ca()
        first = http_path.read_text(encoding="utf-8")
        assert first.count("_rfb_android_apply_ca_bundle(curl);") == 2
        assert first.count("CURLOPT_TIMEOUT, 30L") == 2
        assert "RFB_ANDROID_CURL_FORMAT_MACRO_GUARD" in first
        assert '# pragma push_macro("format")' in first
        assert '# pragma pop_macro("format")' in first
        m.patch_http_curl_ca()
        assert http_path.read_text(encoding="utf-8") == first

        m.DOWNLOAD_CACHE.mkdir(parents=True)
        ca = b"-----BEGIN CERTIFICATE-----\n" + b"A" * 100_100 + b"\n-----END CERTIFICATE-----\n"
        (m.DOWNLOAD_CACHE / "cacert.pem").write_bytes(ca)
        m.install_ca_bundle()
        assert (m.VENDOR / "lib/xtra/curl/cacert.pem").read_bytes() == ca

        m.ASSETS = root / "assets"
        (m.VENDOR / "lib/edit").mkdir(parents=True)
        (m.VENDOR / "lib/edit/example.txt").write_bytes(b"stable bytes\n")
        resolution = {"mode": "test", "tag_name": "v-test"}
        m.prepare_assets("v-test", resolution)
        first_zip = (m.ASSETS / "rfb-data.zip").read_bytes()
        meta = (m.ASSETS / "rfb-build-meta.json").read_text(encoding="utf-8")
        assert '"resource_revision": "rfb-res-v2-' in meta
        m.prepare_assets("v-test", resolution)
        assert (m.ASSETS / "rfb-data.zip").read_bytes() == first_zip

        tag, pinned = m.resolve_source_revision("v1.3.0.6")
        assert tag == "v1.3.0.6" and pinned["mode"] == "pinned"
        m.DOWNLOAD_CACHE.mkdir(parents=True, exist_ok=True)
        (m.DOWNLOAD_CACHE / "rfb-latest-stable-release.json").write_text(
            '{"tag_name":"v1.3.0.6","draft":false,"prerelease":false}\n',
            encoding="utf-8",
        )
        tag, cached = m.resolve_source_revision("latest", offline=True)
        assert tag == "v1.3.0.6" and cached["mode"] == "latest-cache"
        code, name = m.android_version_values("v1.3.0.6")
        assert code == 103000066
        assert name == "1.3.0.6-android-v7.5"
        try:
            m.resolve_source_revision("../../main")
        except SystemExit:
            pass
        else:
            raise AssertionError("unsafe release tag was accepted")

        good = root / "good.tar"
        source = root / "src/pkg"
        source.mkdir(parents=True)
        (source / "a.txt").write_text("ok", encoding="utf-8")
        with tarfile.open(good, "w") as archive:
            archive.add(source, arcname="pkg")
        out = root / "out"
        m.safe_extract_tar(good, out, "pkg")
        assert (out / "a.txt").read_text(encoding="utf-8") == "ok"

        bad = root / "bad.tar"
        with tarfile.open(bad, "w") as archive:
            info = tarfile.TarInfo("../escape")
            data = b"bad"
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
        try:
            m.safe_extract_tar(bad, root / "badout", "pkg")
        except SystemExit:
            pass
        else:
            raise AssertionError("path traversal archive was not rejected")

    print("prepare_source patch/CA/archive safety tests passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
