package dev.turboism.config.optimization;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The single declaration of every native optimization the agent can install.
 *
 * <p>Each row is one exhaustive statement about one optimization: its {@code config.json} key and
 * scope, the {@code -D} properties that force it, the protocols its installers report under, the
 * hook ids a kill switch can address, its settings row and its i18n keys. The derived views below
 * are what every other part of the product consumes, so a new optimization is one row plus its
 * hook and its i18n entries - not another transcription per consumer.</p>
 *
 * <p>Integrity is checked once, at first touch, and a violation throws instead of letting a
 * half-described optimization reach the launcher or the settings page: duplicate key,
 * contribution id, protocol or {@code (section, index)}; a persisted row whose key or scope
 * disagrees with its {@link PerformanceSettingsConfig.Field}; a diagnostic-only row that claims a
 * config key, row, placement or field; a persisted row whose first override property is not
 * {@code turboism.optimization.<key>}.</p>
 */
public final class OptimizationCatalog {
    /** Protocol the clip-mask installer reports its scissor-limited clear sub-mode under. */
    public static final String PROTOCOL_CLIP_MASK_SCISSOR_CLEAR = "TURBOISM_CLIP_MASK_SCISSOR_CLEAR";

    /** Protocol the incremental-update bridge reports its glue-affecter sub-mode under. */
    public static final String PROTOCOL_INCREMENTAL_UPDATE_AFFECTERS = "TURBOISM_INCREMENTAL_UPDATE_AFFECTERS";

    /** Performance-page sections in render order; a {@link Optimization.Placement} names one. */
    public static final List<String> SECTIONS = List.of("heavy-model", "model", "rendering", "upload", "mesh", "jvm");

    // ---------------------------------------------------------------- launcher-hosted preferences

    public static final Optimization MODEL_UPDATE_SKIP = new Optimization(
            "modelUpdateSkip",
            "model-update-skip",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.modelUpdateSkip"),
            List.of("TURBOISM_MODEL_UPDATE_SKIP"),
            Set.of("cubism.model-update.unchanged-frame-skip"),
            Optional.of(new Optimization.Placement("heavy-model", 62)),
            "settings.optimization.model-update-skip",
            "settings.optimization.model-update-skip.tooltip");

    public static final Optimization INCREMENTAL_UPDATE = new Optimization(
            "incrementalUpdate",
            "incremental-update",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.incrementalUpdate"),
            List.of("TURBOISM_INCREMENTAL_UPDATE"),
            Set.of("cubism.model-update.incremental"),
            Optional.of(new Optimization.Placement("model", 72)),
            "settings.optimization.incremental-update",
            "settings.optimization.incremental-update.tooltip");

    public static final Optimization INCREMENTAL_UPDATE_AFFECTERS = new Optimization(
            "incrementalUpdateAffecters",
            "incremental-update-affecters",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.incrementalUpdateAffecters"),
            List.of(PROTOCOL_INCREMENTAL_UPDATE_AFFECTERS),
            Set.of("cubism.model-update.incremental"),
            Optional.of(new Optimization.Placement("model", 73)),
            "settings.optimization.incremental-update-affecters",
            "settings.optimization.incremental-update-affecters.tooltip");

    public static final Optimization UNIFORM_LOCATION_CACHE = new Optimization(
            "uniformLocationCache",
            "uniform-location-cache",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.uniformLocationCache"),
            List.of("TURBOISM_UNIFORM_LOCATION"),
            Set.of("cubism.render.uniform-location-cache"),
            Optional.of(new Optimization.Placement("rendering", 81)),
            "settings.optimization.uniform-location-cache",
            "settings.optimization.uniform-location-cache.tooltip");

    /**
     * One preference implemented by two independent weaves: the skipped-frame elision refuses when
     * the uniform-location cache is enabled, while the line-renderer split installs in exactly that
     * combination. Both protocols therefore prove this one entry.
     */
    public static final Optimization UPLOAD_ELISION = new Optimization(
            "uploadElision",
            "upload-elision",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.uploadElision"),
            List.of("TURBOISM_UPLOAD_ELISION", "TURBOISM_LINE_RENDERER_UPLOAD_SPLIT"),
            Set.of("cubism.render.upload-elision", "cubism.render.line-renderer-upload-split"),
            Optional.of(new Optimization.Placement("upload", 91)),
            "settings.optimization.upload-elision",
            "settings.optimization.upload-elision.tooltip");

    public static final Optimization MESH_SETUP_ELISION = new Optimization(
            "meshSetupElision",
            "mesh-setup-elision",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.meshSetupElision"),
            List.of("TURBOISM_MESH_SETUP_ELISION"),
            Set.of("cubism.render.mesh-setup-elision"),
            Optional.of(new Optimization.Placement("upload", 93)),
            "settings.optimization.mesh-setup-elision",
            "settings.optimization.mesh-setup-elision.tooltip");

    public static final Optimization INPUT_PATH_ELISION = new Optimization(
            "inputPathElision",
            "input-path-elision",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.inputPathElision"),
            List.of("TURBOISM_INPUT_PATH"),
            Set.of("cubism.render.input-path-elision"),
            Optional.of(new Optimization.Placement("upload", 92)),
            "settings.optimization.input-path-elision",
            "settings.optimization.input-path-elision.tooltip");

    public static final Optimization MESA_GL_THREAD = new Optimization(
            "mesaGlThread",
            "mesa-gl-thread",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.mesaGlThread"),
            List.of("TURBOISM_DEFERRED_GL_ERROR_CHECK"),
            Set.of("cubism.render.mesa-gl-thread"),
            Optional.of(new Optimization.Placement("heavy-model", 61)),
            "settings.optimization.mesa-gl-thread",
            "settings.optimization.mesa-gl-thread.tooltip");

    public static final Optimization ASYNC_READBACK = new Optimization(
            "asyncReadback",
            "async-readback",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.asyncReadback"),
            List.of("TURBOISM_ASYNC_READBACK"),
            Set.of("cubism.render.async-readback"),
            Optional.of(new Optimization.Placement("heavy-model", 69)),
            "settings.optimization.async-readback",
            "settings.optimization.async-readback.tooltip");

    /**
     * The second entry with a legacy alias: {@code turboism.meshInterpolation.enabled} is honoured
     * by earlier launchers but never written, so it has to keep working.
     */
    public static final Optimization MESH_INTERPOLATION = new Optimization(
            "meshInterpolation",
            "mesh-interpolation",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.meshInterpolation", "turboism.meshInterpolation.enabled"),
            List.of("TURBOISM_MESH_INTERPOLATION"),
            Set.of("cubism.mesh.interpolation"),
            Optional.of(new Optimization.Placement("model", 74)),
            "settings.optimization.mesh-interpolation",
            "settings.optimization.mesh-interpolation.tooltip");

    /** The other legacy alias: {@code turboism.shaderUniform.enabled}. */
    public static final Optimization SHADER_UNIFORM = new Optimization(
            "shaderUniform",
            "shader-uniform",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.shaderUniform", "turboism.shaderUniform.enabled"),
            List.of("TURBOISM_SHADER_UNIFORM"),
            Set.of("cubism.shader.uniform"),
            Optional.of(new Optimization.Placement("rendering", 82)),
            "settings.optimization.shader-uniform",
            "settings.optimization.shader-uniform.tooltip");

    public static final Optimization MATRIX_SCRATCH = new Optimization(
            "matrixScratch",
            "matrix-scratch",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.matrixScratch"),
            List.of("TURBOISM_MATRIX_SCRATCH"),
            Set.of("cubism.render.matrix-scratch"),
            Optional.of(new Optimization.Placement("rendering", 83)),
            "settings.optimization.matrix-scratch",
            "settings.optimization.matrix-scratch.tooltip");

    public static final Optimization IMAGE_ARCHIVE_REUSE = new Optimization(
            "imageArchiveReuse",
            "image-archive-reuse",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.imageArchiveReuse"),
            List.of("TURBOISM_IMAGE_ARCHIVE_REUSE"),
            Set.of("cubism.image-archive-reuse"),
            Optional.of(new Optimization.Placement("mesh", 105)),
            "settings.optimization.image-archive-reuse",
            "settings.optimization.image-archive-reuse.tooltip");

    public static final Optimization FLOAT_ARRAY_PARSE_CACHE = new Optimization(
            "floatArrayParseCache",
            "float-array-parse-cache",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.floatArrayParseCache"),
            List.of("TURBOISM_FLOAT_ARRAY_PARSE_CACHE"),
            Set.of("cubism.float-array-parse-cache"),
            Optional.of(new Optimization.Placement("mesh", 106)),
            "settings.optimization.float-array-parse-cache",
            "settings.optimization.float-array-parse-cache.tooltip");

    public static final Optimization TEXTURE_UPLOAD_PREPARATION = new Optimization(
            "textureUploadPreparation",
            "texture-upload-preparation",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.textureUploadPreparation"),
            List.of("TURBOISM_TEXTURE_UPLOAD_PREPARATION"),
            Set.of("cubism.texture-upload-preparation"),
            Optional.of(new Optimization.Placement("mesh", 107)),
            "settings.optimization.texture-upload-preparation",
            "settings.optimization.texture-upload-preparation.tooltip");

    public static final Optimization WARP_POSITION_PROJECTION = new Optimization(
            "warpPositionProjection",
            "warp-position-projection",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.warpPositionProjection"),
            List.of("TURBOISM_WARP_POSITION_PROJECTION"),
            Set.of("cubism.warp-position-projection"),
            Optional.of(new Optimization.Placement("rendering", 84)),
            "settings.optimization.warp-position-projection",
            "settings.optimization.warp-position-projection.tooltip");

    public static final Optimization REINIT_PAIR_ELISION = new Optimization(
            "reinitPairElision",
            "reinit-pair-elision",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.reinitPairElision"),
            List.of("TURBOISM_REINIT_PAIR_ELISION"),
            Set.of("cubism.model.reinit-pair-elision"),
            Optional.of(new Optimization.Placement("heavy-model", 66)),
            "settings.optimization.reinit-pair-elision",
            "settings.optimization.reinit-pair-elision.tooltip");

    public static final Optimization CLIP_MASK_DEDUPLICATION = new Optimization(
            "clipMaskDeduplication",
            "clip-mask-deduplication",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.clipMaskDeduplication"),
            List.of("TURBOISM_CLIP_MASK_DEDUPE"),
            Set.of("cubism.render.clip-mask-dedupe"),
            Optional.of(new Optimization.Placement("heavy-model", 63)),
            "settings.optimization.clip-mask-deduplication",
            "settings.optimization.clip-mask-deduplication.tooltip");

    /** Sub-mode of {@link #CLIP_MASK_DEDUPLICATION}, decided inside the same installer. */
    public static final Optimization CLIP_MASK_SCISSOR_CLEAR = new Optimization(
            "clipMaskScissorClear",
            "clip-mask-scissor-clear",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.clipMaskScissorClear"),
            List.of(PROTOCOL_CLIP_MASK_SCISSOR_CLEAR),
            Set.of("cubism.render.clip-mask-dedupe"),
            Optional.of(new Optimization.Placement("heavy-model", 64)),
            "settings.optimization.clip-mask-scissor-clear",
            "settings.optimization.clip-mask-scissor-clear.tooltip");

    public static final Optimization EXTENDED_INTERPOLATION_INCREMENTAL_SETUP = new Optimization(
            "extendedInterpolationIncrementalSetup",
            "extended-interpolation-incremental-setup",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.extendedInterpolationIncrementalSetup"),
            List.of("TURBOISM_EXTENDED_INTERPOLATION_INCREMENTAL_SETUP"),
            Set.of("cubism.editor-model.extended-interpolation-incremental-setup"),
            Optional.of(new Optimization.Placement("heavy-model", 65)),
            "settings.optimization.extended-interpolation-incremental-setup",
            "settings.optimization.extended-interpolation-incremental-setup.tooltip");

    public static final Optimization MODEL_INSTANCE_COALESCE = new Optimization(
            "modelInstanceCoalesce",
            "model-instance-coalesce",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.modelInstanceCoalesce"),
            List.of("TURBOISM_MODEL_INSTANCE_COALESCE"),
            Set.of("cubism.model-instance-update-coalesce"),
            Optional.of(new Optimization.Placement("heavy-model", 67)),
            "settings.optimization.model-instance-coalesce",
            "settings.optimization.model-instance-coalesce.tooltip");

    public static final Optimization INSPECTOR_PROPERTY_BYPASS = new Optimization(
            "inspectorPropertyBypass",
            "inspector-property-bypass",
            Optimization.Access.PERSISTED_LAUNCHER,
            List.of("turboism.optimization.inspectorPropertyBypass"),
            List.of("TURBOISM_INSPECTOR_PROPERTY_BYPASS"),
            Set.of("cubism.editor-model.inspector-property-bypass"),
            Optional.of(new Optimization.Placement("heavy-model", 68)),
            "settings.optimization.inspector-property-bypass",
            "settings.optimization.inspector-property-bypass.tooltip");

    // ------------------------------------------------------------ root-level asset-load preferences

    public static final Optimization MESH_TRIANGULATION_HASH = new Optimization(
            "meshTriangulationHashFix",
            "mesh-triangulation-hash",
            Optimization.Access.PERSISTED_ROOT,
            List.of("turboism.optimization.meshTriangulationHashFix"),
            List.of("TURBOISM_MESH_TRIANGULATION_HASH"),
            Set.of("cubism.mesh.triangulation-hash"),
            Optional.of(new Optimization.Placement("mesh", 101)),
            "settings.mesh-triangulation.hash-degeneracy",
            "settings.mesh-triangulation.hash-degeneracy.tooltip");

    /**
     * The one hook whose {@code hooks.disabledIds} identity is its report id rather than a
     * {@code cubism.*} policy id, because its contributor never overrides
     * {@code HookContributor.policyId()}; both ids are declared so a kill switch works either way.
     */
    public static final Optimization TRIANGULATION_EDGE_INDEX = new Optimization(
            "meshTriangulationEdgeIndex",
            "triangulation-edge-index",
            Optimization.Access.PERSISTED_ROOT,
            List.of("turboism.optimization.meshTriangulationEdgeIndex"),
            List.of("TURBOISM_TRIANGULATION_EDGE_INDEX"),
            Set.of("TURBOISM_TRIANGULATION_EDGE_INDEX", "cubism.mesh.triangulation-edge-index"),
            Optional.of(new Optimization.Placement("mesh", 102)),
            "settings.mesh-triangulation.edge-index",
            "settings.mesh-triangulation.edge-index.tooltip");

    public static final Optimization ATLAS_TILE_BBOX = new Optimization(
            "atlasTileBbox",
            "atlas-tile-bbox",
            Optimization.Access.PERSISTED_ROOT,
            List.of("turboism.optimization.atlasTileBbox"),
            List.of("TURBOISM_ATLAS_TILE_BBOX"),
            Set.of("cubism.textureatlas.tile-bbox"),
            Optional.of(new Optimization.Placement("mesh", 103)),
            "settings.atlas.tile-bbox",
            "settings.atlas.tile-bbox.tooltip");

    public static final Optimization ATLAS_CACHE_REUSE = new Optimization(
            "atlasCacheReuse",
            "atlas-cache-reuse",
            Optimization.Access.PERSISTED_ROOT,
            List.of("turboism.optimization.atlasCacheReuse"),
            List.of("TURBOISM_ATLAS_CACHE_REUSE"),
            Set.of("cubism.textureatlas.cache-reuse"),
            Optional.of(new Optimization.Placement("mesh", 104)),
            "settings.atlas.cache-reuse",
            "settings.atlas.cache-reuse.tooltip");

    // ---------------------------------------------------------------- diagnostic-only probe hooks

    /**
     * Diagnostic-only: the hook only measures a behaviour driven by
     * {@code -Dturboism.validation.canvasCompositeElision=true}, so it proves no user-visible
     * preference and never appears on the settings page or in the status snapshot.
     */
    public static final Optimization CANVAS_COMPOSITE_ELISION = new Optimization(
            "",
            "",
            Optimization.Access.DIAGNOSTIC_ONLY,
            List.of("turboism.validation.canvasCompositeElision"),
            List.of("TURBOISM_CANVAS_COMPOSITE"),
            Set.of("TURBOISM_CANVAS_COMPOSITE"),
            Optional.empty(),
            "",
            "");

    /** Diagnostic-only: measurement-only clipping-mask/transparent-group probe. */
    public static final Optimization CLIP_MASK_PROBE = new Optimization(
            "",
            "",
            Optimization.Access.DIAGNOSTIC_ONLY,
            List.of("turboism.validation.clipMaskProbe"),
            List.of("TURBOISM_CLIP_MASK_PROBE"),
            Set.of("TURBOISM_CLIP_MASK_PROBE"),
            Optional.empty(),
            "",
            "");

    /** Diagnostic-only: validation-only {@code glGetError} elision. */
    public static final Optimization GL_GET_ERROR_ELISION = new Optimization(
            "",
            "",
            Optimization.Access.DIAGNOSTIC_ONLY,
            List.of("turboism.validation.glGetErrorElision"),
            List.of("TURBOISM_GL_ERROR_ELISION"),
            Set.of("TURBOISM_GL_ERROR_ELISION"),
            Optional.empty(),
            "",
            "");

    /** Diagnostic-only: validation-only redundant GL state elision. */
    public static final Optimization REDUNDANT_STATE_ELISION = new Optimization(
            "",
            "",
            Optimization.Access.DIAGNOSTIC_ONLY,
            List.of("turboism.validation.redundantStateElision"),
            List.of("TURBOISM_STATE_ELISION"),
            Set.of("TURBOISM_STATE_ELISION"),
            Optional.empty(),
            "",
            "");

    /**
     * Diagnostic-only: validation-only skipped-frame sorting-info reuse. Its switch is named like a
     * product override ({@code turboism.optimization.sortingInfoCache}) although nothing persists
     * it, and it is the one probe whose default is derived from the preferences it serves.
     */
    public static final Optimization SORTING_INFO_CACHE = new Optimization(
            "",
            "",
            Optimization.Access.DIAGNOSTIC_ONLY,
            List.of("turboism.optimization.sortingInfoCache"),
            List.of("TURBOISM_SORTING_INFO_CACHE"),
            Set.of("cubism.render.sorting-info-cache"),
            Optional.empty(),
            "",
            "");

    /** Every row, in declaration order. */
    private static final List<Optimization> ALL = List.of(
            MODEL_UPDATE_SKIP,
            INCREMENTAL_UPDATE,
            INCREMENTAL_UPDATE_AFFECTERS,
            UNIFORM_LOCATION_CACHE,
            UPLOAD_ELISION,
            MESH_SETUP_ELISION,
            INPUT_PATH_ELISION,
            MESA_GL_THREAD,
            ASYNC_READBACK,
            MESH_INTERPOLATION,
            SHADER_UNIFORM,
            MATRIX_SCRATCH,
            IMAGE_ARCHIVE_REUSE,
            FLOAT_ARRAY_PARSE_CACHE,
            TEXTURE_UPLOAD_PREPARATION,
            WARP_POSITION_PROJECTION,
            REINIT_PAIR_ELISION,
            CLIP_MASK_DEDUPLICATION,
            CLIP_MASK_SCISSOR_CLEAR,
            EXTENDED_INTERPOLATION_INCREMENTAL_SETUP,
            MODEL_INSTANCE_COALESCE,
            INSPECTOR_PROPERTY_BYPASS,
            MESH_TRIANGULATION_HASH,
            TRIANGULATION_EDGE_INDEX,
            ATLAS_TILE_BBOX,
            ATLAS_CACHE_REUSE,
            CANVAS_COMPOSITE_ELISION,
            CLIP_MASK_PROBE,
            GL_GET_ERROR_ELISION,
            REDUNDANT_STATE_ELISION,
            SORTING_INFO_CACHE);

    static {
        validate(ALL);
    }

    private static final List<Optimization> PERSISTED =
            ALL.stream().filter(Optimization::persisted).toList();

    private static final List<Optimization> PLACED = PERSISTED.stream()
            .sorted(Comparator.comparingInt((Optimization entry) ->
                            SECTIONS.indexOf(entry.placement().orElseThrow().section()))
                    .thenComparingInt(entry -> entry.placement().orElseThrow().index()))
            .toList();

    private static final Map<String, Optimization> BY_KEY = indexPersisted(ALL, Optimization::key);

    private static final Map<String, Optimization> BY_CONTRIBUTION_ID =
            indexPersisted(ALL, Optimization::contributionId);

    private static final Map<String, Optimization> BY_PROTOCOL = indexProtocols(ALL);

    private OptimizationCatalog() {}

    /** All entries in declaration order; the launcher/installer/docs views derive from this. */
    public static List<Optimization> all() {
        return ALL;
    }

    /** The entries that persist a preference, i.e. the ones with a config key and a settings row. */
    public static List<Optimization> persisted() {
        return PERSISTED;
    }

    /**
     * The persisted entries in {@code settings-layout.txt} order, i.e. by
     * {@link #SECTIONS section} and then by render index.
     */
    public static List<Optimization> placed() {
        return PLACED;
    }

    /** @throws IllegalArgumentException when no entry declares {@code key} (write paths fail closed) */
    public static Optimization requireByKey(final String key) {
        final Optimization entry = BY_KEY.get(key);
        if (entry == null) throw new IllegalArgumentException("Unknown native optimization: " + key);
        return entry;
    }

    /** @return the entry for {@code key}, or empty for a non-optimization config key */
    public static Optional<Optimization> byKey(final String key) {
        return Optional.ofNullable(key == null ? null : BY_KEY.get(key));
    }

    /** @return the entry that reports {@code protocolId}, or empty for an unknown protocol */
    public static Optional<Optimization> byProtocol(final String protocolId) {
        return Optional.ofNullable(protocolId == null ? null : BY_PROTOCOL.get(protocolId));
    }

    /** @return the entry that renders the settings row {@code contributionId} */
    public static Optional<Optimization> byContributionId(final String contributionId) {
        return Optional.ofNullable(contributionId == null ? null : BY_CONTRIBUTION_ID.get(contributionId));
    }

    /** Every declared protocol id and the entry whose install outcome it proves. */
    public static Map<String, Optimization> protocols() {
        return BY_PROTOCOL;
    }

    /** {@code PERSISTED_LAUNCHER} keys in declaration order. */
    public static List<String> launcherFieldNames() {
        return ALL.stream()
                .filter(entry -> entry.access() == Optimization.Access.PERSISTED_LAUNCHER)
                .map(Optimization::key)
                .toList();
    }

    /**
     * The launcher-hosted preferences that store a boolean, i.e. the launcher fields the config
     * validator type-checks. The launcher's non-optimization companions ({@code cubismJvm},
     * {@code graalVmPath}, {@code zgc}, {@code memoryProfile}) are deliberately absent: each has
     * its own diagnostic code and value shape.
     */
    public static List<String> booleanLauncherFieldNames() {
        return launcherFieldNames();
    }

    /** {@code PERSISTED_ROOT} keys in declaration order. */
    public static List<String> rootFieldNames() {
        return ALL.stream()
                .filter(entry -> entry.access() == Optimization.Access.PERSISTED_ROOT)
                .map(Optimization::key)
                .toList();
    }

    private static Map<String, Optimization> indexPersisted(
            final List<Optimization> entries, final Function<Optimization, String> identity) {
        final Map<String, Optimization> index = new LinkedHashMap<>();
        for (final Optimization entry : entries) {
            final String value = identity.apply(entry);
            if (value.isEmpty()) continue;
            index.put(value, entry);
        }
        return Collections.unmodifiableMap(index);
    }

    private static Map<String, Optimization> indexProtocols(final List<Optimization> entries) {
        final Map<String, Optimization> index = new LinkedHashMap<>();
        for (final Optimization entry : entries) {
            for (final String protocol : entry.protocols()) index.put(protocol, entry);
        }
        return Collections.unmodifiableMap(index);
    }

    /**
     * Refuses a catalogue that cannot be described consistently. Runs once, at first touch, so a
     * mistake fails at class initialisation - naming both offenders - instead of at the first
     * launch that happens to reach the affected consumer.
     *
     * <p>Package-private so {@code OptimizationCatalogTest} can prove each refusal against a
     * two-row declaration without a second copy of the real catalogue.</p>
     */
    static void validate(final List<Optimization> entries) {
        final Set<String> keys = new LinkedHashSet<>();
        final Set<String> contributionIds = new LinkedHashSet<>();
        final Set<String> protocols = new LinkedHashSet<>();
        final Set<String> placements = new LinkedHashSet<>();
        for (final Optimization entry : entries) {
            final String name = describe(entry);
            validateIdentity(entry, name);
            if (!entry.key().isEmpty() && !keys.add(entry.key())) {
                throw new IllegalArgumentException("Two native optimizations declare the config key " + entry.key());
            }
            if (!entry.contributionId().isEmpty() && !contributionIds.add(entry.contributionId())) {
                throw new IllegalArgumentException(
                        "Two native optimizations declare the contribution id " + entry.contributionId());
            }
            if (entry.protocols().isEmpty()) {
                throw new IllegalArgumentException(name + " declares no diagnostics protocol");
            }
            for (final String protocol : entry.protocols()) {
                if (!protocols.add(protocol)) {
                    throw new IllegalArgumentException("Two native optimizations declare the protocol " + protocol);
                }
            }
            if (entry.overrideProperties().isEmpty()) {
                throw new IllegalArgumentException(name + " declares no -D override property");
            }
            if (entry.persisted() && !entry.property().equals("turboism.optimization." + entry.key())) {
                throw new IllegalArgumentException(name + " must be overridable as turboism.optimization." + entry.key()
                        + ", not " + entry.property());
            }
            entry.placement().ifPresent(placement -> {
                if (!SECTIONS.contains(placement.section())) {
                    throw new IllegalArgumentException(
                            name + " is placed in the unknown section " + placement.section());
                }
                if (!placements.add(placement.section() + '#' + placement.index())) {
                    throw new IllegalArgumentException(
                            name + " reuses the settings row " + placement.section() + '#' + placement.index());
                }
            });
        }
    }

    /** A persisted row must carry a config key, a contribution id and a placement; a probe must carry none. */
    private static void validateIdentity(final Optimization entry, final String name) {
        if (!entry.persisted()) {
            if (!entry.key().isEmpty()
                    || !entry.contributionId().isEmpty()
                    || entry.placement().isPresent()
                    || !entry.labelKey().isEmpty()
                    || !entry.tooltipKey().isEmpty()) {
                throw new IllegalArgumentException(name + " is diagnostic-only but declares a persisted identity");
            }
            return;
        }
        if (entry.key().isEmpty()) {
            throw new IllegalArgumentException(name + " is persisted but declares no config key");
        }
        if (entry.contributionId().isEmpty()) {
            throw new IllegalArgumentException(name + " is persisted but declares no contribution id");
        }
        if (entry.placement().isEmpty()) {
            throw new IllegalArgumentException(name + " is persisted but declares no settings placement");
        }
    }

    private static String describe(final Optimization entry) {
        if (!entry.key().isEmpty()) return "Native optimization " + entry.key();
        return "Native optimization " + String.join("/", entry.protocols());
    }
}
