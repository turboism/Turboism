package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.mapping.verification.EmbeddedPanelVerificationManifest;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiInlineLabel;
import dev.turboism.sdk.ui.resource.UiIconAvailability;
import dev.turboism.sdk.ui.resource.UiIconRef;
import dev.turboism.ui.action.RuntimeEditorUiActionRouter;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.panel.EmbeddedPanelContributionProvider;
import dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator;
import dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator;
import dev.turboism.ui.panel.VerifiedEmbeddedPanelHostOperationsTest;
import dev.turboism.ui.resource.CubismNativeIconResolver;
import dev.turboism.ui.resource.NativeIconVariant;
import dev.turboism.ui.resource.RuntimeUiResourceService;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;
import dev.turboism.ui.appearance.UnavailableAppearanceHostProvider;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedHostAdapterConnectorUiResourceTest {

    private static final UiIconRef ICON = new UiIconRef(CubismIcon.ART_MESH);
    private static final EmbeddedPanelVerificationManifest.AdmissionEvidence PANEL_ADMISSION =
        new EmbeddedPanelVerificationManifest.AdmissionEvidence(
            "5.3.02",
            1,
            "a".repeat(64),
            "adapter.editor-ui.embedded-panel",
            "b".repeat(64)
        );

    @Test
    void panelBearingConnectionPublishesTheOneOwnerSdkViewAndClosesIt() throws Exception {
        final VerifiedEmbeddedPanelHostOperationsTest.InstallHost host =
            new VerifiedEmbeddedPanelHostOperationsTest.InstallHost();
        final RuntimeUiResourceService owner = cachedOwner();
        final RuntimeHostAdapters base = RuntimeHostAdapters.safeMode();

        try (HostAdapterConnection connection = connect(
            host,
            owner,
            base,
            new RuntimeEditorUiActionRouter()
        )) {
            assertSame(owner.sdkView(), connection.adapters().uiResources());
            assertEquals(UiIconAvailability.AVAILABLE, connection.adapters().uiResources().availability(ICON));
        }

        assertEquals(UiIconAvailability.SERVICE_UNAVAILABLE, owner.sdkView().availability(ICON));
    }

    @Test
    void injectedCachedOwnerReachesTheActualEmbeddedPanelRenderer() throws Exception {
        final VerifiedHostAdapterConnectorUiResourceTestFixture fixture = fixture();
        final EmbeddedPanelContributionProvider provider = panelProvider(fixture.connection);
        Registration registration = null;
        try {
            registration = provider.apply(7, List.of(iconContribution()));

            final JPanel wrapper = (JPanel) fixture.host
                .nativeContainer(fixture.host.paletteId("icon-pane"))
                .component();
            final JComponent rendered = (JComponent) wrapper.getComponent(0);

            assertEquals(1, resolvedIconCount(rendered));
            assertEquals(UiIconAvailability.AVAILABLE, fixture.owner.sdkView().availability(ICON));
        } finally {
            fixture.connection.close();
            try {
                assertEquals(0, fixture.host.retainedStableContentRootCount());
                fixture.connection.refreshPresentation();
                assertEquals(0, fixture.host.retainedStableContentRootCount());
            } finally {
                if (registration != null) {
                    registration.close();
                }
            }
        }

        assertEquals(UiIconAvailability.SERVICE_UNAVAILABLE, fixture.owner.sdkView().availability(ICON));
    }

    @Test
    void constructionFailureAfterOwnerCreationClosesTheOwner() throws Exception {
        final VerifiedEmbeddedPanelHostOperationsTest.InstallHost host =
            new VerifiedEmbeddedPanelHostOperationsTest.InstallHost();
        final RuntimeUiResourceService owner = cachedOwner();
        final AtomicBoolean ownerCreated = new AtomicBoolean();
        final VerifiedHostAdapterConnector connector = connector(null);

        assertThrows(NullPointerException.class, () -> connector.connectForTesting(
            descriptor(),
            ignored -> panelMaterial(host),
            (panel, adapters) -> {
                ownerCreated.set(true);
                return owner;
            }
        ));

        assertTrue(ownerCreated.get());
        assertEquals(UiIconAvailability.SERVICE_UNAVAILABLE, owner.sdkView().availability(ICON));
    }

    @Test
    void edtIngressSkipsResourceFactoryAndKeepsThePanelConnectionHealthy() throws Exception {
        final VerifiedEmbeddedPanelHostOperationsTest.InstallHost host =
            new VerifiedEmbeddedPanelHostOperationsTest.InstallHost();
        final RuntimeHostAdapters expected = RuntimeHostAdapters.safeMode();
        final AtomicBoolean resourceFactoryCalled = new AtomicBoolean();
        final AtomicReference<HostAdapterConnection> connectionRef = new AtomicReference<>();
        final AtomicReference<Throwable> failureRef = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            try {
                connectionRef.set(connector(new RuntimeEditorUiActionRouter(), expected).connectForTesting(
                    descriptor(),
                    ignored -> panelMaterial(host),
                    (panel, adapters) -> {
                        resourceFactoryCalled.set(true);
                        throw new AssertionError("resource preload must be skipped on the EDT");
                    }
                ));
            } catch (Throwable failure) {
                failureRef.set(failure);
            }
        });

        if (failureRef.get() != null) {
            throw new AssertionError("EDT connection failed", failureRef.get());
        }
        final HostAdapterConnection connection = connectionRef.get();
        assertFalse(resourceFactoryCalled.get());
        assertSame(expected.uiResources(), connection.adapters().uiResources());
        assertEquals(UiIconAvailability.SERVICE_UNAVAILABLE, connection.adapters().uiResources().availability(ICON));

        Registration registration = null;
        try {
            registration = panelProvider(connection).apply(7, List.of(textContribution()));
        } finally {
            if (registration != null) {
                registration.close();
            }
            connection.close();
        }
    }

    private static VerifiedHostAdapterConnectorUiResourceTestFixture fixture() throws Exception {
        final VerifiedEmbeddedPanelHostOperationsTest.InstallHost host =
            new VerifiedEmbeddedPanelHostOperationsTest.InstallHost();
        final RuntimeUiResourceService owner = cachedOwner();
        final RuntimeHostAdapters base = RuntimeHostAdapters.safeMode();
        return new VerifiedHostAdapterConnectorUiResourceTestFixture(
            host,
            owner,
            connect(host, owner, base, new RuntimeEditorUiActionRouter())
        );
    }

    private static HostAdapterConnection connect(
        final VerifiedEmbeddedPanelHostOperationsTest.InstallHost host,
        final RuntimeUiResourceService owner,
        final RuntimeHostAdapters base,
        final RuntimeEditorUiActionRouter actionRouter
    ) throws Exception {
        return connector(actionRouter, base).connectForTesting(
            descriptor(),
            ignored -> panelMaterial(host),
            (panel, adapters) -> owner
        );
    }

    private static VerifiedHostAdapterConnector connector(
        final RuntimeEditorUiActionRouter actionRouter
    ) {
        return connector(actionRouter, RuntimeHostAdapters.safeMode());
    }

    private static VerifiedHostAdapterConnector connector(
        final RuntimeEditorUiActionRouter actionRouter,
        final RuntimeHostAdapters base
    ) {
        final VerifiedMemberResolver editorResolver = TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.read",
            Set.of("cubism.editor-model.read"),
            List.of(StaticSelector.classSelector(
                "fixture.editor-model.class",
                VerifiedHostAdapterConnectorUiResourceTest.class.getName().replace('.', '/')
            )),
            VerifiedHostAdapterConnectorUiResourceTest.class.getClassLoader()
        );
        return new VerifiedHostAdapterConnector(
            ignored -> base,
            ignored -> editorResolver,
            (resolver, sessionId, coreBackend) -> UnavailableCubismModelAccess.INSTANCE,
            null,
            null,
            null,
            new EditorUiPluginResourceRegistry(),
            actionRouter,
            new RuntimeEmbeddedPanelActivationCoordinator(),
            null,
            new RuntimeDockMaintenanceCoordinator(),
            ignored -> new UnavailableAppearanceHostProvider(),
            ignored -> editorResolver,
            ignored -> null,
            () -> Locale.ENGLISH
        );
    }

    private static HostInstanceDescriptor descriptor() {
        final ClassLoader loader = VerifiedHostAdapterConnectorUiResourceTest.class.getClassLoader();
        final Path artifact = Path.of("connector-host.jar");
        final HostVerificationEvidence.Slice project = new HostVerificationEvidence.Slice(
            Path.of("project.json"), artifact, loader
        );
        final HostVerificationEvidence.Slice editor = new HostVerificationEvidence.Slice(
            Path.of("editor.json"), artifact, loader
        );
        final HostVerificationEvidence.Slice panel = new HostVerificationEvidence.Slice(
            Path.of("panel.json"), artifact, loader
        );
        return new HostInstanceDescriptor(
            "connector-resource-session",
            HostVerificationEvidence.withEditorModel(project, editor).addingEmbeddedPanel(panel)
        );
    }

    private static VerifiedHostAdapterConnector.PanelMaterial panelMaterial(
        final VerifiedEmbeddedPanelHostOperationsTest.InstallHost host
    ) {
        return new VerifiedHostAdapterConnector.PanelMaterial(host.resolver(), PANEL_ADMISSION);
    }

    private static EmbeddedPanelContributionProvider panelProvider(
        final HostAdapterConnection connection
    ) {
        return connection.editorUiProviders(7).stream()
            .filter(EmbeddedPanelContributionProvider.class::isInstance)
            .map(EmbeddedPanelContributionProvider.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("panel provider was not composed"));
    }

    private static EditorUiContribution<EmbeddedPanelContribution> iconContribution() {
        return contribution(
            "icon-pane",
            PanelView.toggle(
                "icon-entry",
                UiInlineLabel.icon(ICON, "图形网格"),
                false,
                "history.icon"
            )
        );
    }

    private static EditorUiContribution<EmbeddedPanelContribution> textContribution() {
        return contribution("text-pane", new PanelView.Text("text fallback"));
    }

    private static EditorUiContribution<EmbeddedPanelContribution> contribution(
        final String id,
        final PanelView content
    ) {
        return new EditorUiContribution<>(
            new EditorUiContributionIdentity("turboism.core", EditorUiFamily.PANEL, id),
            0,
            new EmbeddedPanelContribution(id, "Panel " + id, "window", 0, content, false)
        );
    }

    private static RuntimeUiResourceService cachedOwner() throws Exception {
        final NativeIconVariant variant = new NativeIconVariant(
            CubismIcon.ART_MESH,
            NativeIconVariant.Theme.LIGHT,
            RuntimeUiResourceService.DEFAULT_SCALE_PERCENT,
            false
        );
        final Constructor<CubismNativeIconResolver> constructor =
            CubismNativeIconResolver.class.getDeclaredConstructor(Map.class, UiIconAvailability.class);
        constructor.setAccessible(true);
        final CubismNativeIconResolver resolver = constructor.newInstance(
            Map.of(variant, new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)),
            UiIconAvailability.RESOURCE_UNAVAILABLE
        );
        return RuntimeUiResourceService.connected(
            resolver,
            NativeIconVariant.Theme.LIGHT,
            RuntimeUiResourceService.DEFAULT_SCALE_PERCENT
        );
    }

    private static int resolvedIconCount(final JComponent component) throws Exception {
        final Method method = component.getClass().getDeclaredMethod("resolvedIconCount");
        method.setAccessible(true);
        return ((Number) method.invoke(component)).intValue();
    }

    private record VerifiedHostAdapterConnectorUiResourceTestFixture(
        VerifiedEmbeddedPanelHostOperationsTest.InstallHost host,
        RuntimeUiResourceService owner,
        HostAdapterConnection connection
    ) {
    }
}
