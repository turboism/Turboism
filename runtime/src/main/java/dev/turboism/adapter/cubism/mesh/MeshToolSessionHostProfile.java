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
        String endDescriptor,
        String modelingDocumentOwnerInternalName,
        String setEditModeMethod,
        String setEditModeDescriptor) {
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("5.2.03", "5.3.02", "5.3.03");

    public MeshToolSessionHostProfile {
        editorVersion = requireText(editorVersion, "editorVersion");
        meshEditorOwnerInternalName = requireText(meshEditorOwnerInternalName, "meshEditorOwnerInternalName");
        startMethod = requireText(startMethod, "startMethod");
        startDescriptor = requireText(startDescriptor, "startDescriptor");
        endMethod = requireText(endMethod, "endMethod");
        endDescriptor = requireText(endDescriptor, "endDescriptor");
        modelingDocumentOwnerInternalName =
                requireText(modelingDocumentOwnerInternalName, "modelingDocumentOwnerInternalName");
        setEditModeMethod = requireText(setEditModeMethod, "setEditModeMethod");
        setEditModeDescriptor = requireText(setEditModeDescriptor, "setEditModeDescriptor");
        if (!SUPPORTED_VERSIONS.contains(editorVersion)) {
            throw new IllegalArgumentException("Unsupported exact mesh-tool session version: " + editorVersion);
        }
        if (!"(Ljava/util/List;)V".equals(startDescriptor) || !"()V".equals(endDescriptor)) {
            throw new IllegalArgumentException(
                    "Mesh-tool lifecycle descriptors must be exact startMode(List)/endMode shapes.");
        }
        final org.objectweb.asm.Type[] arguments = org.objectweb.asm.Type.getArgumentTypes(setEditModeDescriptor);
        if (meshEditorOwnerInternalName.equals(modelingDocumentOwnerInternalName)
                || arguments.length != 1
                || arguments[0].getSort() != org.objectweb.asm.Type.OBJECT
                || !org.objectweb.asm.Type.VOID_TYPE.equals(
                        org.objectweb.asm.Type.getReturnType(setEditModeDescriptor))) {
            throw new IllegalArgumentException(
                    "Document mode switches require a separate owner and one mode argument.");
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
        final StaticSelector document =
                resolver.verifiedSelector(MeshToolSessionSelectorContract.MODELING_DOCUMENT_CLASS);
        final StaticSelector setMode =
                resolver.verifiedSelector(MeshToolSessionSelectorContract.MODELING_DOCUMENT_SET_EDIT_MODE);
        if (owner.kind() != StaticSelector.Kind.CLASS
                || !owner.ownerInternalName().equals(start.ownerInternalName())
                || !owner.ownerInternalName().equals(end.ownerInternalName())) {
            throw new IllegalStateException("Mesh-tool lifecycle selectors do not share the exact reviewed owner.");
        }
        if (document.kind() != StaticSelector.Kind.CLASS
                || setMode.kind() != StaticSelector.Kind.METHOD
                || !document.ownerInternalName().equals(setMode.ownerInternalName())) {
            throw new IllegalStateException("Document mode-switch selectors do not share the exact reviewed owner.");
        }
        return new MeshToolSessionHostProfile(
                resolver.cubismVersion(),
                owner.ownerInternalName(),
                start.memberName(),
                start.descriptor(),
                end.memberName(),
                end.descriptor(),
                document.ownerInternalName(),
                setMode.memberName(),
                setMode.descriptor());
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name).trim();
        if (text.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return text;
    }
}
