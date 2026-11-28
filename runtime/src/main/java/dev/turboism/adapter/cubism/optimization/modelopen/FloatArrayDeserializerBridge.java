package dev.turboism.adapter.cubism.optimization.modelopen;

import dev.turboism.adapter.cubism.optimization.export.RuntimeExportScope;
import java.nio.charset.StandardCharsets;

/**
 * Single-pass zero-substring deserializer for serialized Cubism float array elements.
 *
 * <p>Preserves {@code Float.floatToRawIntBits} parity across all normal values, signed zero,
 * subnormals, rounding ties, infinities, NaNs, and assertion behavior.
 */
public final class FloatArrayDeserializerBridge {

    private static final char SPACE_SEPARATOR = ' ';
    private static final byte SPACE_SEPARATOR_BYTE = (byte) ' ';
    private static final char MAX_TRIM_CHAR = ' ';
    private static final char MINUS_SIGN = '-';
    private static final char PLUS_SIGN = '+';
    private static final char DECIMAL_POINT = '.';
    private static final char DIGIT_ZERO = '0';
    private static final char DIGIT_NINE = '9';
    private static final char EXP_LOWER = 'e';
    private static final char EXP_UPPER = 'E';
    private static final float POSITIVE_ZERO = 0.0f;
    private static final float NEGATIVE_ZERO = -0.0f;
    private static final int RADIX_TEN = 10;
    private static final int MAX_EXACT_DOUBLE_DIGITS = 15;
    private static final int MAX_EXACT_POW10 = 22;
    private static final int MAX_EXPLICIT_EXP_GUARD = MAX_EXACT_POW10 * RADIX_TEN;
    private static final long FLOAT_TIE_LOW29_MASK = 0x1FFF_FFFFL;
    private static final long FLOAT_TIE_GUARD_LOW = 0x0FFF_FFFEL;
    private static final long FLOAT_TIE_GUARD_HIGH = 0x1000_0002L;
    private static final double[] POW10_TABLE = buildPow10Table();
    private static final float[] EMPTY_FLOATS = new float[0];
    private static final String ASSERTION_FAILED_MSG = "Assertion failed";

    private FloatArrayDeserializerBridge() {}

    /**
     * Parses {@code count} space-separated float tokens from {@code text} in a single forward pass
     * without allocating token lists or per-element substrings.
     *
     * @param count declared element count attribute value
     * @param text raw XML element text
     * @param assertionsEnabled whether Kotlin runtime assertions are active
     * @return parsed float array of length {@code count}
     */
    public static float[] parseFloatArray(final int count, final String text, final boolean assertionsEnabled) {
        final float[] scopedClone = RuntimeExportScope.tryTakeFloatArray(text);
        if (scopedClone != null) {
            return scopedClone;
        }
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
            final int tokenStart = cursor;
            boolean negative = false;
            if (cursor < len) {
                final char signChar = text.charAt(cursor);
                if (signChar == MINUS_SIGN) {
                    negative = true;
                    cursor++;
                } else if (signChar == PLUS_SIGN) {
                    cursor++;
                }
            }

            long mantissa = 0L;
            int sigDigits = 0;
            int fracDigits = 0;
            boolean seenDigit = false;
            boolean fallback = false;

            while (cursor < len) {
                final char ch = text.charAt(cursor);
                if (ch >= DIGIT_ZERO && ch <= DIGIT_NINE) {
                    seenDigit = true;
                    final int digit = ch - DIGIT_ZERO;
                    if (mantissa != 0L || digit != 0) {
                        sigDigits++;
                        if (sigDigits > MAX_EXACT_DOUBLE_DIGITS) {
                            fallback = true;
                            break;
                        }
                        mantissa = mantissa * RADIX_TEN + digit;
                    }
                    cursor++;
                } else {
                    break;
                }
            }

            if (!fallback && cursor < len && text.charAt(cursor) == DECIMAL_POINT) {
                cursor++;
                final int fracStart = cursor;
                while (cursor < len) {
                    final char ch = text.charAt(cursor);
                    if (ch >= DIGIT_ZERO && ch <= DIGIT_NINE) {
                        seenDigit = true;
                        final int digit = ch - DIGIT_ZERO;
                        if (mantissa != 0L || digit != 0) {
                            sigDigits++;
                            if (sigDigits > MAX_EXACT_DOUBLE_DIGITS) {
                                fallback = true;
                                break;
                            }
                            mantissa = mantissa * RADIX_TEN + digit;
                        }
                        cursor++;
                    } else {
                        break;
                    }
                }
                fracDigits = cursor - fracStart;
            }

            int explicitExp = 0;
            if (!fallback && seenDigit && cursor < len) {
                final char expMarker = text.charAt(cursor);
                if (expMarker == EXP_LOWER || expMarker == EXP_UPPER) {
                    cursor++;
                    if (cursor >= len) {
                        fallback = true;
                    } else {
                        boolean expNeg = false;
                        final char expSign = text.charAt(cursor);
                        if (expSign == MINUS_SIGN) {
                            expNeg = true;
                            cursor++;
                        } else if (expSign == PLUS_SIGN) {
                            cursor++;
                        }
                        final int expDigitStart = cursor;
                        while (cursor < len) {
                            final char ec = text.charAt(cursor);
                            if (ec >= DIGIT_ZERO && ec <= DIGIT_NINE) {
                                if (explicitExp > MAX_EXPLICIT_EXP_GUARD) {
                                    fallback = true;
                                    break;
                                }
                                explicitExp = explicitExp * RADIX_TEN + (ec - DIGIT_ZERO);
                                cursor++;
                            } else {
                                break;
                            }
                        }
                        if (cursor == expDigitStart) {
                            fallback = true;
                        } else if (expNeg) {
                            explicitExp = -explicitExp;
                        }
                    }
                }
            }

            if (!fallback && seenDigit && (cursor == len || text.charAt(cursor) == SPACE_SEPARATOR)) {
                final int tokenEnd = cursor;
                final int nextCursor = cursor < len ? cursor + 1 : len + 1;
                final float fastValue = tryConvertExactFloat(negative, mantissa, explicitExp - fracDigits);
                result[index] =
                        Float.isNaN(fastValue) ? Float.parseFloat(text.substring(tokenStart, tokenEnd)) : fastValue;
                cursor = nextCursor;
            } else {
                final int sep = text.indexOf(SPACE_SEPARATOR, cursor);
                final int tokenEnd = sep >= 0 ? sep : len;
                result[index] = parseFloatToken(text, tokenStart, tokenEnd);
                cursor = sep >= 0 ? sep + 1 : len + 1;
            }
        }
        return result;
    }

    /**
     * Parses {@code count} space-separated float tokens directly from a UTF-8 byte slice without
     * allocating an intermediate {@link String} on the fast path.
     *
     * @param count declared element count attribute value
     * @param utf8 source UTF-8 byte buffer
     * @param start inclusive byte offset
     * @param end exclusive byte offset
     * @param assertionsEnabled whether Kotlin runtime assertions are active
     * @return parsed float array of length {@code count}
     */
    static float[] parseFloatArrayFromUtf8(
            final int count, final byte[] utf8, final int start, final int end, final boolean assertionsEnabled) {
        if (assertionsEnabled) {
            verifyTokenCountUtf8(count, utf8, start, end);
        }
        if (count == 0) {
            return EMPTY_FLOATS;
        }
        final float[] result = new float[count];
        int cursor = start;
        for (int index = 0; index < count; index++) {
            if (cursor > end) {
                throw new IndexOutOfBoundsException(index);
            }
            final int tokenStart = cursor;
            boolean negative = false;
            if (cursor < end) {
                final byte signByte = utf8[cursor];
                if (signByte == MINUS_SIGN) {
                    negative = true;
                    cursor++;
                } else if (signByte == PLUS_SIGN) {
                    cursor++;
                }
            }

            long mantissa = 0L;
            int sigDigits = 0;
            int fracDigits = 0;
            boolean seenDigit = false;
            boolean fallback = false;

            while (cursor < end) {
                final byte ch = utf8[cursor];
                if (ch >= DIGIT_ZERO && ch <= DIGIT_NINE) {
                    seenDigit = true;
                    final int digit = ch - DIGIT_ZERO;
                    if (mantissa != 0L || digit != 0) {
                        sigDigits++;
                        if (sigDigits > MAX_EXACT_DOUBLE_DIGITS) {
                            fallback = true;
                            break;
                        }
                        mantissa = mantissa * RADIX_TEN + digit;
                    }
                    cursor++;
                } else {
                    break;
                }
            }

            if (!fallback && cursor < end && utf8[cursor] == DECIMAL_POINT) {
                cursor++;
                final int fracStart = cursor;
                while (cursor < end) {
                    final byte ch = utf8[cursor];
                    if (ch >= DIGIT_ZERO && ch <= DIGIT_NINE) {
                        seenDigit = true;
                        final int digit = ch - DIGIT_ZERO;
                        if (mantissa != 0L || digit != 0) {
                            sigDigits++;
                            if (sigDigits > MAX_EXACT_DOUBLE_DIGITS) {
                                fallback = true;
                                break;
                            }
                            mantissa = mantissa * RADIX_TEN + digit;
                        }
                        cursor++;
                    } else {
                        break;
                    }
                }
                fracDigits = cursor - fracStart;
            }

            int explicitExp = 0;
            if (!fallback && seenDigit && cursor < end) {
                final byte expMarker = utf8[cursor];
                if (expMarker == EXP_LOWER || expMarker == EXP_UPPER) {
                    cursor++;
                    if (cursor >= end) {
                        fallback = true;
                    } else {
                        boolean expNeg = false;
                        final byte expSign = utf8[cursor];
                        if (expSign == MINUS_SIGN) {
                            expNeg = true;
                            cursor++;
                        } else if (expSign == PLUS_SIGN) {
                            cursor++;
                        }
                        final int expDigitStart = cursor;
                        while (cursor < end) {
                            final byte ec = utf8[cursor];
                            if (ec >= DIGIT_ZERO && ec <= DIGIT_NINE) {
                                if (explicitExp > MAX_EXPLICIT_EXP_GUARD) {
                                    fallback = true;
                                    break;
                                }
                                explicitExp = explicitExp * RADIX_TEN + (ec - DIGIT_ZERO);
                                cursor++;
                            } else {
                                break;
                            }
                        }
                        if (cursor == expDigitStart) {
                            fallback = true;
                        } else if (expNeg) {
                            explicitExp = -explicitExp;
                        }
                    }
                }
            }

            if (!fallback && seenDigit && (cursor == end || utf8[cursor] == SPACE_SEPARATOR_BYTE)) {
                final int tokenEnd = cursor;
                final int nextCursor = cursor < end ? cursor + 1 : end + 1;
                final float fastValue = tryConvertExactFloat(negative, mantissa, explicitExp - fracDigits);
                result[index] = Float.isNaN(fastValue)
                        ? Float.parseFloat(new String(utf8, tokenStart, tokenEnd - tokenStart, StandardCharsets.UTF_8))
                        : fastValue;
                cursor = nextCursor;
            } else {
                int tokenEnd = cursor;
                while (tokenEnd < end && utf8[tokenEnd] != SPACE_SEPARATOR_BYTE) {
                    tokenEnd++;
                }
                result[index] =
                        Float.parseFloat(new String(utf8, tokenStart, tokenEnd - tokenStart, StandardCharsets.UTF_8));
                cursor = tokenEnd < end ? tokenEnd + 1 : end + 1;
            }
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
        if (signChar == MINUS_SIGN) {
            negative = true;
            s++;
        } else if (signChar == PLUS_SIGN) {
            s++;
        }
        if (s >= e) {
            return Float.parseFloat(text.substring(trimmedStart, e));
        }

        final char first = text.charAt(s);
        if ((first < DIGIT_ZERO || first > DIGIT_NINE) && first != DECIMAL_POINT) {
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
            if (ch >= DIGIT_ZERO && ch <= DIGIT_NINE) {
                seenDigit = true;
                final int digit = ch - DIGIT_ZERO;
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
            } else if (ch == DECIMAL_POINT) {
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
            if (expMarker != EXP_LOWER && expMarker != EXP_UPPER) {
                return Float.parseFloat(text.substring(trimmedStart, e));
            }
            idx++;
            if (idx >= e) {
                return Float.parseFloat(text.substring(trimmedStart, e));
            }
            boolean expNeg = false;
            final char expSign = text.charAt(idx);
            if (expSign == MINUS_SIGN) {
                expNeg = true;
                idx++;
            } else if (expSign == PLUS_SIGN) {
                idx++;
            }
            if (idx >= e) {
                return Float.parseFloat(text.substring(trimmedStart, e));
            }
            while (idx < e) {
                final char ec = text.charAt(idx);
                if (ec < DIGIT_ZERO || ec > DIGIT_NINE) {
                    return Float.parseFloat(text.substring(trimmedStart, e));
                }
                if (explicitExp > MAX_EXPLICIT_EXP_GUARD) {
                    return Float.parseFloat(text.substring(trimmedStart, e));
                }
                explicitExp = explicitExp * RADIX_TEN + (ec - DIGIT_ZERO);
                idx++;
            }
            if (expNeg) {
                explicitExp = -explicitExp;
            }
        }

        final float fastValue = tryConvertExactFloat(negative, mantissa, explicitExp - fracDigits);
        return Float.isNaN(fastValue) ? Float.parseFloat(text.substring(trimmedStart, e)) : fastValue;
    }

    /**
     * Converts an exact decimal mantissa and base-10 exponent to a float, or returns {@code NaN}
     * when double-rounding, subnormal scaling, or overflow requires JDK fallback.
     */
    private static float tryConvertExactFloat(final boolean negative, final long mantissa, final int exp10) {
        if (mantissa == 0L) {
            return negative ? NEGATIVE_ZERO : POSITIVE_ZERO;
        }
        if (exp10 < -MAX_EXACT_POW10 || exp10 > MAX_EXACT_POW10) {
            return Float.NaN;
        }
        final double exactDouble =
                exp10 >= 0 ? ((double) mantissa * POW10_TABLE[exp10]) : ((double) mantissa / POW10_TABLE[-exp10]);
        if (exactDouble < Float.MIN_NORMAL || exactDouble > Float.MAX_VALUE) {
            return Float.NaN;
        }
        // Reject double results near a float midpoint where double-rounding could shift the final ULP.
        final long low29 = Double.doubleToRawLongBits(exactDouble) & FLOAT_TIE_LOW29_MASK;
        if (low29 > FLOAT_TIE_GUARD_LOW && low29 < FLOAT_TIE_GUARD_HIGH) {
            return Float.NaN;
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

    private static void verifyTokenCountUtf8(final int count, final byte[] utf8, final int start, final int end) {
        int tokenCount = 1;
        for (int i = start; i < end; i++) {
            if (utf8[i] == SPACE_SEPARATOR_BYTE) {
                tokenCount++;
            }
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
