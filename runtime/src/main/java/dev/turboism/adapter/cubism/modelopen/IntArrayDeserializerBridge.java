package dev.turboism.adapter.cubism.modelopen;

/**
 * Single-pass zero-substring deserializer for serialized Cubism integer array elements.
 *
 * <p>Preserves exact {@link Integer#parseInt(String)} semantics, Kotlin assertion behavior on empty
 * and mismatched token counts, and clean fallback for edge tokens.
 */
public final class IntArrayDeserializerBridge {

    private static final char SPACE_SEPARATOR = ' ';
    private static final int RADIX_TEN = 10;
    private static final int MAX_SAFE_FAST_DIGITS = 9;
    private static final int[] EMPTY_INTS = new int[0];
    private static final String ASSERTION_FAILED_MSG = "Assertion failed";

    private IntArrayDeserializerBridge() {}

    /**
     * Parses {@code count} space-separated integers from {@code text} without allocating token
     * lists or per-element substrings.
     *
     * @param count declared element count attribute value
     * @param text raw XML element text
     * @param assertionsEnabled whether Kotlin runtime assertions are active
     * @return parsed int array of length {@code count}
     */
    public static int[] parseIntArray(final int count, final String text, final boolean assertionsEnabled) {
        if (assertionsEnabled) {
            verifyTokenCount(count, text);
        }
        if (count == 0) {
            return EMPTY_INTS;
        }
        final int[] result = new int[count];
        final int len = text.length();
        int cursor = 0;
        for (int index = 0; index < count; index++) {
            if (cursor > len) {
                throw new IndexOutOfBoundsException(index);
            }
            final int sep = text.indexOf(SPACE_SEPARATOR, cursor);
            final int tokenEnd = sep >= 0 ? sep : len;
            result[index] = parseIntToken(text, cursor, tokenEnd);
            cursor = sep >= 0 ? sep + 1 : len + 1;
        }
        return result;
    }

    /**
     * Parses a single integer slice in {@code text[start..end)} with exact {@link Integer#parseInt}
     * parity, falling back to JDK parsing on malformed, padded, or near-limit inputs.
     *
     * @param text source character string
     * @param start inclusive token start offset
     * @param end exclusive token end offset
     * @return parsed 32-bit signed integer value
     */
    public static int parseIntToken(final String text, final int start, final int end) {
        if (start >= end) {
            return Integer.parseInt("");
        }
        int idx = start;
        boolean negative = false;
        final char firstChar = text.charAt(idx);
        if (firstChar == '-') {
            negative = true;
            idx++;
        } else if (firstChar == '+') {
            idx++;
        }
        final int digitCount = end - idx;
        if (digitCount <= 0 || digitCount > MAX_SAFE_FAST_DIGITS) {
            return Integer.parseInt(text.substring(start, end));
        }
        int accum = 0;
        while (idx < end) {
            final char ch = text.charAt(idx++);
            if (ch < '0' || ch > '9') {
                return Integer.parseInt(text.substring(start, end));
            }
            accum = accum * RADIX_TEN + (ch - '0');
        }
        return negative ? -accum : accum;
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
}
