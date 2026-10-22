#!/usr/bin/env python3
"""Generate checked-in Java trust roots from the canonical reviewed release table.

Run without arguments to update; --check rejects stale output byte-for-byte.
"""
from __future__ import annotations

import argparse
from pathlib import Path

from preview.reviewed_host_artifacts import SOURCE_RELATIVE, load_releases

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = Path('scripts/reviewed-releases/ReviewedHostArtifacts.java.template')
JAVA_DIR = Path('runtime/src/main/java/dev/turboism/mapping/verification')
HOST = JAVA_DIR / 'ReviewedHostArtifacts.java'
TABLE = JAVA_DIR / 'ReviewedReleaseTable.java'
ENV = Path('.env.example')


def render(root: Path) -> dict[Path, str]:
    releases = load_releases(root / SOURCE_RELATIVE)
    symbols = ['CUBISM_' + r['version'].replace('.', '_') for r in releases]
    constants = []
    for r, symbol in zip(releases, symbols):
        constants.append(f'    /** Cubism Editor version string reported for {{@link #{symbol}}}. */\n'
                         f'    public static final String {symbol}_VERSION = "{r["version"]}";')
    for r, symbol in zip(releases, symbols):
        a = r['artifact']
        size = format(a['size'], ',').replace(',', '_')
        constants.append(f'    /** Exact reviewed Cubism Editor {r["version"]} application artifact. */\n'
                         f'    public static final HostArtifactDigest {symbol} =\n'
                         f'            new HostArtifactDigest({size}L, "{a["sha256"]}");')
    replacements = {
        '@@CONSTANTS@@': '\n\n'.join(constants),
        '@@VERSIONS@@': ',\n'.join('            ' + s + '_VERSION' for s in symbols),
        '@@ARTIFACTS@@': ',\n'.join('                ' + s for s in symbols),
        '@@LOOKUP@@': '\n'.join(f'        if ({s}.equals(artifact)) {{\n'
                               f'            return Optional.of({s}_VERSION);\n        }}' for s in symbols),
    }
    host = (root / TEMPLATE).read_text(encoding='utf-8')
    for marker, value in replacements.items():
        host = host.replace(marker, value)
    rows = []
    for r, symbol in zip(releases, symbols):
        c = r['core']
        rows.append(f'            new Release(ReviewedHostArtifacts.{symbol}_VERSION, {r["build"]}, '
                    f'{c["major"]}, {c["minor"]}, {c["patch"]}, {str(r["fullRuntime"]).lower()})')
    table = '''package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable release facts generated from reviewed-releases.json; do not edit. */
public final class ReviewedReleaseTable {

    /** Exact observed build, Core tuple and explicit full-runtime decision for one release. */
    public record Release(
            String version, int build, int coreMajor, int coreMinor, int corePatch, boolean fullRuntime) {}

    private static final List<Release> RELEASES = List.of(
''' + ',\n'.join(rows) + ''');

    /** @return all reviewed releases in table order */
    public static List<Release> all() {
        return RELEASES;
    }

    /** @return all exact reviewed version labels, oldest first */
    public static List<String> versions() {
        return RELEASES.stream().map(Release::version).toList();
    }

    /** @return the exact reviewed release, or empty for an unknown version */
    public static Optional<Release> find(final String version) {
        Objects.requireNonNull(version, "version");
        return RELEASES.stream()
                .filter(release -> release.version().equals(version))
                .findFirst();
    }

    private ReviewedReleaseTable() {}
}
'''
    env = (root / ENV).read_text(encoding='utf-8')
    begin, end = '# reviewed-fixtures:begin', '# reviewed-fixtures:end'
    if env.count(begin) != 1 or env.count(end) != 1 or env.index(begin) >= env.index(end):
        raise ValueError('missing or invalid .env.example generated fixture markers')
    fixture_lines = []
    for r in releases:
        version = r['version']
        code = version.replace('.', '')
        fixture_lines.extend([f'TURBOISM_HOST_VALIDATION_FIXTURE_{code}=/path/to/cubism-{version}-fixture.cmo3',
                              f'# TURBOISM_HOST_VALIDATION_FIXTURE_{code}_SHA256=<64 lowercase hex characters>'])
    env = env[:env.index(begin)] + begin + '\n' + '\n'.join(fixture_lines) + '\n' + env[env.index(end):]
    return {HOST: host, TABLE: table, ENV: env}


def check(root: Path) -> list[str]:
    return [f'{path}: generated output differs from reviewed-releases.json; run python3 scripts/generate_reviewed_releases.py'
            for path, content in render(root).items()
            if not (root / path).is_file() or (root / path).read_bytes() != content.encode('utf-8')]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.check:
        problems = check(args.root)
        for problem in problems:
            print(problem)
        return bool(problems)
    for path, content in render(args.root).items():
        (args.root / path).parent.mkdir(parents=True, exist_ok=True)
        (args.root / path).write_text(content, encoding='utf-8')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
