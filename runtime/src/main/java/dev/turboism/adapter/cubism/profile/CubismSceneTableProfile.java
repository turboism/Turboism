package dev.turboism.adapter.cubism.profile;

import dev.turboism.mapping.verification.StaticSelector;
import java.lang.reflect.Modifier;
import java.util.List;

/** Preserves existing host contracts without expanding selector admission. */
public final class CubismSceneTableProfile {
    private CubismSceneTableProfile() {}

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
    public static final String CONTROLLER_SELECT_ROW_FALLBACK = "cubism.scene-palette.controller.select-row-fallback";
    public static final String CONTROLLER_CONTENT = "cubism.scene-palette.controller.content";
    public static final String TABLE_SWING = "cubism.scene-palette.table.swing";
    public static final String CONTENT_SCENE_DOCS = "cubism.scene-palette.content.scene-docs";
    public static final String CONTENT_FILE = "cubism.scene-palette.content.file";
    public static final String DOCUMENT_SCENE_SOURCE = "cubism.scene-palette.document.scene-source";
    public static final String DOCUMENT_OPEN_SCENE = "cubism.scene-palette.document.open-scene";
    public static final String DOCUMENT_SWITCH_SCENE_DEFAULT = "cubism.scene-palette.document.switch-scene-default";
    public static final String SOURCE_SCENE_NAME = "cubism.scene-palette.source.scene-name";
    public static final String SOURCE_GUID = "cubism.scene-palette.source.guid";
    public static final String SOURCE_TAG = "cubism.scene-palette.source.tag";
    public static final String SOURCE_MOVIE_INFO = "cubism.scene-palette.source.movie-info";
    public static final String MOVIE_INFO_DISPLAY_DURATION = "cubism.scene-palette.movie-info.display-duration";
    public static final String COMPLETE_PACK_VIEW_CONTEXT = "cubism.scene-palette.complete-pack.view-context";
    public static final String VIEW_CONTEXT_DOC = "cubism.scene-palette.view-context.doc";

    private static final int PRIVATE_FINAL = Modifier.PRIVATE | Modifier.FINAL;
    private static final int PUBLIC_FINAL = Modifier.PUBLIC | Modifier.FINAL;
    private static final int PUBLIC_STATIC = Modifier.PUBLIC | Modifier.STATIC;
    private static final int STATIC = Modifier.STATIC;
    public static final List<StaticSelector> REVIEWED_SELECTORS = List.of(
            StaticSelector.classSelector(CONTROLLER_CLASS, "com/live2d/cubism/view/palette/scene/b"),
            StaticSelector.classSelector(LISTENER_CLASS, "com/live2d/cubism/view/palette/scene/m"),
            StaticSelector.classSelector(MODELING_DOCUMENT_CLASS, "com/live2d/cubism/doc/modeling/CModelingDocument"),
            StaticSelector.classSelector(SCENE_DOCUMENT_CLASS, "com/live2d/cubism/doc/animation/CSceneDocument"),
            selector(
                    CONTROLLER_TABLE_DATA,
                    StaticSelector.Kind.FIELD,
                    "com/live2d/cubism/view/palette/scene/b",
                    "h",
                    "Ljava/util/ArrayList;",
                    PRIVATE_FINAL,
                    STATIC),
            selector(
                    LISTENER_PALETTE,
                    StaticSelector.Kind.FIELD,
                    "com/live2d/cubism/view/palette/scene/m",
                    "a",
                    "Lcom/live2d/cubism/view/palette/scene/b;",
                    Modifier.FINAL,
                    STATIC),
            method(
                    CONTROLLER_COMPLETE_PACK,
                    "com/live2d/cubism/view/palette/scene/b",
                    "a",
                    "()Lcom/live2d/cubism/pack/CECompletePack;",
                    PUBLIC_FINAL),
            method(
                    CONTROLLER_TABLE,
                    "com/live2d/cubism/view/palette/scene/b",
                    "c",
                    "()Lcom/live2d/ui/control/CTable;",
                    PUBLIC_FINAL),
            method(CONTROLLER_SELECTED_ROW, "com/live2d/cubism/view/palette/scene/b", "d", "()I", PUBLIC_FINAL),
            method(CONTROLLER_SELECT_ROW, "com/live2d/cubism/view/palette/scene/b", "b", "(I)V", PUBLIC_FINAL),
            method(CONTROLLER_SELECT_ROW_FALLBACK, "com/live2d/cubism/view/palette/scene/b", "a", "(I)V", PUBLIC_FINAL),
            method(
                    CONTROLLER_CONTENT,
                    "com/live2d/cubism/view/palette/scene/b",
                    "e",
                    "()Lcom/live2d/cubism/doc/animation/CAnimationFileContent;",
                    PUBLIC_FINAL),
            method(
                    TABLE_SWING,
                    "com/live2d/ui/control/CTable",
                    "getJTable",
                    "()Lcom/live2d/ui/swingImpl/E;",
                    PUBLIC_FINAL),
            method(
                    CONTENT_SCENE_DOCS,
                    "com/live2d/cubism/doc/animation/CAnimationFileContent",
                    "getSceneDocs",
                    "()Lcom/live2d/type/CArrayList;",
                    PUBLIC_FINAL),
            method(
                    CONTENT_FILE,
                    "com/live2d/cubism/doc/animation/CAnimationFileContent",
                    "getFile",
                    "()Ljava/io/File;",
                    Modifier.PUBLIC),
            method(
                    DOCUMENT_SCENE_SOURCE,
                    "com/live2d/cubism/doc/animation/CSceneDocument",
                    "getSceneSource",
                    "()Lcom/live2d/cubism/doc/animation/CSceneSource;",
                    PUBLIC_FINAL),
            method(
                    DOCUMENT_OPEN_SCENE,
                    "com/live2d/cubism/doc/animation/CSceneDocument",
                    "openScene",
                    "()V",
                    PUBLIC_FINAL),
            selector(
                    DOCUMENT_SWITCH_SCENE_DEFAULT,
                    StaticSelector.Kind.METHOD,
                    "com/live2d/cubism/doc/animation/CSceneDocument",
                    "switchScene$default",
                    "(Lcom/live2d/cubism/doc/animation/CSceneDocument;Lcom/live2d/util/a/a;ILjava/lang/Object;)V",
                    PUBLIC_STATIC,
                    0),
            method(
                    SOURCE_SCENE_NAME,
                    "com/live2d/cubism/doc/animation/CSceneSource",
                    "getSceneName",
                    "()Ljava/lang/String;",
                    PUBLIC_FINAL),
            method(
                    SOURCE_GUID,
                    "com/live2d/cubism/doc/animation/CSceneSource",
                    "getGuid",
                    "()Lcom/live2d/type/CSceneGuid;",
                    PUBLIC_FINAL),
            method(
                    SOURCE_TAG,
                    "com/live2d/cubism/doc/animation/CSceneSource",
                    "getTag",
                    "()Ljava/lang/String;",
                    PUBLIC_FINAL),
            method(
                    SOURCE_MOVIE_INFO,
                    "com/live2d/cubism/doc/animation/CSceneSource",
                    "getMovieInfo",
                    "()Lcom/live2d/cubism/doc/animation/movie/core/CMvMovieInfo;",
                    PUBLIC_FINAL),
            method(
                    MOVIE_INFO_DISPLAY_DURATION,
                    "com/live2d/cubism/doc/animation/movie/core/CMvMovieInfo",
                    "getDisplayDuration",
                    "()I",
                    PUBLIC_FINAL),
            method(
                    COMPLETE_PACK_VIEW_CONTEXT,
                    "com/live2d/cubism/pack/CECompletePack",
                    "getCurrentViewContext",
                    "()Lcom/live2d/cubism/view/context/CEViewContext;",
                    PUBLIC_FINAL),
            method(
                    VIEW_CONTEXT_DOC,
                    "com/live2d/cubism/view/context/CEViewContext",
                    "getDoc",
                    "()Lcom/live2d/cubism/doc/IDocument;",
                    Modifier.PUBLIC));

    private static StaticSelector method(
            final String alias,
            final String owner,
            final String name,
            final String descriptor,
            final int requiredAccess) {
        return selector(alias, StaticSelector.Kind.METHOD, owner, name, descriptor, requiredAccess, STATIC);
    }

    private static StaticSelector selector(
            final String alias,
            final StaticSelector.Kind kind,
            final String owner,
            final String name,
            final String descriptor,
            final int requiredAccess,
            final int forbiddenAccess) {
        return new StaticSelector(alias, alias, kind, owner, name, descriptor, requiredAccess, forbiddenAccess);
    }
}
