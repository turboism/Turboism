package dev.turboism.validation.modelimport3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable plain-data fixture model for the T006 offline probe. Nothing here
 * references Cubism host classes; every record is synthetic evidence only.
 * Float key equality is bit-exact via {@link Float#floatToIntBits}: +0.0 and
 * -0.0 are distinct keys; non-finite inputs are rejected before key lookup. This
 * probe convention is NOT claimed to reproduce every native EQUAL detail.
 */
final class Model {

    private Model() {}

    /** Bit-exact float key comparison (frozen offline rule; signed zero distinct). */
    static boolean keyEquals(float a, float b) {
        return Float.floatToIntBits(a) == Float.floatToIntBits(b);
    }

    static void requireFinite(float v, String what) {
        if (!Float.isFinite(v)) {
            throw new FixtureRejected(Reason.NON_FINITE, what + " is not finite: " + v);
        }
    }

    enum Reason {
        NON_FINITE,
        NON_FINITE_KEY,
        DUPLICATE_KEY,
        UNBOUND_DIMENSION,
        DUPLICATE_DIMENSION,
        INCOMPLETE_TUPLE,
        KEY_NOT_DECLARED,
        CELL_NOT_FOUND,
        FORM_MISSING,
        MISSING_DEFAULT_KEY,
        STALE_GENERATION,
        WRONG_OWNER,
        BAD_TOPOLOGY,
        NULL_KEY_VALUE,
        NULL_FORM_GUID,
        DUPLICATE_MORPH_IDENTITY,
        MORPH_NOT_FOUND,
        INVALID_RANGE,
        DUPLICATE_CELL,
        INCONSISTENT_DEFAULT
    }

    static final class FixtureRejected extends RuntimeException {
        final Reason reason;
        FixtureRejected(Reason reason, String detail) {
            super(detail);
            this.reason = reason;
        }
    }

    /** Synthetic scope identity; never a real host/session identity. */
    record Scope(String sessionId, String documentId, String modelId,
                 long generation, String ownerId) {
        Scope {
            Objects.requireNonNull(sessionId);
            Objects.requireNonNull(documentId);
            Objects.requireNonNull(modelId);
            Objects.requireNonNull(ownerId);
        }
    }

    /** One bound ordinary parameter: declared key list plus declared default. */
    record Binding(String paramId, String paramGuid, List<Float> keys,
                   float minValue, float maxValue, float defaultValue) {
        Binding {
            Objects.requireNonNull(paramId);
            Objects.requireNonNull(paramGuid);
            keys = List.copyOf(keys);
            if (keys.isEmpty()) {
                throw new FixtureRejected(Reason.INCOMPLETE_TUPLE,
                        "binding " + paramId + " has no keys");
            }
            requireFinite(minValue, "minValue");
            requireFinite(maxValue, "maxValue");
            requireFinite(defaultValue, "defaultValue");
            if (minValue > maxValue) {
                throw new FixtureRejected(Reason.INVALID_RANGE,
                        "binding " + paramId + " min " + minValue
                                + " > max " + maxValue);
            }
            if (defaultValue < minValue || defaultValue > maxValue) {
                throw new FixtureRejected(Reason.INVALID_RANGE,
                        "binding " + paramId + " defaultValue " + defaultValue
                                + " outside [" + minValue + "," + maxValue + "]");
            }
            for (int i = 0; i < keys.size(); i++) {
                float k = keys.get(i);
                if (!Float.isFinite(k)) {
                    throw new FixtureRejected(Reason.NON_FINITE_KEY,
                            "binding " + paramId + " key[" + i + "] not finite");
                }
                if (k < minValue || k > maxValue) {
                    throw new FixtureRejected(Reason.INVALID_RANGE,
                            "binding " + paramId + " key " + k
                                    + " outside [" + minValue + "," + maxValue + "]");
                }
                for (int j = i + 1; j < keys.size(); j++) {
                    if (keyEquals(k, keys.get(j))) {
                        throw new FixtureRejected(Reason.DUPLICATE_KEY,
                                "binding " + paramId + " duplicate key " + k);
                    }
                }
            }
        }

        /** Index of a declared key by bit-exact match, or -1. */
        int indexOfKey(float value) {
            for (int i = 0; i < keys.size(); i++) {
                if (keyEquals(keys.get(i), value)) return i;
            }
            return -1;
        }
    }

    /** One axis of a tuple, normalized to parameter identity (guid). */
    record KeyPoint(String paramGuid, float value) implements Comparable<KeyPoint> {
        KeyPoint {
            Objects.requireNonNull(paramGuid);
            requireFinite(value, "key value");
        }
        @Override public int compareTo(KeyPoint o) {
            return paramGuid.compareTo(o.paramGuid);
        }
    }

    /** Normalized full tuple: sorted by paramGuid, no duplicate dimension. */
    record CellKey(List<KeyPoint> points) {
        CellKey {
            List<KeyPoint> sorted = new ArrayList<>(points);
            Collections.sort(sorted);
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i - 1).paramGuid()
                        .equals(sorted.get(i).paramGuid())) {
                    throw new FixtureRejected(Reason.DUPLICATE_DIMENSION,
                            "duplicate dimension " + sorted.get(i).paramGuid()
                                    + " inside tuple");
                }
            }
            points = List.copyOf(sorted);
        }
    }

    /** One tuple -> formGuid association entry, before map conversion. */
    record CellEntry(CellKey key, String formGuid) {
        CellEntry {
            Objects.requireNonNull(key);
            if (formGuid == null || formGuid.isEmpty()) {
                throw new FixtureRejected(Reason.NULL_FORM_GUID,
                        "cell " + key + " has null/empty formGuid");
            }
        }
    }

    /**
     * Explicit tuple -> formGuid association list; no native grid engine
     * implied. The list is validated for duplicate tuples, bound-parameter
     * coverage, and declared-key membership before it becomes a map.
     */
    record Grid(List<Binding> bindings, List<CellEntry> cellList) {
        Grid {
            bindings = List.copyOf(bindings);
            cellList = List.copyOf(cellList);
            for (int i = 0; i < bindings.size(); i++) {
                for (int j = i + 1; j < bindings.size(); j++) {
                    Binding a = bindings.get(i), b = bindings.get(j);
                    if (a.paramGuid().equals(b.paramGuid())
                            || a.paramId().equals(b.paramId())
                            || a.paramGuid().equals(b.paramId())
                            || a.paramId().equals(b.paramGuid())) {
                        throw new FixtureRejected(Reason.DUPLICATE_DIMENSION,
                                "duplicate or ambiguous bound parameter " + a.paramId()
                                        + "/" + b.paramId());
                    }
                }
            }
            Map<String, Binding> byGuid = new LinkedHashMap<>();
            for (Binding b : bindings) byGuid.put(b.paramGuid(), b);
            Map<CellKey, String> seen = new LinkedHashMap<>();
            for (CellEntry e : cellList) {
                if (seen.putIfAbsent(e.key(), e.formGuid()) != null) {
                    throw new FixtureRejected(Reason.DUPLICATE_CELL,
                            "duplicate cell tuple " + e.key());
                }
                CellKey k = e.key();
                if (k.points().size() != bindings.size()) {
                    throw new FixtureRejected(Reason.INCOMPLETE_TUPLE,
                            "cell arity " + k.points().size()
                                    + " != bound dims " + bindings.size());
                }
                for (KeyPoint p : k.points()) {
                    Binding b = byGuid.get(p.paramGuid());
                    if (b == null) {
                        throw new FixtureRejected(Reason.UNBOUND_DIMENSION,
                                "cell references unbound " + p.paramGuid());
                    }
                    if (b.indexOfKey(p.value()) < 0) {
                        throw new FixtureRejected(Reason.KEY_NOT_DECLARED,
                                "cell value " + p.value()
                                        + " not a declared key of "
                                        + b.paramId());
                    }
                }
            }
        }

        /** Lookup by normalized tuple. */
        String formGuidAt(CellKey key) {
            for (CellEntry e : cellList) {
                if (e.key().equals(key)) return e.formGuid();
            }
            return null;
        }
    }

    /**
     * One morph-target record. keyValue may be null (observation only — never a
     * writable or exact-locatable identity). basicIdentity is always UNKNOWN:
     * explicit 0.0f is not classified as the base key.
     */
    record MorphRecord(String paramGuid, Float keyValue, String keyformGuid) {
        MorphRecord {
            Objects.requireNonNull(paramGuid);
            if (keyValue != null && !Float.isFinite(keyValue)) {
                throw new FixtureRejected(Reason.NON_FINITE,
                        "morph keyValue not finite");
            }
        }
        String basicIdentity() { return "UNKNOWN"; }
    }

    record MorphSet(List<MorphRecord> records) {
        MorphSet {
            records = List.copyOf(records);
            for (int i = 0; i < records.size(); i++) {
                MorphRecord a = records.get(i);
                for (int j = i + 1; j < records.size(); j++) {
                    MorphRecord b = records.get(j);
                    boolean sameParam = a.paramGuid().equals(b.paramGuid());
                    boolean sameKey = a.keyValue() == null
                            ? b.keyValue() == null
                            : b.keyValue() != null
                                    && keyEquals(a.keyValue(), b.keyValue());
                    if (sameParam && sameKey) {
                        throw new FixtureRejected(Reason.DUPLICATE_MORPH_IDENTITY,
                                "duplicate morph identity " + a.paramGuid()
                                        + "/" + a.keyValue());
                    }
                }
            }
        }
    }

    /**
     * Immutable per-domain snapshot. There is no digest-injecting constructor:
     * the single private canonical constructor clones all array inputs and
     * computes the digest from the cloned content, so even reflective callers
     * cannot produce an inconsistent snapshot. Public getters return fresh
     * copies; no mutable reference leaks in either direction.
     */
    static final class DomainSnapshot {
        private final String domain;
        private final float[] positions;
        private final float[] uvs;
        private final int[] indices;
        private final Integer drawOrder;
        private final String digest;

        private DomainSnapshot(String domain, float[] positions, float[] uvs,
                               int[] indices, Integer drawOrder) {
            this.domain = Objects.requireNonNull(domain);
            this.positions = positions == null ? null : positions.clone();
            this.uvs = uvs == null ? null : uvs.clone();
            this.indices = indices == null ? null : indices.clone();
            this.drawOrder = drawOrder;
            this.digest = Digest.of(domain, this.positions, this.uvs,
                    this.indices, drawOrder);
        }

        static DomainSnapshot of(String domain, float[] positions, float[] uvs,
                                 int[] indices, Integer drawOrder) {
            return new DomainSnapshot(domain, positions, uvs, indices,
                    drawOrder);
        }

        String domain() { return domain; }
        Integer drawOrder() { return drawOrder; }
        String digest() { return digest; }
        float[] positions() { return positions == null ? null : positions.clone(); }
        float[] uvs() { return uvs == null ? null : uvs.clone(); }
        int[] indices() { return indices == null ? null : indices.clone(); }
    }

    /** All forms of one object, keyed by formGuid, each an immutable snapshot. */
    record Forms(Map<String, DomainSnapshot> byGuid) {
        Forms { byGuid = Map.copyOf(byGuid); }
    }

    /**
     * Whole synthetic fixture for one object. Construction validates
     * form-vs-source topology (same vertex arity, finite coordinates) and —
     * when the declared-default tuple is locatable — that it resolves to
     * defaultFormGuid. Missing cells/forms remain locate-time rejections so
     * faulted fixtures can still demonstrate them.
     */
    record Fixture(Scope scope, String objectId, SourceGeometry source,
                   Grid grid, MorphSet morphs, Forms forms,
                   String defaultFormGuid) {
        Fixture {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(objectId);
            Objects.requireNonNull(source);
            Objects.requireNonNull(grid);
            Objects.requireNonNull(morphs);
            Objects.requireNonNull(forms);
            Objects.requireNonNull(defaultFormGuid);
            int arity = source.positions().positions().length;
            for (Map.Entry<String, DomainSnapshot> e
                    : forms.byGuid().entrySet()) {
                DomainSnapshot snap = e.getValue();
                float[] p = snap.positions();
                if (p == null || p.length != arity) {
                    throw new FixtureRejected(Reason.BAD_TOPOLOGY,
                            "form " + e.getKey() + " positions arity "
                                    + (p == null ? -1 : p.length)
                                    + " != source " + arity);
                }
                for (float v : p) {
                    if (!Float.isFinite(v)) {
                        throw new FixtureRejected(Reason.NON_FINITE,
                                "form " + e.getKey() + " non-finite position");
                    }
                }
            }
            CellKey defaultKey = new CellKey(grid.bindings().stream()
                    .map(b -> new KeyPoint(b.paramGuid(), b.defaultValue())).toList());
            String located = grid.formGuidAt(defaultKey);
            // Deliberately missing cells/forms remain locator-negative fixtures.
            if (located != null && forms.byGuid().containsKey(located)
                    && !located.equals(defaultFormGuid)) {
                throw new FixtureRejected(Reason.INCONSISTENT_DEFAULT,
                        "default tuple -> " + located + " but declared " + defaultFormGuid);
            }
        }
    }

    /** Source-level geometry domain (positions/UVs/indices), never a form. */
    record SourceGeometry(DomainSnapshot positions, DomainSnapshot uvs,
                          DomainSnapshot indices) {
        SourceGeometry {
            Objects.requireNonNull(positions);
            Objects.requireNonNull(uvs);
            Objects.requireNonNull(indices);
            float[] p = positions.positions();
            float[] u = uvs.uvs();
            int[] ix = indices.indices();
            if (p == null || p.length % 2 != 0 || p.length == 0) {
                throw new FixtureRejected(Reason.BAD_TOPOLOGY,
                        "positions missing or odd/zero length");
            }
            for (float v : p) {
                if (!Float.isFinite(v)) {
                    throw new FixtureRejected(Reason.NON_FINITE,
                            "source position not finite");
                }
            }
            if (u == null || u.length != p.length) {
                throw new FixtureRejected(Reason.BAD_TOPOLOGY,
                        "uvs length " + (u == null ? -1 : u.length)
                                + " != positions length " + p.length);
            }
            for (float v : u) {
                if (!Float.isFinite(v)) {
                    throw new FixtureRejected(Reason.NON_FINITE,
                            "source uv not finite");
                }
            }
            if (ix == null || ix.length % 3 != 0 || ix.length == 0) {
                throw new FixtureRejected(Reason.BAD_TOPOLOGY,
                        "indices missing or not multiple of 3");
            }
            int verts = p.length / 2;
            for (int t : ix) {
                if (t < 0 || t >= verts) {
                    throw new FixtureRejected(Reason.BAD_TOPOLOGY,
                            "index " + t + " out of range " + verts);
                }
            }
        }
    }
}
