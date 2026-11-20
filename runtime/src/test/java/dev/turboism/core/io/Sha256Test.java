package dev.turboism.core.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Sha256Test {

    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    Path tempDir;

    @Test
    void computesMatchingRawBytesHexAndFileDigests() throws Exception {
        final byte[] input = "abc".getBytes(StandardCharsets.UTF_8);
        assertEquals(ABC_SHA256, Sha256.hex(input));
        assertEquals(ABC_SHA256, Sha256.hex("abc"));
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(input), Sha256.bytes(input));

        final Path file = tempDir.resolve("sample.txt");
        Files.write(file, input);
        assertEquals(ABC_SHA256, Sha256.hexOf(file));
        assertEquals(ABC_SHA256, Sha256.hex(file, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void digestingOutputTeesBytesWithoutAlteringDelegate() throws Exception {
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        final MessageDigest digest = Sha256.digest();
        try (var output = Sha256.digestingOutput(sink, digest)) {
            output.write('a');
            output.write("bc".getBytes(StandardCharsets.UTF_8));
        }
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), sink.toByteArray());
        assertEquals(ABC_SHA256, Sha256.hex(digest));
    }
}
