package dev.turboism.adapter.cubism.modeling;

import static dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.*;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Uses the native lasso's editable objects and IPointRefEx canvas coordinates, for both point families. */
final class NativeModelingPointProjector {
    private NativeModelingPointProjector() {}

    static Snapshot capture(VerifiedMemberResolver resolver, NativeModelingToolSessionResolver.Identity identity) {
        final List<?> sources = list(resolver.invoke(SELECTED_OBJECTS, identity.selector()));
        final List<Owner> owners = new ArrayList<>();
        final List<Candidate> candidates = new ArrayList<>();
        final HashSet<Key> unique = new HashSet<>();
        for (Object source : sources) {
            if (!Boolean.TRUE.equals(resolver.invoke(SOURCE_EDITABLE, source))) continue;
            final Object id = resolver.invoke(SOURCE_ID, source);
            final Object object = resolver.invoke(MODEL_OBJECT, identity.model(), id);
            if (object == null
                    || resolver.invoke(OBJECT_SOURCE, object) != source
                    || !Boolean.TRUE.equals(resolver.invoke(VIEW_EDITABLE, identity.view(), object))) continue;
            final List<?> refs = list(resolver.invoke(OBJECT_POINTS, object));
            final Object selection = resolver.invoke(SOURCE_SELECTION, source);
            final Object pointSelector = resolver.invoke(SELECTION_POINTS, selection);
            final int start = candidates.size();
            for (Object ref : refs) {
                final Key key = key(resolver, ref);
                if (key == null) continue;
                if (key.owner != source || !resolver.isInstance(POINT_EX_CLASS, ref) || !unique.add(key)) {
                    throw new IllegalStateException("native modeling point has mismatched or duplicate ownership");
                }
                // getAllPointRefEx() uses the current calculated form, including parent/parameter
                // deformation. e() is the exact canvas getter used by native ordinary lasso.
                final Object canvas = resolver.invoke(POINT_CANVAS, ref);
                final Point2 canvasPosition = vector(resolver, canvas);
                final Point2 componentPosition = vector(resolver, resolver.invoke(PROJECT, identity.camera(), canvas));
                final long topologyIdentity =
                        key.kind == Kind.MESH ? ((Number) resolver.invoke(MESH_POINT_UID, ref)).longValue() : key.index;
                candidates.add(new Candidate(key, ref, canvasPosition, componentPosition, topologyIdentity));
            }
            if (candidates.size() > start) owners.add(new Owner(source, object, pointSelector));
        }
        return new Snapshot(List.copyOf(sources), List.copyOf(owners), List.copyOf(candidates));
    }

    static Key key(VerifiedMemberResolver resolver, Object ref) {
        final Kind kind;
        final String indexAlias;
        if (resolver.isInstance(MESH_POINT_CLASS, ref)) {
            kind = Kind.MESH;
            indexAlias = MESH_POINT_INDEX;
        } else if (resolver.isInstance(WARP_POINT_CLASS, ref)) {
            kind = Kind.WARP;
            indexAlias = WARP_POINT_INDEX;
        } else return null;
        final Object source = resolver.invoke(POINT_SOURCE, ref);
        final Object index = resolver.invoke(indexAlias, ref);
        if (source == null || !(index instanceof Number number) || number.intValue() < 0) {
            throw new IllegalStateException("native modeling point identity is unavailable");
        }
        return new Key(source, kind, number.intValue());
    }

    static Point2 vector(VerifiedMemberResolver resolver, Object value) {
        final Object x = resolver.invoke(VECTOR_X, value);
        final Object y = resolver.invoke(VECTOR_Y, value);
        if (!(x instanceof Number nx)
                || !(y instanceof Number ny)
                || !Float.isFinite(nx.floatValue())
                || !Float.isFinite(ny.floatValue())) {
            throw new IllegalStateException("native modeling point projection is not finite");
        }
        return new Point2(nx.floatValue(), ny.floatValue());
    }

    static List<?> list(Object value) {
        if (!(value instanceof List<?> list))
            throw new IllegalStateException("native modeling point list is unavailable");
        return List.copyOf(list);
    }

    enum Kind {
        MESH,
        WARP
    }

    static final class Key {
        final Object owner;
        final Kind kind;
        final int index;

        Key(Object owner, Kind kind, int index) {
            this.owner = owner;
            this.kind = kind;
            this.index = index;
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof Key other && owner == other.owner && kind == other.kind && index == other.index;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * System.identityHashCode(owner) + kind.hashCode()) + index;
        }
    }

    record Owner(Object source, Object object, Object pointSelector) {}

    record Candidate(Key key, Object ref, Point2 canvas, Point2 component, long topologyIdentity) {}

    record Snapshot(List<?> selectedSources, List<Owner> owners, List<Candidate> candidates) {
        List<Point2> positions() {
            return candidates.stream().map(Candidate::component).toList();
        }

        boolean sameTargets(Snapshot other) {
            if (other == null
                    || selectedSources.size() != other.selectedSources.size()
                    || owners.size() != other.owners.size()
                    || candidates.size() != other.candidates.size()) return false;
            for (int i = 0; i < selectedSources.size(); i++) {
                if (selectedSources.get(i) != other.selectedSources.get(i)) return false;
            }
            for (int i = 0; i < owners.size(); i++) {
                final Owner a = owners.get(i), b = other.owners.get(i);
                if (a.source != b.source || a.object != b.object || a.pointSelector != b.pointSelector) return false;
            }
            for (int i = 0; i < candidates.size(); i++) {
                final Candidate a = candidates.get(i), b = other.candidates.get(i);
                if (!a.key.equals(b.key)
                        || a.topologyIdentity != b.topologyIdentity
                        || !a.canvas.equals(b.canvas)
                        || !a.component.equals(b.component)) return false;
            }
            return true;
        }
    }
}
