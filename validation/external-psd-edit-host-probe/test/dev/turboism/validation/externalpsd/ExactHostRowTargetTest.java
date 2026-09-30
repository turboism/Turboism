package dev.turboism.validation.externalpsd;

import javax.swing.JTree;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Offline tests for the exact 5.3.02 host row resolver; no pseudo host is in the probe build. */
public final class ExactHostRowTargetTest {
    private static final String SHAPE_JAR_PROPERTY =
        "turboism.validation.externalpsd.shapeJar";
    private static final String SHAPE_CLASSPATH_PROPERTY =
        "turboism.validation.externalpsd.shapeClasspath";
    private static final String SHAPE_REQUIRED_PROPERTY =
        "turboism.validation.externalpsd.shapeRequired";
    private static final String SHAPE_JAR_ENV = "TURBOISM_EXTERNAL_PSD_SHAPE_JAR";
    private static final String SHAPE_CLASSPATH_ENV = "TURBOISM_EXTERNAL_PSD_SHAPE_CLASSPATH";
    private static final String SHAPE_REQUIRED_ENV = "TURBOISM_EXTERNAL_PSD_SHAPE_REQUIRED";

    public static void main(final String[] args) throws Exception {
        final ShapeConfig shape = shapeConfig();
        testOffEdtRejected();
        testPreflightMustRunOffEdt();
        testWrongArtifactHashRejected();
        testAccessorShapeRejectsWrongOwnerAndSignatures();
        testOnlyExactArtMeshSourceIsAllowed();
        testNameColumnUsesModelIndexAfterReorderAndViewport();
        testDomainIdentitySurvivesRebuildAndDoesNotUseLabels();
        if (shape.configured()) {
            testReviewedArtifactShape(shape);
            testWrongModelIdentityRejected(shape);
            System.out.println("SHAPE=PASS: configured reviewed host artifact");
        } else {
            System.out.println("SHAPE=NOT_RUN: configure " + SHAPE_JAR_PROPERTY
                + " and " + SHAPE_CLASSPATH_PROPERTY + " for the real-JAR shape check");
        }
        System.out.println("PASS: ExactHostRowTargetTest");
    }

    private static void testOffEdtRejected() throws Exception {
        final ExactHostRowTarget.Resolution result = ExactHostRowTarget.resolve(new JTable(), 0);
        assertTrue(!result.available(), "off-EDT resolution is rejected");
        assertContains(result.reason(), "EDT", "off-EDT rejection explains the thread gate");
    }

    private static void testReviewedArtifactShape(final ShapeConfig shape) throws Exception {
        try (URLClassLoader loader = reviewedLoader(shape)) {
            final Class<?> modelClass = Class.forName(
                "com.live2d.ui.treeTable.j", false, loader);
            assertTrue(ExactHostRowTarget.isReviewedTableModelClass(modelClass),
                "configured JAR exposes the reviewed table model binary name");
            final ExactHostRowTarget.HostAccessPreparation preparation =
                ExactHostRowTarget.prepareHostAccess(modelClass);
            assertTrue(preparation.available(),
                "the reviewed 5.3.02 JAR passes identity and exact accessor shape: "
                    + preparation.reason());
            assertEquals(ExactHostRowTarget.HOST_JAR_SHA256,
                preparation.context().artifactSha256(),
                "preflight records the verified artifact digest");
            assertEquals(shape.jar(), preparation.context().artifact(),
                "preflight binds the code-source artifact path");
        }
    }

    private static void testPreflightMustRunOffEdt() throws Exception {
        final ExactHostRowTarget.HostAccessPreparation preparation = onEdt(
            () -> ExactHostRowTarget.prepareHostAccess(GoodTable.class));
        assertTrue(!preparation.available(), "EDT preflight is rejected");
        assertContains(preparation.reason(), "off EDT",
            "preflight explains that JAR verification is not an EDT operation");
    }

    private static void testWrongModelIdentityRejected(final ShapeConfig shape) throws Exception {
        try (URLClassLoader loader = reviewedLoader(shape)) {
            final Class<?> modelClass = Class.forName(
                "com.live2d.ui.treeTable.j", false, loader);
            final ExactHostRowTarget.HostAccessPreparation preparation =
                ExactHostRowTarget.prepareHostAccess(modelClass);
            assertTrue(preparation.available(),
                "reviewed context is available for the model identity negative test");

            final JTable table = onEdt(() -> new JTable(new DefaultTableModel(
                new Object[][]{{"not host"}}, new Object[]{"value"})));
            final ExactHostRowTarget.Resolution result = onEdt(
                () -> ExactHostRowTarget.resolve(table, 0, preparation.context()));
            assertTrue(!result.available(), "ordinary JTable model is rejected");
            assertContains(result.reason(), "identity",
                "wrong model identity is explicit");
        }
    }

    private static void testWrongArtifactHashRejected() throws Exception {
        final Path fixtureJar = Files.createTempFile("exact-host-row-fixture-", ".jar");
        try {
            writeFixtureJar(fixtureJar);
            final URL[] urls = {fixtureJar.toUri().toURL()};
            try (URLClassLoader loader = new URLClassLoader(urls,
                ClassLoader.getPlatformClassLoader())) {
                final Class<?> fixtureClass = Class.forName(
                    "com.live2d.ui.treeTable.j", false, loader);
                final ExactHostRowTarget.HostAccessPreparation preparation =
                    ExactHostRowTarget.prepareHostAccess(fixtureClass);
                assertTrue(!preparation.available(), "wrong loaded JAR is rejected");
                assertContains(preparation.reason(), "SHA-256",
                    "wrong loaded JAR hash is rejected before shape use");
            }
        } finally {
            Files.deleteIfExists(fixtureJar);
        }
    }

    private static void writeFixtureJar(final Path destination) throws Exception {
        try (InputStream input = ExactHostRowTargetTest.class.getClassLoader()
            .getResourceAsStream("com/live2d/ui/treeTable/j.class")) {
            assertTrue(input != null, "test-only exact-name fixture is available");
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(destination))) {
                output.putNextEntry(new JarEntry("com/live2d/ui/treeTable/j.class"));
                input.transferTo(output);
                output.closeEntry();
            }
        }
    }

    private static void testAccessorShapeRejectsWrongOwnerAndSignatures() {
        final String good = ExactHostRowTarget.accessorShapeFailureForTest(
            GoodTable.class, GoodTreeModel.class, GoodNode.class, GoodSourceOwner.class,
            GoodParameterId.class, GoodId.class);
        assertTrue(good.isEmpty(), "reviewed accessor shape is accepted by the shape checker");

        final String badTable = ExactHostRowTarget.accessorShapeFailureForTest(
            BadTable.class, GoodTreeModel.class, GoodNode.class, GoodSourceOwner.class,
            GoodParameterId.class, GoodId.class);
        assertTrue(!badTable.isEmpty(), "wrong treeTable.j.b field type is rejected");

        final String badTreeModel = ExactHostRowTarget.accessorShapeFailureForTest(
            BadTreeModelTable.class, GoodTreeModel.class, GoodNode.class, GoodSourceOwner.class,
            GoodParameterId.class, GoodId.class);
        assertTrue(!badTreeModel.isEmpty(), "wrong treeTable.j.a field type is rejected");

        final String badNode = ExactHostRowTarget.accessorShapeFailureForTest(
            GoodTable.class, GoodTreeModel.class, BadNode.class, GoodSourceOwner.class,
            GoodParameterId.class, GoodId.class);
        assertTrue(!badNode.isEmpty(), "wrong node.i return signature is rejected");

        final String badSource = ExactHostRowTarget.accessorShapeFailureForTest(
            GoodTable.class, GoodTreeModel.class, GoodNode.class, BadSourceOwner.class,
            GoodParameterId.class, GoodId.class);
        assertTrue(!badSource.isEmpty(), "wrong source.getId owner/signature is rejected");

        final String badId = ExactHostRowTarget.accessorShapeFailureForTest(
            GoodTable.class, GoodTreeModel.class, GoodNode.class, GoodSourceOwner.class,
            GoodParameterId.class, BadId.class);
        assertTrue(!badId.isEmpty(), "wrong Id.getIdString signature is rejected");
    }

    private static void testOnlyExactArtMeshSourceIsAllowed() {
        assertTrue(ExactHostRowTarget.exactSourceClassForTest(
            GoodArtMesh.class, GoodArtMesh.class), "exact ArtMesh source class is allowed");
        assertTrue(!ExactHostRowTarget.exactSourceClassForTest(
            OtherSource.class, GoodArtMesh.class), "non-ArtMesh source class is rejected");
        assertTrue(!ExactHostRowTarget.exactSourceClassForTest(
            GoodArtMesh.class, OtherSource.class), "wrong expected source identity is rejected");
    }

    private static void testNameColumnUsesModelIndexAfterReorderAndViewport() throws Exception {
        final DefaultTableModel model = new DefaultTableModel(
            new Object[][]{{Boolean.TRUE, Boolean.FALSE, "duplicate label", "overlap"}},
            new Object[]{"Draw", "Lock", "PARTS", "Overlap"});
        final JTable table = onEdt(() -> {
            final JTable created = new JTable(model);
            created.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            created.setRowHeight(24);
            for (int column = 0; column < created.getColumnCount(); column++) {
                created.getColumnModel().getColumn(column).setPreferredWidth(
                    column < 2 ? 32 : column == 2 ? 120 : 80);
            }
            final TableColumnModel columns = created.getColumnModel();
            final TableColumn draw = columns.getColumn(0);
            columns.removeColumn(draw);
            columns.addColumn(draw);
            final TableColumn lock = columns.getColumn(0);
            columns.removeColumn(lock);
            columns.addColumn(lock);
            created.setSize(260, 24);
            created.doLayout();
            final JViewport viewport = new JViewport();
            viewport.setSize(160, 24);
            viewport.setView(created);
            viewport.setViewPosition(new Point(0, 0));
            return created;
        });

        final Object[] statesBefore = onEdt(() -> new Object[] {
            model.getValueAt(0, 0), model.getValueAt(0, 1)
        });
        final Optional<ExactHostRowTarget.NameCell> maybeCell = onEdt(
            () -> ExactHostRowTarget.nameCellForTest(table, 0));
        assertTrue(maybeCell.isPresent(), "visible name cell is resolved after column reorder");
        final ExactHostRowTarget.NameCell cell = maybeCell.orElseThrow();
        onEdt(() -> {
            assertEquals(ExactHostRowTarget.NAME_MODEL_COLUMN, cell.modelColumn(),
                "resolver uses the reviewed model name column");
            assertEquals(ExactHostRowTarget.NAME_MODEL_COLUMN,
                table.convertColumnIndexToModel(cell.viewColumn()),
                "view/model conversion returns the name column");
            assertTrue(cell.bounds().contains(cell.clickPoint()),
                "click point stays in name bounds");
            assertEquals(cell.viewRow(), table.rowAtPoint(cell.clickPoint()),
                "click point stays in the captured row");
            assertEquals(cell.viewColumn(), table.columnAtPoint(cell.clickPoint()),
                "click point stays in the converted name column");
            assertTrue(cell.viewColumn() != table.convertColumnIndexToView(0),
                "click point cannot use Draw model column");
            assertTrue(cell.viewColumn() != table.convertColumnIndexToView(1),
                "click point cannot use Lock model column");
            assertEquals(statesBefore[0], model.getValueAt(0, 0),
                "name coordinate calculation does not change visibility value");
            assertEquals(statesBefore[1], model.getValueAt(0, 1),
                "name coordinate calculation does not change lock value");
            return null;
        });
    }

    private static void testDomainIdentitySurvivesRebuildAndDoesNotUseLabels() {
        final String sourceClass = ExactHostRowTarget.ART_MESH_SOURCE_CLASS_NAME;
        final ExactHostRowTarget.Target first = target(
            ExactHostRowTarget.RowFamily.PARTS, sourceClass, "ArtMesh4", 0, 0,
            true, false);
        final ExactHostRowTarget.Target rebuilt = target(
            ExactHostRowTarget.RowFamily.PARTS, sourceClass, "ArtMesh4", 7, 3,
            false, true);
        assertEquals(first.identity(), rebuilt.identity(),
            "same domain ID remains stable across rebuilt row coordinates and UI state");

        final ExactHostRowTarget.Target different = target(
            ExactHostRowTarget.RowFamily.PARTS, sourceClass, "ArtMesh5", 0, 0,
            true, false);
        assertTrue(!first.identity().equals(different.identity()),
            "different domain IDs do not mix");

        final ExactHostRowTarget.Target sameLabelOtherDomain = target(
            ExactHostRowTarget.RowFamily.PARTS, sourceClass, "ArtMesh6", 1, 1,
            true, false);
        assertTrue(!first.identity().equals(sameLabelOtherDomain.identity()),
            "repeated visible labels cannot mix distinct domain IDs");
        assertEquals(sourceClass, first.sourceClass(), "identity reports the full source class");
        assertEquals("ArtMesh4", first.domainId(), "identity reports the complete domain ID");
    }

    private static ExactHostRowTarget.Target target(
        final ExactHostRowTarget.RowFamily family, final String sourceClass,
        final String domainId, final int viewRow, final int modelRow,
        final boolean visible, final boolean locked) {
        final ExactHostRowTarget.NameCell cell = new ExactHostRowTarget.NameCell(
            viewRow, modelRow, 0, ExactHostRowTarget.NAME_MODEL_COLUMN,
            new Rectangle(0, viewRow * 20, 100, 20), new Point(50, viewRow * 20 + 10));
        return new ExactHostRowTarget.Target(family, sourceClass, domainId,
            viewRow, modelRow, cell,
            new ExactHostRowTarget.VisibilityLockState(visible, locked));
    }

    private static URLClassLoader reviewedLoader(final ShapeConfig shape) throws Exception {
        final List<URL> urls = new ArrayList<>();
        urls.add(shape.jar().toUri().toURL());
        for (final Path dependency : shape.dependencies()) urls.add(dependency.toUri().toURL());
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static ShapeConfig shapeConfig() throws Exception {
        final String jarValue = configuredValue(SHAPE_JAR_PROPERTY, SHAPE_JAR_ENV);
        final boolean required = Boolean.parseBoolean(
            configuredValue(SHAPE_REQUIRED_PROPERTY, SHAPE_REQUIRED_ENV));
        if (jarValue.isBlank()) {
            if (required) {
                throw new IllegalStateException("real-JAR shape check requested but "
                    + SHAPE_JAR_PROPERTY + " is not configured");
            }
            return new ShapeConfig(false, null, List.of());
        }
        final Path jar = Path.of(jarValue).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("configured shape JAR is not a regular file: " + jar);
        }
        final String classpathValue = configuredValue(
            SHAPE_CLASSPATH_PROPERTY, SHAPE_CLASSPATH_ENV);
        if (classpathValue.isBlank()) {
            throw new IllegalStateException("real-JAR shape check requires explicit "
                + SHAPE_CLASSPATH_PROPERTY + " (Kotlin/JDOM dependencies)");
        }
        final List<Path> dependencies = new ArrayList<>();
        for (final String value : classpathValue.split(
            java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (value.isBlank()) continue;
            final Path dependency = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isRegularFile(dependency)) {
                throw new IllegalStateException(
                    "configured shape classpath entry is not a regular file: " + dependency);
            }
            dependencies.add(dependency);
        }
        if (dependencies.isEmpty()) {
            throw new IllegalStateException("configured shape classpath has no dependency entries");
        }
        return new ShapeConfig(true, jar.toRealPath(), List.copyOf(dependencies));
    }

    private static String configuredValue(final String property, final String environment) {
        final String fromProperty = System.getProperty(property, "");
        if (!fromProperty.isBlank()) return fromProperty;
        return System.getenv().getOrDefault(environment, "");
    }

    private record ShapeConfig(boolean configured, Path jar, List<Path> dependencies) {
    }

    private static <T> T onEdt(final Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return operation.call();
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(operation.call());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            final Throwable error = failure.get();
            if (error instanceof Exception exception) throw exception;
            if (error instanceof Error exception) throw exception;
            throw new java.lang.reflect.InvocationTargetException(error);
        }
        return value.get();
    }

    private static void assertEquals(final Object expected, final Object actual,
        final String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertContains(final String value, final String expected,
        final String message) {
        assertTrue(value != null && value.contains(expected),
            message + " value=" + value);
    }

    @SuppressWarnings("serial")
    public static final class GoodTable extends DefaultTableModel {
        private final GoodTreeModel a = new GoodTreeModel();
        private final JTree b = new JTree();
    }

    @SuppressWarnings("serial")
    public static final class BadTable extends DefaultTableModel {
        private final GoodTreeModel a = new GoodTreeModel();
        private final Object b = new Object();
    }

    @SuppressWarnings("serial")
    public static final class BadTreeModelTable extends DefaultTableModel {
        private final Object a = new Object();
        private final JTree b = new JTree();
    }

    public static final class GoodTreeModel { }

    public static final class GoodNode {
        public final Object i() { return null; }
    }

    public static final class BadNode {
        public final String i() { return "wrong"; }
    }

    public abstract static class GoodSourceOwner {
        public abstract GoodParameterId getId();
        public final boolean isVisible() { return true; }
        public final boolean isLocked() { return false; }
    }

    public static final class BadSourceOwner {
        public String getId() { return "wrong"; }
        public final boolean isVisible() { return true; }
        public final boolean isLocked() { return false; }
    }

    public static class GoodParameterId { }

    public static final class GoodId {
        public final String getIdString() { return "GoodId"; }
    }

    public static final class BadId {
        public final Object getIdString() { return new Object(); }
    }

    public static final class GoodArtMesh { }
    public static final class OtherSource { }
}
