"""Native Windows texture validation. Isolated official BAT, model copy, Job Object.

Run one case at a time. No builds or other Cubism sessions during timing.
All paths and raw evidence belong in ignored local output, never committed.
"""
from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes as wt
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import time
import zipfile

REPO = Path(__file__).resolve().parents[4]


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def save(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def properties(path: Path) -> dict[str, str]:
    result = {}
    if path.exists():
        for line in path.read_text(encoding="latin-1").splitlines():
            if line and not line.startswith(("#", "!")) and "=" in line:
                key, value = line.split("=", 1)
                result[key] = value
    return result


def stage_launcher(source: Path, host: Path, destination: Path) -> None:
    data = source.read_bytes()
    if b"TURBOISM MANAGED" in data or b"launch-cubism-turboism" in data:
        raise ValueError("refusing a managed BAT")
    if b"%~dp0" not in data or b"com.live2d.cubism.CECubismEditorApp" not in data:
        raise ValueError("unrecognized official BAT")
    # The only modification is rebasing the pristine BAT's installation directory.
    destination.write_bytes(data.replace(b"%~dp0", (str(host) + "\\").encode("ascii")))


def factory_contract(version: str, host_jar: Path, agent_jar: Path) -> dict[str, str]:
    """Bind the probe to the same frozen version pin shipped in the product agent."""
    if len(version) != 4 or not version.isdigit():
        raise ValueError("expected a reviewed four-digit version code")
    dotted = version[0] + "." + version[1] + "." + version[2:]
    resource = "dev/turboism/adapter/cubism/class-pins/texture-upload-preparation.json"
    with zipfile.ZipFile(agent_jar) as agent:
        encoded = agent.read(resource)
    pins = json.loads(encoded).get(dotted, {})
    if len(pins) != 1:
        raise ValueError("no single reviewed texture factory for " + dotted)
    owner, expected = next(iter(pins.items()))
    if not re.fullmatch(r"com/live2d/graphics3d/shader/[A-Za-z_$][A-Za-z0-9_$]*", owner):
        raise ValueError("unexpected texture factory package")
    with zipfile.ZipFile(host_jar) as host:
        actual = hashlib.sha256(host.read(owner + ".class")).hexdigest()
    if actual != expected:
        raise ValueError("texture factory differs from the product's reviewed pin")
    return {"owner": owner.replace("/", "."), "classSha256": actual,
            "pinResourceSha256": hashlib.sha256(encoded).hexdigest()}


def runtime_ready(home: Path) -> bool:
    return any("Turboism Developer Preview started" in path.read_text(encoding="utf-8", errors="replace")
               for path in (home / "logs").rglob("*.log"))


def copy_seed_state(seed: Path, destination: Path) -> None:
    """Copies settings/license state without stale temp trees or user projects."""
    if not seed.exists():
        return
    shutil.copytree(
        seed,
        destination / "Live2D",
        ignore=shutil.ignore_patterns("backup", "cache", "*.cmo3", "*.can3", "*.bak", "*.log", ".tmp-stale-*"),
        ignore_dangling_symlinks=True,
    )


def java_options(options: list[str]) -> str:
    if any('"' in value or "\n" in value or "\r" in value for value in options):
        raise ValueError("unsafe JVM option")
    return " ".join('"' + value + '"' for value in options)


class Metrics:
    """Read-only, handle-bound Windows process accounting; working set is not Linux RSS."""

    def __init__(self) -> None:
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.psapi = ctypes.WinDLL("psapi", use_last_error=True)
        k = self.kernel
        k.CreateToolhelp32Snapshot.argtypes = [wt.DWORD, wt.DWORD]
        k.CreateToolhelp32Snapshot.restype = wt.HANDLE
        k.OpenProcess.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
        k.OpenProcess.restype = wt.HANDLE
        k.CloseHandle.argtypes = [wt.HANDLE]
        k.GetExitCodeProcess.argtypes = [wt.HANDLE, ctypes.POINTER(wt.DWORD)]
        k.GetExitCodeProcess.restype = wt.BOOL
        k.GetProcessTimes.argtypes = [wt.HANDLE] + [ctypes.POINTER(wt.FILETIME)] * 4
        k.QueryFullProcessImageNameW.argtypes = [wt.HANDLE, wt.DWORD, wt.LPWSTR, ctypes.POINTER(wt.DWORD)]

    def running(self, pid: int) -> bool:
        """Filters Toolhelp's exited process objects; an unreadable process stays active (fail closed)."""
        handle = self.kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            return True
        try:
            code = wt.DWORD()
            if not self.kernel.GetExitCodeProcess(handle, ctypes.byref(code)):
                return True
            return code.value == 259  # STILL_ACTIVE
        finally:
            self.kernel.CloseHandle(handle)

    def processes(self) -> list[tuple[int, int, str]]:
        class Entry(ctypes.Structure):
            _fields_ = [("dwSize", wt.DWORD), ("cntUsage", wt.DWORD), ("th32ProcessID", wt.DWORD),
                        ("th32DefaultHeapID", ctypes.c_size_t), ("th32ModuleID", wt.DWORD),
                        ("cntThreads", wt.DWORD), ("th32ParentProcessID", wt.DWORD),
                        ("pcPriClassBase", wt.LONG), ("dwFlags", wt.DWORD), ("szExeFile", wt.WCHAR * 260)]
        self.kernel.Process32FirstW.argtypes = [wt.HANDLE, ctypes.POINTER(Entry)]
        self.kernel.Process32NextW.argtypes = [wt.HANDLE, ctypes.POINTER(Entry)]
        snapshot = self.kernel.CreateToolhelp32Snapshot(2, 0)
        if snapshot == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        result = []
        try:
            entry = Entry()
            entry.dwSize = ctypes.sizeof(entry)
            more = self.kernel.Process32FirstW(snapshot, ctypes.byref(entry))
            while more:
                if self.running(entry.th32ProcessID):
                    result.append((entry.th32ProcessID, entry.th32ParentProcessID, entry.szExeFile))
                more = self.kernel.Process32NextW(snapshot, ctypes.byref(entry))
        finally:
            self.kernel.CloseHandle(snapshot)
        return result

    def open(self, pid: int, expected: Path) -> int:
        handle = self.kernel.OpenProcess(0x0400 | 0x0010, False, pid)
        if not handle:
            raise ctypes.WinError(ctypes.get_last_error())
        path, size = ctypes.create_unicode_buffer(32768), wt.DWORD(32768)
        if not self.kernel.QueryFullProcessImageNameW(handle, 0, path, ctypes.byref(size)):
            self.kernel.CloseHandle(handle)
            raise ctypes.WinError(ctypes.get_last_error())
        if Path(path.value).resolve() != expected.resolve():
            self.kernel.CloseHandle(handle)
            raise ValueError("task Java executable differs from bundled runtime")
        return handle

    def sample(self, handle: int) -> dict:
        class Memory(ctypes.Structure):
            _fields_ = [("cb", wt.DWORD), ("PageFaultCount", wt.DWORD)] + [
                (name, ctypes.c_size_t) for name in ("PeakWorkingSetSize", "WorkingSetSize",
                "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage", "PrivateUsage")]
        created, exited, kernel, user = (wt.FILETIME() for _ in range(4))
        if not self.kernel.GetProcessTimes(handle, *(ctypes.byref(t) for t in (created, exited, kernel, user))):
            raise ctypes.WinError(ctypes.get_last_error())
        memory = Memory()
        memory.cb = ctypes.sizeof(memory)
        self.psapi.GetProcessMemoryInfo.argtypes = [wt.HANDLE, ctypes.POINTER(Memory), wt.DWORD]
        if not self.psapi.GetProcessMemoryInfo(handle, ctypes.byref(memory), memory.cb):
            raise ctypes.WinError(ctypes.get_last_error())
        def ticks(t):
            return (t.dwHighDateTime << 32) | t.dwLowDateTime
        return {"epochNs": time.time_ns(), "creationTicks": ticks(created),
                "cpuNs": (ticks(kernel) + ticks(user)) * 100,
                "workingSetBytes": memory.WorkingSetSize, "peakWorkingSetBytes": memory.PeakWorkingSetSize,
                "privateCommitBytes": memory.PrivateUsage, "peakCommitBytes": memory.PeakPagefileUsage}


def official_command(comspec: str, bat: str, fixture: str) -> str:
    for value in (comspec, bat, fixture):
        if any(character in value for character in '\"%!?&|<>^\n\r'):
            raise ValueError("unsafe launcher path")
    return '"' + comspec + '" /d /s /c ""' + bat + '" "' + fixture + '""'


def launch_payload(request_path: Path) -> int:
    request = json.loads(request_path.read_text(encoding="utf-8"))
    gate = Path(request["gate"])
    deadline = time.monotonic() + 30
    while not gate.exists():
        if time.monotonic() > deadline:
            raise TimeoutError("Job Object admission gate not released")
        time.sleep(.02)
    # The parent assigns this waiting Python process to its Job Object before releasing the gate.
    # cmd.exe uses its own /s /c quote grammar, not the CRT argv quoting used
    # by subprocess(list). Passing a list would introduce literal backslash-quotes.
    command = official_command(os.environ["COMSPEC"], request["bat"], request["fixture"])
    return subprocess.call(command, cwd=request["host"])


def run(args: argparse.Namespace) -> dict:
    if os.name != "nt":
        raise RuntimeError("this entry point requires native Windows")
    sys.path.insert(0, str(REPO / "scripts/preview"))
    import host_validation_containment as containment
    from reviewed_host_artifacts import sha256 as reviewed_artifact_sha256

    host, fixture, agent, auxiliary = (Path(p).resolve(strict=True) for p in
                                     (args.host, args.fixture, args.agent, args.auxiliary))
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    home = output / "home"
    home.mkdir()
    (home / "plugins").mkdir()
    state = home / "state/texture-upload"
    state.mkdir(parents=True)
    official = host / "CubismEditor5.bat"
    if b"TURBOISM MANAGED" in official.read_bytes():
        official = host / "CubismEditor5.bat.turboism-original.bak"
    host_jar = host / "app/lib/Live2D_Cubism.jar"
    jogl = host / "app/lib/jogl/jogl-all.jar"
    expected = reviewed_artifact_sha256(args.version)
    if digest(host_jar) != expected:
        raise ValueError("official host JAR does not match reviewed version")
    factory = factory_contract(args.version, host_jar, agent)
    if digest(jogl) != "7dbedb4ba89d9744aa8f8e3710436ef349547058c78e5b3beb813b27b382b0b9":
        raise ValueError("JOGL artifact does not match reviewed texture contract")
    metrics = Metrics()
    active = [p for p in metrics.processes() if p[2].lower() in ("java.exe", "javaw.exe", "cubismeditor5.exe")]
    if active:
        raise RuntimeError(f"quiet host required; existing Java/Cubism PIDs: {[p[0] for p in active]}")
    inputs = {"fixture": fixture, "officialBat": official, "installedBat": host / "CubismEditor5.bat",
              "hostJar": host_jar, "jogl": jogl, "agent": agent, "auxiliary": auxiliary}
    staged_experiment = None
    if args.experiment_agent:
        inputs["experimentAgent"] = Path(args.experiment_agent).resolve(strict=True)
        staged_experiment = output / "experiment.jar"
        shutil.copyfile(inputs["experimentAgent"], staged_experiment)
    before = {name: digest(path) for name, path in inputs.items()}
    model = output / "fixture.cmo3"
    shutil.copyfile(fixture, model)
    if digest(model) != before["fixture"]:
        raise ValueError("fixture copy differs")
    staged_agent, staged_aux = output / "agent.jar", output / "auxiliary.jar"
    shutil.copyfile(agent, staged_agent)
    shutil.copyfile(auxiliary, staged_aux)
    shutil.copyfile(REPO / "testing/host-validation/texture-upload/config.json", home / "config.json")
    bat = output / "official.bat"
    stage_launcher(official, host, bat)
    # Copy settings/license state as local-only data. Never copy user projects or caches.
    seed = Path(os.environ["APPDATA"]) / "Live2D"
    roaming, local, profile = output / "roaming", output / "local", output / "profile"
    for path in (roaming, local, profile):
        path.mkdir()
    copy_seed_state(seed, roaming)
    enabled = args.mode != "off"
    options = ["-Djava.locale.providers=CLDR,SPI", f"-Duser.home={profile}", f"-Dturboism.home={home}",
               f"-Dturboism.optimization.textureUploadPreparation={str(enabled).lower()}",
               "-Dturboism.optimization.floatArrayParseCache=false", "-Dturboism.optimization.imageArchiveReuse=false",
               f"-Dturboism.validation.textureUpload.shadow={str(args.mode == 'shadow').lower()}",
               f"-Dturboism.validation.textureUpload.home={home}",
               f"-Dturboism.validation.textureUpload.factoryOwner={factory['owner']}",
               f"-Dturboism.validation.imageArchive.home={home}",
               "-Dturboism.validation.fixtureName=fixture.cmo3",
               "-Dturboism.validation.imageArchive.fixtureName=fixture.cmo3",
               f"-Dturboism.validation.runId={output.name}",
               "-Dturboism.validation.textureUpload.loadingTrace=true",
               "-Dturboism.validation.textureUpload.memoryIdleSeconds=30",
               *(["-Dturboism.validation.dismissTrialWelcome=true"] if args.dismiss_trial else []),
               f"-javaagent:{staged_aux}",
               f"-javaagent:{staged_agent}=home={home};timeoutSeconds=180"]
    if staged_experiment:
        options.insert(-2, f"-javaagent:{staged_experiment}")
        options.extend([
            f"-Dturboism.validation.hotspotExperiment={args.experiment}",
            f"-Dturboism.validation.hotspotShadow={str(args.experiment_shadow).lower()}",
            f"-Dturboism.validation.hotspotHostJar={host_jar}",
            "-Dturboism.optimization.warpPositionProjection=false",
            "-Dturboism.optimization.extendedInterpolationIncrementalSetup=false",
        ])
    if args.profile:
        options.extend([
            f"-XX:StartFlightRecording=name=texture-load,settings=profile,filename={output / 'loading.jfr'},dumponexit=true,maxsize=256m",
            "-XX:FlightRecorderOptions=stackdepth=96",
        ])
    env = dict(os.environ, APPDATA=str(roaming), LOCALAPPDATA=str(local), JAVA_TOOL_OPTIONS=java_options(options))
    for key in ("_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH", "TURBOISM_HOME"):
        env.pop(key, None)
    gate = output / "launch.flag"
    request = output / "launch.json"
    save(request, {"gate": str(gate), "bat": str(bat), "fixture": str(model), "host": str(host)})
    record = {"schemaVersion": 1, "runId": output.name, "mode": args.mode, "arm": args.arm,
              "version": args.version, "textureFactory": factory, "profiling": args.profile,
              "experiment": args.experiment, "experimentShadow": args.experiment_shadow,
              "dismissTrial": args.dismiss_trial,
              "sourceRevision": subprocess.check_output(
                  ["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
              "sourceDiffSha256": hashlib.sha256(subprocess.check_output(["git", "diff"], cwd=REPO)).hexdigest(),
              "before": before, "options": options, "status": "RUNNING", "fixtureBytes": fixture.stat().st_size,
              "runnerSha256": digest(Path(__file__)), "pythonVersion": sys.version,
              "stagedAgentSha256": digest(staged_agent), "stagedAuxSha256": digest(staged_aux),
              "stagedLauncherSha256": digest(bat), "timingBoundary": "aux premain to stable task document; staged warm file cache"}
    save(output / "envelope.json", record)
    process = None
    java_handle = None
    last = None
    started = time.monotonic()
    result = state / "result.properties"
    load = state / "load.properties"
    memory = state / "memory"
    memory.mkdir(exist_ok=True)
    try:
        with (output / "admission.lock").open("wb") as lock, (output / "console.log").open("wb") as console:
            process = containment.start([sys.executable, "-I", str(Path(__file__).resolve()), "_launch", str(request)],
                directory=output / "containment", environment=env, admission_fd=lock.fileno(),
                identity={"jobId": output.name, "attemptId": "1", "runId": output.name,
                          "preparedDigest": before["agent"]}, output=console)
            gate.touch()
            with (output / "process.jsonl").open("w", encoding="utf-8") as samples:
                deadline = time.monotonic() + args.timeout
                while time.monotonic() < deadline:
                    if result.exists():
                        break
                    if java_handle is None:
                        all_processes = metrics.processes()
                        descendants = {process.process.pid}
                        for _ in range(8):
                            descendants.update(pid for pid, parent, _ in all_processes if parent in descendants)
                        java = [pid for pid, _, name in all_processes
                                if pid in descendants and name.lower() == "java.exe"]
                        if len(java) > 1:
                            raise RuntimeError("multiple Java descendants")
                        if java:
                            java_handle = metrics.open(java[0], host / "app/jre/bin/java.exe")
                            record["javaPid"] = java[0]
                            (memory / "attached.properties").write_text("status=PASS\n", encoding="ascii")
                    if java_handle is not None:
                        last = metrics.sample(java_handle)
                        last["elapsedSeconds"] = time.monotonic() - started
                        last["loadPublished"] = load.exists()
                        samples.write(json.dumps(last) + "\n")
                        samples.flush()
                    if (memory / "end.properties").exists() and not (memory / "complete.properties").exists():
                        if staged_experiment:
                            witness = state / "experiment-verify.properties"
                            if witness.exists():
                                verdict = properties(witness).get("status", "FAIL")
                                (memory / "complete.properties").write_text(f"status={verdict}\n", encoding="ascii")
                        else:
                            (memory / "complete.properties").write_text("status=PASS\n", encoding="ascii")
                    if not (state / "start.flag").exists() and runtime_ready(home):
                        (state / "start.flag").touch()
                    if result.exists():
                        break
                    if process.process.poll() is not None:
                        raise RuntimeError("launcher exited before terminal result")
                    time.sleep(.25)
                else:
                    raise TimeoutError("no terminal texture result within timeout")
            validation = properties(result)
            record["validation"] = validation
            record["load"] = properties(load)
            if staged_experiment:
                record["experimentLoad"] = properties(state / "experiment-load.properties")
                record["experimentVerify"] = properties(state / "experiment-verify.properties")
                if record["experimentVerify"].get("status") != "PASS":
                    raise RuntimeError("experiment correctness/restoration failed")
            if validation.get("status") != "PASS":
                raise RuntimeError("auxiliary validation failed: " + str(validation))
            process.process.wait(timeout=120)
            record["normalExitCode"] = process.process.returncode
            if process.process.returncode != 0:
                raise RuntimeError("official launcher exit was not zero")
            record["status"] = "PASS"
    except BaseException as error:
        record["status"] = "FAIL"
        record["failure"] = repr(error)
        raise
    finally:
        if java_handle:
            metrics.kernel.CloseHandle(java_handle)
        if process:
            cleanup = process.finish(timeout_seconds=1)
            save(output / "cleanup.json", cleanup)
            process.close()
            record["cleanup"] = cleanup["cleanup"]
            if cleanup["cleanup"] != "safe":
                record["status"] = "FAIL"
        after = {name: digest(path) for name, path in inputs.items()}
        record["after"] = after
        record["inputsUnchanged"] = before == after
        record["copyUnchanged"] = digest(model) == before["fixture"]
        record["stagedArtifactsUnchanged"] = (digest(staged_agent) == before["agent"]
                                               and digest(staged_aux) == before["auxiliary"]
                                               and (not staged_experiment or digest(staged_experiment) == before["experimentAgent"]))
        if not all(record[k] for k in ("inputsUnchanged", "copyUnchanged", "stagedArtifactsUnchanged")):
            record["status"] = "FAIL"
        record["wallSecondsIncludingIdleAndExit"] = time.monotonic() - started
        save(output / "envelope.json", record)
    if record["status"] != "PASS":
        raise RuntimeError("run failed acceptance; see envelope.json")
    return record


def main() -> None:
    if len(sys.argv) == 3 and sys.argv[1] == "_launch":
        raise SystemExit(launch_payload(Path(sys.argv[2])))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--version", default="5304")
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--agent", required=True)
    parser.add_argument("--auxiliary", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--arm", choices=("native", "legacy", "direct"), required=True)
    parser.add_argument("--mode", choices=("off", "on", "shadow"), required=True)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--profile", action="store_true", help="diagnostic JFR only; never a performance sample")
    parser.add_argument("--experiment-agent", help="private, exact-host experiment probe")
    parser.add_argument("--experiment", choices=("none", "large", "png", "archive", "warp", "float"), default="none")
    parser.add_argument("--experiment-shadow", action="store_true")
    parser.add_argument("--dismiss-trial", action="store_true",
                        help="test-only: dismiss the official trial welcome dialog before opening the fixture")
    args = parser.parse_args()
    if (args.experiment != "none" or args.experiment_shadow) and not args.experiment_agent:
        parser.error("experiments require the isolated probe")
    if (args.arm == "native") != (args.mode == "off"):
        parser.error("native arm requires off; legacy/direct arms require on or shadow")
    if not 30 <= args.timeout <= 1200:
        parser.error("timeout must be 30..1200 seconds")
    if os.name != "nt":
        parser.error("native Windows required")
    import msvcrt
    lock_path = Path(os.environ["LOCALAPPDATA"]) / "TurboismValidation/performance.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+b") as lock:
        if lock.tell() == 0:
            lock.write(b"0")
            lock.flush()
        lock.seek(0)
        msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        try:
            record = run(args)
        except BaseException as error:
            import traceback
            output = Path(args.output)
            if output.is_dir():
                save(output / "failure.json", {"failure": repr(error), "traceback": traceback.format_exc()})
            raise
        finally:
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
    print(json.dumps({"status": record["status"], "runId": record["runId"], "load": record["load"]}))


if __name__ == "__main__":
    main()
