package dev.turboism.adapter.cubism.startup;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class StartupSuppressionClassFileTransformerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void transformsOnlyThePinnedCodeSourceAndBecomesInertAfterTheTargetAttempt() throws Exception {
        final Path artifact = Files.write(temporaryDirectory.resolve("Live2D_Cubism.jar"), new byte[]{1});
        final StartupSuppressionProfile profile = StartupSuppressionProfile.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_02
        ).orElseThrow();
        final byte[] original = StartupSuppressionTransformerTest.fixtureApplicationForInstaller();
        final AtomicInteger cleanupCalls = new AtomicInteger();
        final List<String> diagnostics = new ArrayList<>();
        final StartupSuppressionClassFileTransformer transformer =
            new StartupSuppressionClassFileTransformer(
                artifact.toRealPath(),
                profile,
                new RuntimeStartupConfig(false, true, true, true),
                ignored -> cleanupCalls.incrementAndGet(),
                diagnostics::add
            );
        final ClassLoader hostLoader = new ClassLoader() { };
        final ProtectionDomain exactDomain = domain(artifact.toRealPath().toUri().toURL());

        final byte[] transformed = transformer.transform(
            null,
            hostLoader,
            profile.targetOwner(),
            null,
            exactDomain,
            original
        );

        org.junit.jupiter.api.Assertions.assertNotNull(transformed);
        assertEquals(1, cleanupCalls.get());
        assertEquals(List.of("STARTUP_SUPPRESSION_TRANSFORM_TRANSFORMED"), diagnostics);
        assertNull(transformer.transform(
            null,
            hostLoader,
            profile.targetOwner(),
            null,
            exactDomain,
            original
        ));
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    void rejectsANameMatchFromTheWrongCodeSourceWithoutReturningPartialBytes() throws Exception {
        final Path artifact = Files.write(temporaryDirectory.resolve("Live2D_Cubism.jar"), new byte[]{1});
        final Path other = Files.write(temporaryDirectory.resolve("other.jar"), new byte[]{2});
        final StartupSuppressionProfile profile = StartupSuppressionProfile.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_02
        ).orElseThrow();
        final byte[] original = StartupSuppressionTransformerTest.fixtureApplicationForInstaller();
        final AtomicInteger cleanupCalls = new AtomicInteger();
        final List<String> diagnostics = new ArrayList<>();
        final StartupSuppressionClassFileTransformer transformer =
            new StartupSuppressionClassFileTransformer(
                artifact.toRealPath(),
                profile,
                new RuntimeStartupConfig(false, true, true, true),
                ignored -> cleanupCalls.incrementAndGet(),
                diagnostics::add
            );

        assertNull(transformer.transform(
            null,
            new ClassLoader() { },
            profile.targetOwner(),
            null,
            domain(other.toRealPath().toUri().toURL()),
            original
        ));
        assertEquals(1, cleanupCalls.get());
        assertSame(
            StartupSuppressionClassFileTransformer.Outcome.CODE_SOURCE_REJECTED,
            transformer.outcome()
        );
        assertEquals(List.of("STARTUP_SUPPRESSION_TRANSFORM_CODE_SOURCE_REJECTED"), diagnostics);
    }

    private static ProtectionDomain domain(final URL location) {
        return new ProtectionDomain(new CodeSource(location, (java.security.cert.Certificate[]) null), null);
    }
}
