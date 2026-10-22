package dev.turboism.mapping.verification.selector;

import dev.turboism.sdk.ReviewedVersions;
import java.util.HashSet;
import java.util.Set;

/**
 * Official Editor exporter, reviewed on the exact 5.3.02 artifact.
 * Native data remains inside the runtime adapter.
 */
public final class EditorMocExportSelectorContract {
    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.moc-export.read";

    /**
     * Reviewed generations that carry the complete official MOC 5.3 export surface.
     *
     * <p>Boundary evidence (class-file observation on every reviewed jar): the
     * exporter writes {@code CMocExportFormat.V5_03_00} and
     * {@code CMocExportSetting.setExportInvisibleAlias(Z)V}. Both members exist only
     * from 5.3.00 on — pre-5.3 jars declare the enum constants
     * {@code V3_00_00, V3_03_00, V4_00_00, V4_02_00, V5_00_00} and no invisible-alias
     * setter (5.0.00-5.0.02 additionally lack {@code setEnableExtendEdgeColor}), which
     * matches the Editor 5.3 release of the MOC3 5.3 format. The editor-model records
     * list {@link #CAPABILITY_ID} for exactly this range.</p>
     */
    public static final Set<String> SUPPORTED_CUBISM_VERSIONS =
        ReviewedVersions.line("5.3");
    public static final Set<String> NEW_ALIASES = Set.of(
        "cubism.editor-model.official-export.exporter-create",
        "cubism.editor-model.official-export.settings-create",
        "cubism.editor-model.official-export.exporter-settings",
        "cubism.editor-model.official-export.export",
        "cubism.editor-model.official-export.format-530",
        "cubism.editor-model.official-export.set-format",
        "cubism.editor-model.official-export.set-origin-x",
        "cubism.editor-model.official-export.set-origin-y",
        "cubism.editor-model.official-export.set-pixels-per-unit",
        "cubism.editor-model.official-export.set-invisible-part",
        "cubism.editor-model.official-export.set-sketch-part",
        "cubism.editor-model.official-export.set-invisible-mesh",
        "cubism.editor-model.official-export.set-invisible-alias",
        "cubism.editor-model.official-export.set-extend-edge",
        "cubism.editor-model.official-export.moc-bytes",
        "cubism.editor-model.official-export.texture-images"
    );
    public static final Set<String> REQUIRED_ALIASES;
    static {
        final var aliases = new HashSet<>(NEW_ALIASES);
        aliases.addAll(Set.of(
        "cubism.editor-model.model-source.current-instance",
        "cubism.editor-model.model-source.model-instances",
        "cubism.texture-atlas.model-source.texture-manager",
        "cubism.texture-atlas.texture-manager.atlases",
        "cubism.editor-model.image-resource.image",
        "cubism.editor-model.image-resource.width",
        "cubism.editor-model.image-resource.height",
        "cubism.editor-model.writable-image.j-buffered-image"
        ));
        REQUIRED_ALIASES = Set.copyOf(aliases);
    }
    private EditorMocExportSelectorContract() { }
}
