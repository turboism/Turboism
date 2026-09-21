#!/usr/bin/env python3
"""Offline arithmetic/evidence checks for the uniform benchmark summary."""
import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[1] / "preview/summarize-uniform-benchmark.py"
spec = importlib.util.spec_from_file_location("uniform_summary", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def evidence():
    d = {"status": "PASS", "factor": "uniformSuite", "calibration": "true",
         "uniformCache.twoZoomPixelParity": "true", "latencyDefinition": "test"}
    variants = ["native", "locations", "locations-and-values", "locations-and-values", "locations", "native"]
    for leg, name in enumerate(variants):
        p = f"leg.{leg}."
        latency = 10_000_000 if name == "native" else 5_000_000
        d.update({p+"variant": name, p+"samples": 2, p+"rawNanos": [latency]*2,
                  p+"elapsedNanos": latency*4, p+"completedDisplayFrames": 2})
        for key in ["glErrors", "failures", "shadowMismatches", "draws", "nativeQueries", "nativeUniformWrites"]:
            d[p+"uniformCache."+key] = 0
        r = p + "resources."
        for key in ["processCpuNanos", "wallNanos", "edtAllocatedBytes", "availableProcessors",
                    "workingSetMeanBytes", "workingSetSamplePeakBytes", "heapUsedMeanBytes",
                    "heapUsedSamplePeakBytes", "heapCommittedSamplePeakBytes", "directBufferSamplePeakBytes",
                    "nativeSamples", "samples", "gcCollections", "gcCollectionMillis", "samplerNanos", "sampleFailures"]:
            d[r+key] = 0
        d[r+"processCpuNanos"] = 20_000_000
        d[r+"wallNanos"] = latency*4
        d[r+"availableProcessors"] = 4
        d[r+"samples"] = d[r+"nativeSamples"] = 4
        d[r+"workingSetMeanBytes"] = d[r+"workingSetSamplePeakBytes"] = 200*1024**2
    return d


def render(data):
    return "\n".join(f"{k}={v}" for k, v in data.items())


class SummaryTest(unittest.TestCase):
    def test_frames_use_complete_elapsed_window_not_reciprocal_latency(self):
        groups = module.summarize(render(evidence()))["groups"]
        self.assertEqual(groups[0]["renderFps"], 50.0)
        self.assertEqual(groups[1]["renderFps"], 100.0)
        self.assertEqual(groups[1]["latencySpeedupVsNative"], 2.0)
        self.assertEqual(groups[0]["cpuPercentMachine"], 12.5)
        self.assertEqual(groups[1]["cpuPercentMachine"], 25.0)
        self.assertEqual(groups[0]["workingSetMeanMiB"], 200)

    def test_incomplete_and_inconsistent_evidence_is_rejected(self):
        for key, value in [("status", "RUNNING"), ("leg.2.variant", "native"),
                           ("leg.2.samples", 3), ("calibration", "false"),
                           ("leg.2.uniformCache.glErrors", 1)]:
            data = evidence(); data[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                module.summarize(render(data))

    def test_unavailable_memory_is_not_substituted(self):
        data = evidence()
        data["leg.0.resources.workingSetMeanBytes"] = -1
        self.assertIsNone(module.summarize(render(data))["groups"][0]["workingSetMeanMiB"])

    def test_duplicate_fields_are_rejected(self):
        with self.assertRaises(ValueError):
            module.summarize(render(evidence()) + "\nstatus=PASS")


if __name__ == "__main__":
    unittest.main()
