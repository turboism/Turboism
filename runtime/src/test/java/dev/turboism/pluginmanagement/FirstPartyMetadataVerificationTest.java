package dev.turboism.pluginmanagement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** First-party admission fails closed on unregistered categories, drift, and stale artifacts. */
class FirstPartyMetadataVerificationTest {
    @TempDir Path temp;

    @Test
    void registeredV3TrackedDescriptorAndMatchingJarPass() throws Exception {
        final Path descriptor = tracked("modeling", "[\"parameter\", \"batch-edit\"]");
        final Path jar = jarFor(descriptor);

        FirstPartyMetadataVerificationCli.verify(descriptor, jar);
    }

    @Test
    void unregisteredTrackedCategoryIsRejected() throws Exception {
        final Path descriptor = tracked("custom-tooling", "[]");
        final Path jar = jarFor(descriptor);

        final FirstPartyMetadataVerificationCli.FirstPartyRejection failure = assertThrows(
            FirstPartyMetadataVerificationCli.FirstPartyRejection.class,
            () -> FirstPartyMetadataVerificationCli.verify(descriptor, jar)
        );
        assertEquals("FIRST_PARTY_CATEGORY_UNREGISTERED", failure.code());
    }

    @Test
    void sourceJarClassificationDriftIsRejected() throws Exception {
        final Path descriptor = tracked("modeling", "[\"parameter\"]");
        final Path jar = jarFor(descriptor);

        final Path drifted = tracked("modeling", "[\"binding\"]");

        final FirstPartyMetadataVerificationCli.FirstPartyRejection failure = assertThrows(
            FirstPartyMetadataVerificationCli.FirstPartyRejection.class,
            () -> FirstPartyMetadataVerificationCli.verify(drifted, jar)
        );
        assertEquals("FIRST_PARTY_CLASSIFICATION_MISMATCH", failure.code());
    }

    @Test
    void unsupportedTrackedSchemaVersionIsRejected() throws Exception {
        final Path descriptor = tracked("modeling", "[]");
        final Path jar = jarFor(descriptor);
        final String text = Files.readString(descriptor);
        Files.writeString(descriptor, text.replace("\"schemaVersion\": 3", "\"schemaVersion\": 4"));

        final FirstPartyMetadataVerificationCli.FirstPartyRejection failure = assertThrows(
            FirstPartyMetadataVerificationCli.FirstPartyRejection.class,
            () -> FirstPartyMetadataVerificationCli.verify(descriptor, jar)
        );
        assertEquals("FIRST_PARTY_SCHEMA_NOT_V3", failure.code());
    }

    @Test
    void embeddedSchemaOutsideRuntimeSupportIsRejected() throws Exception {
        final Path descriptor = tracked("modeling", "[]");
        final String text = Files.readString(descriptor);
        final Path unsupportedDescriptor = temp.resolve("unsupported.json");
        Files.writeString(unsupportedDescriptor, text.replace("\"schemaVersion\": 3", "\"schemaVersion\": 9"));

        final FirstPartyMetadataVerificationCli.FirstPartyRejection failure = assertThrows(
            FirstPartyMetadataVerificationCli.FirstPartyRejection.class,
            () -> FirstPartyMetadataVerificationCli.verify(descriptor, jarFor(unsupportedDescriptor))
        );
        assertTrue(failure.code().startsWith("FIRST_PARTY_EMBEDDED_DESCRIPTOR_INVALID"),
            failure.code());
    }

    @Test
    void staleResidualJarInTheSameDirectoryDoesNotInfluenceSelection() throws Exception {
        // The gate binds the explicit Gradle archive; a stale residual JAR in
        // the same directory must never be scanned or substituted.
        final Path descriptor = tracked("modeling", "[\"parameter\"]");
        final Path fresh = jarFor(descriptor);

        final Path stale = temp.resolve("stale");
        Files.createDirectories(stale);
        final Path staleJar = stale.resolve("example-plugin-9.9.9.jar");
        Files.writeString(staleJar, "not-a-zip");
        final Path sameDirFresh = stale.resolve("example-plugin-0.42.0.jar");
        Files.copy(fresh, sameDirFresh);

        FirstPartyMetadataVerificationCli.verify(descriptor, sameDirFresh);
        final FirstPartyMetadataVerificationCli.FirstPartyRejection failure = assertThrows(
            FirstPartyMetadataVerificationCli.FirstPartyRejection.class,
            () -> FirstPartyMetadataVerificationCli.verify(descriptor, staleJar)
        );
        assertTrue(failure.code().startsWith("FIRST_PARTY_JAR_"), failure.code());
    }

    private Path tracked(final String category, final String tagsJson) throws Exception {
        final Path path = temp.resolve("plugin-" + category + "-" + Math.abs(category.hashCode()) + ".json");
        Files.writeString(path, """
            {
              "format": "turboism.plugin.meta",
              "schemaVersion": 3,
              "id": "dev.turboism.plugin.firstparty",
              "name": "First Party",
              "version": "1.0.0",
              "entrypoints": ["dev.turboism.plugin.firstparty.FirstPartyPlugin"],
              "turboismApi": "[0.1.0,0.2.0)",
              "authors": [{"name":"Turboism"}],
              "license": "Project License",
              "website": "https://turboism.dev",
              "resources": [],
              "i18n": {"baseName":"META-INF/turboism/i18n/messages","locales":[]},
              "dependencies": [],
              "permissions": [],
              "capabilities": [],
              "environment": {"requiresCubism":false,"ui":"none"},
              "category": "%s",
              "tags": %s
            }
            """.formatted(category, tagsJson));
        return path;
    }

    private Path jarFor(final Path descriptor) throws Exception {
        return jarForFrom(descriptor, temp.resolve("built"));
    }

    private Path jarForFrom(final Path descriptor, final Path base) throws Exception {
        final Path jarPath = base.resolve("firstparty-1.0.0.jar");
        Files.createDirectories(jarPath.getParent());
        final Path work = base.resolve("work");
        final Path meta = work.resolve("META-INF/turboism");
        final Path i18n = meta.resolve("i18n");
        Files.createDirectories(meta);
        Files.createDirectories(i18n);
        Files.copy(descriptor, meta.resolve("plugin.json"));
        Files.writeString(i18n.resolve("messages.properties"), "plugin.name=First Party\nplugin.description=First party\n");
        final Path classFile = work.resolve("dev/turboism/plugin/firstparty/FirstPartyPlugin.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, new byte[]{0});
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jarPath))) {
            try (var files = Files.walk(work)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    out.putNextEntry(new java.util.zip.ZipEntry(work.relativize(file).toString()));
                    out.write(Files.readAllBytes(file));
                    out.closeEntry();
                }
            }
        }
        return jarPath;
    }
}
