package dev.turboism.sdk.cubism;

import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import java.util.Optional;

/** View of the Cubism host exposed to plugins. */
public interface CubismFacade {

    /** Returns the host runtime identity and version snapshot. */
    CubismRuntimeSnapshot runtime();

    /** Returns the currently open project, or empty when no project is open. */
    Optional<ProjectSnapshot> activeProject();

    /** Returns the focused document, or empty when none is focused. */
    Optional<DocumentSnapshot> activeDocument();

    /**
     * Returns the model owned by the active MODEL document only.
     *
     * <p>This is empty for animation scene, game-data, physics, and other documents, even when
     * those documents reference or preview a Live2D model.</p>
     */
    Optional<ModelSnapshot> activeModel();

    /** Returns the animation file that owns the active ANIMATION_SCENE document. */
    default Optional<AnimationSnapshot> activeAnimation() {
        return ActiveReadProjections.animationOf(activeDocument());
    }

    /** Returns the active document only when it is a modeling document. */
    default Optional<DocumentSnapshot> activeModelDocument() {
        return activeDocument().filter(DocumentSnapshot::isModelDocument);
    }

    /** Returns the active document only when it is an animation scene document. */
    default Optional<DocumentSnapshot> activeAnimationDocument() {
        return activeDocument().filter(DocumentSnapshot::isAnimationDocument);
    }

    /** Returns the active document only when it is a layered image/PSD document. */
    default Optional<DocumentSnapshot> activeImageDocument() {
        return ActiveReadProjections.imageDocumentOf(activeDocument());
    }

    /** Returns the project entry that owns the active document. */
    default Optional<ProjectContentSnapshot> activeProjectContent() {
        return ActiveReadProjections.projectContentOf(activeProject(), activeDocument());
    }

    /**
     * Returns whether the backing snapshot source currently observes a host session — in
     * practice whether a project or document is visible to it. This is the source's
     * observability signal, not a guarantee that a physical host connection is alive.
     */
    boolean isHostPresent();

    /**
     * Returns permission-checked Cubism Core metadata and MOC inspection.
     *
     * <p>The default is the {@link CoreRuntimeInfo#unavailable()} sentinel, matching
     * the facade's other optional accessors.</p>
     */
    default CoreRuntimeInfo coreRuntime() {
        return CoreRuntimeInfo.unavailable();
    }

    /**
     * Returns the unified model object API.
     *
     * <p>The default is the {@link CubismModelAccess#unavailable()} sentinel: it keeps
     * implementations without a Runtime backend fail-closed instead of throwing from
     * the accessor itself.</p>
     */
    default CubismModelAccess model() {
        return CubismModelAccess.unavailable();
    }

    /** Returns active-document native Undo history access when installed by Runtime. */
    @dev.turboism.sdk.CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default CubismHistory history() {
        return CubismHistory.unavailable();
    }

    /**
     * Returns the synchronous Editor-owned authoring transaction service — the recommended entry
     * for grouped or batched writes that must share one Editor Undo unit.
     *
     * <p>Single parameter writes do not need it: {@code model().active().parameters().find(id)
     * .setValue(v)} is already validated and undoable on its own.
     *
     * <p>The default fails closed and never executes work outside a verified transaction scope.</p>
     *
     * @return synchronous authoring transaction service
     */
    @dev.turboism.sdk.CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default AuthoringTransactionService authoringTransactions() {
        return AuthoringTransactionService.unavailable();
    }

    /**
     * Returns the external-application editing session service.
     *
     * <p>The default fails closed: sessions opened through it admit no operations until a
     * Runtime backend with verified editor bindings is installed.</p>
     *
     * @return the editing session service
     */
    @dev.turboism.sdk.Incubating
    @dev.turboism.sdk.CubismEditor(from = "5.2.03", to = "5.3.99")
    default dev.turboism.sdk.cubism.edit.EditSessionService edit() {
        return dev.turboism.sdk.cubism.edit.EditSessionService.unavailable();
    }

    /**
     * Returns whole-object Warp Deformer mirror operations when installed by Runtime.
     *
     * <p>The default fails closed and reports every request as unavailable.</p>
     *
     * @return whole-object warp mirror service
     */
    @dev.turboism.sdk.CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default dev.turboism.sdk.cubism.mirror.WarpMirrorService warpMirror() {
        return dev.turboism.sdk.cubism.mirror.WarpMirrorService.unavailable();
    }

    /** Returns complete texture-atlas authoring layout access when installed. */
    @dev.turboism.sdk.CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService textureAtlasLayouts() {
        return dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService.unavailable();
    }

    /**
     * Returns polygon-aware texture-atlas layout access when installed.
     *
     * <p>Snapshots carry item outlines from the host's model-image contour source
     * (or a flagged bounds fallback), per-item layout policies and issued
     * transforms; {@code apply} writes arbitrary-angle, scaled placements through
     * the same affine/undo boundary as the rectangle service.</p>
     */
    @dev.turboism.sdk.CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService textureAtlasPolygonLayouts() {
        return dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService.unavailable();
    }

    /** Returns read access to the active native texture-atlas editor session. */
    @dev.turboism.sdk.CubismEditor({"5.3.02", "5.3.03"})
    default dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession textureAtlasEditorSession() {
        return dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorSession.unavailable();
    }

    /** Returns UI contribution access to the native texture-atlas editor window. */
    @dev.turboism.sdk.CubismEditor({"5.3.02", "5.3.03"})
    default dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi textureAtlasEditorUi() {
        return dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi.unavailable();
    }

    /** Returns the registry of registered texture-atlas layout algorithms. */
    default dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms() {
        return dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry.unavailable();
    }

    /** Returns whether a project is currently open. */
    default boolean hasActiveProject() {
        return activeProject().isPresent();
    }

    /** Returns whether a document is currently focused. */
    default boolean hasActiveDocument() {
        return activeDocument().isPresent();
    }

    /** Returns whether the active document owns a Live2D model. */
    default boolean hasActiveModel() {
        return activeModel().isPresent();
    }

    /** Returns whether the active document is an animation scene. */
    default boolean hasActiveAnimation() {
        return activeAnimation().isPresent();
    }

    /** Returns whether the active document is a layered image/PSD document. */
    default boolean hasActiveImageDocument() {
        return activeImageDocument().isPresent();
    }
}
