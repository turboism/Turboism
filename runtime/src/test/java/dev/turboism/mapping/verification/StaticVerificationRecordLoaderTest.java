package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticVerificationRecordLoaderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void loadsValidatedRecordAndVerifiesItsArtifact() throws Exception {
        Path artifact = jarContaining(SampleTarget.class);
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);
        String owner = internalName(SampleTarget.class);
        Path record = Files.createTempFile("turboism-static-record", ".json");
        mapper.writeValue(record.toFile(), mapper.readTree("""
            {
              "format": "turboism.static.verification.record",
              "schemaVersion": 1,
              "verificationId": "fixture.static",
              "adapterSliceId": "adapter.project-workspace.readonly",
              "capabilityIds": ["cubism.project.read"],
              "cubismVersion": "5.3.02",
              "profileId": "cubism-5.3.02",
              "artifact": {
                "name": "%s",
                "size": %d,
                "sha256": "%s"
              },
              "evidenceType": "JAR_METADATA",
              "evidencePath": "docs/migration/verification/static/fixture.json",
              "owner": "runtime-adapter",
              "status": "VERIFIED_STATIC",
              "verifiedBy": "test-verifier",
              "verifiedAt": "2026-07-10T00:00:00Z",
              "safeMode": "Fail closed.",
              "selectors": [
                {
                  "mappingId": "fixture.method.greet",
                  "alias": "fixture.greet",
                  "kind": "method",
                  "ownerInternalName": "%s",
                  "memberName": "greet",
                  "descriptor": "(Ljava/lang/String;)Ljava/lang/String;",
                  "requiredAccessFlags": 0,
                  "forbiddenAccessFlags": 8,
                  "status": "VERIFIED_STATIC"
                }
              ]
            }
            """.formatted(
                artifact.getFileName(),
                fingerprint.size(),
                fingerprint.sha256(),
                owner
            )));

        StaticVerificationRecord loaded = new StaticVerificationRecordLoader().load(record).record();
        StaticVerificationReport report = new StaticVerificationCli().verify(record, artifact);

        assertEquals("fixture.static", loaded.verificationId());
        assertEquals(1, loaded.selectors().size());
        assertTrue(report.allSelectorsVerified());
    }

    private static Path jarContaining(final Class<?> type) throws Exception {
        Path jar = Files.createTempFile("turboism-record-fixture", ".jar");
        String entryName = internalName(type) + ".class";
        try (InputStream input = type.getClassLoader().getResourceAsStream(entryName);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private static String internalName(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    static final class SampleTarget {
        String greet(final String name) {
            return "Hello " + name;
        }
    }
}
