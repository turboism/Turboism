package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class FloatArrayDeserializerParityTest {

    @Test
    void parsesAllBoundaryAndEdgeTokensBitIdenticallyToJdkFloat() {
        final List<String> edgeCases = List.of(
                "0",
                "0.0",
                "-0",
                "-0.0",
                "-0.00000000",
                "+0.0",
                "-0.0e10",
                "-1e-99",
                "1e-99",
                "0.0000000000000000000000001",
                ".5",
                "-.5",
                "5.",
                "-5.",
                "1.0",
                "-1.0",
                "1.25",
                "1.2345678",
                "1.2345679",
                "12345678.0",
                "99999999.0",
                "16777215",
                "16777216",
                "16777217",
                "1.4e-45",
                "1.401298464e-45",
                "7.0064923e-46",
                "1.1754942e-38",
                "1.17549435e-38",
                "1.1754944e-38",
                "3.4028234e38",
                "3.4028235e38",
                "3.4028236e38",
                "3.40282366e38",
                "1e39",
                "-1e39",
                "NaN",
                "Infinity",
                "-Infinity",
                "+Infinity",
                "0x1.0p0",
                "0x0.000002p-126",
                "1.5f",
                "1.5d",
                "\t-3.25e+2\n");

        for (final String token : edgeCases) {
            final float expected = Float.parseFloat(token);
            final float actual = FloatArrayDeserializerBridge.parseFloatToken(token, 0, token.length());
            assertEquals(
                    Float.floatToRawIntBits(expected),
                    Float.floatToRawIntBits(actual),
                    "Bit mismatch for token: [" + token + "]");
        }
    }

    @Test
    void fuzzMatchesFloatToRawIntBitsAcrossNormalSubnormalAndRoundingTieStrings() {
        final Random random = new Random(0x5F37_59DF_1234L);
        final StringBuilder joined = new StringBuilder();
        final float[] expected = new float[20_000];
        for (int i = 0; i < expected.length; i++) {
            final String text;
            if (i % 5 == 0) {
                final int rawBits = random.nextInt();
                final float sample = Float.intBitsToFloat(rawBits);
                text = Float.toString(sample);
            } else if (i % 5 == 1) {
                final double base = (random.nextDouble() * 2.0 - 1.0) * 1e6;
                text = String.format(java.util.Locale.ROOT, "%.9f", base);
            } else if (i % 5 == 2) {
                final int raw = 0x3F80_0000 + random.nextInt(100_000);
                final double mid = ((double) Float.intBitsToFloat(raw) + Float.intBitsToFloat(raw + 1)) * 0.5;
                text = Double.toString(mid + (random.nextInt(5) - 2) * 1e-16);
            } else if (i % 5 == 3) {
                text = String.format(java.util.Locale.ROOT, "%.6e", (random.nextDouble() - 0.5) * 1e-36);
            } else {
                text = (random.nextBoolean() ? "-" : "")
                        + random.nextInt(1_000_000)
                        + "."
                        + random.nextInt(1_000_000)
                        + "e"
                        + (random.nextInt(50) - 25);
            }
            expected[i] = Float.parseFloat(text);
            if (i > 0) {
                joined.append(' ');
            }
            joined.append(text);
        }

        final float[] actual = FloatArrayDeserializerBridge.parseFloatArray(expected.length, joined.toString(), true);
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(
                    Float.floatToRawIntBits(expected[i]),
                    Float.floatToRawIntBits(actual[i]),
                    "Bit mismatch at index " + i);
        }
    }

    @Test
    void enforcesAssertionsEmptyAndInvalidSyntaxConsistently() {
        assertArrayEquals(new float[0], FloatArrayDeserializerBridge.parseFloatArray(0, "", true));
        assertArrayEquals(new float[0], FloatArrayDeserializerBridge.parseFloatArray(0, "", false));
        assertThrows(AssertionError.class, () -> FloatArrayDeserializerBridge.parseFloatArray(0, " ", true));
        assertThrows(AssertionError.class, () -> FloatArrayDeserializerBridge.parseFloatArray(2, "1.0", true));
        assertThrows(AssertionError.class, () -> FloatArrayDeserializerBridge.parseFloatArray(2, "1.0 2.0 ", true));
        assertArrayEquals(new float[] {1.0f, 2.0f}, FloatArrayDeserializerBridge.parseFloatArray(2, "1.0 2.0 ", false));

        for (final String bad : List.of("", "   ", ".", "+", "-", "abc", "1e", "1e-", "1.2.3", "0eBAD", "1..2")) {
            assertThrows(
                    NumberFormatException.class,
                    () -> FloatArrayDeserializerBridge.parseFloatToken(bad, 0, bad.length()),
                    "Expected NumberFormatException for [" + bad + "]");
        }
        assertThrows(
                IndexOutOfBoundsException.class, () -> FloatArrayDeserializerBridge.parseFloatArray(2, "1.0", false));
    }
}
