package dev.turboism.adapter.cubism.modeling;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

/** Exact setupCurrentTool owner and signature, derived from the reviewed artifact record. */
public record ModelingToolHostProfile(
        String editorVersion, String ownerInternalName, String method, String descriptor) {
    public ModelingToolHostProfile {
        if (!ModelingSelectionSelectorContract.SUPPORTED_VERSIONS.contains(editorVersion)
                || !"com/live2d/cubism/CEAppCtrl".equals(ownerInternalName)
                || !"setupCurrentTool".equals(method)
                || !"(Lcom/live2d/cubism/view/palette/tool/toolMode/AToolGroup;Lcom/live2d/cubism/view/palette/tool/toolMode/AToolMode;Z)V"
                        .equals(descriptor)) {
            throw new IllegalArgumentException("unsupported native modeling tool lifecycle shape");
        }
    }

    /** Derives the exact tool-setter owner and descriptor from independently admitted selectors. */
    public static ModelingToolHostProfile from(VerifiedMemberResolver resolver) {
        if (!ModelingSelectionSelectorContract.authorizes(resolver))
            throw new IllegalStateException("modeling selectors are unavailable");
        final var selector = resolver.verifiedSelector(ModelingSelectionSelectorContract.SETUP_TOOL);
        if (selector.kind() != StaticSelector.Kind.METHOD)
            throw new IllegalStateException("modeling setter is not a method");
        return new ModelingToolHostProfile(
                resolver.cubismVersion(), selector.ownerInternalName(), selector.memberName(), selector.descriptor());
    }
}
