package dev.turboism.pluginmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogArtifact;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogDependency;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogPermission;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import dev.turboism.pluginmanagement.PluginReleaseCompatibility.Rejection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Regression coverage for the single compatibility gate shared by the market and the updater. */
class PluginReleaseCompatibilityTest {

    private static final PluginVersion RUNTIME_API = PluginVersion.parse("0.68.0");

    private static CatalogRelease release(final String apiRange, final String channel, final List<String> platforms) {
        return new CatalogRelease(
                "1.0.0",
                channel,
                CatalogRelease.STATUS_ACTIVE,
                Instant.parse("2026-10-13T10:00:00Z"),
                "workflow",
                List.of("test"),
                apiRange,
                false,
                List.of(),
                platforms,
                List.of(),
                List.of(new CatalogPermission("turboism.file.read", "application", "Reads files")),
                "https://github.com/turboism/test/releases/tag/v1.0.0",
                Optional.empty(),
                new CatalogArtifact(
                        "application/java-archive",
                        "test-1.0.0.jar",
                        "https://github.com/turboism/test/releases/download/v1.0.0/test-1.0.0.jar",
                        "0".repeat(64),
                        "1".repeat(64),
                        1024L));
    }

    private static CatalogRelease withDependency(final CatalogDependency dependency) {
        final CatalogRelease base = release("[0.68.0,1.0.0)", CatalogRelease.CHANNEL_STABLE, List.of("windows-x64"));
        return new CatalogRelease(
                base.version(),
                base.channel(),
                base.status(),
                base.publishedAt(),
                base.category(),
                base.tags(),
                base.turboismApi(),
                base.requiresCubism(),
                base.cubismVersions(),
                base.platforms(),
                List.of(dependency),
                base.permissions(),
                base.releaseUrl(),
                base.sourceRevision(),
                base.artifact());
    }

    @Test
    void stableInstallationRejectsPreviewRelease() {
        final var verdict = PluginReleaseCompatibility.evaluate(
                release("[0.68.0,1.0.0)", CatalogRelease.CHANNEL_PREVIEW, List.of("windows-x64")),
                RUNTIME_API,
                FrameworkBuildInfo.Channel.STABLE,
                Optional.empty());

        assertFalse(verdict.compatible());
        assertEquals(Optional.of(Rejection.CHANNEL), verdict.rejection());
    }

    @Test
    void betaInstallationAcceptsPreviewRelease() {
        final var verdict = PluginReleaseCompatibility.evaluate(
                release("[0.68.0,1.0.0)", CatalogRelease.CHANNEL_PREVIEW, List.of("windows-x64")),
                RUNTIME_API,
                FrameworkBuildInfo.Channel.BETA,
                Optional.empty());

        assertTrue(verdict.compatible(), verdict.detail().orElse(""));
    }

    @Test
    void releaseWithoutCurrentPlatformIsRejected() {
        final var verdict = PluginReleaseCompatibility.evaluate(
                release("[0.68.0,1.0.0)", CatalogRelease.CHANNEL_STABLE, List.of("linux-x64")),
                RUNTIME_API,
                FrameworkBuildInfo.Channel.STABLE,
                Optional.empty());

        assertFalse(verdict.compatible());
        assertEquals(Optional.of(Rejection.PLATFORM), verdict.rejection());
    }

    @Test
    void requiredDependencyOutsideRangeIsRejected() {
        final CatalogDependency dependency = new CatalogDependency(
                "dev.turboism.plugin.mcp", "[0.2.0,1.0.0)", CatalogDependency.TYPE_REQUIRED, "after", Optional.empty());
        final var verdict = PluginReleaseCompatibility.evaluateDependencies(
                withDependency(dependency), Map.of("dev.turboism.plugin.mcp", PluginVersion.parse("0.1.0")));

        assertFalse(verdict.compatible());
        assertEquals(Optional.of(Rejection.DEPENDENCY), verdict.rejection());
    }

    @Test
    void missingRequiredDependencyIsRejected() {
        final CatalogDependency dependency = new CatalogDependency(
                "dev.turboism.plugin.mcp", "[0.1.0,1.0.0)", CatalogDependency.TYPE_REQUIRED, "after", Optional.empty());
        final var verdict = PluginReleaseCompatibility.evaluateDependencies(withDependency(dependency), Map.of());

        assertFalse(verdict.compatible());
        assertEquals(Optional.of(Rejection.DEPENDENCY), verdict.rejection());
    }

    @Test
    void optionalDependencyIsNotRequired() {
        final CatalogDependency dependency = new CatalogDependency(
                "dev.turboism.plugin.mcp", "[0.1.0,1.0.0)", CatalogDependency.TYPE_OPTIONAL, "after", Optional.empty());
        final var verdict = PluginReleaseCompatibility.evaluateDependencies(withDependency(dependency), Map.of());

        assertTrue(verdict.compatible(), verdict.detail().orElse(""));
    }
}
