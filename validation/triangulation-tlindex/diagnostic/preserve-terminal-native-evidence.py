"""Copy one authoritative terminal task's raw evidence, including failures; never grants acceptance."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts/preview'))
from host_validation_evidence import layout, checked_path


def sha(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--job-id', required=True); parser.add_argument('--prepared-id', required=True)
    parser.add_argument('--output', type=Path, required=True); args = parser.parse_args()
    shared = Path.home() / '.local/state/turboism/host-validation'
    with sqlite3.connect('file:' + str(shared / 'queue.sqlite3') + '?mode=ro', uri=True) as db:
        db.row_factory = sqlite3.Row
        row = db.execute('select * from jobs where job_id=?', (args.job_id,)).fetchone()
        if row is None: raise ValueError('missing authoritative job')
        job = dict(row)
    if job['state'] not in ('succeeded', 'failed', 'timed_out', 'cancelled') or not job['run_id']:
        raise ValueError('terminal task with run identity required')
    if job['prepared_id'] != args.prepared_id: raise ValueError('prepared identity mismatch')
    prepared_root = shared / 'prepared' / job['prepared_id']
    prepared = json.loads((prepared_root / 'prepared.json').read_text())
    outcome_path = shared / 'jobs' / args.job_id / 'outcome.json'
    outcome = json.loads(outcome_path.read_text())
    if outcome['jobId'] != args.job_id or outcome['runId'] != job['run_id'] or outcome['preparedDigest'] != job['prepared_id']:
        raise ValueError('outcome identity mismatch')
    task = layout(prepared, prepared_root, job)['task']
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    sources = [(outcome_path, Path('authority/outcome.json')),
               (prepared_root / 'prepared.json', Path('authority/prepared.json'))]
    for relative in ('evidence', 'turboism-home/state', 'turboism-home/logs'):
        directory = checked_path(task / relative)
        if directory.exists():
            for p in sorted(directory.rglob('*')):
                checked_path(p)
                if p.is_file(): sources.append((p, p.relative_to(task)))
    for relative in ('turboism-home/atlas-profiling.jfr', 'turboism-home/config.json'):
        p = checked_path(task / relative)
        if p.is_file(): sources.append((p, p.relative_to(task)))
    copied = []
    for source, relative in sources:
        before = sha(source); target = out / relative; target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists(): raise ValueError('duplicate evidence destination')
        shutil.copyfile(source, target)
        if sha(target) != before or sha(source) != before: raise ValueError('evidence changed during copy')
        copied.append({'path': str(relative), 'sha256': before})
    report = {'status': 'PRESERVED_TERMINAL_EVIDENCE_ONLY', 'jobId': args.job_id, 'runId': job['run_id'],
              'preparedId': args.prepared_id, 'terminalState': job['state'], 'copiedFiles': copied,
              'acceptance': 'NOT_GRANTED', 'validationStatus': outcome.get('validationStatus'),
              'toolSha256': sha(Path(__file__))}
    (out / 'preservation.json').write_text(json.dumps(report, indent=2) + '\n')
    print(report['status'], job['state'], 'files=' + str(len(copied)))


if __name__ == '__main__': main()
