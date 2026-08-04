package dev.turboism.ui.appearance.control;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.NativeLabelColorAuthoring;
import dev.turboism.adapter.cubism.NativeLabelColorTarget;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.ui.appearance.PaletteEntry;
import dev.turboism.sdk.ui.appearance.PaletteEntryState;
import dev.turboism.sdk.ui.appearance.UiColor;
import dev.turboism.sdk.ui.appearance.model.DeformerAppearance;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterAppearance;
import dev.turboism.sdk.ui.appearance.model.ParameterGroupAppearance;
import dev.turboism.sdk.ui.appearance.model.PartAppearance;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plugin-scoped, fail-closed projection of model-owned Cubism palette entries. */
public final class RuntimeModelAppearanceAccess implements AutoCloseable {

    private final String pluginId;
    private final long pluginGeneration;
    private final PermissionChecker permissionChecker;
    private final HostSnapshotSource source;
    private final PaletteAppearanceCoordinator coordinator;
    private final LongSupplier currentModelGeneration;
    private final NativeLabelColorAuthoring nativeAuthoring;
    private final LongSupplier hostGeneration;
    private final LongSupplier providerGeneration;
    private final AtomicBoolean active = new AtomicBoolean(true);

    RuntimeModelAppearanceAccess(
        final String pluginId,
        final long pluginGeneration,
        final PermissionChecker permissionChecker,
        final HostSnapshotSource source,
        final PaletteAppearanceCoordinator coordinator
    ) {
        this(
            pluginId,
            pluginGeneration,
            permissionChecker,
            source,
            coordinator,
            null,
            () -> 0L,
            () -> 0L,
            NativeLabelColorAuthoring.unavailable()
        );
    }

    RuntimeModelAppearanceAccess(
        final String pluginId,
        final long pluginGeneration,
        final PermissionChecker permissionChecker,
        final HostSnapshotSource source,
        final PaletteAppearanceCoordinator coordinator,
        final NativeLabelColorAuthoring nativeAuthoring
    ) {
        this(
            pluginId,
            pluginGeneration,
            permissionChecker,
            source,
            coordinator,
            null,
            () -> 0L,
            () -> 0L,
            nativeAuthoring
        );
    }

    RuntimeModelAppearanceAccess(
        final String pluginId,
        final long pluginGeneration,
        final PermissionChecker permissionChecker,
        final HostSnapshotSource source,
        final PaletteAppearanceCoordinator coordinator,
        final LongSupplier hostGeneration,
        final LongSupplier providerGeneration,
        final NativeLabelColorAuthoring nativeAuthoring
    ) {
        this(
            pluginId,
            pluginGeneration,
            permissionChecker,
            source,
            coordinator,
            null,
            hostGeneration,
            providerGeneration,
            nativeAuthoring
        );
    }

    public static RuntimeModelAppearanceAccess create(
        final String pluginId,
        final long pluginGeneration,
        final PermissionChecker permissionChecker,
        final HostSnapshotSource source,
        final PaletteAppearanceCoordinator coordinator,
        final LongSupplier currentModelGeneration,
        final LongSupplier hostGeneration,
        final LongSupplier providerGeneration,
        final NativeLabelColorAuthoring nativeAuthoring
    ) {
        return new RuntimeModelAppearanceAccess(
            pluginId,
            pluginGeneration,
            permissionChecker,
            source,
            coordinator,
            currentModelGeneration,
            hostGeneration,
            providerGeneration,
            nativeAuthoring
        );
    }

    RuntimeModelAppearanceAccess(
        final String pluginId,
        final long pluginGeneration,
        final PermissionChecker permissionChecker,
        final HostSnapshotSource source,
        final PaletteAppearanceCoordinator coordinator,
        final LongSupplier currentModelGeneration,
        final LongSupplier hostGeneration,
        final LongSupplier providerGeneration,
        final NativeLabelColorAuthoring nativeAuthoring
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        requireGeneration(pluginGeneration, "pluginGeneration");
        this.pluginGeneration = pluginGeneration;
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.source = Objects.requireNonNull(source, "source");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.currentModelGeneration = currentModelGeneration;
        this.hostGeneration = Objects.requireNonNull(hostGeneration, "hostGeneration");
        this.providerGeneration = Objects.requireNonNull(providerGeneration, "providerGeneration");
        this.nativeAuthoring = Objects.requireNonNull(nativeAuthoring, "nativeAuthoring");
    }

    /** Enrolls plugin cleanup; closing the scope invalidates every returned facade. */
    public void bind(final DisposableScope scope) {
        Objects.requireNonNull(scope, "scope").register(this::close);
    }

    public PartAppearance part(final Part part, final long modelGeneration) {
        final Bound bound = bound(part, modelGeneration, "part");
        return bound == null ? PartAppearance.unavailable() : new PartFacade(bound.scope(), bound.id());
    }

    public DeformerAppearance deformer(final Deformer deformer, final long modelGeneration) {
        final Bound bound = bound(deformer, modelGeneration, "deformer");
        return bound == null ? DeformerAppearance.unavailable()
            : new DeformerFacade(bound.scope(), bound.id());
    }

    public ParameterAppearance parameter(final Parameter parameter, final long modelGeneration) {
        final Bound bound = bound(parameter, modelGeneration, "parameter");
        return bound == null ? ParameterAppearance.unavailable()
            : new ParameterFacade(bound.scope(), bound.id());
    }

    public ParameterGroupAppearance parameterGroup(
        final ParameterGroup group,
        final long modelGeneration
    ) {
        final Bound bound = bound(group, modelGeneration, "parameter group");
        return bound == null ? ParameterGroupAppearance.unavailable()
            : new ParameterGroupFacade(bound.scope(), bound.id());
    }

    public DrawableAppearance drawable(final Drawable drawable, final long modelGeneration) {
        // No verified ArtMesh renderer seam exists. Do not manufacture a palette projection.
        return DrawableAppearance.unavailable();
    }

    public PartAppearance part(
        final String modelId,
        final String partId,
        final long modelGeneration
    ) {
        final Bound bound = bound(modelId, modelGeneration, partId, "part");
        return bound == null ? PartAppearance.unavailable() : new PartFacade(bound.scope(), bound.id());
    }

    public DeformerAppearance deformer(
        final String modelId,
        final String deformerId,
        final long modelGeneration
    ) {
        final Bound bound = bound(modelId, modelGeneration, deformerId, "deformer");
        return bound == null ? DeformerAppearance.unavailable()
            : new DeformerFacade(bound.scope(), bound.id());
    }

    public ParameterAppearance parameter(
        final String modelId,
        final String parameterId,
        final long modelGeneration
    ) {
        final Bound bound = bound(modelId, modelGeneration, parameterId, "parameter");
        return bound == null ? ParameterAppearance.unavailable()
            : new ParameterFacade(bound.scope(), bound.id());
    }

    public ParameterGroupAppearance parameterGroup(
        final String modelId,
        final String groupId,
        final long modelGeneration
    ) {
        final Bound bound = bound(modelId, modelGeneration, groupId, "parameter group");
        return bound == null ? ParameterGroupAppearance.unavailable()
            : new ParameterGroupFacade(bound.scope(), bound.id());
    }

    public DrawableAppearance drawable(
        final String modelId,
        final String drawableId,
        final long modelGeneration
    ) {
        // No verified ArtMesh renderer seam exists. Do not manufacture a palette projection.
        bound(modelId, modelGeneration, drawableId, "drawable");
        return DrawableAppearance.unavailable();
    }

    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            coordinator.removePlugin(pluginId, pluginGeneration);
        }
    }

    private Bound bound(final Part part, final long modelGeneration, final String kind) {
        Objects.requireNonNull(part, kind);
        return bind(null, modelGeneration, () -> part.id().value());
    }

    private Bound bound(final Deformer deformer, final long modelGeneration, final String kind) {
        Objects.requireNonNull(deformer, kind);
        return bind(null, modelGeneration, () -> deformer.id().value());
    }

    private Bound bound(final Parameter parameter, final long modelGeneration, final String kind) {
        Objects.requireNonNull(parameter, kind);
        return bind(null, modelGeneration, () -> parameter.id().value());
    }

    private Bound bound(final ParameterGroup group, final long modelGeneration, final String kind) {
        Objects.requireNonNull(group, kind);
        return bind(null, modelGeneration, () -> group.id().value());
    }

    private Bound bound(
        final String modelId,
        final long modelGeneration,
        final String objectId,
        final String kind
    ) {
        return bind(modelId, modelGeneration, () -> objectId);
    }

    private Bound bind(
        final String expectedModelId,
        final long modelGeneration,
        final java.util.function.Supplier<String> idSupplier
    ) {
        if (!active.get()) return null;
        final Optional<PaletteAppearanceCoordinator.Scope> scope = captureScope(
            expectedModelId, modelGeneration
        );
        if (scope.isEmpty()) return null;
        try {
            return new Bound(scope.orElseThrow(), requireText(idSupplier.get(), "objectId"));
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private Optional<PaletteAppearanceCoordinator.Scope> captureScope(
        final long modelGeneration
    ) {
        return captureScope(null, modelGeneration);
    }

    private Optional<PaletteAppearanceCoordinator.Scope> captureScope(
        final String expectedModelId,
        final long modelGeneration
    ) {
        if (!active.get()) return Optional.empty();
        try {
            if (currentModelGeneration != null
                && currentModelGeneration.getAsLong() != modelGeneration) {
                return Optional.empty();
            }
            if (!source.isHostPresent()) return deactivate();
            final Optional<HostSnapshotSource.HostDocument> document = source.activeDocument();
            final Optional<HostSnapshotSource.HostModel> model = source.activeModel();
            if (document.isEmpty() || model.isEmpty()) return deactivate();
            final HostSnapshotSource.HostDocument currentDocument = document.orElseThrow();
            final HostSnapshotSource.HostModel currentModel = model.orElseThrow();
            if (currentDocument.kind() != DocumentKind.MODEL
                || currentDocument.model().isEmpty()
                || !currentDocument.model().orElseThrow().modelId().equals(currentModel.modelId())) {
                return deactivate();
            }
            if (expectedModelId != null && !expectedModelId.equals(currentModel.modelId())) {
                return Optional.empty();
            }
            final String contentId = currentDocument.contentId().orElse(currentDocument.documentId());
            final PaletteAppearanceCoordinator.Scope scope = new PaletteAppearanceCoordinator.Scope(
                contentId,
                source.invalidationToken(),
                currentModel.modelId(),
                modelGeneration,
                hostGeneration.getAsLong(),
                providerGeneration.getAsLong()
            );
            coordinator.reconcile(scope);
            return Optional.of(scope);
        } catch (RuntimeException unavailable) {
            coordinator.deactivate();
            return Optional.empty();
        }
    }

    private Optional<PaletteAppearanceCoordinator.Scope> deactivate() {
        coordinator.deactivate();
        return Optional.empty();
    }

    private PaletteAppearanceCoordinator.Scope requireScope(
        final PaletteAppearanceCoordinator.Scope expected
    ) {
        if (!active.get()) throw stale();
        final PaletteAppearanceCoordinator.Scope current = captureScope(
            expected.modelId(), expected.modelGeneration()
        ).orElseThrow(this::stale);
        if (!expected.equals(current)) throw stale();
        return current;
    }

    private void readPermission(final String operation) {
        permissionChecker.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, operation);
    }

    private void appearanceModifyPermission(final String operation) {
        permissionChecker.check(PermissionIds.TURBOISM_UI_APPEARANCE_MODIFY, operation);
    }

    private void modelWritePermission(final String operation) {
        permissionChecker.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, operation);
    }

    private IllegalStateException stale() {
        return new IllegalStateException("Model appearance facade is stale or unavailable.");
    }

    private PaletteEntry entry(
        final PaletteAppearanceCoordinator.Scope scope,
        final PaletteAppearanceCoordinator.Palette palette,
        final String objectId
    ) {
        return new Entry(scope, palette, objectId);
    }

    private final class Entry implements PaletteEntry {
        private final PaletteAppearanceCoordinator.Scope scope;
        private final PaletteAppearanceCoordinator.Palette palette;
        private final String objectId;

        private Entry(
            final PaletteAppearanceCoordinator.Scope scope,
            final PaletteAppearanceCoordinator.Palette palette,
            final String objectId
        ) {
            this.scope = scope;
            this.palette = palette;
            this.objectId = objectId;
        }

        @Override
        public Registration overrideFontSize(final float points) {
            return register(PaletteAppearanceCoordinator.Property.FONT_SIZE, points,
                "model.appearance.override-font-size");
        }

        @Override
        public Registration overrideBold(final boolean bold) {
            return register(PaletteAppearanceCoordinator.Property.BOLD, bold,
                "model.appearance.override-bold");
        }

        @Override
        public Registration overrideItalic(final boolean italic) {
            return register(PaletteAppearanceCoordinator.Property.ITALIC, italic,
                "model.appearance.override-italic");
        }

        @Override
        public Registration overrideTextColor(final UiColor color) {
            return register(PaletteAppearanceCoordinator.Property.TEXT_COLOR,
                Objects.requireNonNull(color, "color"), "model.appearance.override-text-color");
        }

        @Override
        public Registration overrideBackgroundColor(final UiColor color) {
            return register(PaletteAppearanceCoordinator.Property.BACKGROUND_COLOR,
                Objects.requireNonNull(color, "color"), "model.appearance.override-background-color");
        }

        @Override
        public PaletteEntryState resolved() {
            readPermission("model.appearance.resolved");
            final PaletteAppearanceCoordinator.Scope current = requireScope(scope);
            return coordinator.resolve(current, palette, objectId);
        }

        @Override
        public Optional<PaletteEntryState> actual() {
            readPermission("model.appearance.actual");
            requireScope(scope);
            return Optional.empty();
        }

        private Registration register(
            final PaletteAppearanceCoordinator.Property property,
            final Object value,
            final String operation
        ) {
            appearanceModifyPermission(operation);
            final PaletteAppearanceCoordinator.Scope current = requireScope(scope);
            return coordinator.register(
                pluginId,
                pluginGeneration,
                current,
                palette,
                objectId,
                property,
                value
            );
        }
    }

    private final class PartFacade implements PartAppearance {
        private final PaletteAppearanceCoordinator.Scope scope;
        private final String objectId;

        private PartFacade(final PaletteAppearanceCoordinator.Scope scope, final String objectId) {
            this.scope = scope;
            this.objectId = objectId;
        }

        @Override
        public Optional<PaletteEntry> partPaletteEntry() {
            requireScope(scope);
            return Optional.of(entry(scope, PaletteAppearanceCoordinator.Palette.PART, objectId));
        }

        @Override
        public Optional<NativeLabelColorState> nativeLabelColor() {
            readPermission("model.part.native-label-color.read");
            requireScope(scope);
            try {
                return Optional.of(Objects.requireNonNull(nativeAuthoring.readNativeLabelColor(
                    new NativeLabelColorTarget(NativeLabelColorTarget.Palette.PART, objectId)
                ), "native label-color state"));
            } catch (UnsupportedOperationException unavailable) {
                return Optional.empty();
            }
        }

        @Override
        public void setNativeLabelColor(final NativeLabelColor color) {
            modelWritePermission("model.part.native-label-color.write");
            requireScope(scope);
            nativeAuthoring.setNativeLabelColor(
                new NativeLabelColorTarget(NativeLabelColorTarget.Palette.PART, objectId),
                Objects.requireNonNull(color, "color")
            );
        }
    }

    private final class DeformerFacade implements DeformerAppearance {
        private final PaletteAppearanceCoordinator.Scope scope;
        private final String objectId;

        private DeformerFacade(final PaletteAppearanceCoordinator.Scope scope, final String objectId) {
            this.scope = scope;
            this.objectId = objectId;
        }

        @Override
        public Optional<PaletteEntry> partPaletteEntry() {
            requireScope(scope);
            return Optional.of(entry(scope, PaletteAppearanceCoordinator.Palette.DEFORMER_PART, objectId));
        }

        @Override
        public Optional<PaletteEntry> deformerPaletteEntry() {
            requireScope(scope);
            return Optional.of(entry(scope, PaletteAppearanceCoordinator.Palette.DEFORMER, objectId));
        }

        @Override
        public Optional<NativeLabelColorState> nativeLabelColor() {
            readPermission("model.deformer.native-label-color.read");
            requireScope(scope);
            try {
                return Optional.of(Objects.requireNonNull(nativeAuthoring.readNativeLabelColor(
                    new NativeLabelColorTarget(NativeLabelColorTarget.Palette.DEFORMER, objectId)
                ), "native label-color state"));
            } catch (UnsupportedOperationException unavailable) {
                return Optional.empty();
            }
        }

        @Override
        public void setNativeLabelColor(final NativeLabelColor color) {
            modelWritePermission("model.deformer.native-label-color.write");
            requireScope(scope);
            nativeAuthoring.setNativeLabelColor(
                new NativeLabelColorTarget(NativeLabelColorTarget.Palette.DEFORMER, objectId),
                Objects.requireNonNull(color, "color")
            );
        }
    }

    private final class ParameterFacade implements ParameterAppearance {
        private final PaletteAppearanceCoordinator.Scope scope;
        private final String objectId;

        private ParameterFacade(final PaletteAppearanceCoordinator.Scope scope, final String objectId) {
            this.scope = scope;
            this.objectId = objectId;
        }

        @Override
        public Optional<PaletteEntry> parameterPaletteEntry() {
            requireScope(scope);
            return Optional.of(entry(scope, PaletteAppearanceCoordinator.Palette.PARAMETER, objectId));
        }
    }

    private final class ParameterGroupFacade implements ParameterGroupAppearance {
        private final PaletteAppearanceCoordinator.Scope scope;
        private final String objectId;

        private ParameterGroupFacade(final PaletteAppearanceCoordinator.Scope scope, final String objectId) {
            this.scope = scope;
            this.objectId = objectId;
        }

        @Override
        public Optional<PaletteEntry> parameterPaletteEntry() {
            requireScope(scope);
            return Optional.of(entry(scope, PaletteAppearanceCoordinator.Palette.PARAMETER_GROUP, objectId));
        }

        @Override
        public Optional<NativeLabelColorState> nativeLabelColor() {
            readPermission("model.parameter-group.native-label-color.read");
            requireScope(scope);
            try {
                return Optional.of(Objects.requireNonNull(nativeAuthoring.readNativeLabelColor(
                    new NativeLabelColorTarget(NativeLabelColorTarget.Palette.PARAMETER_GROUP, objectId)
                ), "native label-color state"));
            } catch (UnsupportedOperationException unavailable) {
                return Optional.empty();
            }
        }

        @Override
        public void setNativeLabelColor(final NativeLabelColor color) {
            modelWritePermission("model.parameter-group.native-label-color.write");
            requireScope(scope);
            nativeAuthoring.setNativeLabelColor(
                new NativeLabelColorTarget(NativeLabelColorTarget.Palette.PARAMETER_GROUP, objectId),
                Objects.requireNonNull(color, "color")
            );
        }
    }

    private record Bound(PaletteAppearanceCoordinator.Scope scope, String id) { }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static void requireGeneration(final long value, final String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
    }
}
