package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Drawable;
import java.lang.reflect.Array;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/** Resolves and revalidates the current native mesh editor, ArtMesh, selector, view, component, and camera. */
public final class MeshToolSessionResolver {
    private final SnapshotReader reader;
    private final VerifiedMemberResolver resolver;

    public MeshToolSessionResolver(final VerifiedMemberResolver resolver, final CubismModelAccess modelAccess) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(modelAccess, "modelAccess");
        if (!MeshToolSessionSelectorContract.authorizes(resolver)) {
            throw new IllegalArgumentException("Exact mesh-tool session selectors are not authorized.");
        }
        this.resolver = resolver;
        this.reader = exactReader(resolver, modelAccess);
    }

    MeshToolSessionResolver(final SnapshotReader reader) {
        this.resolver = null;
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /** Resolves the exact current session at a verified lifecycle start. */
    public Optional<NativeMeshToolSession> resolve(final Object mode, final List<?> startEntries) {
        return open(mode, startEntries, 0L);
    }

    Optional<NativeMeshToolSession> open(final Object mode, final List<?> startEntries, final long hostGeneration) {
        return snapshot(mode, startEntries, true).map(value -> new NativeMeshToolSession(hostGeneration, this, value));
    }

    Optional<Snapshot> currentSnapshot(final Object mode, final Object expectedArtMesh) {
        // Revalidation runs far more often than a lifecycle start, so it stays silent.
        return snapshot(mode, expectedArtMesh == null ? List.of() : List.of(expectedArtMesh), false);
    }

    VerifiedMemberResolver resolver() {
        return resolver;
    }

    /**
     * Reports why an exact session was refused and yields the supplied value.
     *
     * <p>Refusal is silent by contract — host lifecycle ingress must never throw — so without this
     * the operator cannot tell a host that is not in mesh-edit mode apart from a partially
     * materialised editor. Only a bounded step token is disclosed, and only on the lifecycle path,
     * never per sample.</p>
     */
    /** Reports one reader-level refusal; the reader only runs on the lifecycle path. */
    private static <T> T refuse(final String step) {
        return refuse(true, step, null);
    }

    private static <T> T refuse(final boolean reporting, final String step, final T value) {
        if (reporting) {
            try {
                dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                        "mesh-tool-session", "session refused at step=" + step);
            } catch (Throwable ignored) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
                // Diagnostics must never change resolution behaviour.
            }
        }
        return value;
    }

    private Optional<Snapshot> snapshot(final Object mode, final List<?> startEntries, final boolean reporting) {
        if (mode == null) return refuse(reporting, "mode-null", Optional.empty());
        try {
            final Snapshot snapshot = reader.read(mode, startEntries == null ? List.of() : List.copyOf(startEntries));
            if (snapshot == null) return refuse(reporting, "reader-no-snapshot", Optional.empty());
            if (snapshot.mode() != mode) return refuse(reporting, "mode-identity", Optional.empty());
            if (!snapshot.complete()) return refuse(reporting, "incomplete", Optional.empty());
            // The reader already derived the ArtMesh from the start entries and corroborated it
            // through the mode's own edit-data accessor, so no second entry comparison is needed.
            return Optional.of(snapshot);
        } catch (Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            return refuse(reporting, "reader-failed", Optional.empty());
        }
    }

    private static SnapshotReader exactReader(
            final VerifiedMemberResolver resolver, final CubismModelAccess modelAccess) {
        return (mode, startEntries) -> {
            if (!resolver.isInstance(MeshToolSessionSelectorContract.MESH_EDITOR_CLASS, mode))
                return refuse("editor-mode-type");
            final Object application = resolver.invokeStatic(MeshToolSessionSelectorContract.APPLICATION_INSTANCE);
            final Object document =
                    resolver.invoke(MeshToolSessionSelectorContract.APPLICATION_CURRENT_DOCUMENT, application);
            final Object modeDocument = resolver.invoke(MeshToolSessionSelectorContract.MESH_EDITOR_DOCUMENT, mode);
            if (document == null
                    || document != modeDocument
                    || !resolver.isInstance(MeshToolSessionSelectorContract.MODELING_DOCUMENT_CLASS, document)) {
                return refuse("modeling-document");
            }

            final Object modelSource =
                    resolver.invoke(MeshToolSessionSelectorContract.MODELING_DOCUMENT_MODEL_SOURCE, document);
            if (modelSource == null) return refuse("model-source");
            final Object allMeshes =
                    resolver.invoke(MeshToolSessionSelectorContract.MODEL_SOURCE_ALL_MESHES, modelSource);
            final Object artMesh = singleArtMesh(resolver, startEntries, allMeshes);
            if (artMesh == null) return refuse("art-mesh");
            final Object editData =
                    resolver.invoke(MeshToolSessionSelectorContract.MESH_EDITOR_EDIT_DATA_FOR, mode, artMesh);
            if (editData == null
                    || !resolver.isInstance(MeshToolSessionSelectorContract.ARTMESH_EDIT_DATA_CLASS, editData)) {
                return refuse("edit-data");
            }
            final Object editDataArtMesh =
                    resolver.invoke(MeshToolSessionSelectorContract.ARTMESH_EDIT_DATA_SOURCE, editData);
            final Object editableMesh =
                    resolver.invoke(MeshToolSessionSelectorContract.ARTMESH_EDIT_DATA_STAGING, editData);
            if (editDataArtMesh != artMesh
                    || editableMesh == null
                    || !resolver.isInstance(MeshToolSessionSelectorContract.EDITABLE_MESH_CLASS, editableMesh)) {
                return refuse("editable-mesh");
            }

            final Object selection =
                    resolver.invoke(MeshToolSessionSelectorContract.EDITABLE_MESH_SELECTION, editableMesh);
            if (selection == null
                    || !resolver.isInstance(MeshToolSessionSelectorContract.EDITABLE_SELECTION_CLASS, selection)) {
                return refuse("editable-selection");
            }
            final Object pointSelector =
                    resolver.invoke(MeshToolSessionSelectorContract.EDITABLE_SELECTION_POINT_SELECTOR, selection);
            if (pointSelector == null
                    || !resolver.isInstance(MeshToolSessionSelectorContract.POINT_SELECTOR_CLASS, pointSelector)) {
                return refuse("point-selector");
            }

            final Object modelingView =
                    resolver.invoke(MeshToolSessionSelectorContract.MODELING_DOCUMENT_LAST_ACTIVE_VIEW, document);
            if (modelingView == null
                    || !resolver.isInstance(MeshToolSessionSelectorContract.MODELING_VIEW_CLASS, modelingView)) {
                return refuse("modeling-view");
            }
            final Object completePack =
                    resolver.invoke(MeshToolSessionSelectorContract.VIEW_COMPLETE_PACK, modelingView);
            final Object mainView =
                    resolver.invoke(MeshToolSessionSelectorContract.COMPLETE_PACK_MAIN_VIEW, completePack);
            final Object component = resolver.invoke(MeshToolSessionSelectorContract.WIDGET_JCOMPONENT, mainView);
            final Object camera = resolver.invoke(MeshToolSessionSelectorContract.MODELING_VIEW_CAMERA, modelingView);
            if (completePack == null || mainView == null || component == null || camera == null)
                return refuse("view-component-or-camera");

            final Object id = resolver.invoke(MeshToolSessionSelectorContract.ARTMESH_SOURCE_ID, artMesh);
            final Object idValue = resolver.invoke(MeshToolSessionSelectorContract.ID_VALUE, id);
            if (!(idValue instanceof String value) || value.isBlank()) return refuse("art-mesh-id");
            final Drawable drawable;
            try {
                drawable = modelAccess.active().drawables().find(new ArtMeshId(value));
            } catch (IllegalStateException | NoSuchElementException failure) {
                return refuse("drawable-lookup");
            }
            if (drawable == null
                    || drawable.id() == null
                    || !value.equals(drawable.id().value())) {
                return refuse("drawable-identity");
            }
            return new Snapshot(
                    mode,
                    editData,
                    document,
                    artMesh,
                    editableMesh,
                    selection,
                    pointSelector,
                    modelingView,
                    completePack,
                    component,
                    camera,
                    drawable,
                    application,
                    modelSource);
        };
    }

    /**
     * Derives the single ArtMesh an exact session edits from the native start list.
     *
     * <p>Native {@code startMode(List)} receives mesh-edit-data entries, and each one names the
     * ArtMesh it edits through its own accessor. Treating the entries themselves as ArtMesh sources
     * never matched, which refused every session. A host that hands over the ArtMesh source directly
     * is still accepted, and an ambiguous or unrelated list fails closed.</p>
     */
    static Object singleArtMesh(final VerifiedMemberResolver resolver, final List<?> entries, final Object allMeshes) {
        Object match = null;
        if (entries == null) return null;
        for (Object entry : entries) {
            final Object source = artMeshSourceOf(resolver, entry);
            if (source == null || !containsIdentity(allMeshes, source)) continue;
            if (match != null && match != source) return null;
            match = source;
        }
        return match;
    }

    /** Returns the ArtMesh source an edit-data entry names, or the entry itself when it is one. */
    private static Object artMeshSourceOf(final VerifiedMemberResolver resolver, final Object entry) {
        if (entry == null) return null;
        if (resolver.isInstance(MeshToolSessionSelectorContract.ARTMESH_EDIT_DATA_CLASS, entry)) {
            final Object source = resolver.invoke(MeshToolSessionSelectorContract.ARTMESH_EDIT_DATA_SOURCE, entry);
            return source != null && resolver.isInstance(MeshToolSessionSelectorContract.ARTMESH_SOURCE_CLASS, source)
                    ? source
                    : null;
        }
        return resolver.isInstance(MeshToolSessionSelectorContract.ARTMESH_SOURCE_CLASS, entry) ? entry : null;
    }

    private static boolean containsIdentity(final Object values, final Object expected) {
        if (values == null || expected == null) return false;
        if (values instanceof Iterable<?> iterable) {
            for (Object value : iterable) if (value == expected) return true;
            return false;
        }
        if (values.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(values); index++) {
                if (Array.get(values, index) == expected) return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    interface SnapshotReader {
        Snapshot read(Object mode, List<?> startEntries);
    }

    record Snapshot(
            Object mode,
            Object editData,
            Object document,
            Object artMesh,
            Object editableMesh,
            Object selection,
            Object pointSelector,
            Object modelingView,
            Object completePack,
            Object component,
            Object camera,
            Object drawable,
            Object application,
            Object modelSource) {
        boolean complete() {
            return mode != null
                    && editData != null
                    && document != null
                    && artMesh != null
                    && editableMesh != null
                    && selection != null
                    && pointSelector != null
                    && modelingView != null
                    && completePack != null
                    && component != null
                    && camera != null
                    && drawable != null
                    && application != null
                    && modelSource != null;
        }

        Snapshot withMode(final Object value) {
            return new Snapshot(
                    value,
                    editData,
                    document,
                    artMesh,
                    editableMesh,
                    selection,
                    pointSelector,
                    modelingView,
                    completePack,
                    component,
                    camera,
                    drawable,
                    application,
                    modelSource);
        }

        Snapshot withArtMesh(final Object value) {
            return new Snapshot(
                    mode,
                    editData,
                    document,
                    value,
                    editableMesh,
                    selection,
                    pointSelector,
                    modelingView,
                    completePack,
                    component,
                    camera,
                    drawable,
                    application,
                    modelSource);
        }
    }
}
