package dev.turboism.ui;

import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.ViewportSnapshot;

import java.util.Optional;

/**
 * Runtime seam for UI host state used by the M12 fake-first UI capability service.
 */
public interface UiHostStateSource {

    UiHostStateSource DEFAULT = new UiHostStateSource() {
    };

    default ViewportSnapshot viewport() {
        return new ViewportSnapshot("viewport-default", 1, 1, 1.0);
    }

    default Optional<String> chooseFile(FileChooserRequest request) {
        return Optional.empty();
    }
}
