package dev.turboism.sdk.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MeshToolsApiContractTest {

    @Test
    void exposesTheFrozenPreviewApiShapeWithoutAuthoringMethods() throws Exception {
        assertEquals(
                Registration.class,
                MeshToolRegistry.class.getMethod("register", MeshTool.class).getReturnType());
        assertEquals(
                Registration.class,
                MeshToolRegistry.class
                        .getMethod("contributeSlider", MeshToolbarSlider.class)
                        .getReturnType());
        assertTrue(AutoCloseable.class.isAssignableFrom(MeshToolRegistry.class));

        assertEquals(Drawable.class, MeshEditor.class.getMethod("mesh").getReturnType());
        assertEquals(
                VertexSelection.class, MeshEditor.class.getMethod("selection").getReturnType());
        assertEquals(
                void.class,
                MeshEditor.class.getMethod("select", VertexSelection.class).getReturnType());
        assertEquals(
                void.class,
                MeshEditor.class
                        .getMethod("select", VertexSelection.class, SelectionMode.class)
                        .getReturnType());
        final Set<String> editorMethods = Arrays.stream(MeshEditor.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertFalse(editorMethods.contains("addPoints"));
        assertFalse(editorMethods.contains("deletePoints"));
        assertFalse(editorMethods.contains("movePoints"));
        assertFalse(editorMethods.contains("addEdges"));
        assertFalse(editorMethods.contains("deleteEdges"));

        assertEquals(
                List.of("REPLACE", "ADD", "REMOVE", "TOGGLE"),
                Arrays.stream(SelectionMode.values()).map(Enum::name).toList());
        assertEquals(8, MeshBrush.MIN_RADIUS_PIXELS);
        assertEquals(128, MeshBrush.MAX_RADIUS_PIXELS);
        assertTrue(AutoCloseable.class.isAssignableFrom(MeshBrush.class));
        assertEquals(MeshEditor.class, MeshToolContext.class.getMethod("editor").getReturnType());
        assertEquals(
                MeshBrush.class,
                MeshToolContext.class.getMethod("selectionBrush").getReturnType());
    }

    @Test
    void alternateToolIconsDefaultToTheNormalPluginResource() {
        final MeshTool tool = new MeshTool() {
            @Override
            public String id() {
                return "tool";
            }

            @Override
            public String label() {
                return "Tool";
            }

            @Override
            public String iconResourcePath() {
                return "icons/tool.png";
            }

            @Override
            public void activate(final MeshToolContext context) {}
        };

        assertEquals("icons/tool.png", tool.activeIconResourcePath());
        assertEquals("icons/tool.png", tool.rollOverIconResourcePath());
        assertEquals("icons/tool.png", tool.selectedIconResourcePath());
        assertEquals("icons/tool.png", tool.disabledIconResourcePath());
        assertEquals("icons/tool.png", tool.disabledSelectedIconResourcePath());
        assertEquals(0, tool.order());
        tool.deactivate();
    }

    @Test
    void meshToolsAreDirectoryOnlyAndUnavailableByDefault() throws Exception {
        assertThrows(NoSuchMethodException.class, () -> PluginContext.class.getMethod("meshTools"));
        final var directory = new EmptyPluginContext().services();
        final var failure = assertThrows(
                dev.turboism.sdk.plugin.PluginServiceUnavailableException.class,
                () -> directory.require(MeshToolRegistry.class));
        assertEquals(MeshToolRegistry.class, failure.serviceType());
        assertEquals(
                dev.turboism.sdk.plugin.PluginService.MESH_TOOLS,
                failure.service().orElseThrow());
    }

    @Test
    void meshToolStrokeSelectionModeIsAdditiveAndDefaultsToAdd() throws Exception {
        final Method stroke =
                MeshTool.class.getMethod("strokeSelectionMode", boolean.class, boolean.class, boolean.class);
        assertTrue(stroke.isDefault(), "the gesture contract must stay source compatible");
        assertEquals(SelectionMode.class, stroke.getReturnType());

        final MeshTool legacy = new MeshTool() {
            @Override
            public String id() {
                return "legacy";
            }

            @Override
            public String label() {
                return "Legacy";
            }

            @Override
            public String iconResourcePath() {
                return "icons/legacy.png";
            }

            @Override
            public void activate(final MeshToolContext context) {}
        };

        assertEquals(SelectionMode.ADD, legacy.strokeSelectionMode(false, false, false));
        assertEquals(SelectionMode.ADD, legacy.strokeSelectionMode(true, false, false));
        assertEquals(SelectionMode.ADD, legacy.strokeSelectionMode(false, true, false));
        assertEquals(SelectionMode.ADD, legacy.strokeSelectionMode(true, true, true));
    }

    private static final class EmptyPluginContext implements PluginContext {
        @Override
        public PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public PluginLogger logger() {
            return null;
        }

        @Override
        public PluginPaths paths() {
            return null;
        }

        @Override
        public CubismFacade cubism() {
            return null;
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            return null;
        }

        @Override
        public ActionRegistry actions() {
            return null;
        }

        @Override
        public MenuRegistry menus() {
            return null;
        }

        @Override
        public UiScheduler uiScheduler() {
            return null;
        }

        @Override
        public DiagnosticReport diagnostics() {
            return null;
        }

        @Override
        public DisposableScope disposableScope() {
            return null;
        }
    }
}
