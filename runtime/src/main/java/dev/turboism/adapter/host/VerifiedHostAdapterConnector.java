package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.VerifiedRuntimeHostAdaptersFactory;
import dev.turboism.adapter.cubism.editor.EditorBackedCubismModelAccess;
import dev.turboism.adapter.cubism.core.CoreVersionExpectation;
import dev.turboism.adapter.cubism.core.RuntimeCoreModelBackend;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasDataModelCapture;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutProvider;
import dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasLayoutProvider;
import dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasSelectorContract;
import dev.turboism.mapping.verification.BoundingBoxOverlayButtonVerificationManifest;
import dev.turboism.mapping.verification.EmbeddedPanelVerificationManifest;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.MainToolbarVerificationManifest;
import dev.turboism.mapping.verification.TopMenuVerificationManifest;
import dev.turboism.mapping.verification.VerifiedBoundingBoxOverlayButtonResolverFactory;
import dev.turboism.mapping.verification.VerifiedEditorModelResolverFactory;
import dev.turboism.mapping.verification.VerifiedEmbeddedPanelResolverFactory;
import dev.turboism.mapping.verification.VerifiedMainToolbarResolverFactory;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.VerifiedTopMenuResolverFactory;
import dev.turboism.mapping.verification.VerifiedWorkspaceControlResolverFactory;
import dev.turboism.mapping.verification.VerifiedCorePublicApiResolverFactory;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.menu.TopMenuContributionProvider;
import dev.turboism.ui.menu.VerifiedTopMenuHostOperations;
import dev.turboism.ui.overlay.BoundingBoxOverlayButtonContributionProvider;
import dev.turboism.ui.overlay.VerifiedBoundingBoxOverlayButtonHostOperations;
import dev.turboism.ui.panel.EmbeddedPanelContributionProvider;
import dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator;
import dev.turboism.ui.panel.VerifiedEmbeddedPanelHostOperations;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;
import dev.turboism.ui.toolbar.MainToolbarContributionProvider;
import dev.turboism.ui.toolbar.VerifiedMainToolbarHostOperations;
import dev.turboism.ui.toolbar.HorizontalToolbarContributionProvider;
import dev.turboism.ui.toolbar.VerifiedHorizontalToolbarHostOperations;
import dev.turboism.ui.toolbar.VerifiedVerticalToolbarHostOperations;
import dev.turboism.ui.toolbar.VerticalToolbarContributionProvider;
import dev.turboism.ui.appearance.AppearanceHostProvider;
import dev.turboism.ui.appearance.FlatLafAppearanceHostProvider;
import dev.turboism.ui.appearance.SwingFlatLafHostOperations;
import dev.turboism.ui.resource.CubismNativeIconResolver;
import dev.turboism.ui.resource.RuntimeUiResourceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import javax.swing.SwingUtilities;

/** Production connector pinned to the reviewed project/workspace verification trust root. */
final class VerifiedHostAdapterConnector implements HostAdapterConnector {

    static final String PANEL_ICON_ANCHOR_ALIAS =
        "cubism.ui-panel.app-controller.class";

    private final VerifiedAdapterFactory factory;
    private final EditorResolverFactory editorResolverFactory;
    private final EditorAccessFactory editorAccessFactory;
    private final MainToolbarResolverFactory mainToolbarResolverFactory;
    private final EmbeddedPanelResolverFactory embeddedPanelResolverFactory;
    private final TopMenuResolverFactory topMenuResolverFactory;
    private final BoundingBoxOverlayResolverFactory boundingBoxOverlayResolverFactory;
    private final EditorUiPluginResourceRegistry editorUiPluginResources;
    private final dev.turboism.ui.action.RuntimeEditorUiActionRouter editorUiActionRouter;
    private final RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation;
    private final dev.turboism.ui.panel.PanelTabMenuCoordinator panelTabMenus;
    private final dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance;
    private final AppearanceProviderFactory appearanceProviderFactory;
    private final WorkspaceResolverFactory workspaceResolverFactory;
    private final CoreBackendFactory coreBackendFactory;
    private final java.util.function.Supplier<Locale> effectiveLocale;

    VerifiedHostAdapterConnector() {
        this(
            new VerifiedRuntimeHostAdaptersFactory()::create,
            slice -> new VerifiedEditorModelResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            (resolver, sessionId, coreBackend) -> new EditorBackedCubismModelAccess(
                resolver, sessionId, coreBackend == null ? null : coreBackend.evaluatedJoin()
            ),
            slice -> new VerifiedMainToolbarResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            slice -> new VerifiedEmbeddedPanelResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            null,
            null,
            null,
            null,
            null,
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator(),
            VerifiedHostAdapterConnector::productionAppearanceProvider,
            slice -> new VerifiedWorkspaceControlResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            VerifiedHostAdapterConnector::coreMaterial
        );
    }

    VerifiedHostAdapterConnector(final VerifiedAdapterFactory factory) {
        this(
            factory,
            slice -> new VerifiedEditorModelResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            (resolver, sessionId, coreBackend) -> new EditorBackedCubismModelAccess(
                resolver, sessionId, coreBackend == null ? null : coreBackend.evaluatedJoin()
            ),
            slice -> new VerifiedMainToolbarResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            slice -> new VerifiedEmbeddedPanelResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            null,
            null,
            null,
            null,
            null,
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator(),
            ignored -> unavailableAppearanceProvider(),
            slice -> new VerifiedWorkspaceControlResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            VerifiedHostAdapterConnector::coreMaterial
        );
    }

    VerifiedHostAdapterConnector(
        final VerifiedAdapterFactory factory,
        final EditorResolverFactory editorResolverFactory,
        final EditorAccessFactory editorAccessFactory
    ) {
        this(
            factory, editorResolverFactory, editorAccessFactory,
            null, null, null, null, null, null, null,
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator(),
            ignored -> unavailableAppearanceProvider(),
            slice -> new VerifiedWorkspaceControlResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            VerifiedHostAdapterConnector::coreMaterial
        );
    }

    VerifiedHostAdapterConnector(
        final VerifiedAdapterFactory factory,
        final EditorResolverFactory editorResolverFactory,
        final EditorAccessFactory editorAccessFactory,
        final CoreBackendFactory coreBackendFactory
    ) {
        this(
            factory, editorResolverFactory, editorAccessFactory,
            null, null, null, null, null, null, null,
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator(),
            ignored -> unavailableAppearanceProvider(),
            slice -> new VerifiedWorkspaceControlResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            coreBackendFactory
        );
    }

    VerifiedHostAdapterConnector(
        final VerifiedAdapterFactory factory,
        final EditorResolverFactory editorResolverFactory,
        final EditorAccessFactory editorAccessFactory,
        final MainToolbarResolverFactory mainToolbarResolverFactory,
        final EditorUiPluginResourceRegistry editorUiPluginResources,
        final dev.turboism.ui.action.RuntimeEditorUiActionRouter editorUiActionRouter,
        final AppearanceProviderFactory appearanceProviderFactory
    ) {
        this(
            factory, editorResolverFactory, editorAccessFactory, mainToolbarResolverFactory,
            null, null, editorUiPluginResources, editorUiActionRouter, null, null,
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator(), appearanceProviderFactory,
            slice -> new VerifiedWorkspaceControlResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            VerifiedHostAdapterConnector::coreMaterial
        );
    }

    VerifiedHostAdapterConnector(
        final VerifiedAdapterFactory factory,
        final EditorResolverFactory editorResolverFactory,
        final EditorAccessFactory editorAccessFactory,
        final MainToolbarResolverFactory mainToolbarResolverFactory,
        final EditorUiPluginResourceRegistry editorUiPluginResources,
        final dev.turboism.ui.action.RuntimeEditorUiActionRouter editorUiActionRouter
    ) {
        this(
            factory, editorResolverFactory, editorAccessFactory, mainToolbarResolverFactory,
            null, null, editorUiPluginResources, editorUiActionRouter, null, null,
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator(),
            ignored -> unavailableAppearanceProvider(),
            slice -> new VerifiedWorkspaceControlResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            VerifiedHostAdapterConnector::coreMaterial
        );
    }

    VerifiedHostAdapterConnector(
        final VerifiedAdapterFactory factory,
        final EditorResolverFactory editorResolverFactory,
        final EditorAccessFactory editorAccessFactory,
        final MainToolbarResolverFactory mainToolbarResolverFactory,
        final EmbeddedPanelResolverFactory embeddedPanelResolverFactory,
        final EditorUiPluginResourceRegistry editorUiPluginResources,
        final dev.turboism.ui.action.RuntimeEditorUiActionRouter editorUiActionRouter,
        final RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation
    ) {
        this(
            factory, editorResolverFactory, editorAccessFactory, mainToolbarResolverFactory,
            embeddedPanelResolverFactory, null, editorUiPluginResources, editorUiActionRouter,
            embeddedPanelActivation,
            slice -> new VerifiedTopMenuResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator(),
            ignored -> unavailableAppearanceProvider(),
            slice -> new VerifiedWorkspaceControlResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            VerifiedHostAdapterConnector::coreMaterial
        );
    }

    VerifiedHostAdapterConnector(
        final VerifiedAdapterFactory factory,
        final EditorResolverFactory editorResolverFactory,
        final EditorAccessFactory editorAccessFactory,
        final MainToolbarResolverFactory mainToolbarResolverFactory,
        final EmbeddedPanelResolverFactory embeddedPanelResolverFactory,
        final EditorUiPluginResourceRegistry editorUiPluginResources,
        final dev.turboism.ui.action.RuntimeEditorUiActionRouter editorUiActionRouter,
        final RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation,
        final TopMenuResolverFactory topMenuResolverFactory
    ) {
        this(
            factory, editorResolverFactory, editorAccessFactory, mainToolbarResolverFactory,
            embeddedPanelResolverFactory, null, editorUiPluginResources, editorUiActionRouter,
            embeddedPanelActivation, topMenuResolverFactory,
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator(),
            ignored -> unavailableAppearanceProvider(),
            slice -> new VerifiedWorkspaceControlResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            VerifiedHostAdapterConnector::coreMaterial
        );
    }

    VerifiedHostAdapterConnector(
        final VerifiedAdapterFactory factory,
        final EditorResolverFactory editorResolverFactory,
        final EditorAccessFactory editorAccessFactory,
        final MainToolbarResolverFactory mainToolbarResolverFactory,
        final EmbeddedPanelResolverFactory embeddedPanelResolverFactory,
        final EditorUiPluginResourceRegistry editorUiPluginResources,
        final dev.turboism.ui.action.RuntimeEditorUiActionRouter editorUiActionRouter,
        final RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation,
        final TopMenuResolverFactory topMenuResolverFactory,
        final dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance
    ) {
        this(
            factory, editorResolverFactory, editorAccessFactory, mainToolbarResolverFactory,
            embeddedPanelResolverFactory, null, editorUiPluginResources, editorUiActionRouter,
            embeddedPanelActivation, topMenuResolverFactory, dockMaintenance,
            ignored -> unavailableAppearanceProvider(),
            slice -> new VerifiedWorkspaceControlResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            VerifiedHostAdapterConnector::coreMaterial
        );
    }

    VerifiedHostAdapterConnector(
        final VerifiedAdapterFactory factory,
        final EditorResolverFactory editorResolverFactory,
        final EditorAccessFactory editorAccessFactory,
        final MainToolbarResolverFactory mainToolbarResolverFactory,
        final EmbeddedPanelResolverFactory embeddedPanelResolverFactory,
        final BoundingBoxOverlayResolverFactory boundingBoxOverlayResolverFactory,
        final EditorUiPluginResourceRegistry editorUiPluginResources,
        final dev.turboism.ui.action.RuntimeEditorUiActionRouter editorUiActionRouter,
        final RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation,
        final TopMenuResolverFactory topMenuResolverFactory,
        final dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance,
        final AppearanceProviderFactory appearanceProviderFactory
    ) {
        this(
            factory, editorResolverFactory, editorAccessFactory, mainToolbarResolverFactory,
            embeddedPanelResolverFactory, boundingBoxOverlayResolverFactory, editorUiPluginResources,
            editorUiActionRouter, embeddedPanelActivation, topMenuResolverFactory, dockMaintenance,
            appearanceProviderFactory,
            slice -> new VerifiedWorkspaceControlResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            VerifiedHostAdapterConnector::coreMaterial
        );
    }

    VerifiedHostAdapterConnector(
        final VerifiedAdapterFactory factory,
        final EditorResolverFactory editorResolverFactory,
        final EditorAccessFactory editorAccessFactory,
        final MainToolbarResolverFactory mainToolbarResolverFactory,
        final EmbeddedPanelResolverFactory embeddedPanelResolverFactory,
        final BoundingBoxOverlayResolverFactory boundingBoxOverlayResolverFactory,
        final EditorUiPluginResourceRegistry editorUiPluginResources,
        final dev.turboism.ui.action.RuntimeEditorUiActionRouter editorUiActionRouter,
        final RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation,
        final TopMenuResolverFactory topMenuResolverFactory,
        final dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance,
        final AppearanceProviderFactory appearanceProviderFactory,
        final WorkspaceResolverFactory workspaceResolverFactory,
        final CoreBackendFactory coreBackendFactory
    ) {
        this(
            factory, editorResolverFactory, editorAccessFactory, mainToolbarResolverFactory,
            embeddedPanelResolverFactory, boundingBoxOverlayResolverFactory, editorUiPluginResources,
            editorUiActionRouter, embeddedPanelActivation, topMenuResolverFactory, dockMaintenance,
            appearanceProviderFactory, workspaceResolverFactory, coreBackendFactory,
            dev.turboism.i18n.CubismHostLocale::resolve
        );
    }

    VerifiedHostAdapterConnector(
        final VerifiedAdapterFactory factory,
        final EditorResolverFactory editorResolverFactory,
        final EditorAccessFactory editorAccessFactory,
        final MainToolbarResolverFactory mainToolbarResolverFactory,
        final EmbeddedPanelResolverFactory embeddedPanelResolverFactory,
        final BoundingBoxOverlayResolverFactory boundingBoxOverlayResolverFactory,
        final EditorUiPluginResourceRegistry editorUiPluginResources,
        final dev.turboism.ui.action.RuntimeEditorUiActionRouter editorUiActionRouter,
        final RuntimeEmbeddedPanelActivationCoordinator embeddedPanelActivation,
        final TopMenuResolverFactory topMenuResolverFactory,
        final dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance,
        final AppearanceProviderFactory appearanceProviderFactory,
        final WorkspaceResolverFactory workspaceResolverFactory,
        final CoreBackendFactory coreBackendFactory,
        final java.util.function.Supplier<Locale> effectiveLocale
    ) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.editorResolverFactory = Objects.requireNonNull(editorResolverFactory, "editorResolverFactory");
        this.editorAccessFactory = Objects.requireNonNull(editorAccessFactory, "editorAccessFactory");
        this.mainToolbarResolverFactory = mainToolbarResolverFactory;
        this.embeddedPanelResolverFactory = embeddedPanelResolverFactory;
        this.boundingBoxOverlayResolverFactory = boundingBoxOverlayResolverFactory;
        this.editorUiPluginResources = editorUiPluginResources;
        this.editorUiActionRouter = editorUiActionRouter;
        this.embeddedPanelActivation = embeddedPanelActivation;
        this.topMenuResolverFactory = topMenuResolverFactory;
        this.panelTabMenus = new dev.turboism.ui.panel.PanelTabMenuCoordinator();
        this.dockMaintenance = Objects.requireNonNull(dockMaintenance, "dockMaintenance");
        this.appearanceProviderFactory = Objects.requireNonNull(appearanceProviderFactory, "appearanceProviderFactory");
        this.workspaceResolverFactory = Objects.requireNonNull(workspaceResolverFactory, "workspaceResolverFactory");
        this.coreBackendFactory = Objects.requireNonNull(coreBackendFactory, "coreBackendFactory");
        this.effectiveLocale = Objects.requireNonNull(effectiveLocale, "effectiveLocale");
    }

    @Override
    public HostAdapterConnection connect(final HostInstanceDescriptor descriptor) throws Exception {
        return connectWithWiring(
            descriptor,
            this::panelMaterial,
            VerifiedHostAdapterConnector::productionUiResourceOwner
        );
    }

    /**
     * Package-private trusted composition seam for focused Runtime tests. Production callers stay
     * pinned to {@link #productionUiResourceOwner(PanelMaterial, RuntimeHostAdapters)} above.
     */
    HostAdapterConnection connectForTesting(
        final HostInstanceDescriptor descriptor,
        final PanelMaterialFactory panelMaterialFactory,
        final UiResourceOwnerFactory resourceOwnerFactory
    ) throws Exception {
        return connectWithWiring(
            descriptor,
            Objects.requireNonNull(panelMaterialFactory, "panelMaterialFactory"),
            Objects.requireNonNull(resourceOwnerFactory, "resourceOwnerFactory")
        );
    }

    private HostAdapterConnection connectWithWiring(
        final HostInstanceDescriptor descriptor,
        final PanelMaterialFactory panelMaterialFactory,
        final UiResourceOwnerFactory resourceOwnerFactory
    ) throws Exception {
        RuntimeCoreModelBackend ownedCore = null;
        try {
            Objects.requireNonNull(descriptor, "descriptor");
            final HostVerificationEvidence evidence = descriptor.verificationEvidence();
            final RuntimeHostAdapters adapters = factory.create(evidence);
            final AppearanceHostProvider appearanceProvider = appearanceProviderFactory.create(evidence.projectWorkspace());
            final dev.turboism.ui.workspace.WorkspaceHostProvider workspace =
                evidence.workspaceControl().isPresent()
                    ? dev.turboism.ui.workspace.WorkspaceHostProviderFactory.create(
                        workspaceResolverFactory.create(evidence.workspaceControl().orElseThrow())
                    )
                    : null;
            if (evidence.editorModel().isEmpty()) {
                ownedCore = coreBackendFactory.create(evidence);
                final RuntimeCoreModelBackend core = ownedCore;
                final HostAdapterConnection base = HostAdapterConnection.of(
                    adapters,
                    UnavailableCubismModelAccess.INSTANCE,
                    null,
                    appearanceProvider,
                    core == null ? DynamicCoreRuntimeInfo.unavailableRuntime() : core.coreRuntimeInfo(),
                    core
                );
                if (workspace == null) {
                    return base;
                }
                // Workspace control is the one independent slice: it composes on its own even
                // without a verified editor-model slice; UI slices still fail closed.
                return new HostAdapterConnection() {
                    @Override
                    public RuntimeHostAdapters adapters() {
                        return base.adapters();
                    }

                    @Override
                    public dev.turboism.ui.workspace.WorkspaceHostProvider workspaceProvider() {
                        return workspace;
                    }

                    @Override
                    public AppearanceHostProvider appearanceProvider() {
                        return base.appearanceProvider();
                    }

                    @Override
                    public dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntimeInfo() {
                        return base.coreRuntimeInfo();
                    }

                    @Override
                    public void close() throws Exception {
                        base.close();
                    }
                };
            }
            ownedCore = coreBackendFactory.create(evidence);
            final RuntimeCoreModelBackend core = ownedCore;
            final VerifiedMemberResolver resolver = editorResolverFactory.create(
                evidence.editorModel().orElseThrow()
            );
            // Editor CModel is not Core CubismModel. Keep the Core source empty until a
            // separately verified acquisition path supplies an actual Core model.
            final dev.turboism.mapping.verification.EditorModelAdmissionEvidence editorAdmission =
                editorAdmission(evidence.editorModel().orElseThrow(), resolver);
            final CubismModelAccess modelAccess = editorAccessFactory.create(
                resolver,
                descriptor.sessionId(),
                core
            );
            final TextureAtlasDataModelCapture textureAtlasCapture =
                new TextureAtlasDataModelCapture();
            final TextureAtlasLayoutProvider textureAtlasProvider = textureAtlasProvider(
                resolver,
                descriptor.sessionId(),
                textureAtlasCapture
            );
            final ToolbarMaterial toolbar = toolbarMaterial(evidence);
            final PanelMaterial panel = panelMaterialFactory.create(evidence);
            final TopMenuMaterial topMenu = topMenuMaterial(evidence);
            final OverlayMaterial overlay = optionalOverlayMaterial(evidence);
            if (toolbar == null
                && panel == null
                && topMenu == null
                && overlay == null
                && workspace == null
                && textureAtlasProvider == null
                && (editorUiPluginResources == null || editorUiActionRouter == null)) {
                return HostAdapterConnection.of(
                    adapters,
                    modelAccess,
                    resolver,
                    appearanceProvider,
                    core == null ? DynamicCoreRuntimeInfo.unavailableRuntime() : core.coreRuntimeInfo(),
                    core
                );
            }
            return connection(
                adapters,
                modelAccess,
                resolver,
                editorAdmission,
                toolbar,
                panel,
                topMenu,
                overlay,
                appearanceProvider,
                core,
                workspace,
                textureAtlasCapture,
                textureAtlasProvider,
                resourceOwnerFactory
            );
        } catch (Throwable failure) {
            closeAfterFailure(failure, ownedCore);
            rethrowConnectionFailure(failure);
            throw new AssertionError("unreachable");
        }
    }

    static TextureAtlasLayoutProvider textureAtlasProvider(
        final VerifiedMemberResolver resolver,
        final String sessionId,
        final TextureAtlasDataModelCapture capture
    ) {
        final VerifiedTextureAtlasSelectorContract.Profile profile =
            VerifiedTextureAtlasSelectorContract.profileFor(resolver.cubismVersion()).orElse(null);
        if (profile == null) {
            return null;
        }
        return resolver.authorizesFeature(
            VerifiedTextureAtlasSelectorContract.ADAPTER_SLICE_ID,
            VerifiedTextureAtlasSelectorContract.CAPABILITY_ID,
            profile.requiredAliases()
        ) ? new VerifiedTextureAtlasLayoutProvider(resolver, sessionId, capture, profile) : null;
    }

    private static dev.turboism.mapping.verification.EditorModelAdmissionEvidence editorAdmission(
        final HostVerificationEvidence.Slice slice,
        final VerifiedMemberResolver resolver
    ) {
        try {
            return dev.turboism.mapping.verification.EditorModelAdmissionEvidence.forArtifact(
                HostArtifactDigest.from(slice.verifiedArtifact())
            );
        } catch (java.io.IOException missingTestArtifact) {
            return dev.turboism.mapping.verification.EditorModelAdmissionEvidence.forResolver(resolver);
        }
    }

    private static RuntimeCoreModelBackend coreMaterial(
        final HostVerificationEvidence evidence
    ) throws Exception {
        if (evidence.coreRuntime().isEmpty()) return null;
        final HostVerificationEvidence.Slice slice = evidence.coreRuntime().orElseThrow();
        final VerifiedMemberResolver resolver = new VerifiedCorePublicApiResolverFactory().create(
            slice.reviewedRecord(),
            slice.verifiedArtifact(),
            slice.hostClassLoader()
        );
        final var admission = RuntimeCoreModelBackend.admit(
            resolver,
            CoreVersionExpectation.reviewedProfile(resolver.cubismVersion())
        );
        if (!admission.isSuccess()) {
            throw new IllegalArgumentException(
                "Verified Cubism Core runtime admission failed safely."
            );
        }
        return admission.value().orElseThrow();
    }

    private OverlayMaterial optionalOverlayMaterial(final HostVerificationEvidence evidence) {
        try {
            return overlayMaterial(evidence);
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static void diag(final String message) {
        dev.turboism.runtime.log.RuntimeDiagnostics.debug("toolbar", message);
    }

    private ToolbarMaterial toolbarMaterial(final HostVerificationEvidence evidence) throws Exception {
        if (evidence.mainToolbar().isEmpty()
            || mainToolbarResolverFactory == null
            || editorUiPluginResources == null
            || editorUiActionRouter == null) {
            dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                "toolbar",
                "Main toolbar integration is unavailable"
            );
            return null;
        }
        final HostVerificationEvidence.Slice slice = evidence.mainToolbar().orElseThrow();
        return new ToolbarMaterial(
            mainToolbarResolverFactory.create(slice),
            MainToolbarVerificationManifest.admissionForArtifact(
                HostArtifactDigest.from(slice.verifiedArtifact())
            )
        );
    }

    private PanelMaterial panelMaterial(final HostVerificationEvidence evidence) throws Exception {
        if (evidence.embeddedPanel().isEmpty()
            || embeddedPanelResolverFactory == null
            || embeddedPanelActivation == null
            || editorUiActionRouter == null) {
            return null;
        }
        final HostVerificationEvidence.Slice slice = evidence.embeddedPanel().orElseThrow();
        return new PanelMaterial(
            embeddedPanelResolverFactory.create(slice),
            EmbeddedPanelVerificationManifest.admissionForArtifact(
                HostArtifactDigest.from(slice.verifiedArtifact())
            )
        );
    }

    private static RuntimeUiResourceService productionUiResourceOwner(
        final PanelMaterial panel,
        final RuntimeHostAdapters adapters
    ) {
        CubismNativeIconResolver iconResolver = null;
        try {
            iconResolver = CubismNativeIconResolver.preload(
                panel.resolver(), PANEL_ICON_ANCHOR_ALIAS
            );
            return RuntimeUiResourceService.connected(
                iconResolver,
                adapters.themeStatus(),
                () -> RuntimeUiResourceService.DEFAULT_SCALE_PERCENT
            );
        } catch (RuntimeException | LinkageError optionalFailure) {
            // The resolver is not yet owned by RuntimeUiResourceService when construction fails.
            // Release it here so optional icon failure cannot leak a partially-created cache.
            closeAfterFailure(optionalFailure, iconResolver);
            throw optionalFailure;
        }
    }

    private TopMenuMaterial topMenuMaterial(final HostVerificationEvidence evidence) throws Exception {
        if (evidence.topMenu().isEmpty()
            || topMenuResolverFactory == null
            || editorUiActionRouter == null) {
            return null;
        }
        final HostVerificationEvidence.Slice slice = evidence.topMenu().orElseThrow();
        return new TopMenuMaterial(
            topMenuResolverFactory.create(slice),
            TopMenuVerificationManifest.admissionForArtifact(
                HostArtifactDigest.from(slice.verifiedArtifact())
            )
        );
    }

    private OverlayMaterial overlayMaterial(final HostVerificationEvidence evidence) throws Exception {
        if (evidence.boundingBoxOverlayButton().isEmpty()
            || boundingBoxOverlayResolverFactory == null
            || editorUiPluginResources == null) {
            return null;
        }
        final HostVerificationEvidence.Slice slice = evidence.boundingBoxOverlayButton().orElseThrow();
        return new OverlayMaterial(
            boundingBoxOverlayResolverFactory.create(slice),
            BoundingBoxOverlayButtonVerificationManifest.admissionForArtifact(
                HostArtifactDigest.from(slice.verifiedArtifact())
            )
        );
    }

    private HostAdapterConnection connection(
        final RuntimeHostAdapters adapters,
        final CubismModelAccess modelAccess,
        final VerifiedMemberResolver resolver,
        final dev.turboism.mapping.verification.EditorModelAdmissionEvidence editorAdmission,
        final ToolbarMaterial toolbar,
        final PanelMaterial panel,
        final TopMenuMaterial topMenu,
        final OverlayMaterial overlay,
        final AppearanceHostProvider appearanceProvider,
        final RuntimeCoreModelBackend core,
        final dev.turboism.ui.workspace.WorkspaceHostProvider workspace,
        final TextureAtlasDataModelCapture textureAtlasCapture,
        final TextureAtlasLayoutProvider textureAtlasProvider,
        final UiResourceOwnerFactory resourceOwnerFactory
    ) throws Exception {
        final dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator layoutCoordinator =
            new dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator();
        RuntimeUiResourceService resourceOwner = null;
        dev.turboism.ui.workspace.layout.WorkspaceLayoutHostProvider layoutProvider = null;
        dev.turboism.ui.panel.VerifiedEmbeddedPanelHostOperations panelOperations = null;
        dev.turboism.ui.panel.NativePanelTabFloatingBridge.Handler cleanupFloatingToggle = null;
        dev.turboism.ui.panel.NativeFloatingFrameDisposeBridge.Handler cleanupFloatingDispose = null;
        dev.turboism.ui.panel.NativeFloatingTabCloseBridge.Handler cleanupFloatingTabClose = null;
        try {
            if (panel != null) {
                if (SwingUtilities.isEventDispatchThread()) {
                    // Resource preload and host presentation sampling are optional and must never
                    // perform their guarded work on a direct EDT ingress. The panel remains healthy
                    // with the explicit text fallback for this direct-EDT connection.
                    dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                        "embedded-panels",
                        "Native icon preload skipped on the EDT; using text fallback"
                    );
                } else {
                    try {
                        resourceOwner = resourceOwnerFactory.create(panel, adapters);
                    } catch (RuntimeException | LinkageError optionalFailure) {
                        closeAfterFailure(optionalFailure, resourceOwner);
                        resourceOwner = null;
                        dev.turboism.runtime.log.RuntimeDiagnostics.error(
                            "embedded-panels",
                            "Native icon resources unavailable; retaining healthy text fallback",
                            optionalFailure
                        );
                    }
                }
            }
            final RuntimeHostAdapters installedAdapters = resourceOwner == null
                ? adapters
                : RuntimeHostAdapters.withUiResources(adapters, resourceOwner.sdkView());
            layoutProvider = panel == null
                ? null
                : new dev.turboism.ui.workspace.layout.VerifiedWorkspaceLayoutHostProvider(
                    panel.resolver());
            if (layoutProvider != null) {
                layoutCoordinator.connect(layoutProvider);
            }
            final RuntimeUiResourceService owner = resourceOwner;
            panelOperations = panel == null
                ? null
                : new dev.turboism.ui.panel.VerifiedEmbeddedPanelHostOperations(
                    panel.resolver(),
                    editorUiActionRouter,
                    effectiveLocale,
                    owner == null ? (reference, disabled) -> Optional.empty() : owner::resolve
                );
            final RuntimeUiResourceService installedResourceOwner = resourceOwner;
            final dev.turboism.ui.workspace.layout.WorkspaceLayoutHostProvider installedLayoutProvider =
                layoutProvider;
            final dev.turboism.ui.panel.VerifiedEmbeddedPanelHostOperations installedPanelOperations =
                panelOperations;
            final dev.turboism.ui.panel.NativePanelTabFloatingBridge.Handler floatingToggle =
                installedPanelOperations == null ? null : installedPanelOperations::togglePanelFloating;
            final dev.turboism.ui.panel.NativeFloatingFrameDisposeBridge.Handler floatingDispose =
                installedPanelOperations == null ? null : installedPanelOperations::onFloatingFrameDisposed;
            final dev.turboism.ui.panel.NativeFloatingTabCloseBridge.Handler floatingTabClose =
                installedPanelOperations == null ? null : installedPanelOperations::onFloatingTabCloseRequested;
            cleanupFloatingToggle = floatingToggle;
            cleanupFloatingDispose = floatingDispose;
            cleanupFloatingTabClose = floatingTabClose;
            if (installedPanelOperations != null) {
                dev.turboism.ui.panel.NativePanelTabFloatingBridge.install(floatingToggle);
                dev.turboism.ui.panel.NativeFloatingFrameDisposeBridge.install(floatingDispose);
                dev.turboism.ui.panel.NativeFloatingTabCloseBridge.install(floatingTabClose);
            }
        return new HostAdapterConnection() {
            @Override
            public dev.turboism.ui.workspace.WorkspaceHostProvider workspaceProvider() {
                return workspace;
            }

            private long menuGeneration = Long.MIN_VALUE;
            private dev.turboism.ui.context.VerifiedObjectContextMenuHostOperations menuHandler;

            private synchronized dev.turboism.ui.context.VerifiedObjectContextMenuHostOperations menuHandler(
                final long hostGeneration
            ) {
                if (menuHandler != null) {
                    if (menuGeneration != hostGeneration) {
                        throw new IllegalStateException("object context-menu host generation changed");
                    }
                    return menuHandler;
                }
                final dev.turboism.ui.context.VerifiedObjectContextMenuNativeAccess nativeAccess =
                    new dev.turboism.ui.context.VerifiedObjectContextMenuNativeAccess(
                        resolver,
                        hostGeneration,
                        "host-generation-" + hostGeneration
                    );
                menuGeneration = hostGeneration;
                menuHandler = new dev.turboism.ui.context.VerifiedObjectContextMenuHostOperations(
                    nativeAccess,
                    nativeAccess,
                    nativeAccess::appendPersistent
                );
                return menuHandler;
            }
            @Override
            public RuntimeHostAdapters adapters() {
                return installedAdapters;
            }

            @Override
            public CubismModelAccess modelAccess() {
                return modelAccess;
            }

            @Override
            public dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntimeInfo() {
                return core == null
                    ? DynamicCoreRuntimeInfo.unavailableRuntime()
                    : core.coreRuntimeInfo();
            }

            @Override
            public dev.turboism.adapter.cubism.command.EditorCommandAdapter editorCommands() {
                return topMenu == null
                    ? dev.turboism.adapter.cubism.command.EditorCommandAdapter.unavailable()
                    : new dev.turboism.adapter.cubism.command.VerifiedEditorCommandAdapter(topMenu.resolver());
            }

            @Override
            public VerifiedMemberResolver editorModelResolver() {
                return resolver;
            }

            @Override
            public AppearanceHostProvider appearanceProvider() {
                return appearanceProvider;
            }

            @Override
            public TextureAtlasDataModelCapture textureAtlasDataModelCapture() {
                return textureAtlasCapture;
            }

            @Override
            public java.util.Optional<TextureAtlasLayoutProvider> textureAtlasLayoutProvider() {
                return java.util.Optional.ofNullable(textureAtlasProvider);
            }

            @Override
            public dev.turboism.ui.context.NativeObjectContextMenuBridge.Handler objectContextMenuHandler(
                final long hostGeneration
            ) {
                return menuHandler(hostGeneration);
            }

            @Override
            public dev.turboism.ui.context.NativeParameterPointContextMenuBridge.Handler parameterPointMenuHandler(
                final long hostGeneration
            ) {
                final var host = menuHandler(hostGeneration);
                final var nativeAccess = new dev.turboism.ui.context.VerifiedObjectContextMenuNativeAccess(
                    resolver, hostGeneration, "host-generation-" + hostGeneration
                );
                return dev.turboism.ui.context.NativeParameterPointContextMenuBridge.handler(host, nativeAccess);
            }

            @Override
            public VerifiedMemberResolver boundingBoxOverlayResolver() {
                return overlay == null
                    ? HostAdapterConnection.super.boundingBoxOverlayResolver()
                    : overlay.resolver();
            }

            @Override
            public List<EditorUiContributionProvider> editorUiProviders(final long hostGeneration) {
                final List<EditorUiContributionProvider> providers = new ArrayList<>();
                final dev.turboism.ui.context.NativeObjectContextMenuBridge.Handler menuHandler =
                    menuHandler(hostGeneration);
                providers.add(new dev.turboism.ui.context.ContextMenuContributionProvider(
                    EditorUiProviderAdmission.admitted(
                        EditorUiFamily.CONTEXT_MENU,
                        hostGeneration,
                        new EditorUiProviderAdmission.VerificationEvidence(
                            editorAdmission.cubismVersion(),
                            editorAdmission.artifactSize(),
                            editorAdmission.artifactSha256(),
                            editorAdmission.adapterSliceId(),
                            editorAdmission.recordSha256()
                        )
                    ),
                    (dev.turboism.ui.context.ContextMenuHostOperations) menuHandler,
                    editorUiActionRouter,
                    panelTabMenus
                ));
                providers.add(new dev.turboism.ui.toolbar.PaletteToolbarContributionProvider(
                    EditorUiProviderAdmission.admitted(
                        EditorUiFamily.PALETTE_TOOLBAR,
                        hostGeneration,
                        new EditorUiProviderAdmission.VerificationEvidence(
                            editorAdmission.cubismVersion(),
                            editorAdmission.artifactSize(),
                            editorAdmission.artifactSha256(),
                            editorAdmission.adapterSliceId(),
                            editorAdmission.recordSha256()
                        )
                    ),
                    new dev.turboism.ui.toolbar.VerifiedPaletteToolbarHostOperations(
                        editorUiPluginResources
                    ),
                    editorUiActionRouter
                ));
                if (toolbar != null) {
                    diag("installing MAIN/VERTICAL/HORIZONTAL toolbar providers");
                    providers.add(new MainToolbarContributionProvider(
                        EditorUiProviderAdmission.admitted(
                            EditorUiFamily.MAIN_TOOLBAR,
                            hostGeneration,
                            verificationEvidence(toolbar.admission())
                        ),
                        new VerifiedMainToolbarHostOperations(
                            toolbar.resolver(),
                            editorUiPluginResources
                        ),
                        editorUiActionRouter
                    ));
                    providers.add(new VerticalToolbarContributionProvider(
                        EditorUiProviderAdmission.admitted(
                            EditorUiFamily.VERTICAL_TOOLBAR,
                            hostGeneration,
                            verificationEvidence(toolbar.admission())
                        ),
                        new VerifiedVerticalToolbarHostOperations(
                            toolbar.resolver(),
                            editorUiPluginResources
                        ),
                        editorUiActionRouter
                    ));
                    providers.add(new HorizontalToolbarContributionProvider(
                        EditorUiProviderAdmission.admitted(
                            EditorUiFamily.HORIZONTAL_TOOLBAR,
                            hostGeneration,
                            verificationEvidence(toolbar.admission())
                        ),
                        new VerifiedHorizontalToolbarHostOperations(
                            toolbar.resolver(),
                            editorUiPluginResources
                        ),
                        editorUiActionRouter
                    ));
                }
                if (panel != null) {
                    providers.add(new EmbeddedPanelContributionProvider(
                        EditorUiProviderAdmission.admitted(
                            EditorUiFamily.PANEL,
                            hostGeneration,
                            verificationEvidence(panel.admission())
                        ),
                        installedPanelOperations,
                        embeddedPanelActivation,
                        editorUiActionRouter,
                        panelTabMenus,
                        dockMaintenance
                    ));
                }
                if (topMenu != null) {
                    providers.add(new TopMenuContributionProvider(
                        EditorUiProviderAdmission.admitted(
                            EditorUiFamily.MENU,
                            hostGeneration,
                            verificationEvidence(topMenu.admission())
                        ),
                        new VerifiedTopMenuHostOperations(topMenu.resolver()),
                        editorUiActionRouter,
                        () -> dev.turboism.ui.menu.TopMenuText.sharedRootLabel(effectiveLocale.get())
                    ));
                }
                if (overlay != null) {
                    providers.add(new BoundingBoxOverlayButtonContributionProvider(
                        EditorUiProviderAdmission.admitted(
                            EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON,
                            hostGeneration,
                            verificationEvidence(overlay.admission())
                        ),
                        new VerifiedBoundingBoxOverlayButtonHostOperations(
                            overlay.resolver(),
                            editorUiPluginResources
                        )
                    ));
                }
                return List.copyOf(providers);
            }

            @Override
            public dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance() {
                return dockMaintenance;
            }



            @Override
            public dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator workspaceLayoutCoordinator() {
                return layoutCoordinator;
            }

            @Override
            public void refreshPresentation() {
                if (installedResourceOwner != null && !SwingUtilities.isEventDispatchThread()) {
                    try {
                        installedResourceOwner.refreshPresentation();
                    } catch (Throwable failure) {
                        dev.turboism.runtime.log.RuntimeDiagnostics.error(
                            "embedded-panels",
                            "Host presentation sampling failed safely",
                            failure
                        );
                    }
                }
                if (installedPanelOperations != null) {
                    try {
                        installedPanelOperations.refreshPresentation();
                    } catch (Throwable failure) {
                        dev.turboism.runtime.log.RuntimeDiagnostics.error(
                            "embedded-panels",
                            "Host panel presentation refresh failed safely",
                            failure
                        );
                    }
                }
            }

            @Override
            public void close() throws Exception {
                Throwable first = null;
                if (installedPanelOperations != null) {
                    first = runCleanup(first, installedPanelOperations::invalidateHost);
                    first = runCleanup(first, () ->
                        dev.turboism.ui.panel.NativePanelTabFloatingBridge.uninstall(floatingToggle));
                    first = runCleanup(first, () ->
                        dev.turboism.ui.panel.NativeFloatingFrameDisposeBridge.uninstall(floatingDispose));
                    first = runCleanup(first, () ->
                        dev.turboism.ui.panel.NativeFloatingTabCloseBridge.uninstall(floatingTabClose));
                }
                if (installedLayoutProvider != null) {
                    final dev.turboism.ui.workspace.layout.WorkspaceLayoutHostProvider provider =
                        installedLayoutProvider;
                    first = runCleanup(first, () -> layoutCoordinator.disconnect(provider));
                }
                if (installedResourceOwner != null) {
                    first = closeCleanup(first, installedResourceOwner);
                }
                if (core != null) {
                    first = closeCleanup(first, core);
                }
                rethrowConnectionFailure(first);
            }
        };
    } catch (Throwable failure) {
        if (panelOperations != null) {
            final dev.turboism.ui.panel.VerifiedEmbeddedPanelHostOperations cleanupPanelOperations =
                panelOperations;
            final dev.turboism.ui.panel.NativePanelTabFloatingBridge.Handler cleanupToggle =
                cleanupFloatingToggle;
            final dev.turboism.ui.panel.NativeFloatingFrameDisposeBridge.Handler cleanupDispose =
                cleanupFloatingDispose;
            final dev.turboism.ui.panel.NativeFloatingTabCloseBridge.Handler cleanupTabClose =
                cleanupFloatingTabClose;
            runAfterFailure(failure, cleanupPanelOperations::invalidateHost);
            runAfterFailure(failure, () ->
                dev.turboism.ui.panel.NativePanelTabFloatingBridge.uninstall(cleanupToggle));
            runAfterFailure(failure, () ->
                dev.turboism.ui.panel.NativeFloatingFrameDisposeBridge.uninstall(cleanupDispose));
            runAfterFailure(failure, () ->
                dev.turboism.ui.panel.NativeFloatingTabCloseBridge.uninstall(cleanupTabClose));
        }
        if (layoutProvider != null) {
            final dev.turboism.ui.workspace.layout.WorkspaceLayoutHostProvider cleanupLayoutProvider =
                layoutProvider;
            runAfterFailure(failure, () -> layoutCoordinator.disconnect(cleanupLayoutProvider));
        }
        if (resourceOwner != null) {
            closeAfterFailure(failure, resourceOwner);
        }
        rethrowConnectionFailure(failure);
        throw new AssertionError("unreachable");
    }
    }

    private static Throwable runCleanup(final Throwable first, final Runnable operation) {
        try {
            operation.run();
            return first;
        } catch (Throwable failure) {
            return accumulateCleanup(first, failure);
        }
    }

    private static Throwable closeCleanup(final Throwable first, final AutoCloseable resource) {
        try {
            resource.close();
            return first;
        } catch (Throwable failure) {
            return accumulateCleanup(first, failure);
        }
    }

    private static void runAfterFailure(final Throwable primary, final Runnable operation) {
        try {
            operation.run();
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != primary) {
                primary.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void closeAfterFailure(final Throwable primary, final AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != primary) {
                primary.addSuppressed(cleanupFailure);
            }
        }
    }

    private static Throwable accumulateCleanup(final Throwable first, final Throwable next) {
        if (first == null) {
            return next;
        }
        if (next != first) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static void rethrowConnectionFailure(final Throwable failure) throws Exception {
        if (failure == null) {
            return;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        throw new RuntimeException(failure);
    }

    private static EditorUiProviderAdmission.VerificationEvidence verificationEvidence(
        final MainToolbarVerificationManifest.AdmissionEvidence evidence
    ) {
        return new EditorUiProviderAdmission.VerificationEvidence(
            evidence.cubismVersion(), evidence.artifactSize(), evidence.artifactSha256(),
            evidence.adapterSliceId(), evidence.recordSha256()
        );
    }

    private static EditorUiProviderAdmission.VerificationEvidence verificationEvidence(
        final EmbeddedPanelVerificationManifest.AdmissionEvidence evidence
    ) {
        return new EditorUiProviderAdmission.VerificationEvidence(
            evidence.cubismVersion(), evidence.artifactSize(), evidence.artifactSha256(),
            evidence.adapterSliceId(), evidence.recordSha256()
        );
    }

    private static EditorUiProviderAdmission.VerificationEvidence verificationEvidence(
        final TopMenuVerificationManifest.AdmissionEvidence evidence
    ) {
        return new EditorUiProviderAdmission.VerificationEvidence(
            evidence.cubismVersion(), evidence.artifactSize(), evidence.artifactSha256(),
            evidence.adapterSliceId(), evidence.recordSha256()
        );
    }

    private static EditorUiProviderAdmission.VerificationEvidence verificationEvidence(
        final BoundingBoxOverlayButtonVerificationManifest.AdmissionEvidence evidence
    ) {
        return new EditorUiProviderAdmission.VerificationEvidence(
            evidence.cubismVersion(), evidence.artifactSize(), evidence.artifactSha256(),
            evidence.adapterSliceId(),
            BoundingBoxOverlayButtonVerificationManifest.recordSha256ForVersion(
                evidence.cubismVersion()
            )
        );
    }

    private record ToolbarMaterial(
        VerifiedMemberResolver resolver,
        MainToolbarVerificationManifest.AdmissionEvidence admission
    ) {
    }

    record PanelMaterial(
        VerifiedMemberResolver resolver,
        EmbeddedPanelVerificationManifest.AdmissionEvidence admission
    ) {
    }

    private record TopMenuMaterial(
        VerifiedMemberResolver resolver,
        TopMenuVerificationManifest.AdmissionEvidence admission
    ) {
    }

    private record OverlayMaterial(
        VerifiedMemberResolver resolver,
        BoundingBoxOverlayButtonVerificationManifest.AdmissionEvidence admission
    ) {
    }

    @FunctionalInterface
    interface PanelMaterialFactory {
        PanelMaterial create(HostVerificationEvidence evidence) throws Exception;
    }

    @FunctionalInterface
    interface UiResourceOwnerFactory {
        RuntimeUiResourceService create(PanelMaterial panel, RuntimeHostAdapters adapters);
    }

    @FunctionalInterface
    interface VerifiedAdapterFactory {
        RuntimeHostAdapters create(HostVerificationEvidence evidence) throws Exception;
    }

    @FunctionalInterface
    interface EditorResolverFactory {
        VerifiedMemberResolver create(HostVerificationEvidence.Slice slice) throws Exception;
    }

    @FunctionalInterface
    interface EditorAccessFactory {
        /**
         * Creates the Editor model access with the optional Core evaluated join.
         *
         * <p>The Core backend may be null; the production wiring installs it so
         * Editor-backed objects can join Core evaluated fields.</p>
         */
        CubismModelAccess create(
            VerifiedMemberResolver resolver,
            String sessionId,
            RuntimeCoreModelBackend coreBackend
        );

        /** Creates the Editor model access without a Core evaluated join. */
        default CubismModelAccess create(
            final VerifiedMemberResolver resolver,
            final String sessionId
        ) {
            return create(resolver, sessionId, null);
        }
    }

    /**
     * Creates the Core model backend for the host evidence.
     *
     * <p>The production default is the real reviewed admission path
     * ({@link #coreMaterial}); tests inject a fixture-backed backend through this seam.</p>
     */
    @FunctionalInterface
    interface CoreBackendFactory {
        RuntimeCoreModelBackend create(HostVerificationEvidence evidence) throws Exception;
    }
    @FunctionalInterface
    interface MainToolbarResolverFactory {
        VerifiedMemberResolver create(HostVerificationEvidence.Slice slice) throws Exception;
    }

    @FunctionalInterface
    interface AppearanceProviderFactory {
        AppearanceHostProvider create(HostVerificationEvidence.Slice projectSlice) throws Exception;
    }

    interface WorkspaceResolverFactory {
        VerifiedMemberResolver create(HostVerificationEvidence.Slice workspaceControl) throws Exception;
    }

    static AppearanceProviderFactory productionAppearanceProviderFactory() {
        return VerifiedHostAdapterConnector::productionAppearanceProvider;
    }

    static CoreBackendFactory productionCoreBackendFactory() {
        return VerifiedHostAdapterConnector::coreMaterial;
    }

    private static AppearanceHostProvider unavailableAppearanceProvider() {
        return new dev.turboism.ui.appearance.UnavailableAppearanceHostProvider();
    }

    private static AppearanceHostProvider productionAppearanceProvider(
        final HostVerificationEvidence.Slice slice
    ) throws Exception {
        if (!java.nio.file.Files.isRegularFile(slice.verifiedArtifact())) {
            return unavailableAppearanceProvider();
        }
        final HostArtifactDigest artifact = HostArtifactDigest.from(slice.verifiedArtifact());
        final String version = dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest
            .versionForArtifact(artifact);
        return productionAppearanceProviderForVersion(
            version,
            new SwingFlatLafHostOperations(slice.hostClassLoader())
        );
    }

    static AppearanceHostProvider productionAppearanceProviderForVersion(
        final String version,
        final FlatLafAppearanceHostProvider.HostOperations hostOperations
    ) {
        if (!dev.turboism.mapping.verification.ReviewedHostArtifacts.admitsFullRuntime(version)) {
            return unavailableAppearanceProvider();
        }
        return new FlatLafAppearanceHostProvider(version, hostOperations);
    }

    @FunctionalInterface
    interface EmbeddedPanelResolverFactory {
        VerifiedMemberResolver create(HostVerificationEvidence.Slice slice) throws Exception;
    }

    @FunctionalInterface
    interface BoundingBoxOverlayResolverFactory {
        VerifiedMemberResolver create(HostVerificationEvidence.Slice slice) throws Exception;
    }

    @FunctionalInterface
    interface TopMenuResolverFactory {
        VerifiedMemberResolver create(HostVerificationEvidence.Slice slice) throws Exception;
    }
}
