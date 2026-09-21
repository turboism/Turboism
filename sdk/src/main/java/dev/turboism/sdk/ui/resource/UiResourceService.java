package dev.turboism.sdk.ui.resource;

import java.util.Objects;

/**
 * Bounded native display-resource service. No paths, image bytes or native objects are exposed.
 *
 * <p>Availability is a snapshot, not a reservation: the renderer must revalidate its own current
 * binding and retain text fallback. Implementations must not perform blocking host IO in this
 * query; validation and decoded-image ownership belong to the Runtime provider.</p>
 */
public interface UiResourceService {
    /** Creates a host-independent reference, including when the provider is unavailable. */
    default UiIconRef cubismIcon(final CubismIcon icon) {
        return new UiIconRef(icon);
    }

    /** Returns current cached availability without exporting the resource. */
    UiIconAvailability availability(UiIconRef reference);

    /** Fail-closed compatibility default for runtimes without an installed resource provider. */
    static UiResourceService unavailable() {
        return UnavailableUiResourceService.INSTANCE;
    }
}

final class UnavailableUiResourceService implements UiResourceService {
    static final UiResourceService INSTANCE = new UnavailableUiResourceService();

    private UnavailableUiResourceService() {
    }

    @Override
    public UiIconAvailability availability(final UiIconRef reference) {
        Objects.requireNonNull(reference, "reference");
        return UiIconAvailability.SERVICE_UNAVAILABLE;
    }
}
