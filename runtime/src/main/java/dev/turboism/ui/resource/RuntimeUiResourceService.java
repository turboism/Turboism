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
 * <p>The host-binding owner creates one instance per host binding, shares its one stable
 * {@link #sdkView()} with all plugin contexts, and closes this owner when the binding is replaced or
 * disposed. The SDK view implements only {@link UiResourceService}; plugins cannot close the shared
 * resolver through the SDK surface. The Runtime-only {@link #resolve(UiIconRef, boolean)} method and
 * {@link #close()} remain on this owner for the renderer and host lifecycle seams.</p>
 *
 * <p>Host presentation state is sampled only during explicit off-EDT composition or refresh. The
 * availability and resolve paths use the selected in-memory variant and never query a host,
 * filesystem or class loader.</p>
 */
public final class RuntimeUiResourceService implements AutoCloseable {
    public static final int DEFAULT_SCALE_PERCENT = 100;

    private static final Presentation DEFAULT_PRESENTATION =
        new Presentation(NativeIconVariant.Theme.LIGHT, DEFAULT_SCALE_PERCENT);
    private static final RuntimeUiResourceService UNAVAILABLE =
        new RuntimeUiResourceService(null, DEFAULT_PRESENTATION, null);

    private CubismNativeIconResolver resolver;
    private Supplier<Presentation> presentationSource;
    private final UiResourceService sdkView;
    private final Map<LookupKey, NativeIconVariant> selections = new HashMap<>();
    private NativeIconVariant.Theme theme;
    private int scalePercent;
    private boolean closed;

    private RuntimeUiResourceService(
        final CubismNativeIconResolver resolver,
        final Presentation presentation,
        final Supplier<Presentation> presentationSource
    ) {
        this.resolver = resolver;
        final Presentation selected = Objects.requireNonNull(presentation, "presentation");
        this.theme = selected.theme();
        this.scalePercent = selected.scalePercent();
        this.presentationSource = presentationSource;
        this.sdkView = new SdkView();
    }

    /** Creates a connected owner with the explicit light/100% presentation fallback. */
    public RuntimeUiResourceService(final CubismNativeIconResolver resolver) {
        this(resolver, NativeIconVariant.Theme.LIGHT, DEFAULT_SCALE_PERCENT);
    }

    /**
     * Creates a connected owner from already sampled presentation state.
     *
     * <p>Null or unsupported values use an explicit light/100% presentation fallback only; this does
     * not attest host theme/DPI parity or expand the reviewed variant catalog.</p>
     */
    public RuntimeUiResourceService(
        final CubismNativeIconResolver resolver,
        final NativeIconVariant.Theme theme,
        final int scalePercent
    ) {
        this(
            Objects.requireNonNull(resolver, "resolver"),
            normalize(theme, scalePercent),
            null
        );
    }

    /**
     * Creates a connected owner by sampling host presentation state once off the EDT.
     * Subsequent queries use only resolver-backed cached state. Call {@link #refreshPresentation()}
     * off the EDT after a host theme/DPI change.
     */
    public RuntimeUiResourceService(
        final CubismNativeIconResolver resolver,
        final ThemeStatusAdapter themeStatus,
        final IntSupplier scalePercent
    ) {
        this(
            Objects.requireNonNull(resolver, "resolver"),
            readPresentation(themeStatus, scalePercent),
            () -> readPresentation(themeStatus, scalePercent)
        );
    }

    /**
     * Explicit connected factory for host composition. This method does not preload. The host-binding
     * owner must retain and close the returned owner when the binding is replaced or disposed;
     * closing it releases the resolver's cached resources.
     */
    public static RuntimeUiResourceService connected(
        final CubismNativeIconResolver resolver,
        final ThemeStatusAdapter themeStatus,
        final IntSupplier scalePercent
    ) {
        return new RuntimeUiResourceService(resolver, themeStatus, scalePercent);
    }

    /** Creates a connected owner from already sampled presentation state. */
    public static RuntimeUiResourceService connected(
        final CubismNativeIconResolver resolver,
        final NativeIconVariant.Theme theme,
        final int scalePercent
    ) {
        return new RuntimeUiResourceService(resolver, theme, scalePercent);
    }

    /**
     * Returns one stable, non-closeable SDK view for this owner. The same object is returned on every
     * call; it is intended to be composed into every plugin context for this host binding.
     */
    public UiResourceService sdkView() {
        return sdkView;
    }

    /** Returns the fail-closed owner used when no verified host-binding provider is installed. */
    public static RuntimeUiResourceService unavailable() {
        return UNAVAILABLE;
    }

    /**
     * Returns current cached availability for the enabled presentation. The underlying resolver's
     * lookup is constant-space and performs no filesystem, host or decoding IO.
     */
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
        final CubismNativeIconResolver activeResolver = resolver;
        if (closed || activeResolver == null) return Optional.empty();
        return activeResolver.resolve(selection(reference, disabled));
    }

    /**
     * Samples the retained host presentation source off the EDT and invalidates only presentation
     * selection caches. A disposed owner ignores the refresh and does not touch the source. A refresh
     * that already captured the source may finish, but it cannot update a closed owner.
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

    /** Closes this host-binding owner and releases the resolver/source references. */
    @Override
    public void close() {
        final CubismNativeIconResolver activeResolver;
        synchronized (this) {
            if (closed) return;
            closed = true;
            presentationSource = null;
            activeResolver = resolver;
            resolver = null;
            selections.clear();
        }
        if (activeResolver != null) activeResolver.close();
    }

    private UiIconAvailability availability(
        final UiIconRef reference,
        final boolean disabled
    ) {
        final CubismNativeIconResolver activeResolver = resolver;
        if (closed || activeResolver == null) return UiIconAvailability.SERVICE_UNAVAILABLE;
        return activeResolver.availability(selection(reference, disabled));
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
            // Presentation is optional; an adapter failure uses the explicit light fallback.
        }

        int sampledScale = DEFAULT_SCALE_PERCENT;
        try {
            sampledScale = scalePercent.getAsInt();
        } catch (RuntimeException ignored) {
            // An unavailable DPI source uses the explicit light/100% fallback only.
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

    private final class SdkView implements UiResourceService {
        @Override
        public UiIconAvailability availability(final UiIconRef reference) {
            return RuntimeUiResourceService.this.availability(reference);
        }
    }

    private record Presentation(NativeIconVariant.Theme theme, int scalePercent) { }

    private record LookupKey(UiIconRef reference, boolean disabled) { }
}
