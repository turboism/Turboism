#!/usr/bin/env python3
"""Read-only exact-JAR vector-copy audit; no shortcut admission or speed estimate."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def block(text, signature):
    lines = text.splitlines()
    matches = [i for i, line in enumerate(lines) if line.strip() == signature]
    if len(matches) != 1:
        raise ValueError('missing/duplicate signature ' + signature)
    start = matches[0]
    end = next((i for i in range(start + 1, len(lines))
                if lines[i].startswith('  ') and not lines[i].startswith('    ') and lines[i].strip()), len(lines))
    return '\n'.join(lines[start:end]) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for profile in ('5203', '5302', '5303'):
        parser.add_argument('--jar-' + profile, type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    authority = Path('runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java')
    profiles = {}
    vector = 'com.live2d.graphics3d.type.GVector2'
    signatures = {'minus': 'public final ' + vector + ' minus(' + vector + ');',
                  'copyConstructor': 'public ' + vector + '(' + vector + ');',
                  'scalarConstructor': 'public ' + vector + '(float, float);'}
    for profile in ('5203', '5302', '5303'):
        jar = getattr(args, 'jar_' + profile).resolve()
        name = {'5203': 'CUBISM_5_2_03', '5302': 'CUBISM_5_3_02', '5303': 'CUBISM_5_3_03'}[profile]
        match = re.search(r'\b' + name + r'\s*=\s*new HostArtifactDigest\(([\d_]+)L,\s*"([a-f0-9]{64})"\)', authority.read_text())
        if not match or jar.stat().st_size != int(match[1].replace('_', '')) or sha(jar) != match[2]:
            raise ValueError('official JAR mismatch ' + profile)
        cp = str(jar) + ':' + str(jar.parent / 'kotlin-stdlib-1.7.21.jar')
        run = subprocess.run(['javap', '-c', '-p', '-classpath', cp, vector],
                             capture_output=True, text=True, timeout=30)
        if run.returncode:
            raise ValueError(run.stderr)
        listing = out / (profile + '-vector.javap.txt')
        listing.write_text(run.stdout)
        bodies = {name: block(run.stdout, signature) for name, signature in signatures.items()}
        # Diagnostic normalization only; complete input SHA remains authoritative.
        normalized = {name: re.sub(r'#\d+', '#CP', body) for name, body in bodies.items()}
        with zipfile.ZipFile(jar) as archive:
            vector_sha = hashlib.sha256(archive.read(vector.replace('.', '/') + '.class')).hexdigest()
        profiles[profile] = {'jar': str(jar), 'jarSha256': sha(jar), 'rawVectorSha256': vector_sha,
                             'listingSha256': sha(listing), 'bodies': bodies,
                             'normalizedBodySha256': {name: hashlib.sha256(body.encode()).hexdigest()
                                                      for name, body in normalized.items()}}
    comparable = [v['normalizedBodySha256'] for v in profiles.values()]
    report = {'scope': 'READ_ONLY_DIAGNOSTIC_SHAPE_NO_PRODUCTION_ADMISSION', 'profiles': profiles,
              'allThreeReviewedBodiesMatch': all(v == comparable[0] for v in comparable),
              'pins': {str(Path(__file__).resolve()): sha(Path(__file__)), str(authority.resolve()): sha(authority)},
              'limitations': ['Normalized listings are research evidence, not runtime admission fingerprints.',
                              'No native fixtures, full transformed method or Editor executed.',
                              'Removing copies needs shared exact-definition lease, complete failure/order/geometry controls and independent native CPU/RSS comparison.']}
    (out / 'review.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'allThreeReviewedBodiesMatch': report['allThreeReviewedBodiesMatch'],
                      'vectorShas': {p: v['rawVectorSha256'] for p, v in profiles.items()}}, indent=2))


if __name__ == '__main__':
    main()
