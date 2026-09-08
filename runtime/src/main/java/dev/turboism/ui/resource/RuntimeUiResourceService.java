package dev.turboism.ui.resource;

import dev.turboism.adapter.ui.ThemeStatusAdapter;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.resource.UiIconAvailability;
import dev.turboism.sdk.ui.resource.UiIconRef;
import dev.turboism.sdk.ui.resource.UiResourceService;

import javax.swing.Icon;
import javax.swing.SwingUtilities;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Runtime-owned UI resource service backed by one verified native-icon resolver.
 *
 * <p>The resolver and this service are host-binding resources. A composition owner creates one
 * instance per host binding, shares that instance with all plugin contexts, and closes it when the
 * binding is replaced or disposed. Plugin contexts only receive the SDK-facing
 * {@link UiResourceService}; the Runtime-only {@link #resolve(UiIconRef, boolean)} method is kept
 * on this implementation for the renderer seam.</p>
 *
 * <p>Host presentation state is sampled only during explicit off-EDT composition or refresh. The
 * availability and resolve paths use the selected in-memory variant and never query a host,
 * filesystem or class loader.</p>
 */
public final class RuntimeUiResourceService implements UiResourceService, AutoCloseable {
    public static final int DEFAULT_SCALE_PERCENT = 100;

    private static final Presentation DEFAULT_PRESENTATION =
        new Presentation(NativeIconVariant.Theme.LIGHT, DEFAULT_SCALE_PERCENT);
    private static final RuntimeUiResourceService UNAVAILABLE =
        new RuntimeUiResourceService(Optional.empty(), DEFAULT_PRESENTATION, null);

    private final Optional<CubismNativeIconResolver> resolver;
    private final Supplier<Presentation> presentationSource;
    private final Map<LookupKey, NativeIconVariant> selections = new HashMap<>();
    private final Map<LookupKey, UiIconAvailability> availability = new HashMap<>();
    private NativeIconVariant.Theme theme;
    private int scalePercent;
    private boolean closed;

    private RuntimeUiResourceService(
        final Optional<CubismNativeIconResolver> resolver,
        final Presentation presentation,
        final Supplier<Presentation> presentationSource
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        final Presentation selected = Objects.requireNonNull(presentation, "presentation");
        this.theme = selected.theme();
        this.scalePercent = selected.scalePercent();
        this.presentationSource = presentationSource;
    }

    /** Creates a connected service with the conservative light/100% presentation default. */
    public RuntimeUiResourceService(final CubismNativeIconResolver resolver) {
        this(resolver, NativeIconVariant.Theme.LIGHT, DEFAULT_SCALE_PERCENT);
    }

    /**
     * Creates a connected service from already sampled presentation state.
     *
     * <p>Null or unsupported presentation values intentionally fall back to light/100%; they do
     * not widen the reviewed variant catalog.</p>
     */
    public RuntimeUiResourceService(
        final CubismNativeIconResolver resolver,
        final NativeIconVariant.Theme theme,
        final int scalePercent
    ) {
        this(
            Optional.of(Objects.requireNonNull(resolver, "resolver")),
            normalize(theme, scalePercent),
            null
        );
    }

    /**
     * Creates a connected service by sampling host presentation state once off the EDT.
     * Subsequent queries use only cached state. Call {@link #refreshPresentation()} off the EDT
     * after a host theme/DPI change.
     */
    public RuntimeUiResourceService(
        final CubismNativeIconResolver resolver,
        final ThemeStatusAdapter themeStatus,
        final IntSupplier scalePercent
    ) {
        this(
            Optional.of(Objects.requireNonNull(resolver, "resolver")),
            readPresentation(themeStatus, scalePercent),
            () -> readPresentation(themeStatus, scalePercent)
        );
    }

    /**
     * Explicit connected factory for host composition. This method does not preload. The host-binding
     * owner must retain and close the returned service when the binding is replaced or disposed;
     * closing the service releases the resolver's cached resources.
     */
    public static RuntimeUiResourceService connected(
        final CubismNativeIconResolver resolver,
        final ThemeStatusAdapter themeStatus,
        final IntSupplier scalePercent
    ) {
        return new RuntimeUiResourceService(resolver, themeStatus, scalePercent);
    }

    /** Creates a connected service from already sampled presentation state. */
    public static RuntimeUiResourceService connected(
        final CubismNativeIconResolver resolver,
        final NativeIconVariant.Theme theme,
        final int scalePercent
    ) {
        return new RuntimeUiResourceService(resolver, theme, scalePercent);
    }

    /**
     * Returns the fail-closed service used when no verified host-binding provider is installed.
     */
    public static RuntimeUiResourceService unavailable() {
        return UNAVAILABLE;
    }

    @Override
    public synchronized UiIconAvailability availability(final UiIconRef reference) {
        Objects.requireNonNull(reference, "reference");
        return availability(reference, false);
    }

    /**
     * Resolves one Runtime-only Swing display handle. The disabled flag changes presentation only;
     * it is never part of the SDK reference or history identity.
     */
    public synchronized Optional<Icon> resolve(final UiIconRef reference, final boolean disabled) {
        Objects.requireNonNull(reference, "reference");
        if (availability(reference, disabled) != UiIconAvailability.AVAILABLE) {
            return Optional.empty();
        }
        final CubismNativeIconResolver activeResolver = resolver.orElse(null);
        if (closed || activeResolver == null) return Optional.empty();
        return activeResolver.resolve(selection(reference, disabled));
    }

    /**
     * Samples the retained host presentation source off the EDT and invalidates only presentation
     * selection caches. A disposed service ignores the refresh and does not touch the source.
     */
    public void refreshPresentation() {
        final Supplier<Presentation> source;
        synchronized (this) {
            if (closed) return;
            source = presentationSource;
        }
        if (source == null) {
            throw new IllegalStateException("no host presentation source was supplied");
        }
        final Presentation next = source.get();
        synchronized (this) {
            if (!closed) apply(next);
        }
    }

    /** Updates presentation from an already sampled host theme without performing host IO. */
    public void updatePresentationFromHost(
        final ThemeStatusSnapshot snapshot,
        final int scalePercent
    ) {
        updatePresentation(themeOf(snapshot), scalePercent);
    }

    /** Updates presentation from an already sampled Runtime theme without performing host IO. */
    public synchronized void updatePresentation(
        final NativeIconVariant.Theme theme,
        final int scalePercent
    ) {
        if (closed) return;
        apply(normalize(theme, scalePercent));
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        selections.clear();
        availability.clear();
        resolver.ifPresent(CubismNativeIconResolver::close);
    }

    private UiIconAvailability availability(
        final UiIconRef reference,
        final boolean disabled
    ) {
        if (closed || resolver.isEmpty()) return UiIconAvailability.SERVICE_UNAVAILABLE;
        final LookupKey key = new LookupKey(reference, disabled);
        return availability.computeIfAbsent(
            key,
            ignored -> resolver.orElseThrow().availability(selection(reference, disabled))
        );
    }

    private NativeIconVariant selection(final UiIconRef reference, final boolean disabled) {
        final LookupKey key = new LookupKey(reference, disabled);
        return selections.computeIfAbsent(
            key,
            ignored -> new NativeIconVariant(reference.icon(), theme, scalePercent, disabled)
        );
    }

    private void apply(final Presentation presentation) {
        theme = presentation.theme();
        scalePercent = presentation.scalePercent();
        selections.clear();
        availability.clear();
    }

    private static Presentation readPresentation(
        final ThemeStatusAdapter themeStatus,
        final IntSupplier scalePercent
    ) {
        requireOffEdt();
        Objects.requireNonNull(themeStatus, "themeStatus");
        Objects.requireNonNull(scalePercent, "scalePercent");

        NativeIconVariant.Theme theme = NativeIconVariant.Theme.LIGHT;
        try {
            final ThemeStatusAdapter.AdapterResult<Optional<ThemeStatusSnapshot>> result =
                themeStatus.themeStatus();
            if (result != null && result.isAvailable()) {
                final Optional<ThemeStatusSnapshot> snapshot = result.value().orElse(Optional.empty());
                if (snapshot.isPresent() && snapshot.orElseThrow().dark()) {
                    theme = NativeIconVariant.Theme.DARK;
                }
            }
        } catch (RuntimeException ignored) {
            // Presentation is optional; an adapter failure must not make resource access fail open.
        }

        int sampledScale = DEFAULT_SCALE_PERCENT;
        try {
            sampledScale = scalePercent.getAsInt();
        } catch (RuntimeException ignored) {
            // Retain the conservative default when the optional DPI source is unavailable.
        }
        return normalize(theme, sampledScale);
    }

    private static NativeIconVariant.Theme themeOf(final ThemeStatusSnapshot snapshot) {
        return snapshot != null && snapshot.dark()
            ? NativeIconVariant.Theme.DARK
            : NativeIconVariant.Theme.LIGHT;
    }

    private static Presentation normalize(
        final NativeIconVariant.Theme theme,
        final int scalePercent
    ) {
        final NativeIconVariant.Theme normalizedTheme =
            theme == null ? NativeIconVariant.Theme.LIGHT : theme;
        final int normalizedScale = switch (scalePercent) {
            case 100, 125, 150, 175, 200 -> scalePercent;
            default -> DEFAULT_SCALE_PERCENT;
        };
        return new Presentation(normalizedTheme, normalizedScale);
    }

    private static void requireOffEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("native UI presentation sampling must run off the EDT");
        }
    }

    private record Presentation(NativeIconVariant.Theme theme, int scalePercent) { }

    private record LookupKey(UiIconRef reference, boolean disabled) { }
}
