package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Map;
import java.util.Optional;

/** Exact selector tuple recovered from the reviewed legacy 5.2/5.3 path. */
public record MeshMirrorHostProfile(
        String meshEditorOwner,
        String mirrorPointMethod,
        String mirrorAxisPointMethod,
        String mirrorPointDescriptor,
        String mirrorHitMethod,
        String mirrorHitDescriptor,
        String mirrorWidgetOwner,
        String mirrorWidgetMethod,
        String mirrorWidgetDescriptor,
        String mirrorAxisDrawOwner,
        String mirrorAxisDrawMethod,
        String mirrorAxisDrawDescriptor,
        ToolEligibility toolEligibility,
        SelectedPointMove selectedPointMove,
        LinkedDeletion linkedDeletion) {
    /** Exact 5.2.03 selector for the native mirror/subtool eligibility predicate. */
    public record ToolEligibility(String method, String descriptor) {}

    /** Exact 5.2.03 selected-point movement loop that 5.3.02 extends with counterparts. */
    public record SelectedPointMove(String owner, String method, String descriptor) {}

    /**
     * Selectors for mirror-linked deletion, which Cubism ships natively from 5.3.02.
     * Null on hosts that already have it, so the transformer never double-applies.
     *
     * <p>Each action names the enclosing method to transform plus the inner call to
     * intercept inside it. Interception is used rather than a fixed offset so the
     * injected step lands exactly where the host is about to delete.
     */
    public record LinkedDeletion(
            String pointActionOwner,
            String pointActionMethod,
            String pointActionDescriptor,
            String pointDeleteOwner,
            String pointDeleteMethod,
            String pointDeleteDescriptor,
            String edgeActionOwner,
            String edgeActionMethod,
            String edgeActionDescriptor,
            String edgeUndoOwner,
            String edgeUndoMethod,
            String edgeUndoDescriptor,
            String edgeRemoveOwner,
            String edgeRemoveMethod,
            String edgeRemoveDescriptor,
            String eraserActionOwner,
            String eraserActionMethod,
            String eraserActionDescriptor,
            String eraserRemoveOwner,
            String eraserRemoveMethod,
            String eraserRemoveDescriptor,
            String eraserPointRemoveOwner,
            String eraserPointRemoveMethod,
            String eraserPointRemoveDescriptor) {}

    /** Keeps the twelve-argument shape used by hosts that need no linked-deletion backport. */
    public MeshMirrorHostProfile(
            final String meshEditorOwner,
            final String mirrorPointMethod,
            final String mirrorAxisPointMethod,
            final String mirrorPointDescriptor,
            final String mirrorHitMethod,
            final String mirrorHitDescriptor,
            final String mirrorWidgetOwner,
            final String mirrorWidgetMethod,
            final String mirrorWidgetDescriptor,
            final String mirrorAxisDrawOwner,
            final String mirrorAxisDrawMethod,
            final String mirrorAxisDrawDescriptor) {
        this(
                meshEditorOwner,
                mirrorPointMethod,
                mirrorAxisPointMethod,
                mirrorPointDescriptor,
                mirrorHitMethod,
                mirrorHitDescriptor,
                mirrorWidgetOwner,
                mirrorWidgetMethod,
                mirrorWidgetDescriptor,
                mirrorAxisDrawOwner,
                mirrorAxisDrawMethod,
                mirrorAxisDrawDescriptor,
                null,
                null,
                null);
    }

    private static final HostArtifactDigest CUBISM_52 = ReviewedHostArtifacts.CUBISM_5_2_03;
    private static final HostArtifactDigest CUBISM_53 = ReviewedHostArtifacts.CUBISM_5_3_02;
    private static final HostArtifactDigest CUBISM_5303 = ReviewedHostArtifacts.CUBISM_5_3_03;

    /** Returns the exact reviewed selector profile for the artifact, if it is supported. */
    public static Optional<MeshMirrorHostProfile> forArtifact(final HostArtifactDigest artifact) {
        if (CUBISM_52.equals(artifact)) return Optional.of(reviewed52());
        if (CUBISM_53.equals(artifact) || CUBISM_5303.equals(artifact)) {
            return Optional.of(reviewed52And53());
        }
        return Optional.empty();
    }

    /**
     * Returns the reviewed selector profile for a declared reviewed version,
     * independently of the whole-artifact digest. Callers must still verify the
     * pinned class digests in {@link #reviewedClassSha256()} against the actual
     * artifact — the version alone is never proof.
     */
    public static Optional<MeshMirrorHostProfile> forReviewedVersion(final String version) {
        if (ReviewedHostArtifacts.CUBISM_5_2_03_VERSION.equals(version)) {
            return Optional.of(reviewed52());
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02_VERSION.equals(version)
                || ReviewedHostArtifacts.CUBISM_5_3_03_VERSION.equals(version)) {
            return Optional.of(reviewed52And53());
        }
        return Optional.empty();
    }

    /**
     * SHA-256 of every host class this hook transforms or calls into, keyed by
     * internal name, for every reviewed generation. 5.2.03 additionally pins the
     * linked-deletion action and edit-mode classes its backport weaves.
     */
    public static Map<String, Map<String, String>> reviewedClassSha256() {
        return REVIEWED_CLASS_SHA256;
    }

    private static final Map<String, Map<String, String>> REVIEWED_CLASS_SHA256 = Map.of(
            ReviewedHostArtifacts.CUBISM_5_2_03_VERSION,
                    Map.ofEntries(
                            Map.entry(
                                    "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/g",
                                    "1eba7b9ca963a7a8b004239273eb279919319360438ab51f9cdb74686ac9217a"),
                            Map.entry(
                                    "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/ToolPanel_MeshEdit",
                                    "2da109c7d6f5411f5d929036429026c3cb5e0a5f2d2cdeef0f6a34f73a69ceb9"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/K",
                                    "6f5e80281b87db33656fd5f4fe189cb9e4ac0e2e85030e8183307ce5c5de6c6a"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/action/action_meshEditor/d",
                                    "e69ff56a26b64de0fcccc556a2d91dd655495516a50b47ea625b031c0e425286"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/action/action_meshEditor/d$g",
                                    "b8c2e7d8a58b3939488b69d71312c17fd4b68aa3d9ac350a6a255f8d4c6561a3"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/action/action_meshEditor/d$f",
                                    "eb1893ef3c63ca30ed024e98b1c6eb8b5fcd883bc72a05f90f37a7c424f528ef"),
                            Map.entry(
                                    "com/live2d/cubism/doc/modeling/CModelingEditMode_MeshEditor",
                                    "e2514556485ff55552b36c17e82d997c78e8766a5b71906cfee913edf363802f"),
                            Map.entry(
                                    "com/live2d/cubism/view/context/action/p$b",
                                    "38f5a9b96c6ec98761a97d3655895e89589b68a134d091022511e8bc6b248458"),
                            Map.entry(
                                    "com/live2d/graphics3d/editableMesh/GEditableMesh2",
                                    "734b9bde593f27816b72f63c585a371d724507f21c97ac41980cbf3f347c57ad"),
                            Map.entry(
                                    "com/live2d/graphics3d/editableMesh/GEditableMeshHandler",
                                    "92a6591591e226df86dc68a45de97fa4600afd0339f3f11c9954b7e63f8990ba")),
            ReviewedHostArtifacts.CUBISM_5_3_02_VERSION,
                    Map.of(
                            "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/g",
                            "5bb61894c12e0a818ad8c6f5aede9ba0d20f4431ab7b6ab95fc3eee344345665",
                            "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/ToolPanel_MeshEdit",
                            "b78162063007b79e1c3c605363a620f23478eb53eb0aab78fae777e3056033bb",
                            "com/live2d/cubism/view/context/K",
                            "760f67ec9470e1f5aadb6d364741e250b0b078ac6e9d2aed73e9faafdb2da8cb"),
            ReviewedHostArtifacts.CUBISM_5_3_03_VERSION,
                    Map.of(
                            "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/g",
                            "5bb61894c12e0a818ad8c6f5aede9ba0d20f4431ab7b6ab95fc3eee344345665",
                            "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/ToolPanel_MeshEdit",
                            "b78162063007b79e1c3c605363a620f23478eb53eb0aab78fae777e3056033bb",
                            "com/live2d/cubism/view/context/K",
                            "492f1238c8865f5945675fad80c9950043f7960754c1edb12a3066be510a99df"));

    /**
     * 5.2.03 adds the linked-deletion selectors; 5.3.02 must not receive them because it
     * already deletes mirror counterparts natively.
     */
    static MeshMirrorHostProfile reviewed52() {
        final MeshMirrorHostProfile shared = reviewed52And53();
        return new MeshMirrorHostProfile(
                shared.meshEditorOwner(),
                shared.mirrorPointMethod(),
                shared.mirrorAxisPointMethod(),
                shared.mirrorPointDescriptor(),
                shared.mirrorHitMethod(),
                shared.mirrorHitDescriptor(),
                shared.mirrorWidgetOwner(),
                shared.mirrorWidgetMethod(),
                shared.mirrorWidgetDescriptor(),
                shared.mirrorAxisDrawOwner(),
                shared.mirrorAxisDrawMethod(),
                shared.mirrorAxisDrawDescriptor(),
                new ToolEligibility(
                        "a", "(Lcom/live2d/cubism/view/palette/tool/toolMode/meshEditor/ToolMode_MeshEdit_Manual$a;)Z"),
                new SelectedPointMove(
                        "com/live2d/cubism/view/context/action/action_meshEditor/d",
                        "c",
                        "(Lcom/live2d/cubism/view/context/actionManager/Z;)V"),
                new LinkedDeletion(
                        "com/live2d/cubism/view/context/action/action_meshEditor/d$g",
                        "b",
                        "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
                        "com/live2d/cubism/doc/modeling/CModelingEditMode_MeshEditor",
                        "delete_exe",
                        "(Ljava/util/List;Lcom/live2d/undo/GroupUndo;)V",
                        "com/live2d/cubism/view/context/action/action_meshEditor/d$f",
                        "b",
                        "(Lcom/live2d/cubism/view/context/actionManager/N;)V",
                        "com/live2d/cubism/view/context/actionManager/N",
                        "a",
                        "(Ljava/lang/String;)Lcom/live2d/undo/GroupUndo;",
                        "com/live2d/graphics3d/editableMesh/GEditableMesh2",
                        "removeEdge",
                        "(Lcom/live2d/graphics3d/editableMesh/MEdge;)V",
                        "com/live2d/cubism/view/context/action/p$b",
                        "a",
                        "(Lcom/live2d/cubism/view/context/actionManager/Z;Lcom/live2d/ui/event/l;Lcom/live2d/undo/GroupUndo;)V",
                        "com/live2d/graphics3d/editableMesh/GEditableMeshHandler",
                        "a",
                        "(Ljava/util/List;)Lcom/live2d/undo/GroupUndo;",
                        "com/live2d/graphics3d/editableMesh/GEditableMeshHandler",
                        "a",
                        "(Ljava/util/List;Z)Lcom/live2d/undo/GroupUndo;"));
    }

    static MeshMirrorHostProfile reviewed52And53() {
        return new MeshMirrorHostProfile(
                "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/g",
                "a",
                "b",
                "(Lcom/live2d/graphics3d/type/GVector2;)Lcom/live2d/graphics3d/type/GVector2;",
                "a",
                "(Lcom/live2d/graphics3d/type/GVector2;F)Z",
                "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/ToolPanel_MeshEdit",
                "createWidgetMirrorEditForMeshEdit",
                "(Lcom/live2d/ui/control/CCheckBox;)Lcom/live2d/ui/container/CVBox;",
                "com/live2d/cubism/view/context/K",
                "a",
                "(FZFLcom/live2d/type/CColor;)V");
    }
}
