package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the packaged hook manifest and the fail-closed rules of its reader.
 *
 * <p>The manifest mixes two entry forms: a fully-qualified {@link HookContributor} class name, and a
 * {@code @} token that names a {@link DeclarativeHooks} entry. Both must load in manifest order, and
 * every malformed, unknown, duplicated or identity-colliding entry must reject the whole manifest so
 * no hook installs on a manifest the reviewer did not approve.</p>
 */
final class HookManifestTest {

    @Test
    void packagedManifestLoadsEveryDeclaredContributor() throws Exception {
        final List<HookContributor> contributors = HookManifest.load(HookManifestTest.class.getClassLoader());
        assertFalse(contributors.isEmpty());
        assertEquals(
                List.of(
                        MeshMirrorHookContributor.class,
                        MeshToolSessionHookContributor.class,
                        ModelingToolHookContributor.class,
                        WarpAltMirrorHookContributor.class,
                        MeshTriangulationHashHookContributor.class,
                        TriangulationEdgeIndexHookContributor.class,
                        AtlasTileBboxHookContributor.class,
                        AtlasCacheReuseHookContributor.class,
                        MeshInterpolationHookContributor.class,
                        AsyncReadbackHookContributor.class,
                        ShaderUniformHookContributor.class,
                        FpsHookContributor.class,
                        ParameterHookContributor.class,
                        ProjectLifecycleHookContributor.class,
                        FileChooserHistoryHookContributor.class,
                        ExportSettingsHookContributor.class,
                        // Entry guard of the two native Auto Mesh Generate funnels.
                        AutoMeshGuardHookContributor.class,
                        // Declarative native-optimization family: one token per install.
                        DeclarativeHookContributor.class,
                        DeclarativeHookContributor.class,
                        DeclarativeHookContributor.class,
                        DeclarativeHookContributor.class,
                        DeclarativeHookContributor.class,
                        ModelInstanceCoalesceHookContributor.class,
                        DeclarativeHookContributor.class,
                        GlGetErrorElisionHookContributor.class,
                        SkippedFrameUploadElisionHookContributor.class,
                        LineRendererUploadSplitHookContributor.class,
                        SkippedFrameMeshSetupElisionHookContributor.class,
                        InputPathElisionHookContributor.class,
                        CanvasCompositeElisionHookContributor.class,
                        UniformLocationCacheHookContributor.class,
                        // Order-sensitive: must follow the uniform lifecycle transform
                        // (see META-INF/turboism/hooks).
                        DeferredGlErrorCheckHookContributor.class,
                        RedundantStateElisionHookContributor.class,
                        MatrixScratchHookContributor.class,
                        DeclarativeHookContributor.class,
                        NativeEditBeginHookContributor.class,
                        EditApiDispatchHookContributor.class,
                        TextureAtlasDataModelHookContributor.class,
                        TextureAtlasAutoLayoutHookContributor.class,
                        PerformanceProbeHookContributor.class,
                        DockTabPopupHookContributor.class,
                        FloatingFrameDisposeHookContributor.class,
                        FloatingTabCloseHookContributor.class,
                        ObjectContextMenuHookContributor.class,
                        PhysicsEditorHookContributor.class,
                        BoundingBoxOverlayHookContributor.class,
                        ControlAppearanceHookContributor.class,
                        // Measurement-only: no behaviour, no ordering constraints.
                        ClipMaskProbeHookContributor.class,
                        ClipMaskDeduplicationHookContributor.class,
                        // Hierarchy-only incremental CModelSource.setup() form rebuilds.
                        ExtendedInterpolationSetupHookContributor.class,
                        // Inspector shader-only property changes skip the full rebuild.
                        InspectorPropertyBypassHookContributor.class,
                        // Skipped-frame sorting-info reuse and indexed GMeshContainer lookup.
                        SortingInfoCacheHookContributor.class),
                contributors.stream().map(HookContributor::getClass).toList());
    }

    @Test
    void declarativeEntriesKeepTheirDeclaredOrderAndIdentity() throws Exception {
        final List<DeclarativeHookContributor> declarative =
                HookManifest.load(HookManifestTest.class.getClassLoader()).stream()
                        .filter(DeclarativeHookContributor.class::isInstance)
                        .map(DeclarativeHookContributor.class::cast)
                        .toList();
        assertEquals(
                DeclarativeHooks.all().stream().map(DeclarativeHook::reportId).toList(),
                declarative.stream().map(DeclarativeHookContributor::id).toList());
        assertEquals(
                DeclarativeHooks.all().stream().map(DeclarativeHook::hookId).toList(),
                declarative.stream().map(DeclarativeHookContributor::policyId).toList());
        for (final DeclarativeHookContributor contributor : declarative) {
            assertEquals(HookContributor.Phase.HOST_RESOLVED, contributor.phase(), contributor.id());
            assertInstanceOf(ReportedHookContributor.class, contributor);
        }
    }

    @Test
    void everyDeclaredEntryReportsUnderADistinctHookIdentity() throws Exception {
        final List<HookContributor> contributors = HookManifest.load(HookManifestTest.class.getClassLoader());
        final Set<String> identities =
                contributors.stream().map(HookContributor::id).collect(Collectors.toSet());
        assertEquals(contributors.size(), identities.size(), "two entries would overwrite one installation outcome");
    }

    /**
     * A registry entry the manifest never names would be dead code, and a manifest token the registry
     * does not declare already fails to load. This pins the other direction.
     */
    @Test
    void everyDeclaredEntryIsNamedByThePackagedManifest() throws Exception {
        final Set<String> manifestIdentities = HookManifest.load(HookManifestTest.class.getClassLoader()).stream()
                .filter(DeclarativeHookContributor.class::isInstance)
                .map(HookContributor::id)
                .collect(Collectors.toSet());
        assertEquals(
                DeclarativeHooks.all().stream().map(DeclarativeHook::reportId).collect(Collectors.toSet()),
                manifestIdentities,
                "the registry and the packaged manifest must declare the same entries");
    }

    /**
     * Every declarative entry must serve a declared optimization, because the kill switch, the
     * diagnostics protocol and the settings row all read that catalogue row.
     */
    @Test
    void everyDeclaredEntryServesADeclaredOptimization() {
        for (final DeclarativeHook entry : DeclarativeHooks.all()) {
            assertTrue(
                    dev.turboism.config.optimization.OptimizationCatalog.byProtocol(entry.reportId())
                            .isPresent(),
                    entry.key() + " reports under a protocol no catalogue row claims");
        }
    }

    /**
     * The kill switch addresses {@link HookContributor#policyId()}, so the catalogue row that owns an
     * entry's protocol must also declare the id the entry exposes, or an operator cannot disable it.
     */
    @Test
    void everyDeclaredEntryExposesAHookIdItsCatalogueRowDeclares() {
        for (final DeclarativeHook entry : DeclarativeHooks.all()) {
            final var row = dev.turboism.config.optimization.OptimizationCatalog.byProtocol(entry.reportId())
                    .orElseThrow();
            assertTrue(
                    row.hookIds().contains(entry.hookId()),
                    entry.key() + " exposes " + entry.hookId() + " that its catalogue row does not declare");
        }
    }

    @Test
    void packagedManifestCoversEveryHookPhase() throws Exception {
        final List<HookContributor> contributors = HookManifest.load(HookManifestTest.class.getClassLoader());
        for (HookContributor.Phase phase : HookContributor.Phase.values()) {
            assertTrue(
                    contributors.stream().anyMatch(contributor -> contributor.phase() == phase),
                    "manifest declares no " + phase + " contributor");
        }
    }

    @Test
    void missingManifestFailsClosed() {
        assertThrows(HookManifest.HookManifestException.class, () -> HookManifest.load(loaderReturning(null)));
    }

    @Test
    void manifestEntriesMustImplementHookContributor() {
        final HookManifest.HookManifestException failure = assertThrows(
                HookManifest.HookManifestException.class, () -> HookManifest.load(manifest("java.lang.String")));
        assertTrue(failure.getMessage().contains("HookContributor"));
    }

    @Test
    void malformedLineFailsClosed() {
        final HookManifest.HookManifestException failure = assertThrows(
                HookManifest.HookManifestException.class, () -> HookManifest.load(manifest("not a class name")));
        assertTrue(failure.getMessage().contains("is not a class name"), failure.getMessage());
    }

    @Test
    void unknownDeclarativeTokenFailsClosed() {
        final String entry = HookManifest.DECLARATIVE_PREFIX + "verified-not-a-hook";
        final HookManifest.HookManifestException failure =
                assertThrows(HookManifest.HookManifestException.class, () -> HookManifest.load(manifest(entry)));
        assertTrue(failure.getMessage().contains("unknown declarative hook"), failure.getMessage());
    }

    @Test
    void malformedDeclarativeTokenFailsClosed() {
        final String entry = HookManifest.DECLARATIVE_PREFIX + "Verified_Not_A_Token";
        final HookManifest.HookManifestException failure =
                assertThrows(HookManifest.HookManifestException.class, () -> HookManifest.load(manifest(entry)));
        assertTrue(failure.getMessage().contains("is not a declarative hook token"), failure.getMessage());
    }

    @Test
    void duplicateEntryFailsClosed() {
        final String entry = "dev.turboism.bootstrap.AsyncReadbackHookContributor";
        final HookManifest.HookManifestException failure = assertThrows(
                HookManifest.HookManifestException.class, () -> HookManifest.load(manifest(entry + "\n" + entry)));
        assertTrue(failure.getMessage().contains("duplicate entry"), failure.getMessage());
    }

    /**
     * A class entry and a declarative entry that report under one hook identity would overwrite each
     * other's installation outcome, so the reader refuses the pair instead of installing both.
     */
    @Test
    void twoContributorsWithOneHookIdentityAreRefused() {
        final DeclarativeHook declared = DeclarativeHooks.all().get(0);
        final HookManifest.HookManifestException failure = assertThrows(
                HookManifest.HookManifestException.class,
                () -> HookManifest.requireDistinctIdentities(List.of(
                        DeclarativeHookContributor.of(declared), new SameIdentityContributor(declared.reportId()))));
        assertTrue(failure.getMessage().contains("duplicate hook identity"), failure.getMessage());
    }

    /** Stands in for a class entry that reports under an identity a declarative entry already claims. */
    private record SameIdentityContributor(String id) implements HookContributor {

        @Override
        public Phase phase() {
            return Phase.HOST_RESOLVED;
        }

        @Override
        public boolean admitted(final HookEnvironment environment) {
            return false;
        }

        @Override
        public AutoCloseable install(final HookEnvironment environment) {
            return () -> {};
        }
    }

    private static ClassLoader loaderReturning(final InputStream stream) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(final String name) {
                return stream;
            }
        };
    }

    private static ClassLoader manifest(final String body) {
        return loaderReturning(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
}
