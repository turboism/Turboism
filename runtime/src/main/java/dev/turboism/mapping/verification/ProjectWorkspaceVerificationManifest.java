package dev.turboism.mapping.verification;

import java.util.Set;

/** Runtime-owned allowlist for the reviewed Cubism 5.3.02 project/workspace evidence. */
public final class ProjectWorkspaceVerificationManifest {

    public static final String VERIFICATION_ID = "m15.cubism-5.3.02.project-workspace.static";
    public static final String RECORD_SHA256 =
        "447ab125f76f411be8833da375fdc0c743e1797eddfd3bf13b24a277e46ca846";
    public static final String CUBISM_VERSION = "5.3.02";
    public static final String PROFILE_ID = "cubism-5.3.02";
    public static final long ARTIFACT_SIZE = 41922739L;
    public static final String ARTIFACT_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    public static final String ADAPTER_SLICE_ID = "adapter.project-workspace.readonly";
    public static final Set<String> CAPABILITY_IDS = Set.of(
        "cubism.project.read",
        "cubism.workspace.read"
    );
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.app-controller.class",
        "cubism.project.class",
        "cubism.document.class",
        "cubism.file-content.class",
        "cubism.main-frame.class",
        "cubism.dock-wrapper.class",
        "cubism.workspace.class",
        "cubism.id.class",
        "cubism.guid.class",
        "cubism.app-controller.instance",
        "cubism.app-controller.current-project",
        "cubism.app-controller.main-frame",
        "cubism.project.name",
        "cubism.project.documents",
        "cubism.document.file-content",
        "cubism.file-content.file",
        "cubism.main-frame.dock-manager",
        "cubism.dock-wrapper.last-workspace",
        "cubism.workspace.id",
        "cubism.workspace.guid",
        "cubism.id.value",
        "cubism.guid.value"
    );

    private ProjectWorkspaceVerificationManifest() {
    }
}
