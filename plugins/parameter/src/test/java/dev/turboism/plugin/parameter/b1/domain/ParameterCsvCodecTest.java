package dev.turboism.plugin.parameter.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

final class ParameterCsvCodecTest {

    @Test
    void parsesQuotedMultilineRecordsAndCanonicalizesOrderingAndNumbers() {
        final String input = "id,value\r\n\"z,part\",1.2300\r\n\"a\"\"quote\",-2.500\r\n\"multi\r\nline\",0\r\n";
        final ParameterCsvParseResult parsed = ParameterCsvCodec.parse(input);
        assertTrue(parsed.valid(), parsed.error().toString());
        assertEquals(List.of("z,part", "a\"quote", "multi\r\nline"), parsed.rows().stream().map(ParameterCsvRow::id).toList());
        assertEquals("id,value\n\"a\"\"quote\",-2.5\n\"multi\r\nline\",0\n\"z,part\",1.23\n", ParameterCsvCodec.serialize(parsed.rows()));
    }

    @Test
    void reportsFirstStructuralOrSemanticErrorWithStableLocation() {
        assertError(ParameterCsvErrorCode.HEADER_INVALID, "x,value\na,1\n");
        assertError(ParameterCsvErrorCode.RECORD_BLANK, "id,value\n\na,1\n");
        assertError(ParameterCsvErrorCode.FIELD_COUNT, "id,value\na,1,2\n");
        assertError(ParameterCsvErrorCode.QUOTE_UNCLOSED, "id,value\n\"a,1\n");
        assertError(ParameterCsvErrorCode.QUOTE_TRAILING, "id,value\n\"a\"x,1\n");
        assertError(ParameterCsvErrorCode.VALUE_INVALID, "id,value\na,01\n");
        assertError(ParameterCsvErrorCode.VALUE_NEGATIVE_ZERO, "id,value\na,-0.00\n");
        final ParameterCsvParseResult duplicate = ParameterCsvCodec.parse("id,value\na,1\nb,2\na,3\n");
        assertEquals(ParameterCsvErrorCode.DUPLICATE_ID, duplicate.error().orElseThrow().code());
        assertEquals(2, duplicate.error().orElseThrow().firstRecord());
        assertEquals(4, duplicate.error().orElseThrow().record());
    }

    @Test
    void acceptsHeaderOnlyAndRejectsBoundsAndControls() {
        assertEquals(List.of(), ParameterCsvCodec.parse("id,value").rows());
        assertError(ParameterCsvErrorCode.ID_EMPTY, "id,value\n,1\n");
        assertError(ParameterCsvErrorCode.CONTROL_FORBIDDEN, "id,value\na\u0000,1\n");
        assertError(ParameterCsvErrorCode.ID_LIMIT, "id,value\n" + "😀".repeat(257) + ",1\n");
    }

    @Test
    void randomizedRoundTripsAreCanonicalAndLocaleIndependent() {
        final SplittableRandom random = new SplittableRandom(0xC5B1L);
        for (int iteration = 0; iteration < 200; iteration++) {
            final java.util.ArrayList<ParameterCsvRow> rows = new java.util.ArrayList<>();
            final int count = random.nextInt(1, 50);
            for (int index = 0; index < count; index++) {
                rows.add(new ParameterCsvRow("id," + index, BigDecimal.valueOf(random.nextLong(-1_000_000, 1_000_000), random.nextInt(0, 5))));
            }
            final String encoded = ParameterCsvCodec.serialize(rows);
            final ParameterCsvParseResult parsed = ParameterCsvCodec.parse(encoded);
            assertTrue(parsed.valid(), parsed.error().toString());
            assertEquals(encoded, ParameterCsvCodec.serialize(parsed.rows()));
        }
    }

    private static void assertError(ParameterCsvErrorCode code, String input) {
        assertEquals(code, ParameterCsvCodec.parse(input).error().orElseThrow().code());
    }
}
