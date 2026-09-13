#!/usr/bin/env bash
# Offline regression checks for the blocked 020 T036 wrapper and probe bundle.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
wrapper="$root/scripts/preview/run-atlas-image-host-validation.sh"
probe_builder="$root/validation/atlas-image-probe/build-and-selfcheck.sh"
manifest="$root/scripts/preview/host-validation-020.json"
# Machine-specific paths are supplied by the ignored .env/environment entry.
# shellcheck source=/dev/null
source "$root/scripts/preview/host-validation-env.sh"
turboism_select_fixture 5303 || exit 2
fixture="${fixture_src:-}"
fixture_sha256=2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e
fixture_name=atlas_mapping_100.cmo3

fail() {
  printf 'atlas-image offline test: %s\n' "$*" >&2
  exit 1
}

[[ -n "$fixture" ]] || fail 'set TURBOISM_HOST_VALIDATION_FIXTURE_5303 in ignored .env/environment'
[[ "$fixture" = /* ]] || fail "fixture path must be absolute: $fixture"
[[ "$(basename -- "$fixture")" == "$fixture_name" ]] || fail "fixture basename mismatch: $fixture"
[[ -f "$fixture" ]] || fail "fixture is missing: $fixture"
fixture="$(realpath -e -- "$fixture")"
[[ "$(sha256sum "$fixture" | cut -d' ' -f1)" == "$fixture_sha256" ]] \
  || fail "fixed Circle100 fixture hash mismatch: $fixture"

bash -n "$wrapper"
bash -n "$probe_builder"
python3 -m json.tool "$manifest" >/dev/null
grep -q 'if \[ "\${#ready_markers\[@\]}" -gt 0 \]; then' \
  "$root/scripts/preview/run-cubism-host-validation.sh"
! grep -Eq -- '--(ready-marker|failure-marker|ready-timeout)' "$wrapper"
python3 - "$wrapper" <<'PY'
from pathlib import Path
import sys


def require(condition, message):
    if not condition:
        raise SystemExit(message)


text = Path(sys.argv[1]).read_text(encoding="utf-8")
require('runner_args+=(--prepare-dir "$prepare_dir")' in text,
        "wrapper prepare branch missing")
require('runner_args+=(--dry-run)' in text, "wrapper dry-run branch missing")
for placeholder in ("{HOME}", "{TASK_ID}", "{FIXTURE}", "{FIXTURE_NAME}"):
    require(placeholder in text, f"wrapper placeholder missing: {placeholder}")
for forbidden in ("--remote-pre-launch", "--remote-post-launch", "--remote-pre-cleanup", "--client-script"):
    require(forbidden not in text, f"forbidden hook option present: {forbidden}")
print("WRAPPER_ARGUMENT_CONTRACT PASS prepareBranch=static-only")
PY
python3 - "$root/validation/atlas-image-probe/src/dev/turboism/validation/atlasimage/AtlasImageSceneDriverAgent.java" <<'PY'
from pathlib import Path
import sys


def require(condition, message):
    if not condition:
        raise SystemExit(message)


text = Path(sys.argv[1]).read_text(encoding="utf-8")
require(
    'private static final String EDITOR_CLASS = "com.live2d.cubism.doc.modeling.ui.atlasEditor.f$b";' in text,
    "exact editor class missing",
)
require(
    'private static final String EDITOR_CLASS = "com.live2d.cubism.doc.modeling.ui.atlasEditor.a.f";' not in text,
    "old editor class still assigned",
)
require("addShutdownHook" not in text, "driver must not equate shutdown with action success")
require("SwingUtilities.invokeAndWait" not in text, "unbounded invokeAndWait remains")
require("FixedEdtInvocation" in text, "fixed EDT invocation gate missing")
for marker in ("EdtInvocationState.QUEUED", "EdtInvocationState.STARTED",
               "EdtInvocationState.TIMED_OUT", "timeoutIfQueued()", "skipAfterTimeout()",
               "completeStarted()", "onEdtLateSkipped"):
    require(marker in text, f"bounded EDT marker missing: {marker}")
require("driver-stage.properties" in text, "bounded stage evidence path missing")
on_edt_start = text.find("private static <T> T onEdt")
on_edt_end = text.find("static void awaitActionCompletion", on_edt_start)
require(on_edt_start >= 0 and on_edt_end > on_edt_start, "bounded onEdt section missing")
on_edt = text[on_edt_start:on_edt_end]
require("SwingUtilities.invokeLater" in on_edt, "onEdt must dispatch asynchronously")
require("if (!invocation.await(EDT_DISPATCH_TIMEOUT_SECONDS))" in on_edt,
        "onEdt bounded wait missing")
require("catch (InterruptedException interrupted)" in on_edt,
        "onEdt interruption path missing")
require("invocation.timeoutIfQueued()" in on_edt and "onEdtWaitInterrupted" in on_edt,
        "onEdt interruption must invalidate queued callback")
require("STARTUP_TIMEOUT_SECONDS = 120L" in text, "explicit startup budget missing")
startup_start = text.find("static Window waitForMain")
startup_end = text.find("/** Fixed-size diagnostic state", startup_start)
require(startup_start >= 0 and startup_end > startup_start, "startup lookup section missing")
startup = text[startup_start:startup_end]
for marker in ("Math.min(overallDeadlineNanos", "EdtOperation.MAIN_LOOKUP",
               "EdtInvocationState.TIMED_OUT", "mainLookupRetry()",
               "main window startup budget exhausted"):
    require(marker in startup, f"startup lookup marker missing: {marker}")
require("EdtOperation.MAIN_LOOKUP" in startup and "EdtInvocationState.TIMED_OUT" in startup,
        "only queued MAIN_LOOKUP timeout may continue")
dispatch_start = text.find("static final class EditorMenuDispatch")
dispatch_end = text.find("private static final class FixedDriver", dispatch_start)
require(dispatch_start >= 0 and dispatch_end > dispatch_start, "editor dispatch gate section missing")
editor_dispatch = text[dispatch_start:dispatch_end]
request_start = text.find("private void requestEditor")
request_end = text.find("private Editor waitForEditor")
require(request_start >= 0 and request_end > request_start, "editor request section missing")
request = text[request_start:request_end]
require("new EditorMenuDispatch" in request, "requestEditor must use fixed editor dispatch gate")
require("SwingUtilities.invokeLater" not in request, "requestEditor retains raw async callback")
for marker in ("invocation.tryStart()", "timeoutIfQueued()", "invocation.skipAfterTimeout()",
               "invocation.awaitStarted(EDT_DISPATCH_TIMEOUT_SECONDS)",
               "actionFinished.countDown()", "onEdtWaitInterrupted"):
    require(marker in editor_dispatch, f"editor dispatch marker missing: {marker}")
editor_release = editor_dispatch.find("invocation.signalStarted();")
editor_click = editor_dispatch.find("editor.doClick(50);")
require(0 <= editor_release < editor_click,
        "editor dispatch must release driver before modal click")
require("Thread.currentThread().interrupt();" in editor_dispatch,
        "editor dispatch interruption must restore interrupt status")
cancel_start = text.find("private void cancelEditor")
cancel_end = text.find("private void requestFinish")
require(cancel_start >= 0 and cancel_end > cancel_start, "Cancel section missing")
cancel = text[cancel_start:cancel_end]
cancel_closed = cancel.find("if (!closed)")
cancel_wait = cancel.find("awaitActionCompletion(editorActionFinished")
require(0 <= cancel_closed < cancel_wait,
        "Cancel does not await action completion after safe close")
exit_start = text.find("private void requestNativeExit")
exit_end = text.find("private void recordFailure")
require(exit_start >= 0 and exit_end > exit_start, "native exit request section missing")
exit_request = text[exit_start:exit_end]
exit_on_edt = exit_request.find("onEdt(()")
exit_click = exit_request.find("exit.doClick(50);")
exit_operation = exit_request.find("EdtOperation.NATIVE_EXIT")
require(
    0 <= exit_on_edt < exit_click < exit_operation,
    "native exit must use the fixed bounded EDT gate",
)
require("CountDownLatch" not in exit_request, "native exit must not retain an uninvalidated callback latch")
run_start = text.find("void run()")
run_end = text.find("private Path waitForOutputRoot")
require(run_start >= 0 and run_end > run_start, "driver run section missing")
run = text[run_start:run_end]
require(
    0 <= run.find("requestNativeExit(main)") < run.find("publishTaskResult(observerPass"),
    "PASS publication precedes native exit action",
)
require("publishTaskFailureAfterResult" not in text, "post-PASS overwrite fallback remains")
print("DRIVER_MODAL_DISPATCH_CONTRACT PASS async-before-click=true result-after-exit-action=true")
PY

if ! PYTHONOPTIMIZE=1 PYTHONPATH="$root/scripts/preview" python3 - <<'PY'
import tempfile
from pathlib import Path

import host_validation_queue as queue


with tempfile.TemporaryDirectory(prefix="atlas-image-optimized-negative-") as temporary:
    store = queue.Store(Path(temporary) / "queue")
    bad_request = {
        "schemaVersion": queue.SCHEMA + 1,
        "environment": {},
        "argv": [],
    }
    try:
        queue.PreparedStore(store).capture(
            bad_request,
            Path(temporary) / "missing-source",
            "atlas-image-observe:5303",
        )
    except queue.QueueError:
        print("PYTHONOPTIMIZE_NEGATIVE_CONTROL PASS invalidRequestRejected=true")
    else:
        raise SystemExit("invalid PreparedStore request was accepted")
PY
then
  fail "PYTHONOPTIMIZE=1 negative control unexpectedly accepted invalid request"
fi

build_log="$(mktemp)"
tmp="$(mktemp -d)"
test_id="atlas-test-$BASHPID"
preview_root="$root/build/preview/$test_id"
trap 'rm -f "$build_log"; rm -rf -- "$tmp" "$preview_root"' EXIT

TURBOISM_HOST_VALIDATION_FIXTURE_5303="$fixture" bash "$probe_builder" > "$build_log"
grep -q 'ATLAS_IMAGE_PROBE_SELFCHECK PASS checks=29 hostExecuted=false' "$build_log"
grep -q 'ATLAS_IMAGE_LIFECYCLE_SELFCHECK PASS checks=39 hostExecuted=false' "$build_log"
grep -q 'ATLAS_IMAGE_CONFIGURATION_SELFCHECK PASS' "$build_log"
grep -q 'ATLAS_IMAGE_EDT_GATE_SELFCHECK PASS' "$build_log"
grep -q 'ATLAS_IMAGE_MAIN_LOOKUP_SELFCHECK PASS' "$build_log"
grep -q 'ATLAS_IMAGE_EDITOR_DISPATCH_SELFCHECK PASS' "$build_log"
grep -q 'ATLAS_IMAGE_STAGE_EVIDENCE_SELFCHECK PASS' "$build_log"
grep -q 'OFFLINE_ONLY PASS' "$build_log"
evidence="$(sed -n 's/.*OFFLINE_ONLY PASS evidence=\([^ ]*\).*/\1/p' "$build_log" | tail -n 1)"
observer="$(sed -n 's/.*OFFLINE_ONLY PASS evidence=[^ ]* observer=\([^ ]*\).*/\1/p' "$build_log" | tail -n 1)"
driver="$(sed -n 's/.*OFFLINE_ONLY PASS evidence=[^ ]* observer=[^ ]* driver=\([^ ]*\).*/\1/p' "$build_log" | tail -n 1)"
[[ -d "$evidence" && -f "$observer" && -f "$driver" ]] || fail "build evidence/artifacts not reported explicitly"
test -s "$evidence/verified.properties"
sha256sum -c "$evidence/artifact.sha256" >/dev/null

# The scheduler manifest is deliberately blocked and therefore cannot prepare or submit a host job.
if python3 "$root/scripts/preview/host_validation.py" --manifest "$manifest" \
    plan atlas-image-observe:5303 > "$tmp/blocked-plan.log" 2>&1; then
  fail "blocked 020 manifest unexpectedly planned as runnable"
fi
grep -q 'blocked:' "$tmp/blocked-plan.log"
grep -q '"runnable": false' "$manifest"

mkdir -p "$preview_root/atlas-image-observe/bundle.test" "$tmp/golden" "$tmp/host" "$tmp/bin"
printf 'production-agent-test\n' > "$preview_root/turboism-agent.jar"
cp -- "$observer" "$preview_root/atlas-image-observe/bundle.test/atlas-image-load-probe.jar"
cp -- "$driver" "$preview_root/atlas-image-observe/bundle.test/atlas-image-scene-driver.jar"
cat > "$tmp/bin/shorin-proton-wrapper" <<'EOF'
#!/usr/bin/env bash
printf 'unexpected proton wrapper execution\n' >> "${ATLAS_IMAGE_TEST_HOOK_LOG:?}"
exit 99
EOF
cat > "$tmp/proton-runner" <<'EOF'
#!/usr/bin/env bash
printf 'unexpected proton runner execution\n' >> "${ATLAS_IMAGE_TEST_HOOK_LOG:?}"
exit 99
EOF
chmod +x "$tmp/bin/shorin-proton-wrapper" "$tmp/proton-runner"

agent="$preview_root/turboism-agent.jar"
bundle="$preview_root/atlas-image-observe/bundle.test"
agent_sha256="$(sha256sum "$agent" | cut -d' ' -f1)"
observer_sha256="$(sha256sum "$bundle/atlas-image-load-probe.jar" | cut -d' ' -f1)"
driver_sha256="$(sha256sum "$bundle/atlas-image-scene-driver.jar" | cut -d' ' -f1)"
TURBOISM_HOST_VALIDATION_FIXTURE_5303="$fixture" TURBOISM_WORKTREE_ID="$test_id" \
  bash "$probe_builder" --publish --production-agent "$agent" > "$tmp/publish.log"
published_manifest="$preview_root/atlas-image-observe/bundle.manifest"
test -f "$published_manifest"
grep -q '^runnable=false$' "$published_manifest"
! grep -q 'latest' "$published_manifest"
if TURBOISM_HOST_VALIDATION_FIXTURE_5303="$fixture" TURBOISM_WORKTREE_ID="$test_id" \
    bash "$probe_builder" --publish --production-agent "$agent" \
    > "$tmp/publish-second.log" 2>&1; then
  fail "publish overwrote an existing manifest"
fi

manifest_for_wrapper="$tmp/bundle.manifest"
cat > "$manifest_for_wrapper" <<EOF
schemaVersion=1
scene=atlas-image-observe:5303
worktreeId=$test_id
bundleRoot=$bundle
agent=$agent
agentSha256=$agent_sha256
observer=$bundle/atlas-image-load-probe.jar
observerSha256=$observer_sha256
driver=$bundle/atlas-image-scene-driver.jar
driverSha256=$driver_sha256
fixture=$fixture
fixtureName=$fixture_name
fixtureSha256=$fixture_sha256
officialJarSha256=bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166
runnable=false
EOF

runner_env=(
  "TURBOISM_WORKTREE_ID=$test_id"
  "TURBOISM_HOST_VALIDATION_FIXTURE_5303=$fixture"
  "TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX=$tmp/golden"
  "TURBOISM_HOST_VALIDATION_HOST_ROOT=$tmp/host"
  "TURBOISM_HOST_VALIDATION_PROTON_RUNNER=$tmp/proton-runner"
  "TURBOISM_HOST_VALIDATION_TRANSPORT=local"
  "TURBOISM_QUEUE_RUN_ID=atlas-image-observe-5303-test-001"
  "ATLAS_IMAGE_TEST_HOOK_LOG=$tmp/hook.log"
  "PATH=$tmp/bin:$PATH"
)
run_wrapper() {
  env "${runner_env[@]}" "$wrapper" 5303 offline --bundle-manifest "$manifest_for_wrapper" "$@"
}

run_wrapper --dry-run > "$tmp/dry-run.log"
expected_task_id=atlas-image-observe-5303-test-001
expected_fixture_path="$tmp/host/atlas-image-observe/5303-offline/$expected_task_id/$expected_task_id-$fixture_name"
grep -q '^name=atlas-image-observe$' "$tmp/dry-run.log"
grep -q '^version=5303$' "$tmp/dry-run.log"
grep -q '^auxAgentCount=2$' "$tmp/dry-run.log"
grep -q "^fixtureName=$expected_task_id-$fixture_name$" "$tmp/dry-run.log"
grep -q "^fixturePath=$expected_fixture_path$" "$tmp/dry-run.log"
grep -q "^validationFixtureNameJvmOption=-Dturboism.validation.fixtureName=$expected_task_id-$fixture_name$" "$tmp/dry-run.log"
grep -Fq -- '-Dturboism.validation.atlasImageObserve.home=Z:' "$tmp/dry-run.log"
grep -Fq -- "-Dturboism.validation.atlasImageObserve.taskId=$expected_task_id" "$tmp/dry-run.log"
grep -Fq -- '-Dturboism.validation.atlasImageObserve.fixture=Z:' "$tmp/dry-run.log"
grep -Fq -- "-Dturboism.validation.atlasImageObserve.fixtureName=$expected_task_id-$fixture_name" "$tmp/dry-run.log"
grep -q '^jvmOption.0=-Dturboism.validation.atlasImageObserve.home=Z:' "$tmp/dry-run.log"
grep -q '^jvmOption.3=-Dturboism.validation.atlasImageObserve.fixtureName=' "$tmp/dry-run.log"
grep -q '^remotePreLaunch=$' "$tmp/dry-run.log"
grep -q '^remotePostLaunch=$' "$tmp/dry-run.log"
grep -q '^remotePreCleanup=$' "$tmp/dry-run.log"
grep -q '^clientScript=$' "$tmp/dry-run.log"

# The manifest parser is an exact allow-list, not only a required-key check.
unknown_manifest="$tmp/unknown.manifest"
cp -- "$manifest_for_wrapper" "$unknown_manifest"
printf 'unknownKey=must-fail\n' >> "$unknown_manifest"
if env "${runner_env[@]}" "$wrapper" 5303 offline --bundle-manifest "$unknown_manifest" --dry-run \
    > "$tmp/unknown.log" 2>&1; then
  fail "wrapper accepted an unknown manifest key"
fi
grep -q 'unknown manifest key: unknownKey' "$tmp/unknown.log"

# An ancestor symlink that resolves outside the worktree preview root is rejected after realpath.
outside_agent="$tmp/outside-agent"
mkdir -p "$outside_agent"
printf 'escaped-agent\n' > "$outside_agent/turboism-agent.jar"
ln -s "$outside_agent" "$preview_root/atlas-image-observe/escape"
escape_manifest="$tmp/escape.manifest"
sed "s|^agent=.*|agent=$preview_root/atlas-image-observe/escape/turboism-agent.jar|" \
  "$manifest_for_wrapper" > "$escape_manifest"
if env "${runner_env[@]}" "$wrapper" 5303 offline --bundle-manifest "$escape_manifest" --dry-run \
    > "$tmp/escape.log" 2>&1; then
  fail "wrapper accepted a production agent escaping after realpath"
fi
grep -q 'escapes preview root after realpath' "$tmp/escape.log"

# Full hostless PreparedStore capture -> source/input removal -> snapshot replay.
PYTHONPATH="$root/scripts/preview" python3 - "$root" <<'PY'
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import host_validation_queue as queue


def require(condition, message):
    if not condition:
        raise SystemExit(message)


root = Path(sys.argv[1])
required = (
    "run-cubism-host-validation.sh",
    "host-validation-env.sh",
    "host-validation-transport.sh",
    "archive-cubism-host-evidence.sh",
    "host_validation.py",
    "host_validation_queue.py",
    "host_validation_containment.py",
    "host_validation_evidence.py",
)

with tempfile.TemporaryDirectory(prefix="atlas-image-prepared-replay-") as temporary:
    base = Path(temporary)
    source = base / "synthetic-source"
    source_preview = source / "scripts" / "preview"
    source_preview.mkdir(parents=True)
    for name in required:
        shutil.copy2(root / "scripts" / "preview" / name, source_preview / name)
    subprocess.run(["git", "init", "-q"], cwd=source, check=True)
    subprocess.run(["git", "config", "user.email", "offline@example.invalid"], cwd=source, check=True)
    subprocess.run(["git", "config", "user.name", "offline"], cwd=source, check=True)
    subprocess.run(["git", "add", "."], cwd=source, check=True)
    subprocess.run(["git", "commit", "-qm", "synthetic source"], cwd=source, check=True)

    bundle = base / "bundle"
    bundle.mkdir()
    (bundle / "marker.txt").write_bytes(b"synthetic bundle")
    agent = base / "synthetic-agent.jar"
    agent.write_bytes(b"synthetic agent")
    fixture = base / "synthetic-fixture.cmo3"
    fixture.write_bytes(b"synthetic fixture")
    request = {
        "schemaVersion": queue.SCHEMA,
        "environment": {},
        "argv": [
            "--name", "atlas-image-observe",
            "--version", "5303",
            "--bundle-root", str(bundle),
            "--agent", str(agent),
            "--fixture-local", str(fixture),
            "--fixture-sha256", "0" * 64,
            "--fixture-name", "synthetic-fixture.cmo3",
            "--result-file", "state/atlas-image-observe/result.txt",
            "--result-pass-line", "status=PASS",
            "--result-fail-line", "status=FAIL",
            "--jvm-option", "-Dsynthetic.home={HOME}",
        ],
    }
    store = queue.Store(base / "queue")
    prepared_store = queue.PreparedStore(store)
    prepared = prepared_store.capture(request, source, "atlas-image-observe:5303")
    digest = prepared["digest"]
    prepared_root = store.root / "prepared" / digest

    shutil.rmtree(source)
    shutil.rmtree(bundle)
    agent.unlink(missing_ok=True)
    fixture.unlink(missing_ok=True)

    loaded = prepared_store.load(digest)
    command = prepared_store.command(digest, base / "evidence")
    require("@INPUT@" not in command, f"prepared command contains template input: {command}")
    require(str(source) not in command, f"prepared command references removed source: {command}")
    require(len(command) > 1 and Path(command[1]).is_file(), f"prepared command runner missing: {command}")

    def value_after(flag: str) -> Path:
        require(flag in command, f"prepared command missing {flag}: {command}")
        index = command.index(flag)
        require(index + 1 < len(command), f"prepared command missing value after {flag}: {command}")
        return Path(command[index + 1])

    copied_bundle = value_after("--bundle-root")
    copied_agent = value_after("--agent")
    copied_fixture = value_after("--fixture-local")
    require(
        (copied_bundle / "marker.txt").read_bytes() == b"synthetic bundle",
        "prepared bundle replay mismatch",
    )
    require(copied_agent.read_bytes() == b"synthetic agent", "prepared agent replay mismatch")
    require(copied_fixture.read_bytes() == b"synthetic fixture", "prepared fixture replay mismatch")
    require(prepared_root.is_dir(), "prepared snapshot directory missing")
    require(bool(loaded["sourceInputs"]), "prepared source input inventory missing")
    print(f"PREPARED_STORE_OFFLINE PASS digest={digest} hostLaunched=false")
PY

if [[ -e "$tmp/hook.log" ]]; then
  fail "offline wrapper path executed an external hook"
fi
printf 'ATLAS_IMAGE_HOST_VALIDATION_OFFLINE_TEST PASS evidence=%s hostLaunched=false\n' "$evidence"
