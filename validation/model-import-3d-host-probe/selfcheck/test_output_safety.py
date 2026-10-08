#!/usr/bin/env python3
"""T022 wrapper regression: disposable copies only; retain every audit artifact.

Compiler/runtime stubs test admission; --real-java additionally runs the pure
Java fixture and checks sealing. No Cubism. All audit artifacts are retained.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

PROBE = Path(__file__).resolve().parents[1]
ROOT = PROBE.parents[1] / "build/model-import-3d-offline"


def inventory(root):
    result = {}
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in sorted(dirs + files):
            p = Path(directory) / name
            rel = str(p.relative_to(root))
            if p.is_symlink():
                result[rel] = "link:" + os.readlink(p)
            elif p.is_file():
                result[rel] = hashlib.sha256(p.read_bytes()).hexdigest()
            else:
                result[rel] = "directory"
    return result


def lifecycle(audit, env, real):
    repo = audit / "lifecycle" / "repo with 'quotes'"
    target = repo / "validation/model-import-3d-host-probe"
    shutil.copytree(PROBE, target)
    root = repo / "build/model-import-3d-offline"
    root.mkdir(parents=True)
    records = []

    def run(label, argv, expected, selected_env, preserve=None, terminal=None):
        before = inventory(preserve) if preserve else None
        cp = subprocess.run(argv, cwd=repo, env=selected_env, capture_output=True, timeout=120)
        prefix = audit / ("lifecycle-" + label)
        prefix.with_suffix(".stdout.txt").write_bytes(cp.stdout)
        prefix.with_suffix(".stderr.txt").write_bytes(cp.stderr)
        unchanged = before == inventory(preserve) if preserve else True
        terminal_ok = terminal is None or (terminal.is_file() and
                terminal.read_text() == f"offline-process-exit={expected}\n")
        record = dict(id=label, argv=argv, exitCode=cp.returncode, expectedExit=expected,
                      evidenceUnchanged=unchanged, terminalCorrect=terminal_ok,
                      passed=cp.returncode == expected and unchanged and terminal_ok)
        records.append(record)
        print(f'{label}: {"PASS" if record["passed"] else "FAIL"} exit={cp.returncode}')
        return record["passed"]

    failed = root / "runs/failed-runtime"
    run("failed-runtime-sealed", ["bash", str(target / "selfcheck.sh"), "--out", str(failed)],
        1, env, terminal=failed / ".terminal")
    run("failed-run-refused", ["bash", str(target / "selfcheck.sh"), "--out", str(failed)],
        3, env, preserve=root)
    failed_build = root / "runs/failed-build"
    run("failed-build-sealed", ["bash", str(target / "build.sh"), "--out", str(failed_build / "classes")],
        8, dict(env, JAVAC_EXIT="8"), terminal=failed_build / ".terminal")
    if not real:
        return records
    current = root / "runs/real-selfcheck"
    if not run("real-selfcheck", ["bash", str(target / "selfcheck.sh"), "--out", str(current)],
               0, None, terminal=current / ".terminal"):
        return records
    java = ["java", "-Djava.awt.headless=true", "-cp", str(current / "classes"),
            "dev.turboism.validation.modelimport3d.Main"]
    run("sealed-java-refused", java + ["--out", str(current / "late-output")], 3, None, preserve=root)
    run("sealed-wrapper-refused", ["bash", str(target / "selfcheck.sh"), "--out", str(current)],
        3, None, preserve=root)
    standalone = root / "runs/standalone"
    if not run("standalone-build", ["bash", str(target / "build.sh"), "--out", str(standalone / "classes")], 0, None):
        return records
    java = ["java", "-Djava.awt.headless=true", "-cp", str(standalone / "classes"),
            "dev.turboism.validation.modelimport3d.Main"]
    run("standalone-java", java + ["--out", str(standalone / "first")], 0, None)
    (standalone / "empty").mkdir()
    for label, extra in (
            ("java-existing", ["--out", str(standalone / "first")]),
            ("java-empty", ["--out", str(standalone / "empty")]),
            ("java-foreign-run", ["--out", str(current / "new-child")]),
            ("java-classes", ["--out", str(standalone / "classes")]),
            ("java-root-override", ["--out", str(standalone / "bad-root"), "--root", str(root)]),
            ("java-traversal", ["--out", str(standalone / "empty/../traversal")])):
        run(label, java + extra, 3, None, preserve=root)
    for label, link_target in (("java-symlink", standalone / "empty"),
                               ("java-dangling", standalone / "absent")):
        link = standalone / label
        link.symlink_to(link_target)
        run(label, java + ["--out", str(link)], 3, None, preserve=root)
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--real-java", action="store_true")
    args = parser.parse_args()
    for p in (ROOT, *ROOT.parents):
        if p.is_symlink():
            raise SystemExit("refused: symlink in audit root")
    ROOT.mkdir(parents=True, exist_ok=True)
    audit = Path(tempfile.mkdtemp(prefix="main-t022-audit-", dir=ROOT))
    tools = audit / "tools"
    tools.mkdir()
    (tools / "javac").write_text('''#!/bin/sh
if [ "$1" = "-version" ]; then echo "javac 17.0.20"; exit 0; fi
[ "${JAVAC_EXIT:-0}" -eq 0 ] || exit "$JAVAC_EXIT"
while [ "$#" -gt 0 ]; do
  if [ "$1" = "-d" ]; then printf 'admission stub, not bytecode\\n' > "$2/Stub.class"; exit 0; fi
  shift
done
exit 3
''')
    (tools / "java").write_text('#!/bin/sh\necho "intentional runtime stop (no Java executed)"\nexit 9\n')
    for p in tools.iterdir():
        p.chmod(0o700)
    env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ["PATH"])
    records = []
    cases = [
        ("historical-selfcheck", "selfcheck.sh", "selfcheck", False),
        ("historical-classes", "build.sh", "classes", False),
        ("existing-run", "selfcheck.sh", "runs/prior", False),
        ("empty-existing-run", "selfcheck.sh", "runs/empty", False),
        ("existing-run-build", "build.sh", "runs/prior/classes", False),
        ("dry-run-fresh", "selfcheck.sh", "runs/fresh", True),
        ("dry-build-fresh", "build.sh", "runs/fresh/classes", True),
        ("raw-traversal", "selfcheck.sh", "runs/link/../fresh", True),
        ("dangling-link", "selfcheck.sh", "runs/dangling/child", True),
        ("frozen-audit-child", "selfcheck.sh", "main-review-r1/new", False),
    ]
    for label, script, relative, dry in cases:
        repo = audit / label / "repo"
        target = repo / "validation/model-import-3d-host-probe"
        shutil.copytree(PROBE, target)
        outroot = repo / "build/model-import-3d-offline"
        for d in ("selfcheck/run-a", "classes", "runs/prior/classes", "runs/empty",
                  "main-review-r1", "runs/link-target"):
            (outroot / d).mkdir(parents=True, exist_ok=True)
        for f in ("selfcheck/run-a/sentinel", "selfcheck/selfcheck-log.txt",
                  "classes/Keep.class", "runs/prior/classes/Keep.class",
                  "main-review-r1/sentinel"):
            (outroot / f).write_bytes(b"historical evidence: do not change\n")
        (outroot / "runs/link").symlink_to(outroot / "runs/link-target")
        (outroot / "runs/dangling").symlink_to(outroot / "absent")
        before = inventory(outroot)
        argv = ["bash", str(target / script), "--out", str(outroot / relative)]
        if dry:
            argv.append("--dry-run")
        completed = subprocess.run(argv, cwd=repo, env=env, capture_output=True, timeout=30)
        for name, data in (("stdout", completed.stdout), ("stderr", completed.stderr)):
            (audit / label / (name + ".txt")).write_bytes(data)
        after = inventory(outroot)
        expected = 0 if label.startswith("dry-") else 3
        unchanged = before == after
        record = dict(id=label, argv=argv, exitCode=completed.returncode,
                      expectedExit=expected, before=before, after=after,
                      evidenceUnchanged=unchanged,
                      passed=completed.returncode == expected and unchanged)
        records.append(record)
        print(f'{label}: {"PASS" if record["passed"] else "FAIL"} '
              f'exit={completed.returncode} unchanged={unchanged}')
    records.extend(lifecycle(audit, env, args.real_java))
    report = dict(hostExecuted=False, realJavaRequested=args.real_java, sourceFiles=inventory(PROBE),
                  cases=records)
    (audit / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("audit:", audit)
    return 0 if all(r["passed"] for r in records) else 1


if __name__ == "__main__":
    raise SystemExit(main())
