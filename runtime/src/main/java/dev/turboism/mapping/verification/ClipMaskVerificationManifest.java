package dev.turboism.mapping.verification;

import java.util.Set;

/** Runtime-owned allowlist for the reviewed Cubism 5.3.02 clip-mask selector evidence. */
public final class ClipMaskVerificationManifest {

    public static final String VERIFICATION_ID = "m15.cubism-5.3.02.clipmask.static";
    public static final String RECORD_SHA256 =
        "8e4f5a5d9ea7896700a2b40293ba720b7a7df549216bfb6efdedb3d73c951232";
    public static final String CUBISM_VERSION = "5.3.02";
    public static final String PROFILE_ID = "cubism-5.3.02";
    public static final long ARTIFACT_SIZE = 41922739L;
    public static final String ARTIFACT_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    public static final String ADAPTER_SLICE_ID = "adapter.clipmask.readonly";
    public static final Set<String> CAPABILITY_IDS = Set.of("cubism.clipmask.read");
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.clipmask.app-controller.class",
        "cubism.clipmask.app-controller.instance",
        "cubism.clipmask.app-controller.current-document",
        "cubism.clipmask.document.class",
        "cubism.clipmask.modeling-document.class",
        "cubism.clipmask.modeling-document.model-source",
        "cubism.clipmask.model-source.class",
        "cubism.clipmask.model-source.all-art-meshes",
        "cubism.clipmask.art-mesh-source.class",
        "cubism.clipmask.drawable-source.class",
        "cubism.clipmask.drawable-source.guid",
        "cubism.clipmask.drawable-source.clip-guid-list",
        "cubism.clipmask.drawable-source.invert-clipping-mask",
        "cubism.clipmask.drawable-guid.class",
        "cubism.clipmask.guid.class",
        "cubism.clipmask.guid.value"
    );

    private ClipMaskVerificationManifest() {
    }
}
