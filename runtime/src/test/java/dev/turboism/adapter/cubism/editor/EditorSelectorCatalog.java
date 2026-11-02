package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.CompletePack;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.Document;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.EditMode;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.GroupUndo;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.Host;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.Id;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.Listener;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.Model;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.ModelHandler;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.ModelSource;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.NativeSelector;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.ObjectSource;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.Parameter;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.ParameterSet;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.Undo;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.UndoManager;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.UpdateManager;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.desc;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.internal;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.method;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.type;

import dev.turboism.mapping.verification.StaticSelector;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical selector inventories shared by the Editor adapter tests.
 *
 * <p>The fixtures in {@link EditorTestFixtures} model the reviewed Cubism Editor surface, so the
 * aliases that describe that surface are also the baseline resolver records for most editor tests.
 * Keeping the standard blocks here avoids repeating the same selector literals in every test while
 * leaving test-specific aliases next to the test that needs them.
 */
public final class EditorSelectorCatalog {

    private EditorSelectorCatalog() {}

    /** App controller, document selection, and update-manager routes shared by every editor test. */
    public static List<StaticSelector> standardAppControllerSelectors() {
        return List.of(
                StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)),
                StaticSelector.staticMethod(
                        "cubism.editor-model.app-controller.instance",
                        internal(Host.class),
                        "instance",
                        desc(Host.class),
                        StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method(
                        "cubism.editor-model.app-controller.current-document",
                        Host.class,
                        "currentDocument",
                        desc(Document.class)),
                method(
                        "cubism.editor-model.app-controller.complete-pack",
                        Host.class,
                        "completePack",
                        desc(CompletePack.class)),
                method(
                        "cubism.editor-model.app-controller.update-manager",
                        Host.class,
                        "updateManager",
                        desc(UpdateManager.class)),
                method("cubism.editor-model.app-controller.command-delete", Host.class, "commandDelete", "()V"),
                method(
                        "cubism.editor-model.app-controller.command-delete-deformer-and-set-param",
                        Host.class,
                        "commandDeleteDeformerAndSetParam",
                        "()V"),
                StaticSelector.classSelector("cubism.editor-model.update-manager.class", internal(UpdateManager.class)),
                method(
                        "cubism.editor-model.update-manager.set-selection",
                        UpdateManager.class,
                        "setSelection",
                        "(Ljava/lang/Object;Ljava/util/List;ZZ)V"));
    }

    /** Modeling-document identity, edit mode, selection, and dirty-marking routes. */
    public static List<StaticSelector> standardDocumentSelectors() {
        return List.of(
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)),
                method(
                        "cubism.editor-model.modeling-document.model-source",
                        Document.class,
                        "modelSource",
                        desc(ModelSource.class)),
                method(
                        "cubism.editor-model.modeling-document.edit-mode",
                        Document.class,
                        "editMode",
                        desc(EditMode.class)),
                method(
                        "cubism.editor-history.document.undo-manager",
                        Document.class,
                        "undoManager",
                        desc(UndoManager.class)),
                method(
                        "cubism.editor-model.modeling-document.selector",
                        Document.class,
                        "selector",
                        desc(NativeSelector.class)),
                method(
                        "cubism.editor-model.parameter-controllable-source.selection",
                        ObjectSource.class,
                        "selection",
                        desc(ObjectSource.class)),
                method(
                        "cubism.editor-model.selector.replace-selected",
                        NativeSelector.class,
                        "replace",
                        "(Ljava/util/List;I)V"),
                method("cubism.editor-model.selector.selected", NativeSelector.class, "selected", "()Ljava/util/List;"),
                method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
    }

    /** Edit-mode, undo/redo, and history-manager routes shared by undoable editor writes. */
    public static List<StaticSelector> standardHistorySelectors() {
        return List.of(
                method(
                        "cubism.editor-model.edit-mode.begin",
                        EditMode.class,
                        "begin",
                        "(Ljava/lang/String;)" + type(GroupUndo.class)),
                method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"),
                method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"),
                method(
                        "cubism.editor-model.undo.add-listener",
                        Undo.class,
                        "addListener",
                        "(" + type(Listener.class) + ")Z"),
                StaticSelector.classSelector("cubism.editor-history.manager.class", internal(UndoManager.class)),
                method("cubism.editor-history.manager.entries", UndoManager.class, "entries", "()Ljava/util/List;"),
                method("cubism.editor-history.manager.position", UndoManager.class, "position", "()I"),
                method("cubism.editor-history.manager.can-undo", UndoManager.class, "canUndo", "()Ljava/lang/Boolean;"),
                method("cubism.editor-history.manager.can-redo", UndoManager.class, "canRedo", "()Ljava/lang/Boolean;"),
                StaticSelector.classSelector("cubism.editor-history.entry.class", internal(GroupUndo.class)),
                method(
                        "cubism.editor-history.entry.presentation-name",
                        GroupUndo.class,
                        "presentationName",
                        "()Ljava/lang/String;"),
                method(
                        "cubism.editor-history.entry.significant",
                        GroupUndo.class,
                        "significant",
                        "()Ljava/lang/Boolean;"),
                StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)),
                method("cubism.editor-model.undo.execute-undo", Undo.class, "executeUndo", "()V"),
                method("cubism.editor-model.undo.group-undo", GroupUndo.class, "groupUndo", "()V"));
    }

    /** Model, model-source, identity, and hierarchy-entry routes. */
    public static List<StaticSelector> standardModelSelectors() {
        return List.of(
                StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)),
                method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
                method(
                        "cubism.editor-model.model-source.current-instance",
                        ModelSource.class,
                        "currentInstance",
                        desc(Model.class)),
                method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"),
                method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"),
                method("cubism.editor-model.part-id.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.part-id.class", internal(Id.class)),
                method(
                        "cubism.editor-model.model-source.update-instances",
                        ModelSource.class,
                        "updateInstances",
                        "()V"),
                method(
                        "cubism.editor-model.model-source.update-visible-lock-hierarchy",
                        ModelSource.class,
                        "updateVisibleAndLockHierarchy",
                        "()V"),
                method("cubism.editor-model.model-source.parts", ModelSource.class, "allParts", "()Ljava/util/List;"),
                method(
                        "cubism.editor-model.model-source.all-deformers",
                        ModelSource.class,
                        "allDeformers",
                        "()Ljava/util/List;"),
                method(
                        "cubism.editor-model.model-source.all-art-meshes",
                        ModelSource.class,
                        "allArtMeshes",
                        "()Ljava/util/List;"),
                method(
                        "cubism.editor-model.model-source.all-objects",
                        ModelSource.class,
                        "allObjects",
                        "()Ljava/util/List;"),
                method(
                        "cubism.editor-model.model-source.handler",
                        ModelSource.class,
                        "handler",
                        desc(ModelHandler.class)));
    }

    /** Parameter set, parameter, and parameter-source routes shared by parameter editor tests. */
    public static List<StaticSelector> standardParameterSelectors() {
        return List.of(
                method(
                        "cubism.editor-model.model.parameter-set",
                        Model.class,
                        "parameterSet",
                        desc(ParameterSet.class)),
                method(
                        "cubism.editor-model.parameter-set.parameters",
                        ParameterSet.class,
                        "parameters",
                        "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.parameter.class", internal(Parameter.class)),
                method("cubism.editor-model.parameter.id", Parameter.class, "id", desc(Id.class)));
    }

    /**
     * Compact read-only baseline used by model edit-level tests: controller identity, document
     * model-source projection, model identity, and GUID access.
     */
    public static List<StaticSelector> standardModelReadSelectors() {
        return List.of(
                StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)),
                StaticSelector.staticMethod(
                        "cubism.editor-model.app-controller.instance",
                        internal(Host.class),
                        "instance",
                        desc(Host.class),
                        StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method(
                        "cubism.editor-model.app-controller.current-document",
                        Host.class,
                        "currentDocument",
                        desc(Document.class)),
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)),
                method(
                        "cubism.editor-model.modeling-document.model-source",
                        Document.class,
                        "modelSource",
                        desc(ModelSource.class)),
                StaticSelector.classSelector("cubism.editor-model.model-source.class", internal(ModelSource.class)),
                method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
                method(
                        "cubism.editor-model.model-source.current-instance",
                        ModelSource.class,
                        "currentInstance",
                        desc(Model.class)),
                StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)),
                StaticSelector.classSelector("cubism.editor-model.guid.class", internal(Id.class)),
                method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
    }

    /**
     * Undoable write baseline used by model-profile and morph-target writes: controller/document
     * identity, edit-mode bookkeeping, undo listener registration, palette refresh, and repaint.
     */
    public static List<StaticSelector> standardWriteSelectors() {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        selectors.add(StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                internal(Host.class),
                "instance",
                desc(Host.class),
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        selectors.add(method(
                "cubism.editor-model.app-controller.current-document",
                Host.class,
                "currentDocument",
                desc(Document.class)));
        selectors.add(method(
                "cubism.editor-model.app-controller.complete-pack",
                Host.class,
                "completePack",
                desc(CompletePack.class)));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method(
                "cubism.editor-model.modeling-document.edit-mode", Document.class, "editMode", desc(EditMode.class)));
        selectors.add(method("cubism.editor-model.modeling-document.mark-dirty", Document.class, "markDirty", "()V"));
        selectors.add(method(
                "cubism.editor-model.edit-mode.begin",
                EditMode.class,
                "begin",
                "(Ljava/lang/String;)" + type(GroupUndo.class)));
        selectors.add(method("cubism.editor-model.edit-mode.end", EditMode.class, "end", "(ZLjava/lang/Object;)V"));
        selectors.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(" + type(Undo.class) + "Z)Z"));
        selectors.add(method(
                "cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(" + type(Listener.class) + ")Z"));
        selectors.add(
                StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        selectors.add(method(
                "cubism.editor-model.model-source.update-instances", ModelSource.class, "updateInstances", "()V"));
        selectors.add(method(
                "cubism.editor-model.complete-pack.update-part-palette", CompletePack.class, "updateParts", "(Z)V"));
        selectors.add(
                method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"));
        return List.copyOf(selectors);
    }
}
