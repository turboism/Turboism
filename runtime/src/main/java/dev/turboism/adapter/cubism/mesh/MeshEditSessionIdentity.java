package dev.turboism.adapter.cubism.mesh;

import java.util.Objects;

/** Opaque runtime identity tuple for one exact native mesh-edit session. */
public final class MeshEditSessionIdentity {
    private final Object application;
    private final Object mode;
    private final Object editData;
    private final Object document;
    private final Object modelSource;
    private final Object artMesh;
    private final Object editableMesh;
    private final Object selection;
    private final Object pointSelector;
    private final Object modelingView;
    private final Object completePack;
    private final Object component;
    private final Object camera;
    private final Object drawable;

    MeshEditSessionIdentity(final MeshToolSessionResolver.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        application = snapshot.application();
        mode = snapshot.mode();
        editData = snapshot.editData();
        document = snapshot.document();
        modelSource = snapshot.modelSource();
        artMesh = snapshot.artMesh();
        editableMesh = snapshot.editableMesh();
        selection = snapshot.selection();
        pointSelector = snapshot.pointSelector();
        modelingView = snapshot.modelingView();
        completePack = snapshot.completePack();
        component = snapshot.component();
        camera = snapshot.camera();
        drawable = snapshot.drawable();
    }

    boolean matches(final MeshToolSessionResolver.Snapshot snapshot) {
        return snapshot != null
                && application == snapshot.application()
                && mode == snapshot.mode()
                && editData == snapshot.editData()
                && document == snapshot.document()
                && modelSource == snapshot.modelSource()
                && artMesh == snapshot.artMesh()
                && editableMesh == snapshot.editableMesh()
                && selection == snapshot.selection()
                && pointSelector == snapshot.pointSelector()
                && modelingView == snapshot.modelingView()
                && completePack == snapshot.completePack()
                && component == snapshot.component()
                && camera == snapshot.camera();
    }

    /** Returns the exact application controller identity. */
    public Object application() {
        return application;
    }
    /** Returns the exact model-source identity. */
    public Object modelSource() {
        return modelSource;
    }
    /** Returns the exact mesh-editor mode identity. */
    public Object mode() {
        return mode;
    }
    /** Returns the exact ArtMesh edit-data identity. */
    public Object editData() {
        return editData;
    }
    /** Returns the exact modeling-document identity. */
    public Object document() {
        return document;
    }
    /** Returns the exact ArtMesh source identity. */
    public Object artMesh() {
        return artMesh;
    }
    /** Returns the exact editable-mesh identity. */
    public Object editableMesh() {
        return editableMesh;
    }
    /** Returns the exact editable-selection identity. */
    public Object selection() {
        return selection;
    }
    /** Returns the exact point-selector identity. */
    public Object pointSelector() {
        return pointSelector;
    }
    /** Returns the exact modeling-view identity. */
    public Object modelingView() {
        return modelingView;
    }
    /** Returns the exact complete-view-pack identity. */
    public Object completePack() {
        return completePack;
    }
    /** Returns the exact active Swing component identity as an opaque value. */
    public Object component() {
        return component;
    }
    /** Returns the exact camera identity. */
    public Object camera() {
        return camera;
    }
    /** Returns the SDK drawable paired with this native session. */
    public Object drawable() {
        return drawable;
    }
}
