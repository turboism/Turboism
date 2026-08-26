package dev.turboism.tests.preview;

import java.nio.file.Path;
import java.util.Map;

/** Builds an SDK-only plugin JAR that characterizes preview PluginContext services. */
final class PreviewContextServicesPluginJarFixture {

    static final String PLUGIN_ID = "dev.example.preview-context-services";
    static final String MARKER_DIRECTORY_PROPERTY =
        "dev.turboism.tests.preview.context-services.marker-directory";
    static final String READY_FILE_NAME = "preview-context-services.ready";
    static final Map<String, String> EXPECTED_MARKER_VALUES =
        PreviewContextServicesFixtureResources.expectedMarkerValues();

    private PreviewContextServicesPluginJarFixture() {
    }

    static Path write(final Path plugins, final Path temporary) throws Exception {
        return PreviewContextServicesFixtureArchive.write(
            plugins, temporary, MARKER_DIRECTORY_PROPERTY
        );
    }

    static Path readyFile(final Path markerDirectory) {
        return markerDirectory.resolve(READY_FILE_NAME);
    }
}
