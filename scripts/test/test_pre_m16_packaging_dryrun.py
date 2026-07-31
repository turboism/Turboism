#!/usr/bin/env python3
from __future__ import annotations

import csv
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO / "scripts/release/pre_m16_packaging_dryrun.py"
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("pre_m16", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
assert spec.loader
sys.modules[spec.name] = module
spec.loader.exec_module(module)


def expect_error(label, callback, fragment):
    try:
        callback()
    except module.DryRunError as exc:
        assert fragment in str(exc), (label, exc)
    else:
        raise AssertionError(f"{label}: expected DryRunError")


def make_jar(path: Path, entries=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in entries or [("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")]:
            archive.writestr(name, data)


def fixture_manifest(root: Path, worktree_id="phase-five-test"):
    artifacts = []
    scoped = root / "build/worktree" / worktree_id
    for project, directory in module.EXPECTED_LIB_DIRS.items():
        jar = scoped / directory / "libs" / f"{directory}-0.1.0-{worktree_id}.jar"
        make_jar(jar)
        artifacts.append({"path": jar.relative_to(root).as_posix(), "sha256": module.sha256(jar), "size": jar.stat().st_size})
    plans = module.plan_documents(root, scoped, worktree_id, artifacts)
    return {
        "format": module.FORMAT, "schemaVersion": 1, "worktreeId": worktree_id,
        "generatedAt": "2026-07-11T00:00:00Z", "artifacts": artifacts,
        "forbiddenEntries": list(module.CANONICAL_FORBIDDEN_ENTRIES), **plans,
    }


def refresh_trace_digest(trace: dict) -> None:
    digest_document = dict(trace)
    digest_document.pop("traceContentSha256", None)
    trace["traceContentSha256"] = module.bytes_digest(module.canonical_json_bytes(digest_document))


def expected_safe_mode_command_map(repo: Path) -> dict[str, list[str]]:
    gradle = str(repo / "gradlew")
    selector = lambda test: [gradle, ":runtime:test", "--tests", test, "--no-daemon"]
    return {
        "unsupported-version": selector("dev.turboism.adapter.ui.UiSurfaceAdapterContractTest.unsupportedVersionAndHostFailuresFailClosed"),
        "missing-stale-evidence": [str(repo / "scripts/test/test_phase4_build_gates.sh")],
        "adapter-unavailable": selector("dev.turboism.adapter.host.VerifiedHostAdapterConnectorTest.rejectsClipMaskEvidenceFromAnotherArtifact"),
        "hash-mismatch": selector("dev.turboism.mapping.verification.PinnedVerifiedResolverWorkflowTest.rejectsArtifactDigestAndSizeMismatch"),
        "selector-mismatch": selector("dev.turboism.mapping.verification.PinnedVerifiedResolverWorkflowTest.rejectsCapabilityAndAliasMismatch"),
        "partial-slice-failure": selector("dev.turboism.adapter.host.HostSessionPluginContextIntegrationTest.failedDualReplacementMakesSameContextSafeAndKeepsFailureSanitized"),
        "explicit-safe-mode": selector("dev.turboism.adapter.cubism.M14SimulatedReadonlyAdaptersContractTest.projectWorkspaceSafeModeIsUnavailable"),
    }


def test_cleanup_and_source_provenance(tmp: Path):
    repo = tmp / "repo"; repo.mkdir()
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.email", "phase5@example.invalid"], check=True)
    subprocess.run(["git", "-C", str(repo), "config", "user.name", "Phase 5"], check=True)
    (repo / "runtime").mkdir(); (repo / "runtime/a.java").write_text("class A {}")
    subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
    subprocess.run(["git", "-C", str(repo), "commit", "-qm", "base"], check=True)
    assert module.source_provenance(repo)["trackedSourceClean"]
    (repo / "runtime/untracked.java").write_text("class U {}")
    assert module.source_provenance(repo)["untrackedSourcePaths"] == ["runtime/untracked.java"]
    (repo / "runtime/untracked.java").unlink()
    keep = repo / "build/worktree/keep/file"; keep.parent.mkdir(parents=True); keep.write_text("keep")
    remove = repo / "build/worktree/phase-five-test/file"; remove.parent.mkdir(parents=True); remove.write_text("remove")
    module.clean_scoped_output(repo, "phase-five-test")
    assert keep.exists() and not remove.exists()
    expect_error("unsafe id", lambda: module.clean_scoped_output(repo, "../oops"), "unsafe")
    outside = tmp / "outside"; outside.mkdir()
    shutil.rmtree(repo / "build")
    (repo / "build").symlink_to(outside, target_is_directory=True)
    expect_error("build symlink", lambda: module.clean_scoped_output(repo, "phase-five-test"), "symlink")


def test_exact_set_hash_size_and_plan_binding(tmp: Path):
    manifest = fixture_manifest(tmp)
    scoped = tmp / "build/worktree/phase-five-test"
    inventory = module.artifact_inventory(tmp, scoped, "phase-five-test")
    assert len(inventory) == 14
    assert set(module.EXPECTED_PROJECTS) == {
        ":plugins", ":plugins:clip-mask", ":plugins:demo", ":plugins:log-filter",
        ":plugins:core", ":plugins:mesh", ":plugins:parameter", ":plugins:perf-opt",
        ":plugins:render-opt", ":plugins:ui-theme", ":runtime", ":sdk", ":testframework", ":tests",
    }
    assert module.manifest_errors(manifest, tmp) == []
    assert module.validate_plan_binding(tmp, manifest) == []
    artifact = tmp / manifest["artifacts"][0]["path"]
    artifact.write_bytes(b"changed")
    assert any("hash/size mismatch" in error for error in module.manifest_errors(manifest, tmp))
    make_jar(scoped / "unexpected/deeper/libs/unexpected-phase-five-test.JAR")
    expect_error("extra recursive jar", lambda: module.artifact_inventory(tmp, scoped, "phase-five-test"), "not exact")


def test_manifest_strict_v1_and_scoping(tmp: Path):
    manifest = fixture_manifest(tmp)
    manifest["sourceCommit"] = "0" * 40
    assert "strict v1" in module.manifest_errors(manifest, tmp)[0]
    manifest.pop("sourceCommit")
    manifest["artifacts"][0]["path"] = "runtime/build/libs/runtime.jar"
    assert any("unscoped artifact" in error for error in module.manifest_errors(manifest, tmp))
    tasks = module.build_commands(tmp, "phase-five-test")[0]
    assert "build" not in tasks and ":plugins:jar" in tasks and ":tests:jar" in tasks
    assert len([item for item in tasks if item.endswith(":jar")]) == 14


def test_safe_mode_expected_command_map(tmp: Path):
    expected = expected_safe_mode_command_map(tmp)
    cases = module.safe_mode_cases(tmp)
    assert [case["caseId"] for case in cases] == list(expected)
    assert {case["caseId"]: case["command"] for case in cases} == expected
    assert module.safe_mode_commands(tmp) == list(expected.values())


def test_zip_scan_distinguishes_classes_and_nested_jars(tmp: Path):
    clean = tmp / "clean.jar"; make_jar(clean, [("dev/turboism/Normal.class", b"bytecode")])
    assert module.inspect_zip(clean) == []
    nested_bytes = io.BytesIO()
    with zipfile.ZipFile(nested_bytes, "w") as nested:
        nested.writestr("COM/LIVE2D/Secret.CLASS", b"bytecode")
    suspicious = tmp / "suspicious.jar"
    make_jar(suspicious, [
        ("agents.MD", "safe"),
        ("notes.TXT", "mentions COM/LIVE2D/private"),
        ("native.DLL", b"binary"),
        ("lib/NESTED.JAR", nested_bytes.getvalue()),
        ("../escape.txt", "escape"),
    ])
    kinds = {finding["kind"] for finding in module.inspect_zip(suspicious)}
    assert kinds == {"structural", "name", "text-limited"}
    assert any(item["kind"] == "name" and "native" in item["reason"] for item in module.inspect_zip(suspicious))
    bomb = tmp / "bomb.jar"; make_jar(bomb, [("huge.txt", b"0" * (module.MAX_ENTRY_BYTES + 1))])
    assert any("limit" in item["reason"] for item in module.inspect_zip(bomb))


def test_unscoped_latest_root_and_all_jar_symlinks(tmp: Path):
    scoped = tmp / "build/worktree/phase-five-test"; scoped.mkdir(parents=True)
    root = tmp / "build/libs/root.jar"; make_jar(root)
    latest = scoped / "runtime/libs/runtime-latest.jar"; make_jar(latest)
    other = tmp / "build/worktree/other/runtime/libs/runtime-other.jar"; make_jar(other)
    plugin_drop = tmp / "plugins/drop.JAR"; make_jar(plugin_drop)
    target = tmp / "target.bin"; target.write_bytes(b"jar")
    links = [
        scoped / "runtime/libs/scoped-link.jar",
        tmp / "build/libs/root-link.jar",
        tmp / "build/worktree/other/other-link.jar",
        tmp / "plugins/drop-link.jar",
        tmp / "misc/deep/any-link.JAR",
    ]
    for link in links:
        link.parent.mkdir(parents=True, exist_ok=True); link.symlink_to(target)
    broken = tmp / "misc/deep/broken.jar"; broken.symlink_to(tmp / "missing.jar")
    findings = module.find_forbidden_outputs(tmp, scoped, "phase-five-test")
    expected = {path.relative_to(tmp).as_posix() for path in [root, latest, other, plugin_drop, *links, broken]}
    assert set(findings) == expected
    expect_error("scoped symlink exact set", lambda: module.artifact_inventory(tmp, scoped, "phase-five-test"), "symlink")


def test_sandbox_twice_and_pure_transitions(tmp: Path):
    manifest = fixture_manifest(tmp)
    simulation = module.simulate_install_rollback(tmp, manifest)
    assert simulation == [
        {"cycle": 1, "action": "install", "count": 14, "success": True},
        {"cycle": 1, "action": "rollback", "absent": True, "idempotent": True, "success": True},
        {"cycle": 2, "action": "install", "count": 14, "success": True},
        {"cycle": 2, "action": "rollback", "absent": True, "idempotent": True, "success": True},
    ]
    assert not (tmp / "build/worktree/phase-five-test/dryrun/sandbox").exists()
    rows = [
        {"workId": "automation.phase5.packaging-dryrun", "workStatus": "NOT_STARTED", "evidenceLevel": "NONE", "readinessCeiling": "NONE", "blockers": "", "evidenceRefs": ""},
        {"workId": "tranche.automation.overall", "workStatus": "PENDING", "evidenceLevel": "VERIFIED_STATIC_SYNTHETIC", "readinessCeiling": "BUILD_GATED", "blockers": "", "evidenceRefs": "", "nextSlice": "automation.phase5.packaging-dryrun"},
    ]
    transitioned = module.phase5_ledger_transition(rows, ["docs/report.md", "scripts/test/gate.py"])
    assert rows[0]["workStatus"] == "NOT_STARTED"
    assert transitioned[0]["readinessCeiling"] == "DRY_RUN_READY"
    assert transitioned[1]["nextSlice"] == "automation.phase6.closure"
    assert transitioned[0]["evidenceRefs"] == "docs/report.md;scripts/test/gate.py"
    assert "docs/report.md" in transitioned[1]["evidenceRefs"]
    expect_error("build evidence ref", lambda: module.phase5_ledger_transition(rows, ["build/manifest.json"]), "never build evidence")

    report = module.render_report("build/manifest.json", "build/trace.json", manifest, {
        "sourceCommit": "a" * 40,
        "manifestSha256": "1" * 64,
        "traceContentSha256": "2" * 64,
        "scans": [{"artifact": item["path"], "findings": []} for item in manifest["artifacts"]],
        "safeModeMatrix": {"cases": [
            {"caseId": case["caseId"], "reason": case["reason"], "commandIndex": index + 1, "result": {"exitCode": 0}}
            for index, case in enumerate(module.safe_mode_cases(tmp))
        ]},
        "sandboxSimulation": simulation,
    })
    assert "14 exact worktree-scoped JARs" in report
    assert report.count("| `build/worktree/phase-five-test/") == 14
    assert "hard findings (`structural` or `name`): **0**" in report
    assert "two install/rollback cycles: `True`" in report
    assert "rollback idempotent and sandbox finally absent: `True`" in report
    assert "launcher plan writes: `[]`" in report
    assert "seven-case safe-mode matrix" in report
    for case in module.safe_mode_cases(tmp):
        assert f"`{case['caseId']}`" in report
    assert "not release-ready, production-ready, M14 complete, or M16 complete" in report


def test_finalize_only_temporary_ledger_report(tmp: Path):
    subprocess.run(["git", "init", "-q", str(tmp)], check=True)
    subprocess.run(["git", "-C", str(tmp), "config", "user.email", "phase5@example.invalid"], check=True)
    subprocess.run(["git", "-C", str(tmp), "config", "user.name", "Phase 5"], check=True)
    (tmp / ".gitignore").write_text("/build/\n/manifest.json\n/trace.json\n", encoding="utf-8")
    # Finalize dynamically imports the authoritative validator and resolves all ledger
    # foreign keys/evidence. Copy tracked governance fixtures rather than stubbing it.
    shutil.copytree(REPO / "docs", tmp / "docs")
    # The caller may run this suite after authoritative Phase 5 finalization has dirtied
    # the real worktree. Build the fixture from committed H1 governance, not WT state.
    ledger_bytes = subprocess.check_output(
        ["git", "-C", str(REPO), "show", "HEAD:docs/migration/automated-tranche-ledger.tsv"]
    )
    ledger_rows = list(csv.DictReader(io.StringIO(ledger_bytes.decode("utf-8")), delimiter="\t"))
    phase5 = next(item for item in ledger_rows if item["workId"] == "automation.phase5.packaging-dryrun")
    phase5.update(
        workStatus="NOT_STARTED", evidenceLevel="NONE", readinessCeiling="NONE",
        evidenceRefs="docs/migration/plans/automated-tranche-completion-plan.md",
        blockers="Phase 4 build gates", nextSlice="automation.phase6.closure",
    )
    phase6 = next(item for item in ledger_rows if item["workId"] == "automation.phase6.closure")
    phase6.update(
        workStatus="NOT_STARTED", evidenceLevel="NONE", readinessCeiling="NONE",
        evidenceRefs="docs/migration/plans/automated-tranche-completion-plan.md",
        blockers="Phase 0-5 evidence and Oracle closure",
        nextSlice="manual.real-host-observation",
        notes="Required AUTO_NOW identity for automated-tranche-closed readiness.",
    )
    overall = next(item for item in ledger_rows if item["workId"] == "tranche.automation.overall")
    overall.update(
        readinessCeiling="BUILD_GATED",
        evidenceRefs=";".join(ref for ref in overall["evidenceRefs"].split(";") if "phase5-pre-m16" not in ref),
        nextSlice="automation.phase5.packaging-dryrun",
    )
    with (tmp / "docs/migration/automated-tranche-ledger.tsv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(ledger_rows[0]), delimiter="\t", lineterminator="\n")
        writer.writeheader(); writer.writerows(ledger_rows)
    (tmp / "docs/migration/phase5-pre-m16-packaging-dryrun-report.md").unlink(missing_ok=True)
    for directory in ("runtime", "scripts"):
        shutil.copytree(REPO / directory, tmp / directory)
    manifest = fixture_manifest(tmp)
    manifest_path = tmp / "manifest.json"; manifest_path.write_bytes(module.canonical_json_bytes(manifest))
    command = {
        "cwd": str(tmp.resolve()), "command": ["ok"], "exitCode": 0,
        "startedAt": "2026-07-11T00:00:00Z", "endedAt": "2026-07-11T00:00:01Z",
        "durationMillis": 1, "stdoutSha256": "0" * 64, "stderrSha256": "0" * 64,
    }
    expected_argv = module.build_commands(tmp, "phase-five-test") + module.safe_mode_commands(tmp)
    commands = [dict(command, command=argv) for argv in expected_argv]
    build_count = len(module.build_commands(tmp, "phase-five-test"))
    cases = module.safe_mode_cases(tmp)
    trace = {
        "format": module.TRACE_FORMAT, "schemaVersion": 1, "worktreeId": "phase-five-test",
        "sourceCommit": "a" * 40,
        "processGenerated": True, "manifestSha256": "1" * 64, "traceContentSha256": "2" * 64,
        "commands": commands,
        "scans": [{"artifact": item["path"], "findings": []} for item in manifest["artifacts"]],
        "safeModeMatrix": {"cases": [
            {"caseId": case["caseId"], "reason": case["reason"], "commandIndex": build_count + index, "result": commands[build_count + index]}
            for index, case in enumerate(cases)
        ]},
        "sandboxSimulation": [
            {"cycle": 1, "action": "install", "count": 14, "success": True},
            {"cycle": 1, "action": "rollback", "absent": True, "idempotent": True, "success": True},
            {"cycle": 2, "action": "install", "count": 14, "success": True},
            {"cycle": 2, "action": "rollback", "absent": True, "idempotent": True, "success": True},
        ],
    }
    trace_path = tmp / "trace.json"
    ledger = tmp / "docs/migration/automated-tranche-ledger.tsv"
    report = tmp / "docs/migration/phase5-pre-m16-packaging-dryrun-report.md"
    subprocess.run(["git", "-C", str(tmp), "add", ".gitignore", "docs", "runtime", "scripts"], check=True)
    subprocess.run(["git", "-C", str(tmp), "commit", "-qm", "baseline fixture"], check=True)
    trace["sourceCommit"] = subprocess.check_output(["git", "-C", str(tmp), "rev-parse", "HEAD"], text=True).strip()
    trace["manifestSha256"] = module.sha256(manifest_path)
    refresh_trace_digest(trace)
    trace_path.write_bytes(module.canonical_json_bytes(trace))
    assert module._status(tmp) == []  # generated evidence is ignored; ledger is clean and new report absent.

    module.finalize(tmp, manifest_path, trace_path, ledger, report)
    assert module._status(tmp) == [" M docs/migration/automated-tranche-ledger.tsv", "?? docs/migration/phase5-pre-m16-packaging-dryrun-report.md"]
    assert "DRY_RUN_READY" in ledger.read_text() and "pre-M16" in report.read_text()

    subprocess.run(["git", "-C", str(tmp), "checkout", "--", "docs/migration/automated-tranche-ledger.tsv"], check=True)
    report.unlink()
    report.write_text("baseline report\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(tmp), "add", "docs/migration/phase5-pre-m16-packaging-dryrun-report.md"], check=True)
    subprocess.run(["git", "-C", str(tmp), "commit", "-qm", "add baseline report"], check=True)
    trace["sourceCommit"] = subprocess.check_output(["git", "-C", str(tmp), "rev-parse", "HEAD"], text=True).strip()
    refresh_trace_digest(trace)
    trace_path.write_bytes(module.canonical_json_bytes(trace))
    original_validator = module._validate_phase5_ledger
    calls = 0
    def adversarial_validator(repo: Path, path: Path, evidence_overrides=None):
        nonlocal calls
        calls += 1
        if calls == 2:
            (repo / "rogue.txt").write_text("unexpected", encoding="utf-8")
        return []
    module._validate_phase5_ledger = adversarial_validator
    try:
        expect_error(
            "post-validator side effect",
            lambda: module.finalize(tmp, manifest_path, trace_path, ledger, report),
            "unstaged-only ledger/report allowance",
        )
    finally:
        module._validate_phase5_ledger = original_validator
        (tmp / "rogue.txt").unlink(missing_ok=True)
    assert ledger.read_text() == subprocess.check_output(["git", "-C", str(tmp), "show", "HEAD:docs/migration/automated-tranche-ledger.tsv"], text=True)
    assert report.read_text() == "baseline report\n"

    # Digest failures are explicit, independent negative cases.
    wrong_manifest_digest = json.loads(json.dumps(trace))
    wrong_manifest_digest["manifestSha256"] = "0" * 64
    refresh_trace_digest(wrong_manifest_digest)
    assert "manifestSha256 must equal canonical manifest file bytes" in module.trace_errors(wrong_manifest_digest, tmp, manifest)
    wrong_trace_digest = json.loads(json.dumps(trace))
    wrong_trace_digest["traceContentSha256"] = "0" * 64
    assert "traceContentSha256 must equal canonical trace bytes without itself" in module.trace_errors(wrong_trace_digest, tmp, manifest)

    # Forged traces cannot add, remove, reorder, or relabel commands/scans/simulation/provenance.
    # Recompute the digest so each assertion reaches the semantic binding check.
    for mutate in (
        lambda forged: forged["commands"].append(dict(forged["commands"][0])),
        lambda forged: forged["commands"][0]["command"].append("--forged"),
        lambda forged: forged["safeModeMatrix"]["cases"][0].update(commandIndex=0),
        lambda forged: forged["scans"].append(dict(forged["scans"][0])),
        lambda forged: forged["scans"].reverse(),
        lambda forged: forged["sandboxSimulation"].reverse(),
        lambda forged: forged.update(sourceCommit="0" * 40),
    ):
        forged = json.loads(json.dumps(trace)); mutate(forged); refresh_trace_digest(forged)
        assert module.trace_errors(forged, tmp, manifest)

    # A forged empty scan trace cannot bless a currently forbidden artifact even when
    # its manifest digest/size have been refreshed to match those malicious bytes.
    forbidden_artifact = tmp / manifest["artifacts"][0]["path"]
    original_artifact_bytes = forbidden_artifact.read_bytes()
    original_artifact_record = dict(manifest["artifacts"][0])
    make_jar(forbidden_artifact, [("com/live2d/Secret.class", b"bytecode")])
    manifest["artifacts"][0].update(sha256=module.sha256(forbidden_artifact), size=forbidden_artifact.stat().st_size)
    manifest_path.write_bytes(module.canonical_json_bytes(manifest))
    try:
        expect_error(
            "forged empty scan trace",
            lambda: module.finalize(tmp, manifest_path, trace_path, ledger, report),
            "current artifact content scan found hard forbidden entries",
        )
    finally:
        forbidden_artifact.write_bytes(original_artifact_bytes)
        manifest["artifacts"][0] = original_artifact_record
        manifest_path.write_bytes(module.canonical_json_bytes(manifest))


def test_finalize_rejects_pre_dirty_staged_and_concurrent_modification(tmp: Path):
    test_finalize_only_temporary_ledger_report(tmp)
    # Rebuild a clean baseline after the positive case left the two intended files dirty.
    subprocess.run(["git", "-C", str(tmp), "checkout", "--", "docs/migration/automated-tranche-ledger.tsv", "docs/migration/phase5-pre-m16-packaging-dryrun-report.md"], check=True)
    manifest_path, trace_path = tmp / "manifest.json", tmp / "trace.json"
    ledger = tmp / "docs/migration/automated-tranche-ledger.tsv"
    report = tmp / "docs/migration/phase5-pre-m16-packaging-dryrun-report.md"
    (tmp / "dirty.txt").write_text("dirty")
    expect_error("pre-dirty", lambda: module.finalize(tmp, manifest_path, trace_path, ledger, report), "clean against HEAD")
    (tmp / "dirty.txt").unlink()
    ledger.write_text(ledger.read_text() + "\n")
    subprocess.run(["git", "-C", str(tmp), "add", "docs/migration/automated-tranche-ledger.tsv"], check=True)
    expect_error("staged ledger", lambda: module.finalize(tmp, manifest_path, trace_path, ledger, report), "clean against HEAD")
    subprocess.run(["git", "-C", str(tmp), "reset", "-q", "HEAD", "--", "docs/migration/automated-tranche-ledger.tsv"], check=True)
    subprocess.run(["git", "-C", str(tmp), "checkout", "--", "docs/migration/automated-tranche-ledger.tsv"], check=True)

    original_validator = module._validate_phase5_ledger
    calls = 0
    def concurrent_validator(repo: Path, path: Path, evidence_overrides=None):
        nonlocal calls
        calls += 1
        if calls == 1:
            ledger.write_text(ledger.read_text() + "# concurrent\n")
        return []
    module._validate_phase5_ledger = concurrent_validator
    try:
        expect_error("concurrent CAS", lambda: module.finalize(tmp, manifest_path, trace_path, ledger, report), "concurrent modification")
    finally:
        module._validate_phase5_ledger = original_validator
    assert "# concurrent" in ledger.read_text()

    # Once our ledger replace has happened, rollback may restore HEAD only while the
    # destination still has the exact digest we wrote. A concurrent user write wins.
    subprocess.run(["git", "-C", str(tmp), "checkout", "--", "docs/migration/automated-tranche-ledger.tsv", "docs/migration/phase5-pre-m16-packaging-dryrun-report.md"], check=True)
    calls = 0
    def post_replace_concurrent_validator(repo: Path, path: Path, evidence_overrides=None):
        nonlocal calls
        calls += 1
        if calls == 2:
            ledger.write_text(ledger.read_text() + "# user-after-replace\n", encoding="utf-8")
            return ["forced post-replace failure"]
        return []
    module._validate_phase5_ledger = post_replace_concurrent_validator
    try:
        expect_error(
            "post-replace concurrent CAS rollback",
            lambda: module.finalize(tmp, manifest_path, trace_path, ledger, report),
            "forced post-replace failure",
        )
    finally:
        module._validate_phase5_ledger = original_validator
    assert "# user-after-replace" in ledger.read_text()
    assert report.read_text() == "baseline report\n"

    # Even if the final validator reports success and Git status still shows only the
    # two allowed paths, a post-replace concurrent write must fail the exact-byte check
    # and must not be overwritten by rollback.
    for label, destination, marker in (
        ("post-replace ledger modification with validator success", ledger, "# concurrent-ledger-success\n"),
        ("post-replace report modification with validator success", report, "concurrent report success\n"),
    ):
        subprocess.run(["git", "-C", str(tmp), "checkout", "--", "docs/migration/automated-tranche-ledger.tsv", "docs/migration/phase5-pre-m16-packaging-dryrun-report.md"], check=True)
        calls = 0
        def successful_concurrent_validator(repo: Path, path: Path, evidence_overrides=None):
            nonlocal calls
            calls += 1
            if calls == 2:
                destination.write_text(destination.read_text(encoding="utf-8") + marker, encoding="utf-8")
            return []
        module._validate_phase5_ledger = successful_concurrent_validator
        try:
            expect_error(
                label,
                lambda: module.finalize(tmp, manifest_path, trace_path, ledger, report),
                "concurrent modification detected after final validator/status",
            )
        finally:
            module._validate_phase5_ledger = original_validator
        assert marker in destination.read_text(encoding="utf-8")


def test_phase5_ledger_validator_positive_and_negative(tmp: Path):
    validator_path = REPO / "scripts/test/validate_automated_tranche_ledger.py"
    validator_spec = importlib.util.spec_from_file_location("phase5_validator_test", validator_path)
    validator = importlib.util.module_from_spec(validator_spec)
    assert validator_spec.loader
    validator_spec.loader.exec_module(validator)
    source = REPO / "docs/migration/automated-tranche-ledger.tsv"
    with source.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t"); fields, rows = reader.fieldnames, list(reader)
    refs = [
        "docs/migration/phase4-build-gates-report.md",
        "scripts/test/test_pre_m16_packaging_dryrun.py",
    ]
    # Build a genuine Phase 5 fixture even when the authoritative repository has
    # advanced to Phase 6 closure.
    by_id = {row["workId"]: row for row in rows}
    by_id["automation.phase6.closure"].update(
        workStatus="NOT_STARTED", evidenceLevel="NONE", readinessCeiling="NONE",
        evidenceRefs="docs/migration/plans/automated-tranche-completion-plan.md",
        blockers="Phase 0-5 evidence and Oracle closure",
        nextSlice="manual.real-host-observation",
    )
    transitioned = module.phase5_ledger_transition(rows, refs)
    ledger = tmp / "phase5.tsv"
    with ledger.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, delimiter="\t", lineterminator="\n")
        writer.writeheader(); writer.writerows(transitioned)
    assert validator.validate(REPO, ledger, target_phase="phase5") == []

    for label, mutate, expected in (
        ("phase6 unlocked", lambda by_id: by_id["automation.phase6.closure"].update(workStatus="COMPLETE"), "automation.phase6.closure"),
        ("m16 overclaim", lambda by_id: by_id["milestone.m16.overall"].update(readinessCeiling="DRY_RUN_READY"), "milestone.m16.overall"),
        ("phase5 weak evidence", lambda by_id: by_id["automation.phase5.packaging-dryrun"].update(evidenceLevel="NONE"), "automation.phase5.packaging-dryrun"),
        ("wrong next slice", lambda by_id: by_id["tranche.automation.overall"].update(nextSlice="manual.real-host-observation"), "tranche.automation.overall"),
    ):
        negative = [dict(row) for row in transitioned]
        mutate({row["workId"]: row for row in negative})
        with ledger.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields, delimiter="\t", lineterminator="\n")
            writer.writeheader(); writer.writerows(negative)
        errors = validator.validate(REPO, ledger, target_phase="phase5")
        assert any(expected in error for error in errors), (label, errors)


def main():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        tests = [value for name, value in globals().items() if name.startswith("test_") and callable(value)]
        for index, test in enumerate(sorted(tests, key=lambda item: item.__name__)):
            case = root / str(index); case.mkdir()
            test(case)
            print(f"PASS: {test.__name__}")
    print("PASS: pre-M16 packaging dry-run dynamic tests")


if __name__ == "__main__":
    main()
