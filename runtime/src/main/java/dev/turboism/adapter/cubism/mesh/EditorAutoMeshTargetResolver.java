package dev.turboism.adapter.cubism.mesh;

import dev.turboism.automesh.host.AutoMeshFunnel;
import dev.turboism.automesh.host.AutoMeshTargetResolver;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.selector.MeshToolSessionSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the Auto Mesh Generate selection of both guarded funnels into SDK ArtMeshes.
 *
 * <p>Each funnel names its targets differently. The modeling-mode funnel hands over
 * {@code CMeshGeneratorExtension} entries, and each entry names its owning ArtMesh source through
 * the extension base class. The mesh-editor funnel hands over
 * {@code CModelingEditMode_MeshEditor$b} entries, and each entry names its ArtMesh source
 * directly. Both accessors are verified selectors of the reviewed Editor-model record, and both
 * element types carry the same ArtMesh source, so one projection serves both funnels.</p>
 *
 * <p>The projection matches each host source against the live ArtMeshes of the active model by the
 * host-issued object id, which is the same selector the SDK adapter reads for
 * {@link Drawable#id()}. A source the model does not carry — a deleted mesh, or a stale selection —
 * makes the whole resolution fail, so a custom run never writes a different selection than the one
 * the user confirmed.</p>
 *
 * <p>The mesh-generator extension reaches its owner through the extension base class, so the
 * projection uses the verified base-class accessor of the reviewed record. The resolver refuses an
 * alias the admitted plan does not carry and checks the receiver against the declaring class, so a
 * build without the accessor or an element of an unrelated type fails closed instead of being read
 * through a guessed member.</p>
 */
public final class EditorAutoMeshTargetResolver implements AutoMeshTargetResolver {

    private static final String MODELING_EXTENSION_OWNER = "cubism.editor-model.editable-mesh-extension.owner";
    private static final String MESH_EDIT_DATA_CLASS = "cubism.editor-model.mesh-edit-data.class";
    private static final String MESH_EDIT_DATA_SOURCE = MeshToolSessionSelectorContract.ARTMESH_EDIT_DATA_SOURCE;
    private static final String ART_MESH_SOURCE_CLASS = MeshToolSessionSelectorContract.ARTMESH_SOURCE_CLASS;
    private static final String SOURCE_ID = MeshToolSessionSelectorContract.ARTMESH_SOURCE_ID;
    private static final String ID_VALUE = MeshToolSessionSelectorContract.ID_VALUE;

    /** Index of the first declared argument in the bridge's token-and-receiver-prefixed array. */
    private static final int FIRST_DECLARED_ARGUMENT = 2;

    /** Index of the selection list among the declared arguments of both funnels. */
    private static final int SELECTION_ARGUMENT = 2;

    private final VerifiedMemberResolver resolver;
    private final CubismModelAccess modelAccess;

    /**
     * Creates the resolver.
     *
     * @param resolver the verified Editor-model resolver of the active session
     * @param modelAccess the live model access the projection reads
     */
    public EditorAutoMeshTargetResolver(final VerifiedMemberResolver resolver, final CubismModelAccess modelAccess) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelAccess = Objects.requireNonNull(modelAccess, "modelAccess");
    }

    @Override
    public Optional<List<Drawable>> resolve(final AutoMeshFunnel funnel, final Object[] hostArguments) {
        Objects.requireNonNull(funnel, "funnel");
        final int selectionIndex = FIRST_DECLARED_ARGUMENT + SELECTION_ARGUMENT;
        if (hostArguments == null || hostArguments.length <= selectionIndex) {
            return Optional.empty();
        }
        if (!(hostArguments[selectionIndex] instanceof List<?> entries)) {
            return Optional.empty();
        }
        try {
            return project(funnel, entries);
        } catch (RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            return Optional.empty();
        }
    }

    private Optional<List<Drawable>> project(final AutoMeshFunnel funnel, final List<?> entries) {
        if (entries.isEmpty()) {
            return Optional.of(List.of());
        }
        final Drawables drawables = modelAccess.active().drawables();
        final List<Drawable> all = drawables.all();
        final List<Drawable> resolved = new ArrayList<>(entries.size());
        for (final Object entry : entries) {
            final String id = idOf(sourceOf(funnel, entry));
            final Drawable match = id == null ? null : matching(all, id);
            if (match == null) {
                return Optional.empty();
            }
            resolved.add(match);
        }
        return Optional.of(List.copyOf(resolved));
    }

    /**
     * Returns the ArtMesh source one funnel element names, or {@code null} when it names none.
     *
     * <p>The modeling-mode funnel passes mesh-generator extensions, which reach their owner through
     * the extension base class. The mesh-editor funnel passes edit-data entries, which reach their
     * ArtMesh source directly. An element of the wrong type fails the receiver check of the
     * verified call site, which the caller turns into an unresolvable selection.</p>
     */
    private Object sourceOf(final AutoMeshFunnel funnel, final Object entry) {
        if (entry == null) {
            return null;
        }
        final Object source =
                switch (funnel) {
                    case MODELING_MODE -> resolver.invoke(MODELING_EXTENSION_OWNER, entry);
                    case MESH_EDITOR_MODE ->
                        resolver.isInstance(MESH_EDIT_DATA_CLASS, entry)
                                ? resolver.invoke(MESH_EDIT_DATA_SOURCE, entry)
                                : null;
                };
        return source != null && resolver.isInstance(ART_MESH_SOURCE_CLASS, source) ? source : null;
    }

    private String idOf(final Object source) {
        if (source == null) {
            return null;
        }
        final Object id = resolver.invoke(SOURCE_ID, source);
        final Object value = id == null ? null : resolver.invoke(ID_VALUE, id);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Drawable matching(final List<Drawable> drawables, final String id) {
        for (final Drawable drawable : drawables) {
            if (drawable.id().value().equals(id)) {
                return drawable;
            }
        }
        return null;
    }
}
