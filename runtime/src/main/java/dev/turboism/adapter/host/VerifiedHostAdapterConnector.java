package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.VerifiedRuntimeHostAdaptersFactory;
import dev.turboism.adapter.cubism.editor.EditorBackedCubismModelAccess;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.MainToolbarVerificationManifest;
import dev.turboism.mapping.verification.VerifiedEditorModelResolverFactory;
import dev.turboism.mapping.verification.VerifiedMainToolbarResolverFactory;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;
import dev.turboism.ui.toolbar.MainToolbarContributionProvider;
import dev.turboism.ui.toolbar.VerifiedMainToolbarHostOperations;
import dev.turboism.ui.appearance.AppearanceHostProvider;
import dev.turboism.ui.appearance.FlatLafAppearanceHostProvider;
import dev.turboism.ui.appearance.SwingFlatLafHostOperations;

import java.util.List;
import java.util.Objects;

/** Production connector pinned to the reviewed project/workspace verification trust root. */
final class VerifiedHostAdapterConnector implements HostAdapterConnector {

    private final VerifiedAdapterFactory factory;
    private final EditorResolverFactory editorResolverFactory;
    private final EditorAccessFactory editorAccessFactory;
    private final MainToolbarResolverFactory mainToolbarResolverFactory;
    private final EditorUiPluginResourceRegistry editorUiPluginResources;
    private final dev.turboism.ui.action.RuntimeEditorUiActionRouter editorUiActionRouter;
    private final AppearanceProviderFactory appearanceProviderFactory;

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
            null,
            null,
            VerifiedHostAdapterConnector::productionAppearanceProvider
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
            null,
            null,
            slice -> unavailableAppearanceProvider()
        );
    }

    VerifiedHostAdapterConnector(
        final VerifiedAdapterFactory factory,
        final EditorResolverFactory editorResolverFactory,
        final EditorAccessFactory editorAccessFactory
    ) {
        this(factory, editorResolverFactory, editorAccessFactory, null, null, null, ignored -> unavailableAppearanceProvider());
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
            editorUiPluginResources,
            editorUiActionRouter,
            ignored -> unavailableAppearanceProvider()
        );
    }

    static AppearanceProviderFactory productionAppearanceProviderFactory() {
        return VerifiedHostAdapterConnector::productionAppearanceProvider;
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
        this.factory = Objects.requireNonNull(factory, "factory");
        this.editorResolverFactory = Objects.requireNonNull(editorResolverFactory, "editorResolverFactory");
        this.editorAccessFactory = Objects.requireNonNull(editorAccessFactory, "editorAccessFactory");
        this.mainToolbarResolverFactory = mainToolbarResolverFactory;
        this.editorUiPluginResources = editorUiPluginResources;
        this.editorUiActionRouter = editorUiActionRouter;
        this.appearanceProviderFactory = Objects.requireNonNull(appearanceProviderFactory, "appearanceProviderFactory");
    }

    @Override
    public HostAdapterConnection connect(final HostInstanceDescriptor descriptor) throws Exception {
        Objects.requireNonNull(descriptor, "descriptor");
        final HostVerificationEvidence evidence = descriptor.verificationEvidence();
        final RuntimeHostAdapters adapters = factory.create(evidence);
        final AppearanceHostProvider appearanceProvider = appearanceProviderFactory.create(
            evidence.projectWorkspace()
        );
        if (evidence.editorModel().isEmpty()) {
            return HostAdapterConnection.of(adapters, UnavailableCubismModelAccess.INSTANCE, null, appearanceProvider);
        }
        final VerifiedMemberResolver resolver = editorResolverFactory.create(
            evidence.editorModel().orElseThrow()
        );
        final CubismModelAccess modelAccess = editorAccessFactory.create(
            resolver,
            descriptor.sessionId()
        );
        if (evidence.mainToolbar().isEmpty()
            || mainToolbarResolverFactory == null
            || editorUiPluginResources == null
            || editorUiActionRouter == null) {
            return HostAdapterConnection.of(adapters, modelAccess, resolver, appearanceProvider);
        }
        final HostVerificationEvidence.Slice toolbarSlice = evidence.mainToolbar().orElseThrow();
        final VerifiedMemberResolver toolbarResolver = mainToolbarResolverFactory.create(toolbarSlice);
        final MainToolbarVerificationManifest.AdmissionEvidence toolbarAdmission =
            MainToolbarVerificationManifest.admissionForArtifact(
                HostArtifactDigest.from(toolbarSlice.verifiedArtifact())
            );
        return new HostAdapterConnection() {
            @Override
            public RuntimeHostAdapters adapters() {
                return adapters;
            }

            @Override
            public CubismModelAccess modelAccess() {
                return modelAccess;
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
            public List<EditorUiContributionProvider> editorUiProviders(
                final long hostGeneration
            ) {
                return List.of(new MainToolbarContributionProvider(
                    EditorUiProviderAdmission.admitted(
                        EditorUiFamily.MAIN_TOOLBAR,
                        hostGeneration,
                        new EditorUiProviderAdmission.VerificationEvidence(
                            toolbarAdmission.cubismVersion(),
                            toolbarAdmission.artifactSize(),
                            toolbarAdmission.artifactSha256(),
                            toolbarAdmission.adapterSliceId(),
                            toolbarAdmission.recordSha256()
                        )
                    ),
                    new VerifiedMainToolbarHostOperations(
                        toolbarResolver,
                        editorUiPluginResources
                    ),
                    editorUiActionRouter
                ));
            }

            @Override
            public void close() {
            }
        };
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
    interface AppearanceProviderFactory {
        AppearanceHostProvider create(HostVerificationEvidence.Slice projectSlice) throws Exception;
    }

    private static AppearanceHostProvider unavailableAppearanceProvider() {
        return new dev.turboism.ui.appearance.UnavailableAppearanceHostProvider();
    }
    private static AppearanceHostProvider productionAppearanceProvider(
        final HostVerificationEvidence.Slice slice
    ) throws Exception {
        final HostArtifactDigest artifact = HostArtifactDigest.from(slice.verifiedArtifact());
        final String version = dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest
            .versionForArtifact(artifact);
        return new FlatLafAppearanceHostProvider(
            version,
            new SwingFlatLafHostOperations(slice.hostClassLoader())
        );
    }
}
