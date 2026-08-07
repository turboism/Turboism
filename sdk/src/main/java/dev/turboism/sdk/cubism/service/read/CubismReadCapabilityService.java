package dev.turboism.sdk.cubism.service.read;

import dev.turboism.sdk.cubism.AnimationSnapshot;
import dev.turboism.sdk.cubism.ActiveReadProjections;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Cubism-facing read service for SDK-only plugins.
 *
 * <p>This service groups typed model and Editor read families used by plugins. Implementations must return immutable SDK DTOs and must not
 * expose raw host objects, Swing/AWT handles, adapter classes, or hook state.</p>
 */
public interface CubismReadCapabilityService {

    Optional<ProjectSnapshot> activeProject();

    Optional<DocumentSnapshot> activeDocument();

    /** Model owned by the active MODEL document only. */
    Optional<ModelSnapshot> activeModel();

    /** Animation file owning the active ANIMATION_SCENE document. */
    default Optional<AnimationSnapshot> activeAnimation() {
        return ActiveReadProjections.animationOf(activeDocument());
    }

    /** Active layered image/PSD document, when applicable. */
    default Optional<DocumentSnapshot> activeImageDocument() {
        return ActiveReadProjections.imageDocumentOf(activeDocument());
    }

    /** Project entry owning the active document. */
    default Optional<ProjectContentSnapshot> activeProjectContent() {
        return ActiveReadProjections.projectContentOf(activeProject(), activeDocument());
    }

    SelectionSnapshot selection();

    List<ParameterSnapshot> parameters();

    List<ModelObjectSnapshot> modelObjects();

    List<ArtMeshSnapshot> meshes();

    List<DeformerSnapshot> deformers();

    List<PsdDocumentSnapshot> psdDocuments();

    List<ClipMaskSnapshot> clipMasks();

    List<TextureAtlasSnapshot> textureAtlases();

    Optional<RenderStatusSnapshot> renderStatus();

    Optional<WorkspaceSnapshot> workspace();

    Optional<ThemeStatusSnapshot> themeStatus();
}
