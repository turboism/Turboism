package dev.turboism.adapter.cubism.textureatlas;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasAutoLayoutDialogTransformerTest {

    @Test
    void invokesTheIngressAtTheEndOfTheConstructorAndStillCompletesConstruction() throws Exception {
        final String key = "test.texture-atlas.dialog-ingress";
        final AtomicReference<Object> received = new AtomicReference<>();
        final AtomicBoolean constructionCompleted = new AtomicBoolean();
        System.getProperties().put(key, (Consumer<Object>) received::set);
        try {
            final TextureAtlasAutoLayoutDialogTransformer transformer =
                new TextureAtlasAutoLayoutDialogTransformer(
                    "fixture/AtlasDialog", "(Ljava/lang/Object;)V", null, key
                );
            final byte[] transformed = transformer.transform(
                null, null, "fixture/AtlasDialog", null, null, fixtureClass()
            );
            final FixtureLoader loader = new FixtureLoader(null);
            final Class<?> type = loader.define("fixture.AtlasDialog", transformed);
            final Object instance = type.getConstructor(Object.class).newInstance(new Object());
            assertEquals(instance, received.get());
            constructionCompleted.set(true);
        } finally {
            System.getProperties().remove(key);
        }
        assertTrue(constructionCompleted.get());
    }

    @Test
    void contributorIngressSwallowsItsOwnFailures() throws Exception {
        final String key = "test.texture-atlas.dialog-ingress.safe";
        System.getProperties().put(key, (Consumer<Object>) TextureAtlasAutoLayoutDialogContributor::contribute);
        try {
            final TextureAtlasAutoLayoutDialogTransformer transformer =
                new TextureAtlasAutoLayoutDialogTransformer(
                    "fixture/AtlasDialog", "(Ljava/lang/Object;)V", null, key
                );
            final byte[] transformed = transformer.transform(
                null, null, "fixture/AtlasDialog", null, null, fixtureClass()
            );
            final FixtureLoader loader = new FixtureLoader(null);
            final Class<?> type = loader.define("fixture.AtlasDialog", transformed);
            final Object instance = type.getConstructor(Object.class).newInstance(new Object());
            assertNotNull(instance);
        } finally {
            System.getProperties().remove(key);
        }
    }

    @Test
    void missingIngressLeavesTheConstructorUntouched() throws Exception {
        final String key = "test.texture-atlas.dialog-ingress.missing";
        final TextureAtlasAutoLayoutDialogTransformer transformer =
            new TextureAtlasAutoLayoutDialogTransformer(
                "fixture/AtlasDialog", "(Ljava/lang/Object;)V", null, key
            );
        final byte[] transformed = transformer.transform(
            null, null, "fixture/AtlasDialog", null, null, fixtureClass()
        );
        final FixtureLoader loader = new FixtureLoader(null);
        final Class<?> type = loader.define("fixture.AtlasDialog", transformed);
        final Object instance = type.getConstructor(Object.class).newInstance(new Object());
        assertNotNull(instance);
    }

    @Test
    void contributorAddsAlgorithmComboAndPersistsSelection() {
        final JPanel center = new JPanel(new GridBagLayout());
        final GridBagLayout grid = (GridBagLayout) center.getLayout();
        final JPanel spacer = new JPanel();
        final GridBagConstraints spacerConstraints = new GridBagConstraints();
        spacerConstraints.gridy = 5;
        center.add(spacer, spacerConstraints);
        System.getProperties().put(
            TextureAtlasAutoLayoutDialogContributor.ALGORITHM_KEY,
            TextureAtlasAutoLayoutDialogContributor.ALGO_MAXRECTS
        );
        try {
            TextureAtlasAutoLayoutDialogContributor.injectInto(center);
            assertEquals(8, grid.getConstraints(spacer).gridy);

            JComboBox<?> combo = null;
            for (java.awt.Component component : center.getComponents()) {
                if (component instanceof JComboBox<?> candidate) combo = candidate;
            }
            assertNotNull(combo);
            assertEquals(1, combo.getSelectedIndex());
            assertEquals("MaxRects-BSSF", combo.getItemAt(combo.getSelectedIndex()));

            combo.setSelectedIndex(0);
            assertEquals(
                TextureAtlasAutoLayoutDialogContributor.ALGO_NATIVE,
                System.getProperty(TextureAtlasAutoLayoutDialogContributor.ALGORITHM_KEY)
            );
        } finally {
            System.getProperties().remove(TextureAtlasAutoLayoutDialogContributor.ALGORITHM_KEY);
        }
    }

    @Test
    void contributorFailsOpenOnNonDialogInput() {
        TextureAtlasAutoLayoutDialogContributor.contribute(new Object());
        assertTrue(true);
    }

    @Test
    void contributorFailsOpenOnNonGridBagPanel() {
        final JPanel plain = new JPanel();
        TextureAtlasAutoLayoutDialogContributor.injectInto(plain);
        assertTrue(true);
    }

    private static byte[] fixtureClass() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC,
            "fixture/AtlasDialog",
            null,
            "java/lang/Object",
            null
        );
        final MethodVisitor constructor = writer.visitMethod(
            Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null
        );
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false
        );
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
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
