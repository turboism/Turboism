package dev.turboism.distribution;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginManifestFixtureTest {
    private static final Path ROOT = Path.of("src/test/resources/fixtures/schema/plugin-package-manifest-v1");

    @Test void productionParserAcceptsPersistedValidFixtures() throws Exception {
        try (Stream<Path> fixtures = Files.list(ROOT.resolve("valid"))) {
            for (Path fixture : fixtures.toList()) {
                assertDoesNotThrow(() -> parse(fixture), fixture.toString());
            }
        }
    }

    @Test void productionParserRejectsPersistedInvalidFixtures() throws Exception {
        try (Stream<Path> fixtures = Files.list(ROOT.resolve("invalid"))) {
            for (Path fixture : fixtures.toList()) {
                assertThrows(DistributionValidationException.class, () -> parse(fixture), fixture.toString());
            }
        }
    }

    @Test void fixtureSetMeetsSchemaMinimum() throws Exception {
        try (Stream<Path> valid = Files.list(ROOT.resolve("valid"));
             Stream<Path> invalid = Files.list(ROOT.resolve("invalid"))) {
            org.junit.jupiter.api.Assertions.assertTrue(valid.count() >= 1);
            org.junit.jupiter.api.Assertions.assertTrue(invalid.count() >= 3);
        }
    }

    private static void parse(Path fixture) throws Exception {
        try (InputStream input = Files.newInputStream(fixture)) { PluginManifestReader.read(input); }
    }
}
