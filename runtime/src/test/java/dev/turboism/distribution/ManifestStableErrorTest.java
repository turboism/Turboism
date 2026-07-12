package dev.turboism.distribution;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManifestStableErrorTest {
    @Test void malformedJsonUsesStableProjectMessage() {
        DistributionValidationException error = assertThrows(DistributionValidationException.class,
            () -> ManifestReader.read(new ByteArrayInputStream("{".getBytes())));
        assertEquals("MANIFEST_JSON_INVALID", error.code());
        assertEquals("Malformed framework package manifest JSON", error.getMessage());
        assertEquals(ManifestReader.NAME, error.problemPath());
    }
}
