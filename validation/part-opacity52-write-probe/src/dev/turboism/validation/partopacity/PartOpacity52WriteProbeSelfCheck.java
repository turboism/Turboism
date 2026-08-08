package dev.turboism.validation.partopacity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Minimal framework-free safety check for the probe body and its result format. */
public final class PartOpacity52WriteProbeSelfCheck {

    public static void main(final String[] args) throws Exception {
        final Path resultFile = Files.createTempFile("part-opacity52-probe", ".properties");
        final Map<String, String> result = PartOpacity52WriteProbeAgent.probeResult(resultFile);
        if (!"PENDING".equals(result.get("status"))) {
            throw new AssertionError("probe status must be PENDING before the host matrix");
        }
        final String written = Files.readString(resultFile);
        if (!written.contains("probe=part-opacity52-write")
            || !written.contains("targetVersion=5.2.0")) {
            throw new AssertionError("probe result file is incomplete");
        }
        Files.deleteIfExists(resultFile);
        System.out.println("[probe] part-opacity52-write self-check OK");
    }
}
