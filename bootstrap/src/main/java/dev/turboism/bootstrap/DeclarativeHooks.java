package dev.turboism.bootstrap;

import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The declarative hook registry: every install-time hook whose contributor existed only to restate a
 * report label, a persisted preference and an installer is declared here instead of in its own class.
 *
 * <p>Adding a declarative hook means one entry here, one manifest token, and the installer itself.
 * {@code TurboismAgent} and the manifest reader stay unchanged, and the {@link OptimizationCatalog}
 * row that owns the preference keeps the settings page, the diagnostics protocol and the kill switch
 * in one place.</p>
 *
 * <p>An entry derives its protocol id, kill-switch id and default preference gate from that catalogue
 * row, so a manifest token can never drift from the optimization it serves. A row whose installer
 * also publishes a bridge default keeps an explicit gate, because that gate is what arms the bridge
 * before the weave runs. The declaration order below is the manifest order: the agent installs in
 * manifest order, and {@code HookManifestTest} pins the two lists against each other.</p>
 */
final class DeclarativeHooks {

    private static final List<DeclarativeHook> ALL = List.of(
            DeclarativeHook.of(OptimizationCatalog.IMAGE_ARCHIVE_REUSE, VerifiedImageArchiveReuseInstaller::new)
                    .withPreference(NativeOptimizationPolicy::imageArchiveReuseEnabled),
            DeclarativeHook.of(OptimizationCatalog.FLOAT_ARRAY_PARSE_CACHE, VerifiedFloatArrayParseCacheInstaller::new)
                    .withPreference(NativeOptimizationPolicy::floatArrayParseCacheEnabled),
            DeclarativeHook.of(
                            OptimizationCatalog.TEXTURE_UPLOAD_PREPARATION,
                            VerifiedTextureUploadPreparationInstaller::new)
                    .withPreference(NativeOptimizationPolicy::textureUploadPreparationEnabled),
            DeclarativeHook.of(
                            OptimizationCatalog.WARP_POSITION_PROJECTION, VerifiedWarpPositionProjectionInstaller::new)
                    .withPreference(NativeOptimizationPolicy::warpPositionProjectionEnabled),
            DeclarativeHook.of(OptimizationCatalog.MODEL_UPDATE_SKIP, VerifiedModelUpdateSkipInstaller::new)
                    .withPreference(NativeOptimizationPolicy::modelUpdateSkipEnabled),
            DeclarativeHook.of(OptimizationCatalog.INCREMENTAL_UPDATE, VerifiedIncrementalUpdateInstaller::new)
                    .withPreference(NativeOptimizationPolicy::incrementalUpdateEnabled),
            DeclarativeHook.of(OptimizationCatalog.REINIT_PAIR_ELISION, VerifiedReinitPairElisionInstaller::new)
                    .withPreference(NativeOptimizationPolicy::reinitPairElisionEnabled),
            DeclarativeHook.of(
                    OptimizationCatalog.CURSOR_VALIDATION_ELISION, VerifiedCursorValidationElisionInstaller::new),
            DeclarativeHook.of(OptimizationCatalog.SCENE_HANDLER_CACHE, VerifiedSceneHandlerCacheInstaller::new),
            DeclarativeHook.of(OptimizationCatalog.CAMERA_MATRIX_CACHE, VerifiedCameraMatrixCacheInstaller::new),
            DeclarativeHook.of(
                    OptimizationCatalog.WARP_NORMAL_POINT_ELISION, VerifiedWarpNormalPointElisionInstaller::new),
            DeclarativeHook.of(
                    OptimizationCatalog.WARP_GRID_INTERPOLATION, VerifiedWarpGridInterpolationInstaller::new),
            DeclarativeHook.of(OptimizationCatalog.PARAMETER_ROW_REPAINT, VerifiedParameterRowRepaintInstaller::new),
            DeclarativeHook.of(OptimizationCatalog.MESH_HIT_TEST, VerifiedMeshHitTestInstaller::new),
            DeclarativeHook.of(OptimizationCatalog.RENDER_PASS_TRAVERSAL, VerifiedRenderPassTraversalInstaller::new),
            DeclarativeHook.of(
                    OptimizationCatalog.EXTENDED_INTERPOLATION_SIBLING_ELISION,
                    VerifiedExtendedInterpolationSiblingInstaller::new),
            DeclarativeHook.of(
                    OptimizationCatalog.WARP_DEFORM_EDIT_ACCELERATION,
                    VerifiedWarpDeformEditAccelerationInstaller::new),
            DeclarativeHook.of(
                    OptimizationCatalog.ATLAS_INTERACTIVE_RENDER, VerifiedAtlasInteractiveRenderInstaller::new),
            DeclarativeHook.of(OptimizationCatalog.MEMORY_LEAK_MITIGATION, VerifiedMemoryLeakMitigationInstaller::new));

    private DeclarativeHooks() {}

    /**
     * @return every declarative entry, in manifest order
     */
    static List<DeclarativeHook> all() {
        return ALL;
    }

    /**
     * @param key the manifest token
     * @return the entry declared under {@code key}, or empty when no entry claims it
     */
    static Optional<DeclarativeHook> byKey(final String key) {
        return ALL.stream().filter(hook -> hook.key().equals(key)).findFirst();
    }

    /**
     * One declarative install-time hook entry: the facts a hook family used to restate in one
     * {@link HookContributor} subclass per install, expressed as data over {@link HookManifest} and
     * {@link WeavePlan}.
     *
     * <p>The installer keeps its reviewed contract table, {@link Admission} row, {@link WeaveSpec} and
     * its static {@code admitted(...)} gate, so the admission truth table stays where it is pinned.
     * This entry adds only the report label, the persisted-preference gate and the installer factory,
     * and every declared entry installs in {@link HookContributor.Phase#HOST_RESOLVED} because runtime
     * startup can already open and decode the initial document.</p>
     *
     * <p>Entries are addressed from {@code META-INF/turboism/hooks} by {@link #key()}, so a manifest
     * may mix declarative entries with fully-qualified contributor class names and an unmigrated
     * family keeps loading unchanged.</p>
     *
     * @param reportId the protocol id the installation outcome is recorded and logged under; the
     *     manifest token derives from it, so a token can never drift from the protocol it selects
     * @param hookId the operator-facing id {@code hooks.disabledIds} matches, taken from the
     *     installer's own {@code HOOK_ID} constant so the kill switch has one source of truth
     * @param preference the persisted-preference gate, read from the launch plan instead of the file
     * @param installer the installer factory, called once per start attempt
     */
    record DeclarativeHook(
            String reportId,
            String hookId,
            Predicate<HookEnvironment> preference,
            Function<InstallContext, VerifiedHookInstaller> installer) {

        /** Prefix every manifest token carries, so a token is visibly a declarative entry. */
        static final String TOKEN_PREFIX = "verified-";

        /** Prefix the protocol ids carry; the token drops it before deriving its own spelling. */
        private static final String PROTOCOL_PREFIX = "TURBOISM_";

        /**
         * How many hook ids a row may declare for an entry to derive one from it. A row that declares
         * several ids describes several weaves, so the entry cannot pick one without guessing.
         */
        private static final int DERIVABLE_HOOK_IDS = 1;

        DeclarativeHook {
            if (!reportId.startsWith(PROTOCOL_PREFIX)) {
                throw new IllegalArgumentException("declarative hook protocol id must start with " + PROTOCOL_PREFIX);
            }
        }

        /**
         * Declares one entry from a catalogue row.
         *
         * <p>The row supplies the protocol id, the kill-switch id and the default preference gate, so
         * a new entry restates none of them. A row that proves one preference through several
         * protocols declares the one this entry reports under first.</p>
         *
         * <p>The kill-switch id is derivable only from a row that declares exactly one, because a row
         * that declares several describes several weaves and a set has no order to pick by.</p>
         *
         * @param optimization the catalogue row this entry serves
         * @param installer the installer factory
         * @return the entry, whose preference gate is the row's own launch decision
         * @throws IllegalArgumentException when the row declares more than one hook id
         */
        static DeclarativeHook of(
                final Optimization optimization, final Function<InstallContext, VerifiedHookInstaller> installer) {
            if (optimization.hookIds().size() != DERIVABLE_HOOK_IDS) {
                throw new IllegalArgumentException(
                        "declarative hook " + optimization.protocols().get(0)
                                + " must declare exactly one hook id, not " + optimization.hookIds());
            }
            return new DeclarativeHook(
                    optimization.protocols().get(0),
                    optimization.hookIds().iterator().next(),
                    environment -> environment.plan().admitted(optimization),
                    installer);
        }

        /**
         * Replaces the preference gate with one that also arms the installer's bridge default.
         *
         * @param preference the gate to read instead of the row's own launch decision
         * @return this entry with the supplied gate
         */
        DeclarativeHook withPreference(final Predicate<HookEnvironment> preference) {
            return new DeclarativeHook(reportId, hookId, preference, installer);
        }

        /**
         * @return the {@code META-INF/turboism/hooks} token that selects this entry
         */
        String key() {
            return TOKEN_PREFIX
                    + reportId.substring(PROTOCOL_PREFIX.length())
                            .toLowerCase(Locale.ROOT)
                            .replace('_', '-');
        }
    }
}
