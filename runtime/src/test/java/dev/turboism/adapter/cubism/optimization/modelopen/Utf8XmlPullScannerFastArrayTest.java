package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Verifies single-pass {@code float[]} and {@code int[]} parsing in {@link Utf8XmlPullScanner},
 * including IEEE-754 bit-exactness, XML normalization fallbacks, and error boundaries.
 */
final class Utf8XmlPullScannerFastArrayTest {

    private static final String SENTINEL_TAG = "next";
    private static final String[] FLOAT_EDGE_TOKENS = {
        "0",
        "-0",
        "+0.0",
        "-0.0",
        "1",
        "-1",
        "0.5",
        ".5",
        "5.",
        "-123.456",
        "1.2345678e5",
        "-9.876543E-12",
        "1e+22",
        "1e-22",
        "1e23",
        "1e-23",
        "1234567890123456.5",
        "16777217.5",
        "1.401298464324817E-45",
        "1.17549435E-38",
        "1.0e-40",
        "3.4028235E38",
        "1.0e39",
        "NaN",
        "Infinity",
        "-Infinity",
        "0x1.0p0",
        "2.5f",
        "\t3.25\n"
    };
    private static final String[] INT_EDGE_TOKENS = {
        "0", "-0", "+42", "-999999999", "999999999", "2147483647", "-2147483648", "000000000007"
    };

    @Test
    void floatArrayBitExactParityAcrossFastAndFallbackTokens() {
        final String joined = String.join(" ", FLOAT_EDGE_TOKENS);
        final Utf8XmlPullScanner scanner = openFirstChild("<root><f>" + joined + "</f><" + SENTINEL_TAG + "/></root>");

        final float[] actual = scanner.readFloatArray(FLOAT_EDGE_TOKENS.length);
        assertEquals(FLOAT_EDGE_TOKENS.length, actual.length);
        for (int i = 0; i < FLOAT_EDGE_TOKENS.length; i++) {
            final float expected = Float.parseFloat(FLOAT_EDGE_TOKENS[i]);
            assertEquals(
                    Float.floatToRawIntBits(expected),
                    Float.floatToRawIntBits(actual[i]),
                    "Bit mismatch for token " + FLOAT_EDGE_TOKENS[i]);
        }
        assertNextTagIsSentinel(scanner);
    }

    @Test
    void floatArrayFallbackOnEntitiesCrlfCommentsCdataAndPi() {
        final String xml = "<root>"
                + "<f>1.5&#32;-2.25</f>"
                + "<f>3.5\r\n 4.5</f>"
                + "<f>5.5<!--comment--> 6.5</f>"
                + "<f><![CDATA[7.5 8.5]]></f>"
                + "<f>9.5<?pi ignored?> 10.5</f>"
                + "<" + SENTINEL_TAG + "/>"
                + "</root>";
        final Utf8XmlPullScanner scanner = openFirstChild(xml);

        assertFloatBitsEqual(new float[] {1.5f, -2.25f}, scanner.readFloatArray(2));
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextChildOrEnd());
        assertFloatBitsEqual(new float[] {3.5f, 4.5f}, scanner.readFloatArray(2));
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextChildOrEnd());
        assertFloatBitsEqual(new float[] {5.5f, 6.5f}, scanner.readFloatArray(2));
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextChildOrEnd());
        assertFloatBitsEqual(new float[] {7.5f, 8.5f}, scanner.readFloatArray(2));
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextChildOrEnd());
        assertFloatBitsEqual(new float[] {9.5f, 10.5f}, scanner.readFloatArray(2));
        assertNextTagIsSentinel(scanner);
    }

    @Test
    void floatArrayEmptyZeroCountOverflowAndErrorContracts() {
        final Utf8XmlPullScanner zeroAndOverflow = openFirstChild(
                "<root><f/><f></f><f>1.0 2.0 3.0</f><f>4.0 5.0 <!--c-->6.0</f><" + SENTINEL_TAG + "/></root>");
        assertEquals(0, zeroAndOverflow.readFloatArray(0).length);
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, zeroAndOverflow.nextChildOrEnd());
        assertEquals(0, zeroAndOverflow.readFloatArray(0).length);
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, zeroAndOverflow.nextChildOrEnd());
        assertFloatBitsEqual(new float[] {1.0f, 2.0f}, zeroAndOverflow.readFloatArray(2));
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, zeroAndOverflow.nextChildOrEnd());
        assertFloatBitsEqual(new float[] {4.0f, 5.0f}, zeroAndOverflow.readFloatArray(2));
        assertNextTagIsSentinel(zeroAndOverflow);

        assertThrows(
                Utf8XmlPullScanner.StreamingAbortException.class,
                () -> openFirstChild("<root><f/></root>").readFloatArray(1));
        assertThrows(
                Utf8XmlPullScanner.StreamingAbortException.class,
                () -> openFirstChild("<root><f>1.0</f></root>").readFloatArray(2));
        assertThrows(
                NumberFormatException.class,
                () -> openFirstChild("<root><f></f></root>").readFloatArray(1));
        assertThrows(
                NumberFormatException.class,
                () -> openFirstChild("<root><f>1.0 </f></root>").readFloatArray(2));
    }

    @Test
    void intArrayExactParityFallbackAndErrorContracts() {
        final String joined = String.join(" ", INT_EDGE_TOKENS);
        final String xml = "<root>"
                + "<i>" + joined + "</i>"
                + "<i>10&#32;20</i>"
                + "<i>30<!--c--> 40</i>"
                + "<i><![CDATA[50 60]]></i>"
                + "<i/>"
                + "<i></i>"
                + "<i>70 80 90</i>"
                + "<" + SENTINEL_TAG + "/>"
                + "</root>";
        final Utf8XmlPullScanner scanner = openFirstChild(xml);

        final int[] actual = scanner.readIntArray(INT_EDGE_TOKENS.length);
        assertEquals(INT_EDGE_TOKENS.length, actual.length);
        for (int i = 0; i < INT_EDGE_TOKENS.length; i++) {
            assertEquals(Integer.parseInt(INT_EDGE_TOKENS[i]), actual[i], "Mismatch for token " + INT_EDGE_TOKENS[i]);
        }
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextChildOrEnd());
        assertArrayEquals(new int[] {10, 20}, scanner.readIntArray(2));
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextChildOrEnd());
        assertArrayEquals(new int[] {30, 40}, scanner.readIntArray(2));
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextChildOrEnd());
        assertArrayEquals(new int[] {50, 60}, scanner.readIntArray(2));
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextChildOrEnd());
        assertEquals(0, scanner.readIntArray(0).length);
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextChildOrEnd());
        assertEquals(0, scanner.readIntArray(0).length);
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextChildOrEnd());
        assertArrayEquals(new int[] {70, 80}, scanner.readIntArray(2));
        assertNextTagIsSentinel(scanner);

        assertThrows(
                Utf8XmlPullScanner.StreamingAbortException.class,
                () -> openFirstChild("<root><i/></root>").readIntArray(1));
        assertThrows(
                Utf8XmlPullScanner.StreamingAbortException.class,
                () -> openFirstChild("<root><i>1</i></root>").readIntArray(2));
        assertThrows(
                NumberFormatException.class,
                () -> openFirstChild("<root><i></i></root>").readIntArray(1));
        assertThrows(
                NumberFormatException.class,
                () -> openFirstChild("<root><i>2147483648</i></root>").readIntArray(1));
    }

    private static Utf8XmlPullScanner openFirstChild(final String xml) {
        final Utf8XmlPullScanner scanner = new Utf8XmlPullScanner(xml.getBytes(StandardCharsets.UTF_8));
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextTagOrPi());
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextChildOrEnd());
        return scanner;
    }

    private static void assertNextTagIsSentinel(final Utf8XmlPullScanner scanner) {
        assertEquals(Utf8XmlPullScanner.EVENT_START_ELEMENT, scanner.nextChildOrEnd());
        assertEquals(SENTINEL_TAG, scanner.getTagName());
    }

    private static void assertFloatBitsEqual(final float[] expected, final float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]));
        }
    }
}
