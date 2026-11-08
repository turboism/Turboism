package dev.turboism.sdk.cubism.mesh.automesh;

import dev.turboism.sdk.CubismEditor;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * One typed placement parameter an auto-mesh algorithm declares.
 *
 * <p>A descriptor is the whole contract between the plugin and the framework: the runtime renders
 * the control from it, validates user input against it, and stores preset values as canonical
 * text. The plugin therefore never parses unchecked text for its own parameters.</p>
 *
 * <p>Canonical text is the one form a value takes in {@link AlgorithmPreset} values and in {@link
 * AutoMeshRequest#parameterValues()}: a plain decimal integer, a plain decimal number with at most
 * the declared {@code decimals} fractional digits, {@code true} or {@code false}, an exact option
 * value, or the text itself. {@link #canonicalize(String)} both validates and normalizes that form,
 * so a successful call means the value is inside bounds and step-aligned.</p>
 *
 * <p>Values that cannot be described by a static descriptor — a parameter that depends on the
 * selected ArtMesh, for example — are deliberately out of scope: the framework validates every
 * declared parameter, and a descriptor it cannot validate would make the native selector
 * untrustworthy.</p>
 */
@CubismEditor
public sealed interface AlgorithmParameterDescriptor
        permits AlgorithmParameterDescriptor.IntParameter,
                AlgorithmParameterDescriptor.DoubleParameter,
                AlgorithmParameterDescriptor.BoolParameter,
                AlgorithmParameterDescriptor.EnumParameter,
                AlgorithmParameterDescriptor.TextParameter {

    /** Most selectable options a {@link EnumParameter} may declare. */
    int MAX_OPTIONS = 128;

    /** Most fractional digits a {@link DoubleParameter} may declare. */
    int MAX_DECIMALS = 9;

    /** Stable key of this parameter inside its algorithm. */
    String id();

    /** User-visible label; the plugin localizes it and the runtime renders it verbatim. */
    String label();

    /** Optional supporting text; an empty string means the parameter carries no description. */
    String description();

    /**
     * Returns the canonical text of the declared default value.
     *
     * @return the default value in canonical form
     */
    String canonicalDefaultValue();

    /**
     * Validates one value against this descriptor and returns its canonical text.
     *
     * @param value the value text supplied by a preset, a stored selection or a caller
     * @return the same value in canonical form
     * @throws IllegalArgumentException when the text is not this kind's grammar, is outside
     *     {@code [min, max]}, is not step-aligned, or names an unknown option
     * @throws NullPointerException when {@code value} is null
     */
    String canonicalize(String value);

    /** Integer parameter rendered as a stepper or a slider over {@code [min, max]}. */
    record IntParameter(String id, String label, String description, int min, int max, int step, int defaultValue)
            implements AlgorithmParameterDescriptor {

        public IntParameter {
            id = AutoMeshContracts.requireId(id, "id");
            label = AutoMeshContracts.requireText(label, "label", AutoMeshContracts.MAX_LABEL_LENGTH);
            description = AutoMeshContracts.requireDescription(description);
            if (min >= max) {
                throw new IllegalArgumentException("min must be less than max");
            }
            if (step <= 0) {
                throw new IllegalArgumentException("step must be positive");
            }
            if (defaultValue < min || defaultValue > max) {
                throw new IllegalArgumentException("defaultValue must be inside [min, max]");
            }
            if ((defaultValue - (long) min) % step != 0) {
                throw new IllegalArgumentException("defaultValue must align to step from min");
            }
        }

        @Override
        public String canonicalDefaultValue() {
            return Integer.toString(defaultValue);
        }

        @Override
        public String canonicalize(final String value) {
            final long parsed = AutoMeshContracts.requireInteger(value, "value");
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException("value is outside [" + min + ", " + max + "]");
            }
            if ((parsed - min) % step != 0) {
                throw new IllegalArgumentException("value must align to step " + step + " from min " + min);
            }
            return Long.toString(parsed);
        }
    }

    /** Decimal parameter rendered as a numeric field over {@code [min, max]}. */
    record DoubleParameter(
            String id,
            String label,
            String description,
            double min,
            double max,
            double step,
            int decimals,
            double defaultValue)
            implements AlgorithmParameterDescriptor {

        public DoubleParameter {
            id = AutoMeshContracts.requireId(id, "id");
            label = AutoMeshContracts.requireText(label, "label", AutoMeshContracts.MAX_LABEL_LENGTH);
            description = AutoMeshContracts.requireDescription(description);
            if (!Double.isFinite(min)
                    || !Double.isFinite(max)
                    || !Double.isFinite(step)
                    || !Double.isFinite(defaultValue)) {
                throw new IllegalArgumentException("min, max, step and defaultValue must be finite");
            }
            if (min >= max) {
                throw new IllegalArgumentException("min must be less than max");
            }
            if (step <= 0.0d) {
                throw new IllegalArgumentException("step must be positive");
            }
            if (decimals < 0 || decimals > MAX_DECIMALS) {
                throw new IllegalArgumentException("decimals must be between 0 and " + MAX_DECIMALS);
            }
            requireCanonical(min, max, step, decimals, canonicalText(defaultValue));
        }

        @Override
        public String canonicalDefaultValue() {
            return canonicalize(canonicalText(defaultValue));
        }

        @Override
        public String canonicalize(final String value) {
            return requireCanonical(min, max, step, decimals, value);
        }

        private static String requireCanonical(
                final double min, final double max, final double step, final int decimals, final String value) {
            final BigDecimal parsed = AutoMeshContracts.requireDecimal(value, "value");
            if (parsed.compareTo(plainText(min)) < 0 || parsed.compareTo(plainText(max)) > 0) {
                throw new IllegalArgumentException("value is outside [" + plainText(min) + ", " + plainText(max) + "]");
            }
            final BigDecimal canonical = parsed.stripTrailingZeros();
            if (canonical.scale() > decimals) {
                throw new IllegalArgumentException("value must have at most " + decimals + " decimals");
            }
            if (parsed.subtract(plainText(min)).remainder(plainText(step)).compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalArgumentException(
                        "value must align to step " + plainText(step) + " from min " + plainText(min));
            }
            return canonical.toPlainString();
        }

        private static BigDecimal plainText(final double value) {
            return BigDecimal.valueOf(value);
        }

        private static String canonicalText(final double value) {
            return plainText(value).toPlainString();
        }
    }

    /** Boolean parameter rendered as a checkbox. */
    record BoolParameter(String id, String label, String description, boolean defaultValue)
            implements AlgorithmParameterDescriptor {

        private static final String TRUE = "true";
        private static final String FALSE = "false";

        public BoolParameter {
            id = AutoMeshContracts.requireId(id, "id");
            label = AutoMeshContracts.requireText(label, "label", AutoMeshContracts.MAX_LABEL_LENGTH);
            description = AutoMeshContracts.requireDescription(description);
        }

        @Override
        public String canonicalDefaultValue() {
            return Boolean.toString(defaultValue);
        }

        @Override
        public String canonicalize(final String value) {
            Objects.requireNonNull(value, "value");
            if (TRUE.equals(value) || FALSE.equals(value)) {
                return value;
            }
            throw new IllegalArgumentException("value must be " + TRUE + " or " + FALSE);
        }
    }

    /** Single-select parameter rendered as a combo box over its declared options. */
    record EnumParameter(String id, String label, String description, List<Option> options, String defaultOptionValue)
            implements AlgorithmParameterDescriptor {

        public EnumParameter {
            id = AutoMeshContracts.requireId(id, "id");
            label = AutoMeshContracts.requireText(label, "label", AutoMeshContracts.MAX_LABEL_LENGTH);
            description = AutoMeshContracts.requireDescription(description);
            options = List.copyOf(Objects.requireNonNull(options, "options"));
            if (options.isEmpty() || options.size() > MAX_OPTIONS) {
                throw new IllegalArgumentException("options must contain between 1 and " + MAX_OPTIONS + " entries");
            }
            final HashSet<String> values = new HashSet<>();
            for (final Option option : options) {
                Objects.requireNonNull(option, "option");
                if (!values.add(option.value())) {
                    throw new IllegalArgumentException("option values must be unique");
                }
            }
            if (!values.contains(defaultOptionValue)) {
                throw new IllegalArgumentException("defaultOptionValue must name one of the declared options");
            }
        }

        @Override
        public String canonicalDefaultValue() {
            return defaultOptionValue;
        }

        @Override
        public String canonicalize(final String value) {
            Objects.requireNonNull(value, "value");
            for (final Option option : options) {
                if (option.value().equals(value)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("value must name one of the declared options");
        }

        /** One selectable option of an {@link EnumParameter}. */
        public record Option(String value, String label) {
            public Option {
                value = AutoMeshContracts.requireText(value, "value", AutoMeshContracts.MAX_ID_LENGTH);
                label = AutoMeshContracts.requireText(label, "label", AutoMeshContracts.MAX_LABEL_LENGTH);
            }
        }
    }

    /** Free-text parameter rendered as a single-line field bounded by {@code maxLength}. */
    record TextParameter(String id, String label, String description, int maxLength, String defaultValue)
            implements AlgorithmParameterDescriptor {

        public TextParameter {
            id = AutoMeshContracts.requireId(id, "id");
            label = AutoMeshContracts.requireText(label, "label", AutoMeshContracts.MAX_LABEL_LENGTH);
            description = AutoMeshContracts.requireDescription(description);
            if (maxLength < 1 || maxLength > AutoMeshContracts.MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException(
                        "maxLength must be between 1 and " + AutoMeshContracts.MAX_TEXT_LENGTH);
            }
            defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
            if (defaultValue.length() > maxLength) {
                throw new IllegalArgumentException("defaultValue must contain at most maxLength characters");
            }
        }

        @Override
        public String canonicalDefaultValue() {
            return defaultValue;
        }

        @Override
        public String canonicalize(final String value) {
            Objects.requireNonNull(value, "value");
            if (value.length() > maxLength) {
                throw new IllegalArgumentException("value must contain at most " + maxLength + " characters");
            }
            return value;
        }
    }
}
