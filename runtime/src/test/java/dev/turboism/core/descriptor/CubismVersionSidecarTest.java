package dev.turboism.core.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.descriptor.CubismVersionSidecar.Read;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CubismVersionSidecarTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.demo";
    private static final String FORMAT = "turboism.plugin-cubism-versions/v1";

    @TempDir
    Path temporary;

    @Test
    void absentBytesAreUndeclaredNotMalformed() {
        // Absence is decided before the id is compared: no document, no mismatch to report.
        assertInstanceOf(Read.Absent.class, CubismVersionSidecar.read((byte[]) null, "any.neighbouring.plugin"));
    }

    @Test
    void absentEntryInArchiveIsUndeclared() throws IOException {
        final Path jar = temporary.resolve("no-sidecar.jar");
        writeJar(jar, null);

        try (JarFile archive = new JarFile(jar.toFile())) {
            assertInstanceOf(Read.Absent.class, CubismVersionSidecar.read(archive, PLUGIN_ID));
        }
    }

    @Test
    void declaredSupportedVersionsOnly() {
        final CubismVersionSidecar.Declaration declaration = declared(read("""
                {"format":"%s","plugin":"%s","supportedVersions":["5.0.00","5.3.04"]}
                """.formatted(FORMAT, PLUGIN_ID)));

        assertEquals(PLUGIN_ID, declaration.pluginId());
        assertEquals(List.of("5.0.00", "5.3.04"), declaration.supportedVersions());
        assertTrue(declaration.fullFeatureVersions().isEmpty());
    }

    @Test
    void declaredFullFeatureVersions() {
        final CubismVersionSidecar.Declaration declaration = declared(read("""
                {"format":"%s","plugin":"%s","supportedVersions":["5.0.00","5.2.03"],
                 "fullFeatureVersions":["5.2.03"]}
                """.formatted(FORMAT, PLUGIN_ID)));

        assertEquals(List.of("5.0.00", "5.2.03"), declaration.supportedVersions());
        assertEquals(List.of("5.2.03"), declaration.fullFeatureVersions().orElseThrow());
    }

    @Test
    void declaredValuesAreImmutable() {
        final CubismVersionSidecar.Declaration declaration = declared(read("""
                {"format":"%s","plugin":"%s","supportedVersions":["5.2.03"],"fullFeatureVersions":["5.2.03"]}
                """.formatted(FORMAT, PLUGIN_ID)));

        assertThrows(
                UnsupportedOperationException.class,
                () -> declaration.supportedVersions().add("5.3.04"));
        final List<String> fullFeatures = declaration.fullFeatureVersions().orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> fullFeatures.add("5.3.04"));
    }

    @Test
    void ignoresUnknownTopLevelFields() {
        final CubismVersionSidecar.Declaration declaration = declared(read("""
                {"format":"%s","plugin":"%s","supportedVersions":["5.2.03"],"vendorNote":"additive"}
                """.formatted(FORMAT, PLUGIN_ID)));

        assertEquals(List.of("5.2.03"), declaration.supportedVersions());
    }

    @Test
    void readsSidecarFromArchive() throws IOException {
        final Path jar = temporary.resolve("sidecar.jar");
        writeJar(jar, """
                {"format":"%s","plugin":"%s","supportedVersions":["5.0.00"]}
                """.formatted(FORMAT, PLUGIN_ID));

        try (JarFile archive = new JarFile(jar.toFile())) {
            assertEquals(
                    List.of("5.0.00"),
                    declared(CubismVersionSidecar.read(archive, PLUGIN_ID)).supportedVersions());
            assertInstanceOf(Read.Malformed.class, CubismVersionSidecar.read(archive, "dev.turboism.plugin.other"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedDocuments")
    void malformedDocumentsAreRejectedWhole(final String expectedCode, final String document) {
        final Read.Malformed malformed = assertInstanceOf(Read.Malformed.class, read(document));

        assertEquals(expectedCode, malformed.code());
        assertFalse(malformed.detail().isBlank());
    }

    static Stream<Arguments> malformedDocuments() {
        final String valid = """
                {"format":"%s","plugin":"%s","supportedVersions":["5.2.03"]}
                """.formatted(FORMAT, PLUGIN_ID);
        return Stream.of(
                Arguments.of("CUBISM_SIDECAR_INVALID_JSON", "{not json"),
                Arguments.of("CUBISM_SIDECAR_NOT_AN_OBJECT", ""),
                Arguments.of("CUBISM_SIDECAR_INVALID_JSON", valid + "{}"),
                Arguments.of("CUBISM_SIDECAR_INVALID_JSON", """
                        {"format":"%s","format":"%s","plugin":"%s","supportedVersions":["5.2.03"]}
                        """.formatted(FORMAT, FORMAT, PLUGIN_ID)),
                Arguments.of("CUBISM_SIDECAR_NOT_AN_OBJECT", "[]"),
                Arguments.of("CUBISM_SIDECAR_BAD_FORMAT", """
                        {"plugin":"%s","supportedVersions":["5.2.03"]}
                        """.formatted(PLUGIN_ID)),
                Arguments.of("CUBISM_SIDECAR_BAD_FORMAT", """
                        {"format":"turboism.plugin-cubism-versions/v2","plugin":"%s","supportedVersions":["5.2.03"]}
                        """.formatted(PLUGIN_ID)),
                Arguments.of("CUBISM_SIDECAR_PLUGIN_MISMATCH", """
                        {"format":"%s","supportedVersions":["5.2.03"]}
                        """.formatted(FORMAT)),
                Arguments.of("CUBISM_SIDECAR_PLUGIN_MISMATCH", """
                        {"format":"%s","plugin":"dev.turboism.plugin.other","supportedVersions":["5.2.03"]}
                        """.formatted(FORMAT)),
                Arguments.of("CUBISM_SIDECAR_MISSING_VERSIONS", """
                        {"format":"%s","plugin":"%s"}
                        """.formatted(FORMAT, PLUGIN_ID)),
                Arguments.of("CUBISM_SIDECAR_MISSING_VERSIONS", """
                        {"format":"%s","plugin":"%s","supportedVersions":[]}
                        """.formatted(FORMAT, PLUGIN_ID)),
                Arguments.of("CUBISM_SIDECAR_MISSING_VERSIONS", """
                        {"format":"%s","plugin":"%s","supportedVersions":"5.2.03"}
                        """.formatted(FORMAT, PLUGIN_ID)),
                Arguments.of("CUBISM_SIDECAR_MISSING_VERSIONS", """
                        {"format":"%s","plugin":"%s","supportedVersions":["5.2.03"],"fullFeatureVersions":[]}
                        """.formatted(FORMAT, PLUGIN_ID)),
                Arguments.of("CUBISM_SIDECAR_BAD_VERSION", """
                        {"format":"%s","plugin":"%s","supportedVersions":["5.2"]}
                        """.formatted(FORMAT, PLUGIN_ID)),
                Arguments.of("CUBISM_SIDECAR_BAD_VERSION", """
                        {"format":"%s","plugin":"%s","supportedVersions":["5.2.03.1"]}
                        """.formatted(FORMAT, PLUGIN_ID)),
                Arguments.of("CUBISM_SIDECAR_BAD_VERSION", """
                        {"format":"%s","plugin":"%s","supportedVersions":[5.2]}
                        """.formatted(FORMAT, PLUGIN_ID)),
                Arguments.of("CUBISM_SIDECAR_DUPLICATE_VERSION", """
                        {"format":"%s","plugin":"%s","supportedVersions":["5.2.03","5.2.03"]}
                        """.formatted(FORMAT, PLUGIN_ID)),
                Arguments.of("CUBISM_SIDECAR_DUPLICATE_VERSION", """
                        {"format":"%s","plugin":"%s","supportedVersions":["5.2.03"],"fullFeatureVersions":["5.2.03","5.2.03"]}
                        """.formatted(FORMAT, PLUGIN_ID)),
                Arguments.of("CUBISM_SIDECAR_FULL_FEATURE_NOT_SUPPORTED", """
                        {"format":"%s","plugin":"%s","supportedVersions":["5.2.03"],"fullFeatureVersions":["5.3.04"]}
                        """.formatted(FORMAT, PLUGIN_ID)));
    }

    @Test
    void rejectsOversizedSidecar() {
        final byte[] oversized = new byte[CubismVersionSidecar.MAX_SIDECAR_BYTES + 1];

        final Read.Malformed malformed =
                assertInstanceOf(Read.Malformed.class, CubismVersionSidecar.read(oversized, PLUGIN_ID));

        assertEquals("CUBISM_SIDECAR_TOO_LARGE", malformed.code());
    }

    @Test
    void acceptsSidecarAtTheSizeLimit() {
        final String document = """
                {"format":"%s","plugin":"%s","supportedVersions":["5.2.03"]}
                """.formatted(FORMAT, PLUGIN_ID);
        final byte[] padded = new byte[CubismVersionSidecar.MAX_SIDECAR_BYTES];
        final byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        for (int index = bytes.length; index < padded.length; index++) {
            padded[index] = ' ';
        }

        assertEquals(
                List.of("5.2.03"),
                declared(CubismVersionSidecar.read(padded, PLUGIN_ID)).supportedVersions());
    }

    @Test
    void rejectsMoreThan256Versions() {
        final List<String> versions = new ArrayList<>();
        for (int minor = 0; versions.size() <= 256; minor++) {
            versions.add("5." + minor + ".00");
        }
        final String document = """
                {"format":"%s","plugin":"%s","supportedVersions":[%s]}
                """.formatted(
                        FORMAT,
                        PLUGIN_ID,
                        versions.stream()
                                .map(version -> "\"" + version + "\"")
                                .collect(java.util.stream.Collectors.joining(",")));

        final Read.Malformed malformed = assertInstanceOf(Read.Malformed.class, read(document));

        assertEquals("CUBISM_SIDECAR_TOO_MANY_VERSIONS", malformed.code());
    }

    /** Every shipped first-party sidecar is a valid declaration for its own descriptor. */
    @Test
    void allShippedSidecarsAreValidDeclarations() throws IOException, DescriptorParseException {
        final List<Path> sidecars = shippedSidecars();
        assertEquals(37, sidecars.size(), "expected 37 shipped cubism-versions.json sidecars");

        for (Path sidecar : sidecars) {
            final Path descriptor = sidecar.getParent().resolve("plugin.json");
            final String pluginId = descriptorId(descriptor);
            assertEquals(
                    pluginId,
                    declared(CubismVersionSidecar.read(Files.readAllBytes(sidecar), pluginId))
                            .pluginId(),
                    sidecar.toString());
        }
    }

    private static List<Path> shippedSidecars() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root from user.dir");
        }
        final List<Path> sidecars = new ArrayList<>();
        for (String root : List.of("plugins", "templates")) {
            try (Stream<Path> modules = Files.list(current.resolve(root))) {
                for (Path module : modules.toList()) {
                    final Path sidecar = module.resolve("src/main/resources/" + CubismVersionSidecar.ENTRY);
                    if (Files.isRegularFile(sidecar)) {
                        sidecars.add(sidecar);
                    }
                }
            }
        }
        sidecars.sort(Comparator.comparing(Path::toString));
        return List.copyOf(sidecars);
    }

    /** Reads the descriptor id with the production parser so the id shape stays canonical. */
    private static String descriptorId(final Path descriptor) throws IOException, DescriptorParseException {
        try (InputStream source = Files.newInputStream(descriptor)) {
            return new PluginDescriptorParser().parse(source).id();
        }
    }

    private static CubismVersionSidecar.Read read(final String document) {
        return CubismVersionSidecar.read(document.getBytes(StandardCharsets.UTF_8), PLUGIN_ID);
    }

    private static CubismVersionSidecar.Declaration declared(final CubismVersionSidecar.Read read) {
        return assertInstanceOf(Read.Declared.class, read).declaration();
    }

    private static void writeJar(final Path jar, final String sidecar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/turboism/plugin.json"));
            output.write("{}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            if (sidecar != null) {
                output.putNextEntry(new JarEntry(CubismVersionSidecar.ENTRY));
                output.write(sidecar.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }
}
