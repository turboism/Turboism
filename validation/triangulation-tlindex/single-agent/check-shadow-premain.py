#!/usr/bin/env python3
"""Verify actual canonical Turboism premain and the 5303 cold-shadow order; never launch Editor."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--official-dir", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--ui-off", type=Path, required=True)
    parser.add_argument("--ui-on", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cases", default="on,off,missing-token,wrong-source,wrong-shadow-sha,misordered,unsupported,bad-scene-config")
    args = parser.parse_args()
    candidate = args.candidate.resolve()
    receipt = json.loads((candidate / "candidate.json").read_text())
    assert sha(candidate / "turboism-agent.jar") == receipt["agentSha256"]
    assert sha(args.fixture) == "029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c"
    official = args.official_dir.resolve() / "Live2D_Cubism.jar"
    assert sha(official) == "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166"
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    template = json.loads((Path(__file__).resolve().parents[3] / "build/t050-lazy-edge-bytecode/memory-registry-r1/metadata/on5302/command.json").read_text())["argv"]
    env = dict(os.environ)
    for name in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JDK_JAVAC_OPTIONS", "CLASSPATH"):
        env.pop(name, None)
    results = []
    input_pins = {str(official): sha(official), str(args.fixture.resolve()): sha(args.fixture)}
    for case in args.cases.split(","):
        if case not in {"on", "off", "missing-token", "wrong-source", "wrong-shadow-sha", "misordered", "unsupported", "bad-scene-config"}:
            raise ValueError("unknown case: " + case)
        enabled = case != "off"
        directory = out / case
        home = directory / "home"
        validation = home / "validation"
        validation.mkdir(parents=True)
        agent = home / "turboism-agent.jar"
        shutil.copyfile(candidate / "turboism-agent.jar", agent)
        for file in (candidate / "sidecars").iterdir():
            shutil.copyfile(file, validation / file.name)
        ui = args.ui_on if enabled else args.ui_off
        shutil.copyfile(ui, home / "config.json")
        fixture = directory / "offline-t050-producer-heavy.cmo3"
        shutil.copyfile(args.fixture, fixture)
        if case == "misordered":
            temporary = home / "wrong-order.jar"
            with zipfile.ZipFile(agent) as source, zipfile.ZipFile(temporary, "w") as target:
                for entry in source.infolist():
                    raw = source.read(entry.filename)
                    if entry.filename == "META-INF/turboism/hooks":
                        lines = raw.decode().strip().splitlines()
                        pre = "dev.turboism.bootstrap.TriangulationSingleAgentShadowPreHook"
                        assert lines[0] == pre
                        lines.remove(pre)
                        lines.insert(len(lines) - 1, pre)
                        raw = ("\n".join(lines) + "\n").encode()
                    target.writestr(entry, raw)
            temporary.replace(agent)
        options = {}
        for item in template:
            if item.startswith("-D") and "=" in item:
                key, value = item[2:].split("=", 1)
                options[key] = value
        options.update({
            "turboism.home": str(home),
            "turboism.validation.atlasImageShadow.home": str(home),
            "turboism.validation.atlasImageShadow.fixture": str(fixture),
            "turboism.validation.atlasImageShadow.version": "5303",
            "turboism.validation.hostVersion": "5303",
            "turboism.validation.atlasImageShadow.resourceObservation": "true",
            "turboism.validation.triSingleAgent.probePath": str(validation / "tri-weave-agent.jar"),
            "turboism.validation.triSingleAgent.scenePath": str(validation / "atlas-image-shadow-scene-driver.jar"),
            "turboism.validation.triSingleAgent.sceneSha256": receipt["sceneSha256"],
            "turboism.validation.triSingleAgent.shadowPath": str(validation / "t039-shadow-agent.jar"),
            "turboism.validation.triSingleAgent.shadowSha256": receipt["shadowSha256"],
            "turboism.validation.tlWeave.outputDir": str(home / "tl-weave"),
            "turboism.validation.tlWeave.expectCodeSource": official.as_uri(),
            "turboism.validation.settingsStartupExpectedEdgeIndex": str(enabled).lower(),
            "turboism.atlasTileBbox.admitClassSha256": "800e3f6758e47bb1d8d974e72dcfed220f8773e15a5f05cac101ae2b56c1d160",
        })
        tl_sha = "f3427cec0e5c0c7d93c8a2cf72351a82a39b635b15682afc291eb3a62dc888f5" if enabled else "87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29"
        options["turboism.validation.tlWeave.expectClassSha256"] = tl_sha
        options["turboism.validation.tlWeave.expectCaptureClassSha256"] = tl_sha
        with zipfile.ZipFile(validation / "t039-shadow-agent.jar") as jar:
            helper_sha = hashlib.sha256(jar.read("dev/turboism/validation/atlasimage/t039/T039ShadowHelper.class")).hexdigest()
            t038_sha = hashlib.sha256(jar.read("dev/turboism/validation/atlasimage/t038/T038ArrayHelper.class")).hexdigest()
        for name, value in {
            "profile": "5303", "runId": "offline-t050-producer", "sourceBinding": "target-pd",
            "trustedSourcePaths": str(official), "jarSha256": sha(official),
            "classSha256": "ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6",
            "shapeSha256": "a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f",
            "loaderClass": "jdk.internal.loader.ClassLoaders$AppClassLoader", "helperSha256": helper_sha,
            "t038HelperSha256": t038_sha, "shadowMode": "shadow-ready",
            "shadowOptIn": "T039_SHADOW_EXPLICIT_OPT_IN", "maxEvents": "64",
            "ownerColdRemovalOptIn": "T039_OWNED_COLD_REMOVAL_V1",
        }.items():
            options["turboism.validation.t039." + name] = value
        if case == "missing-token": options.pop("turboism.validation.t039.ownerColdRemovalOptIn")
        if case == "wrong-source": options["turboism.validation.t039.trustedSourcePaths"] = str(directory / "Live2D_Cubism.jar")
        if case == "wrong-shadow-sha": options["turboism.validation.triSingleAgent.shadowSha256"] = "0" * 64
        if case == "bad-scene-config": options["turboism.validation.atlasImageShadow.fixtureSha256"] = "0" * 64
        cp = ":".join([str(candidate / "classes"), *map(str, sorted(args.official_dir.resolve().glob("*.jar")))])
        command = ["java", "-Xverify:all", *["-D" + key + "=" + value for key, value in options.items()]]
        if case != "unsupported": command.append("-XX:+DisableAttachMechanism")
        command += ["-javaagent:" + str(agent) + "=home=" + str(home) + ";hostClass=owned.NoCubismApplication;timeoutSeconds=30",
                    "-cp", cp, "dev.turboism.validation.tlindex.singleagent.SingleAgentShadowPremainSelfCheck",
                    str(official), str(enabled).lower(), "good" if case in {"on", "off"} else "reject"]
        (directory / "command.json").write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
        (directory / "console.log").write_text(result.stdout)
        (directory / "exit.json").write_text(json.dumps({"exitCode": result.returncode}) + "\n")
        wanted = "SHADOW_REAL_PREMAIN_METADATA_PASS" if case in {"on", "off"} else "SHADOW_REAL_PREMAIN_REFUSAL_PASS"
        if result.returncode or wanted not in result.stdout:
            raise AssertionError(case + " failed; see " + str(directory / "console.log"))
        if case in {"on", "off", "wrong-source", "misordered", "bad-scene-config"}:
            if "TRI_SINGLE_AGENT_SHADOW_CLEANUP registered=false" not in result.stdout:
                raise AssertionError(case + " did not confirm real shadow cleanup")
        for file in directory.rglob("*"):
            if file.is_file(): input_pins[str(file)] = sha(file)
        results.append({"case": case, "status": "PASS", "officialInitialized": False, "geometryExecuted": False})
    report = {"status": "SHADOW_CANONICAL_REAL_PREMAIN_METADATA_PASS", "cases": results,
              "candidateSha256": receipt["agentSha256"], "inputPins": input_pins,
              "hostSubmitted": False, "productionAcceptance": "NOT_PASSED"}
    (out / "review.json").write_text(json.dumps(report, indent=2) + "\n")
    assert not [file for file, digest in input_pins.items() if sha(Path(file)) != digest]
    print(json.dumps({"status": report["status"], "cases": results, "report": str(out / "review.json")}))


if __name__ == "__main__": main()
