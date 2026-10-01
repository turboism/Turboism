package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.Objects;
import java.util.Set;

/** Exact reviewed host shape for the premain mesh-editor session lifecycle hook. */
public record MeshToolSessionHostProfile(
        String editorVersion,
        String meshEditorOwnerInternalName,
        String startMethod,
        String startDescriptor,
        String endMethod,
        String endDescriptor) {
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("5.2.03", "5.3.02", "5.3.03");

    public MeshToolSessionHostProfile {
        editorVersion = requireText(editorVersion, "editorVersion");
        meshEditorOwnerInternalName = requireText(meshEditorOwnerInternalName, "meshEditorOwnerInternalName");
        startMethod = requireText(startMethod, "startMethod");
        startDescriptor = requireText(startDescriptor, "startDescriptor");
        endMethod = requireText(endMethod, "endMethod");
        endDescriptor = requireText(endDescriptor, "endDescriptor");
        if (!SUPPORTED_VERSIONS.contains(editorVersion)) {
            throw new IllegalArgumentException("Unsupported exact mesh-tool session version: " + editorVersion);
        }
        if (!"(Ljava/util/List;)V".equals(startDescriptor) || !"()V".equals(endDescriptor)) {
            throw new IllegalArgumentException(
                    "Mesh-tool lifecycle descriptors must be exact startMode(List)/endMode shapes.");
        }
    }

    /** Resolves and validates an exact-version host profile from verified selectors. */
    public static MeshToolSessionHostProfile from(final VerifiedMemberResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (!MeshToolSessionSelectorContract.authorizes(resolver)) {
            throw new IllegalStateException("Exact mesh-tool session selectors are not authorized.");
        }
        final StaticSelector owner = resolver.verifiedSelector(MeshToolSessionSelectorContract.MESH_EDITOR_CLASS);
        final StaticSelector start = resolver.verifiedSelector(MeshToolSessionSelectorContract.MESH_EDITOR_START_MODE);
        final StaticSelector end = resolver.verifiedSelector(MeshToolSessionSelectorContract.MESH_EDITOR_END_MODE);
        if (owner.kind() != StaticSelector.Kind.CLASS
                || !owner.ownerInternalName().equals(start.ownerInternalName())
                || !owner.ownerInternalName().equals(end.ownerInternalName())) {
            throw new IllegalStateException("Mesh-tool lifecycle selectors do not share the exact reviewed owner.");
        }
        return new MeshToolSessionHostProfile(
                resolver.cubismVersion(),
                owner.ownerInternalName(),
                start.memberName(),
                start.descriptor(),
                end.memberName(),
                end.descriptor());
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name).trim();
        if (text.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return text;
    }
}
