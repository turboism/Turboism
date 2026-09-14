package dev.turboism.validation.externalpsd;

import javax.swing.JTree;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.TreePath;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Validation-only resolver for an ArtMesh row in the reviewed Cubism 5.3.02 tree tables.
 *
 * <p>This class is intentionally not a host adapter. It accepts only a live Swing table whose
 * model is the exact 5.3.02 {@code com.live2d.ui.treeTable.j} class, and follows only the
 * reviewed accessor chain after an off-EDT identity preflight. A caller cannot supply an artifact
 * hash or use a label/row-number fallback.</p>
 */
public final class ExactHostRowTarget {
    /** SHA-256 of the reviewed 5.3.02 Live2D_Cubism.jar. */
    public static final String HOST_JAR_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";

    /** The reviewed tree/name column in the table model. */
    public static final int NAME_MODEL_COLUMN = 2;

    public static final String ART_MESH_SOURCE_CLASS_NAME =
        "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource";

    private static final String TABLE_MODEL_CLASS_NAME = "com.live2d.ui.treeTable.j";
    private static final String TREE_MODEL_BASE_CLASS_NAME = "com.live2d.ui.treeTable.a";
    private static final String NODE_CLASS_NAME = "com.live2d.ui.treeTable.c";
    private static final String SOURCE_OWNER_CLASS_NAME =
        "com.live2d.cubism.doc.model.ACParameterControllableSource";
    private static final String SOURCE_ID_CLASS_NAME =
        "com.live2d.cubism.doc.model.id.CParameterControllableId";
    private static final String ID_CLASS_NAME = "com.live2d.core.id.Id";

    private static final String PARTS_MODEL_CLASS_NAME =
        "com.live2d.cubism.view.palette.parts.CPartsTreeTable.a";
    private static final String PARTS_NODE_CLASS_NAME =
        "com.live2d.cubism.view.palette.parts.CPartsTreeTable.b";
    private static final String DEFORMER_MODEL_CLASS_NAME = "com.live2d.ui.treeTable.b";
    private static final String DEFORMER_NODE_CLASS_NAME =
        "com.live2d.cubism.view.palette.deformer.CDeformerTreeTable.a";

    private static final String NOT_INITIALIZED_ID = "Not Initialized";
    private static final String NOT_INITIALIZED_TYPED_ID = "__NotInitialized__";
    private static final String ROOT_PART_ID = "__RootPart__";
    private static final String ROOT_DEFORMER_ID = "__RootDeformer__";

    private ExactHostRowTarget() { }

    /**
     * Cheap discovery gate used on the EDT before the off-EDT artifact preflight. This checks
     * only the reviewed binary name; {@link #prepareHostAccess(Class)} still verifies the exact
     * class identity, loader, code source, digest, and accessor shape.
     */
    static boolean isReviewedTableModelClass(final Class<?> modelClass) {
        return modelClass != null && TABLE_MODEL_CLASS_NAME.equals(modelClass.getName());
    }

    /**
     * Immutable identity issued by the off-EDT host preflight. The constructor is private so a
     * caller cannot manufacture a context from a claimed hash, path, or boolean. A context binds
     * the exact loader, all classes used by the accessor chain, and the code-source artifact.
     */
    static final class HostAccessContext {
        private final ClassLoader loader;
        private final Path artifact;
        private final String artifactSha256;
        private final Class<?> tableModelClass;
        private final Class<?> treeModelBaseClass;
        private final Class<?> partsModelClass;
        private final Class<?> partsNodeClass;
        private final Class<?> deformerModelClass;
        private final Class<?> deformerNodeClass;
        private final Class<?> nodeClass;
        private final Class<?> sourceOwner;
        private final Class<?> sourceIdClass;
        private final Class<?> idClass;
        private final Class<?> artMeshClass;
        private final AccessorShape accessors;

        private HostAccessContext(final ClassLoader loader, final ArtifactIdentity artifact,
            final Class<?> tableModelClass, final Class<?> treeModelBaseClass,
            final Class<?> partsModelClass, final Class<?> partsNodeClass,
            final Class<?> deformerModelClass, final Class<?> deformerNodeClass,
            final Class<?> nodeClass, final Class<?> sourceOwner, final Class<?> sourceIdClass,
            final Class<?> idClass, final Class<?> artMeshClass,
            final AccessorShape accessors) {
            this.loader = Objects.requireNonNull(loader, "loader");
            this.artifact = Objects.requireNonNull(artifact, "artifact").path();
            this.artifactSha256 = artifact.sha256();
            this.tableModelClass = Objects.requireNonNull(tableModelClass, "tableModelClass");
            this.treeModelBaseClass = Objects.requireNonNull(treeModelBaseClass,
                "treeModelBaseClass");
            this.partsModelClass = Objects.requireNonNull(partsModelClass, "partsModelClass");
            this.partsNodeClass = Objects.requireNonNull(partsNodeClass, "partsNodeClass");
            this.deformerModelClass = Objects.requireNonNull(deformerModelClass,
                "deformerModelClass");
            this.deformerNodeClass = Objects.requireNonNull(deformerNodeClass,
                "deformerNodeClass");
            this.nodeClass = Objects.requireNonNull(nodeClass, "nodeClass");
            this.sourceOwner = Objects.requireNonNull(sourceOwner, "sourceOwner");
            this.sourceIdClass = Objects.requireNonNull(sourceIdClass, "sourceIdClass");
            this.idClass = Objects.requireNonNull(idClass, "idClass");
            this.artMeshClass = Objects.requireNonNull(artMeshClass, "artMeshClass");
            this.accessors = Objects.requireNonNull(accessors, "accessors");
        }

        Path artifact() { return artifact; }
        String artifactSha256() { return artifactSha256; }
    }

    /** Result of the non-EDT host identity and accessor-shape preflight. */
    static final class HostAccessPreparation {
        private final HostAccessContext context;
        private final String reason;

        private HostAccessPreparation(final HostAccessContext context, final String reason) {
            this.context = context;
            this.reason = reason == null ? "" : reason;
        }

        private static HostAccessPreparation available(final HostAccessContext context) {
            return new HostAccessPreparation(Objects.requireNonNull(context, "context"), "");
        }

        private static HostAccessPreparation unavailable(final String reason) {
            return new HostAccessPreparation(null, reason == null || reason.isBlank()
                ? "host access unavailable" : reason);
        }

        boolean available() { return context != null; }
        HostAccessContext context() { return context; }
        String reason() { return reason; }
    }

    /** The two exact host row families in which an ArtMesh source is supported. */
    public enum RowFamily {
        PARTS,
        DEFORMER
    }

    /** Stable identity; coordinates and UI state are deliberately not part of this value. */
    public record Identity(RowFamily rowFamily, String sourceClass, String domainId) {
        public Identity {
            Objects.requireNonNull(rowFamily, "rowFamily");
            requireText(sourceClass, "sourceClass");
            requireText(domainId, "domainId");
        }
    }

    /** Visibility/lock observation captured independently from the stable identity. */
    public record VisibilityLockState(boolean visible, boolean locked) { }

    /** Safe table-local coordinates for the reviewed model/name column. */
    public record NameCell(int viewRow, int modelRow, int viewColumn, int modelColumn,
        Rectangle bounds, Point clickPoint) {
        public NameCell {
            if (viewRow < 0 || modelRow < 0 || viewColumn < 0 || modelColumn < 0) {
                throw new IllegalArgumentException("cell coordinates must not be negative");
            }
            bounds = new Rectangle(Objects.requireNonNull(bounds, "bounds"));
            clickPoint = new Point(Objects.requireNonNull(clickPoint, "clickPoint"));
            if (bounds.width <= 0 || bounds.height <= 0 || !bounds.contains(clickPoint)) {
                throw new IllegalArgumentException("name cell must have a contained click point");
            }
        }

        @Override public Rectangle bounds() { return new Rectangle(this.bounds); }
        @Override public Point clickPoint() { return new Point(this.clickPoint); }
    }

    /** A resolved target plus its current, non-stable click coordinates and state observation. */
    public record Target(RowFamily rowFamily, String sourceClass, String domainId,
        int viewRow, int modelRow, NameCell nameCell, VisibilityLockState state) {
        public Target {
            Objects.requireNonNull(rowFamily, "rowFamily");
            requireText(sourceClass, "sourceClass");
            requireText(domainId, "domainId");
            if (viewRow < 0 || modelRow < 0) {
                throw new IllegalArgumentException("row coordinates must not be negative");
            }
            Objects.requireNonNull(nameCell, "nameCell");
            Objects.requireNonNull(state, "state");
        }

        public Identity identity() { return new Identity(rowFamily, sourceClass, domainId); }
        public Rectangle nameCellBounds() { return nameCell.bounds(); }
        public Point nameClickPoint() { return nameCell.clickPoint(); }
        public boolean visible() { return state.visible(); }
        public boolean locked() { return state.locked(); }
    }

    /** Fail-closed result used for every unavailable, stale, unsupported, or untrusted target. */
    public record Resolution(Target target, String reason) {
        public Resolution {
            reason = reason == null ? "" : reason;
            if (target == null && reason.isBlank()) reason = "target unavailable";
        }

        public boolean available() { return target != null; }

        static Resolution unavailable(final String reason) {
            return new Resolution(null, reason == null || reason.isBlank()
                ? "target unavailable" : reason);
        }
    }

    private record CellResolution(NameCell cell, String reason) {
        static CellResolution unavailable(final String reason) {
            return new CellResolution(null, reason == null ? "name cell unavailable" : reason);
        }

        boolean available() { return cell != null; }
    }

    private record AccessorShape(Field treeModelField, Field treeField, Method nodeSource,
        Method sourceId, Method idValue, Method visible, Method locked) { }

    private record ArtifactIdentity(Path path, String sha256) { }

    /**
     * Performs the expensive code-source/hash and exact-shape checks once, off the EDT. The
     * returned context is the only input accepted by the live resolver; no caller-supplied hash
     * or trusted flag exists.
     */
    static HostAccessPreparation prepareHostAccess(final Class<?> hostModelClass) {
        if (SwingUtilities.isEventDispatchThread()) {
            return HostAccessPreparation.unavailable("host preflight must run off EDT");
        }
        if (hostModelClass == null) {
            return HostAccessPreparation.unavailable("host model class is unavailable");
        }
        if (!TABLE_MODEL_CLASS_NAME.equals(hostModelClass.getName())) {
            return HostAccessPreparation.unavailable("host model class is not exact "
                + TABLE_MODEL_CLASS_NAME);
        }
        try {
            final ClassLoader loader = hostModelClass.getClassLoader();
            if (loader == null) {
                return HostAccessPreparation.unavailable("host model has no classloader");
            }
            final Class<?> tableModelClass = loadExact(loader, TABLE_MODEL_CLASS_NAME);
            if (tableModelClass != hostModelClass) {
                return HostAccessPreparation.unavailable(
                    "host model class identity is not exact");
            }
            if (!Modifier.isPublic(tableModelClass.getModifiers())
                || !Modifier.isFinal(tableModelClass.getModifiers())) {
                return HostAccessPreparation.unavailable("host model modifiers are not exact");
            }

            // This is deliberately the only file read in the preparation. Every later row
            // resolution consumes the immutable result rather than hashing the JAR on the EDT.
            final ArtifactIdentity artifact = verifyHostArtifact(tableModelClass);
            final Class<?> treeModelBase = loadExact(loader, TREE_MODEL_BASE_CLASS_NAME);
            final Class<?> nodeBase = loadExact(loader, NODE_CLASS_NAME);
            final Class<?> sourceOwner = loadExact(loader, SOURCE_OWNER_CLASS_NAME);
            final Class<?> sourceId = loadExact(loader, SOURCE_ID_CLASS_NAME);
            final Class<?> idClass = loadExact(loader, ID_CLASS_NAME);
            final Class<?> artMeshClass = loadExact(loader, ART_MESH_SOURCE_CLASS_NAME);
            final Class<?> partsModel = loadExact(loader, PARTS_MODEL_CLASS_NAME);
            final Class<?> partsNode = loadExact(loader, PARTS_NODE_CLASS_NAME);
            final Class<?> deformerModel = loadExact(loader, DEFORMER_MODEL_CLASS_NAME);
            final Class<?> deformerNode = loadExact(loader, DEFORMER_NODE_CLASS_NAME);

            for (final Class<?> hostClass : new Class<?>[] {tableModelClass, treeModelBase,
                nodeBase, sourceOwner, sourceId, idClass, artMeshClass, partsModel, partsNode,
                deformerModel, deformerNode}) {
                verifyClassArtifact(hostClass, loader, artifact.path());
            }
            final AccessorShape accessors = accessorShape(tableModelClass, treeModelBase,
                nodeBase, sourceOwner, sourceId, idClass);
            return HostAccessPreparation.available(new HostAccessContext(loader, artifact,
                tableModelClass, treeModelBase, partsModel, partsNode, deformerModel,
                deformerNode, nodeBase, sourceOwner, sourceId, idClass, artMeshClass, accessors));
        } catch (ReflectiveOperationException | IOException | RuntimeException
            | LinkageError failure) {
            return HostAccessPreparation.unavailable(
                reflectionFailure("host preflight", failure));
        }
    }

    /**
     * Resolves one current view row without a verified context. This safe overload never performs
     * an implicit preflight because doing so would put JAR I/O on the EDT.
     */
    public static Resolution resolve(final JTable table, final int viewRow) {
        if (!SwingUtilities.isEventDispatchThread()) return Resolution.unavailable("not on EDT");
        if (table == null) return Resolution.unavailable("table is unavailable");
        return Resolution.unavailable("verified host access context is required");
    }

    /**
     * Resolves one current view row using a context issued by {@link #prepareHostAccess(Class)}.
     * All Swing and live-host object reads in this method are on the EDT; artifact verification is
     * intentionally absent here.
     */
    static Resolution resolve(final JTable table, final int viewRow,
        final HostAccessContext context) {
        if (!SwingUtilities.isEventDispatchThread()) return Resolution.unavailable("not on EDT");
        if (context == null) return Resolution.unavailable("verified host access context is required");
        if (table == null) return Resolution.unavailable("table is unavailable");
        try {
            final Object model = table.getModel();
            if (model == null) return Resolution.unavailable("table model is unavailable");
            if (model.getClass() != context.tableModelClass
                || model.getClass().getClassLoader() != context.loader) {
                return Resolution.unavailable("table model class identity is not the verified host");
            }

            if (!isLive(table)) return Resolution.unavailable(
                "table is not showing, displayable, and attached");

            final Object treeModel = context.accessors.treeModelField().get(model);
            if (treeModel == null || !context.treeModelBaseClass.isInstance(treeModel)) {
                return Resolution.unavailable("host treeTable.j.a backing model is unavailable");
            }
            final Object treeValue = context.accessors.treeField().get(model);
            if (!(treeValue instanceof JTree tree)) {
                return Resolution.unavailable("host treeTable.j.b is not a JTree");
            }
            if (tree.getModel() != treeModel) {
                return Resolution.unavailable("JTree model is not treeTable.j.a");
            }
            if (viewRow < 0 || viewRow >= table.getRowCount()) {
                return Resolution.unavailable("view row is outside the current table");
            }
            final int modelRow = table.convertRowIndexToModel(viewRow);
            if (modelRow < 0 || modelRow >= tree.getRowCount()) {
                return Resolution.unavailable("model row is outside the current tree");
            }
            final TreePath path = tree.getPathForRow(modelRow);
            if (path == null || path.getLastPathComponent() == null) {
                return Resolution.unavailable("tree path is unavailable for the current row");
            }

            final Object node = path.getLastPathComponent();
            final RowFamily family = rowFamily(treeModel.getClass(), node.getClass(), context);
            final Object source = context.accessors.nodeSource().invoke(node);
            if (source == null) return Resolution.unavailable("row source is unavailable");
            if (!exactSourceClass(source.getClass(), context.artMeshClass)) {
                return Resolution.unavailable("row source is not the exact supported ArtMesh class: "
                    + source.getClass().getName());
            }
            if (!context.sourceOwner.isInstance(source)) {
                return Resolution.unavailable(
                    "ArtMesh source is not a parameter-controllable source");
            }

            final Object id = context.accessors.sourceId().invoke(source);
            if (id == null || !context.idClass.isInstance(id)) {
                return Resolution.unavailable("ArtMesh domain ID is unavailable");
            }
            final Object idValue = context.accessors.idValue().invoke(id);
            if (!(idValue instanceof String domainId) || !usableDomainId(domainId)) {
                return Resolution.unavailable("ArtMesh domain ID is empty or a sentinel");
            }
            final Object visibleValue = context.accessors.visible().invoke(source);
            final Object lockedValue = context.accessors.locked().invoke(source);
            if (!(visibleValue instanceof Boolean visible)
                || !(lockedValue instanceof Boolean locked)) {
                return Resolution.unavailable("ArtMesh visibility/lock state is unavailable");
            }

            final CellResolution cell = nameCell(table, viewRow, modelRow);
            if (!cell.available()) return Resolution.unavailable(cell.reason());
            return new Resolution(new Target(family, context.artMeshClass.getName(), domainId,
                viewRow, modelRow, cell.cell(),
                new VisibilityLockState(visible, locked)), "");
        } catch (InvocationTargetException failure) {
            return Resolution.unavailable(reflectionFailure("host accessor", failure.getCause()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return Resolution.unavailable(reflectionFailure("exact host resolution", failure));
        }
    }

    private static CellResolution nameCell(final JTable table, final int viewRow,
        final int modelRow) {
        try {
            if (viewRow < 0 || viewRow >= table.getRowCount()) {
                return CellResolution.unavailable("view row is outside the current table");
            }
            final int viewColumn = table.convertColumnIndexToView(NAME_MODEL_COLUMN);
            if (viewColumn < 0 || viewColumn >= table.getColumnCount()) {
                return CellResolution.unavailable("reviewed name column is not visible");
            }
            if (table.convertColumnIndexToModel(viewColumn) != NAME_MODEL_COLUMN) {
                return CellResolution.unavailable("name column conversion is not exact");
            }
            final Rectangle cell = table.getCellRect(viewRow, viewColumn, true);
            Rectangle visible = table.getVisibleRect();
            // JTable's visible rect is table-local and already accounts for ancestors. The
            // explicit viewport intersection keeps a partially clipped cell from being clicked
            // outside the current viewport when this is the direct JViewport child.
            if (table.getParent() instanceof JViewport viewport) {
                visible = visible.intersection(viewport.getViewRect());
            }
            final Rectangle safe = cell.intersection(visible);
            if (safe.width <= 0 || safe.height <= 0) {
                return CellResolution.unavailable("reviewed name cell is not visible");
            }
            final Point click = new Point(safe.x + safe.width / 2, safe.y + safe.height / 2);
            if (table.rowAtPoint(click) != viewRow
                || table.columnAtPoint(click) != viewColumn) {
                return CellResolution.unavailable(
                    "name cell coordinate does not resolve to the captured row/column");
            }
            return new CellResolution(new NameCell(viewRow, modelRow, viewColumn,
                NAME_MODEL_COLUMN, safe, click), "");
        } catch (RuntimeException failure) {
            return CellResolution.unavailable(reflectionFailure("name cell", failure));
        }
    }

    private static boolean isLive(final JTable table) {
        return table.isShowing() && table.isDisplayable() && table.getParent() != null;
    }

    private static RowFamily rowFamily(final Class<?> treeModelClass,
        final Class<?> nodeClass, final HostAccessContext context) {
        if (treeModelClass == context.partsModelClass && nodeClass == context.partsNodeClass) {
            return RowFamily.PARTS;
        }
        if (treeModelClass == context.deformerModelClass
            && nodeClass == context.deformerNodeClass) return RowFamily.DEFORMER;
        throw new IllegalArgumentException("unsupported exact tree-table row family");
    }

    private static boolean exactSourceClass(final Class<?> actual, final Class<?> artMeshClass) {
        return actual != null && actual == artMeshClass;
    }

    private static AccessorShape accessorShape(final Class<?> modelClass,
        final Class<?> treeModelBase, final Class<?> nodeClass, final Class<?> sourceOwner,
        final Class<?> sourceId, final Class<?> idClass) throws ReflectiveOperationException {
        if (modelClass.getSuperclass() != DefaultTableModel.class) {
            throw new IllegalArgumentException("treeTable.j does not extend DefaultTableModel");
        }
        final Field treeModelField = modelClass.getDeclaredField("a");
        if (treeModelField.getType() != treeModelBase
            || !Modifier.isPrivate(treeModelField.getModifiers())
            || Modifier.isStatic(treeModelField.getModifiers())
            || !treeModelField.trySetAccessible()) {
            throw new IllegalArgumentException("treeTable.j.a field shape is not exact");
        }
        final Field treeField = modelClass.getDeclaredField("b");
        if (treeField.getType() != JTree.class || !Modifier.isPrivate(treeField.getModifiers())
            || Modifier.isStatic(treeField.getModifiers()) || !treeField.trySetAccessible()) {
            throw new IllegalArgumentException("treeTable.j.b field shape is not exact");
        }
        final Method nodeSource = exactMethod(nodeClass, "i", Object.class, false, true);
        final Method sourceGetId = exactMethod(sourceOwner, "getId", sourceId, true, false);
        final Method idValue = exactMethod(idClass, "getIdString", String.class, false, true);
        final Method visible = exactMethod(sourceOwner, "isVisible", boolean.class, false, true);
        final Method locked = exactMethod(sourceOwner, "isLocked", boolean.class, false, true);
        return new AccessorShape(treeModelField, treeField, nodeSource, sourceGetId, idValue,
            visible, locked);
    }

    private static Method exactMethod(final Class<?> owner, final String name,
        final Class<?> returnType, final boolean abstractRequired, final boolean finalRequired,
        final Class<?>... parameters) throws ReflectiveOperationException {
        final Method method = owner.getDeclaredMethod(name, parameters);
        final int modifiers = method.getModifiers();
        if (method.getReturnType() != returnType || !Modifier.isPublic(modifiers)
            || Modifier.isStatic(modifiers) || (abstractRequired != Modifier.isAbstract(modifiers))
            || (finalRequired != Modifier.isFinal(modifiers))) {
            throw new IllegalArgumentException("method shape is not exact: "
                + owner.getName() + '.' + name);
        }
        return method;
    }

    private static Class<?> loadExact(final ClassLoader loader, final String name)
        throws ClassNotFoundException {
        if (loader == null) throw new ClassNotFoundException(name + " has no classloader");
        final Class<?> loaded = Class.forName(name, false, loader);
        if (!name.equals(loaded.getName())) {
            throw new ClassNotFoundException("loaded class name mismatch for " + name);
        }
        return loaded;
    }

    private static ArtifactIdentity verifyHostArtifact(final Class<?> hostModelClass)
        throws IOException {
        final Path artifact = codeSourcePath(hostModelClass);
        if (!Files.isRegularFile(artifact)) {
            throw new IOException("host code source is not a regular JAR file");
        }
        final String actual = sha256(artifact);
        if (!HOST_JAR_SHA256.equals(actual)) {
            throw new IOException("host artifact SHA-256 mismatch: " + actual);
        }
        return new ArtifactIdentity(artifact, actual);
    }

    private static void verifyClassArtifact(final Class<?> hostClass, final ClassLoader loader,
        final Path artifact) throws IOException {
        if (hostClass.getClassLoader() != loader) {
            throw new IOException("host class loader is not the verified loader: "
                + hostClass.getName());
        }
        final Path classArtifact = codeSourcePath(hostClass);
        if (!artifact.equals(classArtifact)) {
            throw new IOException("host class code source differs from verified artifact: "
                + hostClass.getName());
        }
    }

    private static Path codeSourcePath(final Class<?> hostClass) throws IOException {
        final CodeSource codeSource = hostClass.getProtectionDomain() == null
            ? null : hostClass.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new IOException("host class has no code source: " + hostClass.getName());
        }
        try {
            return Path.of(codeSource.getLocation().toURI()).toRealPath();
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IOException("host code source is not a file URI: " + hostClass.getName(),
                failure);
        }
    }

    private static String sha256(final Path artifact) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 is unavailable", failure);
        }
        try (var input = Files.newInputStream(artifact)) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean usableDomainId(final String value) {
        return value != null && !value.isBlank()
            && !NOT_INITIALIZED_ID.equals(value)
            && !NOT_INITIALIZED_TYPED_ID.equals(value)
            && !ROOT_PART_ID.equals(value)
            && !ROOT_DEFORMER_ID.equals(value);
    }

    private static void requireText(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String reflectionFailure(final String stage, final Throwable failure) {
        if (failure == null) return stage + " failed";
        final String message = failure.getMessage();
        return stage + " failed: " + failure.getClass().getName()
            + (message == null || message.isBlank() ? "" : " (" + message + ')');
    }

    /* Test-only seams. They validate no host identity and cannot resolve a target. */

    static String accessorShapeFailureForTest(final Class<?> modelClass,
        final Class<?> treeModelBase, final Class<?> nodeClass, final Class<?> sourceOwner,
        final Class<?> sourceId, final Class<?> idClass) {
        try {
            accessorShape(modelClass, treeModelBase, nodeClass, sourceOwner, sourceId, idClass);
            return "";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return reflectionFailure("accessor shape", failure);
        }
    }

    static boolean exactSourceClassForTest(final Class<?> actual, final Class<?> expected) {
        return exactSourceClass(actual, expected);
    }

    static Optional<NameCell> nameCellForTest(final JTable table, final int viewRow) {
        if (!SwingUtilities.isEventDispatchThread() || table == null) return Optional.empty();
        final int modelRow;
        try {
            modelRow = table.convertRowIndexToModel(viewRow);
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
        final CellResolution result = nameCell(table, viewRow, modelRow);
        return result.available() ? Optional.of(result.cell()) : Optional.empty();
    }
}
