package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.*;
import dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport;
import dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Check;
import dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Operation;
import java.util.Set;

/** Metadata queries only: never resolves/invokes a host member or starts a transaction. */
final class EditorOrdinaryKeyformWriteSupport {
    static OrdinaryKeyformWriteSupport read(
            VerifiedMemberResolver resolver, EditorObjectReadCore.Kind kind, boolean coordinatorPresent) {
        String capability;
        Set<String> aliases;
        switch (kind) {
            case ART_MESH -> {
                capability = EditorArtMeshKeyformSelectorContract.CAPABILITY_ID;
                aliases = EditorArtMeshKeyformSelectorContract.REQUIRED_ALIASES;
            }
            case WARP -> {
                capability = EditorWarpAuthoringSelectorContract.WRITE_CAPABILITY_ID;
                aliases = EditorWarpAuthoringSelectorContract.WRITE_ALIASES;
            }
            case ROTATION -> {
                capability = EditorRotationAuthoringSelectorContract.WRITE_CAPABILITY_ID;
                aliases = EditorRotationAuthoringSelectorContract.WRITE_ALIASES;
            }
            default -> throw new IllegalArgumentException();
        }
        boolean writer =
                resolver.authorizesFeature(EditorArtMeshKeyformSelectorContract.ADAPTER_SLICE_ID, capability, aliases);
        // Rotation's stored writer also calls the source-writer admission guard.
        if (kind == EditorObjectReadCore.Kind.ROTATION)
            writer &= resolver.authorizesFeature(
                    EditorObjectWriteSelectorContract.ADAPTER_SLICE_ID,
                    EditorObjectWriteSelectorContract.ROTATION_CAPABILITY_ID,
                    EditorObjectWriteSelectorContract.ROTATION_REQUIRED_ALIASES);
        var operations = new java.util.EnumMap<Operation, Check>(Operation.class);
        switch (kind) {
            case ART_MESH -> {
                for (var operation : java.util.List.of(
                        Operation.ART_MESH_POSITIONS, Operation.ART_MESH_DRAW_ORDERS, Operation.ART_MESH_VALUES))
                    operations.put(operation, check(writer));
                operations.put(
                        Operation.ART_MESH_VALUES_WITH_OPACITY,
                        check(resolver.authorizesFeature(
                                EditorArtMeshKeyformSelectorContract.ADAPTER_SLICE_ID,
                                EditorArtMeshKeyformSelectorContract.CAPABILITY_ID,
                                EditorArtMeshKeyformSelectorContract.OPACITY_REQUIRED_ALIASES)));
            }
            case WARP -> operations.put(Operation.WARP_CONTROL_POINTS, check(writer));
            case ROTATION -> operations.put(Operation.ROTATION_FORMS, check(writer));
        }
        return new OrdinaryKeyformWriteSupport(
                resolver.cubismVersion(),
                check(EditorReviewedGeneration.admits(resolver)),
                check(writer),
                check(resolver.authorizesFeature(
                        "adapter.editor-model.readwrite",
                        "cubism.editor-history.read",
                        EditorHistoryReadSelectorContract.REQUIRED_ALIASES)),
                check(coordinatorPresent),
                Check.UNKNOWN,
                operations);
    }

    private static Check check(boolean value) {
        return value ? Check.PRESENT : Check.ABSENT;
    }
}
