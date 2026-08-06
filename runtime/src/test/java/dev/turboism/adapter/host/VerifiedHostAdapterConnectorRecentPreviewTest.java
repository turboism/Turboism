package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedHostAdapterConnectorRecentPreviewTest {

    @Test
    void forwardsProjectAndEmbeddedPanelEvidenceToTheVerifiedFactorySeam() throws Exception {
        final ClassLoader hostClassLoader = new ClassLoader() { };
        final HostVerificationEvidence evidence = evidenceWithPanel(hostClassLoader);
        final AtomicReference<HostVerificationEvidence> seenEvidence = new AtomicReference<>();
        final RuntimeHostAdapters expected = RuntimeHostAdapters.safeMode();
        final VerifiedHostAdapterConnector connector = new VerifiedHostAdapterConnector(seen -> {
            seenEvidence.set(seen);
            return expected;
        });

        final HostAdapterConnection connection = connector.connect(
            new HostInstanceDescriptor("session-recent", evidence)
        );

        assertSame(evidence, seenEvidence.get());
        assertTrue(seenEvidence.get().embeddedPanel().isPresent());
        assertSame(expected, connection.adapters());
    }

    @Test
    void rejectsEmbeddedPanelEvidenceFromAnotherHostIdentity() {
        final ClassLoader hostClassLoader = new ClassLoader() { };
        final HostVerificationEvidence evidence = HostVerificationEvidence.projectOnly(slice(
            "project", hostClassLoader
        ));
        assertThrows(IllegalArgumentException.class, () -> evidence.addingEmbeddedPanel(slice(
            "panel", new ClassLoader() { }
        )));
        assertThrows(IllegalArgumentException.class, () -> evidence.addingEmbeddedPanel(slice(
            "panel", hostClassLoader, "host/another.jar"
        )));
    }


    private static HostVerificationEvidence evidenceWithPanel(final ClassLoader hostClassLoader) {
        return HostVerificationEvidence.projectOnly(slice("project", hostClassLoader))
            .addingEmbeddedPanel(slice("panel", hostClassLoader));
    }

    private static HostVerificationEvidence.Slice slice(
        final String name,
        final ClassLoader classLoader
    ) {
        return slice(name, classLoader, "host/Live2D_Cubism.jar");
    }

    private static HostVerificationEvidence.Slice slice(
        final String name,
        final ClassLoader classLoader,
        final String artifact
    ) {
        return new HostVerificationEvidence.Slice(
            java.nio.file.Path.of("records/" + name + ".json"),
            java.nio.file.Path.of(artifact),
            classLoader
        );
    }


}
