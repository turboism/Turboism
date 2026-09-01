package dev.turboism.adapter.cubism.textureatlas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void transformationDoesNotCreateDedicatedDiagnosticLog(@TempDir final Path home) {
        final String previousHome = System.getProperty("turboism.home");
        System.setProperty("turboism.home", home.toString());
        try {
            final TextureAtlasAutoLayoutDialogTransformer transformer =
                new TextureAtlasAutoLayoutDialogTransformer(
                    "fixture/AtlasDialog", "(Ljava/lang/Object;)V", null,
                    "test.texture-atlas.dialog-ingress.no-log"
                );

            final byte[] transformed = transformer.transform(
                null, null, "fixture/AtlasDialog", null, null, fixtureClass()
            );

            assertNotNull(transformed);
            assertFalse(Files.exists(home.resolve("logs").resolve("dialog-transform.log")));
        } finally {
            if (previousHome == null) {
                System.clearProperty("turboism.home");
            } else {
                System.setProperty("turboism.home", previousHome);
            }
        }
    }

    private static TextureAtlasAutoLayoutDialogContributor contributor() {
        final RuntimeTextureAtlasLayoutAlgorithmRegistry registry =
            new RuntimeTextureAtlasLayoutAlgorithmRegistry();
        registry.register(new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm(
            "native", "Native", false, null
        ));
        registry.register(new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm(
            "maxrects", "MaxRects-BSSF", true,
            (items, constraints) -> new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan(
                4096, 4096, 1, java.util.List.of()
            )
        ));
        return new TextureAtlasAutoLayoutDialogContributor(registry, java.util.Locale.ENGLISH);
    }

    @Test
    void contributorIngressSwallowsItsOwnFailures() throws Exception {
        final String key = "test.texture-atlas.dialog-ingress.safe";
        System.getProperties().put(key, contributor().ingress());
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
    void emptyRegistryLeavesTheNativePanelUntouched() {
        final JPanel center = new JPanel(new GridBagLayout());
        final GridBagLayout grid = (GridBagLayout) center.getLayout();
        final JPanel spacer = new JPanel();
        final GridBagConstraints spacerConstraints = new GridBagConstraints();
        spacerConstraints.gridy = 5;
        center.add(spacer, spacerConstraints);
        final RuntimeTextureAtlasLayoutAlgorithmRegistry registry =
            new RuntimeTextureAtlasLayoutAlgorithmRegistry();

        new TextureAtlasAutoLayoutDialogContributor(registry, java.util.Locale.ENGLISH)
            .injectInto(center);

        assertEquals(1, center.getComponentCount());
        assertEquals(5, grid.getConstraints(spacer).gridy);
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
            contributor().injectInto(center);
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
    void validationObserverReceivesOnlyCompleteInjectedControls() {
        final JPanel center = new JPanel(new GridBagLayout());
        final AtomicReference<Object> received = new AtomicReference<>();
        System.getProperties().put(
            TextureAtlasAutoLayoutDialogContributor.VALIDATION_OBSERVER_KEY,
            (Consumer<Object>) received::set
        );
        try {
            contributor().injectInto(center);

            final var observation =
                (TextureAtlasAutoLayoutDialogContributor.DialogObservation) received.get();
            assertNotNull(observation);
            assertEquals(center, observation.center());
            assertEquals("Layout algorithm", observation.algorithmLabel().getText());
            assertEquals(2, observation.algorithmCombo().getItemCount());
            assertEquals("Parallel search", observation.parallelLabel().getText());
            assertNotNull(observation.parallelCheck());
            assertEquals(2, observation.algorithms().size());
        } finally {
            System.getProperties().remove(
                TextureAtlasAutoLayoutDialogContributor.VALIDATION_OBSERVER_KEY
            );
        }
    }

    @Test
    void emptyRegistryDoesNotNotifyValidationObserver() {
        final AtomicReference<Object> received = new AtomicReference<>();
        System.getProperties().put(
            TextureAtlasAutoLayoutDialogContributor.VALIDATION_OBSERVER_KEY,
            (Consumer<Object>) received::set
        );
        try {
            new TextureAtlasAutoLayoutDialogContributor(
                new RuntimeTextureAtlasLayoutAlgorithmRegistry(),
                java.util.Locale.ENGLISH
            ).injectInto(new JPanel(new GridBagLayout()));

            assertEquals(null, received.get());
        } finally {
            System.getProperties().remove(
                TextureAtlasAutoLayoutDialogContributor.VALIDATION_OBSERVER_KEY
            );
        }
    }

    @Test
    void contributorFailsOpenOnNonDialogInput() {
        contributor().ingress().accept(new Object());
        assertTrue(true);
    }

    @Test
    void contributorFailsOpenOnNonGridBagPanel() {
        final JPanel plain = new JPanel();
        contributor().injectInto(plain);
        assertTrue(true);
    }

    @Test
    void parallelCheckboxDisabledForNonParallelAlgorithm() {
        final JPanel center = new JPanel(new GridBagLayout());
        System.getProperties().put(TextureAtlasAutoLayoutDialogContributor.ALGORITHM_KEY, "native");
        System.getProperties().put(TextureAtlasAutoLayoutDialogContributor.PARALLEL_KEY, "true");
        try {
            contributor().injectInto(center);
            javax.swing.JCheckBox check = null;
            javax.swing.JComboBox<?> combo = null;
            for (java.awt.Component component : center.getComponents()) {
                if (component instanceof javax.swing.JCheckBox candidate) check = candidate;
                if (component instanceof javax.swing.JComboBox<?> candidate) combo = candidate;
            }
            assertNotNull(check);
            assertNotNull(combo);
            assertEquals(0, combo.getSelectedIndex());
            assertFalse(check.isEnabled());
            assertFalse(check.isSelected());
            assertEquals("false", System.getProperty(TextureAtlasAutoLayoutDialogContributor.PARALLEL_KEY));

            combo.setSelectedIndex(1);
            assertTrue(check.isEnabled());
        } finally {
            System.getProperties().remove(TextureAtlasAutoLayoutDialogContributor.ALGORITHM_KEY);
            System.getProperties().remove(TextureAtlasAutoLayoutDialogContributor.PARALLEL_KEY);
        }
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
