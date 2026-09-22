#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
shape_args=()
if [[ ${1:-} == '--shape' ]]; then
  shape_args+=("-Dturboism.validation.externalpsd.shapeRequired=true")
  shift
fi
[[ $# -eq 0 ]] || { echo 'Usage: test.sh [--shape]' >&2; exit 2; }
bash validation/external-psd-edit-host-probe/build.sh
id="$(bash scripts/dev/worktree-id.sh)"
shopt -s nullglob
sdk=("build/worktree/$id/sdk/libs/"sdk-*.jar)
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 17 -Xlint:all -cp "${sdk[0]}:build/external-psd-edit-host-probe.jar" -d "$out" \
  validation/external-psd-edit-host-probe/test/dev/turboism/validation/externalpsd/PsdValidationContentTest.java \
  validation/external-psd-edit-host-probe/test/dev/turboism/validation/externalpsd/PsdStructuralStateTest.java \
  validation/external-psd-edit-host-probe/test/dev/turboism/validation/externalpsd/PsdStructuralControlTest.java \
  validation/external-psd-edit-host-probe/test/dev/turboism/validation/externalpsd/ExternalPsdEditHostProbeTest.java \
  validation/external-psd-edit-host-probe/test/dev/turboism/validation/externalpsd/ExternalPsdPerformanceSamplerTest.java \
  validation/external-psd-edit-host-probe/test/dev/turboism/validation/externalpsd/ExactHostRowTargetTest.java \
  validation/external-psd-edit-host-probe/test/com/live2d/ui/treeTable/j.java
java -Djava.awt.headless=true -cp "$out:${sdk[0]}:build/external-psd-edit-host-probe.jar" \
  dev.turboism.validation.externalpsd.PsdValidationContentTest
java -Xmx192m -Djava.awt.headless=true -cp "$out:${sdk[0]}:build/external-psd-edit-host-probe.jar" \
  dev.turboism.validation.externalpsd.PsdValidationContentTest --bounded-f1
java -Djava.awt.headless=true -cp "$out:${sdk[0]}:build/external-psd-edit-host-probe.jar" \
  dev.turboism.validation.externalpsd.PsdStructuralStateTest
java -Djava.awt.headless=true -cp "$out:${sdk[0]}:build/external-psd-edit-host-probe.jar" \
  dev.turboism.validation.externalpsd.PsdStructuralControlTest
java -Djava.awt.headless=true -cp "$out:${sdk[0]}:build/external-psd-edit-host-probe.jar" \
  dev.turboism.validation.externalpsd.ExternalPsdEditHostProbeTest
java -Djava.awt.headless=true -cp "$out:${sdk[0]}:build/external-psd-edit-host-probe.jar" \
  dev.turboism.validation.externalpsd.ExternalPsdPerformanceSamplerTest
java -Djava.awt.headless=true "${shape_args[@]}" \
  -cp "$out:${sdk[0]}:build/external-psd-edit-host-probe.jar" \
  dev.turboism.validation.externalpsd.ExactHostRowTargetTest
python3 - "$id" <<'PY'
import pathlib, sys, zipfile, json
expected = {
    'turboism.cubism.model.read', 'turboism.cubism.model.write',
    'turboism.file.read', 'turboism.file.write', 'turboism.process.run',
    'turboism.cubism.model.observe', 'turboism.event.subscribe',
    'turboism.ui.file-chooser.request'}
with zipfile.ZipFile('build/external-psd-edit-host-probe.jar') as archive:
    names = archive.namelist()
    assert 'com/live2d/ui/treeTable/j.class' not in names
    assert not any(name.startswith('com/live2d/') for name in names)
    metadata = json.loads(archive.read('META-INF/turboism/plugin.json'))
    assert {p['id'] for p in metadata['permissions']} == expected
    assert metadata['entrypoints'] == [
        'dev.turboism.validation.externalpsd.ExternalPsdEditHostProbe']
print('PASS: probe declares only the pipeline permissions it exercises')
bundle = pathlib.Path('build/preview') / sys.argv[1]
if bundle.is_dir():
    for jar in bundle.rglob('*.jar'):
        with zipfile.ZipFile(jar) as archive:
            assert not any('dev/turboism/validation/externalpsd/' in n
                           for n in archive.namelist()), jar
    print('PASS: probe absent from production preview artifacts')
else:
    print('SKIP: preview bundle not built in this worktree')
PY
python3 - "$root/scripts/preview/run-external-psd-edit-host-validation.sh" <<'PY'
import os
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path


wrapper_source = Path(sys.argv[1]).resolve()
expected_ready = [
    "Context-menu transform applied to "
    "com/live2d/cubism/view/palette/deformer/b appendPoints=11",
    "Context-menu transform applied to "
    "com/live2d/cubism/view/palette/parts/T appendPoints=22",
    "Turboism Developer Preview started",
    "EXTERNAL_PSD_EDIT_GUI_TRIGGER_ARMED",
]
hook_failure = "Turboism object context-menu hook disabled safely"
global_failure = "EXTERNAL_PSD_EDIT_RESULT status=BLOCKED"
fixed_trigger = "state/dev.turboism.validation.externalpsd/gui-ready.flag"


def option_values(argv, option):
    return [argv[index + 1] for index, value in enumerate(argv[:-1])
            if value == option]


def executable(path, contents):
    path.write_text(contents, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def run_wrapper(sandbox, wrapper, runner, stub_bin, fixture, plugin, phase,
                capture, extra_args=(), content_profile=None, structural_variant=None,
                expected_code=0):
    environment = os.environ.copy()
    environment.update({
        "PATH": str(stub_bin) + os.pathsep + environment.get("PATH", ""),
        "RUNNER_PATH": str(runner),
        "CAPTURE": str(capture),
        "EXTERNAL_PSD_PHASE": phase,
        "EXTERNAL_PSD_FIXTURE_LOCAL": str(fixture),
    })
    if phase == "gui":
        environment["EXTERNAL_PSD_WITH_PLUGIN"] = str(plugin)
    else:
        environment.pop("EXTERNAL_PSD_WITH_PLUGIN", None)
    if content_profile is None:
        environment.pop("EXTERNAL_PSD_CONTENT_PROFILE", None)
    else:
        environment["EXTERNAL_PSD_CONTENT_PROFILE"] = content_profile
    for name in ("EXTERNAL_PSD_STRUCTURE_SOURCE", "EXTERNAL_PSD_STRUCTURE_VARIANT"):
        environment.pop(name, None)
    if structural_variant is not None:
        environment["EXTERNAL_PSD_STRUCTURE_VARIANT"] = structural_variant
        environment["EXTERNAL_PSD_STRUCTURE_SOURCE"] = str(sandbox / "variant.psd")
    completed = subprocess.run(
        ["/bin/bash", str(wrapper), *extra_args],
        cwd=sandbox,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    assert completed.returncode == expected_code, (
        f"{phase} wrapper failed: rc={completed.returncode}\n"
        f"stdout={completed.stdout}\nstderr={completed.stderr}"
    )
    if expected_code:
        assert not capture.exists(), "rejected structural request invoked Runner"
        return []
    assert capture.is_file(), f"{phase} did not invoke the shared Runner stub"
    return capture.read_text(encoding="utf-8").splitlines()


with tempfile.TemporaryDirectory(prefix="external-psd-wrapper-argv-") as temporary:
    sandbox = Path(temporary)
    preview = sandbox / "scripts" / "preview"
    dev = sandbox / "scripts" / "dev"
    stub_bin = sandbox / "stub-bin"
    preview.mkdir(parents=True)
    dev.mkdir(parents=True)
    stub_bin.mkdir()

    wrapper = preview / "run-external-psd-edit-host-validation.sh"
    shutil.copy2(wrapper_source, wrapper)
    executable(preview / "host-validation-env.sh", """
fixture_src="$PWD/fixture.cmo3"
fixture_sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
turboism_select_fixture() {
  fixture_src="$PWD/fixture.cmo3"
  fixture_sha256=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
}
""")
    executable(dev / "worktree-id.sh", """#!/bin/sh
printf '%s\\n' wrapper-argv-test
""")
    runner = preview / "run-cubism-host-validation.sh"
    executable(runner, """#!/bin/sh
exit 99
""")
    executable(stub_bin / "bash", """#!/bin/sh
set -eu
if [ "${1:-}" = "${RUNNER_PATH:?}" ]; then
  shift
  printf '%s\\n' "$@" > "${CAPTURE:?}"
  exit 0
fi
exec /bin/bash "$@"
""")
    fixture = sandbox / "fixture.cmo3"
    fixture.write_bytes(b"test fixture")
    plugin = sandbox / "external-psd-edit.jar"
    plugin.write_bytes(b"test plugin")

    gui_args = run_wrapper(
        sandbox, wrapper, runner, stub_bin, fixture, plugin, "gui",
        sandbox / "gui.argv", ("--trigger", "caller-override.flag"))
    assert option_values(gui_args, "--ready-marker") == expected_ready, gui_args
    failures = option_values(gui_args, "--failure-marker")
    assert failures.count(hook_failure) == 1, failures
    assert failures.count(global_failure) == 1, failures
    triggers = option_values(gui_args, "--trigger")
    assert triggers == ["caller-override.flag", fixed_trigger], triggers
    assert gui_args[-2:] == ["--trigger", fixed_trigger], gui_args[-4:]
    effective_trigger = triggers[-1]  # shared Runner's last occurrence is authoritative
    assert effective_trigger == fixed_trigger, triggers

    for phase in ("pipeline", "reopen"):
        phase_args = run_wrapper(
            sandbox, wrapper, runner, stub_bin, fixture, plugin, phase,
            sandbox / f"{phase}.argv")
        assert option_values(phase_args, "--ready-marker") == [], phase_args
        assert option_values(phase_args, "--trigger") == [], phase_args
        assert option_values(phase_args, "--failure-marker") == [global_failure], phase_args

    f1_args = run_wrapper(
        sandbox, wrapper, runner, stub_bin, fixture, plugin, "pipeline",
        sandbox / "f1.argv", content_profile="f1")
    jvm_options = option_values(f1_args, "--jvm-option")
    assert "-Dturboism.validation.externalpsd.contentProfile=f1" in jvm_options, jvm_options
    assert option_values(f1_args, "--ready-marker") == [], f1_args
    assert option_values(f1_args, "--trigger") == [], f1_args

    (sandbox / "variant.psd").write_bytes(b"fixed test-only input")
    for phase in ("structure-native", "structure-sdk"):
        for variant in ("add", "delete", "merge", "canvas"):
            args = run_wrapper(sandbox, wrapper, runner, stub_bin, fixture, plugin, phase,
                sandbox / f"{phase}-{variant}.argv", content_profile="f1", structural_variant=variant)
            assert option_values(args, "--home-file") == [
                str(sandbox / "variant.psd") + ":structural-input/external-edit.psd"], args
            assert "-Dturboism.validation.externalpsd.structureVariant=" + variant in option_values(args, "--jvm-option")
            assert "-Dturboism.preview.userFileFixedGrant={HOME}/persisted-document.cmo3" in option_values(args, "--jvm-option")
            assert not option_values(args, "--trigger"), args
        for profile, variant in (("control7", "add"), ("f1", "unknown"), ("f1", None)):
            run_wrapper(sandbox, wrapper, runner, stub_bin, fixture, plugin, phase,
                sandbox / f"reject-{phase}-{profile}-{variant}.argv", content_profile=profile,
                structural_variant=variant, expected_code=2)

print("PASS: external PSD wrapper argv enforces GUI readiness and separate structural controls")
PY
