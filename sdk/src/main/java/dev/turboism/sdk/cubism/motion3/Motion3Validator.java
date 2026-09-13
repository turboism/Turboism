package dev.turboism.sdk.cubism.motion3;

import dev.turboism.protocol.json.StrictJson;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Checks a {@code motion3.json} document against the Cubism 3.x motion format:
 * root {@code Version}, {@code Meta} block, {@code Curves} entries with flat
 * segment lists (linear / bezier / stepped / inverse-stepped), and
 * {@code UserData} events. Pure SDK — needs no host evidence and works for
 * every supported Cubism version.
 */
public final class Motion3Validator {

    private static final Set<String> CURVE_TARGETS = Set.of(
        "Parameter", "PartOpacity", "Model"
    );
    /** Flat segment arity: segment kind → numbers consumed per segment. */
    private static final int[] SEGMENT_ARITY = {2, 6, 2, 2};
    private static final String[] SEGMENT_NAMES = {
        "linear", "bezier", "stepped", "inverse-stepped"
    };
    private static final BigDecimal REQUIRED_VERSION = BigDecimal.valueOf(3L);
    private static final BigDecimal MAX_SEGMENT_KIND = BigDecimal.valueOf(3L);
    private static final double TIME_EPSILON = 1e-4;

    private Motion3Validator() {
    }

    /**
     * Validates {@code bytes} as one motion3 document. Malformed JSON surfaces
     * as a single {@link Motion3Issue.Severity#ERROR} at path {@code $}.
     */
    public static Motion3Report validate(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        final List<Motion3Issue> issues = new ArrayList<>();
        final Object root;
        try {
            root = StrictJson.parse(bytes);
        } catch (IllegalArgumentException failure) {
            issues.add(new Motion3Issue(
                Motion3Issue.Severity.ERROR, "$", "invalid JSON: " + failure.getMessage()
            ));
            return new Motion3Report(issues);
        }
        if (!(root instanceof Map<?, ?> document)) {
            issues.add(new Motion3Issue(
                Motion3Issue.Severity.ERROR, "$", "motion3 root must be a JSON object"
            ));
            return new Motion3Report(issues);
        }
        validateDocument(asStringMap(document, issues, "$"), issues);
        return new Motion3Report(issues);
    }

    /** UTF-8 convenience for {@link #validate(byte[])}. */
    public static Motion3Report validate(final String json) {
        Objects.requireNonNull(json, "json");
        return validate(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void validateDocument(
        final Map<String, Object> document,
        final List<Motion3Issue> issues
    ) {
        if (document == null) {
            return;
        }
        final Object version = document.get("Version");
        if (version == null) {
            error(issues, "$.Version", "missing");
        } else if (!equalsExactly(version, REQUIRED_VERSION)) {
            error(issues, "$.Version", "must be 3, got " + version);
        }
        final Map<String, Object> meta = asStringMap(document.get("Meta"), issues, "$.Meta");
        final double duration = meta == null
            ? Double.NaN
            : validateMeta(meta, issues);
        final List<?> curves = asList(document.get("Curves"), issues, "$.Curves");
        int segmentCount = 0;
        int pointCount = 0;
        if (curves != null) {
            if (curves.isEmpty()) {
                warn(issues, "$.Curves", "motion carries no curves");
            }
            for (int index = 0; index < curves.size(); index++) {
                final int[] counts = validateCurve(
                    curves.get(index), index, duration, issues
                );
                segmentCount += counts[0];
                pointCount += counts[1];
            }
        }
        final List<?> userData = asList(document.get("UserData"), issues, "$.UserData");
        int userDataSize = 0;
        if (userData != null) {
            for (int index = 0; index < userData.size(); index++) {
                userDataSize += validateUserData(userData.get(index), index, duration, issues);
            }
        }
        if (meta != null) {
            checkCount(issues, "$.Meta.CurveCount", meta.get("CurveCount"),
                curves == null ? null : curves.size());
            checkCount(issues, "$.Meta.TotalSegmentCount", meta.get("TotalSegmentCount"),
                curves == null ? null : segmentCount);
            checkCount(issues, "$.Meta.TotalPointCount", meta.get("TotalPointCount"),
                curves == null ? null : pointCount);
            checkCount(issues, "$.Meta.UserDataCount", meta.get("UserDataCount"),
                userData == null ? null : userData.size());
            checkCount(issues, "$.Meta.TotalUserDataSize", meta.get("TotalUserDataSize"),
                userData == null ? null : userDataSize);
        }
    }

    private static double validateMeta(
        final Map<String, Object> meta,
        final List<Motion3Issue> issues
    ) {
        final double duration = positiveNumber(
            meta.get("Duration"), "$.Meta.Duration", issues
        );
        positiveNumber(meta.get("Fps"), "$.Meta.Fps", issues);
        flag(meta.get("Loop"), "$.Meta.Loop", issues);
        flag(meta.get("AreBeziersRestricted"), "$.Meta.AreBeziersRestricted", issues);
        for (String key : List.of(
            "CurveCount", "TotalSegmentCount", "TotalPointCount",
            "UserDataCount", "TotalUserDataSize"
        )) {
            final Object value = meta.get(key);
            if (value != null && !(value instanceof Number number && isIntegral(number))) {
                error(issues, "$.Meta." + key, "must be an integer, got " + value);
            } else if (value instanceof Number number && number.longValue() < 0) {
                error(issues, "$.Meta." + key, "must be non-negative");
            }
        }
        return duration;
    }

    private static int[] validateCurve(
        final Object entry,
        final int index,
        final double duration,
        final List<Motion3Issue> issues
    ) {
        final String path = "$.Curves[" + index + "]";
        final Map<String, Object> curve = asStringMap(entry, issues, path);
        if (curve == null) {
            return new int[] {0, 0};
        }
        final Object target = curve.get("Target");
        if (!(target instanceof String name) || !CURVE_TARGETS.contains(name)) {
            error(issues, path + ".Target",
                "must be one of " + CURVE_TARGETS + ", got " + target);
        }
        final Object id = curve.get("Id");
        if (!(id instanceof String text) || text.isEmpty()) {
            error(issues, path + ".Id", "must be a non-empty string");
        }
        optionalNonNegative(curve.get("FadeInTime"), path + ".FadeInTime", issues);
        optionalNonNegative(curve.get("FadeOutTime"), path + ".FadeOutTime", issues);
        final List<?> segments = asList(curve.get("Segments"), issues, path + ".Segments");
        if (segments == null) {
            return new int[] {0, 0};
        }
        return validateSegments(segments, path + ".Segments", duration, issues);
    }

    /**
     * Parses the flat segment list: {@code [t0, v0, kind, params..., t1, v1, ...]}.
     * Returns {@code [segmentCount, pointCount]}.
     */
    private static int[] validateSegments(
        final List<?> segments,
        final String path,
        final double duration,
        final List<Motion3Issue> issues
    ) {
        if (segments.size() < 2) {
            error(issues, path, "needs at least the first point [time, value]");
            return new int[] {0, 0};
        }
        final Double first = number(segments.get(0));
        if (first == null || segments.get(1) == null || number(segments.get(1)) == null) {
            error(issues, path, "first point must be two numbers");
            return new int[] {0, 0};
        }
        int points = 1;
        int segmentCount = 0;
        double previous = first;
        if (first < -TIME_EPSILON) {
            error(issues, path + "[0]", "time must be >= 0, got " + first);
        }
        int offset = 2;
        while (offset < segments.size()) {
            final Object rawKind = segments.get(offset);
            final BigDecimal kindValue = rawKind instanceof Number kindNumber
                ? asBigDecimal(kindNumber) : null;
            if (kindValue == null || kindValue.stripTrailingZeros().scale() > 0
                || kindValue.compareTo(BigDecimal.ZERO) < 0
                || kindValue.compareTo(MAX_SEGMENT_KIND) > 0) {
                error(issues, path + "[" + offset + "]",
                    "segment kind must be 0..3, got " + rawKind);
                break;
            }
            final int kind = kindValue.intValueExact();
            final int arity = SEGMENT_ARITY[kind];
            if (offset + 1 + arity > segments.size()) {
                error(issues, path + "[" + offset + "]",
                    SEGMENT_NAMES[kind] + " segment needs " + arity
                        + " numbers, only " + (segments.size() - offset - 1) + " remain");
                break;
            }
            for (int index = offset + 1; index <= offset + arity; index++) {
                if (number(segments.get(index)) == null) {
                    error(issues, path + "[" + index + "]",
                        "segment payload must be numeric, got " + segments.get(index));
                    return new int[] {segmentCount, points};
                }
            }
            final double time = number(segments.get(offset + arity - 1));
            if (time <= previous - TIME_EPSILON) {
                error(issues, path + "[" + (offset + arity - 1) + "]",
                    "segment times must increase, got " + time + " after " + previous);
            }
            if (!Double.isNaN(duration) && time > duration + TIME_EPSILON) {
                warn(issues, path + "[" + (offset + arity - 1) + "]",
                    "segment ends past Meta.Duration " + duration + ": " + time);
            }
            previous = time;
            segmentCount++;
            points++;
            offset += 1 + arity;
        }
        return new int[] {segmentCount, points};
    }

    private static int validateUserData(
        final Object entry,
        final int index,
        final double duration,
        final List<Motion3Issue> issues
    ) {
        final String path = "$.UserData[" + index + "]";
        final Map<String, Object> event = asStringMap(entry, issues, path);
        if (event == null) {
            return 0;
        }
        final Object time = event.get("Time");
        final Double seconds = number(time);
        if (seconds == null || seconds < -TIME_EPSILON) {
            error(issues, path + ".Time", "must be a number >= 0, got " + time);
        } else if (!Double.isNaN(duration) && seconds > duration + TIME_EPSILON) {
            warn(issues, path + ".Time",
                "event time past Meta.Duration " + duration + ": " + seconds);
        }
        final Object value = event.get("Value");
        if (!(value instanceof String text)) {
            error(issues, path + ".Value", "must be a string");
            return 0;
        }
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void checkCount(
        final List<Motion3Issue> issues,
        final String path,
        final Object declared,
        final Integer actual
    ) {
        if (actual == null || declared == null) {
            return;
        }
        if (!(declared instanceof Number number) || !isIntegral(number)) {
            error(issues, path, "must be an integer, got " + declared);
            return;
        }
        if (number.longValue() != actual.longValue()) {
            warn(issues, path, "declares " + declared + " but document holds " + actual);
        }
    }

    private static double positiveNumber(
        final Object value,
        final String path,
        final List<Motion3Issue> issues
    ) {
        final Double number = number(value);
        if (number == null || number <= 0.0 || !Double.isFinite(number)) {
            error(issues, path, "must be a positive number, got " + value);
            return Double.NaN;
        }
        return number;
    }

    private static void optionalNonNegative(
        final Object value,
        final String path,
        final List<Motion3Issue> issues
    ) {
        if (value == null) {
            return;
        }
        final Double number = number(value);
        if (number == null || number < 0.0) {
            error(issues, path, "must be a non-negative number, got " + value);
        }
    }

    private static void flag(
        final Object value,
        final String path,
        final List<Motion3Issue> issues
    ) {
        if (!(value instanceof Boolean)) {
            error(issues, path, "must be a boolean, got " + value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(
        final Object value,
        final List<Motion3Issue> issues,
        final String path
    ) {
        if (value == null) {
            error(issues, path, "missing");
            return null;
        }
        if (!(value instanceof Map<?, ?>)) {
            error(issues, path, "must be an object, got " + describe(value));
            return null;
        }
        return (Map<String, Object>) value;
    }

    private static List<?> asList(
        final Object value,
        final List<Motion3Issue> issues,
        final String path
    ) {
        if (value == null) {
            error(issues, path, "missing");
            return null;
        }
        if (!(value instanceof List<?> list)) {
            error(issues, path, "must be an array, got " + describe(value));
            return null;
        }
        return list;
    }

    private static Double number(final Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static boolean isIntegral(final Number number) {
        return !(number instanceof BigDecimal decimal)
            || decimal.stripTrailingZeros().scale() <= 0;
    }

    /**
     * Exact mathematical equality, independent of the written scale:
     * {@code 3}, {@code 3.0} and {@code 3e0} all match while {@code 3.5}
     * and integers that only wrap to the expected value do not.
     */
    private static boolean equalsExactly(
        final Object value,
        final BigDecimal expected
    ) {
        if (!(value instanceof Number number)) {
            return false;
        }
        final BigDecimal actual = asBigDecimal(number);
        return actual != null && actual.compareTo(expected) == 0;
    }

    /**
     * Exact mathematical value of {@code number}, or {@code null} when the
     * value cannot be represented exactly. Unlike {@link Number#longValue()}
     * or {@link Number#intValue()}, this preserves fractional parts and
     * magnitudes beyond the narrowing type, so callers can validate before
     * narrowing.
     */
    private static BigDecimal asBigDecimal(final Number number) {
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        if (number instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (number instanceof Byte || number instanceof Short
            || number instanceof Integer || number instanceof Long) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (number instanceof Double || number instanceof Float) {
            final double value = number.doubleValue();
            return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
        }
        return null;
    }

    private static String describe(final Object value) {
        if (value instanceof List<?>) return "array";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        return value == null ? "null" : "object";
    }

    private static void error(
        final List<Motion3Issue> issues, final String path, final String message
    ) {
        issues.add(new Motion3Issue(Motion3Issue.Severity.ERROR, path, message));
    }

    private static void warn(
        final List<Motion3Issue> issues, final String path, final String message
    ) {
        issues.add(new Motion3Issue(Motion3Issue.Severity.WARNING, path, message));
    }
}
