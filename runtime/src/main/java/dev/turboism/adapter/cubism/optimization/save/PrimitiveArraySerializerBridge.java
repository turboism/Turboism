package dev.turboism.adapter.cubism.optimization.save;

/**
 * Formats primitive float and int arrays into space-delimited strings without element boxing.
 */
public final class PrimitiveArraySerializerBridge {

    private static final String EMPTY_TEXT = "";
    private static final String INT_MIN_TEXT = "-2147483648";
    private static final char[] INT_MIN_CHARS = INT_MIN_TEXT.toCharArray();
    private static final int INT_MIN_LENGTH = INT_MIN_CHARS.length;
    private static final int DIGIT_RADIX = 10;
    private static final int TWO_DIGIT_RADIX = 100;
    private static final char ZERO_CHAR = '0';
    private static final char MINUS_CHAR = '-';
    private static final int ESTIMATED_CHARS_PER_FLOAT = 10;
    private static final int MIN_BUILDER_CAPACITY = 16;
    private static final int MAX_INITIAL_BUILDER_CAPACITY = 32 * 1024 * 1024;
    private static final int[] INT_SIZE_THRESHOLDS = {
        9, 99, 999, 9_999, 99_999, 999_999, 9_999_999, 99_999_999, 999_999_999, Integer.MAX_VALUE
    };
    private static final char[] DIGIT_TENS = buildDigitTens();
    private static final char[] DIGIT_ONES = buildDigitOnes();

    private PrimitiveArraySerializerBridge() {}

    /**
     * Formats {@code values} using {@link Float#toString(float)} parity separated by {@code separator}.
     *
     * @param values float array to format
     * @param separator element separator
     * @return formatted text matching {@code ArraysKt.joinToString$default}
     */
    public static String formatFloatArray(final float[] values, final CharSequence separator) {
        if (values == null || values.length == 0) {
            return EMPTY_TEXT;
        }
        if (values.length == 1) {
            return Float.toString(values[0]);
        }
        final String sep = separator == null ? EMPTY_TEXT : separator.toString();
        final int initialCapacity = computeInitialFloatCapacity(values.length, sep.length());
        final StringBuilder sb = new StringBuilder(initialCapacity);
        sb.append(values[0]);
        for (int i = 1; i < values.length; i++) {
            sb.append(sep).append(values[i]);
        }
        return sb.toString();
    }

    /**
     * Formats {@code values} using {@link Integer#toString(int)} parity separated by {@code separator}.
     *
     * @param values int array to format
     * @param separator element separator
     * @return formatted text matching {@code ArraysKt.joinToString$default}
     */
    public static String formatIntArray(final int[] values, final CharSequence separator) {
        if (values == null || values.length == 0) {
            return EMPTY_TEXT;
        }
        if (values.length == 1) {
            return Integer.toString(values[0]);
        }
        final String sep = separator == null ? EMPTY_TEXT : separator.toString();
        final int sepLen = sep.length();
        long totalChars = (long) (values.length - 1) * sepLen;
        for (final int value : values) {
            totalChars += exactIntStringLength(value);
        }
        final char[] buffer = new char[Math.toIntExact(totalChars)];
        final boolean singleCharSep = sepLen == 1;
        final char sepChar = singleCharSep ? sep.charAt(0) : ZERO_CHAR;
        int pos = writeIntChars(values[0], buffer, 0);
        for (int i = 1; i < values.length; i++) {
            if (singleCharSep) {
                buffer[pos++] = sepChar;
            } else if (sepLen > 0) {
                sep.getChars(0, sepLen, buffer, pos);
                pos += sepLen;
            }
            pos = writeIntChars(values[i], buffer, pos);
        }
        return new String(buffer);
    }

    private static int computeInitialFloatCapacity(final int count, final int sepLen) {
        final long perElement = (long) ESTIMATED_CHARS_PER_FLOAT + sepLen;
        final long estimated = (long) count * perElement;
        if (estimated <= MIN_BUILDER_CAPACITY) {
            return MIN_BUILDER_CAPACITY;
        }
        return (int) Math.min(estimated, MAX_INITIAL_BUILDER_CAPACITY);
    }

    private static int exactIntStringLength(final int value) {
        if (value == Integer.MIN_VALUE) {
            return INT_MIN_LENGTH;
        }
        final int abs;
        final int signLen;
        if (value < 0) {
            abs = -value;
            signLen = 1;
        } else {
            abs = value;
            signLen = 0;
        }
        for (int i = 0; ; i++) {
            if (abs <= INT_SIZE_THRESHOLDS[i]) {
                return signLen + i + 1;
            }
        }
    }

    private static int writeIntChars(final int value, final char[] buffer, final int startPos) {
        if (value == 0) {
            buffer[startPos] = ZERO_CHAR;
            return startPos + 1;
        }
        if (value == Integer.MIN_VALUE) {
            System.arraycopy(INT_MIN_CHARS, 0, buffer, startPos, INT_MIN_LENGTH);
            return startPos + INT_MIN_LENGTH;
        }
        final int totalLen = exactIntStringLength(value);
        final int endPos = startPos + totalLen;
        int unsignedValue = value;
        if (value < 0) {
            buffer[startPos] = MINUS_CHAR;
            unsignedValue = -value;
        }
        int cursor = endPos;
        while (unsignedValue >= TWO_DIGIT_RADIX) {
            final int q = unsignedValue / TWO_DIGIT_RADIX;
            final int r = unsignedValue - q * TWO_DIGIT_RADIX;
            buffer[--cursor] = DIGIT_ONES[r];
            buffer[--cursor] = DIGIT_TENS[r];
            unsignedValue = q;
        }
        if (unsignedValue >= DIGIT_RADIX) {
            buffer[--cursor] = DIGIT_ONES[unsignedValue];
            buffer[--cursor] = DIGIT_TENS[unsignedValue];
        } else {
            buffer[--cursor] = (char) (ZERO_CHAR + unsignedValue);
        }
        return endPos;
    }

    private static char[] buildDigitTens() {
        final char[] table = new char[TWO_DIGIT_RADIX];
        for (int i = 0; i < TWO_DIGIT_RADIX; i++) {
            table[i] = (char) (ZERO_CHAR + (i / DIGIT_RADIX));
        }
        return table;
    }

    private static char[] buildDigitOnes() {
        final char[] table = new char[TWO_DIGIT_RADIX];
        for (int i = 0; i < TWO_DIGIT_RADIX; i++) {
            table[i] = (char) (ZERO_CHAR + (i % DIGIT_RADIX));
        }
        return table;
    }
}
