package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.ModelStatistics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/** Exact-version Editor projection of Cubism's model-statistics inputs. */
final class EditorModelStatisticsAccess {

    private final VerifiedMemberResolver resolver;
    private final EditorObjectReadAccess.CurrentGuard currentGuard;

    EditorModelStatisticsAccess(
        final VerifiedMemberResolver resolver,
        final EditorObjectReadAccess.CurrentGuard currentGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
    }

    ModelStatistics statistics(final String identity, final Object source, final Object model) {
        requireAuthorized();
        currentGuard.requireCurrent(identity, model);
        final List<?> artMeshes = list(
            resolver.invoke("cubism.editor-model.model.all-art-meshes", model),
            "Editor ArtMesh collection"
        );
        final List<?> deformers = list(
            resolver.invoke("cubism.editor-model.model.all-deformers", model),
            "Editor Deformer collection"
        );
        final List<?> parts = list(
            resolver.invoke("cubism.editor-model.model-source.parts", source),
            "Editor Part collection"
        );
        final Object parameterSet = resolver.invoke("cubism.editor-model.model.parameter-set", model);
        final List<?> parameters = list(
            resolver.invoke("cubism.editor-model.parameter-set.parameters", parameterSet),
            "Editor parameter collection"
        );

        int vertices = 0;
        int triangles = 0;
        int masked = 0;
        final Set<String> textures = new HashSet<>();
        final Set<List<String>> maskGroups = new HashSet<>();
        for (Object mesh : artMeshes) {
            final Object meshSource = resolver.invoke("cubism.editor-model.art-mesh.source", mesh);
            vertices = Math.addExact(vertices, arrayLength(
                resolver.invoke("cubism.editor-model.art-mesh-source.positions", meshSource),
                "Editor ArtMesh positions"
            ) / 2);
            triangles = Math.addExact(triangles, arrayLength(
                resolver.invoke("cubism.editor-model.art-mesh-source.indices", meshSource),
                "Editor ArtMesh indices"
            ) / 3);
            final Object texture = resolver.invoke("cubism.editor-model.art-mesh-source.texture", meshSource);
            if (texture != null) {
                final Object textureGuid = resolver.invoke("cubism.editor-model.texture.guid", texture);
                if (textureGuid != null) textures.add(text(resolver.invoke("cubism.editor-model.guid.value", textureGuid)));
            }
            final List<?> clips = list(
                resolver.invoke("cubism.editor-model.art-mesh-source.clip-guid-list", meshSource),
                "Editor clipping-mask collection"
            );
            if (!clips.isEmpty()) {
                masked++;
                final ArrayList<String> group = new ArrayList<>(clips.size());
                for (Object clip : clips) group.add(text(resolver.invoke("cubism.editor-model.guid.value", clip)));
                maskGroups.add(List.copyOf(group));
            }
        }

        final OptionalInt offscreenCount;
        final OptionalInt maxOffscreenDepth;
        if (resolver.isExactCubismVersion("5.3.02")) {
            int count = 0;
            int depth = 0;
            for (Object part : parts) {
                if (Boolean.TRUE.equals(resolver.invoke("cubism.editor-model.part-source.use-offscreen", part))) {
                    count++;
                    depth = Math.max(depth, partDepth(part));
                }
            }
            offscreenCount = OptionalInt.of(count);
            maxOffscreenDepth = OptionalInt.of(depth);
        } else {
            offscreenCount = OptionalInt.empty();
            maxOffscreenDepth = OptionalInt.empty();
        }

        currentGuard.requireCurrent(identity, model);
        return new ModelStatistics(
            parameters.size(), parts.size(), artMeshes.size(), artMeshes.size(), deformers.size(),
            vertices, triangles, textures.size(), masked, maskGroups.size(),
            offscreenCount, maxOffscreenDepth
        );
    }

    private int partDepth(final Object part) {
        int depth = 1;
        Object parent = resolver.invoke("cubism.editor-model.part-source.parent", part);
        while (parent != null) {
            if (Boolean.TRUE.equals(resolver.invoke("cubism.editor-model.part-source.use-offscreen", parent))) depth++;
            parent = resolver.invoke("cubism.editor-model.part-source.parent", parent);
        }
        return depth;
    }

    private void requireAuthorized() {
        final java.util.HashSet<String> aliases = new java.util.HashSet<>(
            EditorObjectReadSelectorContract.STATISTICS_ALIASES
        );
        if (resolver.isExactCubismVersion("5.3.02")) {
            aliases.addAll(EditorObjectReadSelectorContract.OFFSCREEN_STATISTICS_ALIASES);
        }
        if (!resolver.authorizesFeature(
            EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
            EditorObjectReadSelectorContract.STATISTICS_CAPABILITY_ID,
            aliases
        )) {
            throw new UnsupportedOperationException(
                "Editor model statistics require exact verified host evidence."
            );
        }
    }

    private static List<?> list(final Object value, final String label) {
        if (value instanceof List<?> list) return list;
        if (value instanceof Iterable<?> iterable) {
            final ArrayList<Object> copy = new ArrayList<>();
            iterable.forEach(copy::add);
            return List.copyOf(copy);
        }
        throw new IllegalStateException(label + " is unavailable.");
    }

    private static int arrayLength(final Object value, final String label) {
        if (value == null || !value.getClass().isArray()) {
            throw new IllegalStateException(label + " is unavailable.");
        }
        return java.lang.reflect.Array.getLength(value);
    }

    private static String text(final Object value) {
        if (value == null) throw new IllegalStateException("Editor statistics identity is unavailable.");
        return value.toString();
    }
}
