package dev.turboism.ui.appearance.control;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.appearance.PaletteEntryState;
import dev.turboism.sdk.ui.appearance.UiColor;
import java.awt.Component;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;

/** Runtime owner of generation-bound, transient model-palette overrides. */
public final class PaletteAppearanceCoordinator implements AutoCloseable {

    enum Palette {
        PART,
        DEFORMER_PART,
        DEFORMER,
        PARAMETER,
        PARAMETER_GROUP
    }

    enum Property {
        FONT_SIZE,
        BOLD,
        ITALIC,
        TEXT_COLOR,
        BACKGROUND_COLOR
    }

    /**
     * The full generation-bound identity an override is registered against: which content, which
     * model, and which host and provider incarnations were live at the time.
     *
     * <p>Every generation is compared exactly, so an override never survives into a later incarnation
     * of the host, model, provider or content — that is what makes the overrides transient by
     * construction rather than by cleanup.
     *
     * @param contentId the content object owning the registration; must not be {@code null} or blank
     * @param contentGeneration that content's incarnation counter; must be positive
     * @param modelId the model the override applies to; must not be {@code null} or blank
     * @param modelGeneration that model's incarnation counter; must be positive
     * @param hostGeneration the host UI incarnation counter; must be positive
     * @param providerGeneration the appearance provider incarnation counter; must be positive
     * @throws IllegalArgumentException if a string is blank or a generation is not positive
     * @throws NullPointerException if a string component is {@code null}
     */
    public record Scope(
        String contentId,
        long contentGeneration,
        String modelId,
        long modelGeneration,
        long hostGeneration,
        long providerGeneration
    ) {
        public Scope {
            contentId = requireText(contentId, "contentId");
            modelId = requireText(modelId, "modelId");
            requireGeneration(contentGeneration, "contentGeneration");
            requireGeneration(modelGeneration, "modelGeneration");
            requireGeneration(hostGeneration, "hostGeneration");
            requireGeneration(providerGeneration, "providerGeneration");
        }
    }

    private final Object monitor = new Object();
    private final Map<Key, Stored> overrides = new HashMap<>();
    private final List<StoredParameterControlBinding> parameterControls = new ArrayList<>();
    private Scope currentScope;
    private long hostGeneration;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private long sequence;
    private boolean closed;

    /**
     * Reconciles the active scope. Scope switches only change which scope is readable;
     * registrations remain owned by their original content until their registration, plugin,
     * or explicit invalidation closes them.
     */
    void reconcile(final Scope scope) {
        Objects.requireNonNull(scope, "scope");
        boolean changed = false;
        synchronized (monitor) {
            requireOpen();
            if (!scope.equals(currentScope)) {
                currentScope = scope;
                changed = true;
            }
        }
        if (changed) notifyChange();
    }

    AutoCloseable onChange(final Runnable listener) {
        final Runnable value = Objects.requireNonNull(listener, "listener");
        synchronized (monitor) {
            requireOpen();
            listeners.add(value);
        }
        return () -> listeners.remove(value);
    }

    /**
     * Adopts a new host UI generation, discarding the parameter-control bindings that belonged to the
     * previous one.
     *
     * <p>Stored overrides are not cleared — they simply stop resolving, because resolution compares
     * the host generation exactly. Listeners are notified only when the generation actually changes,
     * and notification happens outside the lock.
     *
     * @param generation the new host generation; must be positive
     * @throws IllegalArgumentException if {@code generation} is not positive
     * @throws IllegalStateException if this coordinator has been closed
     */
    public void replaceHostGeneration(final long generation) {
        requireGeneration(generation, "generation");
        boolean changed;
        synchronized (monitor) {
            requireOpen();
            changed = hostGeneration != generation;
            if (changed) parameterControls.clear();
            hostGeneration = generation;
        }
        if (changed) notifyChange();
    }

    /**
     * @return the host UI generation overrides currently resolve against; {@code 0} before any
     *     generation was adopted and after {@link #invalidate()}, a value no override can match
     */
    public long hostGeneration() {
        synchronized (monitor) {
            return hostGeneration;
        }
    }

    void bindParameterControl(
        final boolean folder,
        final String id,
        final Component label
    ) {
        final String value = Objects.requireNonNull(id, "id");
        final Component component = Objects.requireNonNull(label, "label");
        synchronized (monitor) {
            if (closed) return;
            parameterControls.removeIf(binding -> binding.label().get() == null
                || binding.label().get() == component);
            parameterControls.add(new StoredParameterControlBinding(folder, value, new WeakReference<>(component)));
        }
    }

    void unbindParameterControl(final Component label) {
        synchronized (monitor) {
            if (closed) return;
            parameterControls.removeIf(binding -> binding.label().get() == null
                || binding.label().get() == label);
        }
    }

    /** Exact native parameter/folder IDs paired with their live Swing labels. */
    public List<ParameterControlBinding> parameterControlBindings() {
        synchronized (monitor) {
            if (closed) return List.of();
            final List<ParameterControlBinding> live = new ArrayList<>(parameterControls.size());
            final java.util.Iterator<StoredParameterControlBinding> iterator = parameterControls.iterator();
            while (iterator.hasNext()) {
                final StoredParameterControlBinding binding = iterator.next();
                final Component label = binding.label().get();
                if (label == null) {
                    iterator.remove();
                } else {
                    live.add(new ParameterControlBinding(binding.folder(), binding.id(), label));
                }
            }
            return List.copyOf(live);
        }
    }

    /**
     * Drops everything transient — the host generation, every stored override, every parameter-control
     * binding, and the active scope — and notifies listeners so styled components are restored.
     *
     * <p>Used when the host UI can no longer be trusted to correspond to what was registered. Unlike
     * {@code close()}, the coordinator stays usable afterwards. Silently does nothing once closed, and
     * notifies only if something was actually discarded.
     */
    public void invalidate() {
        boolean changed;
        synchronized (monitor) {
            if (closed) return;
            changed = hostGeneration != 0 || currentScope != null || !overrides.isEmpty()
                || !parameterControls.isEmpty();
            hostGeneration = 0;
            overrides.clear();
            parameterControls.clear();
            currentScope = null;
        }
        if (changed) notifyChange();
    }

    /** Hides the current scope without discarding content-owned registrations. */
    void deactivate() {
        boolean changed;
        synchronized (monitor) {
            if (closed) return;
            changed = currentScope != null;
            currentScope = null;
        }
        if (changed) notifyChange();
    }

    /** Removes every transient override owned by one successfully closed content object. */
    public void removeContent(final String contentId) {
        final String id = requireText(contentId, "contentId");
        boolean changed;
        synchronized (monitor) {
            if (closed) return;
            changed = overrides.keySet().removeIf(key -> key.contentId().equals(id));
        }
        if (changed) notifyChange();
    }

    Registration register(
        final String pluginId,
        final long pluginGeneration,
        final Scope scope,
        final Palette palette,
        final String objectId,
        final Property property,
        final Object value
    ) {
        final String owner = requireText(pluginId, "pluginId");
        requireGeneration(pluginGeneration, "pluginGeneration");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(palette, "palette");
        final String id = requireText(objectId, "objectId");
        Objects.requireNonNull(property, "property");
        final Object checkedValue = checkValue(property, value);
        final Key key = new Key(
            owner,
            pluginGeneration,
            scope.contentId(),
            scope.modelId(),
            palette,
            id,
            property
        );
        final Object token = new Object();
        synchronized (monitor) {
            requireOpen();
            requireCurrent(scope);
            overrides.put(key, new Stored(token, checkedValue, ++sequence));
        }
        notifyChange();
        final AtomicBoolean closedRegistration = new AtomicBoolean();
        return () -> {
            if (closedRegistration.compareAndSet(false, true)) {
                remove(key, token);
            }
        };
    }

    PaletteEntryState resolve(
        final Scope scope,
        final Palette palette,
        final String objectId
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(palette, "palette");
        final String id = requireText(objectId, "objectId");
        synchronized (monitor) {
            requireOpen();
            requireCurrent(scope);
            return new PaletteEntryState(
                floatValue(scope, palette, id, Property.FONT_SIZE),
                booleanValue(scope, palette, id, Property.BOLD),
                booleanValue(scope, palette, id, Property.ITALIC),
                colorValue(scope, palette, id, Property.TEXT_COLOR),
                colorValue(scope, palette, id, Property.BACKGROUND_COLOR)
            );
        }
    }
    /** Resolves the current host scope without exposing a stale-scope exception to renderers. */
    Optional<PaletteEntryState> resolveCurrent(
        final long hostGeneration,
        final Palette palette,
        final String objectId
    ) {
        Objects.requireNonNull(palette, "palette");
        final String id = requireText(objectId, "objectId");
        synchronized (monitor) {
            if (closed || currentScope == null || currentScope.hostGeneration() != hostGeneration) {
                return Optional.empty();
            }
            final PaletteEntryState state = resolve(currentScope, palette, id);
            return state.equals(PaletteEntryState.empty()) ? Optional.empty() : Optional.of(state);
        }
    }

    void removePlugin(final String pluginId, final long pluginGeneration) {
        final String owner = requireText(pluginId, "pluginId");
        requireGeneration(pluginGeneration, "pluginGeneration");
        boolean changed;
        synchronized (monitor) {
            if (closed) return;
            changed = overrides.keySet().removeIf(key -> key.pluginId().equals(owner)
                && key.pluginGeneration() == pluginGeneration);
        }
        if (changed) notifyChange();
    }

    Scope currentScope() {
        synchronized (monitor) {
            return currentScope;
        }
    }

    int size() {
        synchronized (monitor) {
            return overrides.size();
        }
    }

    @Override
    public void close() {
        boolean changed;
        synchronized (monitor) {
            if (closed) return;
            changed = hostGeneration != 0 || currentScope != null || !overrides.isEmpty()
                || !parameterControls.isEmpty();
            closed = true;
            hostGeneration = 0;
            overrides.clear();
            parameterControls.clear();
            currentScope = null;
        }
        if (changed) notifyChange();
        listeners.clear();
    }

    private void remove(final Key key, final Object token) {
        boolean changed = false;
        synchronized (monitor) {
            final Stored stored = overrides.get(key);
            if (stored != null && stored.token() == token) {
                overrides.remove(key);
                changed = true;
            }
        }
        if (changed) notifyChange();
    }

    private void notifyChange() {
        listeners.forEach(Runnable::run);
    }

    private Optional<Float> floatValue(
        final Scope scope,
        final Palette palette,
        final String objectId,
        final Property property
    ) {
        final Object value = latest(scope, palette, objectId, property);
        return value instanceof Float floatValue ? Optional.of(floatValue) : Optional.empty();
    }

    private Optional<Boolean> booleanValue(
        final Scope scope,
        final Palette palette,
        final String objectId,
        final Property property
    ) {
        final Object value = latest(scope, palette, objectId, property);
        return value instanceof Boolean booleanValue ? Optional.of(booleanValue) : Optional.empty();
    }

    private Optional<UiColor> colorValue(
        final Scope scope,
        final Palette palette,
        final String objectId,
        final Property property
    ) {
        final Object value = latest(scope, palette, objectId, property);
        return value instanceof UiColor color ? Optional.of(color) : Optional.empty();
    }

    private Object latest(
        final Scope scope,
        final Palette palette,
        final String objectId,
        final Property property
    ) {
        Stored latest = null;
        for (Map.Entry<Key, Stored> entry : overrides.entrySet()) {
            final Key key = entry.getKey();
            if (key.sameSlot(scope, palette, objectId, property)
                && (latest == null || entry.getValue().sequence() > latest.sequence())) {
                latest = entry.getValue();
            }
        }
        return latest == null ? null : latest.value();
    }

    private void requireCurrent(final Scope scope) {
        if (!scope.equals(currentScope)) {
            throw new IllegalStateException("Model appearance scope is stale.");
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Model appearance coordinator is closed.");
    }

    private static Object checkValue(final Property property, final Object value) {
        Objects.requireNonNull(value, "value");
        return switch (property) {
            case FONT_SIZE -> {
                if (!(value instanceof Float size)
                    || !Float.isFinite(size)
                    || size < 6.0F
                    || size > 96.0F) {
                    throw new IllegalArgumentException("font size must be between 6 and 96 points");
                }
                yield size;
            }
            case BOLD, ITALIC -> {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException("style flag must be Boolean");
                yield value;
            }
            case TEXT_COLOR, BACKGROUND_COLOR -> {
                if (!(value instanceof UiColor)) throw new IllegalArgumentException("color must be UiColor");
                yield value;
            }
        };
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static void requireGeneration(final long value, final String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
    }

    private record Key(
        String pluginId,
        long pluginGeneration,
        String contentId,
        String modelId,
        Palette palette,
        String objectId,
        Property property
    ) {
        boolean sameSlot(
            final Scope scope,
            final Palette expectedPalette,
            final String expectedObjectId,
            final Property expectedProperty
        ) {
            return contentId.equals(scope.contentId())
                && modelId.equals(scope.modelId())
                && palette == expectedPalette
                && objectId.equals(expectedObjectId)
                && property == expectedProperty;
        }
    }

    private record Stored(Object token, Object value, long sequence) { }

    /**
     * One live pairing of a host parameter or folder id with the Swing label currently displaying it.
     *
     * <p>Handed out as a strong reference by {@code parameterControlBindings()}, which only reports
     * bindings whose label is still reachable — internally the coordinator holds labels weakly, so a
     * binding never keeps a discarded host widget alive.
     *
     * @param folder {@code true} when the id names a parameter folder rather than a single parameter
     * @param id the host parameter or folder id; must not be {@code null}
     * @param label the live Swing label rendering that id; must not be {@code null}
     * @throws NullPointerException if {@code id} or {@code label} is {@code null}
     */
    public record ParameterControlBinding(boolean folder, String id, Component label) {
        public ParameterControlBinding {
            id = Objects.requireNonNull(id, "id");
            label = Objects.requireNonNull(label, "label");
        }
    }

    private record StoredParameterControlBinding(
        boolean folder,
        String id,
        WeakReference<Component> label
    ) {
    }
}
