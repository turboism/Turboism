package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.mapping.verification.EditorModelVerificationManifest;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class VerifiedHostAdapterConnectorEditorModelTest {

    @Test
    void createsOneConnectionOwnedEditorModelAccessFromTheOptionalVerifiedSlice() throws Exception {
        RuntimeHostAdapters adapters = RuntimeHostAdapters.safeMode();
        VerifiedMemberResolver resolver = dev.turboism.mapping.verification.TestVerifiedResolvers.create(
            EditorModelVerificationManifest.CUBISM_VERSION,
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            EditorModelVerificationManifest.CAPABILITY_IDS,
            java.util.List.of(dev.turboism.mapping.verification.StaticSelector.classSelector(
                "fixture.class", getClass().getName().replace('.', '/')
            )),
            getClass().getClassLoader()
        );
        AtomicReference<HostVerificationEvidence.Slice> observed = new AtomicReference<>();
        VerifiedHostAdapterConnector connector = new VerifiedHostAdapterConnector(
            ignored -> adapters,
            slice -> {
                observed.set(slice);
                return resolver;
            },
            (verified, sessionId) -> () -> {
                throw new IllegalStateException(sessionId);
            }
        );
        HostVerificationEvidence.Slice project = slice("project");
        HostVerificationEvidence.Slice editor = slice("editor");
        HostInstanceDescriptor descriptor = new HostInstanceDescriptor(
            "session-a",
            new HostVerificationEvidence(
                project,
                Optional.empty(),
                Optional.of(editor),
                Optional.empty()
            )
        );

        HostAdapterConnection connection = connector.connect(descriptor);

        assertSame(adapters, connection.adapters());
        assertSame(editor, observed.get());
        assertEquals("session-a", org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> connection.modelAccess().active()
        ).getMessage());
    }

    @Test
    void leavesTextureAtlasProviderUnavailableUntilExact52SelectorsAreAdmitted() throws Exception {
        final RuntimeHostAdapters adapters = RuntimeHostAdapters.safeMode();
        final VerifiedMemberResolver resolver =
            dev.turboism.mapping.verification.TestVerifiedResolvers.create(
                "5.2.0",
                EditorModelVerificationManifest.ADAPTER_SLICE_ID,
                java.util.Set.of("cubism.editor-model.read"),
                java.util.List.of(dev.turboism.mapping.verification.StaticSelector.classSelector(
                    "fixture.class", getClass().getName().replace('.', '/')
                )),
                getClass().getClassLoader()
            );
        final VerifiedHostAdapterConnector connector = new VerifiedHostAdapterConnector(
            ignored -> adapters,
            ignored -> resolver,
            (verified, sessionId) -> () -> { throw new IllegalStateException(sessionId); }
        );
        final HostVerificationEvidence.Slice project = slice("project");
        final HostVerificationEvidence.Slice editor = slice("editor");

        final HostAdapterConnection connection = connector.connect(new HostInstanceDescriptor(
            "session-52",
            new HostVerificationEvidence(
                project,
                Optional.empty(),
                Optional.of(editor),
                Optional.empty()
            )
        ));

        assertEquals(Optional.empty(), connection.textureAtlasLayoutProvider());
    }

    private HostVerificationEvidence.Slice slice(final String name) {
        return new HostVerificationEvidence.Slice(
            Path.of(name + ".json"),
            Path.of("host.jar"),
            getClass().getClassLoader()
        );
    }
}
