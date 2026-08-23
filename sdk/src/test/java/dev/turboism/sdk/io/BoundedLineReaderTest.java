package dev.turboism.sdk.io;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedLineReaderTest {

    @Test
    void returnsCrLfAndFinalEofLinesWithoutTheirTerminators() throws Exception {
        try (BoundedLineReader reader = new BoundedLineReader(
            new StringReader("first\r\nsecond\nthird"), 16
        )) {
            assertEquals("first", reader.readLine());
            assertEquals("second", reader.readLine());
            assertEquals("third", reader.readLine());
            assertNull(reader.readLine());
        }
    }

    @Test
    void rejectsAnOversizedLineAfterReadingAtMostMaximumPlusOneCharacters() throws Exception {
        try (BoundedLineReader reader = new BoundedLineReader(
            new StringReader("abcde\nnext\n"), 4
        )) {
            assertThrows(BoundedLineReader.LineTooLongException.class, reader::readLine);

            reader.discardLine();
            assertEquals("next", reader.readLine());
        }
    }

    @Test
    void drainsAnOversizedDiagnosticLineWhileMaterializingOnlyItsPrefix() throws Exception {
        try (BoundedLineReader reader = new BoundedLineReader(
            new StringReader("abcdefgh\r\nnext\n"), 4
        )) {
            final BoundedLineReader.Line line = reader.readLineTruncated();

            assertEquals("abcd", line.text());
            assertTrue(line.truncated());
            final BoundedLineReader.Line next = reader.readLineTruncated();
            assertEquals("next", next.text());
            assertFalse(next.truncated());
            assertNull(reader.readLineTruncated());
        }
    }
}
