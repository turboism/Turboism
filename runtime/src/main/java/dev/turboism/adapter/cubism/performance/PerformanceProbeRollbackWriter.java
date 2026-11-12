package dev.turboism.adapter.cubism.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.io.DurableFile;
import dev.turboism.core.io.WriteOptions;
import dev.turboism.runtime.json.RuntimeJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validation-only rollback evidence manifest for an exact-version Cubism 5.3
 * performance probe, written from the restoration the close sequence performed.
 * Mirrors the strict schema enforced by
 * scripts/preview/verify-cubism-performance-probe.py: exactly the target owners
 * and exactly one selector match each. No manifest is published on any partial
 * or failed evidence set.
 */
public final class PerformanceProbeRollbackWriter {

    private static final String FORMAT = "turboism.cubism.performance-probe-rollback";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_BYTES = 256 * 1024;
    /** A manifest is evidence, not live state: a plain replacing move is better than no manifest. */
    private static final WriteOptions PREFER_ATOMIC_REPLACE =
            WriteOptions.DEFAULT.withAtomicity(WriteOptions.Atomicity.PREFERRED);

    private final ObjectMapper mapper = RuntimeJson.standard();

    /**
     * Validates a complete rollback evidence set and, only if every check holds, publishes
     * it as a JSON manifest.
     *
     * <p>Validation is all-or-nothing and happens before any bytes are written, so a
     * partial or contradictory evidence set produces no file at all. It requires exactly one
     * selector match per target, and the owner list must cover exactly the target owners.</p>
     *
     * <p>The manifest carries the exact Cubism version selected by the installer profile,
     * is capped at 256 KiB, and is published through a uniquely named sibling temporary file
     * moved into place, atomically where the filesystem supports it.</p>
     *
     * @param output             destination path, resolved to an absolute normalized path
     * @param cubismVersion      exact reviewed Cubism Editor version
     * @param artifactSha256     digest of the Cubism artifact the probe was installed into
     * @param runId              identity of the validation run
     * @param variant            which build variant was exercised
     * @param scenario           name of the scenario that was run
     * @param targets            the instrumentation targets whose rollback is being evidenced
     * @param selectorMatches    how many methods each target matched; each must be exactly 1
     * @throws IllegalArgumentException when any coverage or count rule above is violated
     * @throws IOException when the manifest exceeds 256 KiB, or it cannot be written or moved into place
     */
    public void write(
            final Path output,
            final String cubismVersion,
            final String artifactSha256,
            final String runId,
            final String variant,
            final String scenario,
            final List<PerformanceProbeMethodTransformer.Target> targets,
            final Map<PerformanceProbeMethodTransformer.Target, Integer> selectorMatches)
            throws IOException {
        validateSelectorMatches(targets, selectorMatches);

        final Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("cubismVersion", requireText(cubismVersion, "cubismVersion"));
        manifest.put("artifactSha256", artifactSha256);
        manifest.put("runId", runId);
        manifest.put("variant", variant);
        manifest.put("scenario", scenario);
        manifest.put(
                "owners",
                targets.stream()
                        .map(target -> target.ownerInternalName().replace('/', '.'))
                        .distinct()
                        .toList());

        final List<Map<String, Object>> selectorEntries = new ArrayList<>();
        for (PerformanceProbeMethodTransformer.Target target : targets) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("owner", target.ownerInternalName().replace('/', '.'));
            entry.put("method", target.methodName());
            entry.put("descriptor", target.descriptor());
            entry.put("metric", PerformanceProbeReportWriter.metricName(target.metric()));
            entry.put("matches", selectorMatches.get(target));
            selectorEntries.add(entry);
        }
        manifest.put("selectors", selectorEntries);

        final byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        if (bytes.length > MAX_BYTES) throw new IOException("performance probe rollback manifest exceeds 256 KiB");
        final Path target = output.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        DurableFile.at(target).withOptions(PREFER_ATOMIC_REPLACE).replace(bytes);
    }

    private static void validateSelectorMatches(
            final List<PerformanceProbeMethodTransformer.Target> targets,
            final Map<PerformanceProbeMethodTransformer.Target, Integer> selectorMatches) {
        if (selectorMatches.size() != targets.size()
                || !selectorMatches.keySet().containsAll(targets)) {
            throw new IllegalArgumentException("rollback selector matches must cover exactly the target selectors");
        }
        for (Integer count : selectorMatches.values()) {
            if (count == null || count != 1) {
                throw new IllegalArgumentException("rollback selector match count must be exactly 1");
            }
        }
    }

    private static String requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
