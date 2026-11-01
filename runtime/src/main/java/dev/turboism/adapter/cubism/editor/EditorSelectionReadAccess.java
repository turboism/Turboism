package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorObjectReadCore.list;
import static dev.turboism.adapter.cubism.editor.EditorObjectReadCore.text;
import static dev.turboism.adapter.cubism.editor.EditorObjectReadCore.unavailable;

import dev.turboism.adapter.cubism.HostSnapshotSource.HostSelection;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorSelectionReadSelectorContract;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Live Editor selection read: projects the update manager's selection GUID list onto
 * SDK object ids of the active modeling document, then includes the parameter palette's
 * independently selected rows captured by its verified selection hook.
 *
 * <p>The host list stores {@code Guid} values, not SDK ids, so every element is resolved
 * through a GUID→id map containing only requested GUIDs from the document's object sources
 * ({@code model-source.all-objects}) and, lazily and only when needed, parameter sources
 * ({@code model-source.all-parameters}). Host order is preserved verbatim; mixed and
 * multi selection are therefore unchanged. A GUID with no live source — a deleted object
 * or a kind this projection does not enumerate — has no SDK id and is dropped rather than
 * guessed onto another object.
 * Palette rows must still be attached and their source objects must belong to the active
 * model by reference identity; IDs or GUIDs shared by copied documents are insufficient.
 * Unselected sources still participate in GUID uniqueness checks, but their IDs are not read.
 * Maps live only for this read, so renames, deletions and document switches cannot leave stale IDs.
 *
 * <p>{@code HostSelection.active*Id} stays empty: no reviewed selector proves which
 * palette entry is active, and the first selected entry is not a substitute.
 *
 * <p>Reads must run on the Editor host thread; callers dispatch through
 * {@link EditorHostThread}. An absent app controller or document yields an honest empty
 * selection, while a live-document read failure propagates instead of being masked.
 */
final class EditorSelectionReadAccess {

    private final VerifiedMemberResolver resolver;
    private final java.util.function.Supplier<List<Object>> parameterPaletteSelection;

    EditorSelectionReadAccess(final VerifiedMemberResolver resolver) {
        this(resolver, dev.turboism.ui.appearance.control.NativeParameterAppearanceBridge::selectedParameterSources);
    }

    EditorSelectionReadAccess(
            final VerifiedMemberResolver resolver,
            final java.util.function.Supplier<List<Object>> parameterPaletteSelection) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.parameterPaletteSelection = Objects.requireNonNull(parameterPaletteSelection, "parameterPaletteSelection");
    }

    boolean authorized() {
        return resolver.authorizesFeature(
                EditorSelectionReadSelectorContract.ADAPTER_SLICE_ID,
                EditorSelectionReadSelectorContract.CAPABILITY_ID,
                EditorSelectionReadSelectorContract.REQUIRED_ALIASES);
    }

    HostSelection currentSelection() {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        if (app == null) {
            return HostSelection.empty();
        }
        final Object updateManager = resolver.invoke("cubism.editor-model.app-controller.update-manager", app);
        if (updateManager == null) {
            throw unavailable("Editor update manager is unavailable.");
        }
        final List<?> guidElements = list(
                resolver.invoke("cubism.editor-model.update-manager.selection-guid-list", updateManager),
                "Editor selection GUID list");
        final List<Object> paletteSources = parameterPaletteSelection.get();
        if (guidElements.isEmpty() && paletteSources.isEmpty()) {
            traceProjection(0, 0);
            return HostSelection.empty();
        }
        final Object document = resolver.invoke("cubism.editor-model.app-controller.current-document", app);
        if (!resolver.isInstance("cubism.editor-model.modeling-document.class", document)) {
            if (guidElements.isEmpty()) return HostSelection.empty();
            throw unavailable("Editor selection cannot be projected without an active modeling document.");
        }
        final Object modelSource = resolver.invoke("cubism.editor-model.modeling-document.model-source", document);
        if (modelSource == null) {
            throw unavailable("Editor model source is unavailable.");
        }
        final ArrayList<String> selectedGuids = new ArrayList<>(guidElements.size());
        for (Object element : guidElements) selectedGuids.add(guidText(element));
        final Set<String> requestedGuids = new HashSet<>(selectedGuids);
        final Map<String, String> idsByGuid =
                requestedGuids.isEmpty() ? Map.of() : controllableIdsByGuid(modelSource, requestedGuids);
        final ArrayList<String> selectedObjectIds = new ArrayList<>(guidElements.size());
        Map<String, String> parameterIdsByGuid = null;
        List<?> parameterSources = null;
        for (String guid : selectedGuids) {
            String id = idsByGuid.get(guid);
            if (id == null) {
                if (parameterIdsByGuid == null) {
                    parameterSources = parameterSources(modelSource);
                    parameterIdsByGuid = parameterIdsByGuid(parameterSources, requestedGuids);
                }
                id = parameterIdsByGuid.get(guid);
            }
            if (id != null) {
                selectedObjectIds.add(id);
            }
        }
        if (!paletteSources.isEmpty()) {
            // Native parameter palettes have an independent selection list. Match source
            // object identity, not ID/GUID text: copied models can retain both identifiers.
            final java.util.Set<Object> selectedSources =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            selectedSources.addAll(paletteSources);
            final Set<String> includedIds = new HashSet<>(selectedObjectIds);
            if (parameterSources == null) parameterSources = parameterSources(modelSource);
            for (Object source : parameterSources) {
                if (!selectedSources.contains(source)) continue;
                final Object id = resolver.invoke("cubism.editor-model.parameter-source.id", source);
                final String value = text(resolver.invoke("cubism.editor-model.id.value", id), "Editor parameter ID");
                if (includedIds.add(value)) selectedObjectIds.add(value);
            }
        }
        traceProjection(guidElements.size(), selectedObjectIds.size());
        return new HostSelection(List.copyOf(selectedObjectIds), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static void traceProjection(final int nativeGuidCount, final int matchedIdCount) {
        if ("selection-brush".equals(System.getProperty("turboism.meshEditValidation.mode"))) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "SelectionRead",
                    "nativeGuidCount=" + nativeGuidCount + " matchedIdCount=" + matchedIdCount + " unmatchedGuidCount="
                            + (nativeGuidCount - matchedIdCount));
        }
    }

    private Map<String, String> controllableIdsByGuid(final Object modelSource, final Set<String> requestedGuids) {
        final List<?> sources = list(
                resolver.invoke("cubism.editor-model.model-source.all-objects", modelSource),
                "Editor object source collection");
        final HashMap<String, String> idsByGuid = new HashMap<>();
        final Set<String> seenGuids = new HashSet<>();
        for (Object source : sources) {
            final Object guid = resolver.invoke("cubism.editor-model.parameter-controllable-source.guid", source);
            final String guidValue =
                    text(resolver.invoke("cubism.editor-model.guid.value", guid), "Editor object GUID");
            if (!seenGuids.add(guidValue)) {
                throw unavailable("Editor object GUIDs are not unique.");
            }
            if (!requestedGuids.contains(guidValue)) continue;
            final Object id = resolver.invoke("cubism.editor-model.parameter-controllable-source.id", source);
            final String idValue = text(resolver.invoke("cubism.editor-model.id.value", id), "Editor object ID");
            idsByGuid.put(guidValue, idValue);
        }
        return idsByGuid;
    }

    private List<?> parameterSources(final Object modelSource) {
        return list(
                resolver.invoke("cubism.editor-model.model-source.all-parameters", modelSource),
                "Editor parameter source collection");
    }

    private Map<String, String> parameterIdsByGuid(final List<?> sources, final Set<String> requestedGuids) {
        final HashMap<String, String> idsByGuid = new HashMap<>();
        final Set<String> seenGuids = new HashSet<>();
        for (Object source : sources) {
            final Object guid = resolver.invoke("cubism.editor-model.parameter-source.guid", source);
            final String guidValue =
                    text(resolver.invoke("cubism.editor-model.guid.value", guid), "Editor parameter GUID");
            if (!seenGuids.add(guidValue)) {
                throw unavailable("Editor parameter GUIDs are not unique.");
            }
            if (!requestedGuids.contains(guidValue)) continue;
            final Object id = resolver.invoke("cubism.editor-model.parameter-source.id", source);
            final String idValue = text(resolver.invoke("cubism.editor-model.id.value", id), "Editor parameter ID");
            idsByGuid.put(guidValue, idValue);
        }
        return idsByGuid;
    }

    private String guidText(final Object element) {
        if (element instanceof String value) {
            return value;
        }
        return text(resolver.invoke("cubism.editor-model.guid.value", element), "Editor selection GUID");
    }
}
