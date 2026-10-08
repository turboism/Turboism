package dev.turboism.adapter.cubism.modeling;

import static dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.*;
import static dev.turboism.adapter.cubism.modeling.NativeModelingPointProjector.*;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** One release-time native selection-only transaction; object selections and foreign point types are retained. */
final class NativeModelingPointSelectionAdapter {
    private final VerifiedMemberResolver resolver;
    private final NativeModelingToolSessionResolver sessions;
    private final NativeModelingToolSessionResolver.Identity identity;
    private final BooleanSupplier leaseCurrent;

    NativeModelingPointSelectionAdapter(
            VerifiedMemberResolver resolver,
            NativeModelingToolSessionResolver sessions,
            NativeModelingToolSessionResolver.Identity identity,
            BooleanSupplier leaseCurrent) {
        this.resolver = resolver;
        this.sessions = sessions;
        this.identity = identity;
        this.leaseCurrent = leaseCurrent;
    }

    boolean current(Snapshot snapshot) {
        return leaseCurrent.getAsBoolean()
                && sessions.isCurrent(identity)
                && (snapshot == null || snapshot.sameTargets(capture(resolver, identity)));
    }

    void commit(Snapshot snapshot, List<Integer> ordinals, SelectionMode mode) {
        if (!current(snapshot)) throw new IllegalStateException("ordinary brush target snapshot is stale");
        if (ordinals.isEmpty()) return;
        final Map<Key, Candidate> requested = new LinkedHashMap<>();
        for (int ordinal : ordinals) {
            if (ordinal < 0 || ordinal >= snapshot.candidates().size()) {
                throw new IllegalArgumentException("brush ordinal is outside the candidate snapshot");
            }
            final Candidate point = snapshot.candidates().get(ordinal);
            requested.put(point.key(), point);
        }
        final Map<Key, Selected> before = read(snapshot);
        final Set<Key> expected = expected(before.keySet(), requested.keySet(), mode);
        if (expected.equals(before.keySet())) return;
        if (Boolean.TRUE.equals(resolver.invoke(MODE_EDITING, identity.mode()))) {
            throw new IllegalStateException("another native edit is already in progress");
        }
        // A selection-only GroupUndo follows native ordinary selection history. No model edit,
        // parameter, geometric undo payload or plugin edit session/UI lock is opened.
        final Object undo = resolver.invoke(BEGIN_EDIT, identity.mode(), "Selection Brush");
        boolean editing = true;
        try {
            resolver.invoke(SELECTION_UNDO, undo);
            if (!current(snapshot)) {
                throw new IllegalStateException("ordinary brush target changed while beginning selection");
            }
            for (Map.Entry<Key, Selected> entry : before.entrySet()) {
                requireLease();
                if (!expected.contains(entry.getKey())) {
                    if (!Boolean.TRUE.equals(
                            resolver.invoke(POINT_REMOVE, entry.getValue().selector, entry.getValue().ref))) {
                        throw new IllegalStateException("native point selector rejected removal");
                    }
                }
            }
            for (Key key : expected) {
                requireLease();
                if (before.containsKey(key)) continue;
                final Candidate candidate = requested.get(key);
                final Object pointSelector = selectorFor(snapshot, key.owner);
                if (!Boolean.TRUE.equals(
                        resolver.invoke(POINT_ADD_WEIGHTED, pointSelector, candidate.ref(), 1.0f, true))) {
                    throw new IllegalStateException("native point selector rejected addition");
                }
            }
            if (!leaseCurrent.getAsBoolean()
                    || !sessions.isCurrent(identity)
                    || !read(snapshot).keySet().equals(expected)) {
                throw new IllegalStateException("native point selection readback did not match the requested result");
            }
            refresh();
            if (!leaseCurrent.getAsBoolean()
                    || !sessions.isCurrent(identity)
                    || !read(snapshot).keySet().equals(expected)) {
                throw new IllegalStateException("native point selection changed during refresh");
            }
            // Native endEdit's first argument is isCancel, not isCommit.
            resolver.invoke(END_EDIT, identity.mode(), false, null);
            editing = false;
        } catch (RuntimeException | Error failure) {
            // Never restore old point refs into a replacement document/mode/model.
            if (editing && sessions.isCurrent(identity)) {
                try {
                    restore(snapshot, before);
                    resolver.invoke(END_EDIT, identity.mode(), true, null);
                    refresh();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private Map<Key, Selected> read(Snapshot snapshot) {
        final Map<Key, Selected> selected = new LinkedHashMap<>();
        for (Owner owner : snapshot.owners()) {
            for (Object ref : list(resolver.invoke(POINTS, owner.pointSelector()))) {
                final Key key = key(resolver, ref);
                if (key == null) continue;
                if (key.owner != owner.source())
                    throw new IllegalStateException("native selected point has foreign ownership");
                final float weight =
                        ((Number) resolver.invoke(POINT_WEIGHT, owner.pointSelector(), ref, 1.0f)).floatValue();
                if (!Float.isFinite(weight))
                    throw new IllegalStateException("native selected point weight is not finite");
                selected.put(key, new Selected(owner.pointSelector(), ref, weight));
            }
        }
        return selected;
    }

    private void restore(Snapshot snapshot, Map<Key, Selected> before) {
        for (Selected point : new ArrayList<>(read(snapshot).values())) {
            resolver.invoke(POINT_REMOVE, point.selector, point.ref);
        }
        for (Selected point : before.values()) {
            resolver.invoke(POINT_ADD_WEIGHTED, point.selector, point.ref, point.weight, true);
        }
    }

    private void refresh() {
        // Native rectangle/lasso release invalidates this separate UI cache. Publishing
        // object GUIDs alone leaves its old corners intact; setDirty does not dirty the model.
        final Object boundingBox = resolver.invoke(SELECTOR_BOUNDING_BOX, identity.selector());
        resolver.invoke(BOUNDING_BOX_DIRTY, boundingBox);
        final Object manager = resolver.invoke(UPDATE_MANAGER, identity.pack());
        resolver.invoke(SELECTION_REFRESH, manager, identity.component(), true, false, false);
        identity.component().repaint();
    }

    private void requireLease() {
        if (!leaseCurrent.getAsBoolean() || !sessions.isCurrent(identity)) {
            throw new IllegalStateException("ordinary brush activation ended during selection");
        }
    }

    static Set<Key> expected(Set<Key> before, Set<Key> hits, SelectionMode mode) {
        final Set<Key> result = new HashSet<>(before);
        switch (mode) {
            case REPLACE -> {
                result.clear();
                result.addAll(hits);
            }
            case ADD -> result.addAll(hits);
            case REMOVE -> result.removeAll(hits);
            case TOGGLE -> {
                for (Key key : hits) if (!result.remove(key)) result.add(key);
            }
        }
        return Set.copyOf(result);
    }

    private static Object selectorFor(Snapshot snapshot, Object source) {
        return snapshot.owners().stream()
                .filter(owner -> owner.source() == source)
                .findFirst()
                .orElseThrow()
                .pointSelector();
    }

    private record Selected(Object selector, Object ref, float weight) {}
}
