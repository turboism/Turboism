package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for Editor Inspector Drawable/ArtMesh family
 * authoring writes (Cubism 5.3.02 host). Mirrors the Inspector undo envelopes.
 */
public final class EditorInspectorDrawableWriteSelectorContract {

    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = "cubism.editor-model.art-mesh.inspector-write";

    /**
     * Alpha-composition aliases that exist only on Cubism 5.3.02.
     *
     * <p>This is the single definition of what the 5.2.03 Editor-model scope must subtract for
     * this family. It previously listed two of the eight, which left the 5.2.03 manifest claiming
     * six aliases its reviewed record does not carry.</p>
     */
    public static final Set<String> ALPHA_COMPOSITION_ALIASES = Set.of(
        "cubism.editor-model.alpha-composition.atop",
        "cubism.editor-model.alpha-composition.class",
        "cubism.editor-model.alpha-composition.conjoint",
        "cubism.editor-model.alpha-composition.disjoint",
        "cubism.editor-model.alpha-composition.out",
        "cubism.editor-model.alpha-composition.over",
        "cubism.editor-model.alpha-composition.values",
        "cubism.editor-model.art-mesh-source.set-alpha-composition"
    );

    public static final Set<String> REQUIRED_ALIASES = aliases();

    private static Set<String> aliases() {
        final HashSet<String> aliases = new HashSet<>(Set.of(
            "cubism.editor-model.app-controller.instance",
            "cubism.editor-model.app-controller.current-document",
            "cubism.editor-model.app-controller.complete-pack",
            "cubism.editor-model.modeling-document.edit-mode",
            "cubism.editor-model.modeling-document.mark-dirty",
            "cubism.editor-model.edit-mode.begin",
            "cubism.editor-model.edit-mode.end",
            "cubism.editor-model.undo.add",
            "cubism.editor-model.undo.add-listener",
            "cubism.editor-model.undo-listener.class",
            "cubism.editor-model.model-source.update-instances",
            "cubism.editor-model.model-source.all-art-meshes",
            "cubism.editor-model.model.all-art-meshes",
            "cubism.editor-model.art-mesh-source.class",
            "cubism.editor-model.art-mesh.class",
            "cubism.editor-model.art-mesh.source",
            "cubism.editor-model.art-mesh.current-keyform",
            "cubism.editor-model.art-mesh-source.guid",
            "cubism.editor-model.art-mesh-source.clip-guid-list",
            "cubism.editor-model.parameter-controllable-source.id",
            "cubism.editor-model.parameter-controllable-source.handler",
            "cubism.editor-model.parameter-controllable-handler.class",
            "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
            "cubism.editor-model.parameter-controllable-handler.create-undo-for-basic-setting",
            "cubism.editor-model.parameter-controllable-handler.create-undo-for-keyform-edit",
            "cubism.editor-model.parameter-controllable-handler.change-target-deformer",
            "cubism.editor-model.parameter-controllable-handler.change-target-deformer-guid",
            "cubism.editor-model.complete-pack.update-part-palette",
            "cubism.editor-model.complete-pack.repaint-canvas",
            "cubism.editor-model.drawable-form.draw-order",
            "cubism.editor-model.drawable-form.set-draw-order",
            "cubism.editor-model.drawable-form.multiply-color",
            "cubism.editor-model.drawable-form.screen-color",
            "cubism.editor-model.float-color.set-red",
            "cubism.editor-model.float-color.set-green",
            "cubism.editor-model.float-color.set-blue",
            "cubism.editor-model.float-color.set-alpha",
            "cubism.editor-model.float-color.red",
            "cubism.editor-model.float-color.green",
            "cubism.editor-model.float-color.blue",
            "cubism.editor-model.float-color.alpha",
            // id write (Inspector setId envelope)
            "cubism.editor-model.drawable-source.set-id",
            "cubism.editor-model.drawable-id.create",
            "cubism.editor-model.model-source.handler",
            "cubism.editor-model.model-handler.id-map",
            "cubism.editor-model.id-map.contains",
            "cubism.editor-model.model-source.verify",
            "cubism.editor-model.complete-pack.update-manager",
            "cubism.editor-model.update-manager.update-part",
            "cubism.editor-model.update-manager.update-deformer",
            // targetDeformer write (DeformerSelectorUiFactory envelope)
            "cubism.editor-model.deformer-id.create",
            "cubism.editor-model.deformer-guid.companion",
            "cubism.editor-model.deformer-guid.root",
            "cubism.editor-model.model-source.all-deformers",
            "cubism.editor-model.model.all-deformers",
            // clippingMaskId write (IdListEditable setContent envelope)
            "cubism.editor-model.model-source.get-object",
            "cubism.editor-model.parameter-controllable-source.guid",
            "cubism.editor-model.drawable-guid.class",
            "cubism.editor-model.id-list.clear",
            "cubism.editor-model.id-list.add-all",
            // invertClippingMask version gate (CUB3-2528)
            "cubism.editor-model.art-mesh-source.set-invert-clipping-mask",
            "cubism.editor-model.model-source.target-version",
            "cubism.editor-model.target-version.number",
            // multiply/screen color version gate (CUB3-3264/CUB3-3265)
            // colorComposition write (setColorComposition envelope)
            "cubism.editor-model.art-mesh-source.set-color-composition",
            "cubism.editor-model.color-composition.values",
            // alphaComposition write (5302 only)
            "cubism.editor-model.alpha-composition.values",
            "cubism.editor-model.art-mesh-source.set-alpha-composition",
            // culling write (setCulling + CArtMesh.setupShader envelope)
            "cubism.editor-model.art-mesh-source.set-culling",
            "cubism.editor-model.art-mesh.setup-shader",
            // userData write (MultiEditConsentChecker_UserData envelope)
            "cubism.editor-model.art-mesh-source.set-user-data"
        ));
        return Set.copyOf(aliases);
    }

    private EditorInspectorDrawableWriteSelectorContract() {
    }
}
