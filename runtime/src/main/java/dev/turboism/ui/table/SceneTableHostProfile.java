package dev.turboism.ui.table;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;

import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Exact-artifact Scene palette profile backed only by reviewed static host evidence. */
public final class SceneTableHostProfile {

    public static final String CONTROLLER_CLASS = "cubism.scene-palette.controller.class";
    public static final String LISTENER_CLASS = "cubism.scene-palette.listener.class";
    public static final String MODELING_DOCUMENT_CLASS = "cubism.scene-palette.modeling-document.class";
    public static final String SCENE_DOCUMENT_CLASS = "cubism.scene-palette.document.class";
    public static final String CONTROLLER_TABLE_DATA = "cubism.scene-palette.controller.table-data";
    public static final String LISTENER_PALETTE = "cubism.scene-palette.listener.palette";
    public static final String CONTROLLER_COMPLETE_PACK = "cubism.scene-palette.controller.complete-pack";
    public static final String CONTROLLER_TABLE = "cubism.scene-palette.controller.table";
    public static final String CONTROLLER_SELECTED_ROW = "cubism.scene-palette.controller.selected-row";
    public static final String CONTROLLER_SELECT_ROW = "cubism.scene-palette.controller.select-row";
    public static final String CONTROLLER_SELECT_ROW_FALLBACK =
        "cubism.scene-palette.controller.select-row-fallback";
    public static final String CONTROLLER_CONTENT = "cubism.scene-palette.controller.content";
    public static final String TABLE_SWING = "cubism.scene-palette.table.swing";
    public static final String CONTENT_SCENE_DOCS = "cubism.scene-palette.content.scene-docs";
    public static final String CONTENT_FILE = "cubism.scene-palette.content.file";
    public static final String DOCUMENT_SCENE_SOURCE = "cubism.scene-palette.document.scene-source";
    public static final String DOCUMENT_OPEN_SCENE = "cubism.scene-palette.document.open-scene";
    public static final String DOCUMENT_SWITCH_SCENE_DEFAULT =
        "cubism.scene-palette.document.switch-scene-default";
    public static final String SOURCE_SCENE_NAME = "cubism.scene-palette.source.scene-name";
    public static final String SOURCE_GUID = "cubism.scene-palette.source.guid";
    public static final String SOURCE_TAG = "cubism.scene-palette.source.tag";
    public static final String SOURCE_MOVIE_INFO = "cubism.scene-palette.source.movie-info";
    public static final String MOVIE_INFO_DISPLAY_DURATION =
        "cubism.scene-palette.movie-info.display-duration";
    public static final String COMPLETE_PACK_VIEW_CONTEXT =
        "cubism.scene-palette.complete-pack.view-context";
    public static final String VIEW_CONTEXT_DOC = "cubism.scene-palette.view-context.doc";

    private static final int PRIVATE_FINAL = Modifier.PRIVATE | Modifier.FINAL;
    private static final int PUBLIC_FINAL = Modifier.PUBLIC | Modifier.FINAL;
    private static final int PUBLIC_STATIC = Modifier.PUBLIC | Modifier.STATIC;
    private static final int STATIC = Modifier.STATIC;
    private static final List<StaticSelector> REVIEWED_SELECTORS = List.of(
        StaticSelector.classSelector(CONTROLLER_CLASS, "com/live2d/cubism/view/palette/scene/b"),
        StaticSelector.classSelector(LISTENER_CLASS, "com/live2d/cubism/view/palette/scene/m"),
        StaticSelector.classSelector(
            MODELING_DOCUMENT_CLASS, "com/live2d/cubism/doc/modeling/CModelingDocument"
        ),
        StaticSelector.classSelector(
            SCENE_DOCUMENT_CLASS, "com/live2d/cubism/doc/animation/CSceneDocument"
        ),
        selector(
            CONTROLLER_TABLE_DATA, StaticSelector.Kind.FIELD,
            "com/live2d/cubism/view/palette/scene/b", "h", "Ljava/util/ArrayList;",
            PRIVATE_FINAL, STATIC
        ),
        selector(
            LISTENER_PALETTE, StaticSelector.Kind.FIELD,
            "com/live2d/cubism/view/palette/scene/m", "a",
            "Lcom/live2d/cubism/view/palette/scene/b;", Modifier.FINAL, STATIC
        ),
        method(
            CONTROLLER_COMPLETE_PACK, "com/live2d/cubism/view/palette/scene/b", "a",
            "()Lcom/live2d/cubism/pack/CECompletePack;", PUBLIC_FINAL
        ),
        method(
            CONTROLLER_TABLE, "com/live2d/cubism/view/palette/scene/b", "c",
            "()Lcom/live2d/ui/control/CTable;", PUBLIC_FINAL
        ),
        method(
            CONTROLLER_SELECTED_ROW, "com/live2d/cubism/view/palette/scene/b", "d", "()I",
            PUBLIC_FINAL
        ),
        method(
            CONTROLLER_SELECT_ROW, "com/live2d/cubism/view/palette/scene/b", "b", "(I)V",
            PUBLIC_FINAL
        ),
        method(
            CONTROLLER_SELECT_ROW_FALLBACK, "com/live2d/cubism/view/palette/scene/b", "a", "(I)V",
            PUBLIC_FINAL
        ),
        method(
            CONTROLLER_CONTENT, "com/live2d/cubism/view/palette/scene/b", "e",
            "()Lcom/live2d/cubism/doc/animation/CAnimationFileContent;", PUBLIC_FINAL
        ),
        method(
            TABLE_SWING, "com/live2d/ui/control/CTable", "getJTable",
            "()Lcom/live2d/ui/swingImpl/E;", PUBLIC_FINAL
        ),
        method(
            CONTENT_SCENE_DOCS, "com/live2d/cubism/doc/animation/CAnimationFileContent",
            "getSceneDocs", "()Lcom/live2d/type/CArrayList;", PUBLIC_FINAL
        ),
        method(
            CONTENT_FILE, "com/live2d/cubism/doc/animation/CAnimationFileContent", "getFile",
            "()Ljava/io/File;", Modifier.PUBLIC
        ),
        method(
            DOCUMENT_SCENE_SOURCE, "com/live2d/cubism/doc/animation/CSceneDocument",
            "getSceneSource", "()Lcom/live2d/cubism/doc/animation/CSceneSource;", PUBLIC_FINAL
        ),
        method(
            DOCUMENT_OPEN_SCENE, "com/live2d/cubism/doc/animation/CSceneDocument", "openScene",
            "()V", PUBLIC_FINAL
        ),
        selector(
            DOCUMENT_SWITCH_SCENE_DEFAULT, StaticSelector.Kind.METHOD,
            "com/live2d/cubism/doc/animation/CSceneDocument", "switchScene$default",
            "(Lcom/live2d/cubism/doc/animation/CSceneDocument;Lcom/live2d/util/a/a;ILjava/lang/Object;)V",
            PUBLIC_STATIC, 0
        ),
        method(
            SOURCE_SCENE_NAME, "com/live2d/cubism/doc/animation/CSceneSource", "getSceneName",
            "()Ljava/lang/String;", PUBLIC_FINAL
        ),
        method(
            SOURCE_GUID, "com/live2d/cubism/doc/animation/CSceneSource", "getGuid",
            "()Lcom/live2d/type/CSceneGuid;", PUBLIC_FINAL
        ),
        method(
            SOURCE_TAG, "com/live2d/cubism/doc/animation/CSceneSource", "getTag",
            "()Ljava/lang/String;", PUBLIC_FINAL
        ),
        method(
            SOURCE_MOVIE_INFO, "com/live2d/cubism/doc/animation/CSceneSource", "getMovieInfo",
            "()Lcom/live2d/cubism/doc/animation/movie/core/CMvMovieInfo;", PUBLIC_FINAL
        ),
        method(
            MOVIE_INFO_DISPLAY_DURATION,
            "com/live2d/cubism/doc/animation/movie/core/CMvMovieInfo", "getDisplayDuration",
            "()I", PUBLIC_FINAL
        ),
        method(
            COMPLETE_PACK_VIEW_CONTEXT, "com/live2d/cubism/pack/CECompletePack",
            "getCurrentViewContext", "()Lcom/live2d/cubism/view/context/CEViewContext;", PUBLIC_FINAL
        ),
        method(
            VIEW_CONTEXT_DOC, "com/live2d/cubism/view/context/CEViewContext", "getDoc",
            "()Lcom/live2d/cubism/doc/IDocument;", Modifier.PUBLIC
        )
    );

    private final String cubismVersion;
    private final HostArtifactDigest artifact;
    private final List<StaticSelector> selectors;

    SceneTableHostProfile(
        final String cubismVersion,
        final HostArtifactDigest artifact,
        final List<StaticSelector> selectors
    ) {
        this.cubismVersion = requireText(cubismVersion, "cubismVersion");
        this.artifact = Objects.requireNonNull(artifact, "artifact");
        this.selectors = List.copyOf(Objects.requireNonNull(selectors, "selectors"));
        if (this.selectors.isEmpty()) {
            throw new IllegalArgumentException("selectors must not be empty");
        }
        final long aliases = this.selectors.stream().map(StaticSelector::alias).distinct().count();
        if (aliases != this.selectors.size()) {
            throw new IllegalArgumentException("selector aliases must be unique");
        }
    }

    /** Admits only exact 5.2.03 and 5.3.02 artifacts; 5.3.03 and unknown artifacts stay closed. */
    public static Optional<SceneTableHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (ReviewedHostArtifacts.CUBISM_5_2_03.equals(artifact)) {
            return Optional.of(new SceneTableHostProfile(
                ReviewedHostArtifacts.CUBISM_5_2_03_VERSION, artifact, REVIEWED_SELECTORS
            ));
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02.equals(artifact)) {
            return Optional.of(new SceneTableHostProfile(
                ReviewedHostArtifacts.CUBISM_5_3_02_VERSION, artifact, REVIEWED_SELECTORS
            ));
        }
        return Optional.empty();
    }

    /** Returns the exact reviewed Cubism semantic version this profile is admitted for. */
    public String cubismVersion() {
        return cubismVersion;
    }

    /** Returns the exact reviewed host artifact digest this profile is bound to. */
    public HostArtifactDigest artifact() {
        return artifact;
    }

    List<StaticSelector> selectors() {
        return selectors;
    }

    /** Resolves every reviewed owner/name/descriptor before palette discovery may begin. */
    Bound bind(final ClassLoader hostClassLoader) {
        return new Bound(this, Objects.requireNonNull(hostClassLoader, "hostClassLoader"));
    }

    private static StaticSelector method(
        final String alias,
        final String owner,
        final String name,
        final String descriptor,
        final int requiredAccess
    ) {
        return selector(
            alias, StaticSelector.Kind.METHOD, owner, name, descriptor, requiredAccess, STATIC
        );
    }

    private static StaticSelector selector(
        final String alias,
        final StaticSelector.Kind kind,
        final String owner,
        final String name,
        final String descriptor,
        final int requiredAccess,
        final int forbiddenAccess
    ) {
        return new StaticSelector(
            alias, alias, kind, owner, name, descriptor, requiredAccess, forbiddenAccess
        );
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Fully pre-bound access plan for one admitted artifact and defining host classloader. */
    static final class Bound {
        private final SceneTableHostProfile profile;
        private final Map<String, Class<?>> classes = new LinkedHashMap<>();
        private final Map<String, Field> fields = new LinkedHashMap<>();
        private final Map<String, Method> methods = new LinkedHashMap<>();

        private Bound(final SceneTableHostProfile profile, final ClassLoader hostClassLoader) {
            this.profile = profile;
            for (StaticSelector selector : profile.selectors) {
                bind(selector, hostClassLoader);
            }
        }

        String cubismVersion() {
            return profile.cubismVersion;
        }

        boolean isController(final Object value) {
            return isInstance(CONTROLLER_CLASS, value);
        }

        boolean isNativeSceneRowListener(final Object value) {
            return isExactInstance(LISTENER_CLASS, value);
        }

        boolean isModelingDocument(final Object value) {
            return isInstance(MODELING_DOCUMENT_CLASS, value);
        }

        boolean isSceneDocument(final Object value) {
            return isInstance(SCENE_DOCUMENT_CLASS, value);
        }

        Object listenerPalette(final Object listener) {
            return read(LISTENER_PALETTE, listener);
        }

        Object controllerTableData(final Object palette) {
            return read(CONTROLLER_TABLE_DATA, palette);
        }

        Object controllerCompletePack(final Object palette) {
            return invoke(CONTROLLER_COMPLETE_PACK, palette);
        }

        Object controllerTable(final Object palette) {
            return invoke(CONTROLLER_TABLE, palette);
        }

        int controllerSelectedRow(final Object palette) {
            return ((Number) invoke(CONTROLLER_SELECTED_ROW, palette)).intValue();
        }

        void controllerSelectRow(final Object palette, final int row) {
            invoke(CONTROLLER_SELECT_ROW, palette, Integer.valueOf(row));
        }

        void controllerSelectRowFallback(final Object palette, final int row) {
            invoke(CONTROLLER_SELECT_ROW_FALLBACK, palette, Integer.valueOf(row));
        }

        Object controllerContent(final Object palette) {
            return invoke(CONTROLLER_CONTENT, palette);
        }

        Object tableSwing(final Object wrapper) {
            return invoke(TABLE_SWING, wrapper);
        }

        Object contentSceneDocs(final Object content) {
            return invoke(CONTENT_SCENE_DOCS, content);
        }

        Object contentFile(final Object content) {
            return invoke(CONTENT_FILE, content);
        }

        Object documentSceneSource(final Object document) {
            return invoke(DOCUMENT_SCENE_SOURCE, document);
        }

        void documentOpenScene(final Object document) {
            invoke(DOCUMENT_OPEN_SCENE, document);
        }

        void documentSwitchSceneDefault(final Object document) {
            invoke(DOCUMENT_SWITCH_SCENE_DEFAULT, null, document, null, Integer.valueOf(1), null);
        }

        Object sourceSceneName(final Object source) {
            return invoke(SOURCE_SCENE_NAME, source);
        }

        Object sourceGuid(final Object source) {
            return invoke(SOURCE_GUID, source);
        }

        Object sourceTag(final Object source) {
            return invoke(SOURCE_TAG, source);
        }

        Object sourceMovieInfo(final Object source) {
            return invoke(SOURCE_MOVIE_INFO, source);
        }

        int movieInfoDisplayDuration(final Object movieInfo) {
            return ((Number) invoke(MOVIE_INFO_DISPLAY_DURATION, movieInfo)).intValue();
        }

        Object completePackViewContext(final Object completePack) {
            return invoke(COMPLETE_PACK_VIEW_CONTEXT, completePack);
        }

        Object viewContextDoc(final Object viewContext) {
            return invoke(VIEW_CONTEXT_DOC, viewContext);
        }

        private boolean isInstance(final String alias, final Object value) {
            return value != null && requiredClass(alias).isInstance(value);
        }

        private boolean isExactInstance(final String alias, final Object value) {
            return value != null && value.getClass().equals(requiredClass(alias));
        }

        private Object read(final String alias, final Object target) {
            final Field field = requiredField(alias);
            if (target == null || !field.getDeclaringClass().isInstance(target)) {
                throw accessFailure();
            }
            try {
                return field.get(target);
            } catch (IllegalAccessException | IllegalArgumentException failure) {
                throw accessFailure();
            }
        }

        private Object invoke(final String alias, final Object target, final Object... arguments) {
            final Method method = requiredMethod(alias);
            if (!Modifier.isStatic(method.getModifiers())
                && (target == null || !method.getDeclaringClass().isInstance(target))) {
                throw accessFailure();
            }
            try {
                return method.invoke(target, arguments);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException failure) {
                throw accessFailure();
            }
        }

        private void bind(final StaticSelector selector, final ClassLoader hostClassLoader) {
            try {
                final Class<?> owner = Class.forName(
                    selector.ownerInternalName().replace('/', '.'), false, hostClassLoader
                );
                if (owner.getClassLoader() != hostClassLoader) {
                    throw accessFailure();
                }
                if (selector.kind() == StaticSelector.Kind.CLASS) {
                    classes.put(selector.alias(), owner);
                    return;
                }
                if (selector.kind() == StaticSelector.Kind.FIELD) {
                    final Field field = owner.getDeclaredField(selector.memberName());
                    final Class<?> fieldType = MethodType.fromMethodDescriptorString(
                        "()" + selector.descriptor(), hostClassLoader
                    ).returnType();
                    requireMemberShape(field.getModifiers(), field.getType().equals(fieldType), selector);
                    if (!field.trySetAccessible()) throw accessFailure();
                    fields.put(selector.alias(), field);
                    return;
                }
                final MethodType type = MethodType.fromMethodDescriptorString(
                    selector.descriptor(), hostClassLoader
                );
                final Method method = owner.getDeclaredMethod(
                    selector.memberName(), type.parameterArray()
                );
                requireMemberShape(
                    method.getModifiers(), method.getReturnType().equals(type.returnType()), selector
                );
                if (!method.trySetAccessible()) throw accessFailure();
                methods.put(selector.alias(), method);
            } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException
                     | IllegalArgumentException | LinkageError | SecurityException failure) {
                throw accessFailure();
            }
        }

        private static void requireMemberShape(
            final int modifiers,
            final boolean descriptorMatches,
            final StaticSelector selector
        ) {
            if (!descriptorMatches
                || (modifiers & selector.requiredAccessFlags()) != selector.requiredAccessFlags()
                || (modifiers & selector.forbiddenAccessFlags()) != 0) {
                throw accessFailure();
            }
        }

        private Class<?> requiredClass(final String alias) {
            final Class<?> value = classes.get(alias);
            if (value == null) throw accessFailure();
            return value;
        }

        private Field requiredField(final String alias) {
            final Field value = fields.get(alias);
            if (value == null) throw accessFailure();
            return value;
        }

        private Method requiredMethod(final String alias) {
            final Method value = methods.get(alias);
            if (value == null) throw accessFailure();
            return value;
        }

        private static IllegalArgumentException accessFailure() {
            return new IllegalArgumentException("Scene palette host binding failed safely.");
        }
    }
}
