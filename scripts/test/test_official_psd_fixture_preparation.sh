#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
wrapper_source="$root/scripts/preview/run-external-psd-fixture-preparation.sh"
env_source="$root/scripts/preview/host-validation-env.sh"

python3 - "$wrapper_source" "$env_source" <<'PY'
import os
import shutil
import stat
import subprocess
import tempfile
from pathlib import Path

wrapper_source = Path(os.path.abspath(os.sys.argv[1]))
env_source = Path(os.path.abspath(os.sys.argv[2]))
fixture_sha = "8b760eb0b6ac5839271210aa0efc681f6a02a6537c1ab97d40a3a56879d8f02c"
rgb_sha = "12eca5a1c8d8b9096384c974d52e8f0310c4ad9ac4ea87a072684096cf2b808d"


def executable(path: Path, contents: str) -> None:
    path.write_text(contents, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def option_values(argv, option):
    return [argv[index + 1] for index, value in enumerate(argv[:-1]) if value == option]


with tempfile.TemporaryDirectory(prefix="official-psd-preparation-argv-") as temporary:
    sandbox = Path(temporary)
    preview = sandbox / "scripts" / "preview"
    dev = sandbox / "scripts" / "dev"
    stub_bin = sandbox / "stub-bin"
    fixture = sandbox / "build" / "host-validation" / "025-negative-source" / "queue-81a9c6c0640c4e39a4012fe5dd486b44" / "native-seven-layer.psd"
    preview.mkdir(parents=True)
    dev.mkdir(parents=True)
    stub_bin.mkdir()
    fixture.parent.mkdir(parents=True)
    fixture.write_bytes(b"offline argv fixture placeholder")

    shutil.copy2(wrapper_source, preview / wrapper_source.name)
    shutil.copy2(env_source, preview / env_source.name)
    executable(dev / "worktree-id.sh", "#!/bin/sh\nprintf '%s\\n' wrapper-test\n")
    runner = preview / "run-cubism-host-validation.sh"
    runner.write_text("#!/bin/sh\nexit 99\n", encoding="utf-8")
    runner.chmod(runner.stat().st_mode | stat.S_IXUSR)

    executable(stub_bin / "bash", """#!/bin/sh
set -eu
if [ "${1:-}" = "${RUNNER_PATH:?}" ]; then
  shift
  printf '%s\\n' "$@" > "${CAPTURE:?}"
  exit 0
fi
exec /bin/bash "$@"
""")
    executable(stub_bin / "sha256sum", f"""#!/bin/sh
set -eu
printf '%s  %s\\n' '{fixture_sha}' "${{2:-$1}}"
""")

    capture = sandbox / "runner.argv"
    environment = os.environ.copy()
    environment.update({
        "PATH": str(stub_bin) + os.pathsep + environment.get("PATH", ""),
        "RUNNER_PATH": str(runner),
        "CAPTURE": str(capture),
        "TURBOISM_ENV_FILE": str(sandbox / "empty.env"),
    })
    (sandbox / "empty.env").write_text("", encoding="utf-8")
    completed = subprocess.run(
        ["/bin/bash", str(preview / wrapper_source.name), "--dry-run"],
        cwd=sandbox,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    assert completed.returncode == 0, (
        f"wrapper failed: rc={completed.returncode}\n"
        f"stdout={completed.stdout}\nstderr={completed.stderr}"
    )
    argv = capture.read_text(encoding="utf-8").splitlines()

    assert option_values(argv, "--name") == ["external-psd-fixture-preparation"], argv
    assert option_values(argv, "--run-label") == ["025-t021"], argv
    assert option_values(argv, "--version") == ["5302"], argv
    assert option_values(argv, "--fixture-name") == ["native-seven-layer.psd"], argv
    assert option_values(argv, "--fixture-sha256") == [fixture_sha], argv
    assert argv.count("--require-fixture-unchanged") == 1, argv
    plugins = option_values(argv, "--plugin")
    assert plugins == [str(sandbox / "build" / "external-psd-edit-host-probe.jar")
                       + ":external-psd-edit-host-probe.jar"], plugins
    jvm = option_values(argv, "--jvm-option")
    expected_jvm = {
        "-Dturboism.validation.externalpsd.phase=prepare-fixture",
        "-Dturboism.validation.externalpsd.runId={TASK_ID}",
        "-Dturboism.validation.externalpsd.prepare.fixture={FIXTURE}",
        f"-Dturboism.validation.externalpsd.prepare.fixtureSha256={fixture_sha}",
        "-Dturboism.validation.externalpsd.prepare.fixtureName={FIXTURE_NAME}",
        "-Dturboism.validation.externalpsd.prepare.runId={TASK_ID}",
        "-Dturboism.validation.externalpsd.prepare.taskId={TASK_ID}",
        "-Dturboism.validation.externalpsd.prepare.savedCopy={HOME}/prepared-control.cmo3",
        f"-Dturboism.validation.externalpsd.prepare.targetRgbSha256={rgb_sha}",
        "-Dturboism.validation.externalpsd.prepare.hostVersion=5.3.02",
        "-Dturboism.validation.externalpsd.prepare.timeoutMillis=180000",
        "-Dturboism.preview.userFileFixedGrant={HOME}/prepared-control.cmo3",
    }
    assert set(jvm) == expected_jvm, jvm
    assert option_values(argv, "--result-file") == [
        "state/dev.turboism.validation.externalpsd/external-psd-edit-result.properties"
    ], argv
    assert option_values(argv, "--result-pass-line") == ["status=PASS"], argv
    assert option_values(argv, "--result-fail-line") == ["status=FAIL"], argv
    assert "--plugin" in argv and "--home-file" not in argv, argv
    assert all("external-psd.jar" not in value for value in plugins), plugins

print("PASS: official PSD fixture preparation wrapper argv")
PY
