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
        registry.register(new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm(
            "dalsoo", "Dalsoo Polygon", true, true,
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
            assertEquals(16, grid.getConstraints(spacer).gridy);

            JComboBox<?> combo = null;
            for (java.awt.Component component : center.getComponents()) {
                if (component instanceof JComboBox<?> candidate
                    && candidate.getItemCount() > 0
                    && "Native".equals(candidate.getItemAt(0))) {
                    combo = candidate;
                }
            }
            assertNotNull(combo);
            assertEquals(1, combo.getSelectedIndex());
            assertEquals("MaxRects-BSSF", combo.getItemAt(combo.getSelectedIndex()));
            assertEquals(3, combo.getItemCount());

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
            assertEquals(3, observation.algorithmCombo().getItemCount());
            assertEquals("Parallel search", observation.parallelLabel().getText());
            assertNotNull(observation.parallelCheck());
            assertEquals(3, observation.algorithms().size());
            assertTrue(observation.optionControls().containsKey("rotation"));
            assertTrue(observation.optionControls().containsKey("lockPreset"));
            assertTrue(observation.optionControls().containsKey("scaleMode"));
            assertTrue(observation.optionControls().containsKey("fixedScale"));
            assertTrue(observation.optionControls()
                .containsKey("autoScaleTolerance"));
            assertTrue(observation.optionControls()
                .containsKey("autoScaleMaxTry"));
            assertTrue(observation.optionControls().containsKey("kernel"));
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
                if (component instanceof javax.swing.JComboBox<?> candidate
                    && candidate.getItemCount() > 0
                    && "Native".equals(candidate.getItemAt(0))) {
                    combo = candidate;
                }
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

    @Test
    void polygonOptionControlsEnabledOnlyForPolygonAlgorithms() {
        final JPanel center = new JPanel(new GridBagLayout());
        final AtomicReference<Object> received = new AtomicReference<>();
        System.getProperties().put(
            TextureAtlasAutoLayoutDialogContributor.VALIDATION_OBSERVER_KEY,
            (Consumer<Object>) received::set);
        System.getProperties().put(
            TextureAtlasAutoLayoutDialogContributor.ALGORITHM_KEY, "native");
        try {
            contributor().injectInto(center);
            final var observation =
                (TextureAtlasAutoLayoutDialogContributor.DialogObservation)
                    received.get();
            assertNotNull(observation);
            for (final var control : observation.optionControls().values()) {
                assertFalse(control.isEnabled(),
                    "non-polygon algorithm must disable option controls");
            }
            // dalsoo declares supportsPolygonOptions
            observation.algorithmCombo().setSelectedIndex(2);
            for (final var control : observation.optionControls().values()) {
                assertTrue(control.isEnabled() || control
                    == observation.optionControls().get("fixedScale"),
                    "polygon algorithm must enable option controls");
            }
            // back to a rect-only algorithm: everything disabled again
            observation.algorithmCombo().setSelectedIndex(1);
            for (final var control : observation.optionControls().values()) {
                assertFalse(control.isEnabled());
            }
        } finally {
            System.getProperties().remove(
                TextureAtlasAutoLayoutDialogContributor.VALIDATION_OBSERVER_KEY);
            System.getProperties().remove(
                TextureAtlasAutoLayoutDialogContributor.ALGORITHM_KEY);
        }
    }

    @Test
    void scaleModeGatesScaleFields() {
        final JPanel center = new JPanel(new GridBagLayout());
        final AtomicReference<Object> received = new AtomicReference<>();
        System.getProperties().put(
            TextureAtlasAutoLayoutDialogContributor.VALIDATION_OBSERVER_KEY,
            (Consumer<Object>) received::set);
        System.getProperties().put(
            TextureAtlasAutoLayoutDialogContributor.ALGORITHM_KEY, "dalsoo");
        try {
            contributor().injectInto(center);
            final var observation =
                (TextureAtlasAutoLayoutDialogContributor.DialogObservation)
                    received.get();
            @SuppressWarnings("unchecked")
            final JComboBox<String> scaleMode =
                (JComboBox<String>) observation.optionControls().get("scaleMode");
            final var fixedScale = observation.optionControls().get("fixedScale");
            final var tolerance =
                observation.optionControls().get("autoScaleTolerance");
            final var maxTry =
                observation.optionControls().get("autoScaleMaxTry");
            // auto mode (default): fixedScale off, tolerance/maxTry on
            assertFalse(fixedScale.isEnabled());
            assertTrue(tolerance.isEnabled());
            assertTrue(maxTry.isEnabled());
            scaleMode.setSelectedIndex(1); // fixed
            assertEquals("false", System.getProperty(
                TextureAtlasAutoLayoutDialogContributor.AUTO_SCALE_KEY));
            assertTrue(fixedScale.isEnabled());
            assertFalse(tolerance.isEnabled());
            assertFalse(maxTry.isEnabled());
            scaleMode.setSelectedIndex(0); // auto
            assertEquals("true", System.getProperty(
                TextureAtlasAutoLayoutDialogContributor.AUTO_SCALE_KEY));
        } finally {
            for (final String key : new String[] {
                TextureAtlasAutoLayoutDialogContributor.VALIDATION_OBSERVER_KEY,
                TextureAtlasAutoLayoutDialogContributor.ALGORITHM_KEY,
                TextureAtlasAutoLayoutDialogContributor.AUTO_SCALE_KEY}) {
                System.getProperties().remove(key);
            }
        }
    }

    @Test
    void optionControlsBridgeToSystemProperties() {
        final JPanel center = new JPanel(new GridBagLayout());
        final AtomicReference<Object> received = new AtomicReference<>();
        System.getProperties().put(
            TextureAtlasAutoLayoutDialogContributor.VALIDATION_OBSERVER_KEY,
            (Consumer<Object>) received::set);
        System.getProperties().put(
            TextureAtlasAutoLayoutDialogContributor.ALGORITHM_KEY, "dalsoo");
        try {
            contributor().injectInto(center);
            final var observation =
                (TextureAtlasAutoLayoutDialogContributor.DialogObservation)
                    received.get();
            @SuppressWarnings("unchecked")
            final JComboBox<String> rotation =
                (JComboBox<String>) observation.optionControls().get("rotation");
            @SuppressWarnings("unchecked")
            final JComboBox<String> lock =
                (JComboBox<String>) observation.optionControls().get("lockPreset");
            @SuppressWarnings("unchecked")
            final JComboBox<String> kernel =
                (JComboBox<String>) observation.optionControls().get("kernel");
            rotation.setSelectedIndex(2);
            assertEquals("FREE", System.getProperty(
                TextureAtlasAutoLayoutDialogContributor.ROTATION_KEY));
            lock.setSelectedIndex(4);
            assertEquals("ANGLE_SCALE", System.getProperty(
                TextureAtlasAutoLayoutDialogContributor.LOCK_PRESET_KEY));
            kernel.setSelectedIndex(1);
            assertEquals("dalalah", System.getProperty(
                TextureAtlasAutoLayoutDialogContributor.KERNEL_KEY));

            final javax.swing.JTextField fixedScale =
                (javax.swing.JTextField) observation.optionControls()
                    .get("fixedScale");
            fixedScale.setText("150");
            fixedScale.postActionEvent();
            assertEquals("150", System.getProperty(
                TextureAtlasAutoLayoutDialogContributor.FIXED_SCALE_PERCENT_KEY));
            // invalid input reverts to the last committed value
            fixedScale.setText("bogus");
            fixedScale.postActionEvent();
            assertEquals("150", fixedScale.getText());

            final javax.swing.JTextField tolerance =
                (javax.swing.JTextField) observation.optionControls()
                    .get("autoScaleTolerance");
            tolerance.setText("0.02");
            tolerance.postActionEvent();
            assertEquals("20", System.getProperty(
                TextureAtlasAutoLayoutDialogContributor.AUTO_SCALE_TOLERANCE_KEY));

            final javax.swing.JTextField maxTry =
                (javax.swing.JTextField) observation.optionControls()
                    .get("autoScaleMaxTry");
            maxTry.setText("7");
            maxTry.postActionEvent();
            assertEquals("7", System.getProperty(
                TextureAtlasAutoLayoutDialogContributor.AUTO_SCALE_MAX_TRY_KEY));
        } finally {
            for (final String key : new String[] {
                TextureAtlasAutoLayoutDialogContributor.VALIDATION_OBSERVER_KEY,
                TextureAtlasAutoLayoutDialogContributor.ALGORITHM_KEY,
                TextureAtlasAutoLayoutDialogContributor.ROTATION_KEY,
                TextureAtlasAutoLayoutDialogContributor.LOCK_PRESET_KEY,
                TextureAtlasAutoLayoutDialogContributor.KERNEL_KEY,
                TextureAtlasAutoLayoutDialogContributor.FIXED_SCALE_PERCENT_KEY,
                TextureAtlasAutoLayoutDialogContributor.AUTO_SCALE_TOLERANCE_KEY,
                TextureAtlasAutoLayoutDialogContributor.AUTO_SCALE_MAX_TRY_KEY}) {
                System.getProperties().remove(key);
            }
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
