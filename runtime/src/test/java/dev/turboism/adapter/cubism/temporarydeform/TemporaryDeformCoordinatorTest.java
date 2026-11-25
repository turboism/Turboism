package dev.turboism.adapter.cubism.temporarydeform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformContext;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformPointerEvent;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformRenderScene;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformSession;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformTool;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EdtDispatch;
import java.awt.Window;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JSeparator;
import org.junit.jupiter.api.Test;

class TemporaryDeformCoordinatorTest {

    @Test
    void injectsMenuItemBeforeNativeUnbindSeparatorAndRemovesCleanlyOnUnregister() {
        final JFrame frame = new JFrame();
        final JMenuItem scaleItem = new JMenuItem("Scale");
        final JMenuItem unbindItem = new JMenuItem("Unbind");
        unbindItem.setActionCommand(NativeTemporaryDeformMenuAdapter.UNBIND_COMMAND_ID);

        final JMenu tempMenu = new JMenu("Temporary Deform Tool");
        tempMenu.add(scaleItem);
        tempMenu.add(new JSeparator());
        tempMenu.add(unbindItem);

        final JMenuBar menuBar = new JMenuBar();
        menuBar.add(tempMenu);
        frame.setJMenuBar(menuBar);

        final NativeTemporaryDeformMenuAdapter menuAdapter =
                new NativeTemporaryDeformMenuAdapter(() -> new Window[] {frame});
        final TemporaryDeformCoordinator coordinator =
                new TemporaryDeformCoordinator(menuAdapter, new NativeTemporaryDeformToolDetailAdapter());
        try {
            final PermissionChecker permissions = allPermissions("plugin.a");
            final Registration registration =
                    coordinator.register("plugin.a", 1L, dummyTool("row-col", "Row/Column"), permissions);

            final int countWithCustom = EdtDispatch.call("inspect menu", tempMenu::getMenuComponentCount);
            assertEquals(5, countWithCustom, "expected original 3 components + 1 separator + 1 tool item");
            assertEquals(scaleItem, tempMenu.getMenuComponent(0));
            assertTrue(tempMenu.getMenuComponent(1) instanceof JSeparator);
            assertTrue(tempMenu.getMenuComponent(2) instanceof JMenuItem injected
                    && "Row/Column".equals(injected.getText()));
            assertEquals(unbindItem, tempMenu.getMenuComponent(4));

            registration.close();
            final int countAfterRemove = EdtDispatch.call("inspect menu", tempMenu::getMenuComponentCount);
            assertEquals(3, countAfterRemove, "custom separator and item must be removed on close");
        } finally {
            coordinator.close();
            EdtDispatch.runEventually("dispose test frame", frame::dispose);
        }
    }

    @Test
    void runtimeRegistryRequiresMenuAndWritePermissions() {
        final TemporaryDeformCoordinator coordinator = new TemporaryDeformCoordinator();
        final var lifecycle = new dev.turboism.ui.host.RuntimeEditorUiHostLifecycle();
        final var authority = new dev.turboism.ui.contribution.EditorUiContributionAuthority(lifecycle);
        final var registry = new RuntimeTemporaryDeformToolRegistry(
                "plugin.missing.perm",
                1L,
                PermissionChecker.from(new CubismPermissionGate(
                        "plugin.missing.perm",
                        List.of(permission(PermissionIds.TURBOISM_CUBISM_MODEL_READ)),
                        ignored -> {},
                        Clock.systemUTC())),
                coordinator,
                authority);
        try {
            assertThrows(
                    dev.turboism.sdk.permission.CubismPermissionException.class,
                    () -> registry.register(dummyTool("t", "Tool")));
        } finally {
            registry.close();
            authority.close();
            lifecycle.close();
            coordinator.close();
        }
    }

    @Test
    void toolDetailDropdownReconciliationSuppressesSpuriousNativeSelectionEvents() throws Exception {
        final Path resolvedJar =
                TemporaryDeformNativeTransformerTest.resolveFirstAvailableCubismJar("5.3.03", "5.3.02");
        assumeTrue(Files.isRegularFile(resolvedJar), "local Cubism JAR required for CComboBox2 verification");

        try (URLClassLoader hostLoader = new URLClassLoader(
                new URL[] {resolvedJar.toUri().toURL()}, TemporaryDeformCoordinator.class.getClassLoader())) {
            final Class<?> comboClass = Class.forName("com.live2d.ui.control.CComboBox2", true, hostLoader);
            final Class<?> fn1Class = Class.forName("kotlin.jvm.functions.Function1", true, hostLoader);
            final Class<?> unitClass = Class.forName("kotlin.Unit", true, hostLoader);
            final Object unitInstance = unitClass.getField("INSTANCE").get(null);

            final AtomicInteger nativeCallbacks = new AtomicInteger();
            final AtomicInteger beforeNativeCount = new AtomicInteger();
            final AtomicInteger customCallbacks = new AtomicInteger();

            final Object combo = comboClass.getConstructor(Object[].class).newInstance((Object)
                    new Object[] {"NATIVE_A", "NATIVE_B"});
            final Object nativeListener = Proxy.newProxyInstance(hostLoader, new Class<?>[] {fn1Class}, (p, m, a) -> {
                if ("invoke".equals(m.getName())) {
                    nativeCallbacks.incrementAndGet();
                    return unitInstance;
                }
                return null;
            });
            comboClass.getMethod("setOnSelected", fn1Class).invoke(combo, nativeListener);

            final class FakePair {
                @SuppressWarnings("unused")
                final Object comboRef = combo;
            }
            final class FakeInspector {
                @SuppressWarnings("unused")
                public List<FakePair> getChildPropertyAndInspectors() {
                    return List.of(new FakePair());
                }
            }
            final class FakePanel {
                @SuppressWarnings("unused")
                final Object a = new FakeInspector();
            }
            final FakePanel panel = new FakePanel();
            final NativeTemporaryDeformToolDetailAdapter adapter = new NativeTemporaryDeformToolDetailAdapter();
            final List<NativeTemporaryDeformToolDetailAdapter.ToolDetailEntry> initialTools = List.of(
                    new NativeTemporaryDeformToolDetailAdapter.ToolDetailEntry("p:1:row-col", "Row/Column", 100));
            final List<NativeTemporaryDeformToolDetailAdapter.ToolDetailEntry> updatedTools =
                    List.of(new NativeTemporaryDeformToolDetailAdapter.ToolDetailEntry(
                            "p:1:row-col", "Row/Column Updated", 100));

            EdtDispatch.call("reconcile tool detail combo", () -> {
                adapter.reconcilePanel(
                        panel,
                        initialTools,
                        "p:1:row-col",
                        List.of(),
                        (id, v) -> {},
                        id -> customCallbacks.incrementAndGet(),
                        beforeNativeCount::incrementAndGet);
                adapter.reconcilePanel(
                        panel,
                        updatedTools,
                        "p:1:row-col",
                        List.of(),
                        (id, v) -> {},
                        id -> customCallbacks.incrementAndGet(),
                        beforeNativeCount::incrementAndGet);
                adapter.clearAll();
                return null;
            });

            assertEquals(0, nativeCallbacks.get(), "reconciling dropdown must not fire original native selection");
            assertEquals(0, beforeNativeCount.get(), "reconciling dropdown must not trigger native-switch callback");
            assertEquals(0, customCallbacks.get(), "programmatic selection must not loop back into custom activation");
        }
    }

    @Test
    void warpTargetAdapterWritesEditableKeyformAndSkipsMissingModelUpdate() throws Exception {
        final Path resolvedJar =
                TemporaryDeformNativeTransformerTest.resolveFirstAvailableCubismJar("5.3.03", "5.3.02");
        assumeTrue(Files.isRegularFile(resolvedJar), "local Cubism JAR required for host vector and guid types");

        try (URLClassLoader hostLoader = new URLClassLoader(
                new URL[] {resolvedJar.toUri().toURL()}, TemporaryDeformCoordinator.class.getClassLoader())) {
            final Class<?> vecClass = Class.forName("com.live2d.graphics3d.type.GVector2", true, hostLoader);
            final Class<?> pointRefExClass = Class.forName("com.live2d.doc.selection.IPointRefEx", false, hostLoader);
            final com.live2d.type.Guid deformerGuid = new com.live2d.type.Guid() {};

            final float[] calculatedCoords = new float[] {10.0F, 20.0F};
            final float[] editableCoords = new float[] {10.0F, 20.0F};
            final Object calculatedPoint =
                    Proxy.newProxyInstance(hostLoader, new Class<?>[] {pointRefExClass}, (p, m, a) -> {
                        if ("e".equals(m.getName())) {
                            return vecClass.getConstructor(float.class, float.class)
                                    .newInstance(calculatedCoords[0], calculatedCoords[1]);
                        }
                        if ("a".equals(m.getName())) {
                            calculatedCoords[0] =
                                    ((Number) vecClass.getMethod("getX").invoke(a[0])).floatValue();
                            calculatedCoords[1] =
                                    ((Number) vecClass.getMethod("getY").invoke(a[0])).floatValue();
                            return null;
                        }
                        return null;
                    });
            final Object editablePoint =
                    Proxy.newProxyInstance(hostLoader, new Class<?>[] {pointRefExClass}, (p, m, a) -> {
                        if ("e".equals(m.getName())) {
                            return vecClass.getConstructor(float.class, float.class)
                                    .newInstance(editableCoords[0], editableCoords[1]);
                        }
                        if ("a".equals(m.getName())) {
                            editableCoords[0] =
                                    ((Number) vecClass.getMethod("getX").invoke(a[0])).floatValue();
                            editableCoords[1] =
                                    ((Number) vecClass.getMethod("getY").invoke(a[0])).floatValue();
                            return null;
                        }
                        return null;
                    });
            final class FakeModel {}
            final class FakeEditableForm {
                @SuppressWarnings("unused")
                public List<Object> getAllPointRefEx(final FakeModel m) {
                    return List.of(editablePoint);
                }
            }
            final FakeEditableForm editableForm = new FakeEditableForm();
            final class FakeSource {
                @SuppressWarnings("unused")
                public String getId() {
                    return "warp_0";
                }

                @SuppressWarnings("unused")
                public Object getGuid() {
                    return deformerGuid;
                }

                @SuppressWarnings("unused")
                public int getCol() {
                    return 0;
                }

                @SuppressWarnings("unused")
                public int getRow() {
                    return 0;
                }
            }
            final FakeSource source = new FakeSource();
            final class FakeDeformer {
                @SuppressWarnings("unused")
                public FakeSource getSource() {
                    return source;
                }

                @SuppressWarnings("unused")
                public List<Object> getAllPointRefEx() {
                    return List.of(calculatedPoint);
                }
            }
            final Object actionContext = new Object() {
                @SuppressWarnings("unused")
                public Object a(final com.live2d.type.Guid guid, final boolean create) {
                    return guid == deformerGuid && !create ? editableForm : null;
                }
            };

            final NativeWarpDeformerTargetAdapter adapter =
                    new NativeWarpDeformerTargetAdapter(new FakeDeformer(), new FakeModel(), 0, hostLoader);
            adapter.setActionContext(actionContext);
            adapter.snapshotStrokeBaseline();
            adapter.applyConversionDeltas(Map.of(0, new dev.turboism.sdk.cubism.model.Point2(5.0F, -12.0F)));

            assertEquals(15.0F, editableCoords[0], 0.001F);
            assertEquals(8.0F, editableCoords[1], 0.001F);
            assertEquals(10.0F, calculatedCoords[0], 0.001F, "read-only calculated form must not be written directly");
        }
    }

    private static PermissionChecker allPermissions(final String owner) {
        return PermissionChecker.from(new CubismPermissionGate(
                owner,
                List.of(
                        permission(PermissionIds.TURBOISM_CUBISM_MODEL_READ),
                        permission(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE),
                        permission(PermissionIds.TURBOISM_UI_MENU_CONTRIBUTE)),
                ignored -> {},
                Clock.systemUTC()));
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String scope() {
                return "application";
            }

            @Override
            public String reason() {
                return "test";
            }
        };
    }

    private static TemporaryDeformTool dummyTool(final String id, final String label) {
        final AtomicInteger hits = new AtomicInteger();
        return new TemporaryDeformTool() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return label;
            }

            @Override
            public TemporaryDeformSession createSession(final TemporaryDeformContext context) {
                hits.incrementAndGet();
                return new TemporaryDeformSession() {
                    @Override
                    public void onPointerPress(final TemporaryDeformPointerEvent event) {}

                    @Override
                    public void onPointerDrag(final TemporaryDeformPointerEvent event) {}

                    @Override
                    public TemporaryDeformRenderScene render(final float cameraScale, final boolean valid) {
                        return TemporaryDeformRenderScene.EMPTY;
                    }
                };
            }
        };
    }
}
