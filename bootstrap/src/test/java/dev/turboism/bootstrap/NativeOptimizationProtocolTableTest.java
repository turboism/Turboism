package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.NativeOptimizationConfig.Option;
import dev.turboism.config.NativeOptimizationDiagnostics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The diagnostics protocol table and the packaged hooks must describe each other.
 *
 * <p>Every user-visible {@link Option} has to be served by at least one protocol, and
 * every packaged contributor that reports an installation outcome has to report under a
 * protocol the table maps to an option or lists as a reviewed validation-only probe.
 * Without this, a hook can install a behaviour while the Performance page keeps showing
 * {@code not-attempted}, or one of two hooks serving one preference can overwrite the
 * other's outcome.</p>
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
    void everyOptionIsServedByAtLeastOneProtocol() {
        for (final Option option : Option.values()) {
            assertFalse(
                    protocolsServing(option).isEmpty(),
                    option.key() + " has no serving protocol, so its status can never leave not-attempted");
        }
    }

    @Test
    void everyPackagedContributorProtocolIsMappedOrValidationOnly() throws Exception {
        for (final HookContributor contributor : contributors()) {
            if (!(contributor instanceof ReportedHookContributor reported)) continue;
            final String protocol = reported.protocolId();
            assertTrue(
                    NativeOptimizationDiagnostics.protocols().containsKey(protocol)
                            || NativeOptimizationDiagnostics.validationOnlyProtocols()
                                    .containsKey(protocol),
                    protocol + " reports an installation outcome that no option or review entry claims");
        }
    }

    @Test
    void everyReportedProtocolIsKnownAndEveryKnownProtocolHasAProducer() throws Exception {
        final Set<String> reported = new LinkedHashSet<>();
        for (final HookContributor contributor : contributors()) {
            if (contributor instanceof ReportedHookContributor reporter) reported.add(reporter.protocolId());
        }
        // The two sub-modes are reported by the installer that decides them, not by a contributor.
        reported.add(NativeOptimizationDiagnostics.PROTOCOL_CLIP_MASK_SCISSOR_CLEAR);
        reported.add(NativeOptimizationDiagnostics.PROTOCOL_INCREMENTAL_UPDATE_AFFECTERS);
        final Set<String> known =
                new LinkedHashSet<>(NativeOptimizationDiagnostics.protocols().keySet());
        known.addAll(NativeOptimizationDiagnostics.validationOnlyProtocols().keySet());
        assertEquals(known, reported, "a protocol exists only in the table or only in a producer");
    }

    @Test
    void validationOnlyProtocolsAreTheReviewedProbeHooks() {
        assertEquals(
                Set.of(
                        "TURBOISM_CANVAS_COMPOSITE",
                        "TURBOISM_CLIP_MASK_PROBE",
                        "TURBOISM_GL_ERROR_ELISION",
                        "TURBOISM_SORTING_INFO_CACHE",
                        "TURBOISM_STATE_ELISION"),
                NativeOptimizationDiagnostics.validationOnlyProtocols().keySet());
    }

    @Test
    void subModesAreServedByTheirOwnProtocolAndUploadElisionByTwo() {
        assertEquals(
                List.of(Option.CLIP_MASK_SCISSOR_CLEAR),
                NativeOptimizationDiagnostics.optionsServedBy(
                        NativeOptimizationDiagnostics.PROTOCOL_CLIP_MASK_SCISSOR_CLEAR));
        assertEquals(
                List.of(Option.INCREMENTAL_UPDATE_AFFECTERS),
                NativeOptimizationDiagnostics.optionsServedBy(
                        NativeOptimizationDiagnostics.PROTOCOL_INCREMENTAL_UPDATE_AFFECTERS));
        assertEquals(
                List.of("TURBOISM_UPLOAD_ELISION", "TURBOISM_LINE_RENDERER_UPLOAD_SPLIT"),
                protocolsServing(Option.UPLOAD_ELISION));
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

    private static List<String> protocolsServing(final Option option) {
        return NativeOptimizationDiagnostics.protocols().entrySet().stream()
                .filter(entry -> entry.getValue().contains(option))
                .map(Map.Entry::getKey)
                .toList();
    }
}
