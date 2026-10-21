package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Canonical selector requirements for the texture-atlas authoring surface.
 *
 * <p>These sets mirror the runtime adapter contract
 * {@code dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasSelectorContract};
 * the adapter delegates to this contract so capability admission and runtime
 * authorization resolve the exact same alias sets. Statistics aliases are not a
 * capability requirement: the statistics contribution is gated separately per
 * profile row.</p>
 */
public final class TextureAtlasSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String LAYOUT_WRITE_CAPABILITY_ID = "cubism.texture-atlas.layout.write";
    public static final String DATA_MODEL_HOOK_CAPABILITY_ID =
        "cubism.texture-atlas.data-model-hook";
    public static final String AUTO_LAYOUT_HOOK_CAPABILITY_ID =
        "cubism.texture-atlas.auto-layout-hook";
    public static final String NATIVE_INVOCATION_CAPABILITY_ID =
        "cubism.texture-atlas.native-layout-invocation";
    public static final String DIALOG_INJECTION_CAPABILITY_ID =
        "cubism.texture-atlas.dialog-injection";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.modeling-document.class",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.model-source.guid",
        "cubism.editor-model.guid.value",
        "cubism.texture-atlas.data-model.class",
        "cubism.texture-atlas.data-model.document",
        "cubism.texture-atlas.data-model.model-source",
        "cubism.texture-atlas.texture-manager.atlases",
        "cubism.texture-atlas.model-source.texture-manager",
        "cubism.texture-atlas.texture-manager.images",
        "cubism.editor-model.texture-manager.handler",
        "cubism.texture-atlas.texture-manager-handler.drawable-uses",
        "cubism.texture-atlas.texture-input-relink.helper-instance",
        "cubism.texture-atlas.texture-input-relink.rebuild",
        "cubism.editor-model.model-source.verify",
        "cubism.texture-atlas.texture-manager.change-input-to-atlas",
        "cubism.texture-atlas.texture-manager.reinit-input",
        "cubism.texture-atlas.atlas.class",
        "cubism.texture-atlas.atlas.create",
        "cubism.texture-atlas.atlas.name",
        "cubism.texture-atlas.atlas.width",
        "cubism.texture-atlas.atlas.height",
        "cubism.texture-atlas.atlas.entries",
        "cubism.texture-atlas.entry.class",
        "cubism.texture-atlas.entry.create",
        "cubism.texture-atlas.entry.image",
        "cubism.texture-atlas.entry.transform",
        "cubism.texture-atlas.image.class",
        "cubism.texture-atlas.image.guid",
        "cubism.texture-atlas.image.width",
        "cubism.texture-atlas.image.height",
        "cubism.texture-atlas.affine.class",
        "cubism.texture-atlas.affine.create",
        "cubism.texture-atlas.affine.translate",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.texture-atlas.undo.create",
        "cubism.texture-atlas.undo.force-redo",
        "cubism.texture-atlas.group-undo.add",
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.complete-pack.repaint-canvas",
        "cubism.editor-model.modeling-document.mark-dirty"
    );

    public static final Set<String> HOOK_ALIASES = Set.of(
        "cubism.texture-atlas.model-image-list.class",
        "cubism.texture-atlas.model-image-list.init",
        "cubism.texture-atlas.model-image-list.data-model"
    );

    public static final Set<String> AUTO_LAYOUT_HOOK_ALIASES = Set.of(
        "cubism.texture-atlas.auto-layout.invoke"
    );

    public static final Set<String> NATIVE_INVOCATION_ALIASES = Set.of(
        "cubism.texture-atlas.native.receiver.class",
        "cubism.texture-atlas.native.receiver.settings",
        "cubism.texture-atlas.native.receiver.data",
        "cubism.texture-atlas.native.receiver.overflow",
        "cubism.texture-atlas.native.settings.margin",
        "cubism.texture-atlas.native.settings.rotate",
        "cubism.texture-atlas.native.settings.model-image",
        "cubism.texture-atlas.native.settings.scale",
        "cubism.texture-atlas.native.data.items",
        "cubism.texture-atlas.native.data.width",
        "cubism.texture-atlas.native.data.height",
        "cubism.texture-atlas.native.data.scale",
        "cubism.texture-atlas.native.data.current-scale",
        "cubism.texture-atlas.native.data.impl",
        "cubism.texture-atlas.native.impl.container",
        "cubism.texture-atlas.native.container.children",
        "cubism.texture-atlas.native.item.rect",
        "cubism.texture-atlas.native.item.scale",
        "cubism.texture-atlas.native.item.model-rect",
        "cubism.texture-atlas.native.item.width",
        "cubism.texture-atlas.native.item.height",
        "cubism.texture-atlas.native.item.transform",
        "cubism.texture-atlas.native.item.edit-layer",
        "cubism.texture-atlas.native.edit-layer.draw-data-shapes",
        "cubism.texture-atlas.native.item.current-transform",
        "cubism.texture-atlas.native.rect.x",
        "cubism.texture-atlas.native.rect.y",
        "cubism.texture-atlas.native.rect.width",
        "cubism.texture-atlas.native.rect.height",
        "cubism.texture-atlas.native.affine.create",
        "cubism.texture-atlas.native.layer-ref.layer",
        "cubism.texture-atlas.native.layer-ref.transform",
        "cubism.texture-atlas.native.layer-ref.set-transform",
        "cubism.texture-atlas.native.editor-affine.create"
    );

    public static final Set<String> DIALOG_INJECTION_ALIASES = Set.of(
        "cubism.texture-atlas.dialog.class",
        "cubism.texture-atlas.dialog.init"
    );

    /**
     * Statistics-view selectors. These gate the optional statistics contribution per exact
     * profile rather than a declared capability: they are never added to the dependency table,
     * so a version missing them keeps every other texture-atlas capability.
     */
    public static final Set<String> STATISTICS_ALIASES = Set.of(
        "cubism.texture-atlas.statistics.view-init",
        "cubism.texture-atlas.statistics.view-data-model",
        "cubism.texture-atlas.statistics.data-model.current-page",
        "cubism.texture-atlas.statistics.page-state.atlas"
    );

    private TextureAtlasSelectorContract() {
    }
}
