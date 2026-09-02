package dev.turboism.ui.panel;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Shared verified-alias dock-tree traversal kernel over the Cubism workspace component tree.
 *
 * <p>This kernel is the single source of truth for the r5 type-safety semantics: the workspace
 * tree holds more component types than palette boxes and split containers (for example the
 * canvas contents box), and only {@code split.class} / {@code palette-box.class} attestations
 * may drive traversal. Unknown components are skipped, never expanded, so a verified selector
 * is never invoked on a host type it does not target.</p>
 *
 * <p>Runtime-internal shared kernel (not a public API): consumed by
 * {@link VerifiedEmbeddedPanelHostOperations} and the read-only workspace layout provider.</p>
 */
public final class DockTreeTraversal {

    public static final String PALETTE_BOX_CLASS = "cubism.ui-panel.palette-box.class";
    public static final String SPLIT_CLASS = "cubism.ui-panel.split.class";
    public static final String SPLIT_CONTENTS = "cubism.ui-panel.split.contents";
    public static final String SPLIT_REMOVE = "cubism.ui-panel.split.remove";
    public static final String PALETTE_BOX_PALETTES = "cubism.ui-panel.palette-box.palettes";
    public static final String COMPONENT_PALETTE_COUNT = "cubism.ui-panel.component.palette-count";

    private final VerifiedMemberResolver resolver;

    public DockTreeTraversal(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Exact CPMPaletteBox attestation. */
    public boolean isPaletteBox(final Object component) {
        return resolver.isInstance(PALETTE_BOX_CLASS, component);
    }

    /** Exact CPMSplitContainer attestation; other components are never expanded. */
    public boolean isSplitContainer(final Object component) {
        return resolver.isInstance(SPLIT_CLASS, component);
    }

    /** Ordered child components of a split container. */
    public List<?> splitContents(final Object splitContainer) {
        final Object rawContents = resolver.invoke(SPLIT_CONTENTS, splitContainer);
        if (rawContents instanceof List<?> contents) {
            return contents;
        }
        throw new IllegalStateException("Cubism split contents are not a list");
    }

    /**
     * The number of tabs currently docked in a palette box
     * ({@code CPMPaletteBox.getPalettes().size()}).
     */
    public int paletteTabCount(final Object paletteBox) {
        final Object rawPalettes = resolver.invoke(PALETTE_BOX_PALETTES, paletteBox);
        if (rawPalettes instanceof List<?> palettes) {
            return palettes.size();
        }
        throw new IllegalStateException("Cubism palette box palettes are not a list");
    }

    /** Returns whether the exact target identity is attached below the given dock component. */
    public boolean containsComponent(final Object component, final Object target) {
        if (component == target) {
            return true;
        }
        if (component == null || isPaletteBox(component) || !isSplitContainer(component)) {
            return false;
        }
        for (Object child : splitContents(component)) {
            if (containsComponent(child, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pre-order walk over the dock tree: the visitor receives every palette-box leaf in
     * traversal order; split containers are descended into and unknown components (for
     * example CPMContentsBox) are skipped entirely.
     */
    public void walkComponents(final Object component, final Consumer<Object> visitor) {
        Objects.requireNonNull(visitor, "visitor");
        if (component == null) {
            return;
        }
        if (isPaletteBox(component)) {
            visitor.accept(component);
            return;
        }
        if (!isSplitContainer(component)) {
            return;
        }
        for (Object child : splitContents(component)) {
            walkComponents(child, visitor);
        }
    }

    /**
     * Recursively removes empty palette boxes and split branches.
     * The root component itself is never removed; singleton branches stay intact because
     * no verified reparent operation exists.
     */
    public void pruneEmptyBoxes(final Object component) {
        if (component == null || isPaletteBox(component) || !isSplitContainer(component)) {
            return;
        }
        for (Object child : new ArrayList<>(splitContents(component))) {
            Objects.requireNonNull(child, "Cubism split child");
            if (isEmptyDockComponent(child)) {
                dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                    "floating-panels",
                    "Removing one empty dock component"
                );
                resolver.invoke(SPLIT_REMOVE, component, child);
            }
        }
    }

    private boolean isEmptyDockComponent(final Object component) {
        if (isPaletteBox(component)) {
            return (Integer) resolver.invoke(COMPONENT_PALETTE_COUNT, component) == 0;
        }
        if (!isSplitContainer(component)) {
            return false;
        }
        pruneEmptyBoxes(component);
        return splitContents(component).isEmpty();
    }
}
