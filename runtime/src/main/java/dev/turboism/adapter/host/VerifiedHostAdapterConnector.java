package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.VerifiedRuntimeHostAdaptersFactory;
import dev.turboism.adapter.cubism.editor.EditorBackedCubismModelAccess;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Production connector pinned to the reviewed project/workspace verification trust root. */
final class VerifiedHostAdapterConnector implements HostAdapterConnector {

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

    VerifiedHostAdapterConnector() {
        this(
            new VerifiedRuntimeHostAdaptersFactory()::create,
            slice -> new VerifiedEditorModelResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            EditorBackedCubismModelAccess::new,
            slice -> new VerifiedMainToolbarResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            slice -> new VerifiedEmbeddedPanelResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            null,
            null,
            null
        );
    }

    VerifiedHostAdapterConnector(final VerifiedAdapterFactory factory) {
        this(
            factory,
            slice -> new VerifiedEditorModelResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            EditorBackedCubismModelAccess::new,
            slice -> new VerifiedMainToolbarResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            slice -> new VerifiedEmbeddedPanelResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            ),
            null,
            null,
            null
        );
    }

    VerifiedHostAdapterConnector(
        final VerifiedAdapterFactory factory,
        final EditorResolverFactory editorResolverFactory,
        final EditorAccessFactory editorAccessFactory
    ) {
        this(factory, editorResolverFactory, editorAccessFactory, null, null, null, null, null);
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
            factory,
            editorResolverFactory,
            editorAccessFactory,
            mainToolbarResolverFactory,
            null,
            editorUiPluginResources,
            editorUiActionRouter,
            null
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
            factory,
            editorResolverFactory,
            editorAccessFactory,
            mainToolbarResolverFactory,
            embeddedPanelResolverFactory,
            editorUiPluginResources,
            editorUiActionRouter,
            embeddedPanelActivation,
            slice -> new VerifiedTopMenuResolverFactory().create(
                slice.reviewedRecord(), slice.verifiedArtifact(), slice.hostClassLoader()
            )
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
            embeddedPanelResolverFactory, editorUiPluginResources, editorUiActionRouter,
            embeddedPanelActivation, topMenuResolverFactory,
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator()
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
            factory,
            editorResolverFactory,
            editorAccessFactory,
            mainToolbarResolverFactory,
            embeddedPanelResolverFactory,
            null,
            editorUiPluginResources,
            editorUiActionRouter,
            embeddedPanelActivation,
            topMenuResolverFactory,
            dockMaintenance
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
        final dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance
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
    }

    @Override
    public HostAdapterConnection connect(final HostInstanceDescriptor descriptor) throws Exception {
        Objects.requireNonNull(descriptor, "descriptor");
        final HostVerificationEvidence evidence = descriptor.verificationEvidence();
        final RuntimeHostAdapters adapters = factory.create(evidence);
        if (evidence.editorModel().isEmpty()) {
            return HostAdapterConnection.of(adapters);
        }
        final VerifiedMemberResolver resolver = editorResolverFactory.create(
            evidence.editorModel().orElseThrow()
        );
        final dev.turboism.mapping.verification.EditorModelAdmissionEvidence editorAdmission =
            editorAdmission(evidence.editorModel().orElseThrow(), resolver);
        final CubismModelAccess modelAccess = editorAccessFactory.create(
            resolver,
            descriptor.sessionId()
        );
        final ToolbarMaterial toolbar = toolbarMaterial(evidence);
        final PanelMaterial panel = panelMaterial(evidence);
        final TopMenuMaterial topMenu = topMenuMaterial(evidence);
        final OverlayMaterial overlay = optionalOverlayMaterial(evidence);
        if (toolbar == null && panel == null && topMenu == null && overlay == null) {
            return HostAdapterConnection.of(adapters, modelAccess, resolver);
        }
        return connection(adapters, modelAccess, resolver, editorAdmission, toolbar, panel, topMenu, overlay);
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

    private OverlayMaterial optionalOverlayMaterial(final HostVerificationEvidence evidence) {
        try {
            return overlayMaterial(evidence);
        } catch (Exception unavailable) {
            return null;
        }
    }

    private ToolbarMaterial toolbarMaterial(final HostVerificationEvidence evidence) throws Exception {
        if (evidence.mainToolbar().isEmpty()
            || mainToolbarResolverFactory == null
            || editorUiPluginResources == null
            || editorUiActionRouter == null) {
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
            || embeddedPanelActivation == null) {
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
        final OverlayMaterial overlay
    ) {
        return new HostAdapterConnection() {
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
                return adapters;
            }

            @Override
            public CubismModelAccess modelAccess() {
                return modelAccess;
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
                if (toolbar != null) {
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
                }
                if (panel != null) {
                    providers.add(new EmbeddedPanelContributionProvider(
                        EditorUiProviderAdmission.admitted(
                            EditorUiFamily.PANEL,
                            hostGeneration,
                            verificationEvidence(panel.admission())
                        ),
                        new VerifiedEmbeddedPanelHostOperations(panel.resolver()),
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
                        editorUiActionRouter
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
            public void close() {
            }
        };
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

    private record PanelMaterial(
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
    interface VerifiedAdapterFactory {
        RuntimeHostAdapters create(HostVerificationEvidence evidence) throws Exception;
    }

    @FunctionalInterface
    interface EditorResolverFactory {
        VerifiedMemberResolver create(HostVerificationEvidence.Slice slice) throws Exception;
    }

    @FunctionalInterface
    interface EditorAccessFactory {
        CubismModelAccess create(VerifiedMemberResolver resolver, String sessionId);
    }

    @FunctionalInterface
    interface MainToolbarResolverFactory {
        VerifiedMemberResolver create(HostVerificationEvidence.Slice slice) throws Exception;
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
