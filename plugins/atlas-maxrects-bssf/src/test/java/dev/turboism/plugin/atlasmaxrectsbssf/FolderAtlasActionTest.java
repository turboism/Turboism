package dev.turboism.plugin.atlasmaxrectsbssf;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;

class FolderAtlasActionTest {
    @Test
    void capturesPaletteOrderNestedOwnershipSharedAndUnusedImages() {
        var parts = List.of(
                part("body", null, 2), part("head", null, 0), part("right", "head", 1), part("left", "head", 0));
        var meshes = List.of(mesh("l", "left"), mesh("r", "right"), mesh("b", "body"));
        var relations = List.of(image("eye", "l"), image("shared", "l", "r"), image("cross", "l", "b"));
        var root = FolderAtlasAction.capture(
                model(parts, meshes, relations),
                List.of(item("eye"), item("shared"), item("cross"), item("unused")),
                "unassigned");
        assertEquals(
                List.of("head", "body"),
                root.children().stream().map(f -> f.name()).toList());
        assertEquals(
                List.of("cross", "unused"),
                root.images().stream().map(TextureAtlasLayoutItem::textureId).toList());
        var head = root.children().get(0);
        assertEquals(
                List.of("shared"),
                head.images().stream().map(TextureAtlasLayoutItem::textureId).toList());
        assertEquals(
                List.of("left", "right"),
                head.children().stream().map(f -> f.name()).toList());
        assertEquals("eye", head.children().get(0).images().get(0).textureId());
    }

    @Test
    void rejectsBrokenOrCyclicHierarchyBeforePlanning() {
        assertThrows(
                IllegalStateException.class,
                () -> FolderAtlasAction.capture(
                        model(List.of(part("a", "b", 0), part("b", "a", 0)), List.of(), List.of()),
                        List.of(item("unused")),
                        "root"));
        assertThrows(
                IllegalStateException.class,
                () -> FolderAtlasAction.capture(
                        model(List.of(part("a", "missing", 0)), List.of(), List.of()),
                        List.of(item("unused")),
                        "root"));
    }

    private static CubismModel model(List<Part> parts, List<Drawable> meshes, List<ModelImageRelation> images) {
        var relations = new TextureRelationsSnapshot(
                TextureRelationsSnapshot.Availability.AVAILABLE,
                "binding",
                1,
                1,
                List.of(),
                images,
                List.of(),
                List.of());
        return stub(
                CubismModel.class,
                Map.of(
                        "parts",
                        stub(Parts.class, Map.of("all", parts)),
                        "drawables",
                        stub(Drawables.class, Map.of("all", meshes)),
                        "textures",
                        stub(ModelTextures.class, Map.of("relations", relations))));
    }

    private static Part part(String id, String parent, int index) {
        return stub(
                Part.class,
                Map.of(
                        "id",
                        new PartId(id),
                        "name",
                        id,
                        "index",
                        index,
                        "parentId",
                        Optional.ofNullable(parent).map(PartId::new)));
    }

    private static Drawable mesh(String id, String parent) {
        return stub(Drawable.class, Map.of("id", new ArtMeshId(id), "parentPartId", Optional.of(new PartId(parent))));
    }

    private static ModelImageRelation image(String id, String... users) {
        return new ModelImageRelation(
                new ModelImageId(id),
                stub(ModelImageEntry.class, Map.of()),
                List.of(),
                Optional.empty(),
                Map.of(),
                Arrays.stream(users).map(ArtMeshId::new).toList());
    }

    private static TextureAtlasLayoutItem item(String id) {
        return new TextureAtlasLayoutItem(id, 10, 10);
    }

    private static <T> T stub(Class<T> type, Map<String, Object> methods) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (methods.containsKey(method.getName())) return methods.get(method.getName());
            throw new UnsupportedOperationException(method.getName());
        }));
    }
}
