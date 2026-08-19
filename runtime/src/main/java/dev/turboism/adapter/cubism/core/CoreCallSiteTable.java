package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.selector.CorePublicApiSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.VerifiedMethodCallSite;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Profile-specific, prebound call sites for the public Core structural-read surface.
 *
 * <p>Every reflection member is resolved before this table is admitted. Projection copies only
 * scalars and adapter-owned lists; no Core object or Core-owned array crosses the table.</p>
 */
final class CoreCallSiteTable implements AutoCloseable {

    private final Map<String, VerifiedMethodCallSite> callSites;
    private boolean closed;

    private CoreCallSiteTable(
        final Map<String, VerifiedMethodCallSite> callSites
    ) {
        this.callSites = Collections.unmodifiableMap(
            new LinkedHashMap<>(callSites)
        );
    }

    static CoreCallSiteTable bind(
        final VerifiedMemberResolver resolver,
        final String artifactProfile
    ) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(artifactProfile, "artifactProfile");

        final Set<String> aliases = CorePublicApiSelectorContract
            .structuralMethodAliasesFor(artifactProfile)
            .orElseThrow(() -> invalid(
                "Core structural profile is outside the generated selector contract."
            ));
        final Map<String, VerifiedMethodCallSite> bound = new LinkedHashMap<>();
        try {
            aliases.stream()
                .sorted()
                .forEach(alias -> bound.put(alias, resolver.bind(alias)));
            return new CoreCallSiteTable(bound);
        } catch (RuntimeException exception) {
            closeReverse(new ArrayList<>(bound.values()));
            throw exception;
        }
    }

    CoreStructuralSnapshot project(
        final Object rawModel,
        final long generation,
        final String modelIdentity,
        final String providerId,
        final String artifactProfile
    ) {
        if (closed) {
            throw invalid("Core structural call-site table is closed.");
        }
        final Object canvas = requireObject(
            callSite(CorePublicApiSelectorContract.MODEL_GET_CANVAS_INFO)
                .invoke(rawModel),
            "Core canvas information is unavailable."
        );
        final Object parameters = requireObject(
            callSite(CorePublicApiSelectorContract.MODEL_GET_PARAMETERS)
                .invoke(rawModel),
            "Core parameters are unavailable."
        );
        final Object parts = requireObject(
            callSite(CorePublicApiSelectorContract.MODEL_GET_PARTS).invoke(rawModel),
            "Core parts are unavailable."
        );
        final Object drawables = requireObject(
            callSite(CorePublicApiSelectorContract.MODEL_GET_DRAWABLES).invoke(rawModel),
            "Core drawables are unavailable."
        );
        final Object deformers = requireObject(
            callSite(CorePublicApiSelectorContract.MODEL_GET_DEFORMERS).invoke(rawModel),
            "Core deformers are unavailable."
        );
        final Object glues = requireObject(
            callSite(CorePublicApiSelectorContract.MODEL_GET_GLUES).invoke(rawModel),
            "Core glues are unavailable."
        );

        final CoreCanvasSnapshot canvasSnapshot = readCanvas(canvas);
        final List<CoreParameterDefinition> parameterDefinitions = readParameters(parameters);
        final List<CorePartDefinition> partDefinitions = readParts(parts);
        final List<CoreDrawableDefinition> drawableDefinitions = readDrawables(rawModel, drawables);
        final List<CoreDeformerDefinition> deformerDefinitions = readDeformers(deformers);
        final List<CoreGlueDefinition> glueDefinitions = readGlues(glues);
        validateReferences(
            parameterDefinitions.size(),
            partDefinitions.size(),
            drawableDefinitions,
            deformerDefinitions,
            glueDefinitions
        );
        return new CoreStructuralSnapshot(
            generation,
            modelIdentity,
            providerId,
            artifactProfile,
            canvasSnapshot,
            parameterDefinitions,
            partDefinitions,
            drawableDefinitions,
            deformerDefinitions,
            glueDefinitions
        );
    }

    private CoreCanvasSnapshot readCanvas(final Object canvas) {
        final float[] size = exactFloatArray(
            callSite(CorePublicApiSelectorContract.CANVAS_GET_SIZE_IN_PIXELS)
                .invoke(canvas),
            2,
            "Core canvas size has an invalid representation."
        );
        final float[] origin = exactFloatArray(
            callSite(CorePublicApiSelectorContract.CANVAS_GET_ORIGIN_IN_PIXELS)
                .invoke(canvas),
            2,
            "Core canvas origin has an invalid representation."
        );
        final Object pixelsPerUnitValue = callSite(
            CorePublicApiSelectorContract.CANVAS_GET_PIXELS_PER_UNIT
        ).invoke(canvas);
        if (!(pixelsPerUnitValue instanceof Float pixelsPerUnit)
            || !Float.isFinite(pixelsPerUnit)) {
            throw invalid("Core canvas scale has an invalid representation.");
        }
        return new CoreCanvasSnapshot(
            size[0],
            size[1],
            origin[0],
            origin[1],
            pixelsPerUnit
        );
    }

    private List<CoreParameterDefinition> readParameters(final Object parameters) {
        final Object countValue = callSite(
            CorePublicApiSelectorContract.PARAMETERS_GET_COUNT
        ).invoke(parameters);
        if (!(countValue instanceof Integer count) || count < 0) {
            throw invalid("Core parameter count has an invalid representation.");
        }

        final String[] ids = exactStringArray(
            callSite(CorePublicApiSelectorContract.PARAMETERS_GET_IDS)
                .invoke(parameters),
            count,
            "Core parameter identifiers have an invalid representation."
        );
        final Object[] types = exactObjectArray(
            callSite(CorePublicApiSelectorContract.PARAMETERS_GET_TYPES)
                .invoke(parameters),
            count,
            "Core parameter types have an invalid representation."
        );
        final float[] minimumValues = exactFloatArray(
            callSite(CorePublicApiSelectorContract.PARAMETERS_GET_MINIMUM_VALUES)
                .invoke(parameters),
            count,
            "Core parameter minimum values have an invalid representation."
        );
        final float[] maximumValues = exactFloatArray(
            callSite(CorePublicApiSelectorContract.PARAMETERS_GET_MAXIMUM_VALUES)
                .invoke(parameters),
            count,
            "Core parameter maximum values have an invalid representation."
        );
        final float[] defaultValues = exactFloatArray(
            callSite(CorePublicApiSelectorContract.PARAMETERS_GET_DEFAULT_VALUES)
                .invoke(parameters),
            count,
            "Core parameter default values have an invalid representation."
        );
        final float[] currentValues = exactFloatArray(
            callSite(CorePublicApiSelectorContract.PARAMETERS_GET_VALUES)
                .invoke(parameters),
            count,
            "Core parameter current values have an invalid representation."
        );
        final int[] keyCounts = exactIntArray(
            callSite(CorePublicApiSelectorContract.PARAMETERS_GET_KEY_COUNTS)
                .invoke(parameters),
            count,
            "Core parameter key counts have an invalid representation."
        );
        final float[][] keyValues = exactKeyValues(
            callSite(CorePublicApiSelectorContract.PARAMETERS_GET_KEY_VALUES)
                .invoke(parameters),
            count,
            keyCounts
        );
        final boolean[] repeats = optionalCallSite(
            CorePublicApiSelectorContract.PARAMETERS_GET_REPEATS
        )
            .map(callSite -> exactBooleanArray(
                callSite.invoke(parameters),
                count,
                "Core parameter repeat values have an invalid representation."
            ))
            .orElse(null);

        final List<CoreParameterDefinition> definitions = new ArrayList<>(count);
        final HashSet<String> seenIds = new HashSet<>(count);
        for (int index = 0; index < count; index++) {
            final String id = ids[index];
            if (id == null || id.isBlank()) {
                throw invalid("Core parameter identifier is unavailable.");
            }
            if (!seenIds.add(id)) {
                throw invalid("Core parameter identifiers are not unique.");
            }
            final Object type = requireObject(
                types[index],
                "Core parameter type is unavailable."
            );
            final Object typeNumberValue = callSite(
                CorePublicApiSelectorContract.PARAMETER_TYPE_GET_NUMBER
            ).invoke(type);
            if (!(typeNumberValue instanceof Integer typeNumber)) {
                throw invalid("Core parameter type has an invalid representation.");
            }
            final List<Float> copiedKeyValues =
                new ArrayList<>(keyValues[index].length);
            for (float keyValue : keyValues[index]) {
                copiedKeyValues.add(keyValue);
            }
            definitions.add(new CoreParameterDefinition(
                id,
                typeNumber,
                minimumValues[index],
                maximumValues[index],
                defaultValues[index],
                currentValues[index],
                copiedKeyValues,
                repeats == null
                    ? Optional.empty()
                    : Optional.of(repeats[index])
            ));
        }
        return List.copyOf(definitions);
    }

    private List<CorePartDefinition> readParts(final Object parts) {
        final int count = count(parts, CorePublicApiSelectorContract.PARTS_COUNT, "Part");
        final String[] ids = exactStringArray(callSite(CorePublicApiSelectorContract.PARTS_IDS).invoke(parts), count, "Core Part identifiers have an invalid representation.");
        final float[] opacities = exactFloatArray(callSite(CorePublicApiSelectorContract.PARTS_OPACITIES).invoke(parts), count, "Core Part opacities have an invalid representation.");
        final int[] parents = exactIndexArray(callSite(CorePublicApiSelectorContract.PARTS_PARENT_PART_INDICES).invoke(parts), count, "Core Part parent indices have an invalid representation.");
        final List<CorePartDefinition> values = new ArrayList<>(count);
        requireUniqueIds(ids, "Part");
        for (int index = 0; index < count; index++) {
            requireOptionalIndex(parents[index], count, "Core Part parent index is out of range.");
            values.add(new CorePartDefinition(ids[index], opacities[index], parents[index]));
        }
        return List.copyOf(values);
    }

    private List<CoreDrawableDefinition> readDrawables(final Object rawModel, final Object drawables) {
        final int count = count(drawables, CorePublicApiSelectorContract.DRAWABLES_COUNT, "Drawable");
        final String[] ids = exactStringArray(callSite(CorePublicApiSelectorContract.DRAWABLES_IDS).invoke(drawables), count, "Core Drawable identifiers have an invalid representation.");
        requireUniqueIds(ids, "Drawable");
        final byte[] constantFlags = exactByteArray(callSite(CorePublicApiSelectorContract.DRAWABLES_CONSTANT_FLAGS).invoke(drawables), count, "Core Drawable constant flags have an invalid representation.");
        final byte[] dynamicFlags = exactByteArray(callSite(CorePublicApiSelectorContract.DRAWABLES_DYNAMIC_FLAGS).invoke(drawables), count, "Core Drawable dynamic flags have an invalid representation.");
        final int[] textureIndices = exactNonNegativeIntArray(callSite(CorePublicApiSelectorContract.DRAWABLES_TEXTURE_INDICES).invoke(drawables), count, "Core Drawable texture indices have an invalid representation.");
        final int[] drawOrders = exactIntArray(callSite(CorePublicApiSelectorContract.DRAWABLES_DRAW_ORDERS).invoke(drawables), count, "Core Drawable draw orders have an invalid representation.");
        final int[] renderOrders = optionalCallSite(CorePublicApiSelectorContract.DRAWABLES_RENDER_ORDERS)
            .map(site -> exactIntArray(site.invoke(drawables), count, "Core Drawable render orders have an invalid representation."))
            .orElseGet(() -> optionalCallSite(CorePublicApiSelectorContract.MODEL_GET_RENDER_ORDERS)
                .map(site -> exactIntArray(site.invoke(rawModel), count, "Core Drawable render orders have an invalid representation."))
                .orElseThrow(() -> invalid("Core Drawable render orders are unavailable.")));
        final Optional<VerifiedMethodCallSite> blendModeCallSite = optionalCallSite(CorePublicApiSelectorContract.DRAWABLES_BLEND_MODES);
        final int[] blendModes = blendModeCallSite
            .map(site -> exactIntArray(site.invoke(drawables), count, "Core Drawable blend modes have an invalid representation."))
            .orElseGet(() -> new int[count]);
        final float[] opacities = exactFloatArray(callSite(CorePublicApiSelectorContract.DRAWABLES_OPACITIES).invoke(drawables), count, "Core Drawable opacities have an invalid representation.");
        final int[] maskCounts = exactNonNegativeIntArray(callSite(CorePublicApiSelectorContract.DRAWABLES_MASK_COUNTS).invoke(drawables), count, "Core Drawable mask counts have an invalid representation.");
        final int[][] masks = exactNestedIntArrays(callSite(CorePublicApiSelectorContract.DRAWABLES_MASKS).invoke(drawables), count, maskCounts, "Core Drawable masks have an invalid representation.");
        final int[] vertexCounts = exactNonNegativeIntArray(callSite(CorePublicApiSelectorContract.DRAWABLES_VERTEX_COUNTS).invoke(drawables), count, "Core Drawable vertex counts have an invalid representation.");
        final float[][] positions = exactNestedFloatArrays(callSite(CorePublicApiSelectorContract.DRAWABLES_VERTEX_POSITIONS).invoke(drawables), count, doubled(vertexCounts), "Core Drawable positions have an invalid representation.");
        final float[][] uvs = exactNestedFloatArrays(callSite(CorePublicApiSelectorContract.DRAWABLES_VERTEX_UVS).invoke(drawables), count, doubled(vertexCounts), "Core Drawable UVs have an invalid representation.");
        final int[] indexCounts = exactNonNegativeIntArray(callSite(CorePublicApiSelectorContract.DRAWABLES_INDEX_COUNTS).invoke(drawables), count, "Core Drawable index counts have an invalid representation.");
        final short[][] indices = exactNestedShortArrays(callSite(CorePublicApiSelectorContract.DRAWABLES_INDICES).invoke(drawables), count, indexCounts, "Core Drawable indices have an invalid representation.");
        final float[][] multiplyColors = exactNestedFloatArrays(callSite(CorePublicApiSelectorContract.DRAWABLES_MULTIPLY_COLORS).invoke(drawables), count, filled(count, 4), "Core Drawable multiply colors have an invalid representation.");
        final float[][] screenColors = exactNestedFloatArrays(callSite(CorePublicApiSelectorContract.DRAWABLES_SCREEN_COLORS).invoke(drawables), count, filled(count, 4), "Core Drawable screen colors have an invalid representation.");
        final int[] parentParts = exactIndexArray(callSite(CorePublicApiSelectorContract.DRAWABLES_PARENT_PART_INDICES).invoke(drawables), count, "Core Drawable Part indices have an invalid representation.");
        final int[] parentDeformers = exactIndexArray(callSite(CorePublicApiSelectorContract.DRAWABLES_PARENT_DEFORMER_INDICES).invoke(drawables), count, "Core Drawable Deformer indices have an invalid representation.");
        final int[] parameterCounts = exactNonNegativeIntArray(callSite(CorePublicApiSelectorContract.DRAWABLES_PARAMETER_COUNTS).invoke(drawables), count, "Core Drawable parameter counts have an invalid representation.");
        final int[][] parameters = exactNestedIntArrays(callSite(CorePublicApiSelectorContract.DRAWABLES_PARAMETERS).invoke(drawables), count, parameterCounts, "Core Drawable parameters have an invalid representation.");
        final List<CoreDrawableDefinition> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            validateFlags(constantFlags[index], dynamicFlags[index]);
            values.add(new CoreDrawableDefinition(
                ids[index], constantFlags[index], dynamicFlags[index],
                blendMode(blendModes[index], constantFlags[index], blendModeCallSite.isPresent()),
                textureIndices[index], drawOrders[index], renderOrders[index], opacities[index],
                ints(masks[index]), floats(positions[index]), floats(uvs[index]), shorts(indices[index]),
                color(multiplyColors[index]), color(screenColors[index]), parentParts[index],
                parentDeformers[index], ints(parameters[index])
            ));
        }
        return List.copyOf(values);
    }

    private List<CoreDeformerDefinition> readDeformers(final Object deformers) {
        final int count = count(deformers, CorePublicApiSelectorContract.DEFORMERS_COUNT, "Deformer");
        final String[] ids = exactStringArray(callSite(CorePublicApiSelectorContract.DEFORMERS_IDS).invoke(deformers), count, "Core Deformer identifiers have an invalid representation.");
        requireUniqueIds(ids, "Deformer");
        final int[] parents = exactIndexArray(callSite(CorePublicApiSelectorContract.DEFORMERS_PARENT_DEFORMER_INDICES).invoke(deformers), count, "Core Deformer parent indices have an invalid representation.");
        final int[] parameterCounts = exactNonNegativeIntArray(callSite(CorePublicApiSelectorContract.DEFORMERS_PARAMETER_COUNTS).invoke(deformers), count, "Core Deformer parameter counts have an invalid representation.");
        final int[][] parameters = exactNestedIntArrays(callSite(CorePublicApiSelectorContract.DEFORMERS_PARAMETERS).invoke(deformers), count, parameterCounts, "Core Deformer parameters have an invalid representation.");
        final List<CoreDeformerDefinition> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            requireOptionalIndex(parents[index], count, "Core Deformer parent index is out of range.");
            values.add(new CoreDeformerDefinition(ids[index], parents[index], ints(parameters[index])));
        }
        return List.copyOf(values);
    }

    private List<CoreGlueDefinition> readGlues(final Object glues) {
        final int count = count(glues, CorePublicApiSelectorContract.GLUES_COUNT, "Glue");
        final String[] ids = exactStringArray(callSite(CorePublicApiSelectorContract.GLUES_IDS).invoke(glues), count, "Core Glue identifiers have an invalid representation.");
        requireUniqueIds(ids, "Glue");
        final int[] drawablesA = exactNonNegativeIntArray(callSite(CorePublicApiSelectorContract.GLUES_DRAWABLES_A).invoke(glues), count, "Core Glue Drawable A indices have an invalid representation.");
        final int[] drawablesB = exactNonNegativeIntArray(callSite(CorePublicApiSelectorContract.GLUES_DRAWABLES_B).invoke(glues), count, "Core Glue Drawable B indices have an invalid representation.");
        final int[] parameterCounts = exactNonNegativeIntArray(callSite(CorePublicApiSelectorContract.GLUES_PARAMETER_COUNTS).invoke(glues), count, "Core Glue parameter counts have an invalid representation.");
        final int[][] parameters = exactNestedIntArrays(callSite(CorePublicApiSelectorContract.GLUES_PARAMETERS).invoke(glues), count, parameterCounts, "Core Glue parameters have an invalid representation.");
        final List<CoreGlueDefinition> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(new CoreGlueDefinition(ids[index], drawablesA[index], drawablesB[index], ints(parameters[index])));
        }
        return List.copyOf(values);
    }

    private static void validateReferences(
        final int parameterCount,
        final int partCount,
        final List<CoreDrawableDefinition> drawables,
        final List<CoreDeformerDefinition> deformers,
        final List<CoreGlueDefinition> glues
    ) {
        for (CoreDrawableDefinition drawable : drawables) {
            requireOptionalIndex(drawable.parentPartIndex(), partCount, "Core Drawable parent Part index is out of range.");
            requireOptionalIndex(drawable.parentDeformerIndex(), deformers.size(), "Core Drawable parent Deformer index is out of range.");
            drawable.masks().forEach(index -> requireIndex(index, drawables.size(), "Core Drawable mask index is out of range."));
            drawable.parameters().forEach(index -> requireIndex(index, parameterCount, "Core Drawable parameter index is out of range."));
            drawable.indices().forEach(index -> requireIndex(index, drawable.vertexPositions().size() / 2, "Core Drawable vertex index is out of range."));
        }
        deformers.forEach(deformer -> deformer.parameters().forEach(index -> requireIndex(index, parameterCount, "Core Deformer parameter index is out of range.")));
        for (CoreGlueDefinition glue : glues) {
            requireIndex(glue.drawableA(), drawables.size(), "Core Glue Drawable A index is out of range.");
            requireIndex(glue.drawableB(), drawables.size(), "Core Glue Drawable B index is out of range.");
            glue.parameters().forEach(index -> requireIndex(index, parameterCount, "Core Glue parameter index is out of range."));
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeReverse(new ArrayList<>(callSites.values()));
    }

    private VerifiedMethodCallSite callSite(final String alias) {
        final VerifiedMethodCallSite callSite = callSites.get(alias);
        if (callSite == null) {
            throw invalid("Core structural call site is unavailable: " + alias);
        }
        return callSite;
    }

    private Optional<VerifiedMethodCallSite> optionalCallSite(
        final String alias
    ) {
        return Optional.ofNullable(callSites.get(alias));
    }

    private static float[] exactFloatArray(
        final Object value,
        final int expectedLength,
        final String message
    ) {
        if (!(value instanceof float[] array) || array.length != expectedLength) {
            throw invalid(message);
        }
        final float[] copy = array.clone();
        for (float element : copy) {
            if (!Float.isFinite(element)) {
                throw invalid(message);
            }
        }
        return copy;
    }

    private static int[] exactIntArray(
        final Object value,
        final int expectedLength,
        final String message
    ) {
        if (!(value instanceof int[] array) || array.length != expectedLength) {
            throw invalid(message);
        }
        return array.clone();
    }

    private static int[] exactNonNegativeIntArray(final Object value, final int expectedLength, final String message) {
        final int[] copy = exactIntArray(value, expectedLength, message);
        for (int element : copy) if (element < 0) throw invalid(message);
        return copy;
    }

    private static int[] exactIndexArray(final Object value, final int expectedLength, final String message) {
        final int[] copy = exactIntArray(value, expectedLength, message);
        for (int element : copy) if (element < -1) throw invalid(message);
        return copy;
    }

    private static byte[] exactByteArray(final Object value, final int expectedLength, final String message) {
        if (!(value instanceof byte[] array) || array.length != expectedLength) throw invalid(message);
        return array.clone();
    }

    private static int[][] exactNestedIntArrays(final Object value, final int count, final int[] lengths, final String message) {
        if (!(value instanceof int[][] arrays) || arrays.length != count) throw invalid(message);
        final int[][] copy = new int[count][];
        for (int index = 0; index < count; index++) {
            if (arrays[index] == null || arrays[index].length != lengths[index]) throw invalid(message);
            copy[index] = arrays[index].clone();
            for (int element : copy[index]) if (element < 0) throw invalid(message);
        }
        return copy;
    }

    private static short[][] exactNestedShortArrays(final Object value, final int count, final int[] lengths, final String message) {
        if (!(value instanceof short[][] arrays) || arrays.length != count) throw invalid(message);
        final short[][] copy = new short[count][];
        for (int index = 0; index < count; index++) {
            if (arrays[index] == null || arrays[index].length != lengths[index]) throw invalid(message);
            copy[index] = arrays[index].clone();
            for (short element : copy[index]) if (element < 0) throw invalid(message);
        }
        return copy;
    }

    private static float[][] exactNestedFloatArrays(final Object value, final int count, final int[] lengths, final String message) {
        if (!(value instanceof float[][] arrays) || arrays.length != count) throw invalid(message);
        final float[][] copy = new float[count][];
        for (int index = 0; index < count; index++) copy[index] = exactFloatArray(arrays[index], lengths[index], message);
        return copy;
    }

    private static boolean[] exactBooleanArray(
        final Object value,
        final int expectedLength,
        final String message
    ) {
        if (!(value instanceof boolean[] array) || array.length != expectedLength) {
            throw invalid(message);
        }
        return array.clone();
    }

    private static String[] exactStringArray(
        final Object value,
        final int expectedLength,
        final String message
    ) {
        if (!(value instanceof String[] array) || array.length != expectedLength) {
            throw invalid(message);
        }
        return array.clone();
    }

    private static Object[] exactObjectArray(
        final Object value,
        final int expectedLength,
        final String message
    ) {
        if (!(value instanceof Object[] array) || array.length != expectedLength) {
            throw invalid(message);
        }
        return array.clone();
    }

    private static float[][] exactKeyValues(
        final Object value,
        final int expectedLength,
        final int[] keyCounts
    ) {
        if (!(value instanceof float[][] rows) || rows.length != expectedLength) {
            throw invalid("Core parameter key values have an invalid representation.");
        }
        final float[][] copy = new float[expectedLength][];
        for (int index = 0; index < expectedLength; index++) {
            final float[] row = rows[index];
            if (row == null || row.length != keyCounts[index]) {
                throw invalid(
                    "Core parameter key values do not match their declared counts."
                );
            }
            copy[index] = row.clone();
            for (float element : copy[index]) {
                if (!Float.isFinite(element)) {
                    throw invalid(
                        "Core parameter key values have an invalid representation."
                    );
                }
            }
        }
        return copy;
    }

    private static Object requireObject(final Object value, final String message) {
        if (value == null) {
            throw invalid(message);
        }
        return value;
    }

    private int count(final Object owner, final String alias, final String family) {
        final Object value = callSite(alias).invoke(owner);
        if (!(value instanceof Integer count) || count < 0) throw invalid("Core " + family + " count has an invalid representation.");
        return count;
    }

    private static void requireUniqueIds(final String[] ids, final String family) {
        final Set<String> seen = new HashSet<>();
        for (String id : ids) if (id == null || id.isBlank() || !seen.add(id)) throw invalid("Core " + family + " identifiers are unavailable or not unique.");
    }

    private static void requireOptionalIndex(final int index, final int size, final String message) {
        if (index < -1 || index >= size) throw invalid(message);
    }

    private static void requireIndex(final int index, final int size, final String message) {
        if (index < 0 || index >= size) throw invalid(message);
    }

    private static int[] doubled(final int[] values) {
        final int[] result = new int[values.length];
        for (int index = 0; index < values.length; index++) result[index] = Math.multiplyExact(values[index], 2);
        return result;
    }

    private static int[] filled(final int count, final int value) {
        final int[] values = new int[count];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static List<Integer> ints(final int[] values) { return java.util.Arrays.stream(values).boxed().toList(); }
    private static List<Integer> shorts(final short[] values) { final List<Integer> copy = new ArrayList<>(values.length); for (short value : values) copy.add((int) value); return List.copyOf(copy); }
    private static List<Float> floats(final float[] values) { final List<Float> copy = new ArrayList<>(values.length); for (float value : values) copy.add(value); return List.copyOf(copy); }
    private static Color color(final float[] values) { return new Color(values[0], values[1], values[2], values[3]); }
    private static void validateFlags(final byte constantFlags, final byte dynamicFlags) {
        if ((Byte.toUnsignedInt(constantFlags) & ~0x0F) != 0) {
            throw invalid("Core Drawable constant flags contain unknown bits.");
        }
        if ((Byte.toUnsignedInt(dynamicFlags) & ~0x7F) != 0) {
            throw invalid("Core Drawable dynamic flags contain unknown bits.");
        }
        final int flags = Byte.toUnsignedInt(constantFlags);
        if ((flags & 0x03) == 0x03) {
            throw invalid("Core Drawable blend flags are contradictory.");
        }
    }

    private static BlendMode blendMode(
        final int value,
        final byte constantFlags,
        final boolean hasBlendModes
    ) {
        if (!hasBlendModes) {
            // Exact Core public ConstantFlag values: BLEND_ADDITIVE=1, BLEND_MULTIPLICATIVE=2,
            // IS_DOUBLE_SIDED=4, and IS_INVERTED_MASK=8.
            final int flags = Byte.toUnsignedInt(constantFlags);
            final boolean additive = (flags & 1) != 0;
            final boolean multiplicative = (flags & 2) != 0;
            if (additive && multiplicative) {
                throw invalid("Core Drawable blend flags are contradictory.");
            }
            return additive
                ? BlendMode.ADDITIVE
                : multiplicative ? BlendMode.MULTIPLICATIVE : BlendMode.NORMAL;
        }
        return switch (value) {
            case 0 -> BlendMode.NORMAL;
            case 1 -> BlendMode.ADDITIVE;
            case 2 -> BlendMode.MULTIPLICATIVE;
            default -> throw invalid("Core Drawable blend mode contains an unknown value.");
        };
    }

    private static void closeReverse(
        final List<VerifiedMethodCallSite> callSites
    ) {
        final List<VerifiedMethodCallSite> reversed = new ArrayList<>(callSites);
        Collections.reverse(reversed);
        reversed.forEach(VerifiedMethodCallSite::close);
    }

    private static CoreStructuralValidationException invalid(
        final String message
    ) {
        return new CoreStructuralValidationException(message);
    }
}
