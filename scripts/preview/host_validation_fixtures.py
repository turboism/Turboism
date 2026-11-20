#!/usr/bin/env python3
"""Single source for host-validation fixture SHA-256 digests.

Corpus fixtures resolve from testing/assets/corpus.json; private or derived host
fixtures live in PRIVATE_FIXTURE_SHA256 so wrappers and static gates share one record.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

CORPUS_RELATIVE = "testing/assets/corpus.json"
CORPUS_NAME = Path(CORPUS_RELATIVE).name

PRIVATE_FIXTURE_SHA256: dict[str, str] = {
    "heavy.cmo3": "029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c",
    "pprcut.cmo3": "2a758ba764693a956c327961820a55a03dc35cc1a8ff6567346c17bc2192587a",
    "part-opacity-fixture-52-final.cmo3": "331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028",
    "mixed-mode-53.cmo3": "57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4",
    "pbt-5302.cmo3": "8b1718d2976eabffc8d85ea10343003aadea784230094e5397a41acbc17a20b8",
    "clipmask-mcp.cmo3": "2980dc8c695b13eeb54e37c02832edf80607979f2cc721ea9ca7b671f8bbac10",
    "clipmask.psd": "27c2641e45d9ca55478550b99d1bf69262383af38b0eed39cc172fb96b3b053e",
    "external-psd-first-document": "59d5aa5775e86a2917b05a7322f05f0cdcfead994000fede91ab023cab19f308",
    "external-psd-second-document": "7ed2d0296791cca5f3ad8a7ccd955999a35dc755d32a62268cdfd0007d95b41c",
    "native-seven-layer.psd": "8b760eb0b6ac5839271210aa0efc681f6a02a6537c1ab97d40a3a56879d8f02c",
    "native-seven-layer-target-rgb": "12eca5a1c8d8b9096384c974d52e8f0310c4ad9ac4ea87a072684096cf2b808d",
    "f1-2048-20layers.psd": "2473baeae7fc8942d8d2e0df72cf4567f57f8e3a9a37a924fc4d01034ababfeb",
}

ENV_FIXTURE_DEFAULTS: dict[str, str] = {
    "TURBOISM_HOST_VALIDATION_FIXTURE_5203_SHA256": "part-opacity-fixture-52-final.cmo3",
    "TURBOISM_HOST_VALIDATION_FIXTURE_5302_SHA256": "mixed-mode-53.cmo3",
    "TURBOISM_HOST_VALIDATION_FIXTURE_5303_SHA256": "mixed-mode-53.cmo3",
    "TURBOISM_HOST_VALIDATION_FIXTURE_PBT_5302_SHA256": "pbt-5302.cmo3",
    "TURBOISM_HOST_VALIDATION_FIXTURE_CLIPMASK_SHA256": "clipmask-mcp.cmo3",
    "TURBOISM_HOST_VALIDATION_FIXTURE_MCP_SHA256": "clipmask-mcp.cmo3",
    "TURBOISM_HOST_VALIDATION_FIXTURE_PSD_SHA256": "clipmask.psd",
}


def default_corpus_source() -> Path:
    """Resolve testing/assets/corpus.json from the repo tree or a staged sibling copy."""
    here = Path(__file__).resolve()
    tree = here.parents[2] / CORPUS_RELATIVE if len(here.parents) > 2 else None
    flat = here.with_name(CORPUS_NAME)
    if tree is not None and tree.is_file():
        return tree
    if flat.is_file():
        return flat
    return tree if tree is not None else flat


def load_corpus_sha256(source: Path | None = None) -> dict[str, str]:
    """Return corpus file SHA-256s keyed by relative path and unambiguous basename."""
    path = source or default_corpus_source()
    if not path.is_file():
        return {}
    document = json.loads(path.read_text(encoding="utf-8"))
    by_rel: dict[str, str] = {}
    by_name: dict[str, str] = {}
    ambiguous: set[str] = set()
    for dataset in document.get("datasets", []):
        for entry in dataset.get("files", []):
            rel = entry["path"]
            digest = entry["sha256"]
            by_rel[rel] = digest
            name = Path(rel).name
            if name in by_name and by_name[name] != digest:
                ambiguous.add(name)
            else:
                by_name[name] = digest
    for name in ambiguous:
        by_name.pop(name, None)
    return {**by_name, **by_rel}


def fixture_sha256(key: str, source: Path | None = None) -> str:
    """Return the SHA-256 digest for a private or corpus fixture key, failing closed if absent."""
    if key in PRIVATE_FIXTURE_SHA256:
        return PRIVATE_FIXTURE_SHA256[key]
    corpus = load_corpus_sha256(source)
    if key in corpus:
        return corpus[key]
    raise KeyError(f"unknown fixture key: {key}")


def main(argv: list[str]) -> int:
    if len(argv) == 3 and argv[1] == "sha256":
        try:
            print(fixture_sha256(argv[2]))
        except KeyError as exc:
            print(str(exc), file=sys.stderr)
            return 2
        return 0
    if len(argv) == 2 and argv[1] == "env-defaults":
        for var, key in ENV_FIXTURE_DEFAULTS.items():
            print(f"{var}={fixture_sha256(key)}")
        return 0
    print("Usage: host_validation_fixtures.py sha256 KEY | env-defaults", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
