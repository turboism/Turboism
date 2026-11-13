package dev.turboism.plugin.deformerbatchrename;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ModelNamingIndex;
import dev.turboism.sdk.cubism.model.PartId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds detached rename previews from one naming index.
 *
 * <p>Every proposed name depends on the same index, so both base-name modes are derived from one
 * read. Switching the mode afterwards re-derives names from memory instead of asking the host
 * again, and a large model pays one traversal rather than one per object and property.</p>
 */
final class RenamePlanner {

    /** One preview row: the deformer, its kind, its current name and the proposed name. */
    record Row(DeformerId id, boolean rotation, String original, String target) {
        boolean changed() {
            return !original.equals(target);
        }
    }

    /** Both previews a dialog can show, derived from the same index. */
    record Preview(ModelNamingIndex index, Map<BaseNameMode, List<Row>> rowsByMode) {
        public Preview {
            rowsByMode = Map.copyOf(rowsByMode);
        }

        /** @return the rows for {@code mode}; never null */
        List<Row> rows(final BaseNameMode mode) {
            return rowsByMode.getOrDefault(mode, List.of());
        }
    }

    private static final Pattern AXIS = Pattern.compile("^(.*?)[\\s_]*([XYZxyz])$");

    /** Which name the batch derives the new deformer name from. */
    enum BaseNameMode {
        /** The minimal common prefix of the deformer's leaf meshes. */
        MINIMAL_COMMON,
        /** The name of the part that owns most of the deformer's leaf meshes. */
        PART_NAME
    }

    private RenamePlanner() {}

    /**
     * Derives both previews from one index.
     *
     * @param index the model naming index
     * @return the index and the rows of every mode
     */
    static Preview preview(final ModelNamingIndex index) {
        final Map<BaseNameMode, List<Row>> rowsByMode = new LinkedHashMap<>();
        for (BaseNameMode mode : BaseNameMode.values()) rowsByMode.put(mode, rows(index, mode));
        return new Preview(index, rowsByMode);
    }

    /**
     * Derives one preview from an index the caller already holds.
     *
     * @param index the model naming index
     * @param mode the base-name mode
     * @return the preview rows, in index order
     */
    static List<Row> rows(final ModelNamingIndex index, final BaseNameMode mode) {
        final Tree tree = new Tree(index);
        final List<Row> rows = new ArrayList<>(index.deformers().size());
        for (ModelNamingIndex.DeformerEntry deformer : index.deformers()) {
            final String base = mode == BaseNameMode.PART_NAME ? tree.partBaseName(deformer.id()) : "";
            final String resolved = base.isEmpty() ? tree.commonBaseName(deformer.id()) : base;
            final String suffix = parameterSuffix(deformer.parameterIds().stream()
                    .distinct()
                    .map(tree::parameterName)
                    .toList());
            final String target =
                    resolved.isEmpty() ? deformer.name() : resolved + (suffix.isEmpty() ? "" : " " + suffix);
            rows.add(new Row(deformer.id(), deformer.rotation(), deformer.name(), target));
        }
        return List.copyOf(rows);
    }

    /** Parent and child links over one index, resolved once per preview. */
    private static final class Tree {
        private final Map<DeformerId, List<DeformerId>> children = new HashMap<>();
        private final Map<DeformerId, List<ModelNamingIndex.ArtMeshEntry>> meshes = new HashMap<>();
        private final Map<PartId, String> partNames = new HashMap<>();
        private final Map<ParameterId, String> parameterNames = new HashMap<>();

        Tree(final ModelNamingIndex index) {
            for (ModelNamingIndex.PartEntry part : index.parts()) partNames.put(part.id(), part.name());
            for (ModelNamingIndex.ParameterEntry parameter : index.parameters()) {
                parameterNames.put(parameter.id(), parameter.name());
            }
            for (ModelNamingIndex.DeformerEntry deformer : index.deformers()) {
                deformer.parentDeformerId()
                        .ifPresent(parent -> children.computeIfAbsent(parent, ignored -> new ArrayList<>())
                                .add(deformer.id()));
            }
            for (ModelNamingIndex.ArtMeshEntry mesh : index.artMeshes()) {
                mesh.parentDeformerId()
                        .ifPresent(parent -> meshes.computeIfAbsent(parent, ignored -> new ArrayList<>())
                                .add(mesh));
            }
        }

        /**
         * Collects the mesh names below one deformer.
         *
         * <p>A childless deformer contributes its own meshes; a nested one contributes the leaves
         * of every child, so a deformer that spans several parts keeps a stable name.</p>
         */
        List<String> leafNames(final DeformerId id) {
            final List<String> names = new ArrayList<>();
            collectMeshes(id, new HashSet<>(), names);
            return names;
        }

        private void collectMeshes(final DeformerId id, final Set<DeformerId> visiting, final List<String> names) {
            if (!visiting.add(id)) throw new IllegalStateException("Cyclic deformer hierarchy");
            for (ModelNamingIndex.ArtMeshEntry mesh : meshes.getOrDefault(id, List.of())) names.add(mesh.name());
            for (DeformerId nested : children.getOrDefault(id, List.of())) collectMeshes(nested, visiting, names);
            visiting.remove(id);
        }

        /**
         * Collects the Parts that own the meshes below one deformer.
         *
         * <p>A childless deformer contributes its own mesh parts; a nested one contributes the
         * parts of every leaf below it.</p>
         */
        List<PartId> leafParts(final DeformerId id) {
            final List<PartId> parts = new ArrayList<>();
            collectParts(id, new HashSet<>(), parts);
            return parts;
        }

        private void collectParts(final DeformerId id, final Set<DeformerId> visiting, final List<PartId> parts) {
            if (!visiting.add(id)) throw new IllegalStateException("Cyclic deformer hierarchy");
            for (ModelNamingIndex.ArtMeshEntry mesh : meshes.getOrDefault(id, List.of())) {
                mesh.parentPartId().ifPresent(parts::add);
            }
            for (DeformerId nested : children.getOrDefault(id, List.of())) collectParts(nested, visiting, parts);
            visiting.remove(id);
        }

        String commonBaseName(final DeformerId id) {
            return baseName(leafNames(id));
        }

        /** @return the parameter name, or the identifier text when the index does not contain it */
        String parameterName(final ParameterId id) {
            return parameterNames.getOrDefault(id, id.value());
        }

        /**
         * Picks the part name that owns most of the leaf meshes.
         *
         * <p>Ties keep the first part seen, so one deformer always produces the same name for the
         * same model instead of depending on map ordering.</p>
         *
         * @return the chosen part name, or an empty string when no leaf carries a named part
         */
        String partBaseName(final DeformerId id) {
            final Map<PartId, Integer> counts = new LinkedHashMap<>();
            for (PartId part : leafParts(id)) {
                if (partNames.containsKey(part)) counts.merge(part, 1, Integer::sum);
            }
            PartId best = null;
            int bestCount = 0;
            for (Map.Entry<PartId, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    best = entry.getKey();
                    bestCount = entry.getValue();
                }
            }
            return best == null ? "" : partNames.getOrDefault(best, "").strip();
        }
    }

    static String baseName(final List<String> names) {
        final List<String> clean = names.stream()
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .toList();
        if (clean.isEmpty()) return "";
        String prefix = clean.get(0);
        String shortest = prefix;
        for (String name : clean) {
            if (name.codePointCount(0, name.length()) < shortest.codePointCount(0, shortest.length())) shortest = name;
            int end = 0;
            while (end < prefix.length() && end < name.length() && prefix.charAt(end) == name.charAt(end)) end++;
            if (end > 0 && Character.isHighSurrogate(prefix.charAt(end - 1))) end--;
            prefix = prefix.substring(0, end);
        }
        return prefix.isBlank() ? shortest : prefix.strip();
    }

    static String parameterSuffix(final List<String> names) {
        // Only names ending in a single axis are compacted; unrelated names remain intact.
        final Map<String, LinkedHashSet<String>> axes = new LinkedHashMap<>();
        final List<String> order = new ArrayList<>();
        for (String raw : new LinkedHashSet<>(names)) {
            final String name = raw.strip();
            final var matcher = AXIS.matcher(name);
            if (matcher.matches() && !matcher.group(1).isBlank()) {
                final String stem = matcher.group(1).strip();
                if (!axes.containsKey(stem)) {
                    axes.put(stem, new LinkedHashSet<>());
                    order.add("\u0000" + stem);
                }
                axes.get(stem).add(matcher.group(2).toUpperCase(Locale.ROOT));
            } else if (!name.isEmpty()) {
                order.add(name);
            }
        }
        return String.join(
                " ",
                order.stream()
                        .map(token -> {
                            if (!token.startsWith("\u0000")) return token;
                            final String stem = token.substring(1);
                            return stem + String.join("", axes.get(stem));
                        })
                        .toList());
    }
}
