package dev.turboism.sdk.cubism.service.read;

import dev.turboism.sdk.cubism.AnimationSnapshot;
import dev.turboism.sdk.cubism.ActiveReadProjections;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.CubismFacade;
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
import dev.turboism.sdk.plugin.PluginContext;

import java.util.List;
import java.util.Optional;

/**
 * Cubism-facing read service for SDK-only plugins.
 *
 * <p>This service groups typed model and Editor read families used by plugins. Implementations must return immutable SDK DTOs and must not
 * expose raw host objects, Swing/AWT handles, adapter classes, or hook state.</p>
 */
public interface CubismReadCapabilityService {

    /**
     * @deprecated use {@link PluginContext#cubism()} and its {@link CubismFacade} active-project read.
     */
    @Deprecated
    Optional<ProjectSnapshot> activeProject();

    /**
     * @deprecated use {@link PluginContext#cubism()} and its {@link CubismFacade} active-document read.
     */
    @Deprecated
    Optional<DocumentSnapshot> activeDocument();

    /**
     * Model owned by the active MODEL document only.
     *
     * @deprecated use {@link PluginContext#cubism()} and its {@link CubismFacade} active-model read.
     */
    @Deprecated
    Optional<ModelSnapshot> activeModel();

    /**
     * Animation file owning the active ANIMATION_SCENE document.
     *
     * @deprecated use {@link PluginContext#cubism()} and its {@link CubismFacade} active-animation projection.
     */
    @Deprecated
    default Optional<AnimationSnapshot> activeAnimation() {
        return ActiveReadProjections.animationOf(activeDocument());
    }

    /**
     * Active layered image/PSD document, when applicable.
     *
     * @deprecated use {@link PluginContext#cubism()} and its {@link CubismFacade} active-image-document projection.
     */
    @Deprecated
    default Optional<DocumentSnapshot> activeImageDocument() {
        return ActiveReadProjections.imageDocumentOf(activeDocument());
    }

    /**
     * Project entry owning the active document.
     *
     * @deprecated use {@link PluginContext#cubism()} and its {@link CubismFacade} active-project-content projection.
     */
    @Deprecated
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

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    static CubismReadCapabilityService unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements CubismReadCapabilityService {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Deprecated
        @Override public Optional<ProjectSnapshot> activeProject() {
            throw unavailable();
        }

        @Deprecated
        @Override public Optional<DocumentSnapshot> activeDocument() {
            throw unavailable();
        }

        @Deprecated
        @Override public Optional<ModelSnapshot> activeModel() {
            throw unavailable();
        }

        @Override public SelectionSnapshot selection() {
            throw unavailable();
        }

        @Override public List<ParameterSnapshot> parameters() {
            throw unavailable();
        }

        @Override public List<ModelObjectSnapshot> modelObjects() {
            throw unavailable();
        }

        @Override public List<ArtMeshSnapshot> meshes() {
            throw unavailable();
        }

        @Override public List<DeformerSnapshot> deformers() {
            throw unavailable();
        }

        @Override public List<PsdDocumentSnapshot> psdDocuments() {
            throw unavailable();
        }

        @Override public List<ClipMaskSnapshot> clipMasks() {
            throw unavailable();
        }

        @Override public List<TextureAtlasSnapshot> textureAtlases() {
            throw unavailable();
        }

        @Override public Optional<RenderStatusSnapshot> renderStatus() {
            throw unavailable();
        }

        @Override public Optional<WorkspaceSnapshot> workspace() {
            throw unavailable();
        }

        @Override public Optional<ThemeStatusSnapshot> themeStatus() {
            throw unavailable();
        }

        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("cubismRead service is not available");
        }
    }
}
