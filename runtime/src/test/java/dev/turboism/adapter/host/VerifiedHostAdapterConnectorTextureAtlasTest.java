package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasDataModelCapture;
import dev.turboism.adapter.cubism.textureatlas.VerifiedCubism520TextureAtlasLayoutProvider;
import dev.turboism.adapter.cubism.textureatlas.VerifiedCubism520TextureAtlasSelectorContract;
import dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5302TextureAtlasLayoutProvider;
import dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5302TextureAtlasSelectorContract;
import dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5303TextureAtlasLayoutProvider;
import dev.turboism.adapter.cubism.textureatlas.VerifiedCubism5303TextureAtlasSelectorContract;
import dev.turboism.mapping.verification.EditorModelVerificationManifest;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedHostAdapterConnectorTextureAtlasTest {

    @Test
    void keepsTheProviderUninstalledUntilExactTextureAtlasSelectorsAreAdmitted() throws Exception {
        final RuntimeHostAdapters adapters = RuntimeHostAdapters.safeMode();
        final VerifiedMemberResolver resolver = TestVerifiedResolvers.create(
            EditorModelVerificationManifest.RECORD_5_3_02.cubismVersion(),
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
            (verified, sessionId, core) -> () -> { throw new IllegalStateException(sessionId); }
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

    @Test
    void retainsTheAtlasCaptureWhenNoIndependentUiSliceIsPresent() throws Exception {
        final RuntimeHostAdapters adapters = RuntimeHostAdapters.safeMode();
        final String owner = getClass().getName().replace('.', '/');
        final List<StaticSelector> selectors = VerifiedCubism5303TextureAtlasSelectorContract.REQUIRED_ALIASES
            .stream()
            .map(alias -> StaticSelector.classSelector(alias, owner))
            .toList();
        final VerifiedMemberResolver resolver = TestVerifiedResolvers.create(
            "5.3.03",
            VerifiedCubism5303TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            Set.of(VerifiedCubism5303TextureAtlasSelectorContract.CAPABILITY_ID),
            selectors,
            getClass().getClassLoader()
        );
        final VerifiedHostAdapterConnector connector = new VerifiedHostAdapterConnector(
            ignored -> adapters,
            ignored -> resolver,
            (verified, sessionId, core) -> () -> { throw new IllegalStateException(sessionId); }
        );
        final HostVerificationEvidence.Slice slice = new HostVerificationEvidence.Slice(
            Path.of("editor.json"), Path.of("host.jar"), getClass().getClassLoader()
        );

        final HostAdapterConnection connection = connector.connect(new HostInstanceDescriptor(
            "session-atlas-only",
            new HostVerificationEvidence(slice, Optional.empty(), Optional.of(slice), Optional.empty())
        ));

        assertSame(adapters, connection.adapters());
        assertInstanceOf(
            VerifiedCubism5303TextureAtlasLayoutProvider.class,
            connection.textureAtlasLayoutProvider().orElseThrow()
        );
        assertTrue(connection.textureAtlasDataModelCapture().current().isEmpty());
    }

    @Test
    void selectsOnlyTheExactVersionProviderAndFailsClosedOtherwise() {
        final List<StaticSelector> selectors = VerifiedCubism5302TextureAtlasSelectorContract.REQUIRED_ALIASES
            .stream()
            .map(alias -> StaticSelector.classSelector(alias, getClass().getName().replace('.', '/')))
            .toList();
        final List<StaticSelector> selectors5303 = VerifiedCubism5303TextureAtlasSelectorContract.REQUIRED_ALIASES
            .stream()
            .map(alias -> StaticSelector.classSelector(alias, getClass().getName().replace('.', '/')))
            .toList();
        final List<StaticSelector> selectors520 = VerifiedCubism520TextureAtlasSelectorContract.REQUIRED_ALIASES
            .stream()
            .map(alias -> StaticSelector.classSelector(alias, getClass().getName().replace('.', '/')))
            .toList();
        final Set<String> capability = Set.of(
            VerifiedCubism5302TextureAtlasSelectorContract.CAPABILITY_ID
        );

        assertInstanceOf(
            VerifiedCubism5303TextureAtlasLayoutProvider.class,
            VerifiedHostAdapterConnector.textureAtlasProvider(
                TestVerifiedResolvers.create(
                    "5.3.03",
                    VerifiedCubism5303TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                    capability,
                    selectors5303,
                    getClass().getClassLoader()
                ),
                "session-5303",
                new TextureAtlasDataModelCapture()
            )
        );
        assertInstanceOf(
            VerifiedCubism5302TextureAtlasLayoutProvider.class,
            VerifiedHostAdapterConnector.textureAtlasProvider(
                TestVerifiedResolvers.create(
                    "5.3.02",
                    VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                    capability,
                    selectors,
                    getClass().getClassLoader()
                ),
                "session-5302",
                new TextureAtlasDataModelCapture()
            )
        );
        assertInstanceOf(
            VerifiedCubism520TextureAtlasLayoutProvider.class,
            VerifiedHostAdapterConnector.textureAtlasProvider(
                TestVerifiedResolvers.create(
                    "5.2.03",
                    VerifiedCubism520TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                    capability,
                    selectors520,
                    getClass().getClassLoader()
                ),
                "session-520",
                new TextureAtlasDataModelCapture()
            )
        );
        assertNull(VerifiedHostAdapterConnector.textureAtlasProvider(
            TestVerifiedResolvers.create(
                "5.4.0",
                VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                capability,
                selectors,
                getClass().getClassLoader()
            ),
            "session-unsupported",
            new TextureAtlasDataModelCapture()
        ));
        assertNull(VerifiedHostAdapterConnector.textureAtlasProvider(
            TestVerifiedResolvers.create(
                "5.3.03",
                VerifiedCubism5303TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                capability,
                selectors5303.subList(1, selectors5303.size()),
                getClass().getClassLoader()
            ),
            "session-5303-incomplete",
            new TextureAtlasDataModelCapture()
        ));
        assertNull(VerifiedHostAdapterConnector.textureAtlasProvider(
            TestVerifiedResolvers.create(
                "5.3.02",
                VerifiedCubism5302TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                capability,
                selectors.subList(1, selectors.size()),
                getClass().getClassLoader()
            ),
            "session-incomplete",
            new TextureAtlasDataModelCapture()
        ));
        assertNull(VerifiedHostAdapterConnector.textureAtlasProvider(
            TestVerifiedResolvers.create(
                "5.2.03",
                VerifiedCubism520TextureAtlasSelectorContract.ADAPTER_SLICE_ID,
                capability,
                selectors520.subList(1, selectors520.size()),
                getClass().getClassLoader()
            ),
            "session-520-incomplete",
            new TextureAtlasDataModelCapture()
        ));
    }
}
