package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical text transport for one algorithm's parameter set.
 *
 * <p>A parameter value travels as text — through a preset, a stored selection or a {@link
 * dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest} — so exactly one component must own the
 * mapping between that text, the declared bounds and a typed value. The grammar itself lives on
 * each {@link AlgorithmParameterDescriptor}, which validates a value when it is declared; this
 * codec is the runtime-side entry point that resolves a whole value map, converts a value into a
 * typed carrier, and normalizes preset text at registration time.</p>
 *
 * <p>Every method rejects an undeclared parameter id instead of ignoring it: a value that no
 * descriptor validates would silently reach a compute function it was never written for.</p>
 */
public final class AutoMeshValueCodec {

    private final Map<String, AlgorithmParameterDescriptor> parameters;

    private AutoMeshValueCodec(final Map<String, AlgorithmParameterDescriptor> parameters) {
        this.parameters = parameters;
    }

    /**
     * Renders one decimal in the canonical text of a declared parameter.
     *
     * <p>A slider answers a wider value than the parameter declares, so the value is rounded to the
     * declared decimals and stripped of trailing zeros. Every control therefore stores the same text
     * a preset would carry, and a preset stays selected while its values are unchanged.</p>
     *
     * @param value the value the control produced
     * @param decimals the declared fractional digits
     * @return the canonical text of that value
     */
    public static String canonicalDecimal(final double value, final int decimals) {
        return BigDecimal.valueOf(value)
                .setScale(decimals, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /**
     * Builds the codec for one algorithm's declared parameters.
     *
     * @param algorithm the algorithm whose schema bounds every value
     * @return the codec for that schema
     */
    public static AutoMeshValueCodec forAlgorithm(final AutoMeshAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        final LinkedHashMap<String, AlgorithmParameterDescriptor> declared = new LinkedHashMap<>();
        for (final AlgorithmParameterDescriptor parameter : algorithm.parameters()) {
            declared.put(parameter.id(), parameter);
        }
        return new AutoMeshValueCodec(declared);
    }

    /**
     * Returns {@code algorithm} with every preset value in canonical text.
     *
     * <p>Registration publishes this normalized form, so a stored or rendered preset never depends
     * on how the plugin happened to spell a number. The algorithm itself is returned unchanged when
     * its presets are already canonical.</p>
     *
     * @param algorithm the declared algorithm
     * @return the algorithm whose preset values are canonical
     * @throws IllegalArgumentException when a preset value fails its descriptor
     */
    public static AutoMeshAlgorithm canonicalPresets(final AutoMeshAlgorithm algorithm) {
        final AutoMeshValueCodec codec = forAlgorithm(algorithm);
        boolean changed = false;
        final List<AlgorithmPreset> presets =
                new ArrayList<>(algorithm.presets().size());
        for (final AlgorithmPreset preset : algorithm.presets()) {
            final LinkedHashMap<String, String> canonical = new LinkedHashMap<>();
            preset.values()
                    .forEach(
                            (parameterId, value) -> canonical.put(parameterId, codec.canonicalize(parameterId, value)));
            if (canonical.equals(preset.values())) {
                presets.add(preset);
            } else {
                presets.add(new AlgorithmPreset(preset.name(), canonical));
                changed = true;
            }
        }
        return changed
                ? new AutoMeshAlgorithm(
                        algorithm.id(),
                        algorithm.displayName(),
                        algorithm.description(),
                        algorithm.parameters(),
                        presets,
                        algorithm.supportsMultiKeyform(),
                        algorithm.compute())
                : algorithm;
    }

    /**
     * Validates one value and returns it in canonical form.
     *
     * @param parameterId the declared parameter the value belongs to
     * @param value the value text
     * @return the canonical text of that value
     * @throws IllegalArgumentException when the id is undeclared or the value fails its descriptor
     */
    public String canonicalize(final String parameterId, final String value) {
        return parameter(parameterId).canonicalize(value);
    }

    /**
     * Resolves a partial value map into the complete canonical map a compute function receives.
     *
     * @param overrides the values present in a preset or a selection
     * @return every declared parameter with its canonical value, defaults filled in
     * @throws IllegalArgumentException when an id is undeclared or a value fails its descriptor
     */
    public Map<String, String> resolve(final Map<String, String> overrides) {
        Objects.requireNonNull(overrides, "overrides");
        overrides.keySet().forEach(this::parameter);
        final LinkedHashMap<String, String> resolved = new LinkedHashMap<>();
        parameters.forEach((parameterId, parameter) -> {
            final String override = overrides.get(parameterId);
            resolved.put(
                    parameterId,
                    override == null ? parameter.canonicalDefaultValue() : parameter.canonicalize(override));
        });
        return Collections.unmodifiableMap(resolved);
    }

    /**
     * Converts one value into its typed carrier.
     *
     * @param parameterId the declared parameter the value belongs to
     * @param value the value text
     * @return the typed value
     * @throws IllegalArgumentException when the id is undeclared or the value fails its descriptor
     */
    public Value value(final String parameterId, final String value) {
        final AlgorithmParameterDescriptor parameter = parameter(parameterId);
        final String canonical = parameter.canonicalize(value);
        if (parameter instanceof AlgorithmParameterDescriptor.IntParameter) {
            return new Value.IntegerValue(parameterId, Integer.parseInt(canonical));
        }
        if (parameter instanceof AlgorithmParameterDescriptor.DoubleParameter) {
            return new Value.DecimalValue(parameterId, Double.parseDouble(canonical));
        }
        if (parameter instanceof AlgorithmParameterDescriptor.BoolParameter) {
            return new Value.BooleanValue(parameterId, Boolean.parseBoolean(canonical));
        }
        if (parameter instanceof AlgorithmParameterDescriptor.EnumParameter) {
            return new Value.EnumValue(parameterId, canonical);
        }
        return new Value.TextValue(parameterId, canonical);
    }

    private AlgorithmParameterDescriptor parameter(final String parameterId) {
        final AlgorithmParameterDescriptor parameter = parameters.get(parameterId);
        if (parameter == null) {
            throw new IllegalArgumentException("parameter is not declared by this algorithm: " + parameterId);
        }
        return parameter;
    }

    /** One typed parameter value, carrying the id it belongs to. */
    public sealed interface Value
            permits Value.IntegerValue, Value.DecimalValue, Value.BooleanValue, Value.EnumValue, Value.TextValue {

        /** @return the parameter id this value belongs to */
        String parameterId();

        /** Value of an {@link AlgorithmParameterDescriptor.IntParameter}. */
        record IntegerValue(String parameterId, int value) implements Value {}

        /** Value of an {@link AlgorithmParameterDescriptor.DoubleParameter}. */
        record DecimalValue(String parameterId, double value) implements Value {}

        /** Value of an {@link AlgorithmParameterDescriptor.BoolParameter}. */
        record BooleanValue(String parameterId, boolean value) implements Value {}

        /** Value of an {@link AlgorithmParameterDescriptor.EnumParameter}, as the chosen option value. */
        record EnumValue(String parameterId, String optionValue) implements Value {}

        /** Value of an {@link AlgorithmParameterDescriptor.TextParameter}. */
        record TextValue(String parameterId, String value) implements Value {}
    }
}
