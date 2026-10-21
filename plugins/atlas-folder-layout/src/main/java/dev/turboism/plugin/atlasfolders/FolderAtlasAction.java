package dev.turboism.plugin.atlasfolders;

import dev.turboism.plugin.atlasfolders.layout.FolderTextureAtlasPlanner;
import dev.turboism.plugin.atlasfolders.layout.FolderTextureAtlasPlanner.Folder;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.textureatlas.*;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import java.util.*;
import java.util.function.Consumer;

/** Explicit whole-atlas action, separate from the native current-page packing invocation. */
final class FolderAtlasAction {
    private static final String ID = "texture-atlas.layout-folders";
    private final PluginContext context;

    FolderAtlasAction(PluginContext context) {
        this.context = context;
    }

    void register() {
        context.disposableScope().register(context.actions().register(ID, new ActionRegistry.Action() {
            @Override
            public String id() {
                return ID;
            }

            @Override
            public String label() {
                return text("folders.action");
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return ignored -> context.disposableScope()
                        .register(context.uiScheduler().runOnUiThread(FolderAtlasAction.this::run));
            }
        }));
        context.disposableScope()
                .register(context.menus()
                        .contribute(MenuRegistry.MenuContribution.of("Turboism/" + text("folders.action"), ID, 150)));
    }

    void run() {
        var options = FolderAtlasDialog.show(
                context.localization(),
                context.cubism().textureAtlasAlgorithms().algorithms());
        if (options.isEmpty()) return;
        var selected = options.orElseThrow();
        try {
            var service = context.cubism().textureAtlasLayouts();
            var snapshot = service.current().orElseThrow(() -> new IllegalStateException(text("folders.noAtlas")));
            var model = context.cubism().model().active();
            if (!model.id().value().equals(snapshot.modelId())
                    || snapshot.constraints().singlePageOptions() != null) {
                throw new IllegalStateException(text("folders.noAtlas"));
            }
            var tree = capture(model, snapshot.items(), text("folders.unassigned"));
            TextureAtlasLayoutPlanner algorithm = context.cubism()
                    .textureAtlasAlgorithms()
                    .find(selected.algorithm())
                    .filter(a -> !a.isNative() && a.planner().supportsCompleteAtlas())
                    .map(TextureAtlasLayoutAlgorithm::planner)
                    .orElseThrow(() -> new IllegalStateException(text("folders.notEnabled")));
            var plan = new FolderTextureAtlasPlanner(algorithm, selected.automaticSize())
                    .plan(
                            tree,
                            selected.maxEdge(),
                            snapshot.constraints().maxPages(),
                            Math.max(selected.margin(), snapshot.constraints().edgeMargin()),
                            Math.max(selected.padding(), snapshot.constraints().itemPadding()));
            var result = service.apply(snapshot.target(), plan);
            if (result.failureCode().isPresent())
                throw new IllegalStateException(result.message().orElseThrow());
            notifyStatus("INFO", context.localization().format("folders.success", plan.pageCount()));
        } catch (RuntimeException failure) {
            context.logger().error("Folder texture atlas layout failed.", failure);
            notifyStatus("ERROR", context.localization().format("folders.failed", failure.getMessage()));
        }
    }

    /** Assigns shared images to their users' lowest common ancestor, so no image is duplicated. */
    static Folder capture(CubismModel model, List<TextureAtlasLayoutItem> items, String unassigned) {
        var relations = model.textures().relations();
        if (!relations.isAvailable()) throw new IllegalStateException("Texture relations are unavailable.");
        Map<PartId, Part> parts = new LinkedHashMap<>();
        for (var part : model.parts().all()) {
            if (parts.put(part.id(), part) != null) throw new IllegalStateException("Duplicate Part identity.");
        }
        Map<String, List<PartId>> meshPaths = new HashMap<>();
        for (var mesh : model.drawables().all()) {
            meshPaths.put(mesh.id().value(), path(mesh.parentPartId().orElse(null), parts));
        }
        Map<String, List<TextureAtlasLayoutItem>> assigned = new HashMap<>();
        Map<String, ModelImageRelation> images = new HashMap<>();
        for (var image : relations.modelImages()) images.put(image.id().value(), image);
        for (var item : items) {
            var image = images.get(item.textureId());
            List<PartId> common = null;
            if (image != null) {
                for (var user : image.usingArtMeshIds()) {
                    var userPath = meshPaths.get(user.value());
                    if (userPath == null) throw new IllegalStateException("Image references a missing ArtMesh.");
                    if (common == null) common = new ArrayList<>(userPath);
                    else {
                        int count = 0;
                        while (count < common.size()
                                && count < userPath.size()
                                && common.get(count).equals(userPath.get(count))) count++;
                        common = new ArrayList<>(common.subList(0, count));
                    }
                }
            }
            String owner = common == null || common.isEmpty()
                    ? ""
                    : common.get(common.size() - 1).value();
            assigned.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(item);
        }
        // Validate even empty folders, including cycles disconnected from a root.
        for (var part : parts.values()) path(part.id(), parts);
        return new Folder(unassigned, assigned.getOrDefault("", List.of()), children(null, parts, assigned));
    }

    private static List<PartId> path(PartId id, Map<PartId, Part> parts) {
        List<PartId> result = new ArrayList<>();
        Set<PartId> seen = new HashSet<>();
        while (id != null) {
            if (!seen.add(id) || !parts.containsKey(id)) throw new IllegalStateException("Invalid Part hierarchy.");
            result.add(id);
            id = parts.get(id).parentId().orElse(null);
        }
        Collections.reverse(result);
        return result;
    }

    private static List<Folder> children(
            PartId parent, Map<PartId, Part> parts, Map<String, List<TextureAtlasLayoutItem>> assigned) {
        return parts.values().stream()
                .filter(p -> Objects.equals(p.parentId().orElse(null), parent))
                .sorted(Comparator.comparingInt(Part::index))
                .map(p -> new Folder(
                        p.name(), assigned.getOrDefault(p.id().value(), List.of()), children(p.id(), parts, assigned)))
                .toList();
    }

    private String text(String key) {
        return context.localization().text(key);
    }

    private void notifyStatus(String severity, String message) {
        context.services()
                .find(UiHostCapabilityService.class)
                .ifPresent(ui -> ui.notifyStatus(new StatusNotification(ID, severity, message)));
    }
}
