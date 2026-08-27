package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest;
import dev.turboism.ui.appearance.FlatLafAppearanceHostProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedHostAdapterConnectorAppearanceTest {

    @Test
    void admitsAppearanceProviderFromTheExactReviewed53ProjectArtifact() throws Exception {
        VerifiedHostAdapterConnector connector = new VerifiedHostAdapterConnector(
            ignored -> RuntimeHostAdapters.safeMode(),
            slice -> { throw new AssertionError("editor resolver not expected"); },
            (resolver, session, core) -> { throw new AssertionError("editor access not expected"); },
            null,
            null,
            null,
            slice -> new FlatLafAppearanceHostProvider(
                ProjectWorkspaceVerificationManifest.RECORD_5_3_02.cubismVersion(),
                new NoOpHost()
            )
        );
        HostVerificationEvidence.Slice project = new HostVerificationEvidence.Slice(
            Path.of("project-record.json"),
            Path.of("Live2D_Cubism.jar"),
            getClass().getClassLoader()
        );
        HostInstanceDescriptor descriptor = new HostInstanceDescriptor(
            "session-a",
            HostVerificationEvidence.projectOnly(project)
        );

        HostAdapterConnection connection = connector.connect(descriptor);

        FlatLafAppearanceHostProvider provider = assertInstanceOf(
            FlatLafAppearanceHostProvider.class,
            connection.appearanceProvider()
        );
        assertEquals("5.3.02", provider.hostVersion());
    }

    @Test
    void productionPolicyMakesExact5303AppearanceAvailable() {
        final FlatLafAppearanceHostProvider provider = assertInstanceOf(
            FlatLafAppearanceHostProvider.class,
            VerifiedHostAdapterConnector.productionAppearanceProviderForVersion(
                "5.3.03",
                new NoOpHost()
            )
        );

        assertTrue(provider.isAvailable());
        assertEquals("5.3.03", provider.hostVersion());
    }

    @Test
    void productionPolicyKeepsExact5302AppearanceAvailable() {
        final FlatLafAppearanceHostProvider provider = assertInstanceOf(
            FlatLafAppearanceHostProvider.class,
            VerifiedHostAdapterConnector.productionAppearanceProviderForVersion(
                "5.3.02",
                new NoOpHost()
            )
        );

        assertTrue(provider.isAvailable());
        assertEquals("5.3.02", provider.hostVersion());
    }

    @Test
    void minimalConnectionKeepsUiAndContextMenuSurfacesAbsent() throws Exception {
        final VerifiedHostAdapterConnector connector = new VerifiedHostAdapterConnector(
            ignored -> RuntimeHostAdapters.safeMode(),
            slice -> { throw new AssertionError("editor resolver not expected"); },
            (resolver, session, core) -> { throw new AssertionError("editor access not expected"); },
            null,
            null,
            null,
            ignored -> VerifiedHostAdapterConnector.productionAppearanceProviderForVersion(
                "5.3.03",
                new NoOpHost()
            )
        );
        final HostVerificationEvidence.Slice project = new HostVerificationEvidence.Slice(
            Path.of("project-record.json"),
            Path.of("Live2D_Cubism.jar"),
            getClass().getClassLoader()
        );

        final HostAdapterConnection connection = connector.connect(new HostInstanceDescriptor(
            "session-5303",
            HostVerificationEvidence.projectOnly(project)
        ));

        assertTrue(connection.editorUiProviders(1).isEmpty());
        assertNull(connection.objectContextMenuHandler(1));
        assertNull(connection.parameterPointMenuHandler(1));
        assertTrue(connection.appearanceProvider().isAvailable());
    }

    @Test
    void nonJarHostArtifactLeavesAppearanceUnavailableWithoutFailingConnection() throws Exception {
        Path directoryHost = java.nio.file.Files.createTempDirectory("turboism-host-classes");
        HostVerificationEvidence.Slice project = new HostVerificationEvidence.Slice(
            Path.of("project-record.json"),
            directoryHost,
            getClass().getClassLoader()
        );

        var provider = VerifiedHostAdapterConnector.productionAppearanceProviderFactory().create(project);

        assertInstanceOf(
            dev.turboism.ui.appearance.UnavailableAppearanceHostProvider.class,
            provider
        );
    }

    private static final class NoOpHost implements FlatLafAppearanceHostProvider.HostOperations {
        @Override public java.util.Map<String, String> capture() { return java.util.Map.of(); }
        @Override public void replace(final java.util.Map<String, String> defaults) { }
        @Override public void restoreNative() { }
        @Override public void refresh() { }
    }
}
