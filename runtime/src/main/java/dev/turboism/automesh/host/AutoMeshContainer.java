package dev.turboism.automesh.host;

/**
 * The three native Auto Mesh Generate containers the selector row attaches to.
 *
 * <p>Each popup owns exactly one container widget, and that widget is the {@code this} of its own
 * constructor. The attach anchor is therefore the constructor itself, which is the only place the
 * container is known to exist before the host puts it on screen. The identity travels to the
 * runtime as a value, because transformed host bytecode may name only JDK types.</p>
 */
public enum AutoMeshContainer {

    /**
     * {@code AutoMeshGenerate_DialogType$AutoGenerateMeshPanel}: the top-toolbar Auto Mesh
     * Generate dialog panel.
     */
    MODELING_DIALOG_PANEL("modeling-dialog-panel"),

    /**
     * {@code AutoMeshGenerate_PopupType$AutoGenerateMeshDialog}: the modeling-mode right-click
     * popup, which exposes no public accessor for its container.
     */
    MODELING_POPUP_DIALOG("modeling-popup-dialog"),

    /**
     * {@code AutoMeshGenerate_PopupType_InMeshEditor$AutoGenerateMeshDialog}: the mesh-editor
     * popup.
     */
    MESH_EDITOR_POPUP_DIALOG("mesh-editor-popup-dialog");

    private final String token;

    AutoMeshContainer(final String token) {
        this.token = token;
    }

    /** @return the stable log and diagnostic token of this container */
    public String token() {
        return token;
    }
}
