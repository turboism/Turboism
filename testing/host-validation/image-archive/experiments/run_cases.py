"""Run sequential isolated screening cases and require shared correctness witnesses."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

REPO = Path(__file__).resolve().parents[4]


def props(path):
    out = {}
    for line in path.read_text(encoding="latin-1").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            out[key] = value
    return out


def main(args):
    root = Path(args.root)
    expected = props(root / "control-s3/home/state/texture-upload/experiment-verify.properties")
    for case in args.cases:
        experiment = case.split("-", 1)[0]
        if experiment == "control": experiment = "none"
        if experiment not in ("none", "large", "png", "archive", "warp", "float"):
            raise ValueError("unknown case")
        shadow = "shadow" in case
        agent = args.large_agent if experiment == "large" else args.agent
        command = [sys.executable, str(REPO / "testing/host-validation/image-archive/benchmark/run_native.py"),
                   "--host", args.host, "--fixture", args.fixture, "--agent", agent,
                   "--auxiliary", args.auxiliary, "--output", str(root / case), "--arm", "direct",
                   "--mode", "shadow" if shadow and experiment == "large" else "on",
                   "--experiment-agent", args.probe, "--experiment", experiment, "--timeout", "240"]
        if shadow: command.append("--experiment-shadow")
        with (Path(args.logs) / (case + ".log")).open("w", encoding="utf-8") as log:
            completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, cwd=REPO)
        if completed.returncode:
            raise RuntimeError("case failed, preserved: " + case)
        envelope = json.loads((root / case / "envelope.json").read_text(encoding="utf-8"))
        witness = envelope["experimentVerify"]
        for key in ("archiveSha256", "thumbnailSha256"):
            if witness[key] != expected[key]: raise AssertionError(case + " changed witness: " + key)
        observed = envelope["experimentLoad"]
        if experiment == "large" and int(envelope["load"]["preparation.prepared"]) != 11:
            raise AssertionError("large candidate did not cover all 11 inputs")
        key = {"png": "imagePrepared", "archive": "archivePrepared", "warp": "projectionPrepared", "float": "floatFastHits"}.get(experiment)
        if key and int(observed.get(key, "0")) <= 0: raise AssertionError("candidate had no consumer: " + case)
        if shadow and experiment in ("png", "warp", "float"):
            compared = {"png": "imageCompared", "warp": "projectionCompared", "float": "floatCompared"}[experiment]
            if observed.get(key) != observed.get(compared): raise AssertionError("incomplete shadow: " + case)
        print(json.dumps({"case": case, "status": envelope["status"],
                          "loadSeconds": int(envelope["load"]["agentToDocumentReadyNs"]) / 1e9,
                          "cpuSeconds": int(envelope["load"]["processCpuToDocumentReadyNs"]) / 1e9,
                          "counters": observed, "thumbnail": witness["thumbnailSha256"]}), flush=True)
        import time
        time.sleep(8)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("root", "logs", "host", "fixture", "agent", "large-agent", "auxiliary", "probe"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("cases", nargs="+")
    main(parser.parse_args())
