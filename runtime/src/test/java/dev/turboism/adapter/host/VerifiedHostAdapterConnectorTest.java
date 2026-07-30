package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedHostAdapterConnectorTest {

    @Test
    void forwardsProjectOnlyEvidenceToVerifiedFactorySeam() throws Exception {
        HostVerificationEvidence evidence = HostVerificationEvidence.projectOnly(slice("project"));
        AtomicReference<HostVerificationEvidence> seenEvidence = new AtomicReference<>();
        RuntimeHostAdapters expected = RuntimeHostAdapters.safeMode();
        VerifiedHostAdapterConnector connector = new VerifiedHostAdapterConnector(seen -> {
            seenEvidence.set(seen);
            return expected;
        });

        HostAdapterConnection connection = connector.connect(new HostInstanceDescriptor(
            "session-a",
            evidence
        ));

        assertSame(evidence, seenEvidence.get());
        assertTrue(seenEvidence.get().clipMask().isEmpty());
        assertTrue(seenEvidence.get().topMenu().isEmpty());
        assertSame(expected, connection.adapters());
    }

    @Test
    void forwardsPresentClipMaskEvidenceAsOneTypedValue() throws Exception {
        ClassLoader hostClassLoader = new ClassLoader() { };
        HostVerificationEvidence evidence = HostVerificationEvidence.withClipMask(
            slice("project", "host/Live2D_Cubism.jar", hostClassLoader),
            slice("clip", "host/./Live2D_Cubism.jar", hostClassLoader)
        );
        AtomicReference<HostVerificationEvidence> seenEvidence = new AtomicReference<>();
        RuntimeHostAdapters expected = RuntimeHostAdapters.safeMode();
        VerifiedHostAdapterConnector connector = new VerifiedHostAdapterConnector(seen -> {
            seenEvidence.set(seen);
            return expected;
        });

        HostAdapterConnection connection = connector.connect(new HostInstanceDescriptor(
            "session-a",
            evidence
        ));

        assertSame(evidence, seenEvidence.get());
        assertTrue(seenEvidence.get().clipMask().isPresent());
        assertSame(expected, connection.adapters());
    }

    @Test
    void rejectsClipMaskEvidenceFromAnotherClassLoader() {
        assertThrows(IllegalArgumentException.class, () -> HostVerificationEvidence.withClipMask(
            slice("project", "host/Live2D_Cubism.jar", new ClassLoader() { }),
            slice("clip", "host/Live2D_Cubism.jar", new ClassLoader() { })
        ));
    }

    @Test
    void rejectsClipMaskEvidenceFromAnotherArtifact() {
        ClassLoader hostClassLoader = new ClassLoader() { };
        assertThrows(IllegalArgumentException.class, () -> HostVerificationEvidence.withClipMask(
            slice("project", "host/Live2D_Cubism.jar", hostClassLoader),
            slice("clip", "host/another.jar", hostClassLoader)
        ));
    }

    @Test
    void rejectsTopMenuEvidenceFromAnotherHostIdentity() {
        ClassLoader hostClassLoader = new ClassLoader() { };
        HostVerificationEvidence evidence = HostVerificationEvidence.projectOnly(
            slice("project", "host/Live2D_Cubism.jar", hostClassLoader)
        );

        assertThrows(IllegalArgumentException.class, () -> evidence.addingTopMenu(
            slice("top-menu", "host/Live2D_Cubism.jar", new ClassLoader() { })
        ));
        assertThrows(IllegalArgumentException.class, () -> evidence.addingTopMenu(
            slice("top-menu", "host/another.jar", hostClassLoader)
        ));
    }

    private static HostVerificationEvidence.Slice slice(final String name) {
        return slice(name, "host/" + name + ".jar", new ClassLoader() { });
    }

    private static HostVerificationEvidence.Slice slice(
        final String name,
        final String artifact,
        final ClassLoader classLoader
    ) {
        return new HostVerificationEvidence.Slice(
            Path.of("records/" + name + ".json"),
            Path.of(artifact),
            classLoader
        );
    }
}
