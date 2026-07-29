package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.mapping.verification.EditorModelVerificationManifest;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedHostAdapterConnectorTextureAtlasTest {

    @Test
    void keepsTheProviderUninstalledUntilExactTextureAtlasSelectorsAreAdmitted() throws Exception {
        final RuntimeHostAdapters adapters = RuntimeHostAdapters.safeMode();
        final VerifiedMemberResolver resolver = TestVerifiedResolvers.create(
            EditorModelVerificationManifest.CUBISM_VERSION,
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            Set.of("cubism.editor-model.read"),
            List.of(dev.turboism.mapping.verification.StaticSelector.classSelector(
                "fixture.class", getClass().getName().replace('.', '/')
            )),
            getClass().getClassLoader()
        );
        final VerifiedHostAdapterConnector connector = new VerifiedHostAdapterConnector(
            ignored -> adapters,
            ignored -> resolver,
            (verified, sessionId) -> () -> { throw new IllegalStateException(sessionId); }
        );
        final HostVerificationEvidence.Slice slice = new HostVerificationEvidence.Slice(
            Path.of("editor.json"), Path.of("host.jar"), getClass().getClassLoader()
        );
        final HostAdapterConnection connection = connector.connect(new HostInstanceDescriptor(
            "session-a",
            new HostVerificationEvidence(slice, Optional.empty(), Optional.of(slice), Optional.empty())
        ));

        assertTrue(connection.textureAtlasLayoutProvider().isEmpty());
    }
}
