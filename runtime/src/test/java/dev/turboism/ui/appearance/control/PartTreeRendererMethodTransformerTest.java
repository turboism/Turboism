package dev.turboism.ui.appearance.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JTree;
import java.awt.Component;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartTreeRendererMethodTransformerTest {
    @AfterEach void clear() { NativePartTreeAppearanceBridge.clearForTesting(); }

    @Test
    void instrumentsExactRendererReturnAndPassesNode() throws Exception {
        final String owner = Target.class.getName().replace('.', '/');
        final PartTreeRendererMethodTransformer transformer = new PartTreeRendererMethodTransformer(
            owner, "getTreeCellRendererComponent",
            "(Ljavax/swing/JTree;Ljava/lang/Object;ZZZIZ)Ljava/awt/Component;",
            Target.class.getClassLoader()
        );
        final AtomicInteger calls = new AtomicInteger();
        NativePartTreeAppearanceBridge.install((component, value) -> {
            calls.incrementAndGet();
            ((JLabel) component).setText(value.toString());
            return component;
        });
        final byte[] transformed = transformer.transform(null, Target.class.getClassLoader(), owner, null, null, bytes());
        final Class<?> type = new Loader(Target.class.getClassLoader()).define(Target.class.getName(), transformed);
        final Object target = type.getConstructor().newInstance();
        final Method method = type.getMethod("getTreeCellRendererComponent", JTree.class, Object.class,
            boolean.class, boolean.class, boolean.class, int.class, boolean.class);
        final java.util.concurrent.atomic.AtomicReference<JLabel> result = new java.util.concurrent.atomic.AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try { result.set((JLabel) method.invoke(target, new JTree(), "node", false, false, false, 0, false)); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        assertEquals("node", result.get().getText());
        assertEquals(1, calls.get());
    }

    private static byte[] bytes() throws Exception {
        try (InputStream input = Target.class.getResourceAsStream("/" + Target.class.getName().replace('.', '/') + ".class")) {
            return input.readAllBytes();
        }
    }

    public static final class Target {
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
            boolean expanded, boolean leaf, int row, boolean focused) { return new JLabel("native"); }
    }
    private static final class Loader extends ClassLoader {
        Loader(ClassLoader parent) { super(parent); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
}
