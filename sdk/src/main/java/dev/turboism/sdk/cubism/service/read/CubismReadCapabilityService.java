package dev.turboism.sdk.cubism.service.read;

import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
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
 * M12 read-capability aggregation surface for SDK-only plugins.
 *
 * <p>This service groups the Cubism-facing read families required by legacy
 * business plugins. Implementations must return immutable SDK DTOs and must not
 * expose raw host objects, Swing/AWT handles, adapter classes, or hook state.</p>
 */
public interface CubismReadCapabilityService {

    Optional<ProjectSnapshot> activeProject();

    Optional<DocumentSnapshot> activeDocument();

    Optional<ModelSnapshot> activeModel();

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
