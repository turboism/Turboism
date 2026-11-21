package dev.turboism.adapter.cubism.modelopen;

/**
 * Single-pass zero-substring deserializer for serialized Cubism float array elements.
 *
 * <p>Preserves {@code Float.floatToRawIntBits} parity across all normal values, signed zero,
 * subnormals, rounding ties, infinities, NaNs, and assertion behavior.
 */
public final class FloatArrayDeserializerBridge {

    private static final char SPACE_SEPARATOR = ' ';
    private static final char MAX_TRIM_CHAR = ' ';
    private static final int RADIX_TEN = 10;
    private static final int MAX_EXACT_DOUBLE_DIGITS = 15;
    private static final int MAX_EXACT_POW10 = 22;
    private static final long FLOAT_TIE_LOW29_MASK = 0x1FFF_FFFFL;
    private static final long FLOAT_TIE_GUARD_LOW = 0x0FFF_FFFEL;
    private static final long FLOAT_TIE_GUARD_HIGH = 0x1000_0002L;
    private static final double[] POW10_TABLE = buildPow10Table();
    private static final float[] EMPTY_FLOATS = new float[0];
    private static final String ASSERTION_FAILED_MSG = "Assertion failed";

    private FloatArrayDeserializerBridge() {}

    /**
     * Parses {@code count} space-separated float tokens from {@code text} without allocating token
     * lists.
     *
     * @param count declared element count attribute value
     * @param text raw XML element text
     * @param assertionsEnabled whether Kotlin runtime assertions are active
     * @return parsed float array of length {@code count}
     */
    public static float[] parseFloatArray(final int count, final String text, final boolean assertionsEnabled) {
        if (assertionsEnabled) {
            verifyTokenCount(count, text);
        }
        if (count == 0) {
            return EMPTY_FLOATS;
        }
        final float[] result = new float[count];
        final int len = text.length();
        int cursor = 0;
        for (int index = 0; index < count; index++) {
            if (cursor > len) {
                throw new IndexOutOfBoundsException(index);
            }
            final int sep = text.indexOf(SPACE_SEPARATOR, cursor);
            final int tokenEnd = sep >= 0 ? sep : len;
            result[index] = parseFloatToken(text, cursor, tokenEnd);
            cursor = sep >= 0 ? sep + 1 : len + 1;
        }
        return result;
    }

    /**
     * Parses a single float slice in {@code text[start..end)} with exact {@link Float#parseFloat}
     * bit-level parity.
     *
     * @param text source character string
     * @param start inclusive token start offset
     * @param end exclusive token end offset
     * @return parsed IEEE-754 single-precision value
     */
    public static float parseFloatToken(final String text, final int start, final int end) {
        int s = start;
        int e = end;
        while (s < e && text.charAt(s) <= MAX_TRIM_CHAR) {
            s++;
        }
        while (e > s && text.charAt(e - 1) <= MAX_TRIM_CHAR) {
            e--;
        }
        if (s >= e) {
            return Float.parseFloat("");
        }
        final int trimmedStart = s;
        boolean negative = false;
        final char signChar = text.charAt(s);
        if (signChar == '-') {
            negative = true;
            s++;
        } else if (signChar == '+') {
            s++;
        }
        if (s >= e) {
            return Float.parseFloat(text.substring(trimmedStart, e));
        }

        final char first = text.charAt(s);
        if ((first < '0' || first > '9') && first != '.') {
            return Float.parseFloat(text.substring(trimmedStart, e));
        }

        long mantissa = 0L;
        int sigDigits = 0;
        int fracDigits = 0;
        boolean seenDigit = false;
        boolean seenDot = false;
        int idx = s;
        while (idx < e) {
            final char ch = text.charAt(idx);
            if (ch >= '0' && ch <= '9') {
                seenDigit = true;
                final int digit = ch - '0';
                if (mantissa != 0L || digit != 0) {
                    sigDigits++;
                    if (sigDigits > MAX_EXACT_DOUBLE_DIGITS) {
                        return Float.parseFloat(text.substring(trimmedStart, e));
                    }
                    mantissa = mantissa * RADIX_TEN + digit;
                }
                if (seenDot) {
                    fracDigits++;
                }
                idx++;
            } else if (ch == '.') {
                if (seenDot) {
                    return Float.parseFloat(text.substring(trimmedStart, e));
                }
                seenDot = true;
                idx++;
            } else {
                break;
            }
        }
        if (!seenDigit) {
            return Float.parseFloat(text.substring(trimmedStart, e));
        }

        int explicitExp = 0;
        if (idx < e) {
            final char expMarker = text.charAt(idx);
            if (expMarker != 'e' && expMarker != 'E') {
                return Float.parseFloat(text.substring(trimmedStart, e));
            }
            idx++;
            if (idx >= e) {
                return Float.parseFloat(text.substring(trimmedStart, e));
            }
            boolean expNeg = false;
            final char expSign = text.charAt(idx);
            if (expSign == '-') {
                expNeg = true;
                idx++;
            } else if (expSign == '+') {
                idx++;
            }
            if (idx >= e) {
                return Float.parseFloat(text.substring(trimmedStart, e));
            }
            while (idx < e) {
                final char ec = text.charAt(idx);
                if (ec < '0' || ec > '9') {
                    return Float.parseFloat(text.substring(trimmedStart, e));
                }
                if (explicitExp > MAX_EXACT_POW10 * RADIX_TEN) {
                    return Float.parseFloat(text.substring(trimmedStart, e));
                }
                explicitExp = explicitExp * RADIX_TEN + (ec - '0');
                idx++;
            }
            if (expNeg) {
                explicitExp = -explicitExp;
            }
        }

        if (mantissa == 0L) {
            return negative ? -0.0f : 0.0f;
        }
        final int exp10 = explicitExp - fracDigits;
        if (exp10 < -MAX_EXACT_POW10 || exp10 > MAX_EXACT_POW10) {
            return Float.parseFloat(text.substring(trimmedStart, e));
        }

        final double exactDouble =
                exp10 >= 0 ? ((double) mantissa * POW10_TABLE[exp10]) : ((double) mantissa / POW10_TABLE[-exp10]);
        if (exactDouble < Float.MIN_NORMAL || exactDouble > Float.MAX_VALUE) {
            return Float.parseFloat(text.substring(trimmedStart, e));
        }
        // Reject double results near a float midpoint where double-rounding could shift the final ULP.
        final long low29 = Double.doubleToRawLongBits(exactDouble) & FLOAT_TIE_LOW29_MASK;
        if (low29 > FLOAT_TIE_GUARD_LOW && low29 < FLOAT_TIE_GUARD_HIGH) {
            return Float.parseFloat(text.substring(trimmedStart, e));
        }
        final float value = (float) exactDouble;
        return negative ? -value : value;
    }

    private static void verifyTokenCount(final int count, final String text) {
        int tokenCount = 1;
        int pos = 0;
        while (true) {
            final int sep = text.indexOf(SPACE_SEPARATOR, pos);
            if (sep < 0) {
                break;
            }
            tokenCount++;
            pos = sep + 1;
        }
        final boolean matches = count == tokenCount || (count == 0 && tokenCount == 1);
        if (!matches) {
            throw new AssertionError(ASSERTION_FAILED_MSG);
        }
    }

    private static double[] buildPow10Table() {
        final double[] table = new double[MAX_EXACT_POW10 + 1];
        double current = 1.0d;
        for (int i = 0; i < table.length; i++) {
            table[i] = current;
            current *= RADIX_TEN;
        }
        return table;
    }
}
