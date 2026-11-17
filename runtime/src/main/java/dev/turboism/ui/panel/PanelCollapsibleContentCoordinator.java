package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Content-table coordinator (option B). It keeps one injected-section table per target panel,
 * merges the injected sections into the existing content at render time, and records the
 * contributing pluginId of every injected section so a button action can route back.
 *
 * <p>A and B are two entries into the same content table: A holds the sections a plugin declares
 * in {@code EmbeddedPanelContribution.content}, which the renderer already supports, and B holds
 * the sections injected at run time through {@code contributeCollapsibleSection}. Merge rule: an
 * A section always keeps its declared position in the content, the B sections follow, and the B
 * sections sort by ascending {@code order} and then by {@code sectionId} in dictionary order,
 * independently of plugin load order.</p>
 *
 * <p>A {@link PanelView.Button} inside an injected section must route to the action registry of
 * the contributing pluginId, never to the registry of the target core panel.
 * {@link #actionOwners(EmbeddedPanelId)} walks the injected section content recursively and
 * collects {@code actionId → pluginId}. A button actionId is globally unique because ActionRegistry
 * registers by actionId, and a later registration overrides an earlier one on conflict; the map
 * keeps insertion order, and a conflict happens only when the convention is broken.</p>
 *
 * <p>The host installation lifecycle drives the registration state of a target panel explicitly,
 * because no ready-made event API exists: the host calls
 * {@link #onPanelRegistered(EmbeddedPanelId)} after a successful {@code install}, and it calls
 * {@link #onPanelRemoved(EmbeddedPanelId)} when the panel handle closes. An injected section stays
 * pending while its target is unregistered but remains in the content table, takes its place
 * automatically after registration, and returns to pending after removal without loss or
 * duplication.</p>
 *
 * <p>Thread model: a synchronized block guards the internal state. The EDT usage is a convention,
 * so the caller schedules the EDT work.</p>
 */
public final class PanelCollapsibleContentCoordinator {

    /**
     * Process-wide shared instance. The per-plugin runtime service and the host install rendering
     * share no other wiring point, so the injection table must stay visible across plugins.
     */
    public static PanelCollapsibleContentCoordinator shared() {
        return SHARED;
    }

    private static final PanelCollapsibleContentCoordinator SHARED = new PanelCollapsibleContentCoordinator();

    public static final String CLIPPING_MASK_SHARED_SECTION_ID = "clipping-mask.section";
    private static final Set<String> SHARED_SECTION_IDS = Set.of(CLIPPING_MASK_SHARED_SECTION_ID);

    /** Registration ownership of one injected entry: the contributing pluginId and the contribution. */
    private record OwnerSection(String pluginId, CollapsibleSectionContribution contribution) {}

    private record ContentListener(EmbeddedPanelId panelId, Runnable callback) {}

    private final List<ContentListener> listeners = new CopyOnWriteArrayList<>();

    /** Observes section changes for one installed panel; callbacks run outside the content-table lock. */
    public Registration onContentChanged(final EmbeddedPanelId panelId, final Runnable callback) {
        final ContentListener listener = new ContentListener(
                Objects.requireNonNull(panelId, "panelId"), Objects.requireNonNull(callback, "callback"));
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void contentChanged(final EmbeddedPanelId panelId) {
        for (ContentListener listener : listeners) {
            if (listener.panelId().equals(panelId)) listener.callback().run();
        }
    }

    private final Object monitor = new Object();
    private final Set<EmbeddedPanelId> registeredPanels = new HashSet<>();
    /** Content table: targetPanelId → sectionId → injected contributions. Several plugins may inject into one shared sectionId, and the render merges them into one collapsible section. */
    private final Map<EmbeddedPanelId, Map<String, List<OwnerSection>>> sections = new HashMap<>();

    private static boolean isSharedSection(final String sectionId) {
        return SHARED_SECTION_IDS.contains(sectionId);
    }

    /**
     * Registers one injected section in the content table, the B entry. The entry stays pending
     * while the target panel is unregistered and takes its place after the host installs it.
     *
     * @param pluginId the contributing plugin id, which routes an injected button action back to
     *     the contributor registry
     * @param contribution the injected contribution, which carries the target panel, the sectionId,
     *     the order, and the content
     * @return an unregister handle. It is idempotent and removes only its own entry, so a sectionId
     *     overridden by another contributor is never deleted by mistake
     * @throws NullPointerException when any argument is {@code null}
     * @throws IllegalStateException when an ordinary sectionId is registered twice, or when one
     *     plugin registers twice on a shared sectionId
     */
    public Registration register(final String pluginId, final CollapsibleSectionContribution contribution) {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(contribution, "contribution");
        final EmbeddedPanelId target = contribution.targetPanelId();
        final String sectionId = contribution.sectionId();
        final OwnerSection owner = new OwnerSection(pluginId, contribution);
        synchronized (monitor) {
            final Map<String, List<OwnerSection>> panelSections =
                    sections.computeIfAbsent(target, ignored -> new LinkedHashMap<>());
            final List<OwnerSection> list = panelSections.computeIfAbsent(sectionId, ignored -> new ArrayList<>());
            if (!isSharedSection(sectionId)) {
                if (!list.isEmpty()) {
                    throw new IllegalStateException("collapsible section " + sectionId
                            + " is already registered for panel \"" + target.value() + "\"");
                }
            } else {
                for (OwnerSection existing : list) {
                    if (existing.pluginId().equals(pluginId)) {
                        throw new IllegalStateException("collapsible section " + sectionId
                                + " is already registered for panel \"" + target.value() + "\" by plugin " + pluginId);
                    }
                }
            }
            list.add(owner);
        }
        contentChanged(target);
        return () -> unregister(target, sectionId, owner);
    }

    /**
     * The host installed the target panel. The call is idempotent, affects only the pending and
     * placed states, and leaves the content table unchanged.
     */
    public void onPanelRegistered(final EmbeddedPanelId panelId) {
        Objects.requireNonNull(panelId, "panelId");
        synchronized (monitor) {
            registeredPanels.add(panelId);
        }
    }

    /**
     * The host removed the target panel. The injected sections return to pending and the content
     * table is kept, so nothing is lost or duplicated.
     */
    public void onPanelRemoved(final EmbeddedPanelId panelId) {
        Objects.requireNonNull(panelId, "panelId");
        synchronized (monitor) {
            registeredPanels.remove(panelId);
        }
    }

    /** Reports whether the target panel is registered now. Pending means unregistered but already in the content table. */
    public boolean isRegistered(final EmbeddedPanelId panelId) {
        Objects.requireNonNull(panelId, "panelId");
        synchronized (monitor) {
            return registeredPanels.contains(panelId);
        }
    }

    /**
     * Returns the injected section views sorted by ascending order and then by sectionId in
     * dictionary order. Every injected section renders as a {@link PanelView.CollapsibleSection}
     * node holding the title, the expandedByDefault flag, and the content. When several plugins
     * inject into the same sectionId, the views merge into one CollapsibleSection.
     */
    public List<PanelView> injectedSections(final EmbeddedPanelId panelId) {
        Objects.requireNonNull(panelId, "panelId");
        final List<List<OwnerSection>> snapshot;
        synchronized (monitor) {
            final Map<String, List<OwnerSection>> panelSections = sections.get(panelId);
            if (panelSections == null) {
                return List.of();
            }
            snapshot = panelSections.values().stream()
                    .filter(list -> !list.isEmpty())
                    .map(List::copyOf)
                    .toList();
        }
        final List<List<OwnerSection>> sortedSections = new ArrayList<>(snapshot);
        sortedSections.sort(Comparator.comparingInt((List<OwnerSection> list) -> list.stream()
                        .mapToInt(o -> o.contribution().order())
                        .min()
                        .orElse(0))
                .thenComparing(list -> list.get(0).contribution().sectionId()));
        return sortedSections.stream()
                .map(list -> {
                    if (list.size() == 1) {
                        final CollapsibleSectionContribution c = list.get(0).contribution();
                        return (PanelView) PanelView.collapsibleSection(c.title(), c.expandedByDefault(), c.content());
                    }
                    final List<OwnerSection> sorted = new ArrayList<>(list);
                    sorted.sort(Comparator.comparingInt(
                                    (OwnerSection o) -> o.contribution().order())
                            .thenComparing(OwnerSection::pluginId));
                    final List<PanelView> children = new ArrayList<>();
                    for (OwnerSection owner : sorted) {
                        final PanelView content = owner.contribution().content();
                        if (content instanceof PanelView.Column column) {
                            children.addAll(column.children());
                        } else {
                            children.add(content);
                        }
                    }
                    final CollapsibleSectionContribution primary = sorted.get(0).contribution();
                    final boolean anyExpanded =
                            sorted.stream().anyMatch(o -> o.contribution().expandedByDefault());
                    return (PanelView)
                            PanelView.collapsibleSection(primary.title(), anyExpanded, new PanelView.Column(children));
                })
                .toList();
    }

    /**
     * Merges for rendering. A Column content gains the injected sections at its tail, any other
     * content is wrapped in a column(content, injected sections...), and content without an
     * injection returns unchanged.
     */
    public PanelView merge(final EmbeddedPanelId panelId, final PanelView content) {
        Objects.requireNonNull(panelId, "panelId");
        Objects.requireNonNull(content, "content");
        final List<PanelView> injected = injectedSections(panelId);
        if (injected.isEmpty()) {
            return content;
        }
        final List<PanelView> children = new ArrayList<>(injected.size() + 2);
        if (content instanceof PanelView.Column column) {
            children.addAll(column.children());
        } else {
            children.add(content);
        }
        children.addAll(injected);
        return new PanelView.Column(children);
    }

    /**
     * Returns the actionId → contributing pluginId map of every button inside the injected sections
     * of one panel. The walk reads the Column, Row, and CollapsibleSection content tree recursively
     * and collects every {@link PanelView.Button}. A panel without an injected button returns an
     * empty map.
     */
    public Map<String, String> actionOwners(final EmbeddedPanelId panelId) {
        Objects.requireNonNull(panelId, "panelId");
        final List<List<OwnerSection>> snapshot;
        synchronized (monitor) {
            final Map<String, List<OwnerSection>> panelSections = sections.get(panelId);
            if (panelSections == null) {
                return Map.of();
            }
            snapshot = panelSections.values().stream()
                    .filter(list -> !list.isEmpty())
                    .map(List::copyOf)
                    .toList();
        }
        final Map<String, String> owners = new HashMap<>();
        for (List<OwnerSection> list : snapshot) {
            for (OwnerSection owner : list) {
                collectButtonOwners(owner.contribution().content(), owner.pluginId(), owners);
            }
        }
        return owners;
    }

    private static void collectButtonOwners(
            final PanelView view, final String pluginId, final Map<String, String> owners) {
        if (view instanceof PanelView.Button button) {
            owners.put(button.actionId(), pluginId);
        } else if (view instanceof PanelView.Column column) {
            for (PanelView child : column.children()) {
                collectButtonOwners(child, pluginId, owners);
            }
        } else if (view instanceof PanelView.Row row) {
            for (PanelView child : row.children()) {
                collectButtonOwners(child, pluginId, owners);
            }
        } else if (view instanceof PanelView.CollapsibleSection section) {
            for (PanelView child : section.children()) {
                collectButtonOwners(child, pluginId, owners);
            }
        }
    }

    // Ownership removal is identity-scoped: only the registering contribution may unregister.
    @SuppressWarnings("ReferenceEquality")
    private void unregister(final EmbeddedPanelId target, final String sectionId, final OwnerSection owner) {
        synchronized (monitor) {
            final Map<String, List<OwnerSection>> panelSections = sections.get(target);
            if (panelSections == null) {
                return;
            }
            final List<OwnerSection> list = panelSections.get(sectionId);
            if (list == null) {
                return;
            }
            if (!list.removeIf(existing -> existing == owner)) return;
            if (list.isEmpty()) {
                panelSections.remove(sectionId);
                if (panelSections.isEmpty()) {
                    sections.remove(target);
                }
            }
        }
        contentChanged(target);
    }
}
