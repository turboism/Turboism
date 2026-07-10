package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedMemberResolverFactoryAttestationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsRuntimeClassLoaderWhoseOwnerBytesDifferFromVerifiedJar() throws Exception {
        CompiledHost verified = compileHost("verified");
        CompiledHost runtime = compileHost("runtime-different");
        Path record = recordFor(verified.jar());
        try (URLClassLoader runtimeLoader = new URLClassLoader(
            new URL[]{runtime.jar().toUri().toURL()},
            ClassLoader.getPlatformClassLoader()
        )) {
            assertThrows(IllegalArgumentException.class, () -> new VerifiedMemberResolverFactory().create(
                record,
                verified.jar(),
                runtimeLoader,
                "fixture.attested",
                "adapter.test",
                Set.of("test.capability"),
                Set.of("host.value")
            ));
        }
    }

    @Test
    void acceptsRuntimeClassLoaderWhoseOwnerBytesMatchVerifiedJar() throws Exception {
        CompiledHost verified = compileHost("verified");
        Path record = recordFor(verified.jar());
        try (URLClassLoader runtimeLoader = new URLClassLoader(
            new URL[]{verified.jar().toUri().toURL()},
            ClassLoader.getPlatformClassLoader()
        )) {
            VerifiedMemberResolver resolver = new VerifiedMemberResolverFactory().create(
                record,
                verified.jar(),
                runtimeLoader,
                "fixture.attested",
                "adapter.test",
                Set.of("test.capability"),
                Set.of("host.value")
            );
            Object host = runtimeLoader.loadClass("attested.Host").getConstructor().newInstance();
            assertEquals("verified", resolver.invoke("host.value", host));
        }
    }

    private Path recordFor(final Path artifact) throws Exception {
        HostArtifactFingerprint fingerprint = HostArtifactFingerprint.from("5.3.02", artifact);
        Path record = Files.createTempFile("turboism-attestation-record", ".json");
        mapper.writeValue(record.toFile(), mapper.readTree("""
            {
              "format":"turboism.static.verification.record",
              "schemaVersion":1,
              "verificationId":"fixture.attested",
              "adapterSliceId":"adapter.test",
              "capabilityIds":["test.capability"],
              "cubismVersion":"5.3.02",
              "profileId":"fixture",
              "artifact":{"name":"host.jar","size":%d,"sha256":"%s"},
              "evidenceType":"JAR_METADATA",
              "evidencePath":"docs/migration/verification/static/fixture.json",
              "owner":"runtime-adapter",
              "verifiedBy":"test",
              "verifiedAt":"2026-07-10T00:00:00Z",
              "safeMode":"Fail closed.",
              "status":"VERIFIED_STATIC",
              "selectors":[
                {"mappingId":"fixture.host.value","alias":"host.value","kind":"method","ownerInternalName":"attested/Host","memberName":"value","descriptor":"()Ljava/lang/String;","requiredAccessFlags":1,"forbiddenAccessFlags":8,"status":"VERIFIED_STATIC"}
              ]
            }
            """.formatted(fingerprint.size(), fingerprint.sha256())));
        return record;
    }

    private static CompiledHost compileHost(final String value) throws Exception {
        Path root = Files.createTempDirectory("turboism-attested-host");
        Path source = root.resolve("src/attested/Host.java");
        Path classes = root.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, """
            package attested;
            public final class Host {
                public String value() { return "%s"; }
            }
            """.formatted(value));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(null, null, null, "-d", classes.toString(), source.toString());
        if (result != 0) {
            throw new IllegalStateException("synthetic host compilation failed");
        }
        Path jar = root.resolve("host.jar");
        Path classFile = classes.resolve("attested/Host.class");
        try (InputStream input = Files.newInputStream(classFile);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("attested/Host.class"));
            input.transferTo(output);
            output.closeEntry();
        }
        return new CompiledHost(jar);
    }

    private record CompiledHost(Path jar) {
    }
}
