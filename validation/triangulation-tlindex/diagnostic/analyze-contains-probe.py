"""Validate a complete diagnostic aggregate; no automatic performance verdict."""
import argparse
import hashlib
import json
import re
from pathlib import Path

MARKER = '[TL-CONTAINS-DIAGNOSTIC-v1] '
REASONS = ('identityHit', 'dead', 'dirty', 'setSize', 'keySize', 'identityMiss',
           'bookkeepingFailure')


def counts_from_log(lines):
    records = []
    for line in lines:
        if MARKER in line:
            payload = line.split(MARKER, 1)[1].lstrip()
            record, end = json.JSONDecoder().raw_decode(payload)
            suffix = payload[end:].strip()
            # Cubism wraps System.err in its own logger, appending source location.
            if suffix and not re.fullmatch(
                    r'\] at dev\.turboism\.adapter\.cubism\.mesh\.ContainsDiagnostic '
                    r'\(ContainsDiagnostic\.(?:java|kt):\d+\) lambda\$static\$0\(\)', suffix):
                raise ValueError('unexpected text after diagnostic aggregate')
            records.append(record)
    if len(records) != 1:
        raise ValueError('exactly one shutdown aggregate required; missing/duplicate is invalid')
    counts = records[0]
    if set(counts) != set(REASONS):
        raise ValueError('counter schema mismatch')
    for reason, row in counts.items():
        if (not isinstance(row, list) or len(row) != 3 or
                any(type(n) is not int or n < 0 for n in row)):
            raise ValueError('invalid counter row: ' + reason)
        if row[1] + row[2] != row[0]:
            raise ValueError('exception or incomplete call in aggregate: ' + reason)
    if counts['identityHit'][0] != counts['identityHit'][1]:
        raise ValueError('identity shortcut returned false')
    total = sum(row[0] for row in counts.values())
    if not total or counts['bookkeepingFailure'][0]:
        raise ValueError('no execution or bookkeeping failure; inspect raw evidence')
    return {
        'calls': total,
        'identityHitCalls': counts['identityHit'][0],
        'identityHitFraction': counts['identityHit'][0] / total,
        'fallbackCalls': total - counts['identityHit'][0],
        'counts': counts,
        'scope': 'Whole JVM aggregate; not per-operation timing or a speedup verdict',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('console', type=Path)
    parser.add_argument('outcome', type=Path)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    outcome = json.loads(args.outcome.read_text())
    for key, expected in {'validationStatus': 'PASS', 'cleanup': 'safe',
                          'normalExit': True, 'identityVerified': True,
                          'fixtureUnchanged': True}.items():
        if outcome.get(key) != expected:
            raise ValueError('host gate failed: ' + key)
    manifest = json.loads(args.manifest.read_text())
    if manifest['kind'] != 'DIAGNOSTIC_ONLY_NOT_PERFORMANCE_ACCEPTANCE':
        raise ValueError('not a diagnostic manifest')
    artifacts = outcome['details']['postContainmentChecks']['stagedArtifacts']
    matching = [a for a in artifacts if Path(a['staged']).name == 'turboism-agent.jar']
    if len(matching) != 1 or any(matching[0][key] != manifest['diagnosticJarSha256']
                                 for key in ('sourceSha256', 'stagedSha256')):
        raise ValueError('diagnostic agent identity mismatch')
    evidence = Path(outcome['details']['evidenceDir']).resolve()
    task = evidence.parent
    if not args.console.resolve().is_relative_to(task):
        raise ValueError('console must belong to this outcome task')
    with args.console.open(errors='strict') as stream:
        result = counts_from_log(stream)
    result.update(performanceAcceptance='NOT_DECIDED',
                  runId=outcome['containment']['runId'],
                  jobId=outcome['containment']['jobId'], inputs={})
    for path in (args.console, args.outcome, args.manifest):
        with path.open('rb') as stream:
            result['inputs'][str(path.resolve())] = hashlib.file_digest(stream, 'sha256').hexdigest()
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')


if __name__ == '__main__':
    main()
