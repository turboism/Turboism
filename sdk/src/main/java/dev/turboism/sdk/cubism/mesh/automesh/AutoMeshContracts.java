package dev.turboism.sdk.cubism.mesh.automesh;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shared bounds and validators for the auto-mesh vocabulary.
 *
 * <p>Every record in this package validates itself in its canonical constructor, so a malformed
 * declaration fails where the plugin builds it instead of inside the runtime that renders or
 * dispatches it. The limits are the ones the design review fixed; a new kind of parameter must
 * reuse them rather than invent local bounds.</p>
 */
final class AutoMeshContracts {

    /** Longest accepted identifier such as an algorithm id or a parameter id. */
    static final int MAX_ID_LENGTH = 128;

    /** Longest accepted user-visible label. */
    static final int MAX_LABEL_LENGTH = 128;

    /** Longest accepted free-form description. */
    static final int MAX_DESCRIPTION_LENGTH = 512;

    /** Longest accepted text parameter value or preset value. */
    static final int MAX_TEXT_LENGTH = 4096;

    /** Longest accepted host object identifier; host ids are user-authored and may use any script. */
    static final int MAX_TARGET_ID_LENGTH = 256;

    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]*");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private AutoMeshContracts() {}

    /** Validates a portable identifier that no host naming rule constrains. */
    static String requireId(final String value, final String name) {
        final String id = requireText(value, name, MAX_ID_LENGTH);
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return id;
    }

    /** Validates a host-authored identifier such as an ArtMesh id, which may use any script. */
    static String requireHostId(final String value, final String name) {
        return requireText(value, name, MAX_TARGET_ID_LENGTH);
    }

    /** Validates a non-blank single-line text field. */
    static String requireText(final String value, final String name, final int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must contain at most " + maxLength + " characters");
        }
        return value;
    }

    /** Validates a free-form description, where an absent value means "no description". */
    static String requireDescription(final String value) {
        if (value == null) {
            return "";
        }
        if (value.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "description must contain at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return value;
    }

    /** Validates a user-authored text value such as a preset entry, where an empty value is legal. */
    static String requireValue(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(name + " must contain at most " + MAX_TEXT_LENGTH + " characters");
        }
        return value;
    }

    /** Parses the canonical decimal-integer grammar; a leading {@code +} sign or padding is refused. */
    static long requireInteger(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (!INTEGER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a decimal integer");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException(name + " is outside the supported integer range", overflow);
        }
    }

    /** Parses the canonical decimal grammar; exponents, a leading {@code +} and padding are refused. */
    static BigDecimal requireDecimal(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (!DECIMAL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a decimal number");
        }
        return new BigDecimal(value);
    }
}
