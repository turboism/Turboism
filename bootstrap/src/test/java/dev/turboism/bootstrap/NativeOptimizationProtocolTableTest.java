package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The catalogue's protocol declarations and the packaged hooks must describe each other.
 *
 * <p>Every persisted {@link Optimization} has to be served by at least one protocol, and every
 * packaged contributor that reports an installation outcome has to report under a protocol the
 * catalogue maps to an entry. Without this, a hook can install a behaviour while the Performance
 * page keeps showing {@code not-attempted}, or one of two hooks serving one preference can
 * overwrite the other's outcome.</p>
 */
final class NativeOptimizationProtocolTableTest {

    private static final String REPORT_CALL = "NativeOptimizationDiagnostics.installed(";

    /**
     * Files allowed to report without being a manifest contributor of their own: the shared
     * base contributor and the two installers that decide a sub-mode inside their weave.
     */
    private static final Set<String> DECLARED_PRODUCERS = Set.of(
            "NativeOptimizationHookContributor",
            "VerifiedClipMaskDeduplicationInstaller",
            "VerifiedIncrementalUpdateInstaller");

    @Test
    void everyPersistedEntryIsServedByAtLeastOneProtocol() {
        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            assertFalse(
                    optimization.protocols().isEmpty(),
                    optimization.key() + " has no serving protocol, so its status can never leave not-attempted");
        }
    }

    @Test
    void everyPackagedContributorProtocolIsDeclaredByTheCatalogue() throws Exception {
        for (final HookContributor contributor : contributors()) {
            if (!(contributor instanceof ReportedHookContributor reported)) continue;
            final String protocol = reported.protocolId();
            assertTrue(
                    OptimizationCatalog.byProtocol(protocol).isPresent(),
                    protocol + " reports an installation outcome that no catalogue entry claims");
        }
    }

    /**
     * The reverse direction of the hook ids: a kill switch addresses
     * {@link HookContributor#policyId()}, so every reporting hook's policy id has to be declared by
     * the row it serves or the catalogue cannot tell an operator what {@code hooks.disabledIds}
     * disables.
     */
    @Test
    void everyReportingContributorPolicyIdIsDeclaredByTheCatalogue() throws Exception {
        final Set<String> declared = new LinkedHashSet<>();
        for (final Optimization optimization : OptimizationCatalog.all()) declared.addAll(optimization.hookIds());
        for (final HookContributor contributor : contributors()) {
            if (!(contributor instanceof ReportedHookContributor)) continue;
            assertTrue(
                    declared.contains(contributor.policyId()),
                    contributor.policyId() + " is a hook a kill switch can address but no catalogue row declares it");
        }
    }

    @Test
    void everyReportedProtocolIsKnownAndEveryKnownProtocolHasAProducer() throws Exception {
        final Set<String> reported = new LinkedHashSet<>();
        for (final HookContributor contributor : contributors()) {
            if (contributor instanceof ReportedHookContributor reporter) reported.add(reporter.protocolId());
        }
        // The two sub-modes are reported by the installer that decides them, not by a contributor.
        reported.add(OptimizationCatalog.PROTOCOL_CLIP_MASK_SCISSOR_CLEAR);
        reported.add(OptimizationCatalog.PROTOCOL_INCREMENTAL_UPDATE_AFFECTERS);
        assertEquals(
                OptimizationCatalog.protocols().keySet(),
                reported,
                "a protocol exists only in the table or only in a producer");
    }

    @Test
    void diagnosticOnlyProtocolsAreTheReviewedProbeHooks() {
        final Set<String> diagnosticOnly = new LinkedHashSet<>();
        for (final Optimization optimization : OptimizationCatalog.all()) {
            if (!optimization.persisted()) diagnosticOnly.addAll(optimization.protocols());
        }
        assertEquals(
                Set.of(
                        "TURBOISM_CANVAS_COMPOSITE",
                        "TURBOISM_CLIP_MASK_PROBE",
                        "TURBOISM_GL_ERROR_ELISION",
                        "TURBOISM_SORTING_INFO_CACHE",
                        "TURBOISM_STATE_ELISION"),
                diagnosticOnly);
    }

    @Test
    void subModesAreServedByTheirOwnProtocolAndUploadElisionByTwo() {
        assertEquals(
                OptimizationCatalog.CLIP_MASK_SCISSOR_CLEAR,
                OptimizationCatalog.byProtocol(OptimizationCatalog.PROTOCOL_CLIP_MASK_SCISSOR_CLEAR)
                        .orElseThrow());
        assertEquals(
                OptimizationCatalog.INCREMENTAL_UPDATE_AFFECTERS,
                OptimizationCatalog.byProtocol(OptimizationCatalog.PROTOCOL_INCREMENTAL_UPDATE_AFFECTERS)
                        .orElseThrow());
        assertEquals(
                List.of("TURBOISM_UPLOAD_ELISION", "TURBOISM_LINE_RENDERER_UPLOAD_SPLIT"),
                OptimizationCatalog.UPLOAD_ELISION.protocols());
    }

    private static List<HookContributor> contributors() throws Exception {
        return HookManifest.load(NativeOptimizationProtocolTableTest.class.getClassLoader());
    }

    /**
     * The protocol enumeration above only sees contributors that implement the marker, so a
     * new reporting file must either be one of them or be declared as an installer producer.
     */
    @Test
    void everySourceFileThatReportsIsARegisteredProducer() throws Exception {
        final Path sources = repositoryRoot().resolve("bootstrap/src/main/java/dev/turboism/bootstrap");
        final List<String> reporting = new ArrayList<>();
        try (Stream<Path> paths = Files.list(sources)) {
            for (final Path path : paths.toList()) {
                if (!path.getFileName().toString().endsWith(".java")) continue;
                if (Files.readString(path).contains(REPORT_CALL))
                    reporting.add(path.getFileName().toString());
            }
        }
        reporting.sort(null);
        for (final String producer : DECLARED_PRODUCERS) {
            assertTrue(reporting.contains(producer + ".java"), producer + " no longer reports an outcome");
        }
        for (final String file : reporting) {
            final String simple = file.substring(0, file.length() - ".java".length());
            if (DECLARED_PRODUCERS.contains(simple)) continue;
            assertTrue(
                    ReportedHookContributor.class.isAssignableFrom(Class.forName("dev.turboism.bootstrap." + simple)),
                    file + " reports an installation outcome without the ReportedHookContributor marker");
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root from user.dir");
        return current;
    }
}
