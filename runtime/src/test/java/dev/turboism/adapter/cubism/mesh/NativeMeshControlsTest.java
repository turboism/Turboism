package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NativeMeshControlsTest {
    @Test
    void reservesNativeButtonsIncludingTheirDefaultHitPaddingAndReadsMovedBounds() {
        final Fixture fixture = new Fixture();
        assertTrue(fixture.contains(275, 20));
        assertTrue(fixture.contains(248, 20), "native default padding must remain clickable");
        assertFalse(fixture.contains(240, 20));
        fixture.button.bounds = new Bounds(10, 150, 60, 180);
        assertFalse(fixture.contains(275, 20), "the old location must return to the brush");
        assertTrue(fixture.contains(35, 165));
    }

    @Test
    void ignoresDisabledHierarchyDocumentSpaceButtonsAndNonButtonEntities() {
        final Fixture fixture = new Fixture();
        fixture.button.enabled = false;
        assertFalse(fixture.contains(275, 20));
        fixture.button.enabled = true;
        fixture.button.onComponent = false;
        assertFalse(fixture.contains(275, 20));
        fixture.scene.root.entities = List.of(new Object());
        assertFalse(fixture.contains(275, 20));
    }

    @Test
    void reservesTheSeparateNativeIconButtonFamilyUsedByTheMeshConfirmation() {
        final Fixture fixture = new Fixture();
        final IconButton icon = new IconButton();
        fixture.scene.root.entities = List.of(icon);
        assertTrue(fixture.contains(275, 20));
        icon.enabled = false;
        assertFalse(fixture.contains(275, 20));
    }

    @Test
    void doesNotTreatAnUnreadableNativeSceneAsAnEmptyButtonList() {
        final Fixture fixture = new Fixture();
        fixture.button.bounds = null;
        assertThrows(NullPointerException.class, () -> fixture.contains(275, 20));
        fixture.scene.root.entities = null;
        assertThrows(IllegalStateException.class, () -> fixture.contains(275, 20));
    }

    private static final class Fixture {
        final Button button = new Button();
        final Scene scene = new Scene(new Root(List.of(button)));
        final View view = new View(scene);
        final VerifiedMemberResolver resolver = TestVerifiedResolvers.create(
                MeshToolSessionSelectorContract.ADAPTER_SLICE_ID,
                Set.of(MeshToolSessionSelectorContract.CAPABILITY_ID),
                List.of(
                        method(MeshToolSessionSelectorContract.VIEW_SCENE_GRAPH, View.class, "scene", Scene.class),
                        method(
                                MeshToolSessionSelectorContract.SCENE_COMPONENT_OBJECTS,
                                Scene.class,
                                "root",
                                Root.class),
                        method(MeshToolSessionSelectorContract.ENTITY_TRAVERSE_ALL, Root.class, "all", Iterable.class),
                        method(
                                MeshToolSessionSelectorContract.ENTITY_ENABLED_IN_HIERARCHY,
                                NativeButton.class,
                                "enabled",
                                boolean.class),
                        StaticSelector.classSelector(
                                MeshToolSessionSelectorContract.GUI_BUTTON_CLASS, name(Button.class)),
                        StaticSelector.classSelector(
                                "cubism.editor-model.gui-icon-button.class", name(IconButton.class)),
                        method(
                                MeshToolSessionSelectorContract.GUI_BUTTON_ON_COMPONENT,
                                Button.class,
                                "onComponent",
                                boolean.class),
                        method(
                                MeshToolSessionSelectorContract.GUI_BUTTON_COMPONENT_BOUNDS,
                                NativeButton.class,
                                "bounds",
                                Bounds.class),
                        StaticSelector.staticMethod(
                                MeshToolSessionSelectorContract.GUI_BOUNDS_CONTAINS,
                                name(Bounds.class),
                                "containsWithDefault",
                                MethodType.methodType(
                                                boolean.class,
                                                Bounds.class,
                                                Vector.class,
                                                float.class,
                                                int.class,
                                                Object.class)
                                        .toMethodDescriptorString(),
                                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                        StaticSelector.constructor(
                                MeshToolSessionSelectorContract.VECTOR_CREATE,
                                name(Vector.class),
                                "(FF)V",
                                StaticSelector.ACCESS_PUBLIC)),
                NativeMeshControlsTest.class.getClassLoader());

        boolean contains(final int x, final int y) {
            return NativeMeshControls.contains(resolver, view, x, y);
        }
    }

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String member, final Class<?> result) {
        return StaticSelector.method(
                alias,
                name(owner),
                member,
                MethodType.methodType(result).toMethodDescriptorString(),
                StaticSelector.ACCESS_PUBLIC);
    }

    private static String name(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    public record View(Scene scene) {}

    public record Scene(Root root) {}

    public static final class Root {
        Iterable<?> entities;

        Root(final Iterable<?> entities) {
            this.entities = entities;
        }

        public Iterable<?> all() {
            return entities;
        }
    }

    public static class NativeButton {
        boolean enabled = true;
        Bounds bounds = new Bounds(250, 8, 300, 38);

        public boolean enabled() {
            return enabled;
        }

        public Bounds bounds() {
            return bounds;
        }
    }

    public static final class Button extends NativeButton {
        boolean onComponent = true;

        public boolean onComponent() {
            return onComponent;
        }
    }

    public static final class IconButton extends NativeButton {}

    public record Vector(float x, float y) {}

    public record Bounds(float left, float top, float right, float bottom) {
        public static boolean containsWithDefault(
                final Bounds bounds, final Vector point, final float padding, final int mask, final Object marker) {
            if (padding != 0 || mask != 2 || marker != null) {
                throw new AssertionError("native default padding was not requested");
            }
            return point.x() >= bounds.left() - 3
                    && point.x() < bounds.right() + 3
                    && point.y() >= bounds.top() - 3
                    && point.y() < bounds.bottom() + 3;
        }
    }
}
