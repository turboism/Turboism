package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorPartBasicSettingsSelectorContract;
import dev.turboism.mapping.verification.EditorPartNameSelectorContract;
import dev.turboism.mapping.verification.EditorPartOpacity52SelectorContract;
import dev.turboism.mapping.verification.EditorPartOpacitySelectorContract;
import dev.turboism.mapping.verification.EditorPartTreeSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/** Exact, generation-bound Editor authoring projection for Part opacity. */
final class EditorPartOpacityAccess {

    private static final String ACTION_NAME = "Turboism: Set Part Opacity";
    private static final String NAME_ACTION_NAME = "Turboism: Set Part Name";
    private static final String SHORT_NAME_ACTION_NAME = "Turboism: Set Part Short Name";
    private static final String VISIBILITY_ACTION_NAME = "Turboism: Set Part Visibility";
    private static final String LOCK_ACTION_NAME = "Turboism: Set Part Lock";
    private static final String COLOR_ACTION_NAME = "Turboism: Set Part Edit Color";
    private static final String SKETCH_ACTION_NAME = "Turboism: Set Part Sketch";
    private static final String ORDER_ACTION_NAME = "Turboism: Set Part Default Order";

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorPartOpacityAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    Parts parts(final String identity, final Object source, final Object model) {
        requireProjectionAuthorization();
        modelGuard.requireCurrent(identity, model);
        return new EditorParts(identity, source, model);
    }

    private boolean opacityAuthorized() {
        if (isCubism52()) {
            return resolver.authorizesFeature(
                EditorPartOpacity52SelectorContract.ADAPTER_SLICE_ID,
                EditorPartOpacity52SelectorContract.CAPABILITY_ID,
                EditorPartOpacity52SelectorContract.REQUIRED_ALIASES
            );
        }
        return resolver.authorizesFeature(
            EditorPartOpacitySelectorContract.ADAPTER_SLICE_ID,
            EditorPartOpacitySelectorContract.CAPABILITY_ID,
            EditorPartOpacitySelectorContract.REQUIRED_ALIASES
        );
    }

    private boolean opacityWriteAuthorized() {
        return resolver.authorizesFeature(
            EditorPartOpacitySelectorContract.ADAPTER_SLICE_ID,
            EditorPartOpacitySelectorContract.CAPABILITY_ID,
            EditorPartOpacitySelectorContract.REQUIRED_ALIASES
        );
    }

    private boolean isCubism52() {
        return resolver.isExactCubismVersion(EditorPartOpacity52SelectorContract.CUBISM_VERSION);
    }

    private boolean nameAuthorized() {
        return resolver.authorizesFeature(
            EditorPartNameSelectorContract.ADAPTER_SLICE_ID,
            EditorPartNameSelectorContract.CAPABILITY_ID,
            EditorPartNameSelectorContract.REQUIRED_ALIASES
        );
    }

    private boolean nameWriteAuthorized() {
        return resolver.authorizesFeature(
            EditorPartNameSelectorContract.ADAPTER_SLICE_ID,
            EditorPartNameSelectorContract.WRITE_CAPABILITY_ID,
            EditorPartNameSelectorContract.WRITE_REQUIRED_ALIASES
        );
    }


    private boolean treeAuthorized() {
        return resolver.authorizesFeature(
            EditorPartTreeSelectorContract.ADAPTER_SLICE_ID,
            EditorPartTreeSelectorContract.CAPABILITY_ID,
            EditorPartTreeSelectorContract.REQUIRED_ALIASES
        );
    }

    private boolean basicSettingsReadAuthorized() {
        return resolver.authorizesFeature(
            EditorPartBasicSettingsSelectorContract.ADAPTER_SLICE_ID,
            EditorPartBasicSettingsSelectorContract.READ_CAPABILITY_ID,
            EditorPartBasicSettingsSelectorContract.READ_REQUIRED_ALIASES
        );
    }

    private boolean basicSettingsWriteAuthorized() {
        return resolver.authorizesFeature(
            EditorPartBasicSettingsSelectorContract.ADAPTER_SLICE_ID,
            EditorPartBasicSettingsSelectorContract.WRITE_CAPABILITY_ID,
            EditorPartBasicSettingsSelectorContract.WRITE_REQUIRED_ALIASES
        );
    }

    private List<PartBinding> bindings(final Object source, final Object model) {
        final Object rawSources = resolver.invoke("cubism.editor-model.model-source.parts", source);
        final Object rawParts = resolver.invoke("cubism.editor-model.model.parts", model);
        if (!(rawSources instanceof List<?> sources) || !(rawParts instanceof List<?> parts)) {
            throw unavailable("Editor Part collections are unavailable.");
        }
        final IdentityHashMap<Object, Object> instancesBySource = new IdentityHashMap<>();
        for (Object part : parts) {
            if (!resolver.isInstance("cubism.editor-model.part.class", part)) {
                throw unavailable("Editor Part collection contains an invalid value.");
            }
            final Object partSource = resolver.invoke("cubism.editor-model.part.source", part);
            if (instancesBySource.put(partSource, part) != null) {
                throw unavailable("Editor Part source is bound to multiple active instances.");
            }
        }
        return sources.stream().map(partSource -> {
            if (!resolver.isInstance("cubism.editor-model.part-source.class", partSource)) {
                throw unavailable("Editor Part source collection contains an invalid value.");
            }
            final Object part = instancesBySource.get(partSource);
            if (part == null) {
                throw unavailable("Editor Part source has no active model instance.");
            }
            return new PartBinding(partId(partSource), partSource, part);
        }).toList();
    }

    private PartBinding binding(final Object source, final Object model, final PartId id) {
        return bindings(source, model).stream()
            .filter(value -> value.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("Cubism Part is absent: " + id.value()));
    }

    private PartId partId(final Object partSource) {
        final Object hostId = resolver.invoke("cubism.editor-model.part-source.id", partSource);
        final Object value = resolver.invoke("cubism.editor-model.part-id.value", hostId);
        if (!(value instanceof String id) || id.isBlank()) {
            throw unavailable("Editor Part identity is unavailable.");
        }
        return new PartId(id);
    }

    private float opacity(final Object part) {
        requireOpacityAuthorization();
        final Object value = isCubism52()
            ? resolver.invoke("cubism.editor-model.part.parts-opacity", part)
            : resolver.invoke("cubism.editor-model.part-form.opacity", currentForm(part));
        if (!(value instanceof Float opacity) || !Float.isFinite(opacity)) {
            throw unavailable("Editor Part authoring opacity is unavailable.");
        }
        return opacity;
    }

    private String name(final PartBinding binding) {
        if (!nameAuthorized()) {
            throw new UnsupportedOperationException(
                "Part display-name reading is unavailable without exact verified host evidence."
            );
        }
        final Object value = resolver.invoke(
            "cubism.editor-model.part-source.local-name",
            binding.source()
        );
        if (value == null) return binding.id().value();
        if (!(value instanceof String name)) {
            throw unavailable("Editor Part display name is invalid.");
        }
        return name.isBlank() ? binding.id().value() : name;
    }

    private Optional<String> shortName(final PartBinding binding) {
        requireBasicSettingsReadAuthorization();
        final Object value = resolver.invoke(
            "cubism.editor-model.part-source.local-name",
            binding.source()
        );
        if (value == null) return Optional.empty();
        if (!(value instanceof String name)) {
            throw unavailable("Editor Part short name is invalid.");
        }
        return name.isBlank() ? Optional.empty() : Optional.of(name);
    }

    private boolean booleanSetting(
        final PartBinding binding,
        final String alias,
        final String message
    ) {
        requireBasicSettingsReadAuthorization();
        final Object value = resolver.invoke(alias, binding.source());
        if (!(value instanceof Boolean flag)) throw unavailable(message);
        return flag;
    }

    private int defaultOrder(final PartBinding binding) {
        requireBasicSettingsReadAuthorization();
        final Object value = resolver.invoke(
            "cubism.editor-model.part-source.default-order",
            binding.source()
        );
        if (!(value instanceof Integer order)) {
            throw unavailable("Editor Part default order is invalid.");
        }
        return order;
    }

    private Optional<Color> editColor(final PartBinding binding) {
        requireBasicSettingsReadAuthorization();
        final Object value = resolver.invoke(
            "cubism.editor-model.part-source.edit-color",
            binding.source()
        );
        if (value == null) return Optional.empty();
        if (!resolver.isInstance("cubism.editor-model.color.class", value)) {
            throw unavailable("Editor Part edit color is invalid.");
        }
        return Optional.of(new Color(
            colorComponent("cubism.editor-model.color.red", value),
            colorComponent("cubism.editor-model.color.green", value),
            colorComponent("cubism.editor-model.color.blue", value),
            colorComponent("cubism.editor-model.color.alpha", value)
        ));
    }

    private float colorComponent(final String alias, final Object color) {
        final Object value = resolver.invoke(alias, color);
        if (!(value instanceof Number number) || !Float.isFinite(number.floatValue())) {
            throw unavailable("Editor Part edit color component is invalid.");
        }
        return number.floatValue();
    }

    private void setName(
        final String identity,
        final Object source,
        final Object model,
        final PartId id,
        final Object expectedSource,
        final Object expectedPart,
        final String requestedName
    ) {
        final String name = Objects.requireNonNull(requestedName, "name");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        requireNameWriteAuthorization();
        final PartBinding current = requireCurrentPart(
            identity, source, model, id, expectedSource, expectedPart
        );
        if (name.equals(name(current))) return;
        writePartSource(
            source,
            current,
            NAME_ACTION_NAME,
            () -> resolver.invoke("cubism.editor-model.part-source.set-local-name", current.source(), name)
        );
        requireCurrentPart(identity, source, model, id, expectedSource, expectedPart);
    }

    private Object currentForm(final Object part) {
        final Object form = resolver.invoke("cubism.editor-model.part.current-keyform", part);
        if (!resolver.isInstance("cubism.editor-model.part-form.class", form)) {
            throw unavailable("Editor Part has no current authoring keyform.");
        }
        return form;
    }

    private void setOpacity(
        final String identity,
        final Object source,
        final Object model,
        final PartId id,
        final Object expectedSource,
        final Object expectedPart,
        final float opacity
    ) {
        if (!Float.isFinite(opacity)) {
            throw new IllegalArgumentException("opacity must be finite");
        }
        requireOpacityWriteAuthorization();
        final PartBinding current = requireCurrentPart(
            identity, source, model, id, expectedSource, expectedPart
        );
        if (Float.compare(opacity(current.part()), opacity) == 0) {
            return;
        }
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode.begin", editMode, ACTION_NAME
        );
        boolean completed = false;
        try {
            final Object handler = resolver.invoke(
                "cubism.editor-model.part-source.handler", current.source()
            );
            if (!resolver.isInstance("cubism.editor-model.part-handler.class", handler)) {
                throw unavailable("Editor Part Undo handler is unavailable.");
            }
            final Object partUndo = resolver.invoke(
                "cubism.editor-model.part-handler.create-undo-for-all-edit",
                handler,
                ACTION_NAME
            );
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, partUndo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the Part opacity Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke("cubism.editor-model.model-source.update-instances", source);
                    refresh(app);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", partUndo, listener);
            resolver.invoke(
                "cubism.editor-model.part-form.set-opacity",
                currentForm(current.part()),
                Float.valueOf(opacity)
            );
            resolver.invoke("cubism.editor-model.model-source.update-instances", source);
            refresh(app);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode,
                Boolean.valueOf(!completed),
                null
            );
        }
        requireCurrentPart(identity, source, model, id, expectedSource, expectedPart);
    }

    private void refresh(final Object app) {
        final Object completePack = resolver.invoke(
            "cubism.editor-model.app-controller.complete-pack", app
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.update-part-palette",
            completePack,
            Boolean.TRUE
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.repaint-canvas",
            completePack,
            Boolean.TRUE
        );
    }

    private void writePartSource(
        final Object source,
        final PartBinding current,
        final String actionName,
        final Runnable mutation
    ) {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode", document
        );
        final Object edit = resolver.invoke("cubism.editor-model.edit-mode.begin", editMode, actionName);
        boolean completed = false;
        try {
            final Object partUndo = resolver.invoke(
                "cubism.editor-model.part-source.create-undo-for-basic-settings",
                current.source(),
                actionName
            );
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add", edit, partUndo, Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the Part authoring Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    resolver.invoke(
                        "cubism.editor-model.model-source.update-visible-lock-hierarchy",
                        source
                    );
                    resolver.invoke("cubism.editor-model.model-source.update-instances", source);
                    refresh(app);
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", partUndo, listener);
            mutation.run();
            resolver.invoke(
                "cubism.editor-model.model-source.update-visible-lock-hierarchy",
                source
            );
            resolver.invoke("cubism.editor-model.model-source.update-instances", source);
            refresh(app);
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end", editMode, Boolean.valueOf(!completed), null
            );
        }
    }

    private PartBinding requireCurrentPart(
        final String identity,
        final Object source,
        final Object model,
        final PartId id,
        final Object expectedSource,
        final Object expectedPart
    ) {
        modelGuard.requireCurrent(identity, model);
        final PartBinding current = binding(source, model, id);
        if (current.source() != expectedSource || current.part() != expectedPart) {
            throw unavailable("Cubism Part reference is stale for the active Editor model generation.");
        }
        return current;
    }

    private int parentIndex(final Object source, final Object model, final Object partSource) {
        return parentIndex(bindings(source, model), partSource);
    }

    private int parentIndex(final List<PartBinding> parts, final Object partSource) {
        final Object parent = resolver.invoke("cubism.editor-model.part-source.parent", partSource);
        return parent == null ? -1 : partIndex(parts, parent);
    }

    private static int partIndex(final List<PartBinding> parts, final Object partSource) {
        for (int index = 0; index < parts.size(); index++) {
            if (parts.get(index).source() == partSource) return index;
        }
        throw unavailable("Editor Part is outside the active Part collection.");
    }

    private void requireProjectionAuthorization() {
        if (!opacityAuthorized() && !nameAuthorized() && !treeAuthorized()
            && !basicSettingsReadAuthorized()) {
            throw new UnsupportedOperationException(
                "Part access is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireTreeAuthorization() {
        if (!treeAuthorized()) {
            throw new UnsupportedOperationException(
                "Part index and tree reading are unavailable without exact verified host evidence."
            );
        }
    }

    private void requireOpacityAuthorization() {
        if (!opacityAuthorized()) {
            throw new UnsupportedOperationException(
                "Part opacity reading is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireOpacityWriteAuthorization() {
        if (!opacityWriteAuthorized()) {
            throw new UnsupportedOperationException(
                "Part opacity writing is unavailable on this exact Cubism version."
            );
        }
    }

    private void requireNameWriteAuthorization() {
        if (!nameWriteAuthorized()) {
            throw new UnsupportedOperationException(
                "Part display-name writing is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireBasicSettingsReadAuthorization() {
        if (!basicSettingsReadAuthorized()) {
            throw new UnsupportedOperationException(
                "Part basic-setting reading is unavailable without exact verified host evidence."
            );
        }
    }

    private void requireBasicSettingsWriteAuthorization() {
        if (!basicSettingsWriteAuthorized()) {
            throw new UnsupportedOperationException(
                "Part basic-setting writing is unavailable without exact verified host evidence."
            );
        }
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }

    private final class EditorParts implements Parts {
        private final String identity;
        private final Object source;
        private final Object model;

        private EditorParts(final String identity, final Object source, final Object model) {
            this.identity = identity;
            this.source = source;
            this.model = model;
        }

        @Override public List<Part> all() {
            modelGuard.requireCurrent(identity, model);
            return bindings(source, model).stream()
                .map(value -> (Part) new EditorPart(
                    identity, source, model, value.id(), value.source(), value.part()
                ))
                .toList();
        }

        @Override public Part find(final PartId id) {
            modelGuard.requireCurrent(identity, model);
            final PartBinding value = binding(source, model, Objects.requireNonNull(id, "id"));
            return new EditorPart(identity, source, model, value.id(), value.source(), value.part());
        }
    }

    private final class EditorPart implements Part {
        private final String identity;
        private final Object source;
        private final Object model;
        private final PartId id;
        private final Object expectedSource;
        private final Object expectedPart;

        private EditorPart(
            final String identity,
            final Object source,
            final Object model,
            final PartId id,
            final Object expectedSource,
            final Object expectedPart
        ) {
            this.identity = identity;
            this.source = source;
            this.model = model;
            this.id = id;
            this.expectedSource = expectedSource;
            this.expectedPart = expectedPart;
        }

        private PartBinding current() {
            return requireCurrentPart(
                identity, source, model, id, expectedSource, expectedPart
            );
        }

        @Override public PartId id() { current(); return id; }
        @Override public int index() {
            final PartBinding value = current();
            requireTreeAuthorization();
            return partIndex(bindings(source, model), value.source());
        }
        @Override public Optional<PartId> parentId() {
            final PartBinding value = current();
            requireTreeAuthorization();
            final List<PartBinding> parts = bindings(source, model);
            final int parent = EditorPartOpacityAccess.this.parentIndex(parts, value.source());
            return parent < 0 ? Optional.empty() : Optional.of(parts.get(parent).id());
        }
        @Override public List<PartId> childIds() {
            final PartBinding value = current();
            requireTreeAuthorization();
            final List<PartBinding> parts = bindings(source, model);
            final int index = partIndex(parts, value.source());
            return parts.stream()
                .filter(candidate -> EditorPartOpacityAccess.this.parentIndex(parts, candidate.source()) == index)
                .map(PartBinding::id)
                .toList();
        }
        @Override public String name() { return EditorPartOpacityAccess.this.name(current()); }
        @Override public Optional<String> shortName() {
            return EditorPartOpacityAccess.this.shortName(current());
        }
        @Override public void setShortName(final Optional<String> value) {
            requireBasicSettingsWriteAuthorization();
            final Optional<String> requested = Objects.requireNonNull(value, "value");
            if (requested.filter(String::isBlank).isPresent()) {
                throw new IllegalArgumentException("short name must not be blank");
            }
            final PartBinding current = current();
            if (EditorPartOpacityAccess.this.shortName(current).equals(requested)) return;
            writePartSource(
                source,
                current,
                SHORT_NAME_ACTION_NAME,
                () -> resolver.invoke(
                    "cubism.editor-model.part-source.set-local-name",
                    current.source(),
                    requested.orElse(null)
                )
            );
            current();
        }
        @Override public boolean visible() {
            return booleanSetting(
                current(),
                "cubism.editor-model.parameter-controllable-source.visible",
                "Editor Part visibility is invalid."
            );
        }
        @Override public void setVisible(final boolean value) {
            requireBasicSettingsWriteAuthorization();
            final PartBinding current = current();
            if (visible() == value) return;
            writePartSource(
                source,
                current,
                VISIBILITY_ACTION_NAME,
                () -> resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.set-visible",
                    current.source(),
                    Boolean.valueOf(value)
                )
            );
            current();
        }
        @Override public boolean visibleInHierarchy() {
            return booleanSetting(
                current(),
                "cubism.editor-model.parameter-controllable-source.visible-in-hierarchy",
                "Editor Part effective visibility is invalid."
            );
        }
        @Override public boolean locked() {
            return booleanSetting(
                current(),
                "cubism.editor-model.parameter-controllable-source.locked",
                "Editor Part lock state is invalid."
            );
        }
        @Override public void setLocked(final boolean value) {
            requireBasicSettingsWriteAuthorization();
            final PartBinding current = current();
            if (locked() == value) return;
            writePartSource(
                source,
                current,
                LOCK_ACTION_NAME,
                () -> resolver.invoke(
                    "cubism.editor-model.parameter-controllable-source.set-locked",
                    current.source(),
                    Boolean.valueOf(value)
                )
            );
            current();
        }
        @Override public boolean lockedInHierarchy() {
            return booleanSetting(
                current(),
                "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
                "Editor Part effective lock state is invalid."
            );
        }
        @Override public Optional<Color> editColor() {
            return EditorPartOpacityAccess.this.editColor(current());
        }
        @Override public void setEditColor(final Optional<Color> value) {
            requireBasicSettingsWriteAuthorization();
            final Optional<Color> requested = Objects.requireNonNull(value, "value");
            final PartBinding current = current();
            if (EditorPartOpacityAccess.this.editColor(current).equals(requested)) return;
            final Object hostColor = requested
                .map(color -> resolver.construct(
                    "cubism.editor-model.color.create",
                    Float.valueOf(color.red()),
                    Float.valueOf(color.green()),
                    Float.valueOf(color.blue()),
                    Float.valueOf(color.alpha())
                ))
                .orElse(null);
            writePartSource(
                source,
                current,
                COLOR_ACTION_NAME,
                () -> resolver.invoke(
                    "cubism.editor-model.part-source.set-edit-color",
                    current.source(),
                    hostColor
                )
            );
            current();
        }
        @Override public boolean sketch() {
            return booleanSetting(
                current(),
                "cubism.editor-model.part-source.sketch",
                "Editor Part sketch state is invalid."
            );
        }
        @Override public void setSketch(final boolean value) {
            requireBasicSettingsWriteAuthorization();
            final PartBinding current = current();
            if (sketch() == value) return;
            writePartSource(
                source,
                current,
                SKETCH_ACTION_NAME,
                () -> resolver.invoke(
                    "cubism.editor-model.part-source.set-sketch",
                    current.source(),
                    Boolean.valueOf(value)
                )
            );
            current();
        }
        @Override public int defaultOrder() {
            return EditorPartOpacityAccess.this.defaultOrder(current());
        }
        @Override public void setDefaultOrder(final int value) {
            requireBasicSettingsWriteAuthorization();
            final PartBinding current = current();
            if (EditorPartOpacityAccess.this.defaultOrder(current) == value) return;
            writePartSource(
                source,
                current,
                ORDER_ACTION_NAME,
                () -> resolver.invoke(
                    "cubism.editor-model.part-source.set-default-order",
                    current.source(),
                    Integer.valueOf(value)
                )
            );
            current();
        }
        @Override public void setName(final String name) {
            EditorPartOpacityAccess.this.setName(
                identity, source, model, id, expectedSource, expectedPart, name
            );
        }
        @Override public float getOpacity() { return opacity(current().part()); }
        @Override public int parentIndex() {
            final PartBinding value = current();
            requireTreeAuthorization();
            return EditorPartOpacityAccess.this.parentIndex(source, model, value.source());
        }
        @Override public void setOpacity(final float opacity) {
            EditorPartOpacityAccess.this.setOpacity(
                identity, source, model, id, expectedSource, expectedPart, opacity
            );
        }
    }

    private record PartBinding(PartId id, Object source, Object part) { }
}
