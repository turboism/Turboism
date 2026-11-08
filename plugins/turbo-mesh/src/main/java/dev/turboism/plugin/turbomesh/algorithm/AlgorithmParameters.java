package dev.turboism.plugin.turbomesh.algorithm;

import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed reader for the parameter text one auto-mesh request carries.
 *
 * <p>Values arrive as canonical text that the runtime validated against the algorithm schema. This
 * reader converts that text with the descriptor of the same algorithm, so the bounds live in one
 * place and a plugin parameter cannot drift from the control the runtime renders. A parameter that
 * the request omits resolves to the default its descriptor declares.</p>
 */
final class AlgorithmParameters {

    private final Map<String, AlgorithmParameterDescriptor> descriptors = new HashMap<>();
    private final Map<String, String> values;

    private AlgorithmParameters(final List<AlgorithmParameterDescriptor> parameters, final Map<String, String> values) {
        for (final AlgorithmParameterDescriptor parameter : parameters) {
            descriptors.put(parameter.id(), parameter);
        }
        this.values = values;
    }

    /**
     * Builds the reader for one algorithm's schema and one request's values.
     *
     * @param parameters the declared parameters of the running algorithm
     * @param values the canonical values the request resolved
     * @return the reader
     */
    static AlgorithmParameters of(
            final List<AlgorithmParameterDescriptor> parameters, final Map<String, String> values) {
        return new AlgorithmParameters(parameters, values);
    }

    /**
     * Returns canonical decimal text for a numeric preset value.
     *
     * <p>Presets are declared from the domain constants the planner was verified against, so their
     * text has to reach the descriptor grammar without a second hand-written table. The form is the
     * one {@link AlgorithmParameterDescriptor#canonicalize(String)} returns, which keeps a preset
     * identical to the text the runtime stores for the same value.</p>
     *
     * @param value the numeric value to publish
     * @return the canonical decimal text of {@code value}
     */
    static String canonicalDecimal(final double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * Reads an integer parameter.
     *
     * @param id declared parameter id
     * @return the request value, or the declared default when the request omits it
     * @throws IllegalArgumentException when the id is undeclared or the text is not an integer
     */
    int integer(final String id) {
        return Integer.parseInt(canonical(id));
    }

    /**
     * Reads a decimal parameter.
     *
     * @param id declared parameter id
     * @return the request value, or the declared default when the request omits it
     * @throws IllegalArgumentException when the id is undeclared or the text is not a number
     */
    double decimal(final String id) {
        return Double.parseDouble(canonical(id));
    }

    /**
     * Reads a boolean parameter.
     *
     * @param id declared parameter id
     * @return the request value, or the declared default when the request omits it
     * @throws IllegalArgumentException when the id is undeclared or the text is not a boolean
     */
    boolean flag(final String id) {
        final String value = canonical(id);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("parameter " + id + " is not a boolean");
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * Reads an enumerated parameter.
     *
     * @param id declared parameter id
     * @return the selected option value, or the declared default when the request omits it
     * @throws IllegalArgumentException when the id is undeclared or the text names no option
     */
    String option(final String id) {
        return canonical(id);
    }

    private String canonical(final String id) {
        final AlgorithmParameterDescriptor descriptor = descriptors.get(id);
        if (descriptor == null) {
            throw new IllegalArgumentException("parameter " + id + " is not declared by this algorithm");
        }
        final String value = values.get(id);
        return value == null ? descriptor.canonicalDefaultValue() : descriptor.canonicalize(value);
    }
}
