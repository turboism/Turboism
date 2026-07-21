package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.CorePublicApiSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.VerifiedMethodCallSite;

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

        final CoreCanvasSnapshot canvasSnapshot = readCanvas(canvas);
        final List<CoreParameterDefinition> parameterDefinitions =
            readParameters(parameters);
        return new CoreStructuralSnapshot(
            generation,
            modelIdentity,
            providerId,
            artifactProfile,
            canvasSnapshot,
            parameterDefinitions
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
        final int[] copy = array.clone();
        for (int element : copy) {
            if (element < 0) {
                throw invalid(message);
            }
        }
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
