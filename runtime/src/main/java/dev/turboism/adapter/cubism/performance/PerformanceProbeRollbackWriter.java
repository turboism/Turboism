package dev.turboism.adapter.cubism.performance;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validation-only rollback evidence manifest for the exact Cubism 5.3.02
 * performance probe, written from actual class bytes observed during install
 * and restoration. Mirrors the strict schema enforced by
 * scripts/preview/verify-cubism-performance-probe.py: exactly the target
 * owners, exactly one selector match and one restoration observation each,
 * instrumented bytes differing from the baseline, and restored bytes matching
 * the baseline. No manifest is published on any partial, mismatched, or
 * failed evidence set.
 */
public final class PerformanceProbeRollbackWriter {

    private static final String FORMAT = "turboism.cubism.performance-probe-rollback";
    private static final int SCHEMA_VERSION = 1;
    private static final String CUBISM_VERSION = "5.3.02";
    private static final int MAX_BYTES = 256 * 1024;
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final ObjectMapper mapper = new ObjectMapper();

    public void write(
        final Path output,
        final String artifactSha256,
        final String runId,
        final String variant,
        final String scenario,
        final String agentSha256,
        final String fixtureSha256,
        final List<PerformanceProbeMethodTransformer.Target> targets,
        final Map<String, OwnerEvidence> owners,
        final Map<PerformanceProbeMethodTransformer.Target, Integer> selectorMatches,
        final Map<String, Integer> restorationMatches
    ) throws IOException {
        final List<String> expectedOwners = targets.stream()
            .map(target -> target.ownerInternalName().replace('/', '.'))
            .distinct()
            .toList();
        validateOwners(expectedOwners, owners);
        validateSelectorMatches(targets, selectorMatches);
        validateRestorationMatches(expectedOwners, restorationMatches);

        final Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", FORMAT);
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("cubismVersion", CUBISM_VERSION);
        manifest.put("artifactSha256", artifactSha256);
        manifest.put("runId", runId);
        manifest.put("variant", variant);
        manifest.put("scenario", scenario);
        manifest.put("agentSha256", agentSha256);
        manifest.put("fixtureSha256", fixtureSha256);

        final List<Map<String, Object>> ownerEntries = new ArrayList<>();
        for (String owner : expectedOwners) {
            final OwnerEvidence evidence = owners.get(owner);
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("class", owner);
            entry.put("beforeSha256", evidence.beforeSha256());
            entry.put("instrumentedSha256", evidence.instrumentedSha256());
            entry.put("afterSha256", evidence.afterSha256());
            entry.put("restorationMatches", restorationMatches.get(owner));
            ownerEntries.add(entry);
        }
        manifest.put("owners", ownerEntries);

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
        final Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validateOwners(
        final List<String> expectedOwners,
        final Map<String, OwnerEvidence> owners
    ) {
        if (owners.size() != expectedOwners.size() || !owners.keySet().containsAll(expectedOwners)) {
            throw new IllegalArgumentException(
                "rollback owners must cover exactly the target owners: " + expectedOwners);
        }
        for (String owner : expectedOwners) {
            final OwnerEvidence evidence = owners.get(owner);
            if (evidence == null) throw new IllegalArgumentException("rollback owner missing evidence: " + owner);
            requireSha256(evidence.beforeSha256(), "beforeSha256 for " + owner);
            requireSha256(evidence.instrumentedSha256(), "instrumentedSha256 for " + owner);
            requireSha256(evidence.afterSha256(), "afterSha256 for " + owner);
            if (evidence.instrumentedSha256().equals(evidence.beforeSha256())) {
                throw new IllegalArgumentException(
                    "rollback instrumented bytes equal the baseline for " + owner);
            }
            if (!evidence.afterSha256().equals(evidence.beforeSha256())) {
                throw new IllegalArgumentException(
                    "rollback restoration mismatch for " + owner);
            }
        }
    }

    private static void validateSelectorMatches(
        final List<PerformanceProbeMethodTransformer.Target> targets,
        final Map<PerformanceProbeMethodTransformer.Target, Integer> selectorMatches
    ) {
        if (selectorMatches.size() != targets.size() || !selectorMatches.keySet().containsAll(targets)) {
            throw new IllegalArgumentException("rollback selector matches must cover exactly the target selectors");
        }
        for (Integer count : selectorMatches.values()) {
            if (count == null || count != 1) {
                throw new IllegalArgumentException("rollback selector match count must be exactly 1");
            }
        }
    }

    private static void validateRestorationMatches(
        final List<String> expectedOwners,
        final Map<String, Integer> restorationMatches
    ) {
        if (restorationMatches.size() != expectedOwners.size()
            || !restorationMatches.keySet().containsAll(expectedOwners)) {
            throw new IllegalArgumentException(
                "rollback restoration observations must cover exactly the target owners");
        }
        for (Integer count : restorationMatches.values()) {
            if (count == null || count != 1) {
                throw new IllegalArgumentException("rollback restoration count must be exactly 1 per owner");
            }
        }
    }

    private static void requireSha256(final String value, final String label) {
        if (value == null || !SHA256_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a lowercase 64-character SHA-256");
        }
    }

    public record OwnerEvidence(String beforeSha256, String instrumentedSha256, String afterSha256) { }
}
