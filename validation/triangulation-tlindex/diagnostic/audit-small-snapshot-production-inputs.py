"""Read-only T075 production artifact audit; never prepares or launches a host."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[3]
REPORT = ROOT / 'validation/triangulation-tlindex/small-query-snapshot-production-offline-review-20261003.json'
FAMILY = 'dev/turboism/adapter/cubism/mesh/TriangulationEdgeIndex'
FORBIDDEN = ('TriangulationSingleAgentValidationHook',
             'TriangulationSingleAgentShadowPreHook', 'MeshProducerRecorder', 'MeshProducerWeave')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def audit(baseline, candidate):
    expected = json.loads(REPORT.read_text())['artifacts']['outputs']['production-candidate-r1']
    require(sha(baseline) == expected['baseSha256'], 'baseline is not the frozen T057 production jar')
    require(sha(candidate) == expected['sha256'], 'candidate is not the frozen T075 production jar')
    with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(candidate) as new:
        for archive in (old, new):
            names = archive.namelist()
            require(len(names) == len(set(names)), 'duplicate jar entry')
            require(not any(token in name for name in names for token in FORBIDDEN),
                    'validation observer class in production jar')
            hooks = archive.read('META-INF/turboism/hooks')
            require(not any(token.encode() in hooks for token in FORBIDDEN),
                    'validation observer hook in production jar')
        old_names, new_names = set(old.namelist()), set(new.namelist())
        added, removed = sorted(new_names - old_names), sorted(old_names - new_names)
        changed = sorted(name for name in old_names & new_names if old.read(name) != new.read(name))
        require(added == expected['added'] and removed == expected['removed']
                and changed == expected['changed'], 'unexpected production jar delta')
        require(added == [FAMILY + '$QuerySnapshot.class'] and not removed,
                'unexpected snapshot family addition/removal')
        family = {FAMILY + suffix for suffix in ('.class', '$EdgeKey.class', '$EdgeLookup.class',
                  '$SetKey.class', '$SetLookup.class', '$St.class')}
        require(set(changed) == family, 'changed production entry outside exact index family')
        require(old.read('META-INF/turboism/hooks') == new.read('META-INF/turboism/hooks'),
                'production hook registry changed')
        return {
            'status': 'PASS_FROZEN_PRODUCTION_ARCHIVES_ONLY',
            'baselineSha256': expected['baseSha256'], 'candidateSha256': expected['sha256'],
            'changed': changed, 'added': added, 'removed': removed,
            'unchangedEntryCount': len(old_names & new_names) - len(changed),
            'productionHookRegistryUnchanged': True, 'validationObserverClassesAbsent': True,
            'inputReportSha256': sha(REPORT),
            'hostExecuted': False, 'productionAcceptance': 'NOT_GRANTED_ARCHIVE_AUDIT',
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, default=ROOT / 'build/t057-angle-integration-r1/production-scoped-r3/turboism-agent.jar')
    parser.add_argument('--candidate', type=Path, default=ROOT / 'build/t075-small-query-snapshot-integration-r1/production-candidate-r1/turboism-agent.jar')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.baseline, args.candidate)
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(result['status'])


if __name__ == '__main__':
    main()
