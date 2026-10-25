package dev.turboism.plugin.deformerbatchrename;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import java.util.regex.Pattern;

/** Builds detached previews without changing the model. */
final class RenamePlanner {
    record Row(DeformerId id, boolean rotation, String original, String target) {
        boolean changed() {
            return !original.equals(target);
        }
    }

    private static final Pattern AXIS = Pattern.compile("^(.*?)[\\s_]*([XYZxyz])$");

    static List<Row> preview(CubismModel model) {
        List<Deformer> deformers = model.deformers().all();
        // The session wrapper exposes generic Deformer handles in the unified collection.
        // Determine kinds through typed collections rather than instanceof on those handles.
        Set<DeformerId> warpIds = new HashSet<>();
        Set<DeformerId> rotationIds = new HashSet<>();
        model.warpDeformers().all().forEach(d -> warpIds.add(d.id()));
        model.rotationDeformers().all().forEach(d -> rotationIds.add(d.id()));
        Map<DeformerId, List<Deformer>> children = new HashMap<>();
        Map<DeformerId, List<String>> meshes = new HashMap<>();
        for (Deformer d : deformers) {
            d.parentDeformerId()
                    .ifPresent(parent -> children.computeIfAbsent(parent, k -> new ArrayList<>())
                            .add(d));
        }
        for (Drawable mesh : model.drawables().all()) {
            mesh.parentDeformerId()
                    .ifPresent(parent -> meshes.computeIfAbsent(parent, k -> new ArrayList<>())
                            .add(mesh.name()));
        }
        List<Row> rows = new ArrayList<>();
        for (Deformer d : deformers) {
            if (!warpIds.contains(d.id()) && !rotationIds.contains(d.id())) continue;
            List<String> leaves = leaves(d, children, meshes, new HashSet<>());
            String base = baseName(leaves);
            String suffix = parameterSuffix(d.getParameterBindings().stream()
                    .map(ParameterBinding::parameterId)
                    .distinct()
                    .map(id -> model.parameters()
                            .find(id)
                            .name()
                            .filter(n -> !n.isBlank())
                            .orElse(id.value()))
                    .toList());
            String target = base.isEmpty() ? d.name() : base + (suffix.isEmpty() ? "" : " " + suffix);
            rows.add(new Row(d.id(), rotationIds.contains(d.id()), d.name(), target));
        }
        return List.copyOf(rows);
    }

    private static List<String> leaves(
            Deformer d,
            Map<DeformerId, List<Deformer>> children,
            Map<DeformerId, List<String>> meshes,
            Set<DeformerId> visiting) {
        if (!visiting.add(d.id())) throw new IllegalStateException("Cyclic deformer hierarchy");
        List<String> names = new ArrayList<>(meshes.getOrDefault(d.id(), List.of()));
        for (Deformer child : children.getOrDefault(d.id(), List.of())) {
            List<String> nested = leaves(child, children, meshes, visiting);
            if (nested.isEmpty()) names.add(child.name());
            else names.addAll(nested);
        }
        visiting.remove(d.id());
        return names;
    }

    static String baseName(List<String> names) {
        List<String> clean =
                names.stream().map(String::strip).filter(n -> !n.isEmpty()).toList();
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

    static String parameterSuffix(List<String> names) {
        // Only names ending in a single axis are compacted; unrelated names remain intact.
        Map<String, LinkedHashSet<String>> axes = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (String raw : new LinkedHashSet<>(names)) {
            String name = raw.strip();
            var matcher = AXIS.matcher(name);
            if (matcher.matches() && !matcher.group(1).isBlank()) {
                String stem = matcher.group(1).strip();
                if (!axes.containsKey(stem)) {
                    axes.put(stem, new LinkedHashSet<>());
                    order.add("\u0000" + stem);
                }
                axes.get(stem).add(matcher.group(2).toUpperCase(Locale.ROOT));
            } else if (!name.isEmpty()) order.add(name);
        }
        return String.join(
                " ",
                order.stream()
                        .map(token -> {
                            if (!token.startsWith("\u0000")) return token;
                            String stem = token.substring(1);
                            return stem + String.join("", axes.get(stem));
                        })
                        .toList());
    }
}
