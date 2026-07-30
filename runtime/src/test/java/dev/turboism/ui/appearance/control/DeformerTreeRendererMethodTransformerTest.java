package dev.turboism.ui.appearance.control;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JTree;
import java.awt.Component;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeformerTreeRendererMethodTransformerTest {

    @AfterEach
    void clearBridge() {
        NativeDeformerTreeAppearanceBridge.clearForTesting();
    }

    @Test
    void instrumentsOnlyTheExactRendererAndPassesTheReturnedComponentAndRowState() throws Exception {
        final String owner = Target.class.getName().replace('.', '/');
        final DeformerTreeRendererMethodTransformer transformer =
            new DeformerTreeRendererMethodTransformer(
                owner,
                "getTreeCellRendererComponent",
                "(Ljavax/swing/JTree;Ljava/lang/Object;ZZZIZ)Ljava/awt/Component;",
                Target.class.getClassLoader()
            );
        final AtomicInteger callbacks = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Object> seenValue = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Boolean> seenSelected = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Boolean> seenFocused = new java.util.concurrent.atomic.AtomicReference<>();
        NativeDeformerTreeAppearanceBridge.install((component, value, selected, focused) -> {
            callbacks.incrementAndGet();
            seenValue.set(value);
            seenSelected.set(selected);
            seenFocused.set(focused);
            ((JLabel) component).setText("bridged");
            return component;
        });

        final java.util.concurrent.atomic.AtomicReference<JLabel> result = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                final byte[] transformed = transformer.transform(
                    null,
                    Target.class.getClassLoader(),
                    owner,
                    null,
                    null,
                    bytes(Target.class)
                );
                final Class<?> type = new FixtureLoader(Target.class.getClassLoader()).define(Target.class.getName(), transformed);
                final Object target = type.getConstructor().newInstance();
                final Method method = type.getMethod(
                    "getTreeCellRendererComponent",
                    JTree.class,
                    Object.class,
                    boolean.class,
                    boolean.class,
                    boolean.class,
                    int.class,
                    boolean.class
                );
                result.set((JLabel) method.invoke(target, new JTree(), "row", false, false, false, 1, true));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());

        assertEquals("bridged", result.get().getText());
        assertEquals(1, callbacks.get());
        assertEquals("row", seenValue.get());
        assertEquals(false, seenSelected.get());
        assertEquals(true, seenFocused.get());
        assertNull(transformer.transform(null, Target.class.getClassLoader(), owner + "X", null, null, bytes(Target.class)));
        assertNull(transformer.transform(null, new ClassLoader() { }, owner, null, null, bytes(Target.class)));
    }

    private static byte[] bytes(final Class<?> type) throws IOException {
        final String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            return input.readAllBytes();
        }
    }

    public static final class Target {
        public Component getTreeCellRendererComponent(
            final JTree tree,
            final Object value,
            final boolean selected,
            final boolean expanded,
            final boolean leaf,
            final int row,
            final boolean focused
        ) {
            return new JLabel("native");
        }
    }

    private static final class FixtureLoader extends ClassLoader {
        private FixtureLoader(final ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
