package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Offline coverage for the host-native angle control: a fake {@code com.live2d} UI
 * tree is compiled in-test (same pattern as PinnedVerifiedResolverWorkflowTest) and
 * loaded through the panel's own class loader, so the reflective build/mount wiring
 * is exercised without the real Cubism host.
 */
final class RuntimeMeshEditUiServiceNativeTest {

    @TempDir
    Path tempDir;

    @Test
    void attachesHostNativeControlsAfterThePositionRowAndRoutesChangesThenRemovesOnClose() throws Exception {
        final Path classes = fakeHostClasses();
        try (URLClassLoader loader = new URLClassLoader(
            new URL[] { classes.toUri().toURL() }, getClass().getClassLoader())) {
            final Class<?> panelType = Class.forName(
                "com.live2d.cubism.view.palette.tool.toolMode.meshEditor.ToolPanel_MeshEdit", false, loader);
            final Class<?> sliderType = Class.forName("com.live2d.ui.control.CSlidableFloat", false, loader);
            final Class<?> buttonType = Class.forName("com.live2d.ui.control.CButton", false, loader);
            final Class<?> boxType = Class.forName("com.live2d.ui.container.CHBox", false, loader);
            final Object panel = panelType.getConstructor().newInstance();

            final RuntimeMeshEditUiService service = new RuntimeMeshEditUiService();
            final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
            final AtomicReference<Float> changed = new AtomicReference<>();
            final var registration = service.contributeMirrorAxisAngleControl(
                new MeshEditUiService.MirrorAxisAngleControl(
                    "mesh.mirror-axis.angle", "Mirror Axis Rotation", "Reset to 0°",
                    -180.0f, 180.0f, 0.1f, changed::set
                )
            );
            // The host widget (createWidgetMirrorEditForMeshEdit's CVBox return value) is
            // the mount target; the panel's mirrorEditFoldingPane field is assigned only
            // after that method returns, so it is not available at hook time.
            // The host CVBox already holds the angle row (index 0) and the position
            // row (index 1); the reviewed hook inserts our root after them (index 2).
            final Object widget = invoke(invoke(panel, "getMirrorEditFoldingPane"), "getChild");
            invoke(widget, "add", "angle-row", 0);
            invoke(widget, "add", "position-row", 1);
            service.attachNative(panel, widget, axis);
            service.attachNative(panel, widget, axis);
            SwingUtilities.invokeAndWait(() -> { });

            assertNotNull(service.nativeAttachment());
            final Object mount = widget;
            final List<?> mountChildren = children(mount);
            assertEquals(3, mountChildren.size());
            final Object root = mountChildren.get(2);
            final List<?> wrapperChildren = children(root);
            assertEquals(2, wrapperChildren.size());
            final Object box = wrapperChildren.get(1);
            assertSame(boxType, box.getClass());
            final List<?> rowChildren = children(box);
            assertEquals(3, rowChildren.size());
            final Object slider = rowChildren.get(0);
            assertSame(sliderType, slider.getClass());
            final Object button = rowChildren.get(2);
            assertSame(buttonType, button.getClass());
            assertEquals("Reset to 0°", invoke(button, "getToolTipText"));

            // The CSlidableFloat value change routes through the Function1 proxy.
            invoke(slider, "setValue", 12.3f);
            assertEquals(12.3f, changed.get(), 0.0001f);

            // The reset CButton action routes through its Function1 proxy.
            invoke(invoke(button, "getOnAction"), "invoke", button);
            assertEquals(0.0f, changed.get(), 0.0001f);
            assertEquals(0.0f, ((Number) invoke(slider, "getValue")).floatValue(), 0.0001f);

            registration.close();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(2, mountChildren.size());
        }
    }

    @Test
    void emptyResetToolTipLeavesTheHostDefaultTooltipUntouched() throws Exception {
        final Path classes = fakeHostClasses();
        try (URLClassLoader loader = new URLClassLoader(
            new URL[] { classes.toUri().toURL() }, getClass().getClassLoader())) {
            final Class<?> panelType = Class.forName(
                "com.live2d.cubism.view.palette.tool.toolMode.meshEditor.ToolPanel_MeshEdit", false, loader);
            final Class<?> buttonType = Class.forName("com.live2d.ui.control.CButton", false, loader);
            final Object panel = panelType.getConstructor().newInstance();

            final RuntimeMeshEditUiService service = new RuntimeMeshEditUiService();
            final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
            final var registration = service.contributeMirrorAxisAngleControl(
                new MeshEditUiService.MirrorAxisAngleControl(
                    "mesh.mirror-axis.angle", "Mirror Axis Rotation", "",
                    -180.0f, 180.0f, 0.1f, ignored -> { }
                )
            );
            final Object widget = invoke(invoke(panel, "getMirrorEditFoldingPane"), "getChild");
            service.attachNative(panel, widget, axis);
            SwingUtilities.invokeAndWait(() -> { });

            final List<?> wrapperChildren = children(children(widget).get(0));
            final List<?> rowChildren = children(wrapperChildren.get(1));
            final Object button = rowChildren.get(2);
            assertSame(buttonType, button.getClass());
            assertNull(invoke(button, "getToolTipText"));

            registration.close();
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    @Test
    void attachFailsClosedWhenHostControlClassesAreUnavailable() throws Exception {
        final RuntimeMeshEditUiService service = new RuntimeMeshEditUiService();
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        final var registration = service.contributeMirrorAxisAngleControl(
                new MeshEditUiService.MirrorAxisAngleControl(
                    "mesh.mirror-axis.angle", "Mirror Axis Rotation", null,
                    -180.0f, 180.0f, 0.1f, ignored -> { }
                )
        );
        // A plain panel: no folding pane, and its loader cannot provide com.live2d classes.
        service.attachNative(new Object(), new Object(), axis);
        SwingUtilities.invokeAndWait(() -> { });
        assertNull(service.nativeAttachment());

        service.resetSession();
        registration.close();
        SwingUtilities.invokeAndWait(() -> { });
        assertNull(service.nativeAttachment());
    }

    @Test
    void reportsTheLiveConsumerContributionLifecycle() {
        final RuntimeMeshEditUiService service = new RuntimeMeshEditUiService();
        final java.util.List<Boolean> changes = new ArrayList<>();
        final var observer = service.observeContribution(changes::add);
        final var registration = service.contributeMirrorAxisAngleControl(
                new MeshEditUiService.MirrorAxisAngleControl(
                    "mesh.mirror-axis.angle", "Mirror Axis Rotation", "",
                    -180.0f, 180.0f, 0.1f, ignored -> { }
                )
        );
        registration.close();
        observer.close();

        assertEquals(List.of(false, true, false), changes);
    }

    private Path fakeHostClasses() throws Exception {
        final Path sourceRoot = Files.createDirectories(tempDir.resolve("fake-host/src"));
        final Path classes = Files.createDirectories(tempDir.resolve("fake-host/classes"));
        final List<FakeSource> sources = List.of(
            new FakeSource("kotlin/Unit.java", """
                package kotlin;
                public final class Unit {
                    public static final Unit INSTANCE = new Unit();
                    private Unit() { }
                }
                """),
            new FakeSource("kotlin/jvm/functions/Function1.java", """
                package kotlin.jvm.functions;
                public interface Function1<P, R> { R invoke(P p); }
                """),
            new FakeSource("com/live2d/ui/control/CLabel.java", """
                package com.live2d.ui.control;
                public final class CLabel {
                    private final String text;
                    public CLabel(String text) { this.text = text; }
                    public String getText() { return text; }
                }
                """),
            new FakeSource("com/live2d/ui/control/CSlidableFloat.java", """
                package com.live2d.ui.control;
                import kotlin.jvm.functions.Function1;
                public final class CSlidableFloat {
                    private float value;
                    private Function1 onChanged;
                    public void setMin(float min) { }
                    public void setMax(float max) { }
                    public void setKeta(int keta) { }
                    public void setValue(float value) {
                        this.value = value;
                        if (onChanged != null) onChanged.invoke(value);
                    }
                    public float getValue() { return value; }
                    public void setOnChanged(Function1 onChanged) { this.onChanged = onChanged; }
                    public void addOnAction(Function1 onAction) { }
                }
                """),
            new FakeSource("com/live2d/ui/control/CButton.java", """
                package com.live2d.ui.control;
                import kotlin.jvm.functions.Function1;
                public final class CButton {
                    private Function1 onAction;
                    private String toolTipText;
                    public void setText(String text) { }
                    public void setPrefWidth(int width) { }
                    public void setPrefHeight(int height) { }
                    public void setToolTipText(String tip) { this.toolTipText = tip; }
                    public String getToolTipText() { return toolTipText; }
                    public void addOnAction(Function1 onAction) { this.onAction = onAction; }
                    public Object getOnAction() { return onAction; }
                }
                """),
            new FakeSource("com/live2d/ui/container/CSpacer.java", """
                package com.live2d.ui.container;
                public final class CSpacer {
                    public CSpacer(int width, int height, int gap, Object parent) { }
                }
                """),
            new FakeSource("com/live2d/ui/container/CVBox.java", """
                package com.live2d.ui.container;
                import java.util.ArrayList;
                import java.util.List;
                public class CVBox {
                    private final List<Object> children = new ArrayList<>();
                    public void add(Object child, int index) { children.add(index, child); }
                    public void unaryPlus(Object child) { children.add(child); }
                    public List<Object> getChildren() { return children; }
                    public void revalidate() { }
                    public void repaint() { }
                }
                """),
            new FakeSource("com/live2d/ui/container/CHBox.java", """
                package com.live2d.ui.container;
                public class CHBox extends CVBox { }
                """),
            new FakeSource("com/live2d/ui/container/CFoldingPane.java", """
                package com.live2d.ui.container;
                public final class CFoldingPane {
                    private final CVBox child = new CVBox();
                    public CVBox getChild() { return child; }
                }
                """),
            new FakeSource("com/live2d/cubism/view/palette/tool/toolMode/meshEditor/ToolPanel_MeshEdit.java", """
                package com.live2d.cubism.view.palette.tool.toolMode.meshEditor;
                import com.live2d.ui.control.CLabel;
                import com.live2d.ui.container.CFoldingPane;
                import com.live2d.ui.container.CHBox;
                import com.live2d.ui.container.CVBox;
                public final class ToolPanel_MeshEdit {
                    private final CFoldingPane mirrorEditFoldingPane = new CFoldingPane();
                    public CFoldingPane getMirrorEditFoldingPane() { return mirrorEditFoldingPane; }
                    public static CVBox createWidgetMirrorEditForMeshEdit$createComp(CLabel label, CHBox row) {
                        CVBox box = new CVBox();
                        box.add(label, 0);
                        box.add(row, 1);
                        return box;
                    }
                }
                """)
        );
        for (FakeSource source : sources) {
            final Path file = sourceRoot.resolve(source.path());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.body());
        }
        final List<String> command = new ArrayList<>();
        command.add("-d");
        command.add(classes.toString());
        for (FakeSource source : sources) command.add(sourceRoot.resolve(source.path()).toString());
        final int exit = ToolProvider.getSystemJavaCompiler().run(
            null, null, null, command.toArray(new String[0]));
        assertEquals(0, exit, "fake host compilation failed");
        return classes;
    }

    private static List<?> children(final Object container) throws Exception {
        return (List<?>) container.getClass().getMethod("getChildren").invoke(container);
    }

    private static Object invoke(final Object target, final String name, final Object... arguments) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                return method.invoke(target, arguments);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
    }

    private record FakeSource(String path, String body) { }
}
