package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.HostSnapshotSource.HostSelection;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorSelectionReadSelectorContract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static dev.turboism.adapter.cubism.editor.EditorObjectReadAccess.list;
import static dev.turboism.adapter.cubism.editor.EditorObjectReadAccess.text;
import static dev.turboism.adapter.cubism.editor.EditorObjectReadAccess.unavailable;

/**
 * Live Editor selection read: projects the update manager's selection GUID list onto
 * SDK object ids of the active modeling document.
 *
 * <p>The host list stores {@code Guid} values, not SDK ids, so every element is resolved
 * through a GUID→id map built from the document's object sources
 * ({@code model-source.all-objects}) and, lazily and only when needed, parameter sources
 * ({@code model-source.all-parameters}). Host order is preserved verbatim; mixed and
 * multi selection are therefore unchanged. A GUID with no live source — a deleted object
 * or a kind this projection does not enumerate — has no SDK id and is dropped rather than
 * guessed onto another object.
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

    EditorSelectionReadAccess(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    boolean authorized() {
        return resolver.authorizesFeature(
            EditorSelectionReadSelectorContract.ADAPTER_SLICE_ID,
            EditorSelectionReadSelectorContract.CAPABILITY_ID,
            EditorSelectionReadSelectorContract.REQUIRED_ALIASES
        );
    }

    HostSelection currentSelection() {
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        if (app == null) {
            return HostSelection.empty();
        }
        final Object updateManager = resolver.invoke(
            "cubism.editor-model.app-controller.update-manager", app
        );
        if (updateManager == null) {
            throw unavailable("Editor update manager is unavailable.");
        }
        final List<?> guidElements = list(
            resolver.invoke("cubism.editor-model.update-manager.selection-guid-list", updateManager),
            "Editor selection GUID list"
        );
        if (guidElements.isEmpty()) {
            return HostSelection.empty();
        }
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        if (!resolver.isInstance("cubism.editor-model.modeling-document.class", document)) {
            throw unavailable(
                "Editor selection cannot be projected without an active modeling document."
            );
        }
        final Object modelSource = resolver.invoke(
            "cubism.editor-model.modeling-document.model-source", document
        );
        if (modelSource == null) {
            throw unavailable("Editor model source is unavailable.");
        }
        final Map<String, String> idsByGuid = controllableIdsByGuid(modelSource);
        final ArrayList<String> selectedObjectIds = new ArrayList<>(guidElements.size());
        Map<String, String> parameterIdsByGuid = null;
        for (Object element : guidElements) {
            final String guid = guidText(element);
            String id = idsByGuid.get(guid);
            if (id == null) {
                if (parameterIdsByGuid == null) {
                    parameterIdsByGuid = parameterIdsByGuid(modelSource);
                }
                id = parameterIdsByGuid.get(guid);
            }
            if (id != null) {
                selectedObjectIds.add(id);
            }
        }
        return new HostSelection(
            List.copyOf(selectedObjectIds),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    private Map<String, String> controllableIdsByGuid(final Object modelSource) {
        final List<?> sources = list(
            resolver.invoke("cubism.editor-model.model-source.all-objects", modelSource),
            "Editor object source collection"
        );
        final HashMap<String, String> idsByGuid = new HashMap<>();
        for (Object source : sources) {
            final Object guid = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.guid", source
            );
            final String guidValue = text(
                resolver.invoke("cubism.editor-model.guid.value", guid),
                "Editor object GUID"
            );
            final Object id = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.id", source
            );
            final String idValue = text(
                resolver.invoke("cubism.editor-model.id.value", id),
                "Editor object ID"
            );
            if (idsByGuid.put(guidValue, idValue) != null) {
                throw unavailable("Editor object GUIDs are not unique.");
            }
        }
        return idsByGuid;
    }

    private Map<String, String> parameterIdsByGuid(final Object modelSource) {
        final List<?> sources = list(
            resolver.invoke("cubism.editor-model.model-source.all-parameters", modelSource),
            "Editor parameter source collection"
        );
        final HashMap<String, String> idsByGuid = new HashMap<>();
        for (Object source : sources) {
            final Object guid = resolver.invoke(
                "cubism.editor-model.parameter-source.guid", source
            );
            final String guidValue = text(
                resolver.invoke("cubism.editor-model.guid.value", guid),
                "Editor parameter GUID"
            );
            final Object id = resolver.invoke(
                "cubism.editor-model.parameter-source.id", source
            );
            final String idValue = text(
                resolver.invoke("cubism.editor-model.id.value", id),
                "Editor parameter ID"
            );
            if (idsByGuid.put(guidValue, idValue) != null) {
                throw unavailable("Editor parameter GUIDs are not unique.");
            }
        }
        return idsByGuid;
    }

    private String guidText(final Object element) {
        if (element instanceof String value) {
            return value;
        }
        return text(
            resolver.invoke("cubism.editor-model.guid.value", element),
            "Editor selection GUID"
        );
    }
}
