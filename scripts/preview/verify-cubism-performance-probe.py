#!/usr/bin/env python3
"""Strict offline admission gate for the exact Cubism 5.3.02 validation performance probe.

Stdlib-only (json/re/argparse/datetime). Validates the probe report, the
task-scoped diagnostics capture, the run envelope (run.properties), and the
rollback manifest, and correlates every evidence piece to one exact run
(run id, variant, scenario, agent SHA-256, fixture SHA-256). Any missing,
mismatched, or stale piece fails the run closed; this tool never invents
restoration evidence.

Usage:
  verify-cubism-performance-probe.py --variant on|off --run-id RUN_ID \
      --diagnostics DIAGNOSTICS.txt --run-properties RUN.properties \
      --rollback-manifest MANIFEST.json \
      --agent-sha256 HEX64 --fixture-sha256 HEX64 \
      [--report REPORT.json] --scenario camera|edit
  verify-cubism-performance-probe.py --self-test
"""
import argparse
import datetime as dt
import json
import os
import re
import sys

CUBISM_VERSION = "5.3.02"
ARTIFACT_SHA256 = "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
REPORT_FORMAT = "turboism.cubism.performance-probe"
ROLLBACK_FORMAT = "turboism.cubism.performance-probe-rollback"
SCHEMA_VERSION = 1

METRIC_NAMES = [
    "renderScene",
    "modelingPreRenderUpdate",
    "renderSystem",
    "sceneTraversal",
    "rendererDispatch",
    "updateModelInstances",
    "reinitModelInstanceExe",
]
P0_METRICS = METRIC_NAMES[:5]
P1_METRICS = METRIC_NAMES[5:]
METRIC_FIELDS = ("calls", "sampled", "totalNanos", "maxNanos")

OWNER_CLASSES = [
    "com.live2d.cubism.view.context.CEViewContext",
    "com.live2d.cubism.view.context.K",
    "com.live2d.graphics3d.rendering.e",
    "com.live2d.cubism.doc.model.CModelSource",
    "com.live2d.cubism.doc.model.CModel",
]

# owner, method, descriptor, report metric name — mirrors
# PerformanceProbeTargets.cubism5302().
SELECTORS = [
    ("com.live2d.cubism.view.context.CEViewContext", "renderScene_exe",
     "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;Lcom/live2d/type/CRect;)V",
     "renderScene"),
    ("com.live2d.cubism.view.context.K", "a",
     "(Lcom/live2d/graphics3d/a;)V", "modelingPreRenderUpdate"),
    ("com.live2d.graphics3d.rendering.e", "b",
     "(Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;ZZ)V", "renderSystem"),
    ("com.live2d.graphics3d.rendering.e", "a",
     "(Lcom/live2d/graphics3d/entity/GEntity;ZZZLjava/util/ArrayList;)V", "sceneTraversal"),
    ("com.live2d.graphics3d.rendering.e", "a",
     "(Lcom/live2d/graphics3d/component/AGRenderer;Lcom/live2d/graphics3d/a;Lcom/live2d/type/CRect;)V",
     "rendererDispatch"),
    ("com.live2d.cubism.doc.model.CModelSource", "updateModelInstances",
     "()V", "updateModelInstances"),
    ("com.live2d.cubism.doc.model.CModel", "reinitModelInstance_exe",
     "()V", "reinitModelInstanceExe"),
]

MIN_CAPTURE_MS = 5_000   # documented capture duration range (5-120 s)
MAX_CAPTURE_MS = 120_000
MIN_OVERLAP_MS = 1_000

INSTALL_DIAGNOSTIC = "Turboism validation performance probe installed"
FAILURE_DIAGNOSTICS = (
    "performance probe disabled safely",
    "performance probe cleanup failed",
    "performance probe report failed",
    "performance probe capture rejected",
)

HEX64 = re.compile(r"^[0-9a-f]{64}$")
RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
PROPERTY_LINE = re.compile(r"^([A-Za-z0-9_]+)=(.*)$")


class AdmissionError(Exception):
    """One concrete admission failure; message is the reason."""


def fail(reason):
    raise AdmissionError(reason)


def load_json(path, label):
    try:
        with open(path, encoding="utf-8") as source:
            return json.load(source)
    except (OSError, ValueError) as error:
        fail(f"{label} {path} is not readable JSON: {error}")


def require_hex64(value, label):
    if not isinstance(value, str) or not HEX64.match(value):
        fail(f"{label} must be a lowercase 64-character SHA-256, got {value!r}")


def require_schema_int_one(value, label):
    if not isinstance(value, int) or isinstance(value, bool) or value != 1:
        fail(f"{label} must be the integer 1, got {value!r}")


def require_positive_int(value, label):
    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
        fail(f"{label} must be a non-negative integer, got {value!r}")


def parse_properties(path):
    result = {}
    with open(path, encoding="utf-8") as source:
        for line_number, raw in enumerate(source, start=1):
            line = raw.strip()
            if not line:
                continue
            match = PROPERTY_LINE.match(line)
            if not match:
                fail(f"run properties line {line_number} is malformed: {line!r}")
            key, value = match.group(1), match.group(2)
            if key in result:
                fail(f"run properties line {line_number} repeats key {key!r}")
            result[key] = value
    return result


def validate_run_properties(path, run_id, variant, scenario, agent_sha, fixture_sha):
    """Validate and return the run envelope; every listed field is required."""
    try:
        properties = parse_properties(path)
    except OSError as error:
        fail(f"run properties {path} unreadable: {error}")
    expected = {
        "variant": variant,
        "scenario": scenario,
        "run_id": run_id,
        "agent_sha256": agent_sha,
        "fixture_sha256": fixture_sha,
        "fixture_sha256_after": fixture_sha,
    }
    for key, value in expected.items():
        actual = properties.get(key)
        if actual is None:
            fail(f"run properties is missing required field {key}")
        if actual != value:
            fail(f"run properties {key}={actual!r} does not match expected {value!r}")
    require_hex64(properties["agent_sha256"], "run-properties agent_sha256")
    require_hex64(properties["fixture_sha256"], "run-properties fixture_sha256")
    require_hex64(properties["fixture_sha256_after"], "run-properties fixture_sha256_after")
    if "launch_epoch_ms" not in properties:
        fail("run properties is missing required field launch_epoch_ms")
    try:
        require_positive_int(int(properties["launch_epoch_ms"]), "run-properties launch_epoch_ms")
    except ValueError:
        fail(f"run-properties launch_epoch_ms is not an integer: {properties['launch_epoch_ms']!r}")
    for key in ("scenario_start_epoch_ms", "scenario_end_epoch_ms"):
        if key not in properties:
            fail(f"run properties is missing required field {key}")
        try:
            require_positive_int(int(properties[key]), f"run-properties {key}")
        except ValueError:
            fail(f"run-properties {key} is not an integer: {properties[key]!r}")
    scenario_start = int(properties["scenario_start_epoch_ms"])
    scenario_end = int(properties["scenario_end_epoch_ms"])
    launch = int(properties["launch_epoch_ms"])
    if scenario_end <= scenario_start:
        fail("run-properties scenario window must have end strictly after start")
    if launch > scenario_start:
        fail(f"run-properties scenario starts before launch: scenario_start={scenario_start} "
             f"< launch_epoch_ms={launch}")
    return properties


def validate_diagnostics(path):
    try:
        with open(path, encoding="utf-8") as source:
            text = source.read()
    except OSError as error:
        fail(f"diagnostics capture {path} unreadable: {error}")
    lowered = text.lower()
    if not lowered.strip():
        fail(f"diagnostics capture {path} is empty; probe install diagnostic was not observed")
    if INSTALL_DIAGNOSTIC.lower() not in lowered:
        fail(f"diagnostics capture {path} lacks required install diagnostic {INSTALL_DIAGNOSTIC!r}")
    for pattern in FAILURE_DIAGNOSTICS:
        if pattern.lower() in lowered:
            fail(f"diagnostics capture {path} contains failure diagnostic {pattern!r}")


def validate_rollback_manifest(path, run_id, variant, scenario, agent_sha, fixture_sha):
    manifest = load_json(path, "rollback manifest")
    if manifest.get("format") != ROLLBACK_FORMAT:
        fail(f"rollback manifest format {manifest.get('format')!r} is not {ROLLBACK_FORMAT!r}")
    require_schema_int_one(manifest.get("schemaVersion"), "rollback manifest schemaVersion")
    if manifest.get("cubismVersion") != CUBISM_VERSION:
        fail(f"rollback manifest cubismVersion {manifest.get('cubismVersion')!r} != {CUBISM_VERSION!r}")
    if manifest.get("artifactSha256") != ARTIFACT_SHA256:
        fail("rollback manifest artifactSha256 does not match the reviewed 5.3.02 artifact")
    expected = {
        "runId": run_id,
        "variant": variant,
        "scenario": scenario,
        "agentSha256": agent_sha,
        "fixtureSha256": fixture_sha,
    }
    for key, value in expected.items():
        actual = manifest.get(key)
        if actual != value:
            fail(f"rollback manifest {key}={actual!r} does not match expected {value!r}")
    require_hex64(manifest["agentSha256"], "rollback manifest agentSha256")
    require_hex64(manifest["fixtureSha256"], "rollback manifest fixtureSha256")

    owners = manifest.get("owners")
    if not isinstance(owners, list):
        fail("rollback manifest owners must be a list")
    seen_owners = []
    for entry in owners:
        if not isinstance(entry, dict):
            fail("rollback manifest owner entry must be an object")
        owner = entry.get("class")
        if owner not in OWNER_CLASSES:
            fail(f"rollback manifest owner {owner!r} is not an expected target owner")
        if owner in seen_owners:
            fail(f"rollback manifest owner {owner!r} appears more than once")
        seen_owners.append(owner)
        before = entry.get("beforeSha256")
        instrumented = entry.get("instrumentedSha256")
        after = entry.get("afterSha256")
        require_hex64(before, f"rollback beforeSha256 for {owner}")
        require_hex64(instrumented, f"rollback instrumentedSha256 for {owner}")
        require_hex64(after, f"rollback afterSha256 for {owner}")
        if instrumented == before:
            fail(f"rollback instrumentedSha256 equals the baseline for {owner}; "
                 "instrumented bytes must differ from the pre-install bytes")
        if after != before:
            fail(f"rollback restoration mismatch: {owner} afterSha256 differs from beforeSha256")
        require_schema_int_one(entry.get("restorationMatches"),
                                f"rollback restoration count for {owner}")
    missing_owners = [owner for owner in OWNER_CLASSES if owner not in seen_owners]
    if missing_owners:
        fail(f"rollback manifest lacks owners: {missing_owners}")

    selectors = manifest.get("selectors")
    if not isinstance(selectors, list):
        fail("rollback manifest selectors must be a list")
    seen_selectors = []
    for entry in selectors:
        if not isinstance(entry, dict):
            fail("rollback manifest selector entry must be an object")
        key = (entry.get("owner"), entry.get("method"), entry.get("descriptor"), entry.get("metric"))
        if key not in SELECTORS:
            fail(f"rollback manifest selector {key} is not an expected target selector")
        if key in seen_selectors:
            fail(f"rollback manifest selector {key} appears more than once")
        seen_selectors.append(key)
        require_schema_int_one(entry.get("matches"),
                                f"rollback manifest selector {key} match count")
    missing_selectors = [selector for selector in SELECTORS if selector not in seen_selectors]
    if missing_selectors:
        fail(f"rollback manifest lacks selectors: {missing_selectors}")


def validate_report(path, scenario, agent_sha, fixture_sha, run_properties):
    report = load_json(path, "probe report")
    if report.get("format") != REPORT_FORMAT:
        fail(f"probe report format {report.get('format')!r} is not {REPORT_FORMAT!r}")
    require_schema_int_one(report.get("schemaVersion"), "probe report schemaVersion")
    if report.get("cubismVersion") != CUBISM_VERSION:
        fail(f"probe report cubismVersion {report.get('cubismVersion')!r} != {CUBISM_VERSION!r}")
    if report.get("artifactSha256") != ARTIFACT_SHA256:
        fail("probe report artifactSha256 does not match the reviewed 5.3.02 artifact")
    require_hex64(report.get("agentSha256"), "probe report agentSha256")
    require_hex64(report.get("fixtureSha256"), "probe report fixtureSha256")
    if report.get("agentSha256") != agent_sha:
        fail("probe report agentSha256 does not match the expected validation agent")
    if report.get("fixtureSha256") != fixture_sha:
        fail("probe report fixtureSha256 does not match the expected fixture")
    if report.get("scenario") != scenario:
        fail(f"probe report scenario {report.get('scenario')!r} does not match selected {scenario!r}")

    capture = report.get("capture")
    if not isinstance(capture, dict):
        fail("probe report capture must be an object")
    start, end = capture.get("startEpochMs"), capture.get("endEpochMs")
    require_positive_int(start, "capture.startEpochMs")
    require_positive_int(end, "capture.endEpochMs")
    if start >= end:
        fail(f"capture window inverted or empty: {start} >= {end}")
    duration = end - start
    if duration < MIN_CAPTURE_MS or duration > MAX_CAPTURE_MS:
        fail(f"capture duration {duration} ms outside documented bounds "
             f"[{MIN_CAPTURE_MS}, {MAX_CAPTURE_MS}]")
    require_positive_int(capture.get("dropped"), "capture.dropped")
    if capture.get("dropped") != 0:
        fail(f"capture dropped={capture.get('dropped')!r}; dropped samples are not admissible")
    require_positive_int(capture.get("failures"), "capture.failures")
    if capture.get("failures") != 0:
        fail(f"capture failures={capture.get('failures')!r}; failed capture is not admissible")

    written_at = report.get("writtenAt")
    try:
        dt.datetime.fromisoformat(str(written_at))
    except (TypeError, ValueError):
        fail(f"probe report writtenAt {written_at!r} is not ISO-8601")

    window_start = int(run_properties["scenario_start_epoch_ms"])
    window_end = int(run_properties["scenario_end_epoch_ms"])
    overlap = min(end, window_end) - max(start, window_start)
    if overlap < MIN_OVERLAP_MS:
        fail(f"capture window [{start}, {end}] does not overlap the scenario window "
             f"[{window_start}, {window_end}] by at least {MIN_OVERLAP_MS} ms")

    metrics = report.get("metrics")
    if not isinstance(metrics, dict):
        fail("probe report metrics must be an object")
    if sorted(metrics.keys()) != sorted(METRIC_NAMES):
        fail(f"probe report metric set {sorted(metrics.keys())} != expected {METRIC_NAMES}")
    for name, metric in metrics.items():
        if not isinstance(metric, dict) or set(metric.keys()) != set(METRIC_FIELDS):
            fail(f"probe report metric {name} must have exactly fields {METRIC_FIELDS}")
        for field in METRIC_FIELDS:
            require_positive_int(metric[field], f"metrics.{name}.{field}")
        calls, sampled, total, maximum = (metric[field] for field in METRIC_FIELDS)
        if sampled > calls:
            fail(f"metrics.{name}.sampled={sampled} exceeds calls={calls}")
        if maximum > total:
            fail(f"metrics.{name}.maxNanos={maximum} exceeds totalNanos={total}")
        if sampled == 0 and (total != 0 or maximum != 0):
            fail(f"metrics.{name} has sampled=0 but nonzero totalNanos/maxNanos")
    if scenario == "camera":
        for name in P1_METRICS:
            if metrics[name]["calls"] != 0:
                fail(f"camera scenario recorded P1 calls for {name}; P1 must stay masked")
        for name in P0_METRICS:
            if metrics[name]["calls"] == 0:
                fail(f"camera scenario recorded zero calls for P0 metric {name}; capture is empty")


def verify(arguments):
    if arguments.variant not in ("on", "off"):
        fail("--variant must be on or off")
    if arguments.scenario not in ("camera", "edit"):
        fail("--scenario must be camera or edit")
    if not isinstance(arguments.run_id, str) or not RUN_ID.match(arguments.run_id):
        fail(f"--run-id must match {RUN_ID.pattern}, got {arguments.run_id!r}")
    require_hex64(arguments.agent_sha256, "--agent-sha256")
    require_hex64(arguments.fixture_sha256, "--fixture-sha256")
    if not arguments.run_properties:
        fail("--run-properties is required")
    if not arguments.diagnostics:
        fail("--diagnostics is required")
    if not arguments.rollback_manifest:
        fail("--rollback-manifest is required")

    run_properties = validate_run_properties(
        arguments.run_properties,
        arguments.run_id,
        arguments.variant,
        arguments.scenario,
        arguments.agent_sha256,
        arguments.fixture_sha256,
    )
    validate_diagnostics(arguments.diagnostics)
    validate_rollback_manifest(
        arguments.rollback_manifest,
        arguments.run_id,
        arguments.variant,
        arguments.scenario,
        arguments.agent_sha256,
        arguments.fixture_sha256,
    )
    if arguments.variant == "on":
        if not arguments.report:
            fail("--report is required when --variant on")
        validate_report(
            arguments.report, arguments.scenario,
            arguments.agent_sha256, arguments.fixture_sha256, run_properties,
        )
    elif arguments.report:
        fail("--report must be absent when --variant off")


def self_test():
    """Minimal runnable self-check: a valid evidence set passes; each corruption fails."""

    def report(scenario="camera", failures=0, dropped=0, zero_p1=False, metric_set=None,
               duration_ms=35_000, start_ms=1_700_000_000_000):
        metrics = {name: {"calls": 100, "sampled": 100, "totalNanos": 0, "maxNanos": 0}
                   for name in METRIC_NAMES}
        if zero_p1:
            for name in P1_METRICS:
                metrics[name] = {"calls": 0, "sampled": 0, "totalNanos": 0, "maxNanos": 0}
        if metric_set is not None:
            metrics = metric_set
        return {
            "format": REPORT_FORMAT, "schemaVersion": SCHEMA_VERSION,
            "cubismVersion": CUBISM_VERSION, "artifactSha256": ARTIFACT_SHA256,
            "agentSha256": "c" * 64, "fixtureSha256": "d" * 64,
            "scenario": scenario,
            "capture": {"startEpochMs": start_ms, "endEpochMs": start_ms + duration_ms,
                        "dropped": dropped, "failures": failures},
            "metrics": metrics,
            "writtenAt": "2026-08-02T12:00:00Z",
        }

    def manifest():
        return {
            "format": ROLLBACK_FORMAT, "schemaVersion": SCHEMA_VERSION,
            "cubismVersion": CUBISM_VERSION, "artifactSha256": ARTIFACT_SHA256,
            "runId": "run-01", "variant": "on", "scenario": "camera",
            "agentSha256": "c" * 64, "fixtureSha256": "d" * 64,
            "owners": [{"class": owner, "beforeSha256": "e" * 64,
                        "instrumentedSha256": "f" * 64, "afterSha256": "e" * 64,
                        "restorationMatches": 1}
                       for owner in OWNER_CLASSES],
            "selectors": [{"owner": owner, "method": method, "descriptor": descriptor,
                           "metric": metric, "matches": 1}
                          for owner, method, descriptor, metric in SELECTORS],
        }

    def run_properties_text(variant="on", scenario="camera", run_id="run-01",
                            agent_sha=None, fixture_sha=None,
                            start_ms=1_699_999_000_000, end_ms=1_700_000_040_000,
                            launch_ms=1_699_998_000_000):
        return "\n".join([
            f"variant={variant}", f"scenario={scenario}", f"run_id={run_id}",
            f"launch_epoch_ms={launch_ms}",
            f"agent_sha256={agent_sha or 'c' * 64}",
            f"fixture_sha256={fixture_sha or 'd' * 64}",
            f"fixture_sha256_after={fixture_sha or 'd' * 64}",
            f"scenario_start_epoch_ms={start_ms}", f"scenario_end_epoch_ms={end_ms}",
        ]) + "\n"

    import tempfile
    with tempfile.TemporaryDirectory() as directory:
        report_path = os.path.join(directory, "report.json")
        manifest_path = os.path.join(directory, "manifest.json")
        diagnostics_path = os.path.join(directory, "diagnostics.txt")
        properties_path = os.path.join(directory, "run.properties")
        with open(report_path, "w", encoding="utf-8") as target:
            json.dump(report(zero_p1=True), target)
        with open(manifest_path, "w", encoding="utf-8") as target:
            json.dump(manifest(), target)
        with open(diagnostics_path, "w", encoding="utf-8") as target:
            target.write(INSTALL_DIAGNOSTIC + "\n")
        with open(properties_path, "w", encoding="utf-8") as target:
            target.write(run_properties_text())

        def args(**overrides):
            base = {
                "variant": "on",
                "scenario": "camera",
                "run_id": "run-01",
                "agent_sha256": "c" * 64,
                "fixture_sha256": "d" * 64,
                "report": report_path,
                "run_properties": properties_path,
                "diagnostics": diagnostics_path,
                "rollback_manifest": manifest_path,
            }
            base.update(overrides)
            return argparse.Namespace(**base)

        verify(args())

        off_manifest = manifest()
        off_manifest["variant"] = "off"
        off_args = args(variant="off", report=None,
                        run_properties=_write(directory, "run-off.properties",
                                              run_properties_text(variant="off")),
                        rollback_manifest=_write(directory, "manifest-off.json", off_manifest))
        verify(off_args)

        corrupt = []
        cases = [
            ("report failures=1", {"report": _write(directory, "r1", report(failures=1))}),
            ("report dropped=1", {"report": _write(directory, "r2", report(dropped=1))}),
            ("report P1 calls for camera", {"report": _write(directory, "r3", report(zero_p1=False))}),
            ("report empty P0", {"report": _write(directory, "r4", report(metric_set={
                name: {"calls": 0 if name in P0_METRICS else 100,
                       "sampled": 0, "totalNanos": 0, "maxNanos": 0}
                for name in METRIC_NAMES}))}),
            ("report wrong scenario", {"report": _write(directory, "r5", report(scenario="edit"))}),
            ("report wrong agent", {"report": _write(directory, "r6", report()),
                                   "agent_sha256": "f" * 64}),
            ("report wrong metric set", {"report": _write(directory, "r7", report(metric_set={
                "renderScene": {"calls": 1, "sampled": 1, "totalNanos": 0, "maxNanos": 0}}))}),
            ("report capture window inverted", {"report": _write(directory, "r8", report(duration_ms=-1))}),
            ("report stale non-overlapping capture", {"report": _write(
                directory, "r9", report(start_ms=1_800_000_000_000))}),
            ("run properties variant mismatch", {"run_properties": _write(
                directory, "p1", run_properties_text(variant="off"))}),
            ("run properties missing scenario", {"run_properties": _write(
                directory, "p2", run_properties_text().replace("scenario=camera\n", ""))}),
            ("run properties inverted window", {"run_properties": _write(
                directory, "p3", run_properties_text(start_ms=1_700_000_040_000,
                                                     end_ms=1_699_995_000_000))}),
            ("manifest after differs", {"rollback_manifest": _write(
                directory, "m1", _manifest_after_changed(manifest()))}),
            ("manifest selector count 0", {"rollback_manifest": _write(
                directory, "m2", _manifest_selector_changed(manifest()))}),
            ("manifest missing owner", {"rollback_manifest": _write(
                directory, "m3", _manifest_missing_owner(manifest()))}),
            ("manifest stale run id", {"rollback_manifest": _write(
                directory, "m4", _manifest_run_id_changed(manifest()))}),
            ("manifest mismatched agent", {"rollback_manifest": _write(
                directory, "m5", _manifest_agent_changed(manifest()))}),
            ("missing rollback manifest", {"rollback_manifest": os.path.join(directory, "absent.json")}),
            ("empty diagnostics", {"diagnostics": _write(directory, "d1", "")}),
            ("diagnostics with non-validation failure wording", {"diagnostics": _write(
                directory, "d2", INSTALL_DIAGNOSTIC + "\n"
                + "Turboism performance probe cleanup failed safely\n")}),
            ("diagnostics with failure in mixed case", {"diagnostics": _write(
                directory, "d3", INSTALL_DIAGNOSTIC + "\n"
                + "Turboism PERFORMANCE Probe capture REJECTED safely\n")}),
            ("report failures as string", {"report": _write(
                directory, "r10", report(failures="0"))}),
            ("report sampled exceeds calls", {"report": _write(
                directory, "r11", report(metric_set={name: {
                    "calls": 100, "sampled": 200, "totalNanos": 0, "maxNanos": 0}
                    for name in METRIC_NAMES}))}),
            ("report maxNanos exceeds totalNanos", {"report": _write(
                directory, "r12", report(metric_set={name: {
                    "calls": 100, "sampled": 100, "totalNanos": 5, "maxNanos": 9}
                    for name in METRIC_NAMES}))}),
            ("report zero calls with nonzero totals", {"report": _write(
                directory, "r13", report(metric_set={name: {
                    "calls": 0, "sampled": 0, "totalNanos": 7, "maxNanos": 0}
                    for name in METRIC_NAMES}))}),
            ("manifest missing instrumented hash", {"rollback_manifest": _write(
                directory, "m6", _manifest_missing_instrumented(manifest()))}),
            ("manifest instrumented equals baseline", {"rollback_manifest": _write(
                directory, "m7", _manifest_instrumented_unchanged(manifest()))}),
            ("manifest bad restoration count", {"rollback_manifest": _write(
                directory, "m8", _manifest_bad_restoration_count(manifest()))}),
            ("run properties missing fixture after hash", {"run_properties": _write(
                directory, "p4", run_properties_text().replace(
                    "fixture_sha256_after=" + "d" * 64 + "\n", ""))}),
            ("run properties fixture after mismatch", {"run_properties": _write(
                directory, "p5", run_properties_text(fixture_sha="d" * 64).replace(
                    "fixture_sha256_after=" + "d" * 64,
                    "fixture_sha256_after=" + "a" * 64))}),
            ("run properties duplicate key", {"run_properties": _write(
                directory, "p6", "variant=on\n" + run_properties_text())}),
            ("run properties malformed line", {"run_properties": _write(
                directory, "p7", run_properties_text().replace(
                    "variant=on\n", "variant on\n"))}),
            ("run properties nonnumeric launch", {"run_properties": _write(
                directory, "p8", run_properties_text(launch_ms="abc"))}),
            ("run properties equal scenario window", {"run_properties": _write(
                directory, "p9", run_properties_text(start_ms=1_700_000_040_000,
                                                     end_ms=1_700_000_040_000))}),
            ("run properties pre-launch scenario", {"run_properties": _write(
                directory, "p10", run_properties_text(start_ms=1_699_997_000_000,
                                                      launch_ms=1_699_998_000_000))}),
            ("report schemaVersion as boolean", {"report": _write(
                directory, "r14", _schema_bool(report()))}),
            ("manifest schemaVersion as boolean", {"rollback_manifest": _write(
                directory, "m9", _schema_bool(manifest()))}),
            ("manifest matches as boolean", {"rollback_manifest": _write(
                directory, "m10", _selector_matches_bool(manifest()))}),
            ("manifest restorationMatches as boolean", {"rollback_manifest": _write(
                directory, "m11", _restoration_matches_bool(manifest()))}),
            ("report sampled zero with nonzero total", {"report": _write(
                directory, "r15", report(metric_set={
                    name: {"calls": 0 if name in P1_METRICS else 100,
                           "sampled": 0, "totalNanos": 5, "maxNanos": 0}
                    for name in METRIC_NAMES}))}),
        ]
        for label, overrides in cases:
            try:
                verify(args(**overrides))
                corrupt.append(label)
            except AdmissionError:
                pass
        if corrupt:
            raise SystemExit(f"self-test FAILED: admission accepted: {corrupt}")

    print("verify-cubism-performance-probe.py self-test: PASS")


def _write(directory, name, data):
    path = os.path.join(directory, name)
    with open(path, "w", encoding="utf-8") as target:
        if isinstance(data, str):
            target.write(data)
        else:
            json.dump(data, target)
    return path


def _manifest_after_changed(manifest):
    manifest["owners"][0]["afterSha256"] = "f" * 64
    return manifest


def _manifest_selector_changed(manifest):
    manifest["selectors"][0]["matches"] = 0
    return manifest


def _manifest_missing_owner(manifest):
    manifest["owners"] = manifest["owners"][:-1]
    return manifest


def _manifest_run_id_changed(manifest):
    manifest["runId"] = "other-run"
    return manifest


def _manifest_agent_changed(manifest):
    manifest["agentSha256"] = "f" * 64
    return manifest


def _manifest_missing_instrumented(manifest):
    del manifest["owners"][0]["instrumentedSha256"]
    return manifest


def _manifest_instrumented_unchanged(manifest):
    manifest["owners"][0]["instrumentedSha256"] = manifest["owners"][0]["beforeSha256"]
    return manifest


def _manifest_bad_restoration_count(manifest):
    manifest["owners"][0]["restorationMatches"] = 0
    return manifest


def _schema_bool(doc):
    doc["schemaVersion"] = True
    return doc


def _selector_matches_bool(manifest):
    manifest["selectors"][0]["matches"] = True
    return manifest


def _restoration_matches_bool(manifest):
    manifest["owners"][0]["restorationMatches"] = True
    return manifest


def main():
    if "--self-test" in sys.argv:
        self_test()
        return
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=("on", "off"), required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--report")
    parser.add_argument("--diagnostics", required=True)
    parser.add_argument("--rollback-manifest", required=True)
    parser.add_argument("--run-properties", required=True)
    parser.add_argument("--scenario", choices=("camera", "edit"), required=True)
    parser.add_argument("--agent-sha256", required=True)
    parser.add_argument("--fixture-sha256", required=True)
    parser.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args()
    if arguments.self_test:
        self_test()
        return
    try:
        verify(arguments)
    except AdmissionError as error:
        print(f"performance probe evidence admission: FAIL — {error}", file=sys.stderr)
        raise SystemExit(1)
    print("performance probe evidence admission: PASS")


if __name__ == "__main__":
    main()
