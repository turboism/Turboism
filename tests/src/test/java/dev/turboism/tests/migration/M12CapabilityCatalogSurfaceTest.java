package dev.turboism.tests.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class M12CapabilityCatalogSurfaceTest {

    private static final Path REPO_ROOT = Paths.get(System.getProperty("projectRoot", System.getProperty("user.dir")));
    private static final Path CAPABILITY_CATALOG = REPO_ROOT.resolve("docs/migration/capabilities/capability-catalog.tsv");
    private static final Set<String> M12_PUBLIC_SDK_SURFACES = Set.of(
        "dev.turboism.sdk.cubism.ClipMaskSnapshot",
        "dev.turboism.sdk.cubism.PsdDocumentSnapshot",
        "dev.turboism.sdk.cubism.RenderStatusSnapshot",
        "dev.turboism.sdk.cubism.TextureAtlasSnapshot",
        "dev.turboism.sdk.cubism.WorkspaceSnapshot",
        "dev.turboism.sdk.cubism.boundingbox.BoundingBoxWriteCommand",
        "dev.turboism.sdk.cubism.deformer.DeformerWriteCommand",
        "dev.turboism.sdk.cubism.mesh.MeshWriteCommand",
        "dev.turboism.sdk.cubism.mesh.MirrorWritebackCommand",
        "dev.turboism.sdk.cubism.psd.PsdBindingWriteCommand",
        "dev.turboism.sdk.cubism.write.WriteCanvasCommand",
        "dev.turboism.sdk.cubism.write.WriteClipMaskCommand",
        "dev.turboism.sdk.cubism.write.WriteModelObjectCommand",
        "dev.turboism.sdk.cubism.write.WriteParameterCommand",
        "dev.turboism.sdk.theme.ThemeStatusSnapshot",
        "dev.turboism.sdk.ui.DialogRequest",
        "dev.turboism.sdk.ui.EmbeddedPanelContribution",
        "dev.turboism.sdk.ui.FileChooserRequest",
        "dev.turboism.sdk.ui.OverlayContribution",
        "dev.turboism.sdk.ui.StatusNotification",
        "dev.turboism.sdk.ui.UiHostCapabilityService",
        "dev.turboism.sdk.ui.ViewportSnapshot"
    );

    @Test
    void catalogNamesEveryM12PublicSdkRepresentative() throws Exception {
        List<String> lines = Files.readAllLines(CAPABILITY_CATALOG);
        List<String> columns = Arrays.asList(lines.get(0).split("\t", -1));
        int sdkSurfaceIndex = columns.indexOf("sdkSurface");
        Set<String> catalogedSurfaces = new HashSet<>();

        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            catalogedSurfaces.add(lines.get(i).split("\t", -1)[sdkSurfaceIndex].trim());
        }

        assertTrue(catalogedSurfaces.containsAll(M12_PUBLIC_SDK_SURFACES),
            "Every M12-added public SDK surface must be named by the canonical capability catalog");
    }
}
