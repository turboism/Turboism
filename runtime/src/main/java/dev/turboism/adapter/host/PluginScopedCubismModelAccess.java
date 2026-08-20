package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator;
import dev.turboism.ui.appearance.control.RuntimeModelAppearanceAccess;
import dev.turboism.ui.appearance.control.RuntimeModelAppearanceComposition;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;

import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.List;
import java.util.Optional;

/** Runtime composition helper that invalidates one plugin's model references on scope close. */
public final class PluginScopedCubismModelAccess {

    private PluginScopedCubismModelAccess() {
    }

    /** Builds the narrow host source used only by model appearance projections. */
    public static HostSnapshotSource appearanceSource(
        final ProjectWorkspaceAdapter projectWorkspace,
        final CubismModelAccess modelAccess
    ) {
        return new AppearanceSource(
            Objects.requireNonNull(projectWorkspace, "projectWorkspace"),
            Objects.requireNonNull(modelAccess, "modelAccess")
        );
    }

    /**
     * Wraps {@code delegate} so that closing {@code scope} deactivates the plugin's view of the
     * model: after the scope closes the returned access no longer reaches the host.
     *
     * @param delegate live host model access to delegate to while the scope is open
     * @param scope plugin scope whose close deactivates the returned access
     * @return a model access valid only for the lifetime of the scope
     * @throws NullPointerException if either argument is null
     */
    public static CubismModelAccess bind(
        final CubismModelAccess delegate,
        final DisposableScope scope
    ) {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(Objects.requireNonNull(delegate, "delegate"));
        Objects.requireNonNull(scope, "scope").register(access::deactivate);
        return access;
    }

    /**
     * Same scope-bound binding as {@link #bind(CubismModelAccess, DisposableScope)}, additionally
     * composing permission-checked model-appearance access for the named plugin. The appearance
     * access is bound to the same scope, so both it and the model access are deactivated together
     * when the scope closes.
     *
     * @param delegate live host model access to delegate to while the scope is open
     * @param scope plugin scope whose close deactivates the returned access
     * @param pluginId plugin the appearance access is attributed to for permission checks
     * @param permissionChecker gate consulted before appearance mutations
     * @param source host snapshot source the appearance projection reads from
     * @param coordinator palette appearance coordinator supplying the host generation counter
     * @param nativeLabelColorAuthoring native seam used to author label colours
     * @return a model access with appearance support, valid only for the lifetime of the scope
     * @throws NullPointerException if any argument is null
     */
    public static CubismModelAccess bind(
        final CubismModelAccess delegate,
        final DisposableScope scope,
        final String pluginId,
        final PermissionChecker permissionChecker,
        final HostSnapshotSource source,
        final PaletteAppearanceCoordinator coordinator,
        final NativeLabelColorAuthoring nativeLabelColorAuthoring
    ) {
        final CubismModelAccess hostAccess = Objects.requireNonNull(delegate, "delegate");
        final DisposableScope owner = Objects.requireNonNull(scope, "scope");
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final PaletteAppearanceCoordinator appearanceCoordinator = Objects.requireNonNull(
            coordinator, "coordinator"
        );
        final LongSupplier hostGeneration = appearanceCoordinator::hostGeneration;
        final RuntimeModelAppearanceComposition composition = new RuntimeModelAppearanceComposition(
            appearanceCoordinator,
            access::modelGeneration,
            hostGeneration,
            hostGeneration,
            Objects.requireNonNull(nativeLabelColorAuthoring, "nativeLabelColorAuthoring")
        );
        final RuntimeModelAppearanceAccess appearance = RuntimeModelAppearanceAccess.create(
            pluginId,
            1L,
            Objects.requireNonNull(permissionChecker, "permissionChecker"),
            Objects.requireNonNull(source, "source"),
            composition.coordinator(),
            composition::modelGeneration,
            composition::hostGeneration,
            composition::providerGeneration,
            composition.nativeLabelColorAuthoring()
        );
        access.attachAppearanceAccess(appearance);
        access.connect(hostAccess);
        appearance.bind(owner);
        owner.register(access::deactivate);
        return access;
    }

    private static final class AppearanceSource implements HostSnapshotSource {
        private final ProjectWorkspaceAdapter projectWorkspace;
        private final CubismModelAccess modelAccess;
        private String activeKey;
        private long activationToken;

        private AppearanceSource(
            final ProjectWorkspaceAdapter projectWorkspace,
            final CubismModelAccess modelAccess
        ) {
            this.projectWorkspace = projectWorkspace;
            this.modelAccess = modelAccess;
        }

        @Override
        public java.util.Optional<HostProject> activeProject() {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<HostDocument> activeDocument() {
            return current().map(value -> value.document());
        }

        @Override
        public java.util.Optional<HostModel> activeModel() {
            return current().flatMap(value -> value.model());
        }

        @Override
        public HostSelection selection() {
            return new HostSelection(java.util.List.of(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty());
        }

        @Override
        public boolean isHostPresent() {
            return current().isPresent();
        }

        @Override
        public long invalidationToken() {
            current();
            synchronized (this) {
                return activationToken;
            }
        }

        private synchronized java.util.Optional<Current> current() {
            final java.util.Optional<DocumentSnapshot> snapshot = activeDocumentSnapshot();
            if (snapshot.isEmpty()) {
                changeKey(null);
                return java.util.Optional.empty();
            }
            final DocumentSnapshot document = snapshot.orElseThrow();
            final String contentId = document.contentId().orElse(document.documentId());
            if (document.kind() != DocumentKind.MODEL || document.model().isEmpty()) {
                changeKey(null);
                return java.util.Optional.of(new Current(document(document, contentId), java.util.Optional.empty()));
            }

            final CubismModel model;
            final String modelId;
            try {
                model = Objects.requireNonNull(modelAccess.active(), "active model");
                modelId = Objects.requireNonNull(model.id(), "active model id").value();
            } catch (RuntimeException unavailable) {
                changeKey(null);
                return java.util.Optional.of(new Current(document(document, contentId), java.util.Optional.empty()));
            }
            if (modelId.isBlank()) {
                changeKey(null);
                return java.util.Optional.of(new Current(document(document, contentId), java.util.Optional.empty()));
            }
            final HostModel hostModel = new HostModel(
                modelId,
                document.model().orElseThrow().name(),
                java.util.List.of(), java.util.List.of(), java.util.List.of()
            );
            changeKey(contentId + "\\u0000" + modelId);
            return java.util.Optional.of(new Current(
                document(document, contentId, hostModel), java.util.Optional.of(hostModel)
            ));
        }

        private java.util.Optional<DocumentSnapshot> activeDocumentSnapshot() {
            final ProjectWorkspaceAdapter.AdapterResult<java.util.Optional<DocumentSnapshot>> result =
                projectWorkspace.activeDocument();
            return result.isAvailable() ? result.value().orElse(java.util.Optional.empty())
                : java.util.Optional.empty();
        }

        private static HostDocument document(
            final DocumentSnapshot source,
            final String contentId
        ) {
            return new HostDocument(
                source.documentId(), source.name(), source.kind(), source.relativePath(), source.filePath(),
                java.util.Optional.of(contentId), java.util.Optional.empty(), java.util.Optional.empty()
            );
        }

        private static HostDocument document(
            final DocumentSnapshot source,
            final String contentId,
            final HostModel model
        ) {
            return new HostDocument(
                source.documentId(), source.name(), DocumentKind.MODEL, source.relativePath(), source.filePath(),
                java.util.Optional.of(contentId), java.util.Optional.of(model), java.util.Optional.empty()
            );
        }

        private void changeKey(final String nextKey) {
            if (!Objects.equals(activeKey, nextKey)) {
                activeKey = nextKey;
                activationToken = Math.incrementExact(activationToken);
            }
        }

        private record Current(HostDocument document, java.util.Optional<HostModel> model) { }
    }
}
